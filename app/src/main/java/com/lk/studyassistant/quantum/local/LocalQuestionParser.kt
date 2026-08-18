package com.lk.studyassistant.quantum.local

import com.lk.studyassistant.quantum.util.AppLogger
import com.lk.studyassistant.quantum.util.BlankItem
import com.lk.studyassistant.quantum.util.OptionItem
import com.lk.studyassistant.quantum.util.QuestionExtractResult
import com.lk.studyassistant.quantum.util.QuestionType

object LocalQuestionParser {
    private val labeledOptionPattern = Regex("^\\(?\\s*([A-Ha-h])\\s*\\)?\\s*[).:：、]?\\s*(.+)$")
    private val labelOnlyPattern = Regex("^\\(?[A-Ha-h]\\)?[).:：、]?$|^[A-Ha-h]$")
    private val progressPattern = Regex("^\\d+\\s*/\\s*\\d+$")
    private val scorePattern = Regex("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]|\\b\\d+(?:\\.\\d+)?\\s*分\\b")
    private val wholeStatusPattern = Regex("^(返回|退出|关闭|刷新|重新加载|就绪|答对了|答错了|上一题|下一题|提交答案|确认提交|确认答案|交卷|查看答案解析|点击继续|跳过此题|收藏|收藏本题|答题卡|倒计时|剩余时间|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费|答)$")

    // 题型标签：必须是独立标签形态，不能命中题干里的普通词汇。
    // 例如"判断该患者的分期"里的"判断"前后都是汉字，不该被认成判断题标签。
    private val MULTI_LABEL = Regex("""多选题|多项选择题?|\[多选]|【多选】|(?<![一-鿿])多选(?![一-鿿])""")
    private val SINGLE_LABEL = Regex("""单选题|单项选择题?|\[单选]|【单选】|(?<![一-鿿])单选(?![一-鿿])""")
    private val JUDGE_LABEL = Regex("""判断题|\[判断]|【判断】|(?<![一-鿿])判断(?![一-鿿])|(?<![一-鿿])对错(?![一-鿿])""")
    private val JUDGE_OPTION_WORDS = setOf("正确", "错误", "对", "错", "是", "否")

    fun parse(rawText: String): QuestionExtractResult {
        val text = TextNormalizer.normalizeForParse(rawText)
        val cleanedLines = text.lines()
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
        val lines = cleanedLines.filterNot { looksLikeNoise(it) }

        if (lines.isEmpty()) return QuestionExtractResult.empty(source = "本地OCR")

        val cleanedText = lines.joinToString("\n")
        // 题型标签（"判断题"这种独立成行的）会被 looksLikeNoise 当噪音滤掉，
        // 所以题型判定要看噪音过滤**之前**的文本，否则标签白给了。
        // 选项仍然从过滤后的文本里取——噪音行不该被当成选项。
        val detectedType = detectType(cleanedText, cleanedLines.joinToString("\n"))
        val parsedOptions = extractOptions(lines)
        val finalType = when {
            detectedType != QuestionType.UNKNOWN -> detectedType
            parsedOptions.options.size >= 2 -> QuestionType.SINGLE_CHOICE
            else -> QuestionType.UNKNOWN
        }

        val firstOptionLine = parsedOptions.firstOptionLine
        val stemLines = if (firstOptionLine != null) lines.take(firstOptionLine) else lines
        val cleanedStem = cleanStem(stemLines)
        val generatedOptions = false
        val finalOptions = parsedOptions.options.distinctBy { it.label }.take(8)
        val blanks = emptyList<BlankItem>()

        val missing = mutableListOf<String>()
        if (cleanedStem.isBlank()) missing.add("题干")
        if (finalType in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE) && finalOptions.size < 2) {
            missing.add("选项")
        }
        if (finalType == QuestionType.UNKNOWN) missing.add("题型")

        AppLogger.log("[QuestionParser] type=${TextNormalizer.typeKey(finalType)}")
        AppLogger.log("[QuestionParser] stem=${cleanedStem.take(240)}")
        finalOptions.forEach { AppLogger.log("[QuestionParser] option_${it.label.lowercase()}=${it.text.take(160)}") }
        AppLogger.log("[QuestionParser] complete=${missing.isEmpty()} missing=${missing.joinToString(",")}")

        return QuestionExtractResult(
            questionType = finalType,
            questionNumber = Regex("第\\s*(\\d+)\\s*题|^\\s*(\\d+)\\s*[.、]").find(cleanedText)?.value.orEmpty(),
            questionText = cleanedStem,
            options = finalOptions,
            blanks = blanks,
            isComplete = missing.isEmpty(),
            missingFields = missing,
            unsupportedReason = null,
            generatedOptions = generatedOptions,
            confidence = if (missing.isEmpty()) 0.82f else 0.35f,
            source = "本地OCR",
            rawJson = ""
        )
    }

    /**
     * 题型判定（1.1.42 重写）。只产出 单选 / 多选 / 判断 / UNKNOWN。
     *
     * 相比旧版改了三件事，每一件都对应一类真实漏判：
     *
     * 1. **结构优先于关键词**。选项 ≥ 3 个的题一定是选择题，题干里出现"判断"
     *    （"判断该患者的分期…"）不能再把它打成判断题。旧版 `text.contains("判断")`
     *    排在选项判定之前，而题库里 judge 与 single 是严格互斥的，误判即必然 miss。
     *
     * 2. **题型关键词必须是独立标签**，用 CJK 前后界防止命中题干里的普通词汇。
     *
     * 3. **删掉填空**。旧版 `text.contains("( )")` 排在单选之前，而中文单选题的题干
     *    普遍带"（ ）"占位符（normalizeForParse 会把全角括号转成半角），于是一道
     *    有 4 个选项的单选题被判成填空 → 题型不兼容 → 题库必然 miss。
     *    本版本只支持单选/多选/判断，填空一律不产出。
     */
    private fun detectType(text: String, labelSourceText: String = text): QuestionType {
        val opts = extractOptions(text.lines()).options
        val multiLabel = MULTI_LABEL.containsMatchIn(labelSourceText)
        val singleLabel = SINGLE_LABEL.containsMatchIn(labelSourceText)
        val judgeLabel = JUDGE_LABEL.containsMatchIn(labelSourceText)
        // 选项恰好是 正确/错误、对/错、是/否 → 判断题
        // （屏幕上的"判断题"标签常被白名单边界剥离，只能靠选项内容认出来）
        val binaryJudge = opts.size == 2 && opts.all { it.text.trim() in JUDGE_OPTION_WORDS }

        // rule 记录"是哪条规则定的型"。真机排查题型误判时，只看最终题型无法判断是
        // 结构定的还是关键词定的——而这两者的修法完全不同。
        val (type, rule) = when {
            // 结构优先：≥3 个选项的题不可能是判断题，关键词一律让位
            opts.size >= 3 ->
                if (multiLabel) QuestionType.MULTIPLE_CHOICE to "opts>=3+multi_label"
                else QuestionType.SINGLE_CHOICE to "opts>=3"
            binaryJudge -> QuestionType.TRUE_FALSE to "binary_judge_options"
            multiLabel -> QuestionType.MULTIPLE_CHOICE to "multi_label"
            judgeLabel -> QuestionType.TRUE_FALSE to "judge_label"
            singleLabel -> QuestionType.SINGLE_CHOICE to "single_label"
            opts.size >= 2 -> QuestionType.SINGLE_CHOICE to "opts>=2_default"
            else -> QuestionType.UNKNOWN to "no_signal"
        }
        AppLogger.log(
            "[QuestionParser] type_rule=$rule type=${TextNormalizer.typeKey(type)} opts=${opts.size} " +
                "labels(single=$singleLabel multi=$multiLabel judge=$judgeLabel binary=$binaryJudge)"
        )
        return type
    }

    private data class ParsedOptionLine(val label: String, val text: String, val lineIndex: Int)
    private data class ParsedOptions(val options: List<OptionItem>, val firstOptionLine: Int?)

    private fun extractOptions(lines: List<String>): ParsedOptions {
        val labeled = mutableListOf<ParsedOptionLine>()
        val consumedLines = mutableSetOf<Int>()

        lines.forEachIndexed { index, line ->
            val match = labeledOptionPattern.find(line)
            if (match != null) {
                val label = match.groupValues[1].uppercase()
                val value = cleanOptionText(match.groupValues[2])
                if (value.isNotBlank() && !labelOnlyPattern.matches(value) && looksLikeOptionValue(value, fromLabel = true)) {
                    labeled.add(ParsedOptionLine(label, value, index))
                    consumedLines.add(index)
                }
            }
        }

        lines.forEachIndexed { index, line ->
            if (index in consumedLines) return@forEachIndexed
            if (!labelOnlyPattern.matches(line)) return@forEachIndexed
            val next = lines.drop(index + 1).withIndex()
                // 选项标签独占一行时，下一行内容可能是纯数字（"A"换行后是"72"），不能当噪音杀掉
                .firstOrNull { (_, value) -> value.isNotBlank() && !looksLikeNoise(value, treatShortAsNoise = false, treatNumericAsNoise = false) }
            val value = next?.value?.let { cleanOptionText(it) }.orEmpty()
            if (value.isNotBlank() && looksLikeOptionValue(value, fromLabel = true)) {
                labeled.add(ParsedOptionLine(line.filter { it.isLetter() }.take(1).uppercase(), value, index))
                consumedLines.add(index)
                consumedLines.add(index + 1 + (next?.index ?: 0))
            }
        }

        val loose = lines.mapIndexedNotNull { index, line ->
            if (index in consumedLines) return@mapIndexedNotNull null
            val value = cleanOptionText(line)
            if (looksLikeLooseOption(value)) index to value else null
        }

        val byLabel = labeled.associateBy { it.label }.toMutableMap()
        val orderedLabels = listOf("A", "B", "C", "D", "E", "F", "G", "H")

        if ("A" !in byLabel) {
            val bLine = byLabel["B"]?.lineIndex
            val aLoose = if (bLine != null) loose.lastOrNull { it.first < bLine } else null
            if (aLoose != null) byLabel["A"] = ParsedOptionLine("A", aLoose.second, aLoose.first)
        }

        for (i in 0 until orderedLabels.lastIndex) {
            val current = orderedLabels[i]
            val next = orderedLabels[i + 1]
            if (current in byLabel && next !in byLabel) {
                val currentLine = byLabel[current]?.lineIndex ?: continue
                val nextLoose = loose.firstOrNull { pair ->
                    pair.first > currentLine && byLabel.values.none { item -> item.lineIndex == pair.first }
                }
                if (nextLoose != null) byLabel[next] = ParsedOptionLine(next, nextLoose.second, nextLoose.first)
            }
        }

        if (byLabel.isEmpty() && loose.size >= 2) {
            loose.take(5).forEachIndexed { index, pair ->
                byLabel[orderedLabels[index]] = ParsedOptionLine(orderedLabels[index], pair.second, pair.first)
            }
        }

        val options = orderedLabels.mapNotNull { label -> byLabel[label]?.let { OptionItem(label, it.text) } }
        val firstLine = byLabel.values.minOfOrNull { it.lineIndex }
        return ParsedOptions(options, firstLine)
    }

    private fun cleanStem(lines: List<String>): String {
        return lines
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
            .filterNot { line -> progressPattern.matches(line) }
            .joinToString(" ")
            .replace(Regex("^\\d+\\s*/\\s*\\d+\\s*"), "")
            .replace(Regex("^(\\|\\s*)?(单选题|单选|单项选择题|多选题|多选|判断题|填空题|\\[单选题]|\\[多选题]|\\[判断题]|\\[填空题])\\s*"), "")
            .replace(scorePattern, "")
            // 移除 OCR 误抓的底部按钮文字（答题/交卷/确认答案 等，可能被夹在题干中间）
            .replace(Regex("\\s*答[题題]\\s*"), " ")
            .replace(Regex("\\s*(交卷|确认提交|确认答案|提交答案|查看答案解析|上一题|下一题|点击继续|跳过此题|答题卡|倒计时|剩余时间|收藏本题|收藏|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费)\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '|')
    }

    private fun cleanLine(input: String): String {
        return input
            .trim()
            .replace('（', '(')
            .replace('）', ')')
            .replace('：', ':')
            .replace('。', '.')
            .replace('．', '.')
            .replace(scorePattern, "")
            .replace(Regex("^[|I]+\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanOptionText(input: String): String {
        return cleanLine(input)
            .replace(Regex("^(答对了|答错了)\\s*"), "")
            .replace(Regex("\\s*(答对了|答错了)$"), "")
            .trim()
    }

    private fun looksLikeLooseOption(line: String): Boolean {
        if (!looksLikeOptionValue(line)) return false
        if (line.contains("?") || line.contains("？")) return false
        if (line.contains("规定") || line.contains("根据") || line.contains("应当")) return false
        if (line.length > 32) return false
        return line.any { it.isDigit() } ||
                line.endsWith("米") ||
                line.endsWith("日") ||
                line.endsWith("月") ||
                line.endsWith("年") ||
                line.length <= 14
    }

    private fun looksLikeOptionValue(value: String, fromLabel: Boolean = false): Boolean {
        if (value.isBlank()) return false
        // fromLabel=true：已有明确 A/B/C/D 标签，内容一律接受（含纯数字"1"/单字符，如"有效期为__年 → A.1 B.2 C.3 D.5"）。
        // 不能再走 looksLikeNoise——它会把纯数字、单字符、"1:2"这类都当噪音杀掉。
        if (fromLabel) return true
        if (looksLikeNoise(value)) return false
        if (progressPattern.matches(value)) return false
        return true
    }

    private fun looksLikeNoise(line: String, treatShortAsNoise: Boolean = true, treatNumericAsNoise: Boolean = true): Boolean {
        val value = cleanLine(line)
        if (value.isBlank()) return true
        if (treatShortAsNoise && value.length <= 1) return true
        if (progressPattern.matches(value)) return true
        if (wholeStatusPattern.matches(value)) return true
        if (treatNumericAsNoise && Regex("^[0-9:：\\-\\s]+$").matches(value)) return true
        if (Regex("返回|退出|刷新|重新加载|顺序练习|随机练习|章节练习|模拟考试|答题卡|上一题|下一题|提交答案|确认提交|确认答案|交卷|查看答案解析|点击继续|跳过此题|倒计时|剩余时间|收藏本题|收藏|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费").containsMatchIn(value)) return true
        if (value == "答" || value == "就绪" || value == "答题") return true
        // 章节/分类/进度类噪音（通用，覆盖各类刷题 App 顶部模板文字）
        if (Regex("^[一二三四五六七八九十百]+\\s*[、.,]\\s*").containsMatchIn(value) && value.length <= 12) return true
        if (Regex("^第\\s*[一二三四五六七八九十\\d]+\\s*[章节部分]").containsMatchIn(value)) return true
        if (Regex("^[A-Za-z]\\d*\\s*型题$").matches(value)) return true
        if (Regex("^第\\s*\\d+\\s*(?:/\\s*\\d+\\s*)?题$").matches(value)) return true
        if (Regex("^\\d+\\s*/\\s*\\d+\\s*题$").matches(value)) return true
        // 独立的题型/模式标签
        if (value in setOf("单选题", "多选题", "判断题", "填空题", "单选", "多选", "判断", "填空")) return true
        if (value in setOf("请输入答案", "请选择答案", "答案解析")) return true
        if (value.count { it.isDigit() } >= 3 && value.any { it.isLetter() }) return true
        return false
    }
}
