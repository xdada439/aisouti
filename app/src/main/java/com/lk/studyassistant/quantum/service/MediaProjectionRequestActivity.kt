package com.lk.studyassistant.quantum.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.lk.studyassistant.quantum.util.AppLogger

/**
 * 透明 Activity，用于触发 MediaProjection 系统授权对话框。
 * 仅在 AccessibilityService.takeScreenshot 失败兜底时使用。
 */
class MediaProjectionRequestActivity : Activity() {

    companion object {
        private const val REQ_CODE = 0x4D50  // 'MP'
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log("[Capture] mp_request_consent")
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE)
        } catch (t: Throwable) {
            AppLogger.log("[Capture] mp_request_failed=${t.message?.take(80)}")
            ScreenCaptureHelper.onConsentResult(this, Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE) {
            AppLogger.log("[Capture] mp_consent_result=$resultCode data=${data != null}")
            ScreenCaptureHelper.onConsentResult(this, resultCode, data)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
