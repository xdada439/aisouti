package com.lk.studyassistant.quantum

import com.lk.studyassistant.quantum.util.OcrLayout
import com.lk.studyassistant.quantum.util.OcrLayout.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 版面重建回归测试。
 *
 * 用例不是编的——[真机抓取] 标注的两组数据是 1.1.49 在 OnePlus PJE110 / ColorOS
 * 上用 `[OcrDump]` 抓到的真实行结构，逐行照搬（含 OCR 的错别字）。
 *
 * 这层的全部意义是"换个 App 也不能塌"，所以这里刻意混了几种不同版面：
 * 标签内容分离、标签内容同行、横排网格、无标签纯文本。
 */
class OcrLayoutTest {

    /** 复刻下游 FloatingWindowService.buildOcrQuestionText 的选项锚点判据 */
    private val anchor = Regex("""^\s*[（(]?([A-Ha-h])[)）.、:：]?\s*\S""")

    private fun anchorCount(boxes: List<Box>) = boxes.count { anchor.containsMatchIn(it.text.trim()) }

    // ── 真机抓取：「万用表测电阻属于()。」──────────────────────
    // 症状：选项字母与内容被 ML Kit 切成两块，y 几乎完全重合；B 的字母整个漏识别。
    // 修复前 anchor 只有 1 个（还是误判的「AI解析」），白名单直接失效。
    private fun realDeviceCase1() = listOf(
        Box("11:13", 40, 51, 150, 79),
        Box("个顺序练习", 60, 186, 300, 247),
        Box("变电值宁全导入版", 60, 413, 500, 457),
        Box("第3/866 题", 700, 437, 900, 477),
        Box("顺序练习", 60, 471, 260, 504),
        Box("单选題", 60, 562, 200, 593),
        Box("万用麦测电阻属于()。", 60, 652, 700, 700),
        Box("A", 80, 902, 120, 937),
        Box("直接法", 160, 904, 340, 938),
        Box("间接法", 160, 1096, 340, 1130),
        Box("C", 80, 1285, 120, 1322),
        Box("前援法", 160, 1286, 340, 1322),
        Box("D", 80, 1474, 120, 1509),
        Box("比铰法", 160, 1477, 340, 1515),
        Box("回答正确", 60, 1719, 300, 1767),
        Box("正确苔案:A", 60, 1814, 340, 1856),
        Box("你的答案:A", 60, 1897, 340, 1939),
        Box("☆收藏", 60, 2079, 200, 2128),
        Box("出答题卡", 400, 2082, 600, 2128),
        Box("AI解析", 800, 2084, 950, 2125),
        Box("下一题》", 700, 2258, 900, 2298),
        Box("く上一题", 100, 2259, 300, 2297)
    )

    @Test
    fun 真机用例_选项字母与内容被拆开时应并回同一行() {
        val out = OcrLayout.normalize(realDeviceCase1())
        val texts = out.map { it.text }
        assertTrue("A 与内容应合并，实际: $texts", texts.any { it == "A 直接法" })
        assertTrue("C 与内容应合并，实际: $texts", texts.any { it == "C 前援法" })
        assertTrue("D 与内容应合并，实际: $texts", texts.any { it == "D 比铰法" })
    }

    @Test
    fun 真机用例_修复后选项锚点数应足够触发白名单() {
        val before = anchorCount(realDeviceCase1())
        val after = anchorCount(OcrLayout.normalize(realDeviceCase1()))
        // 修复前只有 1 个（而且是误判的「AI解析」），白名单要求 >= 2
        assertEquals(1, before)
        assertTrue("重建后锚点应 >= 2，实际 $after", after >= 2)
    }

    @Test
    fun 真机用例_底部按钮栏合并后AI解析不再是行首锚点() {
        val out = OcrLayout.normalize(realDeviceCase1())
        val bottom = out.first { it.text.contains("AI解析") }
        // 三个按钮在同一视觉行，合并后「☆收藏」在最左，AI解析 不再位于行首
        assertTrue("底部按钮应合并成一行，实际: ${bottom.text}", bottom.text.startsWith("☆收藏"))
    }

    @Test
    fun 真机用例_上下相邻的标题副标题不应被误并() {
        val out = OcrLayout.normalize(realDeviceCase1())
        val texts = out.map { it.text }
        // 「变电值宁全导入版」(413-457) 与「顺序练习」(471-504) 不重叠，必须分开
        assertTrue(texts.any { it.contains("变电值宁全导入版") })
        assertTrue(texts.none { it.contains("变电值宁全导入版") && it.contains("顺序练习") })
    }

    // ── 其它版面：换个 App 也不能塌 ──────────────────────

    @Test
    fun 标签与内容本来就同行时保持不变() {
        val boxes = listOf(
            Box("题干：以下哪个正确", 60, 100, 700, 150),
            Box("A. 甲选项", 60, 200, 400, 250),
            Box("B. 乙选项", 60, 300, 400, 350)
        )
        val out = OcrLayout.normalize(boxes)
        assertEquals(3, out.size)
        assertEquals(2, anchorCount(out))
    }

    @Test
    fun 横排两列选项应被拆成独立选项() {
        val boxes = listOf(
            Box("A. 甲   B. 乙", 60, 200, 800, 250),
            Box("C. 丙   D. 丁", 60, 300, 800, 350)
        )
        val out = OcrLayout.normalize(boxes)
        val texts = out.map { it.text }
        assertEquals("应拆成 4 个选项，实际: $texts", 4, out.size)
        assertTrue(texts.any { it.startsWith("A") && it.contains("甲") })
        assertTrue(texts.any { it.startsWith("B") && it.contains("乙") })
        assertTrue(texts.any { it.startsWith("D") && it.contains("丁") })
    }

    @Test
    fun 题干里的字母并列不应被误拆() {
        // "A 和 B 都正确" —— 字母不连续递增（A 后面直接是 B 但中间无标点结构），
        // 且这里 A、B 后面没有选项分隔符，不应触发拆分
        val boxes = listOf(Box("下列说法中 A 和 B 都正确的是哪一项", 60, 200, 900, 250))
        val out = OcrLayout.normalize(boxes)
        assertEquals(1, out.size)
    }

    @Test
    fun 空输入与单行输入不崩() {
        assertEquals(0, OcrLayout.normalize(emptyList()).size)
        assertEquals(1, OcrLayout.normalize(listOf(Box("只有一行", 0, 0, 100, 50))).size)
    }

    @Test
    fun 合并后的行按x从左到右排序() {
        val boxes = listOf(
            Box("右边", 500, 100, 700, 150),
            Box("左边", 60, 102, 260, 152)
        )
        val out = OcrLayout.normalize(boxes)
        assertEquals(1, out.size)
        assertEquals("左边 右边", out[0].text)
    }

    @Test
    fun 合并后的包围盒应覆盖所有子块() {
        val boxes = listOf(
            Box("A", 80, 902, 120, 937),
            Box("直接法", 160, 904, 340, 938)
        )
        val out = OcrLayout.normalize(boxes)
        assertEquals(1, out.size)
        assertEquals(80, out[0].left)
        assertEquals(340, out[0].right)
        assertEquals(902, out[0].top)
        assertEquals(938, out[0].bottom)
    }
}
