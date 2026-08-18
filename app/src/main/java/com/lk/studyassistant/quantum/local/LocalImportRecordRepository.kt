package com.lk.studyassistant.quantum.local

import android.content.ContentValues
import android.content.Context

data class LocalImportRecord(
    val id: Long,
    val type: String,
    val fileName: String,
    val displayName: String,
    val fileSize: Long,
    val status: String,
    val errorCode: String,
    val detail: String,
    val totalRows: Int,
    val importedCount: Int,
    val failedCount: Int,
    val singleCount: Int,
    val multipleCount: Int,
    val judgeCount: Int,
    val blankCount: Int,
    val unsupportedCount: Int,
    val chunkCount: Int,
    val enabled: Boolean,
    val createdAt: Long
)

class LocalImportRecordRepository(context: Context) {
    private val dbHelper = LocalDatabase(context.applicationContext)

    fun save(record: LocalImportRecord) {
        dbHelper.writableDatabase.insert("local_import_record", null, ContentValues().apply {
            put("type", record.type)
            put("file_name", record.fileName)
            put("display_name", record.displayName)
            put("file_size", record.fileSize)
            put("status", record.status)
            put("error_code", record.errorCode)
            put("detail", record.detail)
            put("total_rows", record.totalRows)
            put("imported_count", record.importedCount)
            put("failed_count", record.failedCount)
            put("single_count", record.singleCount)
            put("multiple_count", record.multipleCount)
            put("judge_count", record.judgeCount)
            put("blank_count", record.blankCount)
            put("unsupported_count", record.unsupportedCount)
            put("chunk_count", record.chunkCount)
            put("enabled", if (record.enabled) 1 else 0)
            put("created_at", record.createdAt)
        })
    }

    fun count(type: String): Int {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM local_import_record WHERE type=?",
            arrayOf(type)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun list(type: String, limit: Int = 20): List<LocalImportRecord> {
        val records = mutableListOf<LocalImportRecord>()
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT id, type, file_name, display_name, file_size, status, error_code, detail,
                   total_rows, imported_count, failed_count, single_count, multiple_count,
                   judge_count, blank_count, unsupported_count, chunk_count, enabled, created_at
            FROM local_import_record
            WHERE type=?
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(type, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    LocalImportRecord(
                        id = cursor.getLong(0),
                        type = cursor.getString(1).orEmpty(),
                        fileName = cursor.getString(2).orEmpty(),
                        displayName = cursor.getString(3).orEmpty(),
                        fileSize = cursor.getLong(4),
                        status = cursor.getString(5).orEmpty(),
                        errorCode = cursor.getString(6).orEmpty(),
                        detail = cursor.getString(7).orEmpty(),
                        totalRows = cursor.getInt(8),
                        importedCount = cursor.getInt(9),
                        failedCount = cursor.getInt(10),
                        singleCount = cursor.getInt(11),
                        multipleCount = cursor.getInt(12),
                        judgeCount = cursor.getInt(13),
                        blankCount = cursor.getInt(14),
                        unsupportedCount = cursor.getInt(15),
                        chunkCount = cursor.getInt(16),
                        enabled = cursor.getInt(17) == 1,
                        createdAt = cursor.getLong(18)
                    )
                )
            }
        }
        return records
    }

    fun formatRecords(type: String): String {
        val records = list(type)
        if (records.isEmpty()) {
            return when (type) {
                TYPE_QUESTION_BANK -> "暂无精准题库导入记录"
                TYPE_MATERIAL -> "暂无模糊匹配资料导入记录"
                TYPE_MODEL -> "暂不支持本地模型导入"
                else -> "暂无导入记录"
            }
        }
        return records.joinToString("\n\n") { record ->
            when (type) {
                TYPE_QUESTION_BANK -> buildString {
                    append("文件名：${record.fileName}\n")
                    append("导入状态：${record.status}")
                    if (record.errorCode.isNotBlank()) append("（${record.errorCode}）")
                    append("\n")
                    append("总行数：${record.totalRows}，成功：${record.importedCount}，失败：${record.failedCount}\n")
                    append("单选：${record.singleCount}，多选：${record.multipleCount}，判断：${record.judgeCount}，填空：${record.blankCount}，不支持：${record.unsupportedCount}\n")
                    append("状态：${if (record.enabled) "启用中" else "已停用"}")
                    if (record.detail.isNotBlank()) append("\n详情：${record.detail.take(240)}")
                }
                TYPE_MATERIAL -> buildString {
                    append("文件名：${record.fileName}\n")
                    append("资料名称：${record.displayName.ifBlank { record.fileName }}\n")
                    append("导入状态：${record.status}")
                    if (record.errorCode.isNotBlank()) append("（${record.errorCode}）")
                    append("\n")
                    append("切块数量：${record.chunkCount}\n")
                    append("状态：${if (record.enabled) "启用中" else "已停用"}")
                    if (record.detail.isNotBlank()) append("\n详情：${record.detail.take(240)}")
                }
                TYPE_MODEL -> buildString {
                    append("文件名：${record.fileName}\n")
                    append("导入状态：${record.status}")
                    if (record.errorCode.isNotBlank()) append("（${record.errorCode}）")
                    append("\n")
                    append("模型大小：${formatBytes(record.fileSize)}\n")
                    append("推理状态：MODEL_ENGINE_NOT_READY")
                    if (record.detail.isNotBlank()) append("\n详情：${record.detail.take(240)}")
                }
                else -> "${record.fileName}: ${record.status}"
            }
        }
    }

    companion object {
        const val TYPE_QUESTION_BANK = "question_bank"
        const val TYPE_MATERIAL = "material"
        const val TYPE_MODEL = "model"

        fun newRecord(
            type: String,
            fileName: String,
            displayName: String = "",
            fileSize: Long = 0L,
            status: String,
            errorCode: String = "",
            detail: String = "",
            totalRows: Int = 0,
            importedCount: Int = 0,
            failedCount: Int = 0,
            singleCount: Int = 0,
            multipleCount: Int = 0,
            judgeCount: Int = 0,
            blankCount: Int = 0,
            unsupportedCount: Int = 0,
            chunkCount: Int = 0
        ): LocalImportRecord {
            return LocalImportRecord(
                id = 0L,
                type = type,
                fileName = fileName,
                displayName = displayName,
                fileSize = fileSize,
                status = status,
                errorCode = errorCode,
                detail = detail,
                totalRows = totalRows,
                importedCount = importedCount,
                failedCount = failedCount,
                singleCount = singleCount,
                multipleCount = multipleCount,
                judgeCount = judgeCount,
                blankCount = blankCount,
                unsupportedCount = unsupportedCount,
                chunkCount = chunkCount,
                enabled = status == "success",
                createdAt = System.currentTimeMillis()
            )
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            return "%.2f MB".format(kb / 1024.0)
        }
    }
}
