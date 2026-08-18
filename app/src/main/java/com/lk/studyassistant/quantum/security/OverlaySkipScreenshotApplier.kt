package com.lk.studyassistant.quantum.security

import android.annotation.SuppressLint
import android.os.Build
import android.view.SurfaceControl
import android.view.View
import com.lk.studyassistant.quantum.util.AppLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 让自身悬浮窗 Surface 在系统截图/录屏中被跳过。
 *
 * 原理：反射拿到 [View] 的 ViewRootImpl，再取其 mSurfaceControl，
 * 通过 [SurfaceControl.Transaction.setSkipScreenshot] 设置跳过。
 *
 * 限制：
 * - 仅 Android 12 (API 31) 及以上有 setSkipScreenshot
 * - mSurfaceControl 字段在 Android 9+ 属于隐藏 API，需要 HiddenApiBypass 解除限制
 * - 只能让"自身窗口"在截屏/录屏中不可见，**不能**绕过目标 App 的 FLAG_SECURE。
 *
 * 用法：在悬浮窗 view 已 attach 到 window 后调用 [tryApply]。
 * 同一 view 多次调用安全，重复反射会被短路。
 */
object OverlaySkipScreenshotApplier {

    private const val TAG = "[AntiDetect]"

    @Volatile
    private var hiddenApiBypassPrepared = false

    /**
     * 尝试为 [view] 所在的窗口 Surface 开启 skipScreenshot。
     * @return 成功返回 true；不支持/反射失败返回 false（不会抛异常）
     */
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    fun tryApply(view: View?): Boolean {
        if (view == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AppLogger.log("$TAG skipScreenshot_unsupported sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        ensureHiddenApiBypass()

        return runCatching {
            val viewRootImpl = invokeGetViewRootImpl(view) ?: run {
                AppLogger.log("$TAG view_root_impl_null")
                return@runCatching false
            }

            val surfaceControl = readSurfaceControl(viewRootImpl) ?: run {
                AppLogger.log("$TAG surface_control_null")
                return@runCatching false
            }

            val txClass = SurfaceControl.Transaction::class.java
            val tx = txClass.getDeclaredConstructor().newInstance()
            val setSkip = txClass.getDeclaredMethod(
                "setSkipScreenshot",
                SurfaceControl::class.java,
                Boolean::class.javaPrimitiveType
            )
            setSkip.invoke(tx, surfaceControl, true)

            val apply = txClass.getDeclaredMethod("apply")
            apply.invoke(tx)

            AppLogger.log("$TAG skipScreenshot_applied")
            true
        }.getOrElse { e ->
            AppLogger.log("$TAG skipScreenshot_failed=${e.message?.take(120)}")
            false
        }
    }

    private fun ensureHiddenApiBypass() {
        if (hiddenApiBypassPrepared) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hiddenApiBypassPrepared = true
            return
        }
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("")
        }.onFailure {
            AppLogger.log("$TAG hidden_api_bypass_failed=${it.message?.take(80)}")
        }
        hiddenApiBypassPrepared = true
    }

    /** View.getViewRootImpl() 在不同 SDK 上的可见性不同，统一用反射。 */
    private fun invokeGetViewRootImpl(view: View): Any? {
        val method = View::class.java.getDeclaredMethod("getViewRootImpl")
        method.isAccessible = true
        return method.invoke(view)
    }

    /** ViewRootImpl.mSurfaceControl 是隐藏字段。 */
    private fun readSurfaceControl(viewRootImpl: Any): SurfaceControl? {
        val field = viewRootImpl.javaClass.getDeclaredField("mSurfaceControl")
        field.isAccessible = true
        return field.get(viewRootImpl) as? SurfaceControl
    }
}
