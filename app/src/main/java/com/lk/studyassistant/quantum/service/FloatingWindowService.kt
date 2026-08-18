package com.lk.studyassistant.quantum.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.lk.studyassistant.quantum.DebugPanelActivity
import com.lk.studyassistant.quantum.data.ApiConfigStore
import com.lk.studyassistant.quantum.data.ApiRepository
import com.lk.studyassistant.quantum.data.RecognitionLogStore
import com.lk.studyassistant.quantum.floating.FloatingWindowMode
import com.lk.studyassistant.quantum.floating.FloatingWindowUiState
import com.lk.studyassistant.quantum.local.LocalQuestionParser
import com.lk.studyassistant.quantum.local.MaterialRepository
import com.lk.studyassistant.quantum.local.LocalQuestionBankRepository
import com.lk.studyassistant.quantum.local.DebugTraceStore
import com.lk.studyassistant.quantum.local.TextNormalizer
import com.lk.studyassistant.quantum.util.AiQuestionStructurer
import com.lk.studyassistant.quantum.util.AppLogger
import com.lk.studyassistant.quantum.util.CandidateQuestionBlock
import com.lk.studyassistant.quantum.util.ErrorCode
import com.lk.studyassistant.quantum.util.OptionItem
import com.lk.studyassistant.quantum.util.QuestionExtractResult
import com.lk.studyassistant.quantum.util.QuestionType
import com.lk.studyassistant.quantum.util.ScreenOcrEngine
import com.lk.studyassistant.quantum.util.VisibleTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class FloatingWindowService : LifecycleService(), MyAccessibilityService.OverlayActionListener {

    companion object {
        private const val CHANNEL_ID = "quantum_floating_window_service"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "quantum_floating_prefs"
        private const val PREF_MODE = "pref_mode"
        private const val SCREENSHOT_HIDE_DELAY_MS = 400L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L
        private const val MIN_OCR_TEXT_LENGTH = 8

        const val ACTION_SHOW = "com.lk.studyassistant.quantum.action.SHOW"
        const val ACTION_HIDE = "com.lk.studyassistant.quantum.action.HIDE"
        const val ACTION_TOGGLE_MODE = "com.lk.studyassistant.quantum.action.TOGGLE_MODE"

        // 截图哈希缓存（Bug F 修复）
        private const val SCREENSHOT_CACHE_TTL_MS = 30_000L
        private const val HASH_GRID_SIZE = 32

        /**
         * 缓存复用时允许的悬浮球移动距离（px）。
         * 球位置决定"一屏多题时搜哪一道"，所以球挪远了就不能复用上一次的答案。
         * 50px 约等于一根手指的宽度：手抖不会失效，有意换题一定失效。
         */
        private const val BALL_CACHE_TOLERANCE_PX = 50

        // 1.1.13: 多帧截图稳定性参数
        /** 等屏幕停止滚动的最长时间（含轮询开销）。超过仍未稳定也得继续，避免卡死。*/
        private const val SCREENSHOT_STABILITY_MAX_WAIT_MS = 1500L
        /** 距离最近一次滚动事件多久就算"已经静止"。300ms 是 Android UI 帧间隔的 ~5 倍，足够安全。*/
        private const val SCREENSHOT_QUIET_THRESHOLD_MS = 300L
        /** 多帧截图比对，最多截几张。两张相同就停。*/
        private const val SCREENSHOT_MAX_FRAMES = 3
        /** 两帧之间的间隔时间。*/
        private const val SCREENSHOT_FRAME_INTERVAL_MS = 500L

        /** 1.1.18: 0 道完整题时的提示文字（内容驱动，短提示） */
        private const val HINT_NO_COMPLETE_QUESTION = "未完全显示，请继续上滑"

        /**
         * 1.1.44: 识别阶段的看门狗时长。
         *
         * `screenshotInProgress` 只覆盖截屏阶段（1~3s），识别阶段（OCR+Vision+资料+兜底，
         * 最坏可能 80s）之前完全没有守卫，双击悬浮球会并发跑两条完整链路、Vision 计费两次。
         * 现在用一个"截止时间戳"当守卫：正常路径在 finally 里清零，即使有路径漏清，
         * 超过这个时长也会自动失效，不会把悬浮球永久卡在"正在识别..."。
         */
        private const val RECOGNITION_GUARD_MS = 120_000L
        /** 1.1.13: 内部错误码，标记"没有完整题"场景 */
        private const val ERROR_CODE_NO_COMPLETE_QUESTION = "NO_COMPLETE_QUESTION"
        // 资料/教材检索最低相关度。低于此值不投喂 LLM 判答，直接降级到模型自身知识兜底。
        private const val MATERIAL_MATCH_THRESHOLD = 0.45

        /** 兜底答案的免责提示：来源分「视觉模型」/「语言模型」两种。 */
        private const val FALLBACK_SOURCE_VISION = "视觉模型"
        private const val FALLBACK_SOURCE_TEXT = "语言模型"
        private fun fallbackNotice(source: String) = "题库未检索到，依据${source}判断"

        fun show(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply { action = ACTION_SHOW }
            ContextCompat.startForegroundService(context, intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply { action = ACTION_HIDE }
            ContextCompat.startForegroundService(context, intent)
        }

        fun toggleMode(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply { action = ACTION_TOGGLE_MODE }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 供 DebugPanelActivity 读取最新测试详情 */
        @Volatile
        var latestTestDetail: TestDetail = TestDetail()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy(LazyThreadSafetyMode.NONE) { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var screenshotInProgress = false

    /**
     * 识别阶段的守卫截止时间戳（0 = 空闲）。见 [RECOGNITION_GUARD_MS]。
     * 用时间戳而不是布尔量，是为了漏清时能自愈。
     */
    @Volatile
    private var recognitionDeadlineMs = 0L
    private var activeCaptureToken = 0L
    private var captureRestoreJob: Job? = null
    private var sensitiveScenePaused = false
    private var sensitiveScenePackage = ""
    // 识别日志：每次搜题触发记录起点时间，结束时根据 AppLogger 内存日志聚合成一条 RecognitionRecord
    private var triggerStartMs = 0L

    // ── 截图哈希缓存（Bug F 修复）─────────────────
    // 同一屏 SCREENSHOT_CACHE_TTL_MS 内重复搜题直接复用上次结果，
    // 解决"同屏多次搜索答案不一致"（LLM 非确定性 + Vision API 抖动）
    @Volatile
    private var lastScreenshotHash: String = ""
    @Volatile
    private var lastSearchDetail: TestDetail? = null
    @Volatile
    private var lastSearchTimeMs: Long = 0L
    /** 上次缓存结果时的球 Y。与本次球 Y 比距离，决定缓存能否复用。 */
    @Volatile
    private var lastCachedBallCenterY = 0
    // 本次搜索的悬浮球中心 Y（截图前保存，此刻球还没隐藏坐标准；供 OCR/无障碍多题选题用）
    @Volatile private var lastBallCenterY = 0

    /** 测试详情页数据 */
    data class TestDetail(
        val screenshotSuccess: Boolean = false,
        val screenshotSize: String = "",
        val apiJson: String = "",
        val preciseTopCandidates: List<String> = emptyList(),
        val preciseHit: Boolean = false,
        val preciseAnswer: String = "",
        val fuzzyTopCandidates: List<String> = emptyList(),
        val fuzzyChunkCount: Int = 0,
        val apiJudgeResult: String = "",
        val fallbackUsed: Boolean = false,
        /**
         * 兜底答案的来源标注："视觉模型" / "语言模型"。
         * 非空时悬浮窗会在答案下方灰字显示「题库未检索到，依据X判断」。
         * 题库命中和资料命中都不设此字段。
         */
        val fallbackSource: String = "",
        val finalAnswer: String = "",
        val errorCode: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        /**
         * 当前题目的 A→选项文本 映射。仅用于悬浮窗增强显示，让 "A" 显示为 "A 12个月"。
         * 拿不到时保持为空，不影响原 A/B/C/D 显示。
         */
        val answerOptions: LinkedHashMap<String, String> = LinkedHashMap()
    ) {
        fun toDisplayText(): String = buildString {
            append("====== 测试详情 ======\n")
            append("时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}\n\n")
            append("1. 截图: ${if (screenshotSuccess) "成功 $screenshotSize" else "失败"}\n\n")
            append("2. API识图返回JSON:\n${apiJson.ifBlank { "(空)" }}\n\n")
            append("3. 精准题库top10候选\n")
            if (preciseTopCandidates.isEmpty()) append("(无)\n") else preciseTopCandidates.take(10).forEach { append("$it\n") }
            append("\n4. 精准题库命中: ${if (preciseHit) "是 -> $preciseAnswer" else "否"}\n\n")
            append("5. 模糊匹配top20候选\n")
            if (fuzzyTopCandidates.isEmpty()) append("(无)\n") else fuzzyTopCandidates.take(20).forEach { append("$it\n") }
            append("\n6. API判断结果: ${apiJudgeResult.ifBlank { "(未调用)" }}\n\n")
            append("7. 兜底模式: ${if (fallbackUsed) "是（精准题库和模糊匹配均未命中，依据${fallbackSource.ifBlank { "大模型" }}判断）" else "否"}\n\n")
            append("8. 最终答案: ${finalAnswer.ifBlank { "无法判断" }}\n\n")
            append("9. 失败原因码: ${errorCode.ifBlank { "无" }}\n")
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        MyAccessibilityService.setOverlayActionListener(this)
    }

    override fun onDestroy() {
        MyAccessibilityService.setOverlayActionListener(null)
        MyAccessibilityService.getInstance()?.destroyAllOverlays()
        captureRestoreJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW -> {
                val a11y = MyAccessibilityService.getInstance()
                if (a11y == null) { stopSelf(); return START_STICKY }
                a11y.setOverlayMode(loadMode())
                if (a11y.showOverlay()) resetUi() else stopSelf()
            }
            ACTION_HIDE -> {
                MyAccessibilityService.getInstance()?.hideOverlay()
                stopSelf()
            }
            ACTION_TOGGLE_MODE -> {
                val a11y = MyAccessibilityService.getInstance() ?: run { stopSelf(); return START_STICKY }
                val next = if (loadMode() == FloatingWindowMode.NORMAL) FloatingWindowMode.MINIMAL else FloatingWindowMode.NORMAL
                saveMode(next)
                a11y.setOverlayMode(next)
            }
        }
        return START_STICKY
    }

    // ══════════════════════════════════════════════════
    // 主搜题流程
    //   识别层：静默截图 → OCR → Vision；截图不可用/均无答案 → 无障碍节点
    //   答案层：精准题库 → 资料 RAG → 模型自身知识（带免责提示）
    // ══════════════════════════════════════════════════
    override fun onOverlayTriggerCapture() {
        AppLogger.log("[Search] click_search")
        val a11yService = MyAccessibilityService.getInstance()
        if (a11yService == null) {
            AppLogger.log("[Source] failed=ACCESSIBILITY_NODE_TEXT reason=service_null")
            AppLogger.log("[Search] failed ${ErrorCode.NO_ACCESSIBILITY_PERMISSION}")
            renderState(FloatingWindowUiState(statusText = "请先开启无障碍服务", isBusy = false))
            return
        }
        if (isSearchPaused(a11yService)) {
            renderPausedProtectionState()
            AppLogger.log("[FloatingProtection] search_blocked package=${a11yService.getCurrentForegroundPackage()}")
            return
        }
        // 截屏阶段 + 识别阶段都要拦。只拦截屏阶段的话，识别期间（最长可达 80s）
        // 再点一次会并发跑第二条完整链路：Vision 计费两次，后完成的覆盖先完成的答案。
        if (screenshotInProgress || isRecognitionInProgress()) {
            AppLogger.log("[Search] ignored reason=busy screenshot=$screenshotInProgress recognizing=${isRecognitionInProgress()}")
            renderState(FloatingWindowUiState(statusText = "正在识别...", isBusy = true))
            return
        }

        // 不清空哈希和时间戳：同屏 30s 内重复点击走缓存，不重复调用 Vision API
        // 截图哈希不同（换屏）时缓存自动失效；TTL 到期后也自动失效
        // 截图前抓一次球坐标（此刻悬浮球还没隐藏，坐标准确），供 OCR 多题球定位
        lastBallCenterY = a11yService.getOverlayBallCenterY()

        val captureToken = beginScreenshotPhase()
        triggerStartMs = System.currentTimeMillis()
        renderState(FloatingWindowUiState(statusText = "正在识别...", isBusy = true))

        serviceScope.launch {
            // 1.1.19: OCR 截图优先。无障碍节点只作为 OCR/Vision/资料分析都失败后的最后兜底。
            AppLogger.log("[Source] skip=ACCESSIBILITY_NODE_TEXT reason=ocr_first_pipeline")

            // 准备截图：隐藏悬浮球 + 让渲染稳定
            AppLogger.log("[FloatingBall] hide_for_capture")
            hideFloatingForCapture(a11yService, captureToken)
            delay(SCREENSHOT_HIDE_DELAY_MS)
            if (!isActiveCapture(captureToken)) return@launch
            if (isSearchPaused(a11yService)) {
                restoreFloatingAfterCapture(a11yService)
                screenshotInProgress = false
                renderPausedProtectionState()
                return@launch
            }

            // ── Source 2: ACCESSIBILITY_SCREENSHOT（Android 11+）──
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AppLogger.log("[Source] try=ACCESSIBILITY_SCREENSHOT")
                tryAccessibilityScreenshot(captureToken, a11yService)
            } else {
                AppLogger.log("[Source] skip=ACCESSIBILITY_SCREENSHOT reason=android_below_11")
                AppLogger.log("[Source] try=MEDIA_PROJECTION reason=android_below_11")
                tryMediaProjectionScreenshot(captureToken, reason = "android_below_11")
            }
        }
    }

    /**
     * 把无障碍节点的原始文字发给文本 LLM 做结构化，作为本地节点解析不足时的补救。
     *
     * 用在无障碍兜底链路的第一环：本地解析拿不出「题干 + 选项」的完整题时，
     * 让文本模型把这堆散节点整理成结构化题目，再去查题库。
     * 用 textModel（比 visionModel 便宜快），超时 15s。返回 null 表示补救失败。
     */
    private suspend fun tryTextLlmStructure(rawText: String): QuestionExtractResult? = withContext(Dispatchers.IO) {
        val configStore = com.lk.studyassistant.quantum.data.ApiConfigStore(applicationContext)
        if (!configStore.isReady()) {
            AppLogger.log("[Source] skip=TEXT_LLM_STRUCTURE reason=api_not_configured")
            return@withContext null
        }
        if (rawText.length < 8) {
            AppLogger.log("[Source] skip=TEXT_LLM_STRUCTURE reason=raw_text_too_short len=${rawText.length}")
            return@withContext null
        }
        AppLogger.log("[Source] try=TEXT_LLM_STRUCTURE raw_len=${rawText.length}")
        val apiRepo = com.lk.studyassistant.quantum.data.ApiRepository(configStore)
        val result = withTimeoutOrNull(15_000L) {
            withContext(Dispatchers.IO) { apiRepo.callTextStructureApi(rawText) }
        }
        if (result == null) {
            AppLogger.log("[Source] failed=TEXT_LLM_STRUCTURE reason=timeout")
            return@withContext null
        }
        val rawJson = result.getOrNull() ?: run {
            AppLogger.log("[Source] failed=TEXT_LLM_STRUCTURE reason=api_error")
            return@withContext null
        }
        val questionResult = runCatching {
            AiQuestionStructurer.parse(rawJson, AiQuestionStructurer.SOURCE_TEXT_LLM)
        }.getOrNull() ?: run {
            AppLogger.log("[Source] failed=TEXT_LLM_STRUCTURE reason=parse_failed")
            return@withContext null
        }
        if (!AiQuestionStructurer.isValid(questionResult)) {
            AppLogger.log("[Source] failed=TEXT_LLM_STRUCTURE reason=invalid type=${questionResult.questionType} stem_len=${questionResult.questionText.length}")
            return@withContext null
        }
        AppLogger.log("[Source] success=TEXT_LLM_STRUCTURE type=${questionResult.questionType} stem_len=${questionResult.questionText.length} opts=${questionResult.options.size}")
        questionResult
    }

    /**
     * 1.1.13 改造：截图前等屏幕静止 + 多帧哈希比对，取稳定帧。
     *
     * 流程：
     *   1) 等屏幕静止：轮询 [MyAccessibilityService.millisSinceLastScroll]，
     *      连续 [SCREENSHOT_QUIET_THRESHOLD_MS] 无滚动事件 → 静止；
     *      最长等 [SCREENSHOT_STABILITY_MAX_WAIT_MS]，避免卡死。
     *   2) 多帧截图：最多 [SCREENSHOT_MAX_FRAMES] 张，每两帧间隔 [SCREENSHOT_FRAME_INTERVAL_MS]。
     *      连续两张感知哈希相同 → 屏幕静止 → 用第二张。
     *      没找到相同就用最后一张（兜底）。
     *   3) 失败时降级 MediaProjection（与之前一致）。
     */
    private fun tryAccessibilityScreenshot(captureToken: Long, a11yService: MyAccessibilityService) {
        serviceScope.launch {
            // ── 阶段 1：等屏幕静止 ──
            val waitStart = System.currentTimeMillis()
            var polls = 0
            while (System.currentTimeMillis() - waitStart < SCREENSHOT_STABILITY_MAX_WAIT_MS) {
                val sinceScroll = a11yService.millisSinceLastScroll()
                if (sinceScroll >= SCREENSHOT_QUIET_THRESHOLD_MS) break
                delay(100L)
                polls++
                if (!isActiveCapture(captureToken)) return@launch
            }
            val waitMs = System.currentTimeMillis() - waitStart
            AppLogger.log("[Screenshot] stability_wait_ms=$waitMs polls=$polls last_scroll_ago=${a11yService.millisSinceLastScroll()}")

            // ── 阶段 2：多帧截图 + 哈希比对 ──
            var prevBitmap: Bitmap? = null
            var prevHash = ""
            var stableBitmap: Bitmap? = null
            var lastError: String? = null

            for (i in 0 until SCREENSHOT_MAX_FRAMES) {
                if (!isActiveCapture(captureToken)) {
                    runCatching { prevBitmap?.recycle() }
                    return@launch
                }
                val frame = captureSingleFrame(a11yService)
                if (frame == null) {
                    AppLogger.log("[Screenshot] frame=$i result=null")
                    lastError = "frame_${i}_failed"
                    break
                }
                val hash = runCatching { computePerceptualHash(frame) }.getOrDefault("")
                val identical = prevHash.isNotEmpty() && hash == prevHash
                AppLogger.log("[Screenshot] frame=$i hash=${hash.take(12)} prev=${prevHash.take(12)} identical=$identical")
                if (identical) {
                    // 这一帧与上一帧相同 → 稳定 → 用这一帧
                    runCatching { prevBitmap?.recycle() }
                    stableBitmap = frame
                    break
                }
                // 不相同 → 把当前帧当作"上一帧"继续
                runCatching { prevBitmap?.recycle() }
                prevBitmap = frame
                prevHash = hash
                if (i < SCREENSHOT_MAX_FRAMES - 1) delay(SCREENSHOT_FRAME_INTERVAL_MS)
            }

            // ── 阶段 3：处理结果 ──
            val chosen = stableBitmap ?: prevBitmap
            if (chosen == null) {
                AppLogger.log("[Source] failed=ACCESSIBILITY_SCREENSHOT reason=all_frames_failed err=$lastError")
                AppLogger.log("[Source] try=MEDIA_PROJECTION reason=accessibility_screenshot_failed")
                tryMediaProjectionScreenshot(
                    captureToken,
                    reason = "accessibility_screenshot_failed:${lastError ?: "no_frame"}"
                )
                return@launch
            }
            AppLogger.log(
                "[Source] success=ACCESSIBILITY_SCREENSHOT width=${chosen.width} " +
                    "height=${chosen.height} stable=${stableBitmap != null} frames_tried=${if (stableBitmap != null) "≤$SCREENSHOT_MAX_FRAMES" else "all_diff"}"
            )
            handleCapturedBitmap(captureToken, chosen, sourceTag = "ACCESSIBILITY_SCREENSHOT")
        }
    }

    /**
     * 1.1.13: 单次截屏（封装 callback API 为 suspend）。失败返回 null。
     * 内部已经做了 hardware buffer 转 ARGB_8888，调用方拿到的是可直接处理的 software bitmap。
     */
    private suspend fun captureSingleFrame(a11yService: MyAccessibilityService): Bitmap? {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                a11yService.takeScreenshotWithAccessibility(object : MyAccessibilityService.ScreenshotCallback {
                    override fun onSuccess(bitmap: Bitmap) {
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onError(error: String) {
                        AppLogger.log("[Screenshot] capture_single_err=${error.take(80)}")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }

    private fun tryMediaProjectionScreenshot(captureToken: Long, reason: String) {
        ScreenCaptureHelper.capture(this@FloatingWindowService) { bitmap ->
            serviceScope.launch {
                if (!isActiveCapture(captureToken)) return@launch
                if (bitmap == null) {
                    AppLogger.log("[Source] failed=MEDIA_PROJECTION reason=not_granted_or_capture_failed escalate_to=$reason")
                    AppLogger.log("[Source] try=ACCESSIBILITY_NODE_TEXT reason=all_screenshot_sources_failed")
                    handleAllCaptureSourcesFailed(captureToken, reason)
                } else {
                    AppLogger.log("[Source] success=MEDIA_PROJECTION width=${bitmap.width} height=${bitmap.height}")
                    handleCapturedBitmap(captureToken, bitmap, sourceTag = "MEDIA_PROJECTION")
                }
            }
        }
    }

    private suspend fun handleCapturedBitmap(captureToken: Long, bitmap: Bitmap, sourceTag: String) {
        if (!isActiveCapture(captureToken)) return
        val a11yService = MyAccessibilityService.getInstance()

        val safeBitmap = withContext(Dispatchers.Default) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE)
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        }

        a11yService?.let { restoreFloatingAfterCapture(it) }
        screenshotInProgress = false

        try {
            renderState(FloatingWindowUiState(statusText = "识别中...", isBusy = true))
            // 整屏送入识别层：单题天然覆盖全屏，多题由 OCR 白名单/Vision 完整性筛选按球坐标选题。
            var detail = executeSearchPipeline(safeBitmap, captureSource = sourceTag)
            if (!isActiveCapture(captureToken)) return

            // 降级到无障碍的条件：**没答案** 且 **不是"看不全"这种明确结论**。
            // NO_COMPLETE_QUESTION 是 Vision 花钱得出的克制判断（这屏没有一道完整的题），
            // 它跟"识别失败"是两回事。以前一视同仁地继续降级，等于把这个结论丢掉再白跑一轮。
            val deliberateHold = detail.errorCode == ERROR_CODE_NO_COMPLETE_QUESTION
            if (!hasMeaningfulAnswer(detail) && !deliberateHold) {
                AppLogger.log("[Pipeline] ocr_vision_no_answer fallback=ACCESSIBILITY_NODE_TEXT reason=${detail.errorCode.ifBlank { "NO_ANSWER" }}")
                detail = executeAccessibilityTextPipeline(
                    captureError = detail.errorCode.ifBlank { "OCR_VISION_NO_MATCH" },
                    screenshotFailed = false
                )
                if (!isActiveCapture(captureToken)) return
            } else if (deliberateHold) {
                AppLogger.log("[Pipeline] skip=ACCESSIBILITY_NODE_TEXT reason=no_complete_question_is_a_verdict")
            }

            latestTestDetail = detail
            commitRecognitionRecord(detail, extractQuestionPreviewFromDetail(detail))

            // 1.1.13: 没找到完整题 → 渲染灰色提示，不显示答案
            if (detail.errorCode == ERROR_CODE_NO_COMPLETE_QUESTION) {
                renderState(FloatingWindowUiState(
                    answerText = "",
                    hintText = HINT_NO_COMPLETE_QUESTION,
                    fullDebugText = detail.toDisplayText(),
                    isBusy = false,
                    sourceLabel = "等待",
                    answerOptions = LinkedHashMap()
                ))
                return
            }

            renderState(FloatingWindowUiState(
                answerText = detail.finalAnswer,
                fullDebugText = detail.toDisplayText(),
                isBusy = false,
                sourceLabel = sourceLabelOf(detail),
                noticeText = noticeOf(detail),
                answerOptions = detail.answerOptions
            ))
        } finally {
            // 只有自己仍是当前那一轮才清守卫。否则在"守卫已超时失效 → 用户点了新的一轮 →
            // 上一轮才姗姗来迟地结束"这种交错里，会把新一轮的守卫误清掉。
            if (isActiveCapture(captureToken)) endRecognitionPhase()
        }
    }

    /** 答案来源标签：精准题库 / 模糊匹配 / 兜底模式。 */
    private fun sourceLabelOf(detail: TestDetail): String = when {
        detail.preciseHit -> "精准题库"
        detail.fallbackUsed -> "兜底模式"
        detail.apiJudgeResult.isNotBlank() -> "模糊匹配"
        else -> ""
    }

    /**
     * 兜底答案的免责提示。只有走到"模型自身知识"这一级、且真给出了答案时才提示，
     * 题库命中/资料命中/无法判断都不提示。
     */
    private fun noticeOf(detail: TestDetail): String =
        if (detail.fallbackUsed && detail.fallbackSource.isNotBlank() && isMeaningfulAnswerText(detail.finalAnswer))
            fallbackNotice(detail.fallbackSource)
        else ""

    private suspend fun handleAllCaptureSourcesFailed(captureToken: Long, reason: String) {
        if (!isActiveCapture(captureToken)) return
        AppLogger.log("[Search] failed=ALL_SOURCES_EXHAUSTED reason=$reason")
        val a11yService = MyAccessibilityService.getInstance()
        a11yService?.let { restoreFloatingAfterCapture(it) }
        screenshotInProgress = false
        try {
            renderState(FloatingWindowUiState(statusText = "截屏失败，最后一次文字识别...", isBusy = true))
            val detail = executeAccessibilityTextPipeline(captureError = reason, screenshotFailed = true)
            if (!isActiveCapture(captureToken)) return
            latestTestDetail = detail
            DebugTraceStore(this@FloatingWindowService).save(detail.toDisplayText(), null)
            commitRecognitionRecord(detail, extractQuestionPreviewFromDetail(detail))

            // 无障碍也判定"看不全" → 走灰色提示，别报"全部来源均未命中"（那是两件事）
            if (detail.errorCode == ERROR_CODE_NO_COMPLETE_QUESTION) {
                renderState(FloatingWindowUiState(
                    answerText = "",
                    hintText = HINT_NO_COMPLETE_QUESTION,
                    fullDebugText = detail.toDisplayText(),
                    isBusy = false,
                    sourceLabel = "等待",
                    answerOptions = LinkedHashMap()
                ))
                return
            }

            renderState(FloatingWindowUiState(
                statusText = if (!hasMeaningfulAnswer(detail)) "全部识别来源均未命中" else "",
                debugText = if (!hasMeaningfulAnswer(detail)) "节点/无障碍截图/MediaProjection 均失败\n原因: $reason" else "",
                answerText = detail.finalAnswer,
                fullDebugText = detail.toDisplayText(),
                isBusy = false,
                sourceLabel = if (!hasMeaningfulAnswer(detail)) "错误" else sourceLabelOf(detail).ifBlank { "文字识别" },
                noticeText = noticeOf(detail),
                answerOptions = detail.answerOptions
            ))
        } finally {
            // 只有自己仍是当前那一轮才清守卫。否则在"守卫已超时失效 → 用户点了新的一轮 →
            // 上一轮才姗姗来迟地结束"这种交错里，会把新一轮的守卫误清掉。
            if (isActiveCapture(captureToken)) endRecognitionPhase()
        }
    }

    /**
     * 完整搜题链路（1.1.19 OCR-first 改造版）
     *
     * 新流程：
     *   截图 → OCR → 黑白名单清洗/悬浮球选题 → 题库
     *     ├─ 命中 → 返回答案
     *     └─ 未命中 → Vision API → 题库 → 仅 Vision 分支资料分析
     *   OCR/Vision 均无答案后，由调用方降级到无障碍节点题库匹配。
     */
    private suspend fun executeSearchPipeline(
        bitmap: Bitmap,
        captureSource: String = "UNKNOWN"
    ): TestDetail = withContext(Dispatchers.IO) {
        val detail = TestDetail(screenshotSuccess = true, screenshotSize = "${bitmap.width}x${bitmap.height}")
        if (isSearchPaused()) {
            return@withContext detail.copy(errorCode = "SEARCH_PAUSED", finalAnswer = "")
        }

        // ── 截图哈希缓存命中检查 ──
        // 同屏 30 秒内重复搜题直接返回上次结果，避免：
        //   · Vision API 同截图两次给不同 type
        //   · LLM 兜底两次给不同字母
        //   · "这屏没有完整题"的结论被反复重新计费
        //
        // 1.1.44：球 Y 从"按 100px 分桶拼进 key"改为"按距离比较"。
        // 分桶两头都不对——球在 y=199 和 y=201 落在不同桶（该命中的没命中），
        // 而球从 y=101 移到 y=199 仍是同一个桶（一屏多题时会返回上一道题的答案）。
        // 现在：截图哈希必须完全相同，且球移动不超过 BALL_CACHE_TOLERANCE_PX 才算同一次。
        val screenshotHash = runCatching { computePerceptualHash(bitmap) }.getOrDefault("")
        if (screenshotHash.isNotEmpty()) {
            val cached = lastSearchDetail
            val cacheAge = System.currentTimeMillis() - lastSearchTimeMs
            val ballDelta = kotlin.math.abs(lastBallCenterY - lastCachedBallCenterY)
            val sameScreen = screenshotHash == lastScreenshotHash
            val sameBall = ballDelta <= BALL_CACHE_TOLERANCE_PX
            if (sameScreen && sameBall && cached != null && cacheAge < SCREENSHOT_CACHE_TTL_MS) {
                AppLogger.log(
                    "[Pipeline] cache_hit age_ms=$cacheAge ball_delta=$ballDelta " +
                        "answer=${cached.finalAnswer.take(20)} err=${cached.errorCode.ifBlank { "-" }}"
                )
                return@withContext cached
            }
            if (sameScreen && !sameBall) {
                AppLogger.log("[Pipeline] cache_miss reason=ball_moved delta=$ballDelta tolerance=$BALL_CACHE_TOLERANCE_PX")
            }
        }
        val effectiveCacheKey = screenshotHash

        val configStore = ApiConfigStore(applicationContext)
        val pipelineStartMs = System.currentTimeMillis()

        // ── 第一优先级：OCR + 题库 ──────────────────────
        AppLogger.log("[Pipeline] choose=OCR_FIRST capture_source=$captureSource")
        val ocrDetail = tryOcrPipeline(bitmap, detail, captureSource)
        if (hasMeaningfulAnswer(ocrDetail)) {
            AppLogger.log("[Pipeline] ocr_path_done total_ms=${System.currentTimeMillis() - pipelineStartMs} answer=${ocrDetail.finalAnswer.take(20)}")
            cacheResultIfMeaningful(effectiveCacheKey, ocrDetail)
            return@withContext ocrDetail
        }
        AppLogger.log("[Pipeline] ocr_no_answer_fallback_to_vision reason=${ocrDetail.errorCode.ifBlank { "QUESTION_BANK_NO_MATCH" }}")

        // ── 第二优先级：Vision API + 题库 + 资料分析 ──────────────────────
        // 门禁是 hasVision 不是 isReady：只配了文本模型的用户没有读图能力，
        // 这条路线要跳过，但他们的文本能力在无障碍分支的答案层仍然可用。
        if (configStore.hasVision()) {
            AppLogger.log("[Pipeline] choose=VISION_SECOND capture_source=$captureSource")
            val visionBitmap = cropBitmapAroundBallForVision(bitmap)
            var visionDetail = tryVisionPipeline(visionBitmap, detail, configStore)

            // 裁剪与完整性判断本来是互相打架的两个机制（1.1.44 修）：
            // 先按球 Y 裁 ±45%，再让模型判 is_complete —— 一道本来完整、但恰好被裁剪
            // 边界切断的题会被判成"不完整"。A-3 之后"看不全"是终点了（不再有无障碍兜底
            // 给它擦屁股），误判的代价从"多跑一轮"变成"直接让用户去上滑"。
            // 所以裁剪图说不完整时，用整屏复核一次再下结论。
            if (visionDetail?.errorCode == ERROR_CODE_NO_COMPLETE_QUESTION && visionBitmap !== bitmap) {
                AppLogger.log("[Pipeline] vision_recheck_fullscreen reason=cropped_says_incomplete")
                val fullScreenDetail = tryVisionPipeline(bitmap, detail, configStore)
                if (fullScreenDetail != null) {
                    AppLogger.log(
                        "[Pipeline] vision_recheck_done err=${fullScreenDetail.errorCode.ifBlank { "-" }} " +
                            "answer=${fullScreenDetail.finalAnswer.take(20)}"
                    )
                    visionDetail = fullScreenDetail
                } else {
                    AppLogger.log("[Pipeline] vision_recheck_failed keep=cropped_verdict")
                }
            }

            if (visionDetail != null) {
                AppLogger.log("[Pipeline] vision_path_done total_ms=${System.currentTimeMillis() - pipelineStartMs} answer=${visionDetail.finalAnswer.take(20)}")
                cacheResultIfMeaningful(effectiveCacheKey, visionDetail)
                return@withContext visionDetail
            }
            AppLogger.log("[Pipeline] vision_failed_no_answer")
        } else {
            AppLogger.log("[Pipeline] skip=VISION_API reason=no_vision_model_configured")
        }

        return@withContext ocrDetail
    }

    private fun cropBitmapAroundBallForVision(bitmap: Bitmap): Bitmap {
        val ballY = lastBallCenterY
        val h = bitmap.height
        if (ballY <= 0 || h <= 0) return bitmap
        val half = (h * 0.45f).toInt().coerceAtLeast(h / 3)
        val top = (ballY - half).coerceAtLeast(0)
        val bottom = (ballY + half).coerceAtMost(h)
        val cropH = bottom - top
        if (cropH <= 0 || cropH >= h) return bitmap
        AppLogger.log("[Pipeline] vision_ball_crop top=$top bot=$bottom ballY=$ballY bitmapH=$h")
        return android.graphics.Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropH)
    }

    // ── 完整性筛选（1.1.13）─────────────────────
    // 1.1.12 的"满屏 picker 让用户手选"客户反馈太暴露，1.1.13 改回自动：
    // AI 在 PROMPT 里给每道题打 is_complete 标记，本地代码自动选第一道完整的。
    // 没有任何完整题时返回 null，让上层显示"请滚动让题目完整显示"。

    /**
     * 从主题 + others 中挑出"题干 + 选项都完整可见"的那道。
     *
     * 流程：
     *   · main.isComplete=true → 直接用 main
     *   · 否则在 otherQuestions 里找第一个 isComplete=true + 结构完整的，把它的 stem/options
     *     合并到 main 的 copy 上返回
     *   · 都不完整 → 返回 null
     *
     * 注意：PROMPT 已经让 AI 把"最居中 + 最完整"的那道放在主题，所以扫顺序就是从中心
     * 向外的优先级。
     */
    private fun selectCompleteQuestion(
        result: com.lk.studyassistant.quantum.util.QuestionExtractResult
    ): com.lk.studyassistant.quantum.util.QuestionExtractResult? {
        // 主题已完整 → 直接用
        if (result.isComplete) {
            AppLogger.log("[Pipeline] select_complete source=main visible=${result.visibleQuestionsCount}")
            return result
        }
        // 主题不完整 → 在 others 里找
        val completeAlt = result.otherQuestions.firstOrNull { alt ->
            alt.isComplete && alt.stem.isNotBlank() && (
                alt.options.size >= 2 ||
                    alt.questionType == com.lk.studyassistant.quantum.util.QuestionType.TRUE_FALSE
                )
        }
        if (completeAlt != null) {
            AppLogger.log("[Pipeline] select_complete source=alternative stem=${completeAlt.stem.take(40)}")
            return result.copy(
                questionText = completeAlt.stem,
                questionType = completeAlt.questionType,
                options = completeAlt.options,
                isComplete = true,
                visibleQuestionsCount = 1,
                otherQuestions = emptyList()
            )
        }
        // 没有完整题 → 让上层显示"请滚动让题目完整显示"
        AppLogger.log("[Pipeline] no_complete_question visible=${result.visibleQuestionsCount} others=${result.otherQuestions.size}")
        return null
    }

    /**
     * 缓存"有意义答案"。失败/无法判断不缓存，避免错误结果在 30 秒内持续返回。
     */
    private fun cacheResultIfMeaningful(hash: String, detail: TestDetail) {
        if (hash.isEmpty()) return
        // 可缓存的两类结果：
        //   ① 有意义的答案
        //   ② "这屏没有完整的题" —— 这同样是一个确定结论，而且是 Vision 花钱换来的。
        //      不缓存它的话，用户在看不全的屏上连点（恰恰是最容易连点的场景）会反复计费，
        //      而每次的结论必然相同。屏幕一滚动哈希就变，缓存自动失效，不会挡住正常重试。
        val deliberateHold = detail.errorCode == ERROR_CODE_NO_COMPLETE_QUESTION
        if (!isMeaningfulAnswerText(detail.finalAnswer) && !deliberateHold) return
        lastScreenshotHash = hash
        lastSearchDetail = detail
        lastSearchTimeMs = System.currentTimeMillis()
        lastCachedBallCenterY = lastBallCenterY
        AppLogger.log(
            "[Pipeline] cache_store hash=${hash.take(16)} " +
                if (deliberateHold) "verdict=NO_COMPLETE_QUESTION" else "answer=${detail.finalAnswer.take(20)}"
        )
    }

    /**
     * 16×16 感知哈希（Bug F 实现）。
     *
     * 步骤：
     *   1. 等比缩放到 16×16（256 像素）
     *   2. 每个像素取灰度（R+G+B 平均）
     *   3. 计算 256 像素均值
     *   4. 高于均值 → '1'，低于 → '0'，拼成 256 字符串
     *
     * 特点：
     *   - 对像素噪声、轻微动画、压缩误差不敏感（同屏稳定）
     *   - 对内容真正变化敏感（下一题哈希就变）
     *   - 计算极快（<10ms）
     */
    private fun computePerceptualHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_GRID_SIZE, HASH_GRID_SIZE, true)
        val total = HASH_GRID_SIZE * HASH_GRID_SIZE
        val pixels = IntArray(total)
        scaled.getPixels(pixels, 0, HASH_GRID_SIZE, 0, 0, HASH_GRID_SIZE, HASH_GRID_SIZE)
        // 灰度均值
        var sum = 0L
        val gray = IntArray(total)
        for (i in 0 until total) {
            val p = pixels[i]
            val g = (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / 3
            gray[i] = g
            sum += g
        }
        val avg = (sum / total).toInt()
        val sb = StringBuilder(total)
        for (g in gray) sb.append(if (g >= avg) '1' else '0')
        if (scaled !== bitmap) scaled.recycle()
        return sb.toString()
    }

    /**
     * Vision API 主路径：截图 → Vision API → 结构化题目 → 题库/模糊/兜底
     * 返回 null 表示 Vision API 失败需要降级到 OCR 路径。
     */
    private suspend fun tryVisionPipeline(
        bitmap: Bitmap,
        detail: TestDetail,
        configStore: ApiConfigStore
    ): TestDetail? = withContext(Dispatchers.IO) {
        val apiRepo = ApiRepository(configStore)
        AppLogger.log("[Source] try=VISION_API")
        val visionStartMs = System.currentTimeMillis()

        // 1.1.11: 根据用户选择的"识别策略"取对应 PROMPT
        val strategy = com.lk.studyassistant.quantum.data.DisplaySettingsStore(applicationContext).getIdentificationStrategy()
        val prompt = AiQuestionStructurer.promptForStrategy(strategy)
        AppLogger.log("[Pipeline] strategy=${strategy.storageKey} prompt_chars=${prompt.length}")

        val visionResult = withTimeoutOrNull(30_000L) {
            apiRepo.callVisionApi(bitmap, prompt, timeoutSec = 28L)
        }

        if (visionResult == null) {
            AppLogger.log("[Source] failed=VISION_API reason=timeout cost_ms=${System.currentTimeMillis() - visionStartMs}")
            return@withContext null   // 降级 OCR
        }

        val rawJson: String = try {
            visionResult.getOrThrow()
        } catch (e: Exception) {
            AppLogger.log("[Source] failed=VISION_API reason=api_error cost_ms=${System.currentTimeMillis() - visionStartMs} err=${e.message?.take(80)}")
            return@withContext null   // 降级 OCR
        }

        val questionResult = try {
            AiQuestionStructurer.parse(rawJson)
        } catch (e: Exception) {
            AppLogger.log("[Source] failed=VISION_API reason=json_parse_failed cost_ms=${System.currentTimeMillis() - visionStartMs}")
            return@withContext null   // 降级 OCR
        }

        AppLogger.log("[VisionApi] cost_ms=${System.currentTimeMillis() - visionStartMs} type=${questionResult.questionType} stem_len=${questionResult.questionText.length} opts=${questionResult.options.size} confidence=${questionResult.confidence}")
        AppLogger.log("[Source] success=VISION_API type=${questionResult.questionType} stem_len=${questionResult.questionText.length}")
        AppLogger.log("[VisionApi] multi visible=${questionResult.visibleQuestionsCount} others=${questionResult.otherQuestions.size}")
        logExtractResult(questionResult)

        // 没识别到任何有用信息（题干空 或 选项题但选项<2）→ 降级
        if (!AiQuestionStructurer.isValid(questionResult)) {
            AppLogger.log("[Source] failed=VISION_API reason=invalid_result stem_blank=${questionResult.questionText.isBlank()} opts=${questionResult.options.size}")
            return@withContext null
        }

        // 1.1.13: 完整性筛选——AI 已给每道题打 is_complete 标记，本地选第一道完整的。
        // 屏幕上没有任何完整题 → 提示用户滚动，不去匹配题库（避免拿半截题瞎搜）。
        val pickedResult = selectCompleteQuestion(questionResult)
        if (pickedResult == null) {
            AppLogger.log("[Pipeline] hint_show=$HINT_NO_COMPLETE_QUESTION")
            return@withContext detail.copy(
                apiJson = rawJson.take(1000),
                errorCode = ERROR_CODE_NO_COMPLETE_QUESTION,
                finalAnswer = ""
            )
        }

        val currentDetail = detail.copy(
            apiJson = rawJson.take(1000),
            answerOptions = pickedResult.toAnswerOptionsMap()
        )

        // Vision 结构化结果走题库 + 模糊 + 文本兜底
        return@withContext answerQuestionFromResult(
            questionResult = pickedResult,
            currentDetail = currentDetail,
            preciseCandidatesPrefix = listOf(
                "source=VISION_API",
                "type=${questionResult.questionType}",
                "stem=${questionResult.questionText.take(80)}"
            ),
            allowLlmEscalation = true,
            fallbackSource = FALLBACK_SOURCE_VISION
        )
    }

    /**
     * OCR 第一优先级路径：OCR → 黑白名单清洗 → 题库匹配。
     * OCR 不调用资料模糊匹配，也不调用 LLM 自身知识库；未命中交给 Vision。
     */
    private suspend fun tryOcrPipeline(
        bitmap: Bitmap,
        detail: TestDetail,
        captureSource: String
    ): TestDetail = withContext(Dispatchers.IO) {
        AppLogger.log("[Source] try=MLKIT_OCR capture_source=$captureSource")
        val ocrResult = ScreenOcrEngine.recognize(bitmap)
        val ocrText = ocrResult.fullText.trim()
        if (ocrResult.success && ocrText.isNotBlank() && ocrText.length >= MIN_OCR_TEXT_LENGTH) {
            AppLogger.log("[Source] success=MLKIT_OCR length=${ocrText.length}")
            // OCR 白名单：从选项出发只圈"小题干+选项"（单题/多题统一），顶部/底部 UI、公共材料一律不取
            val whitelisted = buildOcrQuestionText(ocrResult.sortedLines, lastBallCenterY)
            val parseText = whitelisted.ifBlank { ocrText }
            if (whitelisted.isNotBlank()) {
                AppLogger.log("[Source] OCR_WHITELIST len=${whitelisted.length} ballY=$lastBallCenterY text=${whitelisted.replace("\n", " ").take(80)}")
            } else {
                AppLogger.log("[Source] OCR_WHITELIST fallback=fulltext reason=options_lt_2")
            }
            val parsedQuestion = LocalQuestionParser.parse(parseText)
            if (isOcrQuestionUsable(parsedQuestion)) {
                AppLogger.log("[Source] usable=MLKIT_OCR engine=${ScreenOcrEngine.ENGINE_NAME}")
                return@withContext answerQuestionFromResult(
                    questionResult = parsedQuestion,
                    currentDetail = detail.copy(
                        apiJson = "OCR文本:\n${parseText.take(1200)}"
                    ),
                    preciseCandidatesPrefix = listOf(
                        "ocr source=${ScreenOcrEngine.ENGINE_NAME}",
                        parseText.take(240)
                    )
                )
            }
            AppLogger.log("[Source] failed=MLKIT_OCR reason=parse_insufficient missing=${parsedQuestion.missingFields.joinToString(",")}")
            AppLogger.log("[Source] skip=ACCESSIBILITY_NODE_TEXT reason=vision_before_accessibility")
            return@withContext detail.copy(
                apiJson = "OCR文本:\n${parseText.take(1200)}",
                errorCode = ErrorCode.SCREENSHOT_SUCCESS_BUT_OCR_EMPTY,
                finalAnswer = ""
            )
        }

        val ocrErrorCode = when {
            !ocrResult.success -> ErrorCode.OCR_FAILED
            ocrText.isBlank() -> ErrorCode.OCR_RESULT_EMPTY
            else -> ErrorCode.OCR_TEXT_TOO_SHORT
        }
        AppLogger.log("[Source] failed=MLKIT_OCR reason=$ocrErrorCode length=${ocrText.length} err=${ocrResult.errorMessage.take(80)}")
        AppLogger.log("[Source] skip=ACCESSIBILITY_NODE_TEXT reason=vision_before_accessibility")
        return@withContext detail.copy(
            apiJson = "OCR失败: $ocrErrorCode\n${ocrResult.errorMessage.take(240)}",
            errorCode = ocrErrorCode,
            finalAnswer = ""
        )
    }

    /**
     * 无障碍节点兜底链路。
     *
     * 有两个调用方，情况完全不同，必须区分（1.1.44 修）：
     *   · [screenshotFailed] = true —— 截屏真的失败了（无障碍截屏 + MediaProjection 都没拿到图）
     *   · [screenshotFailed] = false —— **截屏成功了**，只是 OCR/Vision 都没给出答案
     *
     * 以前这里无条件写死 `screenshotSuccess=false` + `SCREENSHOT_FAILED` + "截图失败: ..."，
     * 于是第二种情况下识别日志和调试面板会谎报"截图失败"，排障时直接把人带到错误方向。
     */
    private suspend fun executeAccessibilityTextPipeline(
        captureError: String,
        screenshotFailed: Boolean
    ): TestDetail = withContext(Dispatchers.IO) {
        val baseDetail = TestDetail(
            screenshotSuccess = !screenshotFailed,
            errorCode = if (screenshotFailed) ErrorCode.SCREENSHOT_FAILED else captureError,
            apiJson = if (screenshotFailed) {
                "截图失败: $captureError\n改用无障碍文字识别"
            } else {
                "截图成功但 OCR/Vision 均未给出答案（$captureError）\n降级到无障碍文字识别"
            }
        )
        if (isSearchPaused()) {
            return@withContext baseDetail.copy(errorCode = "SEARCH_PAUSED", finalAnswer = "")
        }

        val a11y = MyAccessibilityService.getInstance()
            ?: return@withContext baseDetail.copy(finalAnswer = "无法判断")
        val root = a11y.getRootNodeInfo()
            ?: return@withContext baseDetail.copy(
                apiJson = baseDetail.apiJson + "\n无障碍未读取到当前窗口内容",
                finalAnswer = "无法判断"
            )

        val metrics = resources.displayMetrics
        val blocks = runCatching {
            VisibleTextExtractor(packageName).extractBlocks(
                rootNode = root,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels
            )
        }.getOrElse { e ->
            AppLogger.log("[AccessibilityText] extract_failed=${e.message?.take(80)}")
            emptyList()
        }

        AppLogger.log("[AccessibilityText] blocks=${blocks.size}")
        val sh = metrics.heightPixels
        // 球坐标：优先用点击瞬间的快照（那时球还没隐藏，最准），拿不到再实时读
        val ballY = if (lastBallCenterY > 0) lastBallCenterY else a11y.getOverlayBallCenterY()
        // 只在屏内的块里选：过滤 RecyclerView 预加载的屏幕外节点，避免抓到看不见的题
        val bestBlock = blocks
            .filter { it.isValid && it.bounds.bottom > 0 && it.bounds.top < sh }
            .ifEmpty { blocks.filter { it.isValid } }
            .maxByOrNull { accessibilityBlockScore(it, sh, ballY) }

        if (bestBlock == null) {
            return@withContext baseDetail.copy(
                apiJson = baseDetail.apiJson + "\n无障碍未提取到有效题目块",
                finalAnswer = "无法判断"
            )
        }

        val localResult = bestBlock.toQuestionExtractResult()
        AppLogger.log("[AccessibilityText] type=${localResult.questionType} stem=${localResult.questionText.take(50)} options=${localResult.options.size} ballY=$ballY")

        // ── 完整性门槛：题干 ≥ 8 字 且（选项 ≥ 2 或 判断题二元选项），且内容不是 UI 标识符垃圾 ──
        // 不满足 → 先让文本 LLM 补救结构化；再不行就提示上滑，不拿半截题去瞎搜题库。
        val questionResult = if (isAccessibilityQuestionUsable(localResult)) {
            localResult
        } else {
            AppLogger.log(
                "[AccessibilityText] incomplete stem_len=${localResult.questionText.length} " +
                    "opts=${localResult.options.size} type=${localResult.questionType} → try=TEXT_LLM_STRUCTURE"
            )
            val rescued = tryTextLlmStructure(bestBlock.rawText)
            if (rescued != null && isAccessibilityQuestionUsable(rescued)) {
                rescued
            } else {
                AppLogger.log("[Pipeline] hint_show=$HINT_NO_COMPLETE_QUESTION reason=accessibility_incomplete")
                return@withContext baseDetail.copy(
                    apiJson = baseDetail.apiJson + "\n无障碍块不完整：" + bestBlock.rawText.take(240),
                    errorCode = ERROR_CODE_NO_COMPLETE_QUESTION,
                    finalAnswer = ""
                )
            }
        }

        val standardText = AiQuestionStructurer.toStandardFormat(questionResult)

        // 无障碍是最后一条链路：题库不中时允许继续降级到 资料 RAG → 语言模型自身知识
        answerQuestionFromResult(
            questionResult = questionResult,
            currentDetail = baseDetail.copy(apiJson = standardText),
            preciseCandidatesPrefix = listOf(
                "accessibility score=${"%.2f".format(accessibilityBlockScore(bestBlock, sh, ballY))}",
                bestBlock.rawText.take(240)
            ),
            allowLlmEscalation = true,
            fallbackSource = FALLBACK_SOURCE_TEXT
        )
    }

    /**
     * 无障碍块的完整性门槛（1.1.42）。
     *
     * 只支持单选/多选/判断，所以"完整"的定义很明确：
     *   题干 ≥ [MIN_OCR_TEXT_LENGTH] 字 **且**（选项 ≥ 2 **或** 判断题）
     * 另外内容必须不是 App 的 resource-id 之类 UI 垃圾。
     *
     * 之前这条链路没有任何门槛——只要能选出块就送题库，半截题也照搜，
     * 结果是拿脏题干去查库，必然 miss 还浪费一次 LLM 调用。
     */
    private fun isAccessibilityQuestionUsable(q: QuestionExtractResult): Boolean {
        if (q.questionText.trim().length < MIN_OCR_TEXT_LENGTH) return false
        val isJudge = q.questionType == QuestionType.TRUE_FALSE
        if (!isJudge && q.options.size < 2) return false
        return isAccessibilityContentTrustworthy(q)
    }

    /**
     * 答案层（1.1.42 重构）。三级降级，逐级放宽：
     *
     *   ① 精准题库（本地 SQLite，score ≥ 0.30）
     *        ↓ miss
     *   ② 资料 RAG + LLM 判答（首块 ≥ [MATERIAL_MATCH_THRESHOLD] 才投喂）
     *        ↓ miss
     *   ③ 模型自身知识兜底 —— 答案带「题库未检索到，依据X判断」提示
     *        ↓ 仍无
     *      "无法判断"
     *
     * [allowLlmEscalation] 为 false 时只走 ①，题库不中即"无法判断"。
     * OCR 分支就是这样：它不该自己联网,未命中交给后面的 Vision 分支重新识别。
     * Vision 分支和无障碍分支传 true，区别只在 [fallbackSource] 的提示措辞。
     */
    private suspend fun answerQuestionFromResult(
        questionResult: QuestionExtractResult,
        currentDetail: TestDetail,
        preciseCandidatesPrefix: List<String> = emptyList(),
        // 题库不中时是否允许继续降级到 资料 RAG → 模型自身知识
        allowLlmEscalation: Boolean = false,
        // 兜底答案的来源标注：视觉模型 / 语言模型
        fallbackSource: String = ""
    ): TestDetail = withContext(Dispatchers.IO) {
        if (isSearchPaused()) {
            return@withContext currentDetail.copy(errorCode = "SEARCH_PAUSED", finalAnswer = "")
        }

        // 把当前题目的选项文本带入 detail，供悬浮窗"选项字母+内容"增强显示使用
        @Suppress("NAME_SHADOWING")
        val currentDetail = currentDetail.copy(answerOptions = questionResult.toAnswerOptionsMap())

        // 提取诊断日志：把每次"题干被识别成什么、题型/题号/选项数"打到日志，便于排查
        logExtractResult(questionResult)

        // ── 精准题库 ───────────────────────────
        AppLogger.log("[Source] try=QUESTION_BANK")
        val questionBank = LocalQuestionBankRepository(applicationContext)
        val (bankAnswer, candidates) = questionBank.searchWithCandidates(questionResult)

        val preciseCandidates = preciseCandidatesPrefix + candidates.map {
            "score=${"%.3f".format(it.score)} answer=${it.answer} stem=${it.stem.take(40)}"
        }

        if (bankAnswer != null && isMeaningfulAnswerText(bankAnswer.answer)) {
            AppLogger.log("[QuestionBank] matched=true answer=${bankAnswer.answer}")
            AppLogger.log("[Source] success=QUESTION_BANK answer=${bankAnswer.answer} score=${"%.3f".format(bankAnswer.confidence)}")
            // 注意：bankAnswer.answer 已在 LocalQuestionBankRepository 用"题库题型"归一化过
            // （含旧题库 A/B → 正确/错误 的兼容转换），此处不可再用 Vision 题型二次归一化，
            // 否则 Vision 误判判断题为单选时会把"正确"按 [A-E] 提取成空串导致答案丢失。
            return@withContext currentDetail.copy(
                preciseTopCandidates = preciseCandidates,
                preciseHit = true,
                preciseAnswer = bankAnswer.answer,
                finalAnswer = bankAnswer.answer
            )
        }
        if (bankAnswer != null) {
            AppLogger.log("[QuestionBank] discarded_non_meaningful raw=${bankAnswer.answer.take(40)}")
            AppLogger.log("[Source] failed=QUESTION_BANK reason=non_meaningful_answer raw=${bankAnswer.answer.take(20)}")
        } else {
            val bestScore = candidates.firstOrNull()?.score ?: 0.0
            AppLogger.log("[Source] failed=QUESTION_BANK reason=miss_threshold candidates=${candidates.size} best_score=${"%.3f".format(bestScore)}")
        }

        if (!allowLlmEscalation) {
            // OCR 分支：题库不中就交给下一条识别链路（Vision），不在这里联网判答
            AppLogger.log("[Source] skip=FUZZY_MATCH reason=no_llm_escalation_on_this_branch")
            AppLogger.log("[Source] skip=FALLBACK_KNOWLEDGE reason=no_llm_escalation_on_this_branch")
            return@withContext currentDetail.copy(
                preciseTopCandidates = preciseCandidates,
                errorCode = ErrorCode.QUESTION_BANK_NO_MATCH,
                finalAnswer = "无法判断"
            )
        }

        val apiRepo = ApiRepository(ApiConfigStore(applicationContext))
        val standardQuestion = AiQuestionStructurer.toStandardFormat(questionResult)

        // ── ② 资料 RAG（教材匹配）───────────────────────────
        var fuzzyCandidates: List<String> = emptyList()
        var fuzzyChunkCount = 0
        val materialRepo = MaterialRepository(applicationContext)
        val chunkTotal = materialRepo.countChunks()
        AppLogger.log("[Source] try=FUZZY_MATCH chunk_total=$chunkTotal")
        if (chunkTotal > 0) {
            val chunks = materialRepo.search(questionResult, topK = 20)
            if (chunks.isNotEmpty()) {
                fuzzyCandidates = chunks.take(20).map {
                    "score=${"%.3f".format(it.score)} ${it.cleanText.take(60)}"
                }
                fuzzyChunkCount = chunks.size
                val topScore = chunks.first().score
                if (topScore < MATERIAL_MATCH_THRESHOLD) {
                    // 资料不相关：不投喂 LLM（喂了只会照着无关资料瞎编），直接降级到 ③
                    AppLogger.log("[Source] failed=FUZZY_MATCH reason=low_material_score top_score=${"%.3f".format(topScore)} threshold=$MATERIAL_MATCH_THRESHOLD escalate=FALLBACK_KNOWLEDGE")
                } else {
                    val answerResult = apiRepo.callAnswerApi(
                        standardQuestion,
                        chunks.take(15).map { it.cleanText },
                        timeoutSec = 25L
                    )
                    val fuzzyAnswer = answerResult.getOrNull()?.let { parseAnswerFromRaw(it) }
                    if (fuzzyAnswer != null && isMeaningfulAnswerText(fuzzyAnswer)) {
                        AppLogger.log("[Source] success=FUZZY_MATCH answer=$fuzzyAnswer chunks=${chunks.size}")
                        return@withContext currentDetail.copy(
                            preciseTopCandidates = preciseCandidates,
                            fuzzyTopCandidates = fuzzyCandidates,
                            fuzzyChunkCount = fuzzyChunkCount,
                            apiJudgeResult = "答案: $fuzzyAnswer",
                            finalAnswer = fuzzyAnswer
                        )
                    }
                    if (!fuzzyAnswer.isNullOrBlank()) {
                        AppLogger.log("[FuzzyMatch] discarded_non_meaningful raw=${fuzzyAnswer.take(40)}")
                        AppLogger.log("[Source] failed=FUZZY_MATCH reason=non_meaningful_answer raw=${fuzzyAnswer.take(20)}")
                    } else {
                        val apiErr = answerResult.exceptionOrNull()?.message?.take(60).orEmpty()
                        AppLogger.log("[Source] failed=FUZZY_MATCH reason=api_no_answer err=$apiErr")
                    }
                }
            } else {
                AppLogger.log("[Source] failed=FUZZY_MATCH reason=no_matched_chunks")
            }
        } else {
            AppLogger.log("[Source] failed=FUZZY_MATCH reason=no_chunks")
        }

        // ── ③ 模型自身知识兜底 ───────────────────────────
        // 题库没有、教材也没有 → 让模型凭自身知识判一次，但答案必须带免责提示，
        // 让用户知道这条答案没有题库依据。
        AppLogger.log("[Source] try=FALLBACK_KNOWLEDGE source=$fallbackSource")
        val fallbackResult = apiRepo.callFallbackApi(standardQuestion, timeoutSec = 25L)
        fallbackResult.fold(
            onSuccess = { raw ->
                val answer = parseAnswerFromRaw(raw)
                val meaningful = isMeaningfulAnswerText(answer)
                if (!meaningful) {
                    AppLogger.log("[Source] failed=FALLBACK_KNOWLEDGE reason=non_meaningful_answer raw=${answer.take(20)}")
                    currentDetail.copy(
                        preciseTopCandidates = preciseCandidates,
                        fuzzyTopCandidates = fuzzyCandidates,
                        fuzzyChunkCount = fuzzyChunkCount,
                        fallbackUsed = true,
                        fallbackSource = fallbackSource,
                        errorCode = ErrorCode.MATERIAL_NO_MATCH,
                        finalAnswer = "无法判断"
                    )
                } else {
                    AppLogger.log("[Source] success=FALLBACK_KNOWLEDGE answer=$answer source=$fallbackSource")
                    currentDetail.copy(
                        preciseTopCandidates = preciseCandidates,
                        fuzzyTopCandidates = fuzzyCandidates,
                        fuzzyChunkCount = fuzzyChunkCount,
                        fallbackUsed = true,
                        fallbackSource = fallbackSource,
                        apiJudgeResult = "${fallbackNotice(fallbackSource)}: $answer",
                        finalAnswer = answer
                    )
                }
            },
            onFailure = { e ->
                AppLogger.log("[Source] failed=FALLBACK_KNOWLEDGE reason=api_failed err=${e.message?.take(60)}")
                currentDetail.copy(
                    preciseTopCandidates = preciseCandidates,
                    fuzzyTopCandidates = fuzzyCandidates,
                    fuzzyChunkCount = fuzzyChunkCount,
                    fallbackUsed = true,
                    fallbackSource = fallbackSource,
                    errorCode = ErrorCode.API_ANSWER_FAILED,
                    finalAnswer = "无法判断"
                )
            }
        )
    }

    /**
     * 提取诊断日志：每次成功识别一道题后打一行，方便排查"为什么这道题题库没命中"。
     * 字段:
     *   src    - 识别来源（无障碍/OCR/视觉API）
     *   q_no   - 题号（缺省时显示 -）
     *   q_type - 题型（SINGLE_CHOICE/MULTIPLE_CHOICE/TRUE_FALSE/UNKNOWN）
     *   stem_len - 题干字符数（用 stem_len 异常长 → UI 噪音污染）
     *   stem   - 题干前 60 字（看一眼就能判断是否干净）
     *   opts   - 选项数
     *   opt_preview - 选项前 3 个的字母:内容前 8 字（看选项是否抓全）
     */
    private fun logExtractResult(q: QuestionExtractResult) {
        val src = q.source.ifBlank { "-" }
        val no = q.questionNumber.ifBlank { "-" }
        val type = q.questionType.name
        val stemPreview = q.questionText.replace("\n", " ").take(60)
        val optPreview = if (q.options.isEmpty()) "0" else {
            val preview = q.options.joinToString("|") { "${it.label}:${it.text.take(8)}" }
            "${q.options.size}[$preview]"
        }
        AppLogger.log(
            "[Extract] src=$src q_no=$no q_type=$type stem_len=${q.questionText.length} " +
                "stem=$stemPreview opts=$optPreview"
        )
    }

    /**
     * 抽取选项映射用于悬浮窗显示。保持原插入顺序（LinkedHashMap），过滤空文本项。
     */
    private fun QuestionExtractResult.toAnswerOptionsMap(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (opt in options) {
            val label = opt.label.trim()
            val text = opt.text.trim()
            if (label.isNotEmpty()) out[label] = text
        }
        return out
    }

    /**
     * 判据①：判断无障碍提取的题目是否"可信"（真题 vs App 的 UI 节点垃圾）。
     * 无障碍偶尔抓到前台/后台 App 的 resource-id、无障碍标签
     * （如 "home-kaoshi-101"、"home_001"），结构上"够长+有选项"但根本不是题目。
     * 返回 false → 放弃无障碍结果，转截屏。
     */
    private fun isAccessibilityContentTrustworthy(q: QuestionExtractResult): Boolean {
        fun cjk(s: String) = s.count { it.code in 0x4E00..0x9FFF }
        // resource-id 风格：字母开头 + (- 或 _) + 字母/数字（home-kaoshi-101 / home_001）
        val idPattern = Regex("""[A-Za-z]{2,}[-_][A-Za-z0-9_-]+""")

        // 题干几乎无中文 且 含 id 风格串 → UI 标识符垃圾
        val stem = q.questionText.trim()
        if (cjk(stem) < 4 && idPattern.containsMatchIn(stem)) {
            AppLogger.log("[Source] node_untrusted reason=stem_resource_id stem=${stem.take(40)}")
            return false
        }
        // 选项里 id 风格占比 ≥ 半数 → 不可信
        if (q.options.isNotEmpty()) {
            val idOpts = q.options.count { opt ->
                val t = opt.text.trim()
                cjk(t) < 2 && idPattern.containsMatchIn(t)
            }
            if (idOpts.toDouble() / q.options.size >= 0.5) {
                AppLogger.log("[Source] node_untrusted reason=options_resource_id id_opts=$idOpts/${q.options.size}")
                return false
            }
        }
        return true
    }

    private fun accessibilityBlockScore(block: CandidateQuestionBlock, screenHeight: Int = 0, ballCenterY: Int = -1): Float {
        var score = 0f
        if (block.questionText.length >= 8) score += 2f
        if (block.options.size >= 2) score += 3f
        if (block.questionNo != null) score += 1f
        if (block.questionType.isNotBlank()) score += 1f
        val lengthScore = block.rawText.length.coerceAtMost(400) / 400f
        // 邻近度加成：有球坐标时选距球最近的；无球坐标时退化为选最靠下的（兜底）
        val proximityBonus = if (ballCenterY >= 0 && screenHeight > 0) {
            val centerY = (block.bounds.top + block.bounds.bottom) / 2
            (1.0 - kotlin.math.abs(centerY - ballCenterY).toDouble() / screenHeight).coerceAtLeast(0.0).toFloat()
        } else if (screenHeight > 0 && block.bounds.bottom > 0) {
            (block.bounds.bottom.toFloat() / screenHeight).coerceIn(0f, 1f)
        } else 0f
        return score + lengthScore + proximityBonus
    }

    private fun CandidateQuestionBlock.toQuestionExtractResult(): QuestionExtractResult {
        // 优先用 extractor 已经推断好的题型；"未知" 显式映射到 UNKNOWN，
        // 不再让 typeFromText 默认到 SINGLE_CHOICE（默认会让多选题被题型门槛挡掉）
        // 1.1.42：只支持单选/多选/判断。"填空"不再是一个可产出的题型，
        // 归到 UNKNOWN 让题库靠题干+选项相似度自己去匹配，而不是打一个必然不兼容的题型标签。
        val inferredType = when (questionType) {
            "单选" -> QuestionType.SINGLE_CHOICE
            "多选" -> QuestionType.MULTIPLE_CHOICE
            "判断" -> QuestionType.TRUE_FALSE
            "未知", "填空" -> QuestionType.UNKNOWN
            else -> TextNormalizer.typeFromText("$questionType\n$rawText")
        }
        val opts = options.entries.map { OptionItem(it.key, it.value) }
        return QuestionExtractResult(
            questionType = inferredType,
            questionNumber = questionNo?.toString().orEmpty(),
            questionText = questionText.ifBlank { rawText },
            options = opts,
            blanks = emptyList(),
            isComplete = questionText.isNotBlank() && (opts.size >= 2 || inferredType == QuestionType.TRUE_FALSE),
            missingFields = emptyList(),
            unsupportedReason = null,
            confidence = 0.7f,
            source = "无障碍文字",
            rawJson = rawText
        )
    }

    private fun parseAnswerFromRaw(raw: String): String {
        return try {
            val text = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return ""
            val json = org.json.JSONObject(text.substring(start, end + 1))
            json.optString("answer", "").trim()
        } catch (e: Exception) {
            val regex = Regex(""""answer"\s*:\s*"([^"]*)"""")
            regex.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: ""
        }
    }

    /**
     * OCR 白名单提取：从选项出发只圈"小题干 + 选项"，其余一律不要。
     * - 顶部 UI（返回/模拟考试/题型标签）在边界之上 → 不取
     * - 底部 UI（答题卡/进度/答案泄露）在选项之下 → 不取
     * - 共用题干的公共材料在题号之上 → 不取
     * 单题 = 只有一组选项；多题 = 用悬浮球 Y 选最近的一组。
     * 返回空串表示选项识别不足，调用方回退到全文去噪。
     */
    private fun buildOcrQuestionText(lines: List<com.lk.studyassistant.quantum.util.OcrLine>, ballY: Int): String {
        if (lines.isEmpty()) return ""
        val optionLabel = Regex("""^\s*[（(]?([A-Ha-h])[)）.、:：]?\s*\S""")
        val optLineIdx = lines.indices.filter { optionLabel.containsMatchIn(lines[it].text.trim()) }
        if (optLineIdx.size < 2) return ""

        // 选项分组：只按"遇 A 开新组"切（每道题从 A 重新开始）。
        // 不用垂直间隙断组——选项间距大的版面会把单题选项误拆，得不偿失；
        // 底部答案泄露（"答 C 3"）由 LocalQuestionParser 的选项去重兜底。
        data class Grp(val optStart: Int, val optEnd: Int)
        val groups = mutableListOf<Grp>()
        var start = optLineIdx.first()
        var prev = optLineIdx.first()
        for (k in 1 until optLineIdx.size) {
            val idx = optLineIdx[k]
            val label = optionLabel.find(lines[idx].text.trim())?.groupValues?.get(1)?.uppercase()
            if (label == "A") {
                groups.add(Grp(start, prev)); start = idx
            }
            prev = idx
        }
        groups.add(Grp(start, prev))

        // 先为每组选项回溯题干，只保留"题干 + 至少 2 个选项"的完整题；
        // 再按悬浮球 Y 选择最近完整题，避免最近选项组不完整时误回退全文。
        val qNoRegex = Regex("""^\s*[（(]?[1-9][)）.、]""")
        val typeLabelRegex = Regex("""^(单选题?|多选题?|判断题?|填空题?|单项选择题?|多项选择题?)$""")
        data class CompleteGroup(val group: Grp, val text: String, val centerY: Int)

        fun buildCompleteGroup(group: Grp): CompleteGroup? {
            val ti = groups.indexOf(group)
            val prevBottom = if (ti > 0) lines[groups[ti - 1].optEnd].bounds.bottom else 0
            val stem = ArrayDeque<String>()
            var lastTop = lines[group.optStart].bounds.top
            for (i in group.optStart - 1 downTo 0) {
                val line = lines[i]
                if (line.bounds.bottom <= prevBottom) break
                val t = line.text.trim()
                if (optionLabel.containsMatchIn(t)) continue
                val gap = lastTop - line.bounds.bottom
                if (stem.isNotEmpty() && gap > line.bounds.height() * 3) break
                if (typeLabelRegex.containsMatchIn(t)) break
                if (qNoRegex.containsMatchIn(t)) { stem.addFirst(t); break }
                if (isOcrUiNoise(t)) { if (stem.isNotEmpty()) break else continue }
                stem.addFirst(t)
                lastTop = line.bounds.top
            }
            if (stem.isEmpty()) return null

            val sb = StringBuilder()
            stem.forEach { sb.append(it).append('\n') }
            for (i in group.optStart..group.optEnd) sb.append(lines[i].text).append('\n')
            val centerY = (lines[group.optStart].bounds.top + lines[group.optEnd].bounds.bottom) / 2
            return CompleteGroup(group, sb.toString().trim(), centerY)
        }

        val completeGroups = groups.mapNotNull { buildCompleteGroup(it) }
        if (completeGroups.isEmpty()) return ""

        val target = when {
            completeGroups.size == 1 -> completeGroups[0]
            ballY > 0 -> completeGroups.minByOrNull { kotlin.math.abs(it.centerY - ballY) }
            else -> completeGroups.maxByOrNull { lines[it.group.optEnd].bounds.bottom }
        } ?: return ""
        AppLogger.log("[Source] OCR_PICK_COMPLETE groups=${completeGroups.size} ballY=$ballY targetCenter=${target.centerY} text=${target.text.replace("\n", " ").take(80)}")
        return target.text
    }

    /** OCR 行是否明显的 UI 噪音（顶部导航/底部进度/答题卡等，不属于题干或选项）。 */
    private fun isOcrUiNoise(t: String): Boolean {
        if (t.length <= 1) return true
        return Regex("""返回|退出|刷新|重新加载|模拟考试|顺序练习|随机练习|章节练习|每日一练|模拟练习|答题卡|上一题|下一题|交卷|提交答案|确认提交|确认答案|查看答案解析|点击继续|跳过此题|倒计时|剩余时间|收藏本题|收藏|报错|反馈|分享|复制|打印|开通会员|解锁答案|扫码下载|关注公众号|猜你喜欢|热门推荐|广告|赞助|升级VIP|限时免费|^设置$|^首页$|^我的$|^\d+\s*/\s*\d+$|^\d{1,2}:\d{2}""").containsMatchIn(t)
    }

    private fun isOcrQuestionUsable(result: QuestionExtractResult): Boolean {
        if (result.questionText.length < MIN_OCR_TEXT_LENGTH) return false
        if (result.questionType == QuestionType.UNKNOWN) return false
        if (result.questionType in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE) &&
            result.options.size < 2
        ) return false
        return true
    }

    private fun hasMeaningfulAnswer(detail: TestDetail): Boolean {
        return isMeaningfulAnswerText(detail.finalAnswer)
    }

    /**
     * 从 TestDetail.apiJson 里捞一段题干预览，用于识别日志展示。
     * apiJson 形态多样：可能是视觉 API 返回的 JSON、可能是 "OCR文本:\n..."、可能是 "[ACCESSIBILITY_NODE_TEXT]\n..."。
     */
    private fun extractQuestionPreviewFromDetail(detail: TestDetail): String {
        val raw = detail.apiJson
        if (raw.isBlank()) return ""
        // 尝试从 JSON 抽 stem 字段
        val stemMatch = Regex(""""stem"\s*:\s*"([^"]*)"""").find(raw)
        if (stemMatch != null) return stemMatch.groupValues[1].trim()
        // 否则取第一行非空内容（去掉来源标记前缀）
        return raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("```") && !it.startsWith("OCR") }
            ?: raw.take(120)
    }

    /**
     * 将本次搜题的 [Source] 日志聚合成一条 RecognitionRecord 写入存储。
     * 调用点：每次搜题流程结束时（Source 1 成功 / 截图链路结束 / 全部失败）。
     */
    private fun commitRecognitionRecord(detail: TestDetail, questionPreview: String) {
        if (triggerStartMs == 0L) return
        val attempts = parseSourceAttemptsSince(triggerStartMs)
        val diagnosis = parseDiagnosisLinesSince(triggerStartMs)
        val record = RecognitionLogStore.RecognitionRecord(
            timestamp = triggerStartMs,
            attempts = attempts,
            finalAnswer = detail.finalAnswer,
            errorCode = detail.errorCode,
            questionPreview = questionPreview.take(120),
            durationMs = System.currentTimeMillis() - triggerStartMs,
            diagnosis = diagnosis
        )
        runCatching { RecognitionLogStore.appendRecord(applicationContext, record) }
            .onFailure { AppLogger.log("[RecognitionLog] append_failed err=${it.message?.take(80)}") }
        triggerStartMs = 0L
    }

    /**
     * 从 AppLogger 抓取本次搜题的诊断行，写入 RecognitionRecord.diagnosis 供"识别日志"页面展示。
     * 捕获的诊断类别（1.1.10 扩充）：
     *   [Pipeline]       - 主路径选择（Vision-first / OCR-first / 降级 / 缓存命中）
     *   [VisionApi]      - Vision API 耗时、解析结果
     *   [Extract]        - 题目提取详情（题型/题干/选项）
     *   [QuestionBank]   - bank_status 题库状态 / 候选 top1 / 命中诊断 / 各 tier 召回数 /
     *                      不命中对比 / matched=true 命中明示 / raw vs final 答案归一 /
     *                      remap_summary / stripped_qno 题号剥离
     *   [OptionMatch]    - 选项匹配率（Vision 路径核心信号）
     *   [AnswerRemap]    - 答案字母按选项内容反查
     */
    private fun parseDiagnosisLinesSince(startMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = startMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val tsRegex = Regex("""^\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})] (\[(?:Pipeline|VisionApi|Extract|QuestionBank|OptionMatch|AnswerRemap)] .*)$""")
        val lines = mutableListOf<String>()
        for (line in AppLogger.getLogs()) {
            val m = tsRegex.matchEntire(line) ?: continue
            val ts = midnight +
                (m.groupValues[1].toInt() * 3600L + m.groupValues[2].toInt() * 60L + m.groupValues[3].toInt()) * 1000L +
                m.groupValues[4].toInt()
            if (ts < startMs - 50) continue
            val payload = m.groupValues[5]
            // 关注的诊断行白名单（1.1.11 扩充）
            if (payload.startsWith("[Pipeline]") ||
                payload.startsWith("[VisionApi]") ||
                payload.startsWith("[Extract]") ||
                payload.contains("bank_status") ||
                payload.contains("stripped_qno") ||
                payload.contains("remap_summary") ||
                payload.contains("remap_downgrade") ||
                payload.contains("miss_diagnosis") ||
                payload.contains("candidate_1 ") ||                              // top1
                payload.contains("candidate_2 ") ||                              // 1.1.11 新：top2-5
                payload.contains("candidate_3 ") ||
                payload.contains("candidate_4 ") ||
                payload.contains("candidate_5 ") ||
                payload.contains("candidate_dist") ||                            // 1.1.11 新：分布行
                payload.contains("tier") && payload.contains("_loaded=") ||
                payload.contains("matched=true") ||
                payload.contains("raw_answer=") && payload.contains("final=") ||
                payload.startsWith("[OptionMatch]") ||
                payload.startsWith("[AnswerRemap]")
            ) {
                lines.add(payload)
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 从 AppLogger 内存日志（最多 100 条）筛选 triggerStartMs 之后的 [Source] 行，
     * 解析成 SourceAttempt 列表。
     *
     * AppLogger 条目格式："[HH:mm:ss.SSS] [Source] verb=SOURCE detail..."
     */
    private fun parseSourceAttemptsSince(startMs: Long): List<RecognitionLogStore.SourceAttempt> {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = startMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val regex = Regex("""^\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})] \[Source] (.*)$""")
        val out = mutableListOf<RecognitionLogStore.SourceAttempt>()
        for (line in AppLogger.getLogs()) {
            val m = regex.matchEntire(line) ?: continue
            val ts = midnight +
                (m.groupValues[1].toInt() * 3600L + m.groupValues[2].toInt() * 60L + m.groupValues[3].toInt()) * 1000L +
                m.groupValues[4].toInt()
            if (ts < startMs - 50) continue  // 留 50ms 容差
            val payload = m.groupValues[5]
            // payload 形如 "try=ACCESSIBILITY_NODE_TEXT" / "failed=X reason=Y err=Z" / "ACCESSIBILITY_NODE_TEXT blocks=0"
            val firstSpace = payload.indexOf(' ')
            val head = if (firstSpace > 0) payload.substring(0, firstSpace) else payload
            val tail = if (firstSpace > 0) payload.substring(firstSpace + 1).trim() else ""
            val eqIdx = head.indexOf('=')
            if (eqIdx <= 0) continue  // 跳过形如 "[Source] ACCESSIBILITY_NODE_TEXT blocks=0" 的进度信息
            val verb = head.substring(0, eqIdx)
            val source = head.substring(eqIdx + 1)
            out.add(RecognitionLogStore.SourceAttempt(source = source, outcome = verb, detail = tail))
        }
        return out
    }

    /**
     * 防御性过滤：拦截"看起来是答案但其实是垃圾"的字符串。
     *
     * 拦截规则：
     *  1. 空白 / 仅含空白字符
     *  2. 显式"未命中"哨兵：无法判断 / 暂不支持
     *  3. 历史哨兵：纯 "?" 或 纯 "？"（题库匹配器旧版的 unsupported 标记）
     *  4. 纯标点 / 纯符号 / 纯连字符
     */
    private fun isMeaningfulAnswerText(answer: String): Boolean {
        val trimmed = answer.trim()
        if (trimmed.isBlank()) return false
        if (trimmed == "无法判断" || trimmed == "暂不支持") return false
        // 纯 ? / 纯 ？ 或两者混合
        if (trimmed.all { it == '?' || it == '？' }) return false
        // 全部是标点 / 非字母非数字非中文（剔除一切"信息含量为零"的答案）
        val meaningfulChars = trimmed.count { ch ->
            ch.isLetterOrDigit() || ch in '一'..'鿿'
        }
        return meaningfulChars > 0
    }

    // ══════════════════════════════════════════════════�?    // OverlayActionListener 其他回调
    // ══════════════════════════════════════════════════�?
    override fun onOverlayRequestRegionEditor() {
        // 已删除：不再支持设置识别区域
        renderState(FloatingWindowUiState(statusText = "当前版本使用整屏截图，无需设置识别区域", isBusy = false))
    }

    override fun onOverlayOpenDebugPanel() {
        startActivity(Intent(this, DebugPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onOverlayCloseBall() {
        AppLogger.log("[FloatingBall] hide")
        MyAccessibilityService.getInstance()?.destroyAllOverlays()
        getSharedPreferences("quantum_floating_prefs", MODE_PRIVATE)
            .edit().putBoolean("pref_overlay_enabled", false).apply()
        stopSelf()
    }

    override fun onOverlayModeChanged(mode: FloatingWindowMode) {
        saveMode(mode)
    }

    override fun onSensitiveSceneChanged(paused: Boolean, packageName: String) {
        sensitiveScenePaused = paused
        sensitiveScenePackage = packageName
        if (paused) {
            screenshotInProgress = false
            captureRestoreJob?.cancel()
            MyAccessibilityService.getInstance()?.restoreOverlayAfterScreenshot()
            renderPausedProtectionState()
        } else {
            resetUi()
        }
    }

    /**
     * 音量下键双击触发：MyAccessibilityService 已销毁所有悬浮窗，
     * 这里同步：取消正在进行的截图、写偏好为关闭、停服。
     */
    override fun onEmergencyCloseRequested() {
        AppLogger.log("[EmergencyClose] floating_service_stop")
        screenshotInProgress = false
        captureRestoreJob?.cancel()
        getSharedPreferences("quantum_floating_prefs", MODE_PRIVATE)
            .edit().putBoolean("pref_overlay_enabled", false).apply()
        stopSelf()
    }

    // ══════════════════════════════════════════════════�?    // 工具方法
    // ══════════════════════════════════════════════════�?
    private fun beginScreenshotPhase(): Long {
        activeCaptureToken += 1L
        screenshotInProgress = true
        recognitionDeadlineMs = System.currentTimeMillis() + RECOGNITION_GUARD_MS
        return activeCaptureToken
    }

    /** 识别阶段是否仍在进行（含 OCR / Vision / 资料 / 兜底的全过程）。 */
    private fun isRecognitionInProgress(): Boolean =
        recognitionDeadlineMs > 0L && System.currentTimeMillis() < recognitionDeadlineMs

    /** 识别结束（正常完成或异常退出都要调，务必放在 finally 里）。 */
    private fun endRecognitionPhase() {
        recognitionDeadlineMs = 0L
    }

    private fun isActiveCapture(token: Long): Boolean = token == activeCaptureToken

    private fun renderState(state: FloatingWindowUiState) {
        MyAccessibilityService.getInstance()?.renderOverlay(state)
    }

    private fun hideFloatingForCapture(a11yService: MyAccessibilityService, token: Long) {
        AppLogger.log("[FloatingProtection] hide_for_capture token=$token")
        a11yService.hideOverlayForScreenshot()
        captureRestoreJob?.cancel()
        captureRestoreJob = serviceScope.launch {
            delay(SCREENSHOT_TIMEOUT_MS)
            if (isActiveCapture(token) && screenshotInProgress) {
                AppLogger.log("[FloatingProtection] capture_timeout_restore token=$token")
                a11yService.restoreOverlayAfterScreenshot()
                screenshotInProgress = false
                renderState(FloatingWindowUiState(
                    statusText = "截图超时，请重试",
                    isBusy = false
                ))
            }
        }
    }

    private fun restoreFloatingAfterCapture(a11yService: MyAccessibilityService) {
        captureRestoreJob?.cancel()
        captureRestoreJob = null
        a11yService.restoreOverlayAfterScreenshot()
    }

    private fun isSearchPaused(a11yService: MyAccessibilityService? = MyAccessibilityService.getInstance()): Boolean {
        return sensitiveScenePaused || a11yService?.isSearchPausedForForeground() == true
    }

    private fun renderPausedProtectionState() {
        renderState(FloatingWindowUiState(
            statusText = "当前应用已启用暂停保护，AI 搜题功能暂不可用。",
            answerText = "",
            isBusy = false,
            isPaused = true,
            sourceLabel = "暂停保护"
        ))
    }

    private fun resetUi() {
        screenshotInProgress = false
        MyAccessibilityService.getInstance()?.restoreOverlayAfterScreenshot()
        if (isSearchPaused()) renderPausedProtectionState()
        else renderState(FloatingWindowUiState(statusText = "就绪", isBusy = false))
    }

    private fun saveMode(mode: FloatingWindowMode) {
        prefs.edit().putString(PREF_MODE, mode.name).apply()
    }

    private fun loadMode(): FloatingWindowMode {
        val raw = prefs.getString(PREF_MODE, FloatingWindowMode.NORMAL.name)
        return runCatching { FloatingWindowMode.valueOf(raw.orEmpty()) }.getOrElse { FloatingWindowMode.NORMAL }
    }

    private fun startInForeground() {
        createNotificationChannelIfNeeded()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI搜题")
            .setContentText("悬浮窗搜题服务已启用")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "服务运行通知", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }
}
