package com.lk.studyassistant.quantum.util

/**
 * 题型分类器与结构化提取器。
 *
 * 解析顺序固定为：清洗文本 -> 提取题号 -> 识别题型 -> 截断题干 -> 切分选项 -> 校验缺失字段。
 */
object QuestionTypeClassifier {

    private val OPTION_LABELS = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    private val CHOICE_LABELS = setOf("A", "B", "C", "D", "E", "F", "G", "H")

    private val SINGLE_CHOICE_LABELS = listOf("单选题", "单项选择", "单项", "单选")
    private val MULTIPLE_CHOICE_LABELS = listOf("多选题", "多项选择", "多项", "多选")
    private val TRUE_FALSE_LABELS = listOf("判断题", "判断正误", "对错题", "正误题", "判断")

    // 1.1.42：本版本只支持 单选/多选/判断。填空题从"支持"降为"不支持"，
    // 导入时会被标记为不支持并跳过入库——它进了题库也只会污染候选池
    // （题型永远不兼容，还要参与每次召回打分）。
    private val UNSUPPORTED_LABELS = setOf(
        "填空题", "填空",
        "不定项选择", "不定项选择题", "不定项",
        "简答题", "简答", "案例分析", "案例题",
        "问答题", "问答", "论述题", "论述",
        "计算题", "计算", "综合题", "综合分析",
        "材料分析", "材料题", "名词解释", "解释题", "作文", "写作"
    )

    private val TOP_NAV_WORDS = setOf(
        "答题", "背题", "语音", "返回", "退出", "设置", "更多", "上一题", "下一题"
    )

    private val STOP_MARKERS = listOf(
        "来源：", "来源:", "[OCR]", "题干：", "题干:", "选项：", "选项:",
        "缺失：", "缺失:", "收藏", "答题卡", "分享", "提交答案", "交卷",
        "正确答案", "参考答案", "答案解析", "解析"
    )

    private val BOTTOM_NOISE = setOf(
        "开通会员", "立即开通", "超值权益", "精编题库", "易错题", "精简题",
        "收藏", "分享", "答题卡", "已答", "未答", "解析", "查看解析",
        "报告", "统计", "排名", "进度", "目录", "筛选", "展开", "收起",
        "全选", "取消", "提交", "确定提交", "重做", "继续", "开始", "笔记",
        "下载", "打印"
    )

    private val JUDGE_PAIRS = listOf(
        "正确" to "错误",
        "对" to "错",
        "√" to "×",
        "✓" to "✗",
        "是" to "否"
    )

    private val QUESTION_NUMBER_PATTERNS = listOf(
        Regex("""第\s*(\d{1,4})\s*题"""),
        Regex("""(\d{1,4})\s*/\s*(\d{1,4})"""),
        Regex("""^(\d{1,4})\s*[、\.．]"""),
        Regex("""(\d{1,4})\s*[、\.．]""")
    )

    private val OPTION_MARKER = Regex(
        pattern = """(?m)(^|[\s\n\r])([A-H])\s*(?:[.．、:：\)）]\s*)?""",
        options = setOf(RegexOption.MULTILINE)
    )

    fun extract(region: QuestionRegion, allOcrText: String = ""): QuestionExtractResult {
        val regionText = buildRegionText(region)
        val ocrSegments = parseByStateMachine(allOcrText)
        val regionSegments = parseByStateMachine(regionText)

        val preferred = chooseSegments(region, ocrSegments, regionSegments)
        val questionType = preferred.questionType

        if (questionType == QuestionType.UNSUPPORTED) {
            return QuestionExtractResult(
                questionType = QuestionType.UNSUPPORTED,
                questionNumber = preferred.questionNumber,
                questionText = preferred.questionText,
                options = emptyList(),
                blanks = emptyList(),
                isComplete = false,
                missingFields = emptyList(),
                unsupportedReason = preferred.unsupportedReason
            )
        }

        return buildResult(questionType, preferred, allOcrText)
    }

    private fun chooseSegments(
        region: QuestionRegion,
        ocrSegments: ParsedSegments,
        regionSegments: ParsedSegments
    ): ParsedSegments {
        val regionType = mapTypeLabel(region.questionType)
        val fixedRegion = if (regionType != QuestionType.UNKNOWN && regionSegments.questionType == QuestionType.UNKNOWN) {
            regionSegments.copy(questionType = regionType)
        } else regionSegments

        val regionScore = scoreSegments(fixedRegion)
        val ocrScore = scoreSegments(ocrSegments)

        return when {
            fixedRegion.options.size >= 2 -> {
                val type = when {
                    regionType != QuestionType.UNKNOWN -> regionType
                    ocrSegments.questionType != QuestionType.UNKNOWN -> ocrSegments.questionType
                    else -> fixedRegion.questionType
                }
                fixedRegion.copy(questionType = type)
            }
            ocrScore > regionScore -> ocrSegments
            regionType != QuestionType.UNKNOWN -> fixedRegion.copy(questionType = regionType)
            else -> fixedRegion
        }
    }

    private fun scoreSegments(segments: ParsedSegments): Int {
        var score = 0
        if (segments.questionType != QuestionType.UNKNOWN) score += 4
        if (segments.questionNumber.isNotBlank()) score += 2
        if (segments.questionText.length >= 5) score += 2
        score += segments.options.size * 3
        if (segments.options.keys.containsAll(listOf("A", "B", "C", "D"))) score += 4
        return score
    }

    private fun buildResult(
        questionType: QuestionType,
        segments: ParsedSegments,
        allOcrText: String
    ): QuestionExtractResult {
        return when (questionType) {
            QuestionType.SINGLE_CHOICE,
            QuestionType.MULTIPLE_CHOICE -> buildChoiceResult(questionType, segments)
            QuestionType.TRUE_FALSE -> buildTrueFalseResult(segments, allOcrText)
            // 填空题本版本不支持：detectType 已不会产出它，这里保留分支只为 when 穷尽，
            // 万一从别处传进来也按"不支持"处理，不入库。
            QuestionType.FILL_BLANK,
            QuestionType.UNSUPPORTED -> QuestionExtractResult(
                questionType = QuestionType.UNSUPPORTED,
                questionNumber = segments.questionNumber,
                questionText = segments.questionText,
                options = emptyList(),
                blanks = emptyList(),
                isComplete = false,
                missingFields = emptyList(),
                unsupportedReason = segments.unsupportedReason
            )
            QuestionType.UNKNOWN -> buildUnknownResult(segments)
        }
    }

    private fun buildChoiceResult(
        questionType: QuestionType,
        segments: ParsedSegments
    ): QuestionExtractResult {
        val options = segments.options.entries
            .filter { it.key in CHOICE_LABELS && it.value.isNotBlank() }
            .map { (label, text) -> OptionItem(label, cleanOptionText(text)) }
            .take(8)

        val missingFields = mutableListOf<String>()
        if (segments.questionText.isBlank()) missingFields.add("题干")
        if (options.size < 2) missingFields.add("选项（至少需要A/B）")

        return QuestionExtractResult(
            questionType = questionType,
            questionNumber = segments.questionNumber,
            questionText = segments.questionText,
            options = options,
            blanks = emptyList(),
            isComplete = segments.questionText.isNotBlank() && options.size >= 2,
            missingFields = missingFields,
            unsupportedReason = null
        )
    }

    private fun buildUnknownResult(segments: ParsedSegments): QuestionExtractResult {
        val options = segments.options.entries
            .filter { it.key in CHOICE_LABELS && it.value.isNotBlank() }
            .map { (label, text) -> OptionItem(label, cleanOptionText(text)) }
            .take(8)

        val missingFields = mutableListOf("题型")
        if (segments.questionText.isBlank()) missingFields.add("题干")

        return QuestionExtractResult(
            questionType = QuestionType.UNKNOWN,
            questionNumber = segments.questionNumber,
            questionText = segments.questionText,
            options = options,
            blanks = emptyList(),
            isComplete = false,
            missingFields = missingFields,
            unsupportedReason = null
        )
    }

    private fun buildTrueFalseResult(
        segments: ParsedSegments,
        allOcrText: String
    ): QuestionExtractResult {
        val judgeOptions = mutableListOf<OptionItem>()
        var generated = false

        for ((label, text) in segments.options) {
            val t = cleanOptionText(text)
            if (JUDGE_PAIRS.any { (correct, wrong) -> t == correct || t == wrong || t.contains(correct) || t.contains(wrong) }) {
                judgeOptions.add(OptionItem(label, t))
            }
        }

        if (judgeOptions.isEmpty() && allOcrText.isNotBlank()) {
            for ((correct, wrong) in JUDGE_PAIRS.take(3)) {
                if (allOcrText.contains(correct) && allOcrText.contains(wrong)) {
                    judgeOptions.add(OptionItem("A", correct))
                    judgeOptions.add(OptionItem("B", wrong))
                    break
                }
            }
        }

        // 不再自动补 A/B：判断题答案直接用"正确"/"错误"文字，无需选项字母

        return QuestionExtractResult(
            questionType = QuestionType.TRUE_FALSE,
            questionNumber = segments.questionNumber,
            questionText = segments.questionText,
            options = judgeOptions,
            blanks = emptyList(),
            isComplete = segments.questionText.isNotBlank(),
            missingFields = if (segments.questionText.isBlank()) listOf("题干") else emptyList(),
            unsupportedReason = null,
            generatedOptions = false
        )
    }

    fun parseForTest(rawText: String): QuestionExtractResult {
        val segments = parseByStateMachine(rawText)
        return buildResult(segments.questionType, segments, rawText)
    }

    private fun parseByStateMachine(rawText: String): ParsedSegments {
        val cleaned = normalizeText(rawText)
        if (cleaned.isBlank()) return ParsedSegments()

        val mainText = truncateNoise(cleaned)
        val questionType = detectType(mainText)
        val unsupported = detectUnsupported(mainText, questionType)
        if (unsupported != null) {
            return ParsedSegments(
                questionType = QuestionType.UNSUPPORTED,
                questionNumber = extractQuestionNumber(mainText),
                questionText = stripQuestionPrefix(mainText),
                unsupportedReason = unsupported
            )
        }

        val markerMatches = OPTION_MARKER.findAll(mainText).toList()
        val optionMarkers = selectOptionMarkerSequence(mainText, markerMatches)

        val questionPart = if (optionMarkers.isNotEmpty()) {
            mainText.substring(0, optionMarkers.first().range.first).trim()
        } else mainText

        val options = splitOptions(mainText, optionMarkers)
        val questionNumber = extractQuestionNumber(questionPart).ifBlank { extractQuestionNumber(mainText) }
        val questionText = stripOptionLeakage(stripQuestionPrefix(questionPart))

        return ParsedSegments(
            questionType = reconcileTypeWithOptions(questionType, options),
            questionNumber = questionNumber,
            questionText = questionText,
            options = options,
            unsupportedReason = null
        )
    }

    private fun buildRegionText(region: QuestionRegion): String {
        return buildString {
            append(region.questionType).append('\n')
            append(region.stemText).append('\n')
            for ((label, text) in region.options) {
                append(label).append(".").append(text).append('\n')
            }
        }
    }

    private fun normalizeText(rawText: String): String {
        var text = rawText
            .replace('．', '.')
            .replace('：', ':')
            .replace('）', ')')
            .replace('，', ',')
            .replace('。', '。')
            .replace('\u00A0', ' ')

        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                    in '０'..'９' -> '0' + (ch - '０')
                    else -> ch
                }
            )
        }
        text = sb.toString()
        text = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return text.replace(Regex("""[ \t]+"""), " ").trim()
    }

    private fun truncateNoise(text: String): String {
        val lines = text.lines()
        val kept = mutableListOf<String>()
        var hasQuestionSignal = false

        for (line in lines) {
            val t = line.trim()
            if (t.isBlank()) continue
            if (looksLikeQuestionSignal(t)) hasQuestionSignal = true
            if (!hasQuestionSignal) continue
            if (hasQuestionSignal && STOP_MARKERS.any { marker ->
                    t == marker.removeSuffix("：").removeSuffix(":") ||
                    t.startsWith(marker) ||
                    (marker in listOf("收藏", "答题卡", "分享") && t.contains(marker))
                }) {
                break
            }
            kept.add(t)
        }

        return kept.joinToString("\n").trim()
    }

    private fun looksLikeQuestionSignal(text: String): Boolean {
        return detectType(text) != QuestionType.UNKNOWN ||
                QUESTION_NUMBER_PATTERNS.any { it.containsMatchIn(text) } ||
                Regex("""^[A-H]\s*(?:[.、:：\)）]|\s+).+""").containsMatchIn(text)
    }

    private fun detectType(text: String): QuestionType {
        val compact = text.replace(Regex("""\s+"""), "")
        return when {
            MULTIPLE_CHOICE_LABELS.any { compact.contains(it) } -> QuestionType.MULTIPLE_CHOICE
            SINGLE_CHOICE_LABELS.any { compact.contains(it) } -> QuestionType.SINGLE_CHOICE
            TRUE_FALSE_LABELS.any { compact.contains(it) } -> QuestionType.TRUE_FALSE
            else -> QuestionType.UNKNOWN
        }
    }

    /**
     * 用选项结构纠正题型（1.1.42）。
     *
     * [detectType] 只看文本关键词，且是在切出选项之前跑的，所以题干里一句
     * "判断下列说法是否正确" 会把一道 4 选项单选题标成判断题。判断题在题库侧
     * 与选择题严格互斥，标错 = 这条题入库后永远匹配不上。
     *
     * 规则：选项 ≥ 3 个 → 一定是选择题；已标判断的降回单选（单选/多选在检索时互兼容，
     * 所以这里退到单选不会丢召回）。
     */
    private fun reconcileTypeWithOptions(
        detected: QuestionType,
        options: Map<String, String>
    ): QuestionType {
        val optionCount = options.count { it.key in CHOICE_LABELS && it.value.isNotBlank() }
        if (optionCount >= 3 && detected == QuestionType.TRUE_FALSE) return QuestionType.SINGLE_CHOICE
        return detected
    }

    private fun detectUnsupported(text: String, detectedType: QuestionType): String? {
        if (detectedType != QuestionType.UNKNOWN) return null
        val label = UNSUPPORTED_LABELS.firstOrNull { text.contains(it) } ?: return null
        return "当前版本只支持单选、多选、判断，不支持「$label」题型"
    }

    private fun selectOptionMarkerSequence(
        text: String,
        matches: List<MatchResult>
    ): List<MatchResult> {
        val candidates = matches.filter { match ->
            val label = match.groupValues[2]
            label in OPTION_LABELS && isRealOptionMarker(text, match)
        }
        if (candidates.isEmpty()) return emptyList()

        val byLabel = LinkedHashMap<String, MatchResult>()
        var expected = 'A'
        for (candidate in candidates) {
            val label = candidate.groupValues[2].first()
            if (label == expected) {
                byLabel[label.toString()] = candidate
                expected++
            }
        }
        return byLabel.values.toList()
    }

    private fun isRealOptionMarker(text: String, match: MatchResult): Boolean {
        val start = match.range.first
        val end = match.range.last + 1
        val label = match.groupValues[2]
        val marker = match.value
        val hasExplicitDelimiter = marker.any { it in ".．、:：)）" }
        val next = text.drop(end).dropWhile { it == ' ' || it == '\t' }.firstOrNull()
        val prev = text.substring(0, start).trimEnd().lastOrNull()
        val lineStart = start == 0 || prev == '\n' || prev == '\r'

        if (label !in OPTION_LABELS) return false
        if (next == null || next == '\n' || next == '\r') return true
        if (hasExplicitDelimiter) return true
        if (lineStart && (next.isDigit() || next in "对错是吾讯知合施安区特编负")) return true
        return false
    }

    private fun splitOptions(
        text: String,
        markers: List<MatchResult>
    ): LinkedHashMap<String, String> {
        val options = LinkedHashMap<String, String>()
        for ((index, marker) in markers.withIndex()) {
            val label = marker.groupValues[2]
            val start = marker.range.last + 1
            val end = markers.getOrNull(index + 1)?.range?.first ?: text.length
            val value = text.substring(start, end).trim()
            if (value.isNotBlank()) {
                options[label] = cleanOptionText(value)
            }
        }
        return options
    }

    private fun stripQuestionPrefix(stem: String): String {
        var text = stem.trim()
        text = stripLeadingChrome(text)
        val typePrefix = Regex("""^(?:单选题|多选题|判断题|填空题|单选|多选|判断|填空|单项选择|多项选择|单项|多项)\s*[:：]?\s*""")
        text = typePrefix.replaceFirst(text, "").trim()
        text = stripLeadingSectionTitle(text)

        for (pattern in QUESTION_NUMBER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            if (match.range.first == 0) {
                text = text.removeRange(match.range).trim()
                break
            }
        }

        return text.replace(Regex("""\s+"""), " ").trim()
    }

    private fun stripLeadingChrome(input: String): String {
        var text = input.trim()
        var changed = true
        while (changed) {
            changed = false
            for (word in TOP_NAV_WORDS + listOf("顺序练习", "练习", "编辑")) {
                if (text == word || text.startsWith("$word ")) {
                    text = text.removePrefix(word).trim()
                    changed = true
                }
            }
        }
        return text
    }

    private fun stripLeadingSectionTitle(input: String): String {
        var text = input.trim()
        val section = Regex("""^第\s*\d+\s*节\s+[^。？！?]*?\s+""").find(text)
        if (section != null && section.range.first == 0) {
            text = text.removeRange(section.range).trim()
        }
        return text
    }

    private fun stripOptionLeakage(stem: String): String {
        val match = Regex("""(?:^|\s)[A-H]\s*(?:[.．、:：\)）]|\s+)\s*.+$""").find(stem) ?: return stem
        return if (match.range.first > 0) stem.substring(0, match.range.first).trim() else stem
    }

    private fun cleanOptionText(text: String): String {
        var value = text.trim()
        value = value.replace(Regex("""^[A-H]\s*[.．、:：\)）]?\s*"""), "")
        value = value.replace(Regex("""^[.．、:：]\s*"""), "")
        for (noise in BOTTOM_NOISE) {
            if (value.endsWith(noise)) value = value.removeSuffix(noise).trim()
        }
        return value.replace(Regex("""\s+"""), " ").trim()
    }

    private fun extractQuestionNumber(text: String): String {
        val trimmed = text.trim()
        val afterType = Regex("""(?:单选题?|多选题?|判断题?|填空题?)\s*(\d{1,4})\s*[、\.．]""")
            .find(trimmed)
        if (afterType != null) return "第${afterType.groupValues[1]}题"

        for (pattern in QUESTION_NUMBER_PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            return when {
                match.groupValues.size >= 3 && match.groupValues[2].isNotBlank() &&
                        match.groupValues[2].toIntOrNull() != null -> {
                    "第${match.groupValues[1]}/${match.groupValues[2]}题"
                }
                match.value.contains("第") && match.value.contains("题") -> match.value.trim()
                match.groupValues.size >= 2 && match.groupValues[1].toIntOrNull() != null -> "第${match.groupValues[1]}题"
                else -> match.value.trim()
            }
        }
        return ""
    }

    fun formatResult(result: QuestionExtractResult): String {
        val sb = StringBuilder()

        when (result.questionType) {
            QuestionType.UNSUPPORTED -> {
                sb.append("题型：暂不支持\n")
                sb.append("原因：${result.unsupportedReason ?: "当前版本只支持单选、多选、判断"}\n")
                if (result.questionText.isNotBlank()) sb.append("题干：${result.questionText}\n")
                return sb.toString()
            }
            QuestionType.UNKNOWN -> {
                sb.append("题型：未识别\n")
                appendCommonFields(sb, result)
                return sb.toString()
            }
            QuestionType.SINGLE_CHOICE,
            QuestionType.MULTIPLE_CHOICE -> {
                sb.append("题型：${typeLabel(result.questionType)}\n")
                appendCommonFields(sb, result)
            }
            QuestionType.TRUE_FALSE -> {
                sb.append("题型：判断题\n")
                appendCommonFields(sb, result)
                if (result.generatedOptions) sb.append("（由系统自动生成）\n")
            }
            QuestionType.FILL_BLANK -> {
                // 本版本不支持填空题，只可能来自历史数据
                sb.append("题型：填空题（当前版本不支持）\n")
                if (result.questionNumber.isNotBlank()) sb.append("题号：${result.questionNumber}\n")
                sb.append("题干：${result.questionText}\n")
            }
        }

        return sb.toString()
    }

    private fun appendCommonFields(
        sb: StringBuilder,
        result: QuestionExtractResult
    ) {
        if (result.questionNumber.isNotBlank()) sb.append("题号：${result.questionNumber}\n")
        sb.append("题干：${result.questionText}\n")
        for (opt in result.options) sb.append("${opt.label}：${opt.text}\n")
        if (result.missingFields.isNotEmpty()) sb.append("缺失：${result.missingFields.joinToString("、")}\n")
    }

    fun typeLabel(type: QuestionType): String = when (type) {
        QuestionType.SINGLE_CHOICE -> "单选题"
        QuestionType.MULTIPLE_CHOICE -> "多选题"
        QuestionType.TRUE_FALSE -> "判断题"
        QuestionType.FILL_BLANK -> "填空题"
        QuestionType.UNSUPPORTED -> "暂不支持"
        QuestionType.UNKNOWN -> "未识别"
    }

    private fun mapTypeLabel(text: String): QuestionType = when {
        Regex("""多选|多项""").containsMatchIn(text) -> QuestionType.MULTIPLE_CHOICE
        Regex("""单选|单项""").containsMatchIn(text) -> QuestionType.SINGLE_CHOICE
        Regex("""判断|对错|正误""").containsMatchIn(text) -> QuestionType.TRUE_FALSE
        else -> QuestionType.UNKNOWN
    }

    private data class ParsedSegments(
        val questionType: QuestionType = QuestionType.UNKNOWN,
        val questionNumber: String = "",
        val questionText: String = "",
        val options: LinkedHashMap<String, String> = LinkedHashMap(),
        val unsupportedReason: String? = null
    )
}
