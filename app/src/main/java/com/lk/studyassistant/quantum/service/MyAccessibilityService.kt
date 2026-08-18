package com.lk.studyassistant.quantum.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lk.studyassistant.quantum.floating.FloatingWindowController
import com.lk.studyassistant.quantum.floating.FloatingWindowMode
import com.lk.studyassistant.quantum.floating.FloatingWindowUiState
import com.lk.studyassistant.quantum.data.AntiDetectionStore
import com.lk.studyassistant.quantum.data.FloatingProtectionStore
import com.lk.studyassistant.quantum.security.OverlaySkipScreenshotApplier
import com.lk.studyassistant.quantum.util.Utils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MyAccessibilityService : AccessibilityService(), FloatingWindowController.Callbacks {

    companion object {
        private const val POSITION_PREFS = "quantum_floating_position"
        private const val PREF_WINDOW_X = "window_x"
        private const val PREF_WINDOW_Y = "window_y"

        @Volatile private var instance: MyAccessibilityService? = null
        @Volatile private var overlayActionListener: OverlayActionListener? = null

        fun getInstance(): MyAccessibilityService? = instance
        fun isServiceRunning(): Boolean = instance != null
        fun setOverlayActionListener(listener: OverlayActionListener?) {
            overlayActionListener = listener
        }
    }

    interface ScreenshotCallback {
        fun onSuccess(bitmap: Bitmap)
        fun onError(error: String)
    }

    interface OverlayActionListener {
        fun onOverlayTriggerCapture()
        fun onOverlayRequestRegionEditor()
        fun onOverlayOpenDebugPanel()
        fun onOverlayCloseBall()
        fun onOverlayModeChanged(mode: FloatingWindowMode)
        fun onSensitiveSceneChanged(paused: Boolean, packageName: String)
        /** 音量下键双击触发的紧急关闭。 */
        fun onEmergencyCloseRequested() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService by lazy(LazyThreadSafetyMode.NONE) {
        Executors.newSingleThreadExecutor()
    }
    private val overlayWindowManager: WindowManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var overlayController: FloatingWindowController? = null
    private var overlayViewAdded = false
    private var overlayVisibleRequested = false
    private var overlayTemporarilyRemovedForScreenshot = false
    private var currentForegroundPackage: String = ""
    private var sensitiveScenePaused = false
    private val protectionStore by lazy(LazyThreadSafetyMode.NONE) {
        FloatingProtectionStore(this)
    }
    private val antiDetectionStore by lazy(LazyThreadSafetyMode.NONE) {
        AntiDetectionStore(this)
    }

    // 音量下键双击关闭：500ms 内连按两次
    private var lastVolumeDownTime = 0L
    private val volumeDoubleTapIntervalMs = 500L

    /**
     * 最近一次接收到 TYPE_VIEW_SCROLLED 的系统时间（uptimeMillis）。0 表示从未滚动过。
     *
     * 1.1.13 新增：FloatingWindowService 在截图前用 [millisSinceLastScroll] 判断屏幕是否已经静止，
     * 避免截到滚动过程中的中间帧（A3/A4 案例题用户惯性滑动后立刻按"答"的场景）。
     */
    @Volatile
    private var lastScrollEventTimeMs: Long = 0L

    private val overlayLayoutParams: WindowManager.LayoutParams by lazy(LazyThreadSafetyMode.NONE) {
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val secureFlag = if (antiDetectionStore.shouldUseFlagSecure())
            WindowManager.LayoutParams.FLAG_SECURE else 0
        val saved = getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
        val defaultX = Utils.dp2px(this@MyAccessibilityService, 12)
        val defaultY = resources.displayMetrics.heightPixels / 3
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            baseFlags or secureFlag,
            PixelFormat.TRANSLUCENT
        ).apply {
            // TOP gravity 使 y = 窗口顶边到屏幕顶部的距离，窗口高度变化时顶边不移动。
            // CENTER_VERTICAL 下高度变化会触发重算，导致窗口"跳动"。
            gravity = Gravity.END or Gravity.TOP
            x = saved.getInt(PREF_WINDOW_X, defaultX)
            y = saved.getInt(PREF_WINDOW_Y, defaultY)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1.1.13: 任何窗口的滚动事件都记录时间戳，给搜题流程"等屏幕静止"用
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollEventTimeMs = android.os.SystemClock.uptimeMillis()
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == currentForegroundPackage) return
        currentForegroundPackage = pkg
        val paused = protectionStore.isBlocked(pkg)
        if (paused != sensitiveScenePaused) {
            sensitiveScenePaused = paused
            AppLogger.log("[FloatingProtection] paused=$paused package=$pkg")
            overlayActionListener?.onSensitiveSceneChanged(paused, pkg)
        }
    }

    /**
     * 距离最近一次 TYPE_VIEW_SCROLLED 过去多久（毫秒）。
     * 从未滚动过 → 返回 Long.MAX_VALUE（视为"很早之前就静止"）。
     * 1.1.13 新增。
     */
    fun millisSinceLastScroll(): Long {
        val last = lastScrollEventTimeMs
        return if (last == 0L) Long.MAX_VALUE
        else (android.os.SystemClock.uptimeMillis() - last).coerceAtLeast(0L)
    }

    override fun onInterrupt() {
        Log.d("MyAccessibilityService", "Accessibility service interrupted")
    }

    /**
     * 音量下键双击紧急关闭：
     * 1) 立即销毁悬浮窗
     * 2) 通知 FloatingWindowService 同步停服
     * 3) **不**消费按键事件，保留系统原生音量调节（返回 false 让事件继续派发）
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.repeatCount != 0) return false

        val now = System.currentTimeMillis()
        val delta = now - lastVolumeDownTime
        lastVolumeDownTime = now
        if (delta in 1..volumeDoubleTapIntervalMs) {
            AppLogger.log("[EmergencyClose] volume_down_double_tap delta=${delta}ms")
            lastVolumeDownTime = 0L
            destroyAllOverlays()
            overlayActionListener?.onEmergencyCloseRequested()
        }
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            // 1.1.13: 增加 TYPE_VIEW_SCROLLED 以便记录"最近一次滚动时间"，让截图等屏幕静止
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ensureOverlayController()
        Log.d("MyAccessibilityService", "Accessibility service connected")
    }

    fun getRootNodeInfo(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * 悬浮球视觉中心的屏幕 Y 坐标（px）。
     * overlayLayoutParams.y = 窗口顶边到屏幕顶部的距离（Gravity.TOP 语义）。
     * 中心 = 顶边 + 视图高度 / 2。
     */
    fun getOverlayBallCenterY(): Int {
        val topY = overlayLayoutParams.y
        val viewH = overlayController?.getRootView()?.height ?: 0
        return topY + viewH / 2
    }

    fun isSearchPausedForForeground(): Boolean = sensitiveScenePaused

    fun getCurrentForegroundPackage(): String = currentForegroundPackage

    // ═══════════════════════════════════════════════════
    // 悬浮窗管理
    // ═══════════════════════════════════════════════════

    fun showOverlay(): Boolean {
        return runOnMainSync {
            overlayVisibleRequested = true
            overlayTemporarilyRemovedForScreenshot = false
            ensureOverlayController()
            val controller = overlayController ?: return@runOnMainSync false
            val rootView = controller.getOrCreateRootView()
            if (!overlayViewAdded) {
                try {
                    if (rootView.parent != null) {
                        (rootView.parent as? android.view.ViewGroup)?.removeView(rootView)
                    }
                    applyAntiDetectionFlags()
                    overlayWindowManager.addView(rootView, overlayLayoutParams)
                    overlayViewAdded = true
                    controller.updateWindowPosition(overlayLayoutParams.x, overlayLayoutParams.y)
                    applySkipScreenshotIfEnabled(rootView)
                } catch (t: Throwable) {
                    Log.e("MyAccessibilityService", "showOverlay failed", t)
                    return@runOnMainSync false
                }
            }
            controller.restoreAfterScreenshot()
            true
        }
    }

    fun hideOverlay() {
        runOnMain {
            overlayVisibleRequested = false
            overlayTemporarilyRemovedForScreenshot = false
            removeOverlayViewIfNeeded()
        }
    }

    fun renderOverlay(state: FloatingWindowUiState) {
        runOnMain {
            ensureOverlayController()
            overlayController?.render(state)
        }
    }

    fun setOverlayMode(mode: FloatingWindowMode) {
        runOnMain {
            ensureOverlayController()
            overlayController?.setMode(mode)
            if (overlayViewAdded) {
                val view = overlayController?.getRootView() ?: return@runOnMain
                runCatching {
                    overlayWindowManager.updateViewLayout(view, overlayLayoutParams)
                    overlayController?.updateWindowPosition(overlayLayoutParams.x, overlayLayoutParams.y)
                }.onFailure { Log.w("MyAccessibilityService", "updateViewLayout after mode failed: ${it.message}") }
            }
        }
    }

    fun hideOverlayForScreenshot() {
        runOnMain {
            val controller = overlayController ?: return@runOnMain
            // 截图期间只让内容不可见，不再 remove/add 窗口，避免极简球/答案框回跳或长时间消失。
            controller.hideForScreenshot()
        }
    }

    fun restoreOverlayAfterScreenshot() {
        runOnMain {
            val controller = overlayController ?: return@runOnMain
            if (overlayTemporarilyRemovedForScreenshot && overlayVisibleRequested) {
                val rootView = controller.getRootView() ?: controller.getOrCreateRootView()
                try {
                    if (rootView.parent != null) {
                        (rootView.parent as? android.view.ViewGroup)?.removeView(rootView)
                    }
                    applyAntiDetectionFlags()
                    overlayWindowManager.addView(rootView, overlayLayoutParams)
                    overlayViewAdded = true
                    controller.updateWindowPosition(overlayLayoutParams.x, overlayLayoutParams.y)
                    applySkipScreenshotIfEnabled(rootView)
                } catch (t: Throwable) {
                    Log.e("MyAccessibilityService", "restoreOverlayAfterScreenshot failed", t)
                }
            }
            overlayTemporarilyRemovedForScreenshot = false
            controller.restoreAfterScreenshot()
        }
    }

    /**
     * 一键销毁所有悬浮窗（紧急关闭/服务销毁/UI 关闭 共用入口）。
     * 与 [hideOverlay] 区别：完全释放 controller，下次 show 时重建。
     */
    fun destroyAllOverlays() {
        runOnMain {
            overlayVisibleRequested = false
            overlayTemporarilyRemovedForScreenshot = false
            removeOverlayViewIfNeeded()
            overlayController = null
            AppLogger.log("[FloatingBall] destroy_all_overlays")
        }
    }

    // ═══════════════════════════════════════════════════
    // 截图
    // ═══════════════════════════════════════════════════

    fun takeScreenshotWithAccessibility(callback: ScreenshotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            postError(callback, "系统版本过低，仅支持 Android 11 及以上版本")
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (hardwareBitmap == null) {
                            postError(callback, "截图失败：无法生成位图")
                            return
                        }
                        val safeBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        if (safeBitmap == null || safeBitmap.isRecycled) {
                            postError(callback, "截图失败：位图转换失败")
                            return
                        }
                        postSuccess(callback, safeBitmap)
                    } catch (t: Throwable) {
                        Log.e("MyAccessibilityService", "takeScreenshot error", t)
                        postError(callback, "截图处理失败：${t.message}")
                    } finally {
                        try { result.hardwareBuffer.close() } catch (_: Throwable) { }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    postError(callback, "截图失败，错误码: $errorCode")
                }
            })
        } catch (t: Throwable) {
            Log.e("MyAccessibilityService", "takeScreenshot invoke error", t)
            postError(callback, "截图调用失败：${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    // Callbacks
    // ═══════════════════════════════════════════════════

    override fun onTriggerCapture() { overlayActionListener?.onOverlayTriggerCapture() }
    override fun onRequestRegionEditor() { overlayActionListener?.onOverlayRequestRegionEditor() }
    override fun onOpenDebugPanel() { overlayActionListener?.onOverlayOpenDebugPanel() }
    override fun onCloseBall() {
        AppLogger.log("[FloatingBall] hide_from_menu")
        hideOverlay()
        overlayActionListener?.onOverlayCloseBall()
    }
    override fun onModeChanged(mode: FloatingWindowMode) { overlayActionListener?.onOverlayModeChanged(mode) }

    override fun onMoveWindow(newWindowX: Int, newWindowY: Int) {
        runOnMain {
            overlayLayoutParams.x = newWindowX
            overlayLayoutParams.y = newWindowY
            getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_WINDOW_X, overlayLayoutParams.x)
                .putInt(PREF_WINDOW_Y, overlayLayoutParams.y)
                .apply()
            applyAntiDetectionFlags()
            if (overlayViewAdded) {
                val view = overlayController?.getRootView() ?: return@runOnMain
                runCatching {
                    overlayWindowManager.updateViewLayout(view, overlayLayoutParams)
                    overlayController?.updateWindowPosition(overlayLayoutParams.x, overlayLayoutParams.y)
                }.onFailure { Log.w("MyAccessibilityService", "updateViewLayout failed: ${it.message}") }
            } else {
                overlayController?.updateWindowPosition(overlayLayoutParams.x, overlayLayoutParams.y)
            }
        }
    }

    override fun onDestroy() {
        destroyAllOverlays()
        runCatching { screenshotExecutor.shutdownNow() }
        instance = null
        super.onDestroy()
    }

    private fun ensureOverlayController() {
        if (overlayController != null) return
        overlayController = FloatingWindowController(this, this)
    }

    /**
     * 根据 AntiDetectionStore 当前开关，刷新 LayoutParams 上的 FLAG_SECURE。
     * 调用时机：addView / updateViewLayout 之前。
     */
    private fun applyAntiDetectionFlags() {
        val flagSecure = WindowManager.LayoutParams.FLAG_SECURE
        if (antiDetectionStore.shouldUseFlagSecure()) {
            overlayLayoutParams.flags = overlayLayoutParams.flags or flagSecure
        } else {
            overlayLayoutParams.flags = overlayLayoutParams.flags and flagSecure.inv()
        }
    }

    /** 如果开关开启且 SDK 支持，反射调用 SurfaceControl.Transaction.setSkipScreenshot。 */
    private fun applySkipScreenshotIfEnabled(view: android.view.View?) {
        if (!antiDetectionStore.shouldUseSkipScreenshot()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // attach 后才能拿到 ViewRootImpl 与 SurfaceControl，post 一次
        view?.post { OverlaySkipScreenshotApplier.tryApply(view) }
    }

    private fun removeOverlayViewIfNeeded() {
        if (!overlayViewAdded) return
        val view = overlayController?.getRootView() ?: return
        runCatching { overlayWindowManager.removeViewImmediate(view) }
            .onFailure { Log.w("MyAccessibilityService", "removeOverlayViewIfNeeded failed: ${it.message}") }
        overlayViewAdded = false
    }

    private fun postSuccess(callback: ScreenshotCallback, bitmap: Bitmap) { runOnMain { callback.onSuccess(bitmap) } }
    private fun postError(callback: ScreenshotCallback, error: String) { runOnMain { callback.onError(error) } }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun <T> runOnMainSync(block: () -> T): T {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            val holder = arrayOfNulls<Any>(1)
            val latch = CountDownLatch(1)
            mainHandler.post {
                try { holder[0] = block() as Any? } finally { latch.countDown() }
            }
            latch.await()
            @Suppress("UNCHECKED_CAST") holder[0] as T
        }
    }
}
