package com.lk.studyassistant.quantum.data

import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger

/**
 * API配置存储。
 * 用户自己填写 Base URL、API Key、模型名称，保存在本机 SharedPreferences。
 * 不硬编码、不写死、不依赖服务器。
 */
class ApiConfigStore(context: Context) {

    companion object {
        private const val PREFS = "api_config_prefs"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_SUPPORTS_VISION = "supports_vision"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_UPDATED_AT = "updated_at"

        /**
         * 服务商预设。
         *
         * 只是"填空模板"，点一下把 Base URL 和模型名填进输入框，用户仍可任意改。
         * 模型名会随服务商上下线而变动，最终以各家控制台为准——预设填错了不会
         * 导致崩溃，只会在「测试连接」时报模型不存在，用户改一下模型名即可。
         *
         * 收录标准：必须提供 OpenAI 兼容的 /chat/completions 接口，且有多模态模型，
         * 因为 [ApiRepository] 统一用 `messages[].content[] = {type:image_url}` 这一种格式发图。
         *
         * @param hint 该服务商特有的坑，点选时显示给用户
         */
        data class ProviderPreset(
            val name: String,
            val baseUrl: String,
            val visionModel: String,
            val textModel: String,
            val hint: String = ""
        )

        val PROVIDER_PRESETS = listOf(
            ProviderPreset("通义千问",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-vl-max", "qwen-plus",
                "阿里云百炼控制台开通后获取 API Key。用的是 DashScope 的 OpenAI 兼容模式。"),
            ProviderPreset("豆包",
                "https://ark.cn-beijing.volces.com/api/v3",
                "doubao-1.5-vision-pro-32k", "",
                "火山方舟的模型名要填「接入点 ID（ep- 开头）」或控制台给出的模型 ID，" +
                    "预设值仅供参考，请到方舟控制台复制实际值。"),
            ProviderPreset("智谱GLM",
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-4v-plus", "glm-4-flash",
                "智谱开放平台获取 API Key。glm-4v 系列支持读图。"),
            ProviderPreset("Kimi",
                "https://api.moonshot.cn/v1",
                "moonshot-v1-8k-vision-preview", "moonshot-v1-8k",
                "Moonshot 开放平台获取 API Key。需要用带 vision 的模型名才能读图。"),
            ProviderPreset("OpenAI",
                "https://api.openai.com/v1",
                "gpt-4o", "gpt-4o-mini",
                "国内网络通常需要自备代理，或填写中转服务商的 Base URL。"),
            ProviderPreset("SiliconFlow",
                "https://api.siliconflow.cn/v1",
                "Qwen/Qwen2.5-VL-72B-Instruct", "Qwen/Qwen2.5-7B-Instruct",
                "聚合平台，模型名要带组织前缀（如 Qwen/…），到模型广场复制完整名称。")
            // 注：DeepSeek 官方 API 目前不提供视觉模型（deepseek-vl 系列只有开源权重，
            // 官方 /chat/completions 调不到），填了也读不了图，故不列入预设。
            // 只想用文本能力的话，手动填 https://api.deepseek.com/v1 + deepseek-chat 到"文本模型"即可。
        )
        /**
         * 规范化用户填的 Base URL：补协议头 + 自动补 chat/completions 路径。
         *
         * 1.1.46 补的两条，都是真机上撞出来的：
         *  · 没有协议头就补 https 前缀。用户从控制台复制专属 endpoint 时经常只有裸域名
         *    （如 `llm-xxx.cn-beijing.maas.aliyuncs.com`），OkHttp 会直接抛
         *    "Expected URL scheme 'http' or 'https'"，而错误信息完全看不出该怎么办。
         *  · 只有域名没有路径时补 /v1/chat/completions。各家自建/专属 endpoint
         *    基本都是 OpenAI 兼容的 /v1 前缀，这样用户粘贴域名就能直接用。
         */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isBlank()) return url

            // ① 补协议头。注意要排除 "localhost:8000" 这种带端口的写法被误判成有 scheme。
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            if (url.endsWith("/chat/completions", ignoreCase = true)) return url

            // ② 只有 scheme://host（可带端口），没有任何路径 → 按 OpenAI 兼容惯例补全
            val afterScheme = url.substringAfter("://")
            if (!afterScheme.contains('/')) {
                return "$url/v1/chat/completions"
            }
            return when {
                // 阿里通义千问（DashScope 兼容模式）
                url.contains("dashscope.aliyuncs.com") && !url.contains("v1") ->
                    "$url/compatible-mode/v1/chat/completions"
                url.contains("dashscope.aliyuncs.com") ->
                    url.trimEnd('/') + "/chat/completions"
                // 火山引擎·豆包
                url.contains("volces.com") ->
                    "$url/chat/completions"
                // 智谱 GLM-4V
                url.contains("bigmodel.cn") ->
                    "$url/chat/completions"
                // 百度千帆
                url.contains("qianfan.baidubce.com") ->
                    "$url/chat/completions"
                // OpenAI 官方
                url.contains("openai.com") ->
                    "$url/chat/completions"
                // DeepSeek（DeepSeek-VL2）
                url.contains("deepseek.com") ->
                    "$url/chat/completions"
                // SiliconFlow（聚合视觉模型）
                url.contains("siliconflow.cn") ->
                    "$url/chat/completions"
                // Moonshot Kimi
                url.contains("moonshot.cn") || url.contains("moonshot.ai") ->
                    "$url/chat/completions"
                // 零一万物
                url.contains("lingyiwanwu.com") ->
                    "$url/chat/completions"
                // 腾讯混元
                url.contains("hunyuan.tencent.com") ->
                    "$url/chat/completions"
                // 通用：以 /v1、/v2、/v3 结尾 → 补全 /chat/completions
                Regex("/v\\d+$").containsMatchIn(url) ->
                    "$url/chat/completions"
                else -> url
            }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class ApiConfig(
        val baseUrl: String,
        val apiKey: String,
        val visionModel: String,
        val textModel: String,
        val supportsVision: Boolean,
        val enabled: Boolean,
        val updatedAt: Long
    ) {
        /**
         * 接口是否可用。**只要求填了地址、Key 和至少一个模型名**。
         *
         * 1.1.43 放宽：原来强制要求 visionModel 非空，导致只配了文本模型的用户
         * 连"资料判答 / 模型兜底 / 无障碍文本结构化"这些纯文本能力都用不了——
         * 而这三项根本不需要读图。读图能力单独用 [hasVision] 判断。
         */
        val isReady: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && enabled &&
                (visionModel.isNotBlank() || textModel.isNotBlank())

        /** 是否具备读图能力（截图 → 题目结构 的 Vision 识别路线需要） */
        val hasVision: Boolean
            get() = isReady && supportsVision && visionModel.isNotBlank()
    }

    fun save(config: ApiConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_VISION_MODEL, config.visionModel.trim())
            .putString(KEY_TEXT_MODEL, config.textModel.trim())
            .putBoolean(KEY_SUPPORTS_VISION, config.supportsVision)
            .putBoolean(KEY_ENABLED, config.enabled)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        AppLogger.log("[ApiConfig] save_success base_url=${config.baseUrl} vision_model=${config.visionModel}")
    }

    fun get(): ApiConfig {
        return ApiConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().trim(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim(),
            visionModel = prefs.getString(KEY_VISION_MODEL, "").orEmpty().trim(),
            textModel = prefs.getString(KEY_TEXT_MODEL, "").orEmpty().trim(),
            supportsVision = prefs.getBoolean(KEY_SUPPORTS_VISION, true),
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun isReady(): Boolean = get().isReady

    /** 是否具备读图能力。Vision 识别路线的门禁。 */
    fun hasVision(): Boolean = get().hasVision

    /** 日志安全：API Key只显示前4位和后4位 */
    fun maskKey(raw: String): String {
        val t = raw.trim()
        if (t.length <= 8) return "****"
        return t.take(4) + "****" + t.takeLast(4)
    }

    /** 转发到 [Companion.normalizeBaseUrl]，保持既有调用点写法不变。 */
    fun normalizeBaseUrl(raw: String): String = Companion.normalizeBaseUrl(raw)

}
