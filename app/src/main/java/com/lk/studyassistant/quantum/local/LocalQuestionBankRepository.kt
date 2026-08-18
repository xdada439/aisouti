package com.lk.studyassistant.quantum.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.lk.studyassistant.quantum.util.AppLogger
import com.lk.studyassistant.quantum.util.OptionItem
import com.lk.studyassistant.quantum.util.QuestionExtractResult
import com.lk.studyassistant.quantum.util.QuestionType
import java.util.zip.ZipInputStream
import kotlin.math.max

class LocalQuestionBankRepository(private val context: Context) {
    private val dbHelper = LocalDatabase(context.applicationContext)

    data class ImportReport(
        val status: String,
        val errorCode: String,
        val totalRows: Int,
        val imported: Int,
        val singleCount: Int,
        val multipleCount: Int,
        val judgeCount: Int,
        val blankCount: Int,
        val unsupportedCount: Int,
        val failedRows: Int,
        val failureReasons: List<String>,
        val sheetNames: List<String>,
        val headers: List<String>
    ) {
        fun toDisplayText(): String = buildString {
            append(if (status == "success") "导入成功：$imported 题" else "导入失败：$errorCode")
            append("\n总行数：$totalRows")
            append("\n成功：$imported  失败：$failedRows")
            append("\n单选：$singleCount  多选：$multipleCount  判断：$judgeCount  填空：$blankCount  不支持：$unsupportedCount")
            if (sheetNames.isNotEmpty()) append("\n工作表：${sheetNames.joinToString(", ")}")
            if (headers.isNotEmpty()) append("\n表头：${headers.joinToString(", ")}")
            if (failureReasons.isNotEmpty()) {
                append("\n失败原因：\n")
                append(failureReasons.take(12).joinToString("\n"))
            }
        }
    }

    fun importExcel(uri: Uri, sourceName: String): Int = importExcelWithReport(uri, sourceName).imported

    fun importExcelWithReport(uri: Uri, sourceName: String): ImportReport {
        val report = importExcelWithReportInternal(uri, sourceName)
        // 持久化最近 5 次导入报告，让用户在「题库诊断」里能看到失败行明细
        runCatching { ImportReportStore.append(context, sourceName, report) }
            .onFailure { AppLogger.log("[ImportReportStore] save_failed err=${it.message?.take(80)}") }
        return report
    }

    private fun importExcelWithReportInternal(uri: Uri, sourceName: String): ImportReport {
        AppLogger.log("[Import] question_bank_parse_start")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return failedReport("EXCEL_OPEN_FAILED", "无法打开 Excel 文件")

        val workbook = runCatching { XlsxReader.readWorkbook(bytes) }
            .getOrElse { error ->
                AppLogger.log("[Import] failed EXCEL_OPEN_FAILED ${error.message.orEmpty()}")
                return failedReport("EXCEL_OPEN_FAILED", error.message.orEmpty())
            }
        AppLogger.log("[Import] workbook_sheets_count=${workbook.sheets.size}")
        workbook.sheets.forEach { sheet -> AppLogger.log("[Import] sheet_name=${sheet.name} rows=${sheet.rows.size}") }
        if (workbook.sheets.isEmpty()) return failedReport("WORKBOOK_EMPTY", "工作表为空")

        val sheet = workbook.sheets.firstOrNull { sheet ->
            sheet.rows.any { row -> row.any { cell -> cell.isNotBlank() } }
        } ?: return failedReport("SHEET_NOT_FOUND", "未找到包含数据的工作表", workbook.sheets.map { it.name })
        AppLogger.log("[Import] selected_sheet_name=${sheet.name}")

        val rawHeaders = sheet.rows.firstOrNull()?.map { it.trim() }.orEmpty()
        if (rawHeaders.isEmpty() || rawHeaders.all { it.isBlank() }) {
            return failedReport("HEADER_MISSING", "未找到表头行", workbook.sheets.map { it.name }, rawHeaders)
        }
        val headers = rawHeaders.map { normalizeHeader(it) }
        AppLogger.log("[Import] header_columns=${rawHeaders.joinToString("|")}")
        AppLogger.log("[Import] header_map ${buildHeaderMapDebug(headers)}")

        val rows = sheet.rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        AppLogger.log("[Import] rows_total=${rows.size}")

        val missing = mutableListOf<String>()
        if (!hasAnyHeader(headers, HEADER_STEM)) missing.add("题干")
        if (!hasAnyHeader(headers, HEADER_ANSWER)) missing.add("正确答案")
        if (missing.isNotEmpty()) {
            return failedReport(
                code = "REQUIRED_COLUMN_MISSING",
                reason = "缺少必填列：${missing.joinToString(", ")}",
                sheetNames = workbook.sheets.map { it.name },
                headers = rawHeaders
            )
        }

        val db = dbHelper.writableDatabase
        var imported = 0
        var single = 0
        var multiple = 0
        var judge = 0
        var blank = 0
        var unsupported = 0
        val failures = mutableListOf<String>()

        AppLogger.log("[DB] question_bank_insert_start count=${rows.size}")
        db.beginTransaction()
        try {
            rows.forEachIndexed { index, row ->
                val rowNo = index + 2
                val mapped = mapRow(headers, row)
                if (mapped == null) {
                    failures.add("第${rowNo}行：行解析失败（${rowDiagnostic(headers, row)}）")
                    return@forEachIndexed
                }
                if (mapped.typeKey == "unknown") {
                    unsupported++
                    failures.add("第${rowNo}行：题型不支持")
                    return@forEachIndexed
                }

                val optionsText = listOf(
                    mapped.optionA, mapped.optionB, mapped.optionC, mapped.optionD,
                    mapped.optionE, mapped.optionF, mapped.optionG, mapped.optionH
                ).joinToString(" ")
                val normalizedStem = TextNormalizer.normalize(mapped.stem)
                val normalizedOptions = TextNormalizer.normalize(optionsText)
                val normalizedFullText = TextNormalizer.normalize(mapped.stem + " " + optionsText)
                val id = db.insert("local_question_bank", null, ContentValues().apply {
                    put("question_type", mapped.typeKey)
                    put("stem", mapped.stem)
                    put("option_a", mapped.optionA)
                    put("option_b", mapped.optionB)
                    put("option_c", mapped.optionC)
                    put("option_d", mapped.optionD)
                    put("option_e", mapped.optionE)
                    put("option_f", mapped.optionF)
                    put("option_g", mapped.optionG)
                    put("option_h", mapped.optionH)
                    put("answer", mapped.answer)
                    put("source_file", sourceName)
                    put("normalized_stem", normalizedStem)
                    put("normalized_options", normalizedOptions)
                    put("normalized_full_text", normalizedFullText)
                    put("created_at", System.currentTimeMillis())
                })
                if (id <= 0) {
                    failures.add("第${rowNo}行：数据库插入失败")
                    return@forEachIndexed
                }

                db.insert("local_question_bank_fts", null, ContentValues().apply {
                    put("docid", id)
                    put("stem", normalizedStem)
                    put("options", normalizedOptions)
                })

                imported++
                when (mapped.typeKey) {
                    "single" -> single++
                    "multiple" -> multiple++
                    "judge" -> judge++
                    "blank" -> blank++
                }
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            AppLogger.log("[DB] question_bank_insert_failed DB_INSERT_FAILED ${error.message.orEmpty()}")
            return failedReport("DB_INSERT_FAILED", error.message.orEmpty(), workbook.sheets.map { it.name }, rawHeaders)
        } finally {
            db.endTransaction()
        }

        val total = countQuestions()
        AppLogger.log("[Import] parsed_count=$imported")
        AppLogger.log("[Import] failed_count=${rows.size - imported}")
        AppLogger.log("[Import] single_count=$single")
        AppLogger.log("[Import] multiple_count=$multiple")
        AppLogger.log("[Import] judge_count=$judge")
        AppLogger.log("[Import] blank_count=$blank")
        AppLogger.log("[Import] unsupported_count=$unsupported")
        AppLogger.log("[DB] question_bank_insert_success count=$imported")
        AppLogger.log("[DB] question_bank_total_count=$total")

        return ImportReport(
            status = if (imported > 0) "success" else "failed",
            errorCode = if (imported > 0) "" else "ROW_PARSE_FAILED",
            totalRows = rows.size,
            imported = imported,
            singleCount = single,
            multipleCount = multiple,
            judgeCount = judge,
            blankCount = blank,
            unsupportedCount = unsupported,
            failedRows = rows.size - imported,
            failureReasons = failures,
            sheetNames = workbook.sheets.map { it.name },
            headers = rawHeaders
        )
    }

    fun clearAll(): Int {
        val db = dbHelper.writableDatabase
        val count = countQuestions()
        try { db.delete("local_question_bank_fts", null, null) } catch (_: Exception) { }
        try { db.delete("local_question_bank", null, null) } catch (_: Exception) { }
        AppLogger.log("[QuestionBank] cleared count=$count")
        return count
    }

    data class SourceFileSummary(
        val sourceFile: String,
        val count: Int,
        val latestCreatedAt: Long
    )

    /**
     * 1.1.11 新增：检查某个来源文件是否已经导入过。
     * 用于防重复导入提示。
     */
    fun hasSourceFile(sourceFile: String): Pair<Boolean, Int> {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM local_question_bank WHERE source_file = ?",
            arrayOf(sourceFile)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val count = cursor.getInt(0)
                return Pair(count > 0, count)
            }
        }
        return Pair(false, 0)
    }

    fun listSourceFiles(): List<SourceFileSummary> {
        val db = dbHelper.readableDatabase
        val rows = mutableListOf<SourceFileSummary>()
        db.rawQuery(
            "SELECT COALESCE(source_file,''), COUNT(*), MAX(created_at) " +
                "FROM local_question_bank " +
                "GROUP BY source_file " +
                "ORDER BY MAX(created_at) DESC",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    SourceFileSummary(
                        sourceFile = cursor.getString(0).orEmpty(),
                        count = cursor.getInt(1),
                        latestCreatedAt = cursor.getLong(2)
                    )
                )
            }
        }
        return rows
    }

    fun deleteBySourceFile(sourceFile: String): Int {
        val db = dbHelper.writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            val ids = mutableListOf<Long>()
            db.rawQuery(
                "SELECT id FROM local_question_bank WHERE source_file = ?",
                arrayOf(sourceFile)
            ).use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
            for (id in ids) {
                runCatching {
                    db.delete("local_question_bank_fts", "docid = ?", arrayOf(id.toString()))
                }
            }
            deleted = db.delete(
                "local_question_bank",
                "source_file = ?",
                arrayOf(sourceFile)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AppLogger.log("[QuestionBank] delete_by_source file=$sourceFile deleted=$deleted")
        return deleted
    }

    fun countQuestions(): Int {
        dbHelper.readableDatabase.rawQuery("SELECT COUNT(*) FROM local_question_bank", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * 按题型统计题库题数。返回 typeKey → count，含 single/multiple/judge/blank/其它。
     * 便于诊断"导入的题库是不是缺某类题型"（例如客户只导了单选，多选搜不到就合情合理）。
     */
    fun countByType(): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        // 初始化为 0，保证 UI 渲染顺序固定且零数也会显示
        listOf("single", "multiple", "judge", "blank", "unknown").forEach { out[it] = 0 }
        dbHelper.readableDatabase.rawQuery(
            "SELECT COALESCE(question_type, 'unknown'), COUNT(*) FROM local_question_bank GROUP BY question_type",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0).orEmpty().ifBlank { "unknown" }
                out[key] = cursor.getInt(1)
            }
        }
        return out
    }

    fun search(question: QuestionExtractResult): LocalSearchAnswer? = searchWithCandidates(question).first

    fun searchWithCandidates(question: QuestionExtractResult): Pair<LocalSearchAnswer?, List<QuestionCandidate>> {
        val total = countQuestions()
        // bank_status 诊断行（永久打）：每次搜题第一时间汇报题库当前状态
        val typeStats = countByType()
        AppLogger.log(
            "[QuestionBank] bank_status total=$total " +
                "single=${typeStats["single"] ?: 0} " +
                "multi=${typeStats["multiple"] ?: 0} " +
                "judge=${typeStats["judge"] ?: 0} " +
                "blank=${typeStats["blank"] ?: 0} " +
                "unknown=${typeStats["unknown"] ?: 0}"
        )
        AppLogger.log("[QuestionBank] total_count=$total")
        if (!TextNormalizer.supported(question.questionType)) {
            AppLogger.log("[QuestionBank] failed UNSUPPORTED_TYPE type=${question.questionType}")
            return Pair(null, emptyList())
        }

        // Bug D 修复：剥离 stem 开头的题号前缀（"10、xxx" / "(10) xxx" / "第10题 xxx"）
        // Vision API 提取的 stem 常带 "10、" 前缀，但 Excel 题库的 stem 通常不带
        // 不剥离会导致 Tier 1 第一个 4-char 窗口"10患儿" 匹配不到，召回率掉
        val strippedStem = stripLeadingQuestionNumber(question.questionText)
        if (strippedStem != question.questionText) {
            AppLogger.log("[QuestionBank] stripped_qno raw_len=${question.questionText.length} stripped_len=${strippedStem.length}")
        }

        val normalizedStem = TextNormalizer.normalize(strippedStem)
        val optionText = TextNormalizer.normalize(TextNormalizer.optionText(question.options))
        val normalizedFullText = TextNormalizer.normalize(strippedStem + " " + TextNormalizer.optionText(question.options))
        AppLogger.log("[QuestionBank] query_normalized_stem=${normalizedStem.take(300)}")
        AppLogger.log("[QuestionBank] query_normalized_options=${optionText.take(300)}")
        AppLogger.log("[QuestionBank] query_normalized_full_text=${normalizedFullText.take(300)}")

        if (normalizedStem.length < 4) return Pair(null, emptyList())

        // 判断查询来自哪里：Vision API 来的 stem/options 干净度高，启用专用评分
        val isVisionSource = question.source.contains("视觉") || question.source.contains("vision", ignoreCase = true)
        AppLogger.log("[QuestionBank] source=${question.source} isVision=$isVisionSource opts=${question.options.size}")

        // Tier 0（NEW）: 当 Vision API 提取出了 ≥2 个选项时，用选项内容召回候选
        // 选项是短的、唯一性高的术语，比题干 bigram 召回准得多
        val rows = loadCandidateRows(normalizedStem, normalizedFullText, question.options)
        val expectedType = TextNormalizer.typeKey(question.questionType)

        val scored = rows.map { row ->
            val typeBonus = when {
                expectedType == "unknown" -> 0.0
                row.typeKey == expectedType -> 0.06
                else -> 0.0
            }

            val rawScore = if (isVisionSource && question.options.size >= 2) {
                // Vision 专用打分：选项内容匹配率占大头
                scoreVision(normalizedStem, question.options, row)
            } else {
                // OCR / 无障碍 路径：保留原打分逻辑
                score(normalizedStem, optionText, normalizedFullText, row.normalizedStem, row.normalizedOptions, row.normalizedFullText)
            }
            val value = (rawScore + typeBonus).coerceIn(0.0, 1.0)
            Pair(row, value)
        }.sortedByDescending { it.second }

        // 候选行必须带 type：题型不兼容导致的 miss 和分数不够导致的 miss，
        // 在日志上长得一模一样。带上 type + 查询侧的 expectedType 才能一眼区分。
        scored.take(5).forEachIndexed { index, pair ->
            AppLogger.log(
                "[QuestionBank] candidate_${index + 1} score=${"%.3f".format(pair.second)} " +
                    "type=${pair.first.typeKey} answer=${pair.first.answer} stem=${pair.first.stem.take(160)}"
            )
        }
        AppLogger.log("[QuestionBank] query_expected_type=$expectedType threshold=0.300")
        // 1.1.11: 候选分数分布行（top1 / top5 / 阈值差），方便看"是否在临界值"
        if (scored.size >= 2) {
            val top1 = scored[0].second
            val top5 = if (scored.size >= 5) scored[4].second else scored.last().second
            val gap = top1 - (scored.getOrNull(1)?.second ?: 0.0)
            AppLogger.log(
                "[QuestionBank] candidate_dist top1=${"%.3f".format(top1)} " +
                    "top5=${"%.3f".format(top5)} " +
                    "top1_vs_top2_gap=${"%.3f".format(gap)} " +
                    "threshold=0.300"
            )
        }

        val candidates = scored.take(5).map { (row, value) ->
            QuestionCandidate(
                stem = row.stem,
                answer = AnswerFormatter.finalAnswer(row.answer, question.questionType),
                score = value,
                sourceFile = row.sourceFile
            )
        }

        if (candidates.isEmpty()) AppLogger.log("[QuestionBank] failed QUESTION_BANK_NO_CANDIDATE")

        // 命中条件：score ≥ 0.30 + 题型兼容
        //
        // 1.1.10 修复（Bug A）：原版 typeOk 太严格 — Vision API 把多选误标单选时，
        // 哪怕题库 score=1.0 也会因 typeKey 不等被丢弃，整题降级到 LLM。
        // 新规则：
        //   - 单选 ↔ 多选 互兼容（都是选择题，Vision 题型抖动时不能丢候选）
        //   - 判断 严格匹配（判断题只有对/错两个答案，跟选择题混了必错）
        //   - score ≥ 0.95 时直接信任，题型不作约束
        // 1.1.42：识别侧不再产出填空题；题库里遗留的 blank 行只能靠 score ≥ 0.95 命中。
        val best = scored.firstOrNull { (row, value) ->
            val typeOk = when {
                expectedType == "unknown" -> true
                row.typeKey == expectedType -> true
                value >= 0.95 -> true   // 高分直接信任题库
                isChoiceType(expectedType) && isChoiceType(row.typeKey) -> true  // 选择题互兼容
                else -> false
            }
            typeOk && value >= 0.30
        } ?: run {
            // 失败诊断：把 normalize 后的查询 stem 与 top1 候选 stem 一起打出来，方便对比噪音
            val top = scored.firstOrNull()
            if (top != null) {
                AppLogger.log(
                    "[QuestionBank] miss_diagnosis " +
                        "query_norm_stem_len=${normalizedStem.length} " +
                        "query_norm_stem=${normalizedStem.take(120)} " +
                        "top1_score=${"%.3f".format(top.second)} " +
                        "top1_type=${top.first.typeKey} " +
                        "top1_norm_stem=${top.first.normalizedStem.take(120)}"
                )
            }
            AppLogger.log("[QuestionBank] matched=false")
            return Pair(null, candidates)
        }

        // 按选项内容对齐：A~H 全覆盖；用 TextNormalizer.normalize 抹平全角/标点/空白差异
        val dbOptions = mapOf(
            "A" to best.first.optionA, "B" to best.first.optionB, "C" to best.first.optionC,
            "D" to best.first.optionD, "E" to best.first.optionE, "F" to best.first.optionF,
            "G" to best.first.optionG, "H" to best.first.optionH
        )
        val queryOptions = question.options.associate { it.label to it.text }
        val rawAnswer = best.first.answer
        val remappedAnswer = remapAnswerByContent(rawAnswer, dbOptions, queryOptions, question.questionType)
            ?: if (best.second >= 0.90) {
                // 题库整体匹配度极高（题干+选项 score≥0.9），选项错位概率极低；
                // remap 仅因 OCR 个别错别字（如"产品促销"→"产品销"）对不齐时，信任题库原字母，不丢答案。
                AppLogger.log("[QuestionBank] remap_failed_but_high_score score=${"%.3f".format(best.second)} trust_bank=$rawAnswer")
                rawAnswer
            } else {
                // 选项内容对齐失败 + 分数不够高：可能是不同题，整题降级走 LLM 兜底。
                AppLogger.log("[QuestionBank] downgrade reason=option_remap_failed raw_answer=$rawAnswer")
                AppLogger.log("[Source] failed=QUESTION_BANK reason=option_remap_failed raw_answer=$rawAnswer")
                return Pair(null, candidates)
            }

        // Bug B 修复：用**题库存的题型**来 normalize 答案，而不是 Vision 给的题型。
        // 原版 bug：Vision 把多选误标单选 → finalAnswer("ABDE", SINGLE) 走单选分支只取第一个字母 → 客户看到 "A"
        // 修复后：题库 row.typeKey="multiple" → finalAnswer("ABDE", MULTIPLE) 完整保留 → "ABDE"
        val bankType = TextNormalizer.typeFromKey(best.first.typeKey)
        val answer = AnswerFormatter.finalAnswer(remappedAnswer, bankType)
        AppLogger.log("[QuestionBank] matched=true")
        AppLogger.log("[QuestionBank] raw_answer=$rawAnswer remapped=$remappedAnswer final=$answer bank_type=${best.first.typeKey} vision_type=${TextNormalizer.typeKey(question.questionType)}")
        return Pair(LocalSearchAnswer(answer, "精准题库", best.second, best.first.stem), candidates)
    }

    /**
     * 候选行召回（五层策略，每层失败/不足则降级到下一层）：
     *
     *  Tier 0（NEW，仅 Vision API 路径用）: 按选项内容召回  LIMIT 200
     *          Vision API 提取出的选项是干净的术语（如"二指肠球部溃疡"、"生命体征的观察"），
     *          这些内容在题库中的 option_a..h 列里几乎唯一。直接用选项内容前缀 LIKE
     *          匹配 option_a..h 任一列。哪怕题干被 AI 改写，只要选项一字不差，依然能召回真题。
     *
     *  Tier 1: 4 字滑窗 LIKE OR  LIMIT 300
     *          取查询 stem 紧凑形式的 4-char sliding windows（如"类风湿性/湿性关节/关节炎病/..."），
     *          OR 召回。4-char 子串极少有跨题碰撞，命中率高、噪声低。
     *
     *  Tier 2: bigram AND        LIMIT 200
     *          要求 5 个 bigram 全部出现。窄收。例如"病人"+"包括"+"类风"+"关节"+"风湿"
     *          AND 起来只会留下"类风湿性关节炎病人"相关题。
     *
     *  Tier 3: bigram OR         LIMIT 500
     *          老逻辑加宽。LIMIT 从 80 → 500，缓解高频词漏召回（"病人"匹配上千行时不再被 80 截断）。
     *
     *  Tier 4: 全表 ORDER BY id DESC LIMIT 800
     *          最后兜底（前 3 层都拿不到 3 条以上时）。
     *
     *  各层按 normalized_stem 去重，避免重复评分。
     *  每层带日志 [QuestionBank] tierN_loaded=X cum=Y，便于排查"真题在哪一层被召回的"。
     */
    private fun loadCandidateRows(
        normalizedStem: String,
        normalizedFullText: String,
        queryOptions: List<OptionItem> = emptyList()
    ): List<CandidateRow> {
        val rows = mutableListOf<CandidateRow>()
        val seenStems = mutableSetOf<String>()  // 用 normalized_stem 去重
        val db = dbHelper.readableDatabase

        val baseSelect = """
            SELECT answer, question_type, normalized_stem, normalized_options, COALESCE(normalized_full_text, ''), stem, source_file,
                   option_a, option_b, option_c, option_d, option_e, option_f, option_g, option_h
            FROM local_question_bank
        """.trimIndent()

        fun runQuery(sql: String, args: Array<String>): Int {
            var added = 0
            runCatching {
                db.rawQuery(sql, args).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = cursor.toCandidateRow()
                        if (row.normalizedStem in seenStems) continue
                        seenStems.add(row.normalizedStem)
                        rows.add(row)
                        added++
                    }
                }
            }.onFailure { AppLogger.log("[QuestionBank] tier_query_failed err=${it.message?.take(80)}") }
            return added
        }

        val compactStem = normalizedStem.replace(" ", "")
        val bigrams = searchTerms(normalizedStem)

        // ── Tier 0（NEW）: 按 Vision 提取的选项内容召回 ──
        // 选项内容是高唯一性的术语，匹配它们能直接定位真题。
        //
        // 1.1.10 优化（Bug G）：
        //   1. 改用 normalized_options 列做 LIKE（之前 LIKE option_a..h 原始列，
        //      Excel 含标点时 normalize 不一致会失配）
        //   2. fragments take 从 10 字 → 12 字（兼顾长选项又不过短）
        //   3. 短选项（≤5 字）直接用全文，不截
        if (queryOptions.size >= 2) {
            val optionFragments = queryOptions
                .map { TextNormalizer.normalize(it.text).replace(" ", "") }
                .filter { it.length >= 3 }
                .map { frag -> if (frag.length <= 5) frag else frag.take(12) }
                .distinct()
                .take(8)
            if (optionFragments.isNotEmpty()) {
                // 只查 normalized_options 一列，且这列已经在导入时统一 normalize 过，
                // 跟我们查询用的 fragment 走同一套规则，避免标点/全角等差异
                val sql = "$baseSelect WHERE " +
                    optionFragments.joinToString(" OR ") { "normalized_options LIKE ?" } +
                    " LIMIT 200"
                val args = optionFragments.map { "%$it%" }.toTypedArray()
                val added = runQuery(sql, args)
                AppLogger.log(
                    "[QuestionBank] tier0_option_loaded=$added cum=${rows.size} " +
                        "fragments=${optionFragments.joinToString("|") { it.take(8) }}"
                )
            }
        }

        // ── Tier 1: 4-char 滑窗 OR ──────────────────
        if (compactStem.length >= 4) {
            val windows = compactStem.windowed(size = 4, step = 2).distinct().take(5)
            if (windows.isNotEmpty()) {
                val sql = "$baseSelect WHERE " +
                    windows.joinToString(" OR ") { "normalized_stem LIKE ?" } +
                    " LIMIT 300"
                val added = runQuery(sql, windows.map { "%$it%" }.toTypedArray())
                AppLogger.log("[QuestionBank] tier1_4char_loaded=$added cum=${rows.size} windows=${windows.joinToString("|")}")
            }
        }

        // ── Tier 2: bigram AND ──────────────────────
        if (rows.size < 5 && bigrams.size >= 2) {
            val n = minOf(5, bigrams.size)
            val selected = bigrams.take(n)
            val sql = "$baseSelect WHERE " +
                selected.joinToString(" AND ") { "normalized_stem LIKE ?" } +
                " LIMIT 200"
            val added = runQuery(sql, selected.map { "%$it%" }.toTypedArray())
            AppLogger.log("[QuestionBank] tier2_bigram_and_loaded=$added cum=${rows.size} bigrams=${selected.joinToString("|")}")
        }

        // ── Tier 3: bigram OR（老逻辑加宽 LIMIT）──
        if (rows.size < 5 && bigrams.isNotEmpty()) {
            val keywords = bigrams.take(8)
            val sql = "$baseSelect WHERE " +
                keywords.joinToString(" OR ") { "normalized_stem LIKE ?" } +
                " LIMIT 500"
            val added = runQuery(sql, keywords.map { "%$it%" }.toTypedArray())
            AppLogger.log("[QuestionBank] tier3_bigram_or_loaded=$added cum=${rows.size}")
        }

        // ── Tier 4: 全表兜底 ────────────────────────
        if (rows.size < 3) {
            rows.clear()
            seenStems.clear()
            val sql = "$baseSelect ORDER BY id DESC LIMIT 800"
            val added = runQuery(sql, emptyArray())
            AppLogger.log("[QuestionBank] tier4_fulltable_loaded=$added")
        }

        AppLogger.log("[QuestionBank] candidate_rows_loaded=${rows.size}")
        return rows
    }

    private fun Cursor.toCandidateRow(): CandidateRow = CandidateRow(
        answer = getString(0).orEmpty(),
        typeKey = getString(1).orEmpty(),
        normalizedStem = getString(2).orEmpty(),
        normalizedOptions = getString(3).orEmpty(),
        normalizedFullText = getString(4).orEmpty(),
        stem = getString(5).orEmpty(),
        sourceFile = getString(6).orEmpty(),
        optionA = getString(7).orEmpty(),
        optionB = getString(8).orEmpty(),
        optionC = getString(9).orEmpty(),
        optionD = getString(10).orEmpty(),
        optionE = getString(11).orEmpty(),
        optionF = getString(12).orEmpty(),
        optionG = getString(13).orEmpty(),
        optionH = getString(14).orEmpty()
    )

    private fun isChoiceType(typeKey: String): Boolean = typeKey == "single" || typeKey == "multiple"

    /**
     * 剥离 stem 开头的题号 + 章节标签前缀（Bug D 修复，1.1.11 扩展）。
     *
     * 覆盖的前缀模式（按顺序剥，多重前缀循环剥到没东西可剥为止）：
     *   "10、xxx"           → "xxx"
     *   "10.xxx"            → "xxx"
     *   "(10) xxx"          → "xxx"
     *   "10）xxx"           → "xxx"
     *   "第10题 xxx"        → "xxx"
     *   "1) xxx"            → "xxx"  (子问题编号，A3/A4)
     *   "一、xxx"           → "xxx"  (中文章节)
     *   "三、A3/A4型题14、xxx" → "xxx" (1.1.11 新：组合前缀)
     *   "A1型题 xxx"        → "xxx"  (1.1.11 新：医学考试题型标签)
     *   "第一章 xxx" "第3节 xxx" → "xxx" (1.1.11 新：章节)
     *   "Section 2 xxx" "Part 1 xxx" → "xxx" (1.1.11 新：英文章节)
     *
     * Vision API 会照搬这些前缀，但 Excel 题库通常不含。剥离后两边 normalize 结果一致。
     */
    private fun stripLeadingQuestionNumber(stem: String): String {
        var s = stem.trim()
        var prev = ""
        // 循环剥，最多 5 轮，处理"三、A3/A4型题14、" 这种组合
        repeat(5) {
            if (s == prev) return@repeat
            prev = s
            // 中文章节序号: "一、" "二、" "三、" ...
            s = s.replace(Regex("^\\s*[一二三四五六七八九十百]+\\s*[、.,]\\s*"), "")
            // 章节: "第一章" "第3节" "第二部分"
            s = s.replace(Regex("^\\s*第\\s*[一二三四五六七八九十\\d]+\\s*[章节部分篇]\\s*[：:、.]?\\s*"), "")
            // 医学考试题型标签: "A1型题" "A2型题" "A3/A4型题" "B1型题" "B型题"
            s = s.replace(Regex("^\\s*[A-Za-z]\\d?(?:/[A-Za-z]\\d?)?\\s*型题\\s*[：:、.]?\\s*"), "")
            // 英文章节: "Section 2" "Part 1" "Chapter 3"
            s = s.replace(Regex("^\\s*(?:Section|Part|Chapter|Unit)\\s*\\d+\\s*[：:.,]?\\s*", RegexOption.IGNORE_CASE), "")
            // 题号: "10、" "10." "10）" "10)"
            s = s.replace(Regex("^\\s*\\d{1,4}\\s*[、.,)）.]\\s*"), "")
            // 题号: "(10)" "（10）"
            s = s.replace(Regex("^\\s*[(（]\\s*\\d{1,4}\\s*[)）]\\s*"), "")
            // 子问题编号: "1)" "2)" "(1)"（A3/A4 案例题）
            s = s.replace(Regex("^\\s*[(（]?\\d{1,2}[)）]\\s*"), "")
            // 题号: "第10题"
            s = s.replace(Regex("^\\s*第\\s*\\d{1,4}\\s*题\\s*[：:、.]?\\s*"), "")
            s = s.trim()
        }
        return s
    }

    /**
     * Vision API 专用评分（1.1.8 新增）。
     *
     * 思想：Vision API 提取的选项内容是高纯度的术语，匹配它们比题干 bigram 准得多。
     * 即使题干字面差异（AI 可能补/漏几个字），只要 4-5 个选项里有 ≥75% 能在题库行的
     * option_a..h 列里找到完整子串，那基本就是同一题。
     *
     * 评分公式：
     *   final = optionOverlap * 0.60 + stemScore * 0.30 + fullTextScore * 0.10
     *
     *   optionOverlap：查询选项里有多少在候选行的 option_a..h 里能找到（任意 ≥ 0.75 子串重合）
     *   stemScore   ：normalize 后的 stem token + bigram 相似度（与老 score 同方式）
     *   fullTextScore：normalize 全文本 bigram 相似度
     *
     * 不再单独 containsBonus（因为 optionOverlap 已经是更强的 containment 信号）。
     */
    private fun scoreVision(
        normalizedStem: String,
        queryOptions: List<OptionItem>,
        row: CandidateRow
    ): Double {
        if (queryOptions.isEmpty()) {
            // 退回到老打分（理论上不会，调用方已经判断 size >= 2）
            return score(normalizedStem, "", normalizedStem, row.normalizedStem, row.normalizedOptions, row.normalizedFullText)
        }

        // 1. 选项匹配率 ─────────────────────────
        val rowOptions = listOf(
            row.optionA, row.optionB, row.optionC, row.optionD,
            row.optionE, row.optionF, row.optionG, row.optionH
        ).map { TextNormalizer.normalize(it).replace(" ", "") }
            .filter { it.isNotBlank() }

        if (rowOptions.isEmpty()) {
            // 候选行没存选项内容（填空题等），退回老打分
            return score(normalizedStem, "", normalizedStem, row.normalizedStem, row.normalizedOptions, row.normalizedFullText)
        }

        val queryOptNormed = queryOptions
            .map { TextNormalizer.normalize(it.text).replace(" ", "") }
            .filter { it.isNotBlank() }

        if (queryOptNormed.isEmpty()) {
            return score(normalizedStem, "", normalizedStem, row.normalizedStem, row.normalizedOptions, row.normalizedFullText)
        }

        var matchedCount = 0
        for (q in queryOptNormed) {
            val isMatched = rowOptions.any { r ->
                when {
                    r == q -> true                                        // 精确相等
                    q.length >= 3 && r.contains(q) -> true                // 查询选项是候选选项的子串
                    r.length >= 3 && q.contains(r) -> true                // 反之
                    q.length >= 3 && r.length >= 3 -> {
                        // 兜底：bigram ≥ 0.75 且长度比 ≥ 0.5
                        val short = if (q.length <= r.length) q else r
                        val long = if (q.length <= r.length) r else q
                        val ratio = short.length.toDouble() / long.length
                        ratio >= 0.5 && TextNormalizer.bigramOverlap(short, long) >= 0.75
                    }
                    else -> false
                }
            }
            if (isMatched) matchedCount++
        }
        val optionOverlap = matchedCount.toDouble() / queryOptNormed.size

        // 2. stem 相似度（与老 score 同方式）─────
        val compactStem = normalizedStem.replace(" ", "")
        val compactCandidate = row.normalizedStem.replace(" ", "")
        val stemScore = tokenOverlap(normalizedStem, row.normalizedStem)
            .coerceAtLeast(TextNormalizer.bigramOverlap(compactStem, compactCandidate))

        // 3. 全文 bigram ─────────────────────────
        val fullTextScore = TextNormalizer.bigramOverlap(
            (normalizedStem + " " + queryOptNormed.joinToString(" ")).replace(" ", ""),
            row.normalizedFullText.replace(" ", "")
        )

        val finalScore = (optionOverlap * 0.60 + stemScore * 0.30 + fullTextScore * 0.10)
            .coerceIn(0.0, 1.0)

        // 只对"值得关注"的候选打日志（选项匹配率≥50% 或 最终分≥0.5），避免上千行刷屏
        if (optionOverlap >= 0.5 || finalScore >= 0.5) {
            AppLogger.log(
                "[OptionMatch] matched=$matchedCount/${queryOptNormed.size} " +
                    "opt_overlap=${"%.2f".format(optionOverlap)} " +
                    "stem=${"%.2f".format(stemScore)} " +
                    "full=${"%.2f".format(fullTextScore)} " +
                    "final=${"%.2f".format(finalScore)} " +
                    "row_stem=${row.normalizedStem.take(40)}"
            )
        }

        return finalScore
    }

    private fun score(stem: String, options: String, fullText: String, candidateStem: String, candidateOptions: String, candidateFullText: String): Double {
        val stemScore = tokenOverlap(stem, candidateStem).coerceAtLeast(TextNormalizer.bigramOverlap(stem.replace(" ", ""), candidateStem.replace(" ", "")))
        val compactStem = stem.replace(" ", "")
        val compactCandidate = candidateStem.replace(" ", "")
        val containsBonus = when {
            compactStem.length >= 10 && compactCandidate.contains(compactStem) -> 0.30
            compactCandidate.length >= 10 && compactStem.contains(compactCandidate.take(28)) -> 0.22
            else -> 0.0
        }
        val optionScore = tokenOverlap(options, candidateOptions).coerceAtLeast(TextNormalizer.bigramOverlap(options.replace(" ", ""), candidateOptions.replace(" ", "")))
        val fullScore = TextNormalizer.bigramOverlap(fullText.replace(" ", ""), candidateFullText.replace(" ", ""))
        return (stemScore * 0.58 + optionScore * 0.18 + fullScore * 0.14 + containsBonus).coerceAtMost(1.0)
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val left = searchTerms(a)
        val right = searchTerms(b).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.count { it in right }.toDouble() / max(left.size, 1)
    }

    /**
     * 把 normalize 后的 text 切成 bigram 串。
     *
     * 1.1.10 修复（Bug C）：原版有"≥2 个 word 时返回 words"分支，导致 stem 含多个标点
     * （normalize 后变成多空格分隔）时，返回的不是 bigram，而是"生后30天/进行新生儿筛查时"
     * 这样的长词；Tier 2 AND 拼起来过度严格，几乎必 miss。
     *
     * 现在统一从 compact 形式切 bigram，确保查询和题库 normalize 一致。
     */
    private fun searchTerms(text: String): List<String> {
        val compact = text.replace(" ", "")
        if (compact.length < 2) return emptyList()
        return compact.windowed(size = 2, step = 1).distinct()
    }

    private fun mapRow(headers: List<String>, row: List<String>): RowQuestion? {
        fun byHeaders(candidates: Set<String>): String {
            val normalizedCandidates = candidates.map { normalizeHeader(it) }.toSet()
            for ((index, header) in headers.withIndex()) {
                if (header in normalizedCandidates && index < row.size) return row[index].trim()
            }
            return ""
        }

        val stem = byHeaders(HEADER_STEM)
        val answer = byHeaders(HEADER_ANSWER)
        if (stem.isBlank() || answer.isBlank()) return null

        val typeText = byHeaders(HEADER_TYPE)
        val questionType = TextNormalizer.typeFromText(typeText)
        return RowQuestion(
            typeKey = TextNormalizer.typeKey(questionType),
            stem = stem,
            optionA = byHeaders(HEADER_OPTION_A),
            optionB = byHeaders(HEADER_OPTION_B),
            optionC = byHeaders(HEADER_OPTION_C),
            optionD = byHeaders(HEADER_OPTION_D),
            optionE = byHeaders(HEADER_OPTION_E),
            optionF = byHeaders(HEADER_OPTION_F),
            optionG = byHeaders(HEADER_OPTION_G),
            optionH = byHeaders(HEADER_OPTION_H),
            answer = TextNormalizer.normalizeAnswer(answer, questionType)
        )
    }

    private fun rowDiagnostic(headers: List<String>, row: List<String>): String {
        fun value(candidates: Set<String>): String {
            val normalizedCandidates = candidates.map { normalizeHeader(it) }.toSet()
            for ((index, header) in headers.withIndex()) {
                if (header in normalizedCandidates && index < row.size) return row[index].trim()
            }
            return ""
        }
        val stem = value(HEADER_STEM)
        val answer = value(HEADER_ANSWER)
        val type = value(HEADER_TYPE)
        val missing = mutableListOf<String>()
        if (stem.isBlank()) missing.add("题干为空")
        if (answer.isBlank()) missing.add("正确答案为空")
        return buildString {
            append(if (missing.isEmpty()) "字段完整" else missing.joinToString("，"))
            append("；题型=$type, 题干=$stem, 答案=$answer")
        }
    }

    private fun buildHeaderMapDebug(headers: List<String>): String {
        val parts = mutableListOf<String>()
        fun add(name: String, candidates: Set<String>) {
            val normalizedCandidates = candidates.map { normalizeHeader(it) }.toSet()
            val index = headers.indexOfFirst { it in normalizedCandidates }
            parts.add("$name=$index")
        }
        add("question_type", HEADER_TYPE); add("stem", HEADER_STEM)
        add("option_a", HEADER_OPTION_A); add("option_b", HEADER_OPTION_B); add("option_c", HEADER_OPTION_C)
        add("option_d", HEADER_OPTION_D); add("option_e", HEADER_OPTION_E); add("option_f", HEADER_OPTION_F)
        add("option_g", HEADER_OPTION_G); add("option_h", HEADER_OPTION_H); add("answer", HEADER_ANSWER)
        return parts.joinToString(" ")
    }

    /**
     * 按选项内容对齐映射答案。
     * 例：题库中 A:String B:int 答案为B，但用户看到的是 A:int B:String，
     * 比对内容后把 B 映射为 A 返回。
     */
    /**
     * 把题库 answer（题库字母 A~H）按"选项内容"对齐到当前考试的字母。
     *
     * 三档匹配（先精确、再包含、再 bigram 相似）：
     *   - 精确：normalize+compact 后完全相等
     *   - 包含：短串 in 长串 且 长度比 ≥ 0.5（防 "中国" vs "中华人民共和国" 这类长度悬殊的误命中）
     *   - 相似：bigramOverlap ≥ 0.7 且 长度比 ≥ 0.5 且 与第二名差距 ≥ 0.1
     *
     * 任一字母对齐失败 → 返回 null，由调用方整题降级走 LLM；
     * 不再静默回退到题库字母（旧逻辑的隐蔽 bug：OCR 顺序乱时会直接返回错位字母）。
     */
    private fun remapAnswerByContent(
        rawAnswer: String,
        dbOptions: Map<String, String>,
        queryOptions: Map<String, String>,
        questionType: QuestionType
    ): String? {
        if (questionType == QuestionType.TRUE_FALSE || questionType == QuestionType.FILL_BLANK) {
            return rawAnswer
        }

        val answerLetters = Regex("[A-Ha-h]").findAll(rawAnswer)
            .map { it.value.uppercase() }.toList().distinct()
        if (answerLetters.isEmpty()) return rawAnswer

        fun norm(s: String) = TextNormalizer.normalize(s).replace(" ", "")
        val normQuery = queryOptions.mapValues { norm(it.value) }
            .filterValues { it.isNotBlank() }

        // 没有任何 query 选项 → 没法 remap，但也别一刀切丢答案；返回题库原字母
        if (normQuery.isEmpty()) {
            AppLogger.log("[QuestionBank] remap_lenient_passthrough reason=no_query_options answer=$rawAnswer")
            return rawAnswer
        }

        // 1.1.10 修复（Bug E）：宽容化
        // 旧版：任何一个字母 remap 失败 → 整题降级 LLM
        // 问题：多选 ABCDE 5 字母，哪怕只是 Vision 漏识 1 个选项也会全砍
        // 新版：尝试 remap 全部字母，统计成功率
        //   - 成功率 ≥ 80% → 接受，失败字母按"题库原字母"保留
        //   - 成功率 < 80% → 视为题库不匹配，降级 LLM
        val results = mutableListOf<Pair<String, String?>>()  // letter -> mappedLabel or null
        for (letter in answerLetters) {
            val dbContentRaw = dbOptions[letter].orEmpty()
            val dbContent = norm(dbContentRaw)
            if (dbContent.isBlank()) {
                AppLogger.log("[QuestionBank] remap_skip letter=$letter reason=db_option_empty")
                results.add(letter to null)
                continue
            }
            // 1.1.14 同位优先：若屏幕上同字母的选项内容已与题库该字母内容相似，
            // 说明选项没有乱序，直接保留原字母，不再全局搜索（防止短内容误映射到其他字母）。
            val samePosRaw = normQuery[letter].orEmpty()
            AppLogger.log("[QuestionBank] remap_check letter=$letter db=${dbContentRaw.take(30)} screen=${samePosRaw.take(30)}")
            if (samePosRaw.isNotBlank()) {
                val shortStr = if (samePosRaw.length <= dbContent.length) samePosRaw else dbContent
                val longStr  = if (samePosRaw.length <= dbContent.length) dbContent else samePosRaw
                val lenRatio = if (longStr.isEmpty()) 0.0 else shortStr.length.toDouble() / longStr.length
                // 注意：bigramOverlap 对长度 <2 的串返回 0，需在它之前单独处理精确相等（含单字符）
                val samePosScore = when {
                    samePosRaw == dbContent -> 1.0
                    lenRatio < 0.5 -> 0.0
                    shortStr.length >= 2 && shortStr in longStr -> 0.95
                    else -> TextNormalizer.bigramOverlap(shortStr, longStr)
                }
                AppLogger.log("[QuestionBank] remap_check_score letter=$letter score=${"%.2f".format(samePosScore)}")
                if (samePosScore >= 0.7) {
                    AppLogger.log("[QuestionBank] remap_same_pos letter=$letter score=${"%.2f".format(samePosScore)} db=${dbContentRaw.take(30)}")
                    results.add(letter to letter)
                    continue
                }
            }
            val (mappedLabel, mode) = findQueryLabelByContent(dbContent, normQuery)
            if (mappedLabel != null) {
                AppLogger.log("[QuestionBank] remap_ok letter=$letter->$mappedLabel mode=$mode db=${dbContentRaw.take(30)}")
            } else {
                AppLogger.log("[QuestionBank] remap_miss letter=$letter db=${dbContentRaw.take(40)} reason=$mode")
            }
            results.add(letter to mappedLabel)
        }

        val successCount = results.count { it.second != null }
        val successRate = if (results.isEmpty()) 0.0 else successCount.toDouble() / results.size
        AppLogger.log("[QuestionBank] remap_summary success=$successCount/${results.size} rate=${"%.2f".format(successRate)}")

        if (successRate < 0.8) {
            AppLogger.log("[QuestionBank] remap_downgrade reason=low_success_rate threshold=0.80 actual=${"%.2f".format(successRate)}")
            return null
        }

        // 成功率达标：合并；失败的字母用原字母（不破坏多选完整性）
        val remapped = results.map { (letter, mapped) -> mapped ?: letter }
        return remapped.distinct().sorted().joinToString("")
    }

    /**
     * 在考试选项里找题库内容对应的字母。返回 (字母, 判定理由)；找不到时字母为 null，理由记日志。
     */
    private fun findQueryLabelByContent(
        dbContent: String,
        normQuery: Map<String, String>
    ): Pair<String?, String> {
        if (normQuery.isEmpty()) return Pair(null, "no_query_options")

        // 1. 精确等于
        val exact = normQuery.entries.firstOrNull { it.value == dbContent }?.key
        if (exact != null) return Pair(exact, "exact")

        // 2. 给每个查询选项打分：包含关系优先，否则用 bigram
        val scored = normQuery.entries.map { (label, content) ->
            val (shortStr, longStr) =
                if (content.length <= dbContent.length) content to dbContent else dbContent to content
            val lenRatio = if (longStr.isEmpty()) 0.0
                else shortStr.length.toDouble() / longStr.length
            val score: Double = when {
                // 长度悬殊（如"中国" vs "中华人民共和国"，ratio=2/7≈0.29）→ 不给分
                lenRatio < 0.5 -> 0.0
                // 短串完整包含在长串里（如"北京" ⊂ "北京市"）→ 高分
                shortStr.length >= 2 && shortStr in longStr -> 0.95
                else -> TextNormalizer.bigramOverlap(shortStr, longStr)
            }
            Triple(label, score, lenRatio)
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull() ?: return Pair(null, "no_query_options")
        if (top.second < 0.7) {
            return Pair(null, "best_score_low=${"%.2f".format(top.second)} len_ratio=${"%.2f".format(top.third)}")
        }
        val second = scored.getOrNull(1)
        if (second != null && top.second - second.second < 0.1) {
            return Pair(null, "ambiguous top=${top.first}:${"%.2f".format(top.second)} second=${second.first}:${"%.2f".format(second.second)}")
        }
        return Pair(top.first, "fuzzy score=${"%.2f".format(top.second)}")
    }

    private fun failedReport(code: String, reason: String, sheetNames: List<String> = emptyList(), headers: List<String> = emptyList()): ImportReport {
        AppLogger.log("[Import] failed $code $reason")
        return ImportReport(
            status = "failed", errorCode = code, totalRows = 0, imported = 0,
            singleCount = 0, multipleCount = 0, judgeCount = 0, blankCount = 0,
            unsupportedCount = 0, failedRows = 0,
            failureReasons = listOf("$code：$reason"), sheetNames = sheetNames, headers = headers
        )
    }

    private data class CandidateRow(
        val answer: String, val typeKey: String, val normalizedStem: String,
        val normalizedOptions: String, val normalizedFullText: String, val stem: String,
        val sourceFile: String,
        val optionA: String = "", val optionB: String = "", val optionC: String = "",
        val optionD: String = "", val optionE: String = "", val optionF: String = "",
        val optionG: String = "", val optionH: String = ""
    )
    private data class RowQuestion(
        val typeKey: String, val stem: String,
        val optionA: String, val optionB: String, val optionC: String, val optionD: String,
        val optionE: String, val optionF: String, val optionG: String, val optionH: String,
        val answer: String
    )

    companion object {
        private val HEADER_TYPE = setOf("type", "question_type", "questiontype", "题型", "类型")
        private val HEADER_STEM = setOf("stem", "question", "question_text", "questiontext", "题干", "题目", "问题")
        private val HEADER_OPTION_A = setOf("option_a", "optiona", "a", "选项a", "a选项")
        private val HEADER_OPTION_B = setOf("option_b", "optionb", "b", "选项b", "b选项")
        private val HEADER_OPTION_C = setOf("option_c", "optionc", "c", "选项c", "c选项")
        private val HEADER_OPTION_D = setOf("option_d", "optiond", "d", "选项d", "d选项")
        private val HEADER_OPTION_E = setOf("option_e", "optione", "e", "选项e", "e选项")
        private val HEADER_OPTION_F = setOf("option_f", "optionf", "f", "选项f", "f选项")
        private val HEADER_OPTION_G = setOf("option_g", "optiong", "g", "选项g", "g选项")
        private val HEADER_OPTION_H = setOf("option_h", "optionh", "h", "选项h", "h选项")
        private val HEADER_ANSWER = setOf("answer", "correct_answer", "correctanswer", "正确答案", "参考答案", "答案")

        private fun normalizeHeader(raw: String): String {
            return raw.trim().lowercase()
                .replace("_", "").replace("-", "").replace(" ", "").replace("　", "")
                .replace("?", "").replace("？", "").replace(":", "").replace("：", "")
                .replace("(", "").replace(")", "").replace("（", "").replace("）", "")
        }

        private fun hasAnyHeader(headers: List<String>, candidates: Set<String>): Boolean {
            val normalizedCandidates = candidates.map { normalizeHeader(it) }.toSet()
            return headers.any { it in normalizedCandidates }
        }
    }
}

internal object XlsxReader {
    data class Workbook(val sheets: List<Sheet>)
    data class Sheet(val name: String, val rows: List<List<String>>)

    fun readWorkbook(bytes: ByteArray): Workbook {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) }.orEmpty()
        val names = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)?.let { parseSheetNames(it) }.orEmpty()
        val sheetEntries = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { path -> Regex("sheet(\\d+)\\.xml").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE }
        val sheets = sheetEntries.mapIndexed { index, path ->
            val name = names.getOrNull(index) ?: path.substringAfterLast('/')
            Sheet(name = name, rows = parseSheet(entries[path]!!.toString(Charsets.UTF_8), shared))
        }
        return Workbook(sheets)
    }

    private fun parseSheetNames(xml: String): List<String> = Regex("<(?:[A-Za-z0-9_]+:)?sheet\\b[^>]*\\bname=\"([^\"]*)\"")
        .findAll(xml).map { xmlUnescape(it.groupValues[1]) }.toList()

    private fun parseSharedStrings(xml: String): List<String> = Regex("<(?:[A-Za-z0-9_]+:)?si\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?si>", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml)
        .map { si ->
            Regex("<(?:[A-Za-z0-9_]+:)?t\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(si.groupValues[1])
                .joinToString("") { xmlUnescape(it.groupValues[1]) }
        }
        .toList()

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rowRegex = Regex("<(?:[A-Za-z0-9_]+:)?row\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?row>", RegexOption.DOT_MATCHES_ALL)
        // attrs 用非贪婪 [^>]*?，否则会吃掉自闭合 <c r="G2"/> 里的 /，把后续 cell 全吞掉
        val cellRegex = Regex("<(?:[A-Za-z0-9_]+:)?c\\b([^>]*?)(?:\\s*/>|>(.*?)</(?:[A-Za-z0-9_]+:)?c>)", RegexOption.DOT_MATCHES_ALL)
        return rowRegex.findAll(xml).map { rowMatch ->
            val cells = mutableMapOf<Int, String>()
            cellRegex.findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues.getOrNull(2).orEmpty()
                val ref = attr(attrs, "r")
                val type = attr(attrs, "t")
                val index = columnIndex(ref)
                val raw = when (type) {
                    "s" -> shared.getOrNull(tagText(body, "v").toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> inlineText(body)
                    else -> tagText(body, "v").ifBlank { inlineText(body) }
                }
                cells[index] = raw.trim()
            }
            val max = cells.keys.maxOrNull() ?: 0
            (0..max).map { cells[it].orEmpty() }
        }.toList()
    }

    private fun attr(attrs: String, name: String): String = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.getOrNull(1).orEmpty()

    private fun tagText(xml: String, tag: String): String = Regex("<(?:[A-Za-z0-9_]+:)?$tag\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
        .find(xml)?.groupValues?.getOrNull(1)?.let { xmlUnescape(it) }.orEmpty()

    private fun inlineText(xml: String): String = Regex("<(?:[A-Za-z0-9_]+:)?t\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?t>", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml).joinToString("") { xmlUnescape(it.groupValues[1]) }

    private fun xmlUnescape(value: String): String {
        var result = value
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
        result = Regex("&#(\\d+);").replace(result) { mr ->
            val codePoint = mr.groupValues[1].toIntOrNull() ?: return@replace mr.value
            StringBuilder().appendCodePoint(codePoint).toString()
        }
        result = Regex("&#x([0-9A-Fa-f]+);").replace(result) { mr ->
            val codePoint = mr.groupValues[1].toIntOrNull(16) ?: return@replace mr.value
            StringBuilder().appendCodePoint(codePoint).toString()
        }
        return result.replace("&amp;", "&")
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        var n = 0
        for (c in letters) n = n * 26 + (c - 'A' + 1)
        return (n - 1).coerceAtLeast(0)
    }
}
