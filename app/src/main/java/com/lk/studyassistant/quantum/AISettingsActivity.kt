package com.lk.studyassistant.quantum

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lk.studyassistant.quantum.data.ApiConfigStore
import com.lk.studyassistant.quantum.data.ApiRepository
import com.lk.studyassistant.quantum.util.AppLogger
import com.lk.studyassistant.quantum.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AISettingsActivity : AppCompatActivity() {

    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etVisionModel: EditText
    private lateinit var etTextModel: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentConfig: TextView
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnBack: Button
    private lateinit var llProviderPresets: LinearLayout

    private val configStore by lazy { ApiConfigStore(this) }
    private val repo by lazy { ApiRepository(configStore) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_settings)

        AppLogger.log("[ApiConfig] open_settings_page")

        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etVisionModel = findViewById(R.id.etVisionModel)
        etTextModel = findViewById(R.id.etTextModel)
        tvStatus = findViewById(R.id.tvStatus)
        tvCurrentConfig = findViewById(R.id.tvCurrentConfig)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        btnBack = findViewById(R.id.btnBack)
        llProviderPresets = findViewById(R.id.llProviderPresets)

        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveConfig() }
        btnTest.setOnClickListener { testConnection() }

        buildProviderPresets()
        loadCurrentConfig()
    }

    private fun buildProviderPresets() {
        val gapPx = Utils.dp2px(this, 8)
        ApiConfigStore.PROVIDER_PRESETS.forEach { preset ->
            val chip = TextView(this).apply {
                text = preset.name
                textSize = 13f
                setTextColor(Color.parseColor("#1A56DB"))
                background = GradientDrawable().apply {
                    cornerRadius = Utils.dp2px(this@AISettingsActivity, 16).toFloat()
                    setColor(Color.parseColor("#E8F0FE"))
                    setStroke(Utils.dp2px(this@AISettingsActivity, 1), Color.parseColor("#AAB4D4"))
                }
                setPadding(
                    Utils.dp2px(this@AISettingsActivity, 14), Utils.dp2px(this@AISettingsActivity, 7),
                    Utils.dp2px(this@AISettingsActivity, 14), Utils.dp2px(this@AISettingsActivity, 7)
                )
                setOnClickListener {
                    etBaseUrl.setText(preset.baseUrl)
                    if (etVisionModel.text.isBlank()) etVisionModel.setText(preset.visionModel)
                    if (etTextModel.text.isBlank() && preset.textModel.isNotBlank()) {
                        etTextModel.setText(preset.textModel)
                    }
                    val base = "已选择「${preset.name}」，请填写 API Key 后保存。"
                    showStatus(if (preset.hint.isBlank()) base else "$base\n${preset.hint}")
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gapPx }
            llProviderPresets.addView(chip, lp)
        }
    }

    private fun loadCurrentConfig() {
        val config = configStore.get()
        etBaseUrl.setText(config.baseUrl)
        etApiKey.setText(config.apiKey)
        etVisionModel.setText(config.visionModel)
        etTextModel.setText(config.textModel)
        refreshCurrentConfigDisplay(config)
        AppLogger.log("[Startup] api_config_exists=${config.isReady} api_supports_vision=${config.supportsVision}")
    }

    private fun refreshCurrentConfigDisplay(config: ApiConfigStore.ApiConfig) {
        tvCurrentConfig.text = if (config.isReady) {
            buildString {
                append(if (config.hasVision) "当前状态：已配置（可读图）\n" else "当前状态：已配置（仅文本，不走截图识别）\n")
                append("URL：${config.baseUrl.take(50)}\n")
                append("Key：${configStore.maskKey(config.apiKey)}\n")
                if (config.visionModel.isNotBlank()) append("视觉模型：${config.visionModel}\n")
                if (config.textModel.isNotBlank()) append("文本模型：${config.textModel}")
            }.trimEnd()
        } else {
            "当前状态：未配置（本地题库仍可正常使用；填写后才启用 AI 识别与兜底）"
        }
    }

    private fun saveConfig() {
        val baseUrl = configStore.normalizeBaseUrl(etBaseUrl.text.toString())
        val apiKey = etApiKey.text.toString().trim()
        val visionModel = etVisionModel.text.toString().trim()
        val textModel = etTextModel.text.toString().trim()

        if (baseUrl.isBlank()) { showStatus("请填写 API Base URL"); return }
        if (apiKey.isBlank()) { showStatus("请填写 API Key"); return }
        // 1.1.43：视觉模型不再强制。只填文本模型也能用（资料判答/兜底/无障碍文本结构化），
        // 只是没有"截图交给大模型识别"这一路，OCR 仍然可用。
        if (visionModel.isBlank() && textModel.isBlank()) {
            showStatus("视觉模型和文本模型至少填一个")
            return
        }

        val config = ApiConfigStore.ApiConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            visionModel = visionModel,
            textModel = textModel,
            supportsVision = true,
            enabled = true,
            updatedAt = System.currentTimeMillis()
        )
        configStore.save(config)
        // 更新输入框显示标准化后的URL
        etBaseUrl.setText(baseUrl)
        refreshCurrentConfigDisplay(config)
        showStatus("配置已保存")
        AppLogger.log("[ApiConfig] save_success base_url=$baseUrl vision_model=$visionModel key_masked=${configStore.maskKey(apiKey)}")
    }

    private fun testConnection() {
        val baseUrl = configStore.normalizeBaseUrl(etBaseUrl.text.toString())
        val apiKey = etApiKey.text.toString().trim()
        val visionModel = etVisionModel.text.toString().trim()

        val textModel = etTextModel.text.toString().trim()
        if (baseUrl.isBlank()) { showStatus("请先填写 Base URL"); return }
        if (apiKey.isBlank()) { showStatus("请先填写 API Key"); return }
        if (visionModel.isBlank() && textModel.isBlank()) {
            showStatus("视觉模型和文本模型至少填一个")
            return
        }

        // 先临时保存用于测试
        configStore.save(ApiConfigStore.ApiConfig(
            baseUrl = baseUrl, apiKey = apiKey, visionModel = visionModel,
            textModel = textModel,
            supportsVision = true, enabled = true,
            updatedAt = System.currentTimeMillis()
        ))

        setBusy(true)
        showStatus("正在测试连接...")
        AppLogger.log("[ApiConfig] test_start")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repo.testConnection() }
            setBusy(false)
            result.fold(
                onSuccess = {
                    showStatus("连接成功！API 可用")
                    AppLogger.log("[ApiConfig] test_success")
                },
                onFailure = { e ->
                    showStatus("连接失败：${e.message?.take(120)}")
                    AppLogger.log("[ApiConfig] test_failed reason=${e.message?.take(100)}")
                }
            )
        }
    }

    private fun showStatus(msg: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = msg
    }

    private fun setBusy(busy: Boolean) {
        btnSave.isEnabled = !busy
        btnTest.isEnabled = !busy
    }
}
