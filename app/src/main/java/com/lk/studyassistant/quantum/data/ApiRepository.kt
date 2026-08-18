package com.lk.studyassistant.quantum.data

import android.util.Base64
import android.graphics.Bitmap
import com.lk.studyassistant.quantum.util.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * API调用仓库。
 * 1. 视觉API：整屏截图 → 题目结构 JSON
 * 2. 答案API：题目JSON + 模糊匹配资料片段 → 答案
 * 3. 兜底API：题目JSON → 大模型自身知识判断
 * 4. 测试连接
 */
class ApiRepository(private val configStore: ApiConfigStore) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun buildClient(timeoutSec: Long = 30L): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }

    /** 视觉API：图片 → 结构化题目JSON（只识别题目，不答题） */
    fun callVisionApi(bitmap: Bitmap, prompt: String, timeoutSec: Long = 30L): Result<String> {
        val config = configStore.get()
        if (!config.isReady) {
            AppLogger.log("[ApiVision] failed reason=API_CONFIG_MISSING")
            return Result.failure(IllegalStateException("API_CONFIG_MISSING"))
        }
        AppLogger.log("[ApiVision] request_start model=${config.visionModel}")

        return runCatching {
            val imageDataUrl = bitmapToDataUrl(bitmap)
            val contentArray = JSONArray()
                .put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
                .put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply { put("url", imageDataUrl) })
                })

            val payload = JSONObject().apply {
                put("model", config.visionModel)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                }))
                put("temperature", 0)
                put("max_tokens", 1200)
            }
            val raw = execute(config.baseUrl, config.apiKey, payload, buildClient(timeoutSec))
            AppLogger.log("[ApiVision] response_raw=${raw.take(200)}")
            raw
        }.onFailure { e ->
            AppLogger.log("[ApiVision] failed reason=${e.message?.take(100)}")
        }
    }

    /** 模糊匹配API：题目JSON + 资料片段 → 答案 */
    fun callAnswerApi(questionJson: String, chunks: List<String>, timeoutSec: Long = 30L): Result<String> {
        val config = configStore.get()
        val model = config.textModel.ifBlank { config.visionModel }
        if (config.baseUrl.isBlank() || config.apiKey.isBlank() || model.isBlank()) {
            return Result.failure(IllegalStateException("API_CONFIG_MISSING"))
        }

        val chunksText = chunks.mapIndexed { i, c -> "[${i + 1}] $c" }.joinToString("\n")
        val prompt = buildAnswerPrompt(questionJson, chunksText)
        AppLogger.log("[ApiAnswer] request_start model=$model chunks=${chunks.size}")

        return runCatching {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("temperature", 0)
                put("max_tokens", 200)
            }
            val raw = execute(config.baseUrl, config.apiKey, payload, buildClient(timeoutSec))
            AppLogger.log("[ApiAnswer] response_raw=${raw.take(200)}")
            raw
        }.onFailure { e ->
            AppLogger.log("[ApiAnswer] failed reason=${e.message?.take(100)}")
        }
    }

    /**
     * 纯文字结构化 API：无障碍节点原始文字 → 结构化题目 JSON。
     * 输出格式与 callVisionApi 相同，调用方可复用 AiQuestionStructurer.parse()。
     * 比视觉 API 更快（无图片编码），用于节点文字本地解析不足时的补救。
     */
    fun callTextStructureApi(rawText: String, timeoutSec: Long = 15L): Result<String> {
        val config = configStore.get()
        val model = config.textModel.ifBlank { config.visionModel }
        if (config.baseUrl.isBlank() || config.apiKey.isBlank() || model.isBlank()) {
            AppLogger.log("[ApiTextStructure] failed reason=API_CONFIG_MISSING")
            return Result.failure(IllegalStateException("API_CONFIG_MISSING"))
        }
        val prompt = com.lk.studyassistant.quantum.util.AiQuestionStructurer.TEXT_STRUCTURE_PROMPT +
            "\n\n---原始文字开始---\n" + rawText + "\n---原始文字结束---"
        AppLogger.log("[ApiTextStructure] request_start model=$model text_len=${rawText.length}")
        return runCatching {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("temperature", 0)
                put("max_tokens", 800)
            }
            val raw = execute(config.baseUrl, config.apiKey, payload, buildClient(timeoutSec))
            AppLogger.log("[ApiTextStructure] response_raw=${raw.take(200)}")
            raw
        }.onFailure { e ->
            AppLogger.log("[ApiTextStructure] failed reason=${e.message?.take(100)}")
        }
    }

    /** 兜底模式API：题目JSON → 大模型自身知识判断（不使用外部资料） */
    fun callFallbackApi(questionJson: String, timeoutSec: Long = 30L): Result<String> {
        val config = configStore.get()
        val model = config.textModel.ifBlank { config.visionModel }
        if (config.baseUrl.isBlank() || config.apiKey.isBlank() || model.isBlank()) {
            return Result.failure(IllegalStateException("API_CONFIG_MISSING"))
        }

        val prompt = buildFallbackPrompt(questionJson)
        AppLogger.log("[FallbackApi] request_start model=$model")

        return runCatching {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("temperature", 0)
                put("max_tokens", 200)
            }
            val raw = execute(config.baseUrl, config.apiKey, payload, buildClient(timeoutSec))
            AppLogger.log("[FallbackApi] response_raw=${raw.take(200)}")
            raw
        }.onFailure { e ->
            AppLogger.log("[FallbackApi] failed reason=${e.message?.take(100)}")
        }
    }

    /** 测试连接 */
    fun testConnection(): Result<String> {
        val config = configStore.get()
        if (config.baseUrl.isBlank()) return Result.failure(IllegalStateException("BASE_URL_EMPTY"))
        if (config.apiKey.isBlank()) return Result.failure(IllegalStateException("API_KEY_EMPTY"))
        AppLogger.log("[ApiConfig] test_start")

        return runCatching {
            val payload = JSONObject().apply {
                put("model", config.visionModel.ifBlank { config.textModel })
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                }))
                put("max_tokens", 5)
            }
            val result = execute(config.baseUrl, config.apiKey, payload, buildClient(15L))
            AppLogger.log("[ApiConfig] test_success")
            result
        }.onFailure { e ->
            AppLogger.log("[ApiConfig] test_failed reason=${e.message?.take(120)}")
        }
    }

    /**
     * 读图能力实测（1.1.48）。
     *
     * [testConnection] 只发纯文本，所以它通过**只能说明地址通、Key 有效、模型名存在**，
     * 完全没验证这个模型能不能吃图片——填一个纯文本模型（如 qwen-plus）测试照样通过，
     * 等到真正搜题时才发现读不了图。
     *
     * 这里生成一张 96×96 的图，白底黑字写一个数字，问模型图里是什么数字：
     *  · 请求成功 → 至少证明该模型接受 image_url 输入
     *  · 回答里出现那个数字 → 进一步证明它是真看懂了，而不是在瞎猜
     */
    fun testVisionCapability(timeoutSec: Long = 30L): Result<String> {
        val config = configStore.get()
        if (config.visionModel.isBlank()) {
            return Result.failure(IllegalStateException("未填写视觉模型"))
        }
        val digit = "7"
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 72f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText(digit, 48f, 72f, paint)
        }
        AppLogger.log("[ApiVisionTest] request_start model=${config.visionModel}")

        return runCatching {
            val contentArray = JSONArray()
                .put(JSONObject().apply {
                    put("type", "text")
                    put("text", "图片里是一个阿拉伯数字，只回答这个数字本身，不要任何其它文字。")
                })
                .put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply { put("url", bitmapToDataUrl(bmp)) })
                })
            val payload = JSONObject().apply {
                put("model", config.visionModel)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                }))
                put("temperature", 0)
                put("max_tokens", 20)
            }
            val raw = execute(config.baseUrl, config.apiKey, payload, buildClient(timeoutSec)).trim()
            val recognized = raw.contains(digit)
            AppLogger.log("[ApiVisionTest] success recognized=$recognized reply=${raw.take(40)}")
            if (recognized) {
                "读图正常（模型正确识别出图中的数字）"
            } else {
                "该模型接受图片输入，但没认出图中的数字（回答：${raw.take(30)}）。可能识别能力偏弱，建议换更强的视觉模型。"
            }
        }.onFailure { e ->
            AppLogger.log("[ApiVisionTest] failed reason=${e.message?.replace("\n", " | ")?.take(160)}")
        }.also { runCatching { bmp.recycle() } }
    }

    private fun execute(url: String, apiKey: String, payload: JSONObject, client: OkHttpClient): String {
        val request = Request.Builder()
            .url(url.trim())
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 把请求地址和常见状态码的含义带进错误信息。
                // 原来只有 "HTTP 404:"（很多网关 404 连 body 都没有），用户完全无从下手。
                val advice = when (resp.code) {
                    401, 403 -> "API Key 无效或没有该模型的权限"
                    404 -> "地址或模型不存在。检查 Base URL 路径（多数服务商是 /v1/chat/completions），以及模型名是否属于该服务商"
                    429 -> "触发限流或余额不足"
                    in 500..599 -> "服务商侧异常，稍后重试"
                    else -> "请求被拒绝"
                }
                throw IllegalStateException(
                    "HTTP ${resp.code}（$advice）\n请求地址: ${url.trim()}\n返回: ${body.take(200).ifBlank { "(空)" }}"
                )
            }
            if (body.isBlank()) throw IllegalStateException("API返回为空")
            return parseAssistantContent(body)
        }
    }

    private fun parseAssistantContent(raw: String): String {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("返回中没有choices字段")
        if (choices.length() == 0) throw IllegalStateException("choices为空")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("返回中没有message字段")
        return when (val content = message.opt("content")) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val item = content.opt(i)
                    if (item is JSONObject) {
                        val text = item.optString("text").trim()
                        if (text.isNotBlank()) { if (isNotBlank()) append(" "); append(text) }
                    } else if (item is String && item.isNotBlank()) {
                        if (isNotBlank()) append(" "); append(item.trim())
                    }
                }
            }.trim().ifBlank { throw IllegalStateException("content解析为空") }
            else -> throw IllegalStateException("不支持的content格式")
        }
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun buildAnswerPrompt(questionJson: String, chunksText: String): String {
        return """
你是题目答案判断工具。

你只能根据我提供的题目和资料片段判断答案。
不要使用外部资料。不要解释。不要输出推理过程。只输出JSON。

题目：
$questionJson

资料片段：
$chunksText

输出格式：
{
  "source": "material",
  "answer": "",
  "confidence": "high/medium/low",
  "reason_code": "MATERIAL_ANSWERED/MATERIAL_NO_ANSWER"
}

答案规则：
- 单选题：A/B/C/D/E
- 多选题：AB/AC/BD/ABC/ABCD
- 判断题：对/错
- 填空题：标准文字答案
- 无法判断：answer为空，confidence为low
""".trimIndent()
    }

    private fun buildFallbackPrompt(questionJson: String): String {
        return """
你是题目答案判断工具。

我没有给你提供任何外部资料。请你仅根据你自身的知识判断这道题的答案。
不要解释。不要输出推理过程。只输出JSON。

题目：
$questionJson

输出格式：
{
  "source": "fallback",
  "answer": "",
  "confidence": "high/medium/low",
  "reason_code": "FALLBACK_ANSWERED/FALLBACK_NO_ANSWER",
  "warning": "兜底模式答案可能不准确，仅作为参考。"
}

答案规则：
- 单选题：A/B/C/D/E（且只能 1 个字母）
- 多选题：AB/AC/BD/ABC/ABCD/ABCDE（必须 ≥ 2 个字母）
- 判断题：对/错
- 填空题：标准文字答案
- 如果完全无法判断：answer为空，confidence为low

【多选题硬约束 —— 必须遵守】
- 如果"题型"字段是"多选"或"multiple"，answer **必须**包含 2 个或更多字母
- 多选题应当"宁可多选不要漏选"——只要某选项与题干主题有合理关联，就应该入选
- 不允许多选题只回答一个字母；如果你只看好 1 个，请在剩余选项里挑出第二个最可能的
- 题型为"多选"但你完全无法判断 → answer 写"无法判断"，不要写单字母
- 常见错误：把"包括"、"有哪些"、"哪几项"开头的题答成单字母——这类几乎都是多选

【单选题约束】
- 题型为"单选"或"single"时，只输出 1 个字母
- 不要输出"AB"这种多字母给单选题

【判断题约束】
- 题型为"判断"或"judge"时，answer 输出"对"或"错"
- 不要输出 A/B 字母
""".trimIndent()
    }
}
