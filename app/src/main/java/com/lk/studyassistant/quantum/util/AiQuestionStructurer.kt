package com.lk.studyassistant.quantum.util

import org.json.JSONObject

object AiQuestionStructurer {

    const val MIN_CONFIDENCE = 0.4f

    fun promptForStrategy(strategy: com.lk.studyassistant.quantum.data.DisplaySettingsStore.IdentificationStrategy): String {
        return PROMPT
    }

    /**
     * 纯文字结构化 PROMPT：无障碍节点原始文字 → 结构化 JSON。
     * 比 Vision PROMPT 短很多；输出格式与 PROMPT 完全相同，
     * 这样 AiQuestionStructurer.parse() 可以复用同一解析逻辑。
     */
    const val TEXT_STRUCTURE_PROMPT = """
你是题目文字解析器。以下是从题目 App 屏幕节点树提取的原始文字（可能含界面噪音）。
请从中识别一道题目，输出结构化 JSON。

输出要求：只返回一个 JSON 对象，不含 Markdown 代码块或解释文字。

JSON 格式：
{
  "question_type": "single|multiple|judge|unknown",
  "stem": "题干原文（去掉题号和题型标签，不含选项）",
  "options": {"A":"","B":"","C":"","D":""},
  "is_complete": true,
  "confidence": "high|medium|low",
  "missing": [],
  "visible_questions_count": 1
}

规则：
1. question_type: single=单选, multiple=多选, judge=判断。只有这三种，判不出就填 unknown。
   注意：题干里带"（ ）"占位符的绝大多数是单选题，不要因为有括号就往别的题型上靠。
2. stem: 照搬原文，不改写、不补全、不截短
3. options: 单选/多选填 A-H；判断题填 {"A":"正确","B":"错误"} 或原文对错措辞
4. 忽略界面噪音：返回按钮、底部导航、进度条、页码、解析区域等
5. confidence=low 当文字过少或无法确认题型时
6. 只输出 JSON，以 { 开头以 } 结尾
7. 多道题时选第一道完整的（输入顺序 = 屏幕从下到上，第一道 = 最靠屏幕底部）
"""

    /** 通用模式 PROMPT（保留为默认，兼容历史） */
    const val PROMPT: String = """

你是题目截图结构化解析器。你的唯一任务是从截图提取题目信息，严禁答题、解释或推理。

输出严格要求：只返回一个JSON对象，不得包含Markdown代码块、解释文字、答案或任何其他内容。

JSON格式：
{
  "question_type": "single",
  "chapter": "",
  "stem": "",
  "options": {
    "A": "",
    "B": "",
    "C": "",
    "D": "",
    "E": "",
    "F": "",
    "G": "",
    "H": ""
  },
  "complete": true,
  "is_complete": true,
  "missing": [],
  "confidence": "high",
  "visible_questions_count": 1,
  "other_questions": []
}

═══════════════════════════════════════
识别规则（必须严格遵守）
═══════════════════════════════════════

【屏幕含多道题时的选择规则——1.1.18 完整性判定（核心）】

A3/A4 案例题、章节合并截图等场景，一张截图里常有多个子问题。
判定逻辑：客观判定每道题内容是否完整，选最底部的完整题作为主题。

is_complete 判定（内容驱动，以下条件全满足才算 true）：
  1. 题干文字完整：有明确的题目开始特征（题号/章节标签/完整句子首行），非截断的中间部分
  2. 题干结尾标点（？/。/：）存在
  3. 所有选项内容完整：
     · 单选/多选题：选项序列连续无跳号（如仅有 ABC 无 D 则不完整），且每个选项文字未被截断
     · 判断题："对"和"错"两个选项都出现
  4. 【重要】不再要求最后选项距屏幕下边缘必须有留白——屏幕滑至最底部时选项内容仍然完整

主问题（顶层 stem/options 字段）选择规则：
1. **完整性优先**：在 is_complete=true 的题里选
2. **最靠底部**：多道完整题时，选最后一个选项在屏幕位置最低的那道（底部边缘最靠下）
3. **没有完整题**：选最接近完整的那道，is_complete 必须标 false，confidence 标 low

visible_questions_count 字段：
- 屏幕上能识别出题目结构的题目数（含主问题，含不完整的）
- 单题截图：1
- 多题截图：2 或 3

other_questions 字段（数组，按屏幕从上到下顺序）：
- 屏幕上**除主问题外**的所有题（完整 + 不完整都列出）
- 每个元素格式：{"stem": "...", "type": "single", "is_complete": true, "options": {"A": "...", ...}}
- 不完整的题也要给一个尽量准确的 is_complete=false
- 单题场景：other_questions 为 []

is_complete 字段（顶层和每个 other_questions 各一份）：
- true  → 这道题题干 + 全部选项都完整可见，可以直接拿去匹配题库
- false → 题干被截 / 选项被截 / 只看到尾巴 / 只看到开头

示例 1（单题截图）：
  visible_questions_count: 1
  is_complete: true
  stem: "肺癌的主要临床表现有"
  other_questions: []

示例 2（A3/A4 案例题，屏幕同时显示 2 道子题且都完整，选最底部那道为主题）：
  visible_questions_count: 2
  is_complete: true             ← 主问题（底部那道）完整
  stem: "[案例精简]。该患儿的饮食原则是"
  other_questions: [
    {"stem": "[案例精简]。患儿可能的诊断是",
     "type": "single",
     "is_complete": true,         ← 上方那道也完整，但不是最底部
     "options": {"A": "猩红热", "B": "流行性腮腺炎", "C": "麻疹", "D": "百日咳"}}
  ]

示例 2b（A3/A4 案例题，3 道子题，只有中间和底部完整，选最底部那道为主题）：
  visible_questions_count: 3
  is_complete: true             ← 主问题（最底部完整题）完整
  stem: "[案例精简]。为做好消毒隔离，护士应"
  other_questions: [
    {"stem": "[案例精简]。患儿可能的诊断是",
     "type": "single",
     "is_complete": false,        ← 上方那道题干完整但选项被截
     "options": {"A": "猩红热", "B": "流行性腮腺炎"}},
    {"stem": "[案例精简]。该患儿的饮食原则是",
     "type": "single",
     "is_complete": true,         ← 中间那道完整，但不是最底部
     "options": {"A": "低盐饮食", "B": "低脂饮食", "C": "半流质", "D": "普食"}}
  ]

示例 3（用户停在两题之间，两道都不完整）：
  visible_questions_count: 2
  is_complete: false           ← 没一道完整 → 程序会让用户滚动
  confidence: "low"
  stem: "[ 最接近完整的题干 ]"
  other_questions: [
    {"stem": "...", "type": "single", "is_complete": false, "options": {...}}
  ]


【question_type 题型——硬约束，按优先级判定】
本版本只支持三种题型，除此之外一律 unknown：
- single → 单选题（若干选项选1个）
- multiple → 多选题（选2个或更多）
- judge → 判断题（对/错）
- 无法确定 → "unknown"

题型判定优先级（按顺序检查，匹配则定型）：
1. 截图顶部有"A1型题"/"A2型题"/"B1型题"/"B型题" 等医学考试题型分类标签
   → **强制 single**（这些都是单选格式，不要因为有 5 选项就猜多选）
2. 截图顶部有"A3型题"/"A4型题"/"A3/A4型题"标签（案例题）
   → 看当前子问题：通常也是 single（除非子问题明确标"多选"）
3. 截图里有独立的"多选题"标签（蓝色 chip / 圆角徽章 / 独立 TextView） → **必须** multiple
4. 截图里有独立的"单选题"标签 → single
5. 截图里有独立的"判断题"标签 → judge
6. 选项中有"对/错/正确/错误"且只有两个选项 → judge
7. **选项 ≥ 3 个 → 一定不是判断题**（哪怕题干里出现"判断"两个字，那是题干措辞不是题型）
8. 题干末尾"包括"、"哪些"、"哪几项"、"有哪些"、"包含" + 5+ 选项 → 倾向 multiple
   （注意：仅"哪项"、"是"、"为"、"属于"等单值提问 → 仍然是 single）
9. 选项数 = 5 + 题干"是"/"为"/"属于"/"的是"结尾 → single
10. 都不命中 → unknown（不要随便猜，宁可标 unknown 让下游兜底）

**"（ ）"/"____"占位符不是题型信号**：中文单选题的题干普遍带括号占位符，
看到它请继续按选项数判定，不要因此改题型。

**取消旧规则"不确定时倾向 multiple"——这条会把 A1 单选题误标为 multi。**
现在的原则：**有明确证据才定型，没证据就 unknown**。
- 看到 X1/X2/B1 型题标签 → 一律 single（这是医学考试的硬规则）
- 看到"多选题"chip → 一律 multiple
- 看到题干有"哪些"等并列性提问 + 大量选项 → multiple
- 其它情况 → 优先 single（医学考试 70%+ 是单选），实在不确定才 unknown

【chapter 章节】
- 截图中如有章节/知识点标签，填入此处
- 没有则为空字符串

【stem 题干提取——最容易出错】
1. 题干是题目正文，不包含题型标签、题号、选项
2. 题干必须在第一个选项(A)之前结束
3. 绝对不要把A/B/C/D/E/F/G/H选项的文字混入题干
4. 如果题干被换行打断，合并为一句完整文本
5. 不要包含"单选题""第117题"等标签文字

【题干原文照抄——硬约束】
- 必须逐字照搬截图原文，禁止改写、缩短、补充、补全标点
- 截图里的标点（"："/"。"/"？"/"，"）原样保留，不要替换成英文标点
- 截图里没有的字一个也不要加，不要根据上下文"补全"
- 例：截图是"肝移植术后的观察要点包括："→ 输出必须就是"肝移植术后的观察要点包括："
  · 不要输出"肝移植术后的观察要点包括哪些"
  · 不要输出"肝移植术后的观察要点"（少了"包括："）
  · 不要输出"肝移植病人术后的观察要点包括："（多加"病人"）

【A3/A4 案例题特殊规则——重要】

A3/A4 案例题的典型形态：
  顶部："三、A3/A4型题" 章节标签
  题号："14、" 一道大题号
  案例背景：一长段患者情况描述（年龄/性别/主诉/体征/化验等，常 100-300 字）
  子问题：1) 2) 3) 等多个小题，每个有自己的 A-E 选项

截图时用户通常只能看到 1 个子问题的题干 + 选项（其它子问题可能滚动出屏幕外）。
你的任务是输出"匹配题库时能用"的精简 stem。

**stem 输出格式（硬规则）**：
    [精简案例背景，60-100 字] + 。 + [当前子问题题干]

**案例背景精简规则**：

保留（必留）:
  · 患者基本：年龄/性别（"患儿男11岁"/"老年女72岁"）
  · 主诉：发病时间 + 核心症状（"右侧腮腺肿大1天入院"）
  · 关键体征：异常的关键检查值（"T39.8℃"/"BP200/110mmHg"）
  · 与诊断/治疗相关的关键线索（"腮腺管口分泌物"/"心电图ST抬高"）

删除（必删）:
  · "三、A3/A4型题14、" 等章节标签和题号
  · 所有指标都正常的描述（"血压心率呼吸正常"等可省）
  · 详细化验数字（保留异常关键值即可，正常的可省）
  · 详细家族史/生活习惯（除非与诊断相关）
  · "查体过程中..."等过程描述
  · 重复表述/同义反复
  · 入院后治疗过程（除非当前题在问治疗）

**1.1.13 关键词保真硬约束（影响题库匹配命中率）**：

保留的关键症状/部位/化验名称必须**逐字使用原文用词**，不要"翻译""同义改写"：
  · 原文"腮腺肿大" → 不要写成"腺体肿大"/"腮部肿胀"
  · 原文"右侧腮腺管口分泌物" → 不要简化成"口腔分泌物"
  · 原文"T 39.8℃" → 保留"39.8℃"，不要换成"高热"
  · 原文"BP 200/110mmHg" → 保留"200/110mmHg"，不要换成"血压升高"

子问题题干**必须逐字保留原文**，不要做任何同义改写：
  · 原文"患儿可能的诊断是" → 必须就是"患儿可能的诊断是"
  · 不要改成"该患儿最可能患的是"
  · 不要删句尾的"是"/"为"/"应该"/"包括"
  · 不要把"。"换成"？"或反过来

**stem 长度控制**：精简案例 60-100 字 + 句号 + 子问题题干 = 总长建议 80-150 字

**示例**

原始截图：
    三、A3/A4型题
    14、患儿男，11岁。发现右侧腮腺肿大1天入院。护理体检：
        T 39.8℃，P 110次/min，R 22次/min，BP 106/63mmHg，
        右侧腮腺肿痛，有触痛，口腔内腮腺管口可见分泌物，
        时有恶心，胃区不适，头痛，周身肌肉关节痛。
        患儿病来无腹胀、腹痛，进食差，大、小便正常。
    1) 患儿可能的诊断是
       A 猩红热  B 流行性腮腺炎  C 麻疹  D 百日咳  E 水痘

✅ 正确 stem 输出（精简案例 35 字 + 句号 + 子问题 11 字 = 47 字）：
    患儿男11岁,右侧腮腺肿大1天,T39.8℃,腮腺肿痛有触痛,腮腺管口有分泌物。患儿可能的诊断是

❌ 错误 stem 输出 1（没精简，包含章节标签）：
    三、A3/A4型题14、患儿男，11岁。发现右侧腮腺肿大1天入院。护理体检：T 39.8℃...

❌ 错误 stem 输出 2（精简过度，丢了关键症状）：
    患儿男11岁,腮腺肿大。患儿可能的诊断是

✅ options 输出：仅当前子问题的选项
    A: 猩红热
    B: 流行性腮腺炎
    C: 麻疹
    D: 百日咳
    E: 水痘

❌ 错误 options：把其它子问题的选项混进来（如下个子题的"低盐饮食 低碘饮食..."）

**子问题题号识别**：
  · "1)" "2)" "3)" / "(1)" "(2)" / "1." "2." 都是子问题编号
  · 不要把子问题编号塞进 stem，只取题干部分
  · 例："1) 患儿可能的诊断是" → 子问题题干是"患儿可能的诊断是"

**当截图只看到子问题没看到完整案例背景时**：
  · stem 只填子问题题干
  · 不要瞎补案例（不要凭空编内容）
  · confidence 标 "medium"（提示下游：缺案例上下文，置信度受影响）

**当截图是普通题（非 A3/A4）时**：
  · 完全按"题干原文照抄"规则，不要精简
  · 不要硬塞案例处理

【options 选项提取——最容易出错】
1. 圆形 A/B/C/D/E/F/G/H 标记是选项标识，每个选项独立提取
2. 每个选项只能包含自己的内容，绝对不能混入下一个选项
3. 按截图中视觉从上到下顺序排列选项
4. 短选项也必须完整保留
5. 选项内容跨多行时合并为完整文本
6. 卡片式布局的选项也要按视觉位置对应 A/B/C/D/E/F/G/H 提取
7. 如果截图中确实没有某个选项才省略
8. 选项最多支持到 H（医学考试 A1 型题常见 6-8 个选项）

【选项内容原文照抄——硬约束】
- 选项文本必须照搬截图原文（同题干硬约束）
- 不要把选项文字中的标点、数字、单位（mmol/L、°C 等）做任何"美化"
- 例：截图选项是"4.4～8.0mmol/L"→ 输出必须是"4.4～8.0mmol/L"，不要写成"4.4-8.0 mmol/L"

【必须忽略的非题目内容】
- 顶部系统状态栏（时间、电量、信号、网络图标）
- 返回按钮、标题栏（如"题型练习"、"顺序练习"、"三基题库_完整版"）
- 底部按钮（收藏、答题卡、上一题、下一题、提交答案、AI解析、讲解、交卷）
- 进度条、页码（如"第 3/771 题"、"2/200"）
- 广告、悬浮球、调试文字、答案框、悬浮答题助手
- "分享""收藏""答题卡""交卷""讲解""提交"等按钮文字
- "已答""未答""正确""错误"等统计
- "解析""答案""查看解析""试题解析""原始解析"区域
- "开通会员""立即开通""精编题库"等广告
- 微信/短信/通知弹窗（如"赵某某: xxx"这类）
- 桌面图标、应用切换器、最近任务列表

【complete 完整性】
- 题型、题干、必要选项都清晰可见 → true
- 有内容缺失或遮挡 → false

【missing 缺失字段】
- 填写截图中缺失的内容，如 ["选项C", "选项D"]
- 全部可见则为空数组 []

【confidence 置信度】
- high：题型、题干、全部选项都清晰完整
- medium：大部分内容清晰，个别选项可能有轻微遮挡
- low：较多内容模糊或不确定，或截图根本不是题目截图

【严禁输出以下内容】
- 答案（正确答案、参考答案）
- 解析、解释、分析
- Markdown代码块（```json```等）
- AI推理过程、思考过程
- "这道题选X""答案是X"等答题内容
- 任何非JSON的说明文字

只输出JSON对象本身，以{开头，以}结尾。

"""

    /** 视觉 API 产出的来源标签。题库侧靠它切换到"选项权重 0.60"的 Vision 打分式。 */
    const val SOURCE_VISION = "AI视觉"

    /** 文本 LLM 结构化产出的来源标签。同样是结构化结果，但输入是无障碍节点文字，不是截图。 */
    const val SOURCE_TEXT_LLM = "文本模型"

    /**
     * 解析模型返回的结构化 JSON。
     *
     * [source] 必须如实传：它既是日志里的 `src=`，也被
     * [com.lk.studyassistant.quantum.local.LocalQuestionBankRepository] 用来决定走哪套打分公式
     * （`source.contains("视觉")` → 选项重合率权重 0.60）。以前这里写死 "AI视觉"，
     * 于是无障碍链路的文本结构化结果也被当成视觉来源，日志误导且打分公式选错。
     */
    @JvmOverloads
    fun parse(raw: String, source: String = SOURCE_VISION): QuestionExtractResult {
        val jsonText = extractJsonObject(raw)
        val root = JSONObject(jsonText)

        val typeStr = root.optString("question_type", "").trim().lowercase()
        // 1.1.42：只接受 单选/多选/判断。模型若仍回 blank（旧提示词的残留习惯），
        // 归到 UNKNOWN——UNKNOWN 在题库侧是"题型不设约束"，比打一个必然不兼容的
        // blank 标签更安全。
        val type = when {
            typeStr == "single" || typeStr.contains("单选") -> QuestionType.SINGLE_CHOICE
            typeStr == "multiple" || typeStr.contains("多选") -> QuestionType.MULTIPLE_CHOICE
            typeStr == "judge" || typeStr.contains("判断") || typeStr.contains("对错") -> QuestionType.TRUE_FALSE
            else -> QuestionType.UNKNOWN
        }

        val stem = root.optString("stem", "").trim()
        val complete = root.optBoolean("is_complete", root.optBoolean("complete", false))
        val confidenceStr = root.optString("confidence", "low").trim().lowercase()
        val confidence = when (confidenceStr) {
            "high" -> 0.9f
            "medium" -> 0.7f
            else -> 0.4f
        }

        val optionsJson = root.optJSONObject("options")
        val options = mutableListOf<OptionItem>()
        if (optionsJson != null) {
            for (label in listOf("A", "B", "C", "D", "E", "F", "G", "H")) {
                val text = optionsJson.optString(label).trim()
                if (text.isNotBlank()) options.add(OptionItem(label, text))
            }
        }

        val missing = mutableListOf<String>()
        val missingJson = root.optJSONArray("missing")
        if (missingJson != null) {
            for (i in 0 until missingJson.length()) {
                val item = missingJson.optString(i).trim()
                if (item.isNotBlank()) missing.add(item)
            }
        }

        val isComplete = complete && when (type) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> stem.isNotBlank() && options.size >= 2
            QuestionType.TRUE_FALSE -> stem.isNotBlank()
            else -> false
        }

        val visibleCount = maxOf(root.optInt("visible_questions_count", 1), 1)
        val otherQs = mutableListOf<AlternativeQuestion>()
        val otherJson = root.optJSONArray("other_questions")
        if (otherJson != null) {
            for (i in 0 until otherJson.length()) {
                val o = otherJson.optJSONObject(i) ?: continue
                val oStem = o.optString("stem").trim()
                if (oStem.isBlank()) continue
                val oTypeStr = o.optString("type", "").trim().lowercase()
                val oType = when (oTypeStr) {
                    "single" -> QuestionType.SINGLE_CHOICE
                    "judge" -> QuestionType.TRUE_FALSE
                    "multiple" -> QuestionType.MULTIPLE_CHOICE
                    else -> QuestionType.SINGLE_CHOICE
                }
                val oOpts = mutableListOf<OptionItem>()
                val oOptsJson = o.optJSONObject("options")
                if (oOptsJson != null) {
                    for (label in listOf("A", "B", "C", "D", "E", "F", "G", "H")) {
                        val t = oOptsJson.optString(label).trim()
                        if (t.isNotBlank()) oOpts.add(OptionItem(label, t))
                    }
                }
                val oIsComplete = o.optBoolean("is_complete", true)
                otherQs.add(AlternativeQuestion(oStem, oType, oOpts, oIsComplete))
            }
        }

        return QuestionExtractResult(
            questionType = type,
            questionNumber = "",
            questionText = stem,
            options = options,
            blanks = emptyList(),
            isComplete = isComplete,
            missingFields = missing,
            unsupportedReason = null,
            confidence = confidence,
            source = source,
            rawJson = raw,
            visibleQuestionsCount = visibleCount,
            otherQuestions = otherQs
        )
    }

    fun isValid(result: QuestionExtractResult): Boolean {
        if (result.confidence < MIN_CONFIDENCE) return false
        if (result.questionType == QuestionType.UNKNOWN) return false
        if (result.questionText.isBlank()) return false
        if (result.questionType in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE)) {
            return result.options.size >= 2
        }
        return true
    }

    fun toStandardFormat(result: QuestionExtractResult): String {
        val sb = StringBuilder()
        sb.append("题型：${QuestionTypeClassifier.typeLabel(result.questionType)}\n")
        sb.append("题干：${result.questionText}\n")
        result.options.forEach { opt -> sb.append("${opt.label}：${opt.text}\n") }
        return sb.toString().trim()
    }

    private fun extractJsonObject(raw: String): String {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        throw IllegalStateException("AI 未返回 JSON 对象：${raw.take(120)}")
    }
}