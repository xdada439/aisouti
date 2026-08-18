package com.lk.studyassistant.quantum.util

import android.graphics.Rect

/**
 * 无障碍节点提取的单个候选题块。
 * 当屏幕有 ViewPager/RecyclerView 预加载时，会产生多个候选题块。
 */
data class CandidateQuestionBlock(
    /** 题号，如 94，null 表示无法确定 */
    val questionNo: Int?,
    /** 题型：单选/多选/判断/填空/未知 */
    val questionType: String,
    /** 题干文本（不含选项） */
    val questionText: String,
    /** 选项 map，按 A/B/C/D 顺序 */
    val options: LinkedHashMap<String, String>,
    /** 该题块的原始文本 */
    val rawText: String,
    /** 该题块在屏幕上的包围矩形 */
    val bounds: Rect,
    /** 该题块包含的源节点列表 */
    val nodes: List<VisibleTextExtractor.ExtractedNode>,
    /** 是否包含图片控件 */
    val hasImage: Boolean = false,
    /** 选择评分（由 QuestionBlockSelector 计算） */
    val score: Float = 0f,
    /** 评分详情（调试用） */
    val scoreDetails: String = ""
) {
    val hasOptions: Boolean get() = options.isNotEmpty()
    val isValid: Boolean get() = questionText.isNotBlank() || hasOptions
}
