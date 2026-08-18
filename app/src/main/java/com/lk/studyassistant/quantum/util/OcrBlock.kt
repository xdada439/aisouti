package com.lk.studyassistant.quantum.util

import android.graphics.Rect

/**
 * OCR 文本块（带屏幕坐标），由 ML Kit TextBlock 直接映射。
 */
data class OcrBlock(
    val text: String,
    val lines: List<OcrLine>,
    val bounds: Rect
)

/**
 * OCR 单行文本（带屏幕坐标和置信度）。
 */
data class OcrLine(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)

/**
 * 检测到的选项锚点。
 * 从 OCR 坐标结果中识别 A/B/C/D 等选项标记及其屏幕位置。
 */
data class OptionAnchor(
    val label: String,
    val text: String,
    val bounds: Rect,
    val lineIndex: Int  // 在排序后的 OcrLine 列表中的索引
)

/**
 * 仲裁后的题目区域结果。
 */
data class QuestionRegion(
    /** 题型：单选/多选/判断/填空/未知 */
    val questionType: String,
    /** 题干文本（OCR + 无障碍补字后） */
    val stemText: String,
    /** 选项列表 */
    val options: LinkedHashMap<String, String>,
    /** 题干在屏幕上的包围矩形 */
    val stemBounds: Rect?,
    /** 各选项在屏幕上的包围矩形 */
    val optionBounds: Map<String, Rect>,
    /** 置信度 0-1 */
    val confidence: Float,
    /** 识别来源 */
    val source: String
)

/**
 * 过滤后的无障碍节点快照。
 * 只保留坐标和文本，不保留 AccessibilityNodeInfo 引用以避免 recycle 问题。
 */
data class AccessibilityNodeSnapshot(
    val text: String,
    val bounds: Rect,
    val className: String,
    val packageName: String,
    val isContentDescription: Boolean
)

/**
 * 被丢弃的节点及其丢弃原因。
 */
data class DiscardInfo(
    val text: String,
    val bounds: Rect,
    val reason: String
)

/**
 * 当前屏幕 ROI（感兴趣区域）。
 * 排除状态栏、导航栏、悬浮窗自身、底部操作栏。
 */
data class ScreenRoi(
    val fullWidth: Int,
    val fullHeight: Int,
    val topExclude: Int,
    val bottomExclude: Int,
    val leftExclude: Int = 0,
    val rightExclude: Int = 0
) {
    val effectiveRect: Rect
        get() = Rect(leftExclude, topExclude, fullWidth - rightExclude, fullHeight - bottomExclude)

    val effectiveTop: Int get() = topExclude
    val effectiveBottom: Int get() = fullHeight - bottomExclude
    val effectiveLeft: Int get() = leftExclude
    val effectiveRight: Int get() = fullWidth - rightExclude
}

/**
 * 识别模式测试开关。
 */
enum class RecognitionMode(val label: String) {
    OCR_PLUS_ACCESSIBILITY("OCR + 无障碍"),
    OCR_ONLY("纯 OCR"),
    ACCESSIBILITY_FILTERED_ONLY("纯无障碍（坐标过滤）")
}
