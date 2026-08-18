package com.lk.studyassistant.quantum.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.lk.studyassistant.quantum.util.AppLogger
import com.lk.studyassistant.quantum.util.QuestionExtractResult
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

class MaterialRepository(private val context: Context) {
    private val dbHelper = LocalDatabase(context.applicationContext)

    data class ImportReport(
        val success: Boolean,
        val chunkCount: Int,
        val failedChunks: Int,
        val materialName: String,
        val failureReasons: List<String>
    )

    fun countMaterials(): Int {
        dbHelper.readableDatabase.rawQuery("SELECT COUNT(*) FROM local_material", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clearAll(): Int {
        val db = dbHelper.writableDatabase
        val count = countChunks()
        try { db.delete("local_material_chunk_fts", null, null) } catch (_: Exception) { }
        try { db.delete("local_material_chunk", null, null) } catch (_: Exception) { }
        try { db.delete("local_material", null, null) } catch (_: Exception) { }
        AppLogger.log("[Material] cleared chunk_count=$count")
        return count
    }

    data class MaterialSummary(
        val id: Long,
        val name: String,
        val sourceFile: String,
        val chunkCount: Int,
        val createdAt: Long
    )

    fun listMaterials(): List<MaterialSummary> {
        val db = dbHelper.readableDatabase
        val rows = mutableListOf<MaterialSummary>()
        db.rawQuery(
            """
            SELECT m.id, m.name, COALESCE(m.source_file,''), m.created_at,
                   (SELECT COUNT(*) FROM local_material_chunk c WHERE c.material_id = m.id)
            FROM local_material m
            ORDER BY m.created_at DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    MaterialSummary(
                        id = cursor.getLong(0),
                        name = cursor.getString(1).orEmpty(),
                        sourceFile = cursor.getString(2).orEmpty(),
                        createdAt = cursor.getLong(3),
                        chunkCount = cursor.getInt(4)
                    )
                )
            }
        }
        return rows
    }

    fun deleteMaterial(materialId: Long): Int {
        val db = dbHelper.writableDatabase
        var chunkDeleted = 0
        db.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            db.rawQuery(
                "SELECT id FROM local_material_chunk WHERE material_id = ?",
                arrayOf(materialId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
            for (id in ids) {
                runCatching {
                    db.delete("local_material_chunk_fts", "docid = ?", arrayOf(id.toString()))
                }
            }
            chunkDeleted = db.delete(
                "local_material_chunk",
                "material_id = ?",
                arrayOf(materialId.toString())
            )
            db.delete("local_material", "id = ?", arrayOf(materialId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AppLogger.log("[Material] delete material_id=$materialId chunks=$chunkDeleted")
        return chunkDeleted
    }

    fun countChunks(): Int {
        dbHelper.readableDatabase.rawQuery("SELECT COUNT(*) FROM local_material_chunk", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun importMaterial(uri: Uri, displayName: String, materialName: String): Int =
        importMaterialWithReport(uri, displayName, materialName).chunkCount

    fun importMaterialWithReport(uri: Uri, displayName: String, materialName: String): ImportReport {
        AppLogger.log("[Import] material_parse_start file=$displayName")
        val lower = displayName.lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: run {
            return ImportReport(false, 0, 0, materialName, listOf("无法读取文件"))
        }

        val text = when {
            lower.endsWith(".txt") || lower.endsWith(".md") -> bytes.toString(Charsets.UTF_8)
            lower.endsWith(".docx") -> extractDocxText(bytes)
            lower.endsWith(".xlsx") -> extractXlsxText(bytes)
            lower.endsWith(".pdf") -> extractPdfText(bytes)
            else -> return ImportReport(false, 0, 0, materialName, listOf("不支持的文件格式：$lower"))
        }

        val clean = cleanMaterialText(text)
        if (clean.length < 20) {
            return ImportReport(false, 0, 0, materialName, listOf("文本内容过短（${clean.length}字符）"))
        }

        val chunks = chunk(clean)
        if (chunks.isEmpty()) {
            return ImportReport(false, 0, 0, materialName, listOf("切块结果为空"))
        }

        val name = materialName.ifBlank { displayName.substringBeforeLast('.') }
        val db = dbHelper.writableDatabase
        var chunkCount = 0
        val failures = mutableListOf<String>()
        db.beginTransaction()
        try {
            val materialId = db.insert("local_material", null, ContentValues().apply {
                put("name", name)
                put("source_file", displayName)
                put("created_at", System.currentTimeMillis())
            })
            chunks.forEachIndexed { index, chunk ->
                val keywords = keywords(chunk)
                val id = db.insert("local_material_chunk", null, ContentValues().apply {
                    put("material_id", materialId)
                    put("material_name", name)
                    put("chapter_path", detectChapter(chunk))
                    putNull("page_no")
                    put("paragraph_index", index)
                    put("raw_text", chunk)
                    put("clean_text", TextNormalizer.normalize(chunk))
                    put("keywords", keywords)
                    put("source_file", displayName)
                    put("created_at", System.currentTimeMillis())
                })
                if (id > 0) {
                    db.insert("local_material_chunk_fts", null, ContentValues().apply {
                        put("docid", id)
                        put("clean_text", TextNormalizer.normalize(chunk))
                        put("keywords", keywords)
                    })
                    chunkCount++
                } else {
                    failures.add("块${index + 1}: 数据库插入失败")
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            failures.add("事务异常: ${e.message}")
        } finally {
            db.endTransaction()
        }

        AppLogger.log("[Import] material_insert_success chunk_count=$chunkCount failures=${failures.size}")
        return ImportReport(
            success = chunkCount > 0,
            chunkCount = chunkCount,
            failedChunks = chunks.size - chunkCount,
            materialName = name,
            failureReasons = failures
        )
    }

    fun search(question: QuestionExtractResult, topK: Int = 20): List<MaterialChunk> {
        val query = TextNormalizer.normalize(question.questionText + " " + TextNormalizer.optionText(question.options))
        val terms = query.split(' ').filter { it.length >= 2 }.take(16)
        if (terms.isEmpty()) return emptyList()
        val ftsQuery = terms.joinToString(" OR ")
        val sql = """
            SELECT c.id, c.material_name, c.chapter_path, c.raw_text, c.clean_text
            FROM local_material_chunk c
            JOIN local_material_chunk_fts f ON c.id = f.docid
            WHERE local_material_chunk_fts MATCH ?
            LIMIT 80
        """.trimIndent()
        val results = mutableListOf<MaterialChunk>()
        dbHelper.readableDatabase.rawQuery(sql, arrayOf(ftsQuery)).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(3).orEmpty()
                val clean = cursor.getString(4).orEmpty()
                results.add(MaterialChunk(
                    id = cursor.getLong(0),
                    materialName = cursor.getString(1).orEmpty(),
                    chapterPath = cursor.getString(2).orEmpty(),
                    cleanText = raw,
                    score = score(terms, clean)
                ))
            }
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    private fun extractText(uri: Uri, displayName: String): String {
        val lower = displayName.lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") -> bytes.toString(Charsets.UTF_8)
            lower.endsWith(".docx") -> extractDocxText(bytes)
            lower.endsWith(".xlsx") -> extractXlsxText(bytes)
            lower.endsWith(".pdf") -> extractPdfText(bytes)
            else -> ""
        }
    }

    private fun extractDocxText(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return xml
                        .replace(Regex("<w:p[^>]*>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                        .trim()
                }
                zip.closeEntry()
            }
        }
        return ""
    }

    private fun extractXlsxText(bytes: ByteArray): String {
        val workbook = runCatching {
            com.lk.studyassistant.quantum.local.XlsxReader.readWorkbook(bytes)
        }.getOrNull() ?: return ""

        return workbook.sheets.joinToString("\n\n") { sheet ->
            "【${sheet.name}】\n" + sheet.rows.joinToString("\n") { row ->
                row.filter { it.isNotBlank() }.joinToString(" | ")
            }
        }
    }

    private fun extractPdfText(bytes: ByteArray): String {
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val streams = Regex("stream\\r?\\n(.*?)\\r?\\nendstream", RegexOption.DOT_MATCHES_ALL)
            .findAll(raw).map { it.groupValues[1] }.toList()
        val decoded = streams.joinToString("\n") { stream ->
            inflateStream(stream).ifBlank { stream }
        }
        val literal = Regex("\\((?:\\\\.|[^\\\\)])*\\)")
            .findAll(decoded).map { decodePdfLiteral(it.value.drop(1).dropLast(1)) }
        val hex = Regex("<([0-9A-Fa-f]{4,})>")
            .findAll(decoded).map { decodePdfHex(it.groupValues[1]) }
        return (literal + hex).joinToString("\n")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun inflateStream(stream: String): String = runCatching {
        InflaterInputStream(ByteArrayInputStream(stream.toByteArray(Charsets.ISO_8859_1)))
            .readBytes().toString(Charsets.ISO_8859_1)
    }.getOrDefault("")

    private fun decodePdfLiteral(value: String): String = value
        .replace("\\n", "\n").replace("\\r", "\n").replace("\\t", " ")
        .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")

    private fun decodePdfHex(hex: String): String {
        val clean = if (hex.length % 2 == 0) hex else "${hex}0"
        val bytes = clean.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (bytes.isEmpty()) return ""
        return runCatching { bytes.toString(Charsets.UTF_16BE).trim(' ') }
            .getOrElse { bytes.toString(Charsets.UTF_8) }
    }

    private fun cleanMaterialText(text: String): String = text.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    private fun chunk(text: String): List<String> {
        val paragraphs = text.split(Regex("\n{1,}")).map { it.trim() }.filter { it.length >= 8 }
        val chunks = mutableListOf<String>()
        val window = StringBuilder()
        for (p in paragraphs) {
            if (window.length + p.length > 900 && window.isNotBlank()) {
                chunks.add(window.toString())
                val tail = window.takeLast(120)
                window.clear()
                window.append(tail).append('\n')
            }
            window.append(p).append('\n')
        }
        if (window.isNotBlank()) chunks.add(window.toString())
        return chunks.filter { it.length >= 20 }
    }

    private fun keywords(text: String): String = TextNormalizer.normalize(text)
        .split(' ').filter { it.length >= 2 }
        .groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.take(24)
        .joinToString(" ") { it.key }

    private fun detectChapter(text: String): String = text.lines().firstOrNull { line ->
        line.length in 4..40 && (line.contains("章") || line.contains("节") || line.contains("单元"))
    }.orEmpty()

    private fun score(terms: List<String>, cleanText: String): Double {
        if (terms.isEmpty()) return 0.0
        var hit = 0
        for (term in terms) if (cleanText.contains(term)) hit++
        return hit.toDouble() / terms.size
    }
}

data class MaterialChunk(
    val id: Long,
    val materialName: String,
    val chapterPath: String,
    val cleanText: String,
    val score: Double
)
