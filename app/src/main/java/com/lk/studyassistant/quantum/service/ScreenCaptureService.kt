package com.lk.studyassistant.quantum.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lk.studyassistant.quantum.util.AppLogger

/**
 * MediaProjection 一次性截屏服务（兜底路径）。
 *
 * 仅当 AccessibilityService.takeScreenshot 失败时由 FloatingWindowService 触发。
 *
 * 工作流程：
 *  1. 上层先通过 MediaProjectionRequestActivity 拿到 resultCode + Intent
 *  2. 通过 startWithCapture() 启动本服务（带 resultCode + Intent + 回调）
 *  3. 进入前台 (foregroundServiceType=mediaProjection)
 *  4. 创建 VirtualDisplay + ImageReader，抓首帧 → 回调 Bitmap → 释放并 stopSelf
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "quantum_screen_capture_service"
        private const val NOTIFICATION_ID = 1002
        private const val CAPTURE_TIMEOUT_MS = 5000L

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        @Volatile
        private var pendingCallback: ((Bitmap?) -> Unit)? = null

        fun startWithCapture(
            context: Context,
            resultCode: Int,
            data: Intent,
            callback: (Bitmap?) -> Unit
        ) {
            pendingCallback = callback
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var delivered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent == null) {
            finishWith(null)
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            AppLogger.log("[Capture] mp_data_missing")
            finishWith(null)
            return START_NOT_STICKY
        }
        captureOneFrame(resultCode, data)
        return START_NOT_STICKY
    }

    private fun captureOneFrame(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            AppLogger.log("[Capture] mp_get_failed")
            finishWith(null)
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { AppLogger.log("[Capture] mp_stopped") }
        }, Handler(mainLooper))

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        AppLogger.log("[Capture] mp_start width=$w height=$h dpi=$dpi")

        val thread = HandlerThread("ScreenCaptureWorker").also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper).also { workerHandler = it }

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (delivered) {
                runCatching { r.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val rawBitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride,
                    h,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (rowPadding == 0) rawBitmap
                else Bitmap.createBitmap(rawBitmap, 0, 0, w, h)
                if (cropped !== rawBitmap) rawBitmap.recycle()
                delivered = true
                AppLogger.log("[Capture] mp_success size=${cropped.width}x${cropped.height}")
                finishWith(cropped)
            } catch (t: Throwable) {
                AppLogger.log("[Capture] mp_read_failed=${t.message?.take(80)}")
                finishWith(null)
            } finally {
                runCatching { img.close() }
            }
        }, handler)

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "ai-souti-capture",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            AppLogger.log("[Capture] mp_vd_failed=${t.message?.take(80)}")
            finishWith(null)
            return
        }

        Handler(mainLooper).postDelayed({
            if (!delivered) {
                AppLogger.log("[Capture] mp_timeout")
                finishWith(null)
            }
        }, CAPTURE_TIMEOUT_MS)
    }

    private fun finishWith(bitmap: Bitmap?) {
        val cb = pendingCallback
        pendingCallback = null
        runCatching { cb?.invoke(bitmap) }
        Handler(mainLooper).postDelayed({ shutdownAndStop() }, 200)
    }

    private fun shutdownAndStop() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { workerThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        projection = null
        workerThread = null
        workerHandler = null
        stopSelf()
    }

    override fun onDestroy() {
        shutdownAndStop()
        super.onDestroy()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "屏幕捕获",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI搜题")
            .setContentText("正在捕获屏幕")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }
}
