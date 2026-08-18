# 搜题链路（1.1.42）

> 本文件在 1.1.42 被整体重写。旧版本描述的是 1.1.8 的 "Vision-first / 无障碍优先" 链路，
> 与代码相反，已作废。权威说明见仓库根目录 `CLAUDE.md`，本文件只是它的链路速查图。

## 支持范围

**只支持 单选 / 多选 / 判断 三种题型。** 填空题在识别侧不产出，在导入侧被标记为"不支持"并跳过入库。

## 识别层

```
点击悬浮球「答」
  │  前置门禁：无障碍未开 / 敏感场景保护 / 上次未结束 → 中止
  │  抓球 Y 坐标快照（必须在隐藏悬浮球之前）
  ▼
隐藏悬浮球 → delay 400ms
  ▼
静默截屏
  ├─ Android 11+ ：无障碍截屏（等静止 ≤1500ms，多帧 32×32 感知哈希比对）
  ├─ 失败 / Android 11 以下：MediaProjection
  └─ 全部失败 ────────────────────────────────┐
  ▼                                            │
executeSearchPipeline（先查 30s 同屏缓存）       │
  │                                            │
  ├─ ① OCR（ML Kit，离线免费）                  │
  │    黑名单 isOcrUiNoise 去 UI 噪音            │
  │    白名单 buildOcrQuestionText 圈"题干+选项" │
  │      · 按「遇 A 开新组」分组                 │
  │      · 每组向上回溯收题干（遇题型标签/题号/  │
  │        上组选项/3 倍行高空白即停）           │
  │      · 只留「题干 + ≥2 选项」的完整组        │
  │      · 按球 Y 选最近的一组                   │
  │    → 答案层（只用题库）                      │
  │      命中 → 出答案                           │
  │      未命中 ↓                                │
  │                                              │
  ├─ ② Vision（视觉大模型，联网计费，需配 Key）   │
  │    以球 Y 为中心裁 ±45%                       │
  │    isValid：confidence ≥0.4 且题型≠UNKNOWN    │
  │    selectCompleteQuestion：选第一道 is_complete│
  │      一道都不完整 → 提示「未完全显示，请继续上滑」│
  │    → 答案层（三级全开，兜底标注"视觉模型"）    │
  │      未命中 ↓                                 │
  │                                               │
  └─ ③ 无障碍节点（离线免费）  ←──────────────────┘
       VisibleTextExtractor.extractBlocks，只留屏内块
       accessibilityBlockScore 选最佳块（含距球邻近度）
       完整性门槛 isAccessibilityQuestionUsable：
         题干 ≥8 字 且（选项 ≥2 或 判断题）且非 resource-id 垃圾
         不满足 → tryTextLlmStructure 文本 LLM 补救
         仍不满足 → 提示「未完全显示，请继续上滑」
       → 答案层（三级全开，兜底标注"语言模型"）
```

无障碍服务的用途是 **截屏 + 悬浮窗宿主 + 球坐标**，节点文字识别只是最后兜底。

## 答案层

```
① 精准题库（本地 SQLite）
     命中判定：score ≥ 0.30 且题型兼容
       · 单选 ↔ 多选 互兼容
       · 判断 严格匹配
       · score ≥ 0.95 → 题型不作约束
     选项内容 remap：按选项文本把题库 ABCD 映射到屏幕 ABCD
     答案定稿用**题库题型**，不用识别题型
     ↓ miss
② 资料 RAG（MaterialRepository.search topK=20）
     首块 score < 0.45 → 跳过本级（不投喂 LLM，避免照着无关资料瞎编）
     否则前 15 块交 callAnswerApi 判答
     ↓ miss
③ 模型自身知识兜底 callFallbackApi
     答案下方灰字：「题库未检索到，依据视觉模型/语言模型判断」
     ↓ 仍无
   "无法判断"
```

OCR 分支 `allowLlmEscalation = false`，只走 ①——未命中不在这里联网，交给 Vision 重新识别。

## 排障

`[Pipeline]` 看走了哪条路 → `[Extract]` 看题提取干不干净 → `[QuestionBank] miss_diagnosis`
对比查询 stem 与 top1 候选 stem。用户侧入口：用户中心 → 识别日志。
