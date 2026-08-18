package com.lk.studyassistant.quantum.local

import com.lk.studyassistant.quantum.util.OptionItem
import com.lk.studyassistant.quantum.util.QuestionType
import kotlin.math.max

object TextNormalizer {
    private val progressLineRegex = Regex("^\\s*\\d+\\s*/\\s*\\d+\\s*$")
    private val scoreRegex = Regex("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]|\\b\\d+(?:\\.\\d+)?\\s*分\\b")
    private val wholeStatusRegex = Regex("^(返回|退出|关闭|刷新|重新加载|就绪|答对了|答错了|上一题|下一题|提交答案|确认提交|确认答案|交卷|查看答案解析|点击继续|跳过此题|收藏|收藏本题|答题卡|倒计时|剩余时间|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费|答)$")

    fun normalizeForParse(input: String): String {
        if (input.isBlank()) return ""
        return input
            .replace('\u00A0', ' ')
            .replace('\r', '\n')
            .replace('（', '(')
            .replace('）', ')')
            .replace('【', '[')
            .replace('】', ']')
            .replace('：', ':')
            .replace('。', '.')
            .replace('．', '.')
            .replace('，', ',')
            .replace('？', '?')
            .replace('！', '!')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { progressLineRegex.matches(it) }
            .filterNot { wholeStatusRegex.matches(it) }
            .joinToString("\n")
            .trim()
    }

    fun cleanNoiseForSearch(input: String): String {
        return normalizeForParse(input)
            .replace(scoreRegex, " ")
            // 进度类：单独成行的 "X/Y"
            .replace(Regex("(?m)^\\s*\\d+\\s*/\\s*\\d+\\s*$"), " ")
            // 进度类：行内任意位置的 "第 X/Y 题" / "第X题" / "X/Y题"（兜底，正常已经被无障碍层剥离）
            .replace(Regex("第\\s*\\d+\\s*(?:/\\s*\\d+\\s*)?题"), " ")
            .replace(Regex("\\d+\\s*/\\s*\\d+\\s*题"), " ")
            // 章节/分类：A1型题 / B型题
            .replace(Regex("[A-Za-z]\\d*\\s*型题"), " ")
            // 章节序号：一、 二、（仅匹配开头独立形式）
            .replace(Regex("(?m)^\\s*[一二三四五六七八九十百]+\\s*[、.,]\\s*"), " ")
            .replace(Regex("(?m)^\\s*第\\s*[一二三四五六七八九十\\d]+\\s*[章节部分]\\s*"), " ")
            // 题型标签词（独立词，不影响题干内嵌的"判断"等字）
            .replace(Regex("(?<![\\u4e00-\\u9fff])(?:单选题|多选题|判断题|填空题|单选|多选|判断|填空)(?![\\u4e00-\\u9fff])"), " ")
            // 模式词
            .replace(Regex("顺序练习|随机练习|章节练习|模拟考试|每日一练|顺序模式|随机模式"), " ")
            .replace(Regex("返回|退出|关闭|刷新|重新加载|答对了|答错了|上一题|下一题|提交答案|确认提交|确认答案|交卷|查看答案解析|点击继续|跳过此题|收藏本题|收藏|答题卡|倒计时|剩余时间|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费|请输入答案"), " ")
            .replace(Regex("^\\s*第\\s*\\d+\\s*题[.)、]?\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val parseText = cleanNoiseForSearch(input)
        val normalizedChars = buildString(parseText.length) {
            for (ch in parseText.lowercase()) {
                append(
                    when (ch) {
                        in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                        in 'Ａ'..'Ｚ' -> 'a' + (ch - 'Ａ')
                        in '０'..'９' -> '0' + (ch - '０')
                        else -> ch
                    }
                )
            }
        }
        return normalizedChars
            .replace(Regex("[\\p{Punct}，。！？：；、（）()\\[\\]{}《》“”‘’]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun compact(input: String): String = normalize(input).replace(" ", "")

    fun normalizedFullText(stem: String, options: List<OptionItem>): String {
        return normalize(stem + " " + optionText(options))
    }

    fun optionText(options: List<OptionItem>): String = options
        .sortedBy { it.label }
        .joinToString(" ") { "${it.label} ${it.text}" }

    fun normalizeAnswer(raw: String, type: QuestionType): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return when (type) {
            QuestionType.SINGLE_CHOICE -> Regex("[A-Ea-e]").find(value)?.value?.uppercase().orEmpty()
            QuestionType.MULTIPLE_CHOICE -> Regex("[A-Ea-e]").findAll(value)
                .map { it.value.uppercase() }
                .distinct()
                .sorted()
                .joinToString("")
            QuestionType.TRUE_FALSE -> {
                // 判断题归一为"正确"/"错误"（含兼容旧版 A/B 存储格式）
                val v = value.trim()
                when {
                    v.equals("A", true) || v.contains("正确") || v.contains("对") || v.equals("true", true) || v == "√" -> "正确"
                    v.equals("B", true) || v.contains("错误") || v.contains("错") || v.equals("false", true) || v == "×" -> "错误"
                    else -> v
                }
            }
            QuestionType.FILL_BLANK -> value.replace(Regex("^答案[:：]?"), "").trim()
            QuestionType.UNKNOWN -> {
                // 题型未知：若含字母 A-H 视为选择题答案；否则原样返回（支持填空文本）
                val letters = Regex("[A-Ha-h]").findAll(value)
                    .map { it.value.uppercase() }.toList().distinct().sorted()
                if (letters.isNotEmpty()) letters.joinToString("") else value
            }
            else -> value
        }
    }

    // 1.1.42：本版本只支持 单选/多选/判断，填空题不再参与检索。
    fun supported(type: QuestionType): Boolean = type == QuestionType.SINGLE_CHOICE ||
            type == QuestionType.MULTIPLE_CHOICE ||
            type == QuestionType.TRUE_FALSE ||
            // UNKNOWN 也允许进题库匹配（典型场景：刷题 App 没显示"单选/多选"标签）
            // 不进行 typeBonus 加分，纯靠题干+选项相似度命中
            type == QuestionType.UNKNOWN

    fun typeKey(type: QuestionType): String = when (type) {
        QuestionType.SINGLE_CHOICE -> "single"
        QuestionType.MULTIPLE_CHOICE -> "multiple"
        QuestionType.TRUE_FALSE -> "judge"
        QuestionType.FILL_BLANK -> "blank"
        QuestionType.UNKNOWN -> "unknown"
        else -> "unknown"
    }

    /**
     * typeKey 的反向映射（Bug B 修复用）：题库存的题型 key → QuestionType
     * 用于"以题库答案的题型为准格式化最终答案"，避免 Vision 把多选误标单选导致答案被砍。
     */
    fun typeFromKey(key: String): QuestionType = when (key.lowercase().trim()) {
        "single" -> QuestionType.SINGLE_CHOICE
        "multiple" -> QuestionType.MULTIPLE_CHOICE
        "judge" -> QuestionType.TRUE_FALSE
        "blank" -> QuestionType.FILL_BLANK
        else -> QuestionType.UNKNOWN
    }

    fun typeFromText(raw: String): QuestionType {
        val text = normalizeForParse(raw).lowercase()
        return when {
            text.contains("多选") || text.contains("多项选择") || text.contains("multiple") -> QuestionType.MULTIPLE_CHOICE
            text.contains("判断") || text.contains("对错") || text.contains("true") || text.contains("false") -> QuestionType.TRUE_FALSE
            text.contains("填空") || text.contains("blank") -> QuestionType.FILL_BLANK
            text.contains("单选") || text.contains("单项选择") || text.contains("single") -> QuestionType.SINGLE_CHOICE
            else -> QuestionType.SINGLE_CHOICE
        }
    }

    fun similarity(a: String, b: String): Double {
        val left = normalize(a).split(' ').filter { it.length >= 2 }
        val right = normalize(b).split(' ').filter { it.length >= 2 }.toSet()
        val compactA = compact(a)
        val compactB = compact(b)
        val overlap = if (left.isEmpty() || right.isEmpty()) 0.0 else left.count { it in right }.toDouble() / max(left.size, 1)
        val bigramScore = bigramOverlap(compactA, compactB)
        val containsBonus = when {
            compactA.length >= 8 && compactB.contains(compactA) -> 0.30
            compactB.length >= 8 && compactA.contains(compactB.take(24)) -> 0.20
            else -> 0.0
        }
        return (overlap * 0.45 + bigramScore * 0.45 + containsBonus).coerceIn(0.0, 1.0)
    }

    fun bigramOverlap(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val left = a.windowed(2).distinct()
        val right = b.windowed(2).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.count { it in right }.toDouble() / max(left.size, 1)
    }
}

data class LocalSearchAnswer(
    val answer: String,
    val source: String,
    val confidence: Double,
    val detail: String = ""
)

data class QuestionCandidate(
    val stem: String,
    val answer: String,
    val score: Double,
    val sourceFile: String
)


object AnswerFormatter {
    fun finalAnswer(answer: String, type: QuestionType): String {
        if (!TextNormalizer.supported(type)) return "暂不支持"
        val normalized = TextNormalizer.normalizeAnswer(answer, type)
        return normalized.ifBlank { "无法判断" }
    }
}
