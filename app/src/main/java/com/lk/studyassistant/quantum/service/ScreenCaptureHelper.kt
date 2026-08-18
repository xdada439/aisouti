package com.lk.studyassistant.quantum.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.lk.studyassistant.quantum.util.AppLogger

/**
 * MediaProjection 截屏协调器。
 *
 * 用法：
 *   ScreenCaptureHelper.capture(context) { bitmap ->
 *       // bitmap == null 代表用户拒绝授权或抓帧失败
 *   }
 *
 * 注意：MediaProjection token 系统不允许跨进程缓存，每次调用都会弹一次授权框。
 */
object ScreenCaptureHelper {

    @Volatile
    private var callback: ((Bitmap?) -> Unit)? = null

    fun capture(context: Context, callback: (Bitmap?) -> Unit) {
        this.callback = callback
        AppLogger.log("[Capture] mp_helper_request")
        val intent = Intent(context, MediaProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { context.startActivity(intent) }
            .onFailure {
                AppLogger.log("[Capture] mp_helper_launch_failed=${it.message?.take(80)}")
                deliver(null)
            }
    }

    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            AppLogger.log("[Capture] mp_consent_denied")
            deliver(null)
            return
        }
        val cb = callback ?: return
        callback = null
        ScreenCaptureService.startWithCapture(
            context.applicationContext, resultCode, data
        ) { bitmap ->
            runCatching { cb(bitmap) }
        }
    }

    private fun deliver(bitmap: Bitmap?) {
        val cb = callback
        callback = null
        runCatching { cb?.invoke(bitmap) }
    }
}
