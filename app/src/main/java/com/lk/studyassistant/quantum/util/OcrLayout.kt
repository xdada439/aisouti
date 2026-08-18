package com.lk.studyassistant.quantum.util

/**
 * OCR 版面重建（1.1.50）。
 *
 * **这一层是 OCR 路线跨 App 稳定性的地基，所以它必须是纯 Kotlin、可单测的**——
 * 不依赖 android.graphics.Rect，不依赖 Service，不针对任何具体刷题 App。
 *
 * 解决的问题：[OcrLine] 不是"屏幕上的一行"，而是 ML Kit 按自己的启发式切出来的块。
 * 真机实测（OnePlus/ColorOS，某刷题 App）：
 *
 * ```
 * y=902-937 | A          ← 选项字母（左侧小圆圈里）
 * y=904-938 | 直接法      ← 选项内容，y 与上一块几乎完全重合
 * ```
 *
 * 二者在屏幕上本就是同一行，只因中间水平留白大而被拆开。下游的选项锚点正则要求
 * "字母后紧跟非空白"，孤立的 `A` 匹配不上 → 锚点数不足 → 白名单机制整体失效。
 *
 * 这里做两件纯几何的事，都不假设任何具体版面：
 *  1. [rebuildVisualLines]：按 y 区间重叠度把块并回视觉行
 *  2. [splitInlineOptions]：一行里含多个连续选项字母时拆开（横排/网格版面）
 */
object OcrLayout {

    /**
     * y 重叠阈值（相对较矮那块的高度）。
     * 实测：选项字母与其内容 0.97（应合并）；上下相邻的标题/副标题 0.49（不应合并）。
     */
    const val DEFAULT_OVERLAP_RATIO = 0.6f

    /** 与坐标系解耦的文本块。Service 侧负责与 [OcrLine] 互转。 */
    data class Box(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val height: Int get() = bottom - top
    }

    /**
     * 把 y 区间重叠的块并回"屏幕上真正的一行"，行内按 x 从左到右拼接。
     *
     * 副作用（都是好的）：
     *  · 底部按钮栏（收藏/答题卡/AI解析）合并成一行噪音，
     *    顺带消掉"AI解析"因 A 开头被误判成选项锚点的问题
     *  · 本来就在一行的横排选项，合并前后都是一行，不受影响
     */
    fun rebuildVisualLines(
        boxes: List<Box>,
        overlapRatio: Float = DEFAULT_OVERLAP_RATIO
    ): List<Box> {
        if (boxes.size < 2) return boxes
        val sorted = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val groups = mutableListOf<MutableList<Box>>()

        for (b in sorted) {
            val g = groups.lastOrNull()
            if (g == null) {
                groups.add(mutableListOf(b))
                continue
            }
            val gTop = g.minOf { it.top }
            val gBottom = g.maxOf { it.bottom }
            val overlap = minOf(gBottom, b.bottom) - maxOf(gTop, b.top)
            val shorter = minOf(gBottom - gTop, b.height).coerceAtLeast(1)
            if (overlap.toFloat() / shorter >= overlapRatio) g.add(b) else groups.add(mutableListOf(b))
        }

        return groups.map { g ->
            if (g.size == 1) return@map g[0]
            val ordered = g.sortedBy { it.left }
            Box(
                text = ordered.joinToString(" ") { it.text.trim() }.trim(),
                left = ordered.minOf { it.left },
                top = ordered.minOf { it.top },
                right = ordered.maxOf { it.right },
                bottom = ordered.maxOf { it.bottom }
            )
        }
    }

    private val INLINE_LABEL = Regex("""(?:^|\s)([A-Ha-h])\s*[).、:：.]\s*""")

    /**
     * 一行内含多个选项时拆开，覆盖 `A.甲  B.乙` 这类横排/网格版面。
     *
     * 要求字母**严格递增连续**（A→B→C…）才拆，避免把题干里的
     * "A 和 B 都正确"误切成两个选项。
     */
    fun splitInlineOptions(boxes: List<Box>): List<Box> {
        val out = mutableListOf<Box>()
        for (b in boxes) {
            val hits = INLINE_LABEL.findAll(b.text).toList()
            val labels = hits.map { it.groupValues[1].uppercase()[0] }
            val sequential = hits.size >= 2 && labels.zipWithNext().all { (x, y) -> y == x + 1 }
            if (!sequential) {
                out.add(b)
                continue
            }
            val total = b.text.length.coerceAtLeast(1)
            val w = b.right - b.left
            for ((i, h) in hits.withIndex()) {
                val start = h.range.first
                val end = if (i + 1 < hits.size) hits[i + 1].range.first else b.text.length
                val seg = b.text.substring(start, end).trim()
                if (seg.isBlank()) continue
                // 宽度按字符占比粗分，只为保住左右顺序，精度不重要
                out.add(
                    Box(
                        text = seg,
                        left = b.left + w * start / total,
                        top = b.top,
                        right = b.left + w * end / total,
                        bottom = b.bottom
                    )
                )
            }
        }
        return out
    }

    /** 完整流程：先并回视觉行，再拆行内多选项。 */
    fun normalize(boxes: List<Box>, overlapRatio: Float = DEFAULT_OVERLAP_RATIO): List<Box> =
        splitInlineOptions(rebuildVisualLines(boxes, overlapRatio))
}
