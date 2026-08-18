package com.lk.studyassistant.quantum.util

enum class QuestionType {
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    FILL_BLANK,
    UNSUPPORTED,
    UNKNOWN
}

data class OptionItem(
    val label: String,
    val text: String
)

data class BlankItem(
    val index: Int,
    val placeholder: String
)

/**
 * 屏幕上其它（次要）题目（1.1.12 新增 — 多题选择支持）。
 * Vision API 检测到屏幕含多个完整子问题时，主问题进 QuestionExtractResult 主字段，
 * 其它候选放在 otherQuestions 列表里。
 *
 * 1.1.13 新增 isComplete：AI 判断该题是否"题干完整 + 至少 3 个选项完整可见"。
 * 用于本地从 [main + others] 里挑出真正完整的那道当作"用户在看的题"。
 */
data class AlternativeQuestion(
    val stem: String,
    val questionType: QuestionType,
    val options: List<OptionItem>,
    val isComplete: Boolean = true
)

data class QuestionExtractResult(
    val questionType: QuestionType,
    val questionNumber: String,
    val questionText: String,
    val options: List<OptionItem>,
    val blanks: List<BlankItem>,
    val isComplete: Boolean,
    val missingFields: List<String>,
    val unsupportedReason: String?,
    val generatedOptions: Boolean = false,
    /** AI置信度 0~1，非AI来源为0 */
    val confidence: Float = 0f,
    /** 识别来源：AI视觉 / OCR / OCR+无障碍 */
    val source: String = "",
    /** AI原始返回JSON，非AI来源为空 */
    val rawJson: String = "",
    /** 1.1.12：屏幕上完整可见的题目总数（含主问题）。默认 1（单题）*/
    val visibleQuestionsCount: Int = 1,
    /** 1.1.12：除主问题外的其它完整题（A3/A4 案例题滚动场景）。默认空 */
    val otherQuestions: List<AlternativeQuestion> = emptyList()
) {
    companion object {
        fun empty(
            unsupportedReason: String? = null,
            source: String = ""
        ): QuestionExtractResult {
            return QuestionExtractResult(
                questionType = if (unsupportedReason != null) QuestionType.UNSUPPORTED else QuestionType.UNKNOWN,
                questionNumber = "",
                questionText = "",
                options = emptyList(),
                blanks = emptyList(),
                isComplete = false,
                missingFields = listOf("题干", "题型"),
                unsupportedReason = unsupportedReason,
                source = source
            )
        }
    }
}
