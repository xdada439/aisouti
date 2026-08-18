package com.lk.studyassistant.quantum

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lk.studyassistant.quantum.data.DisplaySettingsStore
import com.lk.studyassistant.quantum.util.AppLogger

/**
 * 悬浮窗显示设置：整窗透明度（3%-100% 滑杆）+ 答案字号档位 + 答案显示模式（完整/仅字母）。
 * 改完立即写入 SharedPreferences；FloatingWindowController 下一次 render 时生效。
 */
class DisplaySettingsActivity : AppCompatActivity() {

    private lateinit var store: DisplaySettingsStore

    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var tvAnswerPreview: TextView
    private lateinit var rgFontScale: RadioGroup
    private lateinit var rbFontSmall: RadioButton
    private lateinit var rbFontDefault: RadioButton
    private lateinit var rbFontLarge: RadioButton
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var rbDisplayShowContent: RadioButton
    private lateinit var rbDisplayLetterOnly: RadioButton
    // 1.1.13: 移除"多题时弹框选择"开关——B 方案 picker 客户反馈太暴露，
    // 改成自动按完整性筛选（FloatingWindowService.selectCompleteQuestion）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_settings)
        AppLogger.log("[DisplaySettings] open")

        store = DisplaySettingsStore(this)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        seekAlpha = findViewById(R.id.seekAlpha)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        tvAnswerPreview = findViewById(R.id.tvAnswerPreview)
        rgFontScale = findViewById(R.id.rgFontScale)
        rbFontSmall = findViewById(R.id.rbFontSmall)
        rbFontDefault = findViewById(R.id.rbFontDefault)
        rbFontLarge = findViewById(R.id.rbFontLarge)
        rgDisplayMode = findViewById(R.id.rgDisplayMode)
        rbDisplayShowContent = findViewById(R.id.rbDisplayShowContent)
        rbDisplayLetterOnly = findViewById(R.id.rbDisplayLetterOnly)
        // ── 透明度（1.1.9 调整为 3%-100%）─────────────────
        // max = 97, progress + 3 = 实际百分比
        val savedAlpha = store.getAnswerAlpha()
        seekAlpha.progress = alphaToProgress(savedAlpha)
        applyAlphaToUi(savedAlpha)

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progressToAlpha(progress)
                applyAlphaToUi(alpha)
                if (fromUser) store.setAnswerAlpha(alpha)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                AppLogger.log("[DisplaySettings] alpha_changed value=${store.getAnswerAlpha()}")
            }
        })

        // ── 字号档位 ──────────────────────────────────────
        val savedScale = store.getAnswerFontScale()
        when (savedScale) {
            DisplaySettingsStore.FontScale.SMALL -> rbFontSmall.isChecked = true
            DisplaySettingsStore.FontScale.LARGE -> rbFontLarge.isChecked = true
            DisplaySettingsStore.FontScale.DEFAULT -> rbFontDefault.isChecked = true
        }
        applyFontScaleToPreview(savedScale)

        rgFontScale.setOnCheckedChangeListener { _, checkedId ->
            val scale = when (checkedId) {
                R.id.rbFontSmall -> DisplaySettingsStore.FontScale.SMALL
                R.id.rbFontLarge -> DisplaySettingsStore.FontScale.LARGE
                else -> DisplaySettingsStore.FontScale.DEFAULT
            }
            store.setAnswerFontScale(scale)
            applyFontScaleToPreview(scale)
            AppLogger.log("[DisplaySettings] font_scale_changed value=${scale.storageKey}")
        }

        // ── 答案显示模式 ────────────────────────────────
        val savedMode = store.getAnswerDisplayMode()
        when (savedMode) {
            DisplaySettingsStore.AnswerDisplayMode.LETTER_ONLY -> rbDisplayLetterOnly.isChecked = true
            DisplaySettingsStore.AnswerDisplayMode.SHOW_CONTENT -> rbDisplayShowContent.isChecked = true
        }
        applyDisplayModeToPreview(savedMode)

        rgDisplayMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbDisplayLetterOnly -> DisplaySettingsStore.AnswerDisplayMode.LETTER_ONLY
                else -> DisplaySettingsStore.AnswerDisplayMode.SHOW_CONTENT
            }
            store.setAnswerDisplayMode(mode)
            applyDisplayModeToPreview(mode)
            AppLogger.log("[DisplaySettings] display_mode_changed value=${mode.storageKey}")
        }

        // 1.1.13: 移除"多题时弹框选择"开关——B 方案 picker 太暴露，
        // 改成自动按完整性筛选（FloatingWindowService.selectCompleteQuestion）。
        // 兼容性：DisplaySettingsStore.getMultiQuestionSelectionEnabled() 仍保留但已停用。
    }

    private fun alphaToProgress(alpha: Float): Int {
        val pct = (alpha * 100f).toInt()
        return (pct - DisplaySettingsStore.SEEKBAR_PROGRESS_OFFSET)
            .coerceIn(0, DisplaySettingsStore.SEEKBAR_MAX_PROGRESS)
    }

    private fun progressToAlpha(progress: Int): Float {
        val pct = (progress + DisplaySettingsStore.SEEKBAR_PROGRESS_OFFSET)
            .coerceIn(DisplaySettingsStore.SEEKBAR_PROGRESS_OFFSET, 100)
        return pct / 100f
    }

    private fun applyAlphaToUi(alpha: Float) {
        tvAlphaValue.text = "${(alpha * 100).toInt()}%"
        tvAnswerPreview.alpha = alpha
    }

    private fun applyFontScaleToPreview(scale: DisplaySettingsStore.FontScale) {
        // 与 FloatingWindowController NORMAL 模式基准 13sp 一致
        tvAnswerPreview.textSize = 13f * scale.multiplier
    }

    private fun applyDisplayModeToPreview(mode: DisplaySettingsStore.AnswerDisplayMode) {
        tvAnswerPreview.text = when (mode) {
            DisplaySettingsStore.AnswerDisplayMode.SHOW_CONTENT -> "A 12个月"
            DisplaySettingsStore.AnswerDisplayMode.LETTER_ONLY -> "A"
        }
    }
}
