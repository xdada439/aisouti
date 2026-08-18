package com.lk.studyassistant.quantum.floating

data class FloatingWindowUiState(
    val statusText: String = "就绪",
    val answerText: String = "",
    val isBusy: Boolean = false,
    val isPaused: Boolean = false,
    /** 来源标签：精准题库 / 模糊匹配 / 兜底模式 */
    val sourceLabel: String = "",
    /** 简要展示文本 */
    val debugText: String = "",
    /** 完整调试/测试详情文本 */
    val fullDebugText: String = "",
    /**
     * 当前题目的选项映射（A→选项A文本）。用于增强显示：
     * 若 answerText 是纯 A/B/C/D 字母且能在此映射中找到，悬浮窗会显示为 "A 12个月" 形式。
     * 拿不到选项文本时此字段为空，保持原 A/B/C/D 显示，不影响兼容性。
     */
    val answerOptions: LinkedHashMap<String, String> = LinkedHashMap(),
    /**
     * 1.1.13 新增：灰色提示文本（与 answerText 互斥）。
     * 用于"屏幕上没有任何一道完整可见的题"场景，悬浮窗显示
     *   "请滚动让题目完整显示"
     * 用 #80808080 灰色显示，跟正常答案区分。answerText 优先级更高。
     */
    val hintText: String = "",
    /**
     * 1.1.42 新增：答案下方的灰色免责提示，如
     *   "题库未检索到，依据视觉模型判断"
     * 只在走到"模型自身知识兜底"这一级时非空。与 answerText 同时显示
     * （小一号灰字，接在答案后面），不影响题库/资料命中的干净显示。
     */
    val noticeText: String = ""
) {
    val displayText: String
        get() = debugText.ifBlank { answerText }
}
