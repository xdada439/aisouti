package com.lk.studyassistant.quantum.data

import android.content.Context

/**
 * 悬浮答案显示样式：透明度 + 字体大小档位。本地持久化。
 *
 * 默认值用于保持现有用户的视觉体验，新增设置不影响老用户。
 */
class DisplaySettingsStore(context: Context) {

    enum class FontScale(val multiplier: Float, val storageKey: String) {
        SMALL(0.78f, "small"),
        DEFAULT(1.0f, "default"),
        LARGE(1.25f, "large");

        companion object {
            fun fromKey(key: String?): FontScale =
                values().firstOrNull { it.storageKey == key } ?: DEFAULT
        }
    }

    /**
     * 答案显示模式：
     *   SHOW_CONTENT：显示"字母 + 选项内容"（默认，1.1.3 之后的行为）
     *   LETTER_ONLY：只显示字母 A/B/C/D（适合担心选项内容遮挡题面的客户）
     */
    enum class AnswerDisplayMode(val storageKey: String) {
        SHOW_CONTENT("show_content"),
        LETTER_ONLY("letter_only");

        companion object {
            fun fromKey(key: String?): AnswerDisplayMode =
                values().firstOrNull { it.storageKey == key } ?: SHOW_CONTENT
        }
    }

    enum class IdentificationStrategy(val storageKey: String, val displayName: String) {
        GENERIC("generic", "通用模式");

        companion object {
            fun fromKey(key: String?): IdentificationStrategy = GENERIC
        }
    }

    companion object {
        private const val PREFS_NAME = "quantum_display_settings"
        private const val KEY_ANSWER_ALPHA = "key_answer_alpha"
        private const val KEY_ANSWER_FONT_SCALE = "key_answer_font_scale"
        private const val KEY_ANSWER_DISPLAY_MODE = "key_answer_display_mode"
        private const val KEY_IDENTIFICATION_STRATEGY = "key_identification_strategy"
        private const val KEY_MULTI_QUESTION_SELECTION = "key_multi_question_selection"

        const val MIN_ANSWER_ALPHA = 0.03f
        const val MAX_ANSWER_ALPHA = 1.00f
        const val DEFAULT_ANSWER_ALPHA = 1.00f

        // SeekBar 用：max 值 = (100 - MIN%) ，progress + MIN% = 实际百分比
        // 3% 起步 → max=97
        const val SEEKBAR_PROGRESS_OFFSET = 3
        const val SEEKBAR_MAX_PROGRESS = 97
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAnswerAlpha(): Float {
        val raw = prefs.getFloat(KEY_ANSWER_ALPHA, DEFAULT_ANSWER_ALPHA)
        return raw.coerceIn(MIN_ANSWER_ALPHA, MAX_ANSWER_ALPHA)
    }

    fun setAnswerAlpha(alpha: Float) {
        val safe = alpha.coerceIn(MIN_ANSWER_ALPHA, MAX_ANSWER_ALPHA)
        prefs.edit().putFloat(KEY_ANSWER_ALPHA, safe).apply()
    }

    fun getAnswerFontScale(): FontScale =
        FontScale.fromKey(prefs.getString(KEY_ANSWER_FONT_SCALE, FontScale.DEFAULT.storageKey))

    fun setAnswerFontScale(scale: FontScale) {
        prefs.edit().putString(KEY_ANSWER_FONT_SCALE, scale.storageKey).apply()
    }

    fun getAnswerDisplayMode(): AnswerDisplayMode =
        AnswerDisplayMode.fromKey(prefs.getString(KEY_ANSWER_DISPLAY_MODE, AnswerDisplayMode.SHOW_CONTENT.storageKey))

    fun setAnswerDisplayMode(mode: AnswerDisplayMode) {
        prefs.edit().putString(KEY_ANSWER_DISPLAY_MODE, mode.storageKey).apply()
    }

    fun getIdentificationStrategy(): IdentificationStrategy =
        IdentificationStrategy.fromKey(prefs.getString(KEY_IDENTIFICATION_STRATEGY, IdentificationStrategy.GENERIC.storageKey))

    fun setIdentificationStrategy(strategy: IdentificationStrategy) {
        prefs.edit().putString(KEY_IDENTIFICATION_STRATEGY, strategy.storageKey).apply()
    }

    /**
     * 1.1.12 新增：多题检测时是否弹框让用户选择。
     * 默认 false（自动选最佳，不打扰），开启后 A3/A4 案例题滚动场景可手动定位。
     */
    fun getMultiQuestionSelectionEnabled(): Boolean =
        prefs.getBoolean(KEY_MULTI_QUESTION_SELECTION, false)

    fun setMultiQuestionSelectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_QUESTION_SELECTION, enabled).apply()
    }
}
