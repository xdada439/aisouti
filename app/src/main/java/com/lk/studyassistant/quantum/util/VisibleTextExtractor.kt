package com.lk.studyassistant.quantum.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class VisibleTextExtractor(
    private val ownPackageName: String
) {

    data class ExtractedNode(
        val text: String,
        val bounds: Rect,
        val className: String,
        val packageName: String,
        val isContentDescription: Boolean
    )

    companion object {
        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher"
        )

        private val UI_CHROME_TEXTS = setOf(
            "答题", "背题", "语音", "返回", "编辑", "更多",
            "分享", "收藏", "答题卡", "上一题", "下一题",
            "退出", "设置", "首页", "我的", "题库", "课程",
            "消息", "搜索", "练习", "考试", "刷题", "错题",
            "笔记", "下载", "打印", "夜间", "白天", "字号",
            "报告", "统计", "排名", "进度", "目录", "筛选",
            "提交答案", "交卷", "重做", "继续", "开始",
            "正确", "错误", "对", "错", "得分", "得分：",
            "展开", "收起", "全选", "取消",
            // 模式标签
            "顺序练习", "随机练习", "章节练习", "模拟考试", "每日一练",
            "请输入答案", "请选择答案", "答案解析", "解析"
            // 注意：单选题/多选题/判断题/填空题 这类题型标签词 **不在**这里过滤，
            // 因为 detectQuestionType 需要看到它们才能正确识别题型。
            // reverseExtractStem 通过 TYPE_LABEL_TEXTS 单独跳过它们不入题干，等效不会污染。
        )

        // 题型标签词（用于推断 questionType，不进 stem）
        private val TYPE_LABEL_TEXTS = setOf(
            "单选题", "多选题", "判断题", "填空题",
            "单选", "多选", "判断", "填空"
        )

        // 题干边界正则：向上回溯题干时遇到这些立刻停止（不再取更上面的节点进 stem）
        private val STEM_BOUNDARY_PATTERNS = listOf(
            Regex("""^[一二三四五六七八九十百]+\s*[、.,]"""),       // 一、 二、
            Regex("""^第\s*[一二三四五六七八九十\d]+\s*[章节部分]"""),  // 第一章 / 第2节
            Regex("""^[A-Za-z]\d*\s*型题$"""),                       // A1型题 / B型题
            Regex("""^\d+\s*[/／]\s*\d+$"""),                       // 2/200
            Regex("""^第\s*\d+\s*(?:[/／]\s*\d+\s*)?题$"""),         // 第2583题 / 第2583/9409题
            Regex("""^\d+\s*[/／]\s*\d+\s*题$""")                    // 2583/9409题
        )

        // 进度/题号串：匹配整个节点为这种串时跳过（不进 stem，也不停止回溯）
        private val PROGRESS_LIKE_PATTERNS = listOf(
            Regex("""^\d+\s*[/／]\s*\d+$"""),
            Regex("""^第\s*\d+\s*题$"""),
            Regex("""^第\s*\d+\s*[/／]\s*\d+\s*题$"""),
            Regex("""^[A-Za-z]\d*\s*型题$""")
        )

        private const val MIN_STEM_NODE_LENGTH = 6
        private const val MAX_STEM_BACKTRACK_NODES = 5

        private val OPTION_LABELS = setOf("A", "B", "C", "D", "E", "F", "G", "H")
        private const val SAME_LINE_Y_TOLERANCE = 20
        // 孤立 label 与目标文本节点之间允许的最大水平距离（px），防对角误合并
        private const val MAX_LABEL_CONTENT_HORIZONTAL_DX = 800
        // 垂直方向"重叠"判定容差
        private const val VERTICAL_OVERLAP_TOLERANCE = 20

        // 题号锚点正则: "单选 94、", "94、", "第94题", "(94)" 等
        private val QUESTION_ANCHOR_REGEX = Regex(
            """(?:单选|多选|判断|填空)?\s*(\d{1,4})\s*[、\.．]"""
        )
        private val QUESTION_ANCHOR_PAREN = Regex("""\((\d{1,4})\)""")
        // 支持 "第94题"、"第 94 题"、"第 2583/9409 题"
        private val QUESTION_ANCHOR_DI = Regex("""第\s*(\d{1,4})\s*(?:/\s*\d+\s*)?题""")

        // 题型前缀正则
        private val TYPE_MULTI = Regex("""多选""")
        private val TYPE_SINGLE = Regex("""单选""")
        private val TYPE_JUDGE = Regex("""判断""")

        // 选项模式
        private val OPTION_PATTERNS = listOf(
            Regex("""^([A-H])[\.、．]\s*(.+)$"""),
            Regex("""^\(([A-H])\)\s*(.+)$"""),
            Regex("""^([A-H])\s{1,2}(.+)$""")
        )

        // 图片相关关键词
        val IMAGE_KEYWORDS = setOf(
            "如图", "下图", "图中", "上图", "右图", "左图",
            "根据图片", "根据图示", "观察图片", "看图",
            "如图所示", "如下图", "见下图", "见上图",
            "示意图", "图示", "图片", "图像"
        )

        // 父节点有子节点且文本过长时，跳过父节点文本（避免把多道题一次性拼接）
        private const val PARENT_TEXT_SKIP_LENGTH = 100
    }

    /**
     * 收集全量节点，不过滤顶部工具栏和底部导航栏区域。
     * 专用于案例题检测：确保顶部子题编号不被工具栏过滤器遗漏。
     * 返回按 top/left 排序的节点列表（已去除自身包名、系统 UI、空文本节点）。
     */
    fun extractRawNodes(
        rootNode: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int = 0,
        navigationBarHeight: Int = 0
    ): List<ExtractedNode> {
        val screenRect = Rect(0, 0, screenWidth, screenHeight)
        val safeTop = statusBarHeight
        val safeBottom = screenHeight - navigationBarHeight

        val allNodes = mutableListOf<ExtractedNode>()
        collectVisibleNodes(rootNode, screenRect, allNodes, depth = 0)

        return allNodes.filter { node ->
            node.bounds.width() > 0 &&
                node.bounds.height() > 0 &&
                node.text.isNotBlank() &&
                node.packageName != ownPackageName &&
                node.packageName !in SYSTEM_UI_PACKAGES &&
                Rect.intersects(screenRect, node.bounds) &&
                !isFullyInExcludedZone(node.bounds, safeTop, safeBottom, screenHeight) &&
                !isUiChromeText(node.text)
            // 不过滤顶部工具栏和底部导航栏，确保案例题子题编号可被检测到
        }.sortedWith(compareBy<ExtractedNode> { it.bounds.top }.thenBy { it.bounds.left })
    }

    /**
     * 提取所有候选题块。
     * 按题号锚点分割，每个锚点开启一个新的 CandidateQuestionBlock。
     */
    fun extractBlocks(
        rootNode: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int = 0,
        navigationBarHeight: Int = 0
    ): List<CandidateQuestionBlock> {
        val screenRect = Rect(0, 0, screenWidth, screenHeight)
        val safeTop = statusBarHeight
        val safeBottom = screenHeight - navigationBarHeight
        val topToolbarBottom = safeTop + (screenHeight * 0.10).toInt()
        val bottomBarTop = (screenHeight * 0.88).toInt()

        // 1. 收集可见节点（叶子节点优先）
        val allNodes = mutableListOf<ExtractedNode>()
        collectVisibleNodes(rootNode, screenRect, allNodes, depth = 0)

        // 2. 过滤
        val filtered = allNodes.filter { node ->
            node.bounds.width() > 0 &&
                    node.bounds.height() > 0 &&
                    node.text.isNotBlank() &&
                    node.packageName != ownPackageName &&
                    node.packageName !in SYSTEM_UI_PACKAGES &&
                    Rect.intersects(screenRect, node.bounds) &&
                    !isFullyInExcludedZone(node.bounds, safeTop, safeBottom, screenHeight) &&
                    !isUiChromeText(node.text) &&
                    !isInToolbarZone(node.bounds, topToolbarBottom) &&
                    !isInBottomBarZone(node.bounds, bottomBarTop, screenHeight)
        }

        // 3. 排序
        val sorted = filtered.sortedWith(
            compareBy<ExtractedNode> { it.bounds.top }
                .thenBy { it.bounds.left }
        )

        // 4. 合并孤立选项字母
        val merged = mergeOrphanOptionLabels(sorted)

        // 5. 去重
        val deduplicated = deduplicatePreserveOptions(merged)

        if (deduplicated.isEmpty()) return emptyList()

        // 6. 找题号锚点，分割为候选题块
        val anchorIndices = findQuestionAnchors(deduplicated)

        if (anchorIndices.isEmpty()) {
            // 没有题号锚点，整个作为单个 block
            val block = buildBlock(deduplicated, 0, deduplicated.size)
            return if (block != null) listOf(block) else emptyList()
        }

        // 7. 按锚点分割
        val blocks = mutableListOf<CandidateQuestionBlock>()
        for (i in anchorIndices.indices) {
            val startIdx = anchorIndices[i].index
            val endIdx = if (i + 1 < anchorIndices.size) {
                anchorIndices[i + 1].index
            } else {
                deduplicated.size
            }

            // 检查是否碰到终止标记
            val actualEnd = findStopBoundary(deduplicated, startIdx, endIdx)

            val block = buildBlock(deduplicated, startIdx, actualEnd)
            if (block != null) {
                blocks.add(block)
            }
        }

        return blocks
    }

    /**
     * 查找所有题号锚点位置。
     * 返回 (nodeIndex, questionNo, typePrefix) 列表。
     */
    private fun findQuestionAnchors(nodes: List<ExtractedNode>): List<AnchorInfo> {
        val anchors = mutableListOf<AnchorInfo>()

        for ((index, node) in nodes.withIndex()) {
            val text = node.text.trim()

            // 尝试匹配 "单选 94、" / "94、" 等格式
            val match = QUESTION_ANCHOR_REGEX.find(text)
            if (match != null) {
                val qno = match.groupValues[1].toIntOrNull() ?: continue
                val prefix = text.substring(0, match.range.first).trim()
                val typePrefix = detectTypeFromPrefix(prefix)
                anchors.add(AnchorInfo(index, qno, typePrefix))
                continue
            }

            // 尝试匹配 "(94)" 格式
            val parenMatch = QUESTION_ANCHOR_PAREN.find(text)
            if (parenMatch != null) {
                val qno = parenMatch.groupValues[1].toIntOrNull() ?: continue
                anchors.add(AnchorInfo(index, qno, "未知"))
                continue
            }

            // 尝试匹配 "第94题"
            val diMatch = QUESTION_ANCHOR_DI.find(text)
            if (diMatch != null) {
                val qno = diMatch.groupValues[1].toIntOrNull() ?: continue
                val prefix = text.substring(0, diMatch.range.first).trim()
                val typePrefix = detectTypeFromPrefix(prefix)
                anchors.add(AnchorInfo(index, qno, typePrefix))
            }
        }

        return anchors
    }

    private fun detectTypeFromPrefix(prefix: String): String {
        return when {
            TYPE_MULTI.containsMatchIn(prefix) -> "多选"
            TYPE_SINGLE.containsMatchIn(prefix) -> "单选"
            TYPE_JUDGE.containsMatchIn(prefix) -> "判断"
            else -> "未知"
        }
    }

    /**
     * 找到终止边界（提交答案等标记）
     */
    private fun findStopBoundary(nodes: List<ExtractedNode>, start: Int, defaultEnd: Int): Int {
        for (i in start until minOf(defaultEnd, nodes.size)) {
            val text = nodes[i].text.trim()
            if (text == "提交答案" || text == "交卷" || text == "下一题" ||
                text.contains("提交答案") || text.contains("下一题")
            ) {
                return i
            }
            // 解析区标记
            if (text == "解析" || text == "答案" || text == "正确答案" ||
                text == "答案解析" || text == "试题解析"
            ) {
                return i
            }
        }
        return defaultEnd
    }

    /**
     * 从节点子列表构建一个 CandidateQuestionBlock。
     *
     * 反向提取策略（不依赖任何 App 特定排版）：
     *   1. 在 blockNodes 里找所有选项节点（A/B/C/D...）
     *   2. **从第一个选项向上回溯**，取离选项最近的长文本节点作为题干
     *      - 遇到章节边界（一、 / A1型题 / 第X/Y题 等）立刻停止
     *      - 跳过进度类节点（X/Y）和题型标签节点
     *      - 跳过过短节点（< 6 字）
     *      - 最多回溯 5 个节点
     *   3. 题号从题干首部抽取（"2、xxx" → no=2, stem=xxx）
     *   4. 题型四档兜底推断（独立标签 → 选项内容 → 占位符 → 选项数）
     *
     * 这样无论顶部有多少 UI 噪音（导航/题库名/进度/章节分类），
     * 都只会拿到选项之前最近的"看起来像题干"的那段。
     */
    private fun buildBlock(
        nodes: List<ExtractedNode>,
        startIdx: Int,
        endIdx: Int
    ): CandidateQuestionBlock? {
        if (startIdx >= endIdx || startIdx >= nodes.size) return null

        val blockNodes = nodes.subList(startIdx, minOf(endIdx, nodes.size))
        if (blockNodes.isEmpty()) return null

        // ── Step 1: 找选项 ──────────────────────────
        val optionEntries = findOptionLinesInNodes(blockNodes)

        // ── Step 2: 反向提取题干 ────────────────────
        val firstOptionIdx = optionEntries.firstOrNull()?.index ?: blockNodes.size
        val rawStem = reverseExtractStem(blockNodes, firstOptionIdx)

        // 没选项也没题干（既无 ABCD 又找不到长文本）→ 用兜底：取所有非噪音节点拼接（旧行为）
        val (questionText, questionNo) = if (rawStem.isBlank()) {
            val fallback = blockNodes
                .take(firstOptionIdx)
                .filter { !isStemBoundary(it.text.trim()) && !isProgressLike(it.text.trim()) }
                .joinToString(" ") { it.text }
                .trim()
            Pair(fallback, extractQuestionNo(blockNodes.firstOrNull()?.text?.trim().orEmpty()))
        } else {
            // 从题干首部剥题号
            val (stemNoNum, no) = stripLeadingQuestionNo(rawStem)
            Pair(stemNoNum, no ?: extractQuestionNo(blockNodes.firstOrNull()?.text?.trim().orEmpty()))
        }

        // ── Step 3: 题型四档兜底推断 ────────────────
        val options = LinkedHashMap<String, String>()
        for (entry in optionEntries) {
            if (options.containsKey(entry.label)) continue
            options[entry.label] = entry.content
        }
        val questionType = detectQuestionType(blockNodes, questionText, options)

        // ── Step 4: 校验选项结构（与之前一致）──────
        val validatedOptions = validateOptions(questionType, options)

        // ── 包围矩形 + 图片检测 + raw ───────────────
        val bounds = computeBlockBounds(blockNodes)
        val hasImage = blockNodes.any { node ->
            val cls = node.className.lowercase()
            cls.contains("image") || cls.contains("img") || cls.contains("picture") ||
                    cls.contains("photo") || cls.contains("chart") || cls.contains("graph")
        } || IMAGE_KEYWORDS.any { kw -> blockNodes.any { it.text.contains(kw) } }
        val rawText = blockNodes.joinToString("\n") { it.text }

        return CandidateQuestionBlock(
            questionNo = questionNo,
            questionType = questionType,
            questionText = questionText,
            options = validatedOptions,
            rawText = rawText,
            bounds = bounds,
            nodes = blockNodes.toList(),
            hasImage = hasImage
        )
    }

    /**
     * 反向提取题干：从第一个选项节点（exclusive）向上回溯，找连续的长文本作为题干。
     * 规则：
     *  - 命中 STEM_BOUNDARY_PATTERNS → 立刻停止
     *  - 命中 PROGRESS_LIKE_PATTERNS / 题型标签 → 跳过该节点继续向上
     *  - 长度 < MIN_STEM_NODE_LENGTH → 跳过继续向上
     *  - 命中选项模式（孤立选项标签等漏网）→ 跳过
     *  - 已经取到 1 个长文本之后，再遇到一个长文本则停止（题干一般是 1-2 行连续文本）
     *  - 最多回溯 MAX_STEM_BACKTRACK_NODES 个节点
     */
    private fun reverseExtractStem(
        blockNodes: List<ExtractedNode>,
        firstOptionIdx: Int
    ): String {
        if (firstOptionIdx <= 0) return ""
        val upper = minOf(firstOptionIdx, blockNodes.size)
        val collected = ArrayDeque<String>()
        var visited = 0

        for (i in (upper - 1) downTo 0) {
            if (visited >= MAX_STEM_BACKTRACK_NODES) break
            visited++
            val text = blockNodes[i].text.trim()
            if (text.isEmpty()) continue

            // 章节边界 → 停止
            if (isStemBoundary(text)) break

            // 进度类 / 题型标签 / 孤立选项 → 跳过该节点继续向上
            if (isProgressLike(text)) continue
            if (text in TYPE_LABEL_TEXTS) continue
            if (isOptionLine(text)) continue

            // 太短 → 跳过继续向上
            if (text.length < MIN_STEM_NODE_LENGTH) continue

            collected.addFirst(text)
            // 已经取到 ≥ 2 段，往上再遇到长文本可能就是上一题/标题了，停止
            if (collected.size >= 2) break
        }

        return collected.joinToString(" ").trim()
    }

    private fun isStemBoundary(text: String): Boolean =
        STEM_BOUNDARY_PATTERNS.any { it.matches(text) }

    private fun isProgressLike(text: String): Boolean =
        PROGRESS_LIKE_PATTERNS.any { it.matches(text) }

    private fun isOptionLine(text: String): Boolean {
        for (p in OPTION_PATTERNS) if (p.matches(text)) return true
        return false
    }

    /**
     * 从题干首部剥离题号前缀（"2、xxx" / "94. xxx" / "(94) xxx" / "第94题 xxx"），
     * 返回 (剥离后的纯题干, 题号Int?)。
     */
    private fun stripLeadingQuestionNo(stem: String): Pair<String, Int?> {
        val trimmed = stem.trim()

        // "94、xxx" / "94. xxx" / "94． xxx"
        QUESTION_ANCHOR_REGEX.find(trimmed)?.let { m ->
            if (m.range.first == 0) {
                val no = m.groupValues[1].toIntOrNull()
                val rest = trimmed.substring(m.range.last + 1).trim()
                return Pair(rest, no)
            }
        }
        // "(94) xxx"
        QUESTION_ANCHOR_PAREN.find(trimmed)?.let { m ->
            if (m.range.first == 0) {
                val no = m.groupValues[1].toIntOrNull()
                val rest = trimmed.substring(m.range.last + 1).trim()
                return Pair(rest, no)
            }
        }
        // "第94题 xxx"
        QUESTION_ANCHOR_DI.find(trimmed)?.let { m ->
            if (m.range.first == 0) {
                val no = m.groupValues[1].toIntOrNull()
                val rest = trimmed.substring(m.range.last + 1).trim()
                return Pair(rest, no)
            }
        }
        return Pair(trimmed, null)
    }

    /**
     * 题型推断（1.1.42 收敛为 单选/多选/判断/未知，不再产出填空）：
     *   0. **结构硬约束**：选项 ≥ 3 个 → 一定是选择题，"判断"关键词一律让位。
     *      否则题干里的"判断该患者…"会把 4 选项单选题打成判断题，而题库侧
     *      judge 与 single 是严格互斥的，误判即必然 miss。
     *   1. blockNodes 里有独立的 单选/多选/判断 节点 → 用之
     *   2. options 内容全是 正确/错误/对/错（且不超过 2 个）→ 判断
     *   3. 有 A~H 选项 → 单选（下游算法对单/多选共通，不会因此误判答案）
     *   4. 都没命中 → 未知
     */
    private fun detectQuestionType(
        blockNodes: List<ExtractedNode>,
        questionText: String,
        options: LinkedHashMap<String, String>
    ): String {
        // 0. 选项 ≥ 3 → 判断题被排除，后面所有"判断"信号都不采信
        val choiceOnly = options.size >= 3

        // 1. 独立题型标签节点（容错：节点文本完全等于题型词）
        for (node in blockNodes) {
            val t = node.text.trim()
            when (t) {
                "多选题", "多选" -> return "多选"
                "单选题", "单选" -> return "单选"
                "判断题", "判断" -> if (!choiceOnly) return "判断"
            }
        }

        // 全文扫描两段：
        //  阶段 1：直接 contains "X选题"。这些是 3 字技术词，几乎不会出现在题干句子里，
        //         安全可用 contains。修复了 (?![一-鿿]) lookaround 因 "多选" 后跟 "题"（中文）
        //         直接失配的问题（覆盖"多选题"被合并进父节点文本的场景）。
        //  阶段 2：备用 "X选"（无"题"字）作为短形式兜底，仍要求前后非中文，避免误识题干内"判断下列"。
        val fullText = blockNodes.joinToString(" ") { it.text }
        when {
            fullText.contains("多选题") -> return "多选"
            fullText.contains("单选题") -> return "单选"
            fullText.contains("判断题") -> if (!choiceOnly) return "判断"
            Regex("(?<![\\u4e00-\\u9fff])多选(?![\\u4e00-\\u9fff])").containsMatchIn(fullText) -> return "多选"
            Regex("(?<![\\u4e00-\\u9fff])单选(?![\\u4e00-\\u9fff])").containsMatchIn(fullText) -> return "单选"
            Regex("(?<![\\u4e00-\\u9fff])判断(?![\\u4e00-\\u9fff])").containsMatchIn(fullText) ->
                if (!choiceOnly) return "判断"
        }

        // 2. 选项内容全是 正确/错误/对/错（二元）→ 判断
        if (options.isNotEmpty() && !choiceOnly && options.values.all {
                it.trim() in setOf("正确", "错误", "对", "错", "是", "否", "√", "×")
            }) {
            return "判断"
        }

        // 3. 有 A~H 选项 → 默认按单选（多选答案字母也能命中题库）
        if (options.size >= 2) return "单选"

        // 5. 兜底未知
        return "未知"
    }

    private fun extractQuestionNo(text: String): Int? {
        QUESTION_ANCHOR_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        QUESTION_ANCHOR_PAREN.find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        QUESTION_ANCHOR_DI.find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private data class OptionEntry(val label: String, val index: Int, val content: String)

    private fun findOptionLinesInNodes(nodes: List<ExtractedNode>): List<OptionEntry> {
        val entries = mutableListOf<OptionEntry>()
        for ((idx, node) in nodes.withIndex()) {
            for (pattern in OPTION_PATTERNS) {
                val match = pattern.find(node.text.trim()) ?: continue
                val label = match.groupValues[1]
                val content = match.groupValues[2].trim()
                if (label in OPTION_LABELS && entries.none { it.label == label }) {
                    entries.add(OptionEntry(label, idx, content))
                }
                break
            }
        }
        return entries.sortedBy { it.index }
    }

    /**
     * 校验选项结构：
     * - 判断题不应出现 A/B/C/D
     * - 单选/多选题不应出现 正确/错误
     */
    private fun validateOptions(
        questionType: String,
        options: LinkedHashMap<String, String>
    ): LinkedHashMap<String, String> {
        if (options.isEmpty()) return options

        return when (questionType) {
            "判断" -> {
                // 判断题：只保留正确/错误/对/错/是/否
                val judgeOptions = LinkedHashMap<String, String>()
                for ((label, content) in options) {
                    val c = content.trim()
                    if (c in setOf("正确", "错误", "对", "错", "是", "否", "√", "×")) {
                        judgeOptions[label] = content
                    }
                }
                if (judgeOptions.isEmpty()) LinkedHashMap() else judgeOptions
            }

            "单选", "多选" -> {
                // 检查是否混入了判断类选项
                val hasJudgeContent = options.values.any {
                    it.trim() in setOf("正确", "错误", "对", "错", "是", "否")
                }
                if (hasJudgeContent && options.size <= 3) {
                    // 可能是判断题误标为单选/多选，清空选项
                    LinkedHashMap()
                } else {
                    options
                }
            }

            else -> options
        }
    }

    private fun computeBlockBounds(nodes: List<ExtractedNode>): Rect {
        if (nodes.isEmpty()) return Rect(0, 0, 0, 0)
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (node in nodes) {
            if (node.bounds.left < left) left = node.bounds.left
            if (node.bounds.top < top) top = node.bounds.top
            if (node.bounds.right > right) right = node.bounds.right
            if (node.bounds.bottom > bottom) bottom = node.bounds.bottom
        }
        return Rect(left, top, right, bottom)
    }

    // ─── 节点收集 ───────────────────────────────────

    private fun collectVisibleNodes(
        node: AccessibilityNodeInfo,
        screenRect: Rect,
        result: MutableList<ExtractedNode>,
        depth: Int
    ) {
        if (depth > 64) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (node.isVisibleToUser && Rect.intersects(screenRect, bounds)) {
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val hasChildren = node.childCount > 0

            if (!text.isNullOrBlank()) {
                // 父节点有子节点且文本过长 → 跳过，避免把多题拼成一条
                val tooLong = hasChildren && text.length > PARENT_TEXT_SKIP_LENGTH
                if (!tooLong) {
                    result.add(
                        ExtractedNode(
                            text = text,
                            bounds = Rect(bounds),
                            className = node.className?.toString().orEmpty(),
                            packageName = node.packageName?.toString().orEmpty(),
                            isContentDescription = false
                        )
                    )
                }
            }

            if (!contentDesc.isNullOrBlank() && contentDesc != text) {
                val tooLong = hasChildren && contentDesc.length > PARENT_TEXT_SKIP_LENGTH
                if (!tooLong) {
                    result.add(
                        ExtractedNode(
                            text = contentDesc,
                            bounds = Rect(bounds),
                            className = node.className?.toString().orEmpty(),
                            packageName = node.packageName?.toString().orEmpty(),
                            isContentDescription = true
                        )
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleNodes(child, screenRect, result, depth + 1)
            child.recycle()
        }
    }

    // ─── 区域过滤 ───────────────────────────────────

    private fun isFullyInExcludedZone(
        bounds: Rect, safeTop: Int, safeBottom: Int, screenHeight: Int
    ): Boolean {
        if (bounds.bottom <= safeTop && safeTop > 0) return true
        if (bounds.top >= safeBottom && safeBottom < screenHeight) return true
        return false
    }

    private fun isUiChromeText(text: String): Boolean {
        val t = text.trim()
        if (t.length <= 1) return false
        return t in UI_CHROME_TEXTS
    }

    private fun isInToolbarZone(bounds: Rect, toolbarBottom: Int): Boolean {
        return bounds.bottom <= toolbarBottom
    }

    private fun isInBottomBarZone(bounds: Rect, bottomBarTop: Int, screenHeight: Int): Boolean {
        return bounds.top >= bottomBarTop && bottomBarTop < screenHeight
    }

    // ─── 选项字母合并（空间感知）─────────────────────

    /**
     * 合并孤立选项字母节点 → 选项行节点。
     *
     * 旧逻辑：sort 后取相邻节点配对，要求 label.top ≈ content.top（20px 容差）。
     * 缺陷：圆形 label badge 居中、多行内容文字顶部偏上时，sort 后 content 在 label 之前；
     *      或两者 Y 差超过 20px → 合并失败 → 内容节点流入题干，label 节点被丢弃。
     *
     * 新逻辑（空间搜索）：
     *   对每个孤立 label 节点，扫描全节点池找最佳"目标文本"：
     *     1. 不能是另一个 label / 不能已是 "X 内容" 形式选项行
     *     2. 中心 X 必须在 label 右侧（label.right < content.centerX）
     *     3. 垂直方向必须有重叠（容差 ±20px）
     *     4. 水平距离不超过 800px（防对角误合并）
     *   距离公式：dx + |center_y_diff| / 2；选最小值
     *   先来后到：同一个 content 只能被一个 label 占用
     *
     * 这覆盖了：
     *   · 单行选项：label 与 content 完全同 Y，相邻配对自然命中
     *   · 多行选项：label 居中、content 起于上方，按重叠判定能找到
     *   · 反向排序（content 在 label 前/后均可）
     */
    private fun mergeOrphanOptionLabels(nodes: List<ExtractedNode>): List<ExtractedNode> {
        if (nodes.size < 2) return nodes

        // 1. 收集所有"孤立 label"索引
        val orphanIndices = nodes.indices.filter { idx ->
            val t = nodes[idx].text.trim()
            t.length <= 2 && OPTION_LABELS.any { t.equals(it, ignoreCase = true) }
        }
        if (orphanIndices.isEmpty()) return nodes

        val consumed = mutableSetOf<Int>()                       // 被某 label 占用的内容节点索引
        val replacements = mutableMapOf<Int, ExtractedNode>()    // label 索引 → 合并后节点

        // 2. 对每个 label，空间搜索最佳 content
        for (labelIdx in orphanIndices) {
            if (labelIdx in consumed) continue
            val labelNode = nodes[labelIdx]
            val labelBounds = labelNode.bounds
            val labelText = labelNode.text.trim()
            val labelCenterY = (labelBounds.top + labelBounds.bottom) / 2

            var bestIdx = -1
            var bestDist = Int.MAX_VALUE

            for (j in nodes.indices) {
                if (j == labelIdx || j in consumed || j in replacements) continue
                val cand = nodes[j]
                val ct = cand.text.trim()

                // 排除：另一个孤立 label / 已成形的选项行（"A 内容"）
                if (ct.length <= 2 && OPTION_LABELS.any { ct.equals(it, ignoreCase = true) }) continue
                if (isOptionLine(ct)) continue

                // 垂直重叠（含 ±20px 容差）
                val verticalOverlap =
                    cand.bounds.bottom + VERTICAL_OVERLAP_TOLERANCE >= labelBounds.top &&
                    cand.bounds.top - VERTICAL_OVERLAP_TOLERANCE <= labelBounds.bottom
                if (!verticalOverlap) continue

                // 中心 X 在 label 右侧
                val candCenterX = (cand.bounds.left + cand.bounds.right) / 2
                if (candCenterX <= labelBounds.right) continue

                // 水平距离上限
                val dx = (cand.bounds.left - labelBounds.right).coerceAtLeast(0)
                if (dx > MAX_LABEL_CONTENT_HORIZONTAL_DX) continue

                // 综合距离
                val candCenterY = (cand.bounds.top + cand.bounds.bottom) / 2
                val dy = kotlin.math.abs(candCenterY - labelCenterY)
                val dist = dx + dy / 2

                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = j
                }
            }

            if (bestIdx >= 0) {
                val candNode = nodes[bestIdx]
                val candText = candNode.text.trim()
                val merged = ExtractedNode(
                    text = "$labelText $candText",
                    bounds = Rect(
                        minOf(labelBounds.left, candNode.bounds.left),
                        minOf(labelBounds.top, candNode.bounds.top),
                        maxOf(labelBounds.right, candNode.bounds.right),
                        maxOf(labelBounds.bottom, candNode.bounds.bottom)
                    ),
                    className = labelNode.className,
                    packageName = labelNode.packageName,
                    isContentDescription = false
                )
                replacements[labelIdx] = merged
                consumed.add(bestIdx)
            }
        }

        // 3. 输出：consumed 节点丢弃，replacements 替换原 label 节点
        val result = mutableListOf<ExtractedNode>()
        for (i in nodes.indices) {
            if (i in consumed) continue
            result.add(replacements[i] ?: nodes[i])
        }
        return result
    }

    // ─── 去重 ───────────────────────────────────────

    private fun deduplicatePreserveOptions(nodes: List<ExtractedNode>): List<ExtractedNode> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ExtractedNode>()

        for (node in nodes) {
            val normalized = node.text.trim()
            if (normalized.isEmpty()) continue

            val isOptionLine = OPTION_LABELS.any { label ->
                normalized.startsWith("$label.") || normalized.startsWith("$label、") ||
                        normalized.startsWith("$label．") || normalized.startsWith("$label ") ||
                        normalized.startsWith("($label)")
            }

            if (isOptionLine) {
                if (normalized !in seen) {
                    result.add(node)
                    seen.add(normalized)
                }
            } else if (normalized !in seen) {
                result.add(node)
                seen.add(normalized)
            }
        }
        return result
    }

    private data class AnchorInfo(
        val index: Int,
        val questionNo: Int,
        val typePrefix: String
    )
}
