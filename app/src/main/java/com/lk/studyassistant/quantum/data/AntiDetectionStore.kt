package com.lk.studyassistant.quantum.data

import android.content.Context

/**
 * 防检测开关存储。
 *
 * - enabled: 总开关，默认开。关闭后悬浮窗回退为普通窗口，不施加任何防检测措施。
 * - useSkipScreenshot: Android 12+ 反射 SurfaceControl.Transaction.setSkipScreenshot；
 *   关闭则只能依赖 FLAG_SECURE。
 * - useFlagSecure: WindowManager.LayoutParams.FLAG_SECURE 作为低版本/兜底手段。
 */
class AntiDetectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var useSkipScreenshot: Boolean
        get() = prefs.getBoolean(KEY_USE_SKIP_SCREENSHOT, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_SKIP_SCREENSHOT, value).apply()

    var useFlagSecure: Boolean
        get() = prefs.getBoolean(KEY_USE_FLAG_SECURE, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FLAG_SECURE, value).apply()

    /** 是否应在 LayoutParams 上挂 FLAG_SECURE。 */
    fun shouldUseFlagSecure(): Boolean = enabled && useFlagSecure

    /** 是否应尝试反射调 setSkipScreenshot（仅 Android 12+ 有效）。 */
    fun shouldUseSkipScreenshot(): Boolean = enabled && useSkipScreenshot

    companion object {
        private const val PREFS_NAME = "anti_detection_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USE_SKIP_SCREENSHOT = "use_skip_screenshot"
        private const val KEY_USE_FLAG_SECURE = "use_flag_secure"
    }
}
