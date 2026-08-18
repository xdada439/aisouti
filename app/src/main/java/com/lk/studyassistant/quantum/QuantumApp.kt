package com.lk.studyassistant.quantum

import android.app.Application
import com.lk.studyassistant.quantum.data.ApiConfigStore
import com.lk.studyassistant.quantum.data.RecognitionLogStore
import com.lk.studyassistant.quantum.local.LocalQuestionBankRepository
import com.lk.studyassistant.quantum.local.MaterialRepository
import com.lk.studyassistant.quantum.util.AppLogger

class QuantumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 标记新的"启动会话"，方便识别日志按启动聚合（保留近 3 次）
        RecognitionLogStore.onAppLaunch(this)
        Thread {
            try {
                // 环境指纹：用户把识别日志发过来时，先看这两行就知道是哪个包、什么机器。
                // 少了它，一份日志根本没法判断复现的是不是同一个版本。
                val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
                AppLogger.log(
                    "[Startup] app_version=${pkg?.versionName ?: "?"} build=${pkg?.versionCode ?: "?"} " +
                        "pkg=$packageName"
                )
                AppLogger.log(
                    "[Startup] device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} " +
                        "android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}"
                )

                val apiConfig = ApiConfigStore(this).get()
                val qbCount = LocalQuestionBankRepository(this).countQuestions()
                val matChunks = MaterialRepository(this).countChunks()
                // isReady 和 hasVision 是两回事：只配文本模型时 isReady=true 但 hasVision=false，
                // 此时 Vision 识别路线会被跳过。分开打，排查"为什么没走视觉"时一眼可见。
                AppLogger.log(
                    "[Startup] api_ready=${apiConfig.isReady} has_vision=${apiConfig.hasVision} " +
                        "vision_model=${apiConfig.visionModel.ifBlank { "-" }} " +
                        "text_model=${apiConfig.textModel.ifBlank { "-" }}"
                )
                AppLogger.log("[Startup] question_bank_total_count=$qbCount")
                AppLogger.log("[Startup] material_chunks_count=$matChunks")
            } catch (e: Exception) {
                AppLogger.log("[Startup] init_error ${e.message}")
            }
        }.start()
    }
}
