# AI 搜题（aisouti）— 项目现状交接文档

> **本文档基于 1.1.44 改造后的实际代码核实，日期 2026-08-18。**
> 读完这一份即可上手，不需要再读 `docs/` 下的旧文档。

---

## ⚠️ 先读这条

`docs/` 下的旧文档描述的是 1.1.8 时期的链路（"Vision-first"、"无障碍优先"），与代码相反，**不要参考**。以本文件 + 代码为准。

1.1.42 相对旧文档的三处关键差异：

1. **识别顺序是「截屏 OCR → Vision → 无障碍节点」**，不是"无障碍优先"。
2. **题库命中阈值是 `0.30`**，不是旧文档写的 `0.46`。
3. **只支持单选 / 多选 / 判断三种题型**。填空题在识别侧不再产出，在导入侧被标记为"不支持"并跳过入库。

### 1.1.44 改了什么（相对 1.1.43）

| 改动 | 说明 |
|---|---|
| **识别阶段加并发守卫** | `screenshotInProgress` 只覆盖截屏（1~3s），识别阶段（最长可达 80s）之前完全没守卫，双击悬浮球会并发跑两条完整链路、Vision 计费两次。新增 `recognitionDeadlineMs` 时间戳守卫（[RECOGNITION_GUARD_MS] = 120s），正常路径在 `finally` 里清零，漏清也会自愈；`handleCapturedBitmap` / `handleAllCaptureSourcesFailed` 在每个长耗时步骤后补 `isActiveCapture` 复核 |
| **`NO_COMPLETE_QUESTION` 不再被当成失败** | 这是 Vision 花钱得出的"这屏没有完整题"的**结论**，不是识别失败。以前一视同仁地继续降级到无障碍，等于把结论丢掉再白跑一轮 |
| 该结论现在会进缓存 | 同屏连点不再重复计费。屏幕一滚动哈希就变，缓存自动失效 |
| **无障碍链路不再谎报"截图失败"** | `executeAccessibilityTextPipeline` 新增 `screenshotFailed` 参数。它有两个调用方：截屏真失败 / 截屏成功但 OCR+Vision 没答案。以前无条件写死 `SCREENSHOT_FAILED`，排障时直接把人带偏 |

### 1.1.43 改了什么（相对 1.1.42）

| 改动 | 说明 |
|---|---|
| **彻底移除激活/授权体系** | 安装即全功能，不联网校验、无账号、无设备指纹。删除 `ActivationActivity`、`LicenseStore`、`ServerApiClient`、`ServerConfig`、`ServerCredentialVerifier`、`DeviceFingerprint`、`BackendApiService` 及 `activity_activation.xml` |
| Launcher 改为 `MainActivity` | 首次启动弹一次「使用前须知」，同意后写本地标记 `pref_agreement_accepted`，之后不再弹 |
| 用户中心删除授权卡片 | 账号信息 / 激活状态 / 终身买断按钮全部移除，只留法律条款、版本、识别日志、题库诊断 |
| 用户协议 + 隐私政策重写 | 原文本还在描述"授权码 / 设备指纹 / RSA 签名 / 联系管理员购买"，与事实不符。已改为如实描述：不收集、不联网校验、AI 请求由用户设备直连服务商 |
| 服务商预设收敛为多模态可用的六家 | 通义千问 / 豆包 / 智谱GLM / Kimi / OpenAI / SiliconFlow，每家带 `hint` 说明各自的坑 |
| 移除 DeepSeek 预设 | DeepSeek 官方 API 不提供视觉模型（`deepseek-vl` 只有开源权重），预设 `deepseek-vl2` 是错的，用户配了读不了图 |
| `isReady` 放宽 + 新增 `hasVision` | 原来强制要求填视觉模型，导致只配文本模型的用户连"资料判答 / 模型兜底 / 无障碍文本结构化"都用不了——这三项根本不需要读图。现在 Vision 识别路线用 `hasVision` 单独把关 |

### 1.1.42 改了什么（相对 1.1.41）

| 改动 | 原因 |
|---|---|
| 题型判定收敛为三类，**结构优先于关键词** | 旧版 `contains("( )")` 排在单选之前，带括号占位符的单选题被判成填空；`contains("判断")` 排在选项判定之前，题干含"判断"二字的单选题被判成判断题。两者都导致题型不兼容 → 题库必然 miss |
| **打通模型自身知识兜底** | 旧版 `allowFallbackKnowledge` 恒 false，题库+资料都不中就直接"无法判断"；`callFallbackApi` 实现完整却无人调用 |
| **无障碍链路补齐降级链**（题库 → 文本 LLM 结构化 → 资料 → 自身知识） | 旧版无障碍只有"题库命中/不命中"两种结局 |
| **无障碍块增加完整性门槛** | 旧版无任何门槛，半截题也照送题库 |
| 兜底答案带免责提示（视觉模型 / 语言模型） | 让用户知道这条答案没有题库依据 |
| 删除 6 段死代码 + 15 个零引用文件 | 1.1.19 链路倒置的残留 |

---

## 一、项目是什么

Android 端**考试搜题悬浮窗**工具。用户在任意刷题 App 上点击悬浮球「答」，应用静默截屏 → 识别屏幕上的题目 → 在本地题库里检索答案 → 悬浮窗直接显示答案。

- **主要卖点**：本地题库离线命中，不联网、不花钱、响应快。
- **LLM 是补充**，不是主力：只有本地路径全部失手才调用。
- 商业形态：授权码激活制（RSA 签名 token），有配套 Python 后端做发卡与题库管理。

---

## 二、构建与运行

```bash
./gradlew :app:assembleDebug
```

| 项 | 值 |
|---|---|
| Gradle 根项目 | `StudyAssistant2`，只有 `:app` 一个模块 |
| `namespace` | `com.lk.studyassistant.quantum` |
| `applicationId` | `com.aisouti` ⚠️ 与 namespace 不同，别混 |
| `versionCode / versionName` | `43` / `1.1.41` |
| `compileSdk / targetSdk / minSdk` | `34 / 34 / 21` |
| JVM | 17 |
| 依赖版本管理 | `gradle/libs.versions.toml`（version catalog） |
| Release 签名 | 读根目录 `keystore.properties` → `ai_souti.keystore`。文件不存在时自动跳过签名配置，不会构建失败 |

关键依赖：`mlkit-text-recognition-chinese`（离线 OCR）、`hidden-api-bypass`、Retrofit + OkHttp。

**注意**：`local.properties`、`keystore.properties`、`ai_souti.keystore` 都在仓库里，含敏感信息，不要外传。

---

## 三、代码地图

包根：`app/src/main/java/com/lk/studyassistant/quantum/`

| 路径 | 职责 | 关键文件 |
|---|---|---|
| `service/` | **核心链路** | `FloatingWindowService.kt`（~85KB，搜题主流程全在这）、`MyAccessibilityService.kt`（无障碍服务：悬浮窗宿主 + 截屏 + 球坐标）、`ScreenCaptureService.kt`（MediaProjection） |
| `floating/` | 悬浮窗 UI 与渲染 | `FloatingWindowController.kt`（36KB，含答案显示增强、选项内容反查） |
| `local/` | **本地数据层** | `LocalQuestionBankRepository.kt`（58KB，题库召回 + 打分 + remap）、`LocalDatabase.kt`（SQLite schema）、`LocalQuestionParser.kt`（OCR 文本 → 结构化题目）、`MaterialRepository.kt`（资料 RAG）、`TextNormalizer.kt` |
| `util/` | 识别与解析工具 | `ScreenOcrEngine.kt`（ML Kit 封装）、`VisibleTextExtractor.kt`（无障碍节点树提取）、`AiQuestionStructurer.kt`（Vision 提示词 + JSON 解析）、`QuestionTypeClassifier.kt` |
| `data/` | 配置与远程 | `ApiConfigStore.kt`（用户填的 API Key/模型）、`ApiRepository.kt`（4 个 LLM 调用入口）、`DisplaySettingsStore.kt`、`FloatingProtectionStore.kt`、`RecognitionLogStore.kt` |
| `security/` | 悬浮窗防截屏 | `OverlaySkipScreenshotApplier.kt` |
| 根包 | Activity | `MainActivity`（**Launcher**）、`UserCenterActivity`、`UploadCenterActivity`（题库导入）、`AISettingsActivity`、`DisplaySettingsActivity`、`DebugPanelActivity` |

> `network/` 包已在 1.1.43 随激活体系一并删除。**App 现在唯一的网络请求，是用户自己配置 AI 接口后、由设备直连模型服务商的请求。**

`backend/`：Python FastAPI，发卡 + 题库管理 + RAG。**1.1.43 起 App 与它完全脱钩**，一行代码都不再调用。后端目录保留未动，如果不再需要发卡可以整个删掉。

---

## 四、核心链路（这是本项目的全部重点）

### 4.1 整体两层

```
识别层：屏幕 → 结构化题目（题干 + 选项 + 题型）
答案层：结构化题目 → 答案
```

两层解耦：任何识别路线产出同一个 `QuestionExtractResult`，都汇入同一个答案层。

### 4.2 入口

`FloatingWindowService.onOverlayTriggerCapture()`（第 214 行）——所有搜题都从这里开始。

前置门禁（任一不过即中止）：
1. 无障碍服务未开启 → 提示开启
2. 敏感场景保护中（`FloatingProtectionStore` 用户可配黑名单）→ 拦截
3. 上次识别未结束 → 拦截

然后：**先抓球 Y 坐标快照**（`lastBallCenterY`）——必须在隐藏悬浮球之前抓，之后就取不准了。这个坐标后续用于「一屏多题时选哪道」。

### 4.3 静默截屏

```
隐藏悬浮球（delay 400ms 让渲染稳定）
   ↓
Android 11+ ：无障碍截屏  ← 首选
   · 等屏幕静止：最长 1500ms，滚动停止 300ms 判定为静止
   · 多帧比对：最多 3 帧，间隔 500ms，32×32 感知哈希，连续两帧一致即采用
   · 单帧超时 3000ms
   ↓ 失败
Android 11 以下 / 无障碍截屏失败 ：MediaProjection
   ↓ 全部失败
直接跳到无障碍节点路线（executeAccessibilityTextPipeline）
```

### 4.4 识别层：三条路线，严格串行

进入 `executeSearchPipeline()`（第 757 行）。

**先查缓存**：32×32 感知哈希 + 球 Y 按 100px 分桶做 key，TTL 30s。同屏重复点击直接返回上次答案（避免 Vision 重复计费、避免 LLM 两次给不同答案）。只缓存"有意义的答案"，失败结果不缓存。

| 顺序 | 路线 | 引擎 | 成本 | 触发条件 |
|---|---|---|---|---|
| ① | **OCR** | ML Kit 中文 | 离线免费 | 截屏成功即执行 |
| ② | **Vision** | 用户配置的视觉大模型 | 联网计费 | OCR 无答案 **且** API 已配置 |
| ③ | **无障碍节点** | `VisibleTextExtractor` | 离线免费 | ①②均无答案，或截屏全部失败 |

> **无障碍服务是必需的，但它的用途是「截屏 + 悬浮窗宿主 + 球坐标」，不是文字识别。**
> 节点文字识别只是最后兜底。触发时第一行日志就是
> `[Source] skip=ACCESSIBILITY_NODE_TEXT reason=ocr_first_pipeline`

#### ① OCR 路线（`tryOcrPipeline`，第 1010 行）

- 门禁：OCR 全文 ≥ 8 字（`MIN_OCR_TEXT_LENGTH`）
- **白名单提取**（`buildOcrQuestionText`，第 1466 行）—— 这是 OCR 路线的精髓：
  1. 找出所有以 `A/B/C…` 开头的选项锚点行
  2. 按「遇到 A 就开新组」分组（单题=一组，多题=多组）
  3. 每组从首选项**向上回溯**收题干，遇下列边界即停：题型标签（单选题/判断题…）、题号（收入后停）、上一组选项、超过 3 倍行高的垂直空白
  4. 只保留「题干 + ≥2 选项」的完整组，再按球 Y 选最近的一组
  5. 输出 = 题干 + 本组选项，**边界外的一律丢弃**

  这是**白名单**而非黑名单：不是"删噪音"，而是"只圈题干+选项"。顶部 UI、底部答案泄露、共用题干的公共材料，全部被边界天然挡在外面。
- 解析：`LocalQuestionParser.parse`
- 可用性检查 `isOcrQuestionUsable`：题干 ≥ 8 字、题型 ≠ UNKNOWN、选择题选项 ≥ 2
- **答案层只用题库**（`allowLlmEscalation = false`）。未命中不在这里联网，交给下一条识别链路 Vision 重新识别

#### ② Vision 路线（`tryVisionPipeline`）

- 输入：以球 Y 为中心裁剪 ±45% 高度（`cropBitmapAroundBallForVision`）
- 提示词：`AiQuestionStructurer.promptForStrategy(strategy)`
  ⚠️ `strategy` 恒为 `GENERIC`——`DisplaySettingsStore.IdentificationStrategy.fromKey()` 无视入参直接 return GENERIC，枚举也只剩一个值。"用户可选识别策略"实际是死的。
- 超时 30s（内部 28s）
- 门禁 `AiQuestionStructurer.isValid`：`confidence ≥ 0.4` **且** 题型 ≠ UNKNOWN **且** 题干非空 **且** 选择题选项 ≥ 2
- 完整性筛选 `selectCompleteQuestion`：AI 给每道题打 `is_complete`，本地选第一道完整的；一道都不完整 → 返回 `NO_COMPLETE_QUESTION`，悬浮窗显示「未完全显示，请继续上滑」，不去瞎搜题库
- 答案层走完整三级降级，兜底提示为「依据**视觉模型**判断」

#### ③ 无障碍节点路线（`executeAccessibilityTextPipeline`）

- `VisibleTextExtractor.extractBlocks` 提取候选块，只保留屏内块（过滤 RecyclerView 预加载的屏幕外节点）
- `accessibilityBlockScore` 选最佳块：题干≥8字 +2、选项≥2 +3、有题号 +1、有题型 +1、长度分（≤400 归一）、**距球邻近度**
- **完整性门槛** `isAccessibilityQuestionUsable`（1.1.42 新增）：题干 ≥ 8 字 **且**（选项 ≥ 2 **或** 判断题）**且** 内容不是 resource-id 之类 UI 垃圾
  - 不满足 → 先用 `tryTextLlmStructure` 让文本 LLM 补救结构化
  - 补救仍不满足 → 显示「未完全显示，请继续上滑」，**不送题库**
- 答案层走完整三级降级，兜底提示为「依据**语言模型**判断」

### 4.5 答案层：三级降级，全部可达

`answerQuestionFromResult()`。

```
① 精准题库（本地 SQLite，score ≥ 0.30）
     ↓ miss
② 资料 RAG + LLM 判答（首块 ≥ 0.45 才投喂 LLM；低于则跳过本级）
     ↓ miss
③ 模型自身知识兜底 → 答案带「题库未检索到，依据X模型判断」灰字提示
     ↓ 仍无
   "无法判断"
```

`allowLlmEscalation = false` 时只走 ①（OCR 分支）。Vision / 无障碍分支传 true，
区别只在 `fallbackSource` 的措辞（视觉模型 / 语言模型）。

免责提示的落地路径：`TestDetail.fallbackSource` → `noticeOf()` → `FloatingWindowUiState.noticeText`
→ `FloatingWindowController.withNotice()` 用 Spannable 在答案下方接一行灰色小字
（不新增 View，避免悬浮窗高度变化引起位置跳动）。

#### 精准题库（`LocalQuestionBankRepository.searchWithCandidates`，第 317 行）

**五层召回**（逐层加宽，累积候选）：

| Tier | 策略 | LIMIT | 备注 |
|---|---|---|---|
| 0 | 按选项内容召回 | 200 | 仅 Vision 路径且选项 ≥ 2 |
| 1 | 4 字滑窗 LIKE OR | 300 | 主召回 |
| 2 | bigram AND | 200 | 收紧 |
| 3 | bigram OR | 500 | 加宽 |
| 4 | 全表 `ORDER BY id DESC` | 800 | 最后保底 |

**两套打分公式**：

```
Vision 专用式（source 含"视觉" 且 选项 ≥ 2）：
   final = 选项重合率 × 0.60 + 题干相似 × 0.30 + 全文相似 × 0.10

通用式（OCR / 无障碍）：
   final = 题干 × 0.58 + 选项 × 0.18 + 全文 × 0.14 + 包含奖励(0.30 / 0.22)

共用后置：题型一致 +0.06，结果截断到 1.0
```

**命中判定**：`score ≥ 0.30` **且** 题型兼容。题型兼容规则：
- 识别题型为 UNKNOWN → 放行
- 与题库题型相同 → 放行
- `score ≥ 0.95` → **直接信任，题型不作约束**
- 单选 ↔ 多选 **互相兼容**（识别层题型抖动时不能丢候选）
- 判断 / 填空 → 严格匹配

**选项内容对齐（remap）**：题库的 ABCD 顺序可能与屏幕不同，按**选项内容**把题库字母重映射到屏幕字母。
- 单个选项采信条件：相似度 ≥ 0.70 且与第二名差距 ≥ 0.10（防歧义）
- 整体成功率 < 0.80 → 降级
- **高分信任**：remap 失败但 `score ≥ 0.90` → 信任题库原字母，不丢答案（避免"整体明明命中却因一个 OCR 错别字丢答案"）
- remap 失败且分数不够高 → 整题丢弃

**答案定稿用题库存的题型**，不用识别层给的题型。这条很重要：Vision 把多选误判成单选时，若用识别层题型会把 `ABDE` 截成 `A`。

#### 资料 RAG（Vision / 无障碍分支）

`MaterialRepository.search(topK=20)` → 首块 score < `0.45` 则**不调 LLM**（资料不相关时投喂只会让模型照着无关资料瞎编），**降级到 ③ 模型自身知识** → 否则携带前 15 块交 `callAnswerApi`，超时 25s。

---

## 五、阈值速查表

| 常量 / 判据 | 值 | 作用 | 位置 |
|---|---|---|---|
| `MIN_OCR_TEXT_LENGTH` | 8 | OCR 全文与题干最短长度 | `FloatingWindowService:56` |
| `SCREENSHOT_HIDE_DELAY_MS` | 400ms | 悬浮球隐藏后等渲染稳定 | `:54` |
| `SCREENSHOT_STABILITY_MAX_WAIT_MS` | 1500ms | 等屏幕静止最长时间 | `:68` |
| `SCREENSHOT_QUIET_THRESHOLD_MS` | 300ms | 多久无滚动算静止 | `:70` |
| `SCREENSHOT_MAX_FRAMES` / `INTERVAL` | 3 / 500ms | 多帧哈希比对 | `:72,74` |
| `HASH_GRID_SIZE` | **32** | 感知哈希网格 ⚠️ 函数注释写"16×16"，是过期注释 | `:64` |
| `SCREENSHOT_CACHE_TTL_MS` | 30s | 同屏缓存 | `:63` |
| `MIN_CONFIDENCE` | 0.40 | Vision 结果准入 | `AiQuestionStructurer:7` |
| **题库命中阈值** | **0.30** | 低于即 miss | `LocalQuestionBankRepository:422` |
| 题型一致加分 | +0.06 | | `:364` |
| 题型兼容免检 | ≥ 0.95 | 分数够高则题型不约束 | `:418` |
| remap 失败仍信任 | ≥ 0.90 | 用题库原字母 | `:449` |
| remap 成功率下限 | 0.80 | | `:959` |
| 单选项 remap 采信 | ≥ 0.70 且差距 ≥ 0.10 | | `:999 / :1003` |
| `MATERIAL_MATCH_THRESHOLD` | 0.45 | 资料首块低于此跳过资料级，直接降到模型兜底 | `FloatingWindowService` companion |

> `BANK_SCORE_ESCALATE_THRESHOLD`（0.25）已随死代码在 1.1.42 一并删除。

**置信度体系**：`confidence` 字段只有 Vision 路线真正读，其余路线靠**结构检查**。

| 来源 | confidence | 是否被消费 | 实际门槛 |
|---|---|---|---|
| Vision（high/medium/low） | 0.9 / 0.7 / 0.4 | ✅ `isValid` 要求 ≥ 0.4 | + `is_complete` 完整性筛选 |
| OCR（完整/缺字段） | 0.82 / 0.35 | ❌ 写了没人读 | `isOcrQuestionUsable` 结构检查 |
| 无障碍 | 固定 0.7 | ❌ | `isAccessibilityQuestionUsable` 结构检查（1.1.42 新增） |
| **题库 score** | 0.00–1.00 | ✅ **唯一真正决定"信不信"的量** | ≥ 0.30 |

---

## 六、数据层

SQLite：`local_ai_search.db`，`DB_VERSION = 5`（`LocalDatabase.kt`）。

| 表 | 用途 |
|---|---|
| `local_question_bank` | 题库主表。选项 `option_a` ~ `option_h`（8 个）；`normalized_stem` / `normalized_options` / `normalized_full_text` 是入库时预计算的归一化文本，检索直接打这三列 |
| `local_question_bank_fts` | FTS4 虚拟表 |
| `local_material` / `local_material_chunk` | 资料库（RAG 用），分块存储 |
| `local_material_chunk_fts` | FTS4 |
| `local_import_record` | 导入记录 |

v5 升级直接 DROP 重建题库表（当时判断无老用户兼容包袱）。**再改 schema 要注意现在已有真实用户数据。**

导入入口：`UploadCenterActivity` → Excel/文档。归一化统一走 `TextNormalizer`——**检索准确率强依赖入库和查询两侧用同一套归一化**，改 `TextNormalizer` 必须同步重建题库的 normalized 列。

---

## 七、日志与诊断

`AppLogger` 内存环形缓冲，保留最近 **100** 条。`RecognitionLogStore` 持久化最近 **3 次启动**、每次最多 **60** 条记录。

用户侧查看路径：**用户中心 → 识别日志**（可复制全部）。

按 tag 读日志：

| Tag | 含义 |
|---|---|
| `[Search]` | 用户点击事件 |
| `[FloatingBall]` / `[FloatingProtection]` | 悬浮窗状态 / 敏感场景保护 |
| `[Screenshot]` | 稳定性等待、多帧哈希比对 |
| `[Pipeline]` | **主路径决策**：`choose=OCR_FIRST` / `ocr_no_answer_fallback_to_vision` / `cache_hit` |
| `[Source]` | 各识别源 try / success / failed / skip |
| `[OCR]` | ML Kit 引擎 |
| `[VisionApi]` | Vision 耗时与结果 |
| `[Extract]` | **提取出的题目结构**（题干/选项/题型）——排查"题识别成什么样"看这行 |
| `[QuestionBank]` | 题库召回各 Tier、候选分数、`miss_diagnosis` |
| `[OptionMatch]` | 选项匹配率明细 |
| `[AnswerRemap]` / `[AnswerDisplay]` | 字母反查 / 实际显示 |

**排障顺序**：`[Pipeline]` 看走了哪条路 → `[Extract]` 看题提取干不干净 → `[QuestionBank] miss_diagnosis` 把查询 stem 和 top1 候选 stem 对比，差异一目了然。

---

## 八、1.1.42 已删除的死代码

1.1.19 链路倒置的残留，已全部清理。留档备查，避免有人以为"这些能力还在"：

| 已删除 | 说明 |
|---|---|
| `tryAccessibilityNodeTextSource` | 原「无障碍优先」入口 |
| `tryBallZoneCaseExtract` | 共用题干**案例题**的球定位选题 |
| `processNodeSourceResult` / `executeFallback` | 旧的节点结果处理与独立兜底 |
| 案例题截图裁剪 + `lastCaseTarget*` 字段 | 依赖恒为 false 的 `lastSearchWasCaseQuestion` |
| `BANK_SCORE_ESCALATE_THRESHOLD` / `escalateOnLowBankScore` | 唯一使用点在死代码里 |
| 15 个零引用文件 | `ScreenRegionArbiter` `QuestionBlockSelector` `OcrPageVerifier` `QuestionTextParser` `ImageQuestionDetector` `DebugBitmapAnnotator` `BitmapCropUtils` `CropRegionStore` `ImagePreprocessor` `DashScopeRepository` `AppSettingsStore` `DashScopeApi` `DashScopeDtos` `NetworkModule` `QuantumConfig` |
| `src/` 下 3 个 `.bak_upload_*` 备份 | 不参与编译，但干扰阅读和全局搜索 |

`tryTextLlmStructure(rawText)` **保留并接回**：现在是无障碍链路的结构化补救环节。

仍然存在的"看起来能配置、实际是死的"：
- `IdentificationStrategy`——`fromKey()` 恒返回 `GENERIC`，枚举只剩一个值。

> **案例题（共用题干、一题多问）能力已随死代码删除**。当前一屏多题靠 OCR 白名单分组 + 球 Y 就近选题，共用题干场景没有专门处理。用户反馈"案例题识别不对"时，根因在这里。

---

## 九、改动时的注意事项

1. **改识别顺序** → 动 `executeSearchPipeline` 和 `handleCapturedBitmap` 里的无障碍降级判断。三条路线本身是解耦的。
2. **改题库匹配** → 打分在 `scoreVision` / `score`，阈值 0.30。改阈值前先用 `[QuestionBank] candidate_dist` 日志看分数分布，避免误伤。
3. **改归一化** → `TextNormalizer` 改动必须同步重建题库 normalized 列，否则查询侧和入库侧不一致，召回率会暴跌。
4. **改题型判定** → 三条铁律：
   - **结构优先于关键词**：选项 ≥ 3 一定是选择题，题干里的"判断"二字不算数。破坏这条会让一批单选题被打成判断题，而判断题在题库侧与选择题严格互斥 → 必然 miss。
   - **只产出 单选/多选/判断/UNKNOWN**，不要把填空加回来。拿不准就 UNKNOWN——UNKNOWN 在题库侧是"题型不设约束"，比打错标签安全。
   - 答案定稿用的是**题库题型**不是识别题型（`LocalQuestionBankRepository`），这是防抖动的关键设计，别"顺手改回来"。
   - 回归测试在 `app/src/test/.../QuestionTypeDetectionTest.kt`，改判定逻辑前先跑。
5. **判断题**统一显示"正确/错误"，不显示 A/B，也不拼成"A 对"。归一化在 `AnswerFormatter.finalAnswer`，显示守卫在 `FloatingWindowController`。
6. **悬浮球坐标**必须在截屏前抓（`lastBallCenterY`）。一屏多题的选题全靠它，OCR / Vision / 无障碍三条路线都用。
7. **兜底答案必须带免责提示**。走到"模型自身知识"这一级的答案没有题库依据，不加提示等于骗用户。
8. **不要信 `docs/` 下的旧文档**，也不要用它们的数字去"修正"代码。

---

## 十、术语表

| 词 | 含义 |
|---|---|
| **球 / 悬浮球** | 悬浮窗上的触发按钮。其 Y 坐标用于一屏多题时判断用户要做哪道 |
| **白名单提取** | OCR 路线的核心手法：以选项为锚点向上回溯圈定题干，边界外一律丢弃（对比"黑名单删噪音"） |
| **remap** | 按选项内容把题库的 ABCD 重映射到屏幕上的 ABCD |
| **Tier 0–4** | 题库的五层召回策略 |
| **案例题** | 共用一段题干的多道子题（A1/A2 型题）。1.1.42 起无专门处理 |
| **精准题库 / 模糊匹配 / 兜底模式** | 答案来源标签，分别对应题库命中 / 资料 RAG / 模型自身知识 |
| **免责提示** | 兜底答案下方的灰字「题库未检索到，依据视觉模型/语言模型判断」 |
