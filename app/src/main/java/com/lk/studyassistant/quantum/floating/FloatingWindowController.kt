package com.lk.studyassistant.quantum.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.lk.studyassistant.quantum.R
import com.lk.studyassistant.quantum.data.DisplaySettingsStore
import com.lk.studyassistant.quantum.local.TextNormalizer
import com.lk.studyassistant.quantum.util.Utils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingWindowController(
    context: Context,
    private val callbacks: Callbacks
) {

    companion object {
        /** 答案 TextView 最多行数（1.1.9：去掉 5 字截断后，给长选项内容更多换行空间）*/
        private const val MAX_ANSWER_LINES = 12

        /** 极简模式小圆球的固定透明度
         *  1.1.12：脱离 root.alpha 控制，永远半透明
         *  1.1.13：20% → 50%（客户反馈 20% 几乎看不见，找不到悬浮球位置）
         */
        private const val MINIMAL_ORB_ALPHA = 0.50f

        /**
         * @deprecated 1.1.9 起不再截断；保留函数以便未来按需启用。
         * 5 字截断的历史动机：选项文本太长会撑大悬浮窗。
         * 现在通过"仅显示答案"开关让用户自己控制是否要内容显示。
         */
        @Suppress("unused")
        private const val MAX_OPTION_CHARS = 5
    }

    interface Callbacks {
        fun onTriggerCapture()
        fun onRequestRegionEditor()
        fun onOpenDebugPanel()
        fun onCloseBall()
        fun onModeChanged(mode: FloatingWindowMode)
        fun onMoveWindow(newWindowX: Int, newWindowY: Int)
    }

    private val appContext = context.applicationContext
    private val inflater = LayoutInflater.from(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displaySettings = DisplaySettingsStore(appContext)

    // 答案 TextView 的基准字号（sp）。用户字号档位会乘到这里。
    private val normalAnswerBaseSp = 13f
    private val minimalAnswerBaseSp = 14f

    private var rootView: View? = null

    private var buttonView: TextView? = null
    private var statusView: TextView? = null
    private var answerInlineView: TextView? = null
    private var answerScrollView: NestedScrollView? = null
    private var answerScrollTextView: TextView? = null
    private var sourceBar: View? = null
    private var sourceView: TextView? = null
    private var copyButton: Button? = null

    private var minimalRootView: FrameLayout? = null
    private var minimalOrbView: TextView? = null
    private var menuView: LinearLayout? = null

    private var currentMode = FloatingWindowMode.NORMAL
    private var currentState = FloatingWindowUiState()
    private var screenshotHidden = false

    private var currentWindowX = Utils.dp2px(appContext, 12)
    private var currentWindowY = 0

    // 极简球"搜索中"三点帧动画
    private var dotsAnimator: Runnable? = null
    private var dotsIndex = 0
    private val dotsFrames = arrayOf("·", "· ·", "· · ·", "· ·")

    fun getOrCreateRootView(): View {
        val existing = rootView
        if (existing != null) return existing

        val root = FrameLayout(appContext)
        rootView = root
        rebuildRootContent(root)
        applyScreenshotHiddenState()
        return root
    }

    fun getRootView(): View? = rootView

    fun updateWindowPosition(x: Int, y: Int) {
        currentWindowX = x
        currentWindowY = y
    }

    fun render(state: FloatingWindowUiState) {
        currentState = if (
            currentMode == FloatingWindowMode.MINIMAL &&
            state.isBusy &&
            state.answerText.isBlank() &&
            currentState.answerText.isNotBlank()
        ) {
            // 答案气泡被点击触发下一次识别时，保持气泡形态和当前位置，避免切回小球造成视觉跳动。
            state.copy(answerText = "...", answerOptions = LinkedHashMap())
        } else {
            state
        }
        applyUiState()
    }

    fun setMode(mode: FloatingWindowMode) {
        if (currentMode == mode) return
        currentMode = mode
        rebuildRootContent(rootView as? FrameLayout)
        callbacks.onModeChanged(mode)
        applyUiState()
    }

    fun getMode(): FloatingWindowMode = currentMode

    fun hideForScreenshot() {
        screenshotHidden = true
        applyScreenshotHiddenState()
    }

    fun restoreAfterScreenshot() {
        screenshotHidden = false
        applyScreenshotHiddenState()
    }

    fun getSearchAreaRectOnScreen(): Rect? {
        val screenSize = getRealScreenSize()
        val screenWidth = screenSize.x
        val screenHeight = screenSize.y
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val topInset = getTopInset()
        val bottomInset = getBottomInset()

        val horizontalMargin = max(Utils.dp2px(appContext, 16), (screenWidth * 0.05f).toInt())
        val extraTopMargin = max(Utils.dp2px(appContext, 12), (screenHeight * 0.04f).toInt())
        val extraBottomMargin = max(Utils.dp2px(appContext, 20), (screenHeight * 0.08f).toInt())

        val maxTabletWidth = Utils.dp2px(appContext, 920)

        val safeLeftBase = horizontalMargin
        val safeRightBase = screenWidth - horizontalMargin
        val safeTop = (topInset + extraTopMargin).coerceAtLeast(0)
        val safeBottom = (screenHeight - bottomInset - extraBottomMargin)
            .coerceAtMost(screenHeight)

        val availableWidth = (safeRightBase - safeLeftBase).coerceAtLeast(1)
        val finalWidth = min(availableWidth, maxTabletWidth)
        val centeredLeft = ((screenWidth - finalWidth) / 2).coerceAtLeast(safeLeftBase)
        val centeredRight = (centeredLeft + finalWidth).coerceAtMost(safeRightBase)

        val rect = Rect(
            centeredLeft,
            safeTop,
            centeredRight,
            safeBottom
        )

        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    private fun startDotsAnimation() {
        stopDotsAnimation()
        dotsIndex = 0
        val runnable = object : Runnable {
            override fun run() {
                val orb = minimalOrbView ?: return
                if (orb.visibility == View.VISIBLE && currentState.isBusy) {
                    orb.text = dotsFrames[dotsIndex % dotsFrames.size]
                    dotsIndex++
                    mainHandler.postDelayed(this, 400L)
                }
            }
        }
        dotsAnimator = runnable
        mainHandler.post(runnable)
    }

    private fun stopDotsAnimation() {
        dotsAnimator?.let { mainHandler.removeCallbacks(it) }
        dotsAnimator = null
    }

    private fun rebuildRootContent(root: FrameLayout?) {
        stopDotsAnimation()
        val realRoot = root ?: return
        realRoot.removeAllViews()

        buttonView = null
        statusView = null
        answerInlineView = null
        answerScrollView = null
        answerScrollTextView = null
        sourceBar = null
        sourceView = null
        copyButton = null
        minimalRootView = null
        minimalOrbView = null
        menuView = null

        when (currentMode) {
            FloatingWindowMode.NORMAL -> {
                val normalView = inflater.inflate(R.layout.layout_floating_window, realRoot, false)
                buttonView = normalView.findViewById(R.id.btn_screenshot)
                statusView = normalView.findViewById(R.id.tv_data)
                answerInlineView = normalView.findViewById(R.id.tv_data_ans_inline)
                answerScrollView = normalView.findViewById(R.id.scroll_answer)
                answerScrollTextView = normalView.findViewById(R.id.tv_data_ans)
                sourceBar = normalView.findViewById(R.id.source_bar)
                sourceView = normalView.findViewById(R.id.tv_source)
                copyButton = normalView.findViewById(R.id.btn_copy)

                copyButton?.setOnClickListener {
                    copyDebugTextToClipboard()
                }

                bindDragAndClickTouch(
                    target = buttonView ?: normalView,
                    dragThresholdDp = 8,  // 1.1.13: 2 → 8，避免轻微抖动误判拖拽
                    longPressTimeoutMs = 300L,
                    onClick = {
                        AppLogger.log("[Search] click_search_ball")
                        if (!currentState.isBusy) callbacks.onTriggerCapture()
                    },
                    onLongPress = {
                        toggleActionMenu()
                    }
                )

                realRoot.addView(normalView)
                realRoot.addView(createActionMenu())
            }

            FloatingWindowMode.MINIMAL -> {
                val minimalView = createMinimalModeView()
                realRoot.addView(minimalView)
                realRoot.addView(createActionMenu())
            }
        }
    }

    private fun createMinimalModeView(): View {
        val root = FrameLayout(appContext)
        minimalRootView = root

        // 答案气泡（有答案时显示，跟随用户透明度设置）
        val answerBubble = TextView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = ""
            maxLines = MAX_ANSWER_LINES
            ellipsize = null
            textSize = 14f
            setTextColor(Color.parseColor("#FF1A1A1A"))
            background = null
            setPadding(Utils.dp2px(appContext, 4), 0,
                Utils.dp2px(appContext, 4), 0)
            visibility = View.GONE
        }
        // 绑定拖拽+点击：点击清空答案，长按拖动
        bindDragAndClickTouch(
            target = answerBubble,
            dragThresholdDp = 8,  // 1.1.13: 2 → 8
            longPressTimeoutMs = 300L,
            onClick = {
                AppLogger.log("[Search] click_search_answer_bubble")
                if (!currentState.isBusy) callbacks.onTriggerCapture()
            }
        )
        answerInlineView = answerBubble
        root.addView(answerBubble)

        // 小圆球（无答案时显示，纯透明背景，仅显示点点文字）
        val orbSize = Utils.dp2px(appContext, 40)
        val orb = TextView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(orbSize, orbSize).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = ""
            maxLines = 1
            textSize = 13f
            setTextColor(Color.parseColor("#CC404040"))
            background = null
            alpha = MINIMAL_ORB_ALPHA
        }
        minimalOrbView = orb

        bindDragAndClickTouch(
            target = orb,
            dragThresholdDp = 8,  // 1.1.13: 2 → 8，修复客户反馈"点击后悬浮球跑掉"的根因
            longPressTimeoutMs = 300L,
            onClick = {
                AppLogger.log("[Search] click_search_orb")
                if (!currentState.isBusy) callbacks.onTriggerCapture()
            },
            onLongPress = {
                toggleActionMenu()
            }
        )

        root.addView(orb)
        return root
    }

    private fun createActionMenu(): LinearLayout {
        val menu = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                Utils.dp2px(appContext, 8),
                Utils.dp2px(appContext, 8),
                Utils.dp2px(appContext, 8),
                Utils.dp2px(appContext, 8)
            )
            // 背景全透明：长按只显示按钮文字，不要灰白底板/描边/阴影
            background = null
            elevation = 0f
        }

        fun addMenuButton(text: String, onClick: () -> Unit) {
            val button = Button(appContext).apply {
                this.text = text
                textSize = 14f
                minHeight = Utils.dp2px(appContext, 38)
                // 去掉系统按钮默认的浅灰底，只保留文字（颜色随系统深浅）
                background = null
                setOnClickListener {
                    hideActionMenu()
                    onClick()
                }
            }
            menu.addView(
                button,
                LinearLayout.LayoutParams(
                    Utils.dp2px(appContext, 132),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addMenuButton("关闭悬浮球") {
            AppLogger.log("[FloatingBall] hide_from_menu")
            callbacks.onCloseBall()
        }

        menuView = menu
        menu.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = Utils.dp2px(appContext, 42)
            rightMargin = Utils.dp2px(appContext, 4)
        }
        return menu
    }

    private fun toggleActionMenu() {
        val menu = menuView ?: return
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun hideActionMenu() {
        menuView?.visibility = View.GONE
    }

    private fun applyUiState() {
        when (currentMode) {
            FloatingWindowMode.NORMAL -> applyNormalUiState()
            FloatingWindowMode.MINIMAL -> applyMinimalUiState()
        }
        // 整窗透明度：作用到 rootView，让"答"按钮 + 状态栏 + 答案 + 玻璃背景一起变淡。
        // 截图隐藏期间 applyScreenshotHiddenState 会强制覆盖为 0，结束后再回到这里恢复用户值。
        applyWindowAlpha()
        applyScreenshotHiddenState()
    }

    /**
     * 透明度应用（1.1.12 重构）：
     *  - NORMAL 模式：rootView.alpha = 用户设置（"答"按钮+状态+答案+背景一起变淡）
     *  - MINIMAL 模式：
     *      · rootView.alpha = 1.0（root 不透明，避免 orb 被二次衰减）
     *      · orb.alpha = max(用户设置, 0.25)（跟随透明度滑杆，但至少保留 25% 可见性）
     *      · bubble.alpha = 用户设置（有答案时，跟整窗设置一致）
     */
    private fun applyWindowAlpha() {
        if (screenshotHidden) return
        val userAlpha = displaySettings.getAnswerAlpha()
        when (currentMode) {
            FloatingWindowMode.NORMAL -> {
                rootView?.alpha = userAlpha
            }
            FloatingWindowMode.MINIMAL -> {
                rootView?.alpha = 1.0f
                minimalOrbView?.alpha = maxOf(userAlpha, 0.25f)
                // 答案气泡只在有答案时显示，单独应用用户透明度
                answerInlineView?.alpha = userAlpha
            }
        }
    }

    private fun applyNormalUiState() {
        val button = buttonView ?: return
        val status = statusView ?: return
        val answerInline = answerInlineView ?: return
        val scrollView = answerScrollView ?: return
        val sourceRow = sourceBar ?: return

        button.text = when {
            currentState.isPaused -> "停"
            currentState.isBusy -> "..."
            else -> "答"
        }

        // 状态栏：仅暂停保护时显示。busy 时按钮已显示 "..."，不再额外显示状态文字
        // 避免 GONE→VISIBLE 引起窗口高度变化，导致 CENTER_VERTICAL 重算偏移产生"跳动"
        val hasAnswer = currentState.answerText.isNotBlank()
        status.visibility = if (currentState.isPaused && !hasAnswer) View.VISIBLE else View.GONE
        status.text = if (currentState.isPaused) currentState.statusText else ""

        // 来源栏：始终隐藏
        sourceRow.visibility = View.GONE

        // 调试滚动区：始终隐藏
        scrollView.visibility = View.GONE

        if (hasAnswer) {
            // 只显示答案，干净简洁
            answerInline.visibility = View.VISIBLE
            val displayed = enrichAnswerWithOptions(currentState.answerText, currentState.answerOptions)
            answerInline.text = withNotice(displayed)
            answerInline.maxLines = MAX_ANSWER_LINES + if (currentState.noticeText.isNotBlank()) 1 else 0
            answerInline.ellipsize = null
            answerInline.setTextColor(Color.parseColor("#FF1A1A1A"))
            applyAnswerStyle(answerInline, normalAnswerBaseSp)
            logAnswerRendered("NORMAL", displayed)
        } else if (currentState.hintText.isNotBlank()) {
            // 1.1.13：灰色提示（如"请滚动让题目完整显示"）
            answerInline.visibility = View.VISIBLE
            answerInline.text = currentState.hintText
            answerInline.maxLines = 2
            answerInline.ellipsize = null
            answerInline.setTextColor(Color.parseColor("#FF888888"))
            applyAnswerStyle(answerInline, normalAnswerBaseSp)
            AppLogger.log("[AnswerDisplay] mode=NORMAL hint=${currentState.hintText.take(40)}")
        } else {
            answerInline.visibility = View.GONE
        }
    }

    private fun applyMinimalUiState() {
        val orb = minimalOrbView ?: return
        val bubble = answerInlineView ?: return

        val hasAnswer = currentState.answerText.isNotBlank()

        if (hasAnswer) {
            // 有答案：停止动画，隐藏球，显示答案气泡
            stopDotsAnimation()
            orb.visibility = View.GONE
            bubble.visibility = View.VISIBLE
            val displayed = enrichAnswerWithOptions(currentState.answerText, currentState.answerOptions)
            bubble.text = withNotice(displayed)
            bubble.maxLines = MAX_ANSWER_LINES + if (currentState.noticeText.isNotBlank()) 1 else 0
            bubble.setTextColor(Color.parseColor("#FF1A1A1A"))
            applyAnswerStyle(bubble, minimalAnswerBaseSp)
            logAnswerRendered("MINIMAL", displayed)
        } else if (currentState.hintText.isNotBlank()) {
            // 灰色提示（如"请滚动让题目完整显示"）
            stopDotsAnimation()
            orb.visibility = View.GONE
            bubble.visibility = View.VISIBLE
            bubble.text = currentState.hintText
            bubble.maxLines = 2
            bubble.setTextColor(Color.parseColor("#FF888888"))
            applyAnswerStyle(bubble, minimalAnswerBaseSp)
            AppLogger.log("[AnswerDisplay] mode=MINIMAL hint=${currentState.hintText.take(40)}")
        } else {
            // 无答案：显示小圆球
            bubble.visibility = View.GONE
            orb.visibility = View.VISIBLE
            orb.alpha = maxOf(displaySettings.getAnswerAlpha(), 0.25f)
            when {
                currentState.isPaused -> {
                    stopDotsAnimation()
                    orb.text = "停"
                }
                currentState.isBusy -> {
                    // 搜索中：启动三点动画
                    startDotsAnimation()
                }
                else -> {
                    // 待机：固定显示两个点
                    stopDotsAnimation()
                    orb.text = "· ·"
                }
            }
        }
    }

    /**
     * 给答案文本接上兜底免责提示（1.1.42）。
     *
     * 题库/资料命中时 noticeText 为空，答案原样显示；只有走到"模型自身知识"这一级，
     * 才在答案下方补一行灰色小字「题库未检索到，依据视觉模型判断」，让用户知道
     * 这条答案没有题库依据。用 Spannable 而不是两个 TextView，避免动到悬浮窗布局
     * 高度（高度变化会让 CENTER_VERTICAL 重算位置，产生"跳动"）。
     */
    private fun withNotice(answer: CharSequence): CharSequence {
        val notice = currentState.noticeText
        if (notice.isBlank()) return answer
        val sb = android.text.SpannableStringBuilder(answer).append('\n').append(notice)
        val start = sb.length - notice.length
        sb.setSpan(
            android.text.style.ForegroundColorSpan(Color.parseColor("#FF888888")),
            start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.RelativeSizeSpan(0.6f),
            start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        AppLogger.log("[AnswerDisplay] notice=${notice.take(40)}")
        return sb
    }

    private fun copyDebugTextToClipboard() {
        // 优先复制完整调试文本，没有则复制模板展示文本
        val text = currentState.fullDebugText.ifBlank { currentState.debugText }
        if (text.isBlank()) {
            Toast.makeText(appContext, "无内容可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AI搜题调试信息", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(appContext, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun bindDragAndClickTouch(
        target: View,
        dragThresholdDp: Int,
        longPressTimeoutMs: Long,
        onClick: () -> Unit,
        onLongPress: (() -> Unit)? = null
    ) {
        // 1.1.13：用 ViewConfiguration.scaledTouchSlop 兜底，避免 2dp 阈值过低误判拖拽。
        val systemTouchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
        val dragThreshold = maxOf(Utils.dp2px(appContext, dragThresholdDp), systemTouchSlop)
        // 3dp：手指稍微挪动即取消"点击"判定，防止用户轻微调整位置后抬手意外触发搜索。
        // 比 dragThreshold 小：不会移动窗口，但会阻止点击，形成"死区"。
        val noClickThreshold = Utils.dp2px(appContext, 3)

        target.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startWindowX = 0
            private var startWindowY = 0
            private var longPressTriggered = false
            private var dragging = false
            private var fingerMoved = false  // 手指移动超过 noClickThreshold 则不当点击

            private val longPressRunnable = Runnable {
                longPressTriggered = true
                onLongPress?.invoke()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startWindowX = currentWindowX
                        startWindowY = currentWindowY
                        longPressTriggered = false
                        dragging = false
                        fingerMoved = false
                        mainHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY

                        if (!fingerMoved && (abs(dx) >= noClickThreshold || abs(dy) >= noClickThreshold)) {
                            fingerMoved = true
                        }

                        if (!longPressTriggered) {
                            // 长按触发前：移动超过阈值则进入拖拽模式，取消长按防误弹菜单
                            if (abs(dx) >= dragThreshold || abs(dy) >= dragThreshold) {
                                dragging = true
                                mainHandler.removeCallbacks(longPressRunnable)
                                // 立即开始拖拽
                                val newX = startWindowX - dx.toInt()
                                val newY = startWindowY + dy.toInt()
                                callbacks.onMoveWindow(newX, newY)
                            }
                            return true
                        }

                        // 长按已触发，进入拖拽模式
                        val newX = startWindowX - dx.toInt()
                        val newY = startWindowY + dy.toInt()
                        callbacks.onMoveWindow(newX, newY)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        val isClick = !longPressTriggered && !dragging && !fingerMoved
                        if (isClick) onClick()
                        longPressTriggered = false
                        dragging = false
                        fingerMoved = false
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // 系统取消（如截图前隐藏悬浮窗发 CANCEL）：不视为点击，仅重置状态
                        mainHandler.removeCallbacks(longPressRunnable)
                        longPressTriggered = false
                        dragging = false
                        fingerMoved = false
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 极简模式悬浮球外观（两层）：
     *   外层（44dp）：淡蓝色光晕，约 19% 不透明度，视觉上扩散到内核边缘之外
     *   内层（32dp，6dp inset）：主体圆球，约 46% 不透明度 + 1dp 描边
     * 整体再乘以用户透明度（≥0.25），白色背景下呈现柔和蓝色而不突兀。
     */
    private fun createMinimalOrbDrawable(): Drawable {
        val glowInset = Utils.dp2px(appContext, 6)
        val strokePx = Utils.dp2px(appContext, 1)

        val glowLayer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#3089CFF0"))   // baby blue ~19%
        }
        val orbLayer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#7589CFF0"))   // baby blue ~46%
            setStroke(strokePx, Color.parseColor("#AA89CFF0"))  // baby blue ~67%
        }
        return LayerDrawable(arrayOf(glowLayer, orbLayer)).also { ld ->
            ld.setLayerInset(0, 0, 0, 0, 0)
            ld.setLayerInset(1, glowInset, glowInset, glowInset, glowInset)
        }
    }

    private fun getRealScreenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }
    }

    private fun getTopInset(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        } else {
            Utils.calcStatusBarHeight(appContext).coerceAtLeast(0)
        }
    }

    private fun getBottomInset(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
            ).bottom
        } else {
            0
        }
    }

    private fun applyScreenshotHiddenState() {
        val view = rootView ?: return
        view.visibility = if (screenshotHidden) View.INVISIBLE else View.VISIBLE
        // 1.1.12 调整：截图期间强制 0；恢复时按模式不同处理
        //   · NORMAL：root.alpha = 用户设置
        //   · MINIMAL：root.alpha = 1.0（orb 自带 0.20，bubble 单独应用用户设置）
        view.alpha = when {
            screenshotHidden -> 0f
            currentMode == FloatingWindowMode.MINIMAL -> 1.0f
            else -> displaySettings.getAnswerAlpha()
        }
        view.isEnabled = !screenshotHidden
        view.isClickable = !screenshotHidden
    }

    /**
     * 应用用户设置的字号到答案 TextView。
     * 透明度在 applyWindowAlpha() 里整窗统一控制（B 方案），这里不再单独叠加 view.alpha。
     */
    private fun applyAnswerStyle(view: TextView, baseSp: Float) {
        view.textSize = baseSp * displaySettings.getAnswerFontScale().multiplier
    }

    /**
     * 把答案结合当前题目选项映射渲染为 "字母 + 选项内容"，避免 App 打乱 ABCD 顺序后用户选错。
     *
     * 三种输入形态：
     *  1. 纯字母（"A" / "AB" / "A,B"）        → 按字母拼当前屏选项内容
     *  2. 选项文本（LLM 偶发返回 "12个月"）→ 在当前屏 options 里反查得到字母，再拼成 "X 12个月"
     *  3. 其他（"无法判断" / "对" / "错" / 文本兜底失败） → 原样返回
     *
     * 内容超过 5 个字符自动截断为 5 字 + "…"，保持悬浮窗宽度稳定（多选时尤其重要）。
     *
     * 注意：精准题库路径 LocalQuestionBankRepository.remapAnswerByContent 已经做过一轮按
     * 内容反查的字母校正；这里覆盖的是模糊匹配/兜底 LLM 路径的鲁棒性。
     *
     * 1.1.9 新增：用户可在「悬浮窗显示设置」选择"仅显示答案"模式，此时直接返回原 answerText
     * 不做任何"字母+内容"拼接，悬浮窗只展示 A/B/C/D 字母。
     */
    private fun enrichAnswerWithOptions(
        answerText: String,
        options: Map<String, String>
    ): String {
        // 用户选择"仅显示答案" → 跳过所有增强，原样返回字母
        if (displaySettings.getAnswerDisplayMode() == DisplaySettingsStore.AnswerDisplayMode.LETTER_ONLY) {
            return answerText
        }
        if (options.isEmpty()) return answerText
        val trimmed = answerText.trim()
        if (trimmed.isEmpty()) return answerText

        // 判断题答案（对/错/正确/错误/√/×）直接原样显示，不做"字母+内容"拼接，
        // 避免 Vision 给了 {A:对,B:错} 选项时把"对"反查成"A 对"又冒出字母。
        if (trimmed in setOf("对", "错", "正确", "错误", "√", "×")) return answerText

        // ── 形态 1：纯字母答案 ──
        // 1.1.9: 既然用户主动选择了"开启答案显示"，就把完整内容显示出来，不再 5 字截断。
        //        长内容会自然换行，TextView 的 maxLines=6 仍兜底防止刷屏。
        val lettersOnly = trimmed.replace(Regex("[\\s,，;；、/]+"), "")
        if (lettersOnly.isNotEmpty() && lettersOnly.all { it in 'A'..'H' }) {
            val letters = lettersOnly.toCharArray().toSet().toList().sorted().map { it.toString() }
            if (letters.none { options[it]?.isNotBlank() == true }) return answerText
            return letters.joinToString("\n") { letter ->
                val text = options[letter]?.trim().orEmpty()
                if (text.isBlank()) letter else "$letter $text"
            }
        }

        // ── 形态 2：文本答案 → 反查当前屏选项字母 ──
        val (matchedLetter, matchMode) = findLetterByContent(trimmed, options)
        if (matchedLetter != null) {
            val text = options[matchedLetter]?.trim().orEmpty()
            AppLogger.log(
                "[AnswerRemap] text_answer=${trimmed.take(30)} -> letter=$matchedLetter mode=$matchMode"
            )
            return if (text.isBlank()) "$matchedLetter $trimmed"
            else "$matchedLetter $text"
        }

        // ── 形态 3：原样返回（判断题"对/错"、"无法判断"等）──
        return answerText
    }

    /**
     * 每个选项内容最多 5 字，超出加 "…"。
     * 用 codePoint 计数，对汉字 / emoji / 代理对都友好。
     */
    private fun truncateOptionText(text: String): String {
        val cps = text.codePoints().toArray()
        if (cps.size <= MAX_OPTION_CHARS) return text
        val sb = StringBuilder()
        for (i in 0 until MAX_OPTION_CHARS) sb.appendCodePoint(cps[i])
        sb.append('…')
        return sb.toString()
    }

    /**
     * 反查：把答案文本（如 "12个月"）映射到当前屏 options 里的字母。
     * 与 LocalQuestionBankRepository.findQueryLabelByContent 的策略保持一致，避免上下游不同步：
     *   精确等于 → 短串包含长串(长度比≥0.5) → bigram≥0.7 且 与第二名差≥0.1。
     * 返回 (字母 or null, 命中模式描述)。
     */
    private fun findLetterByContent(
        answer: String,
        options: Map<String, String>
    ): Pair<String?, String> {
        val target = TextNormalizer.normalize(answer).replace(" ", "")
        if (target.isEmpty()) return Pair(null, "empty_target")
        val normOptions = options
            .mapValues { TextNormalizer.normalize(it.value).replace(" ", "") }
            .filterValues { it.isNotBlank() }
        if (normOptions.isEmpty()) return Pair(null, "no_options")

        // 1. 精确
        normOptions.entries.firstOrNull { it.value == target }?.let { return Pair(it.key, "exact") }

        // 2. 短串包含 + 长度比≥0.5；多个命中视为歧义
        val containsHits = normOptions.entries.filter { (_, content) ->
            val short = if (target.length <= content.length) target else content
            val long = if (target.length <= content.length) content else target
            val ratio = if (long.isEmpty()) 0.0 else short.length.toDouble() / long.length
            ratio >= 0.5 && short.length >= 2 && short in long
        }
        if (containsHits.size == 1) return Pair(containsHits.first().key, "contains")

        // 3. bigram 相似度 + 长度比保护 + 与第二名分差≥0.1
        val scored = normOptions.entries.map { (label, content) ->
            val short = if (target.length <= content.length) target else content
            val long = if (target.length <= content.length) content else target
            val ratio = if (long.isEmpty()) 0.0 else short.length.toDouble() / long.length
            val s = if (ratio < 0.5) 0.0 else TextNormalizer.bigramOverlap(short, long)
            Pair(label, s)
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull() ?: return Pair(null, "no_scored")
        if (top.second < 0.7) return Pair(null, "low_score_${"%.2f".format(top.second)}")
        val second = scored.getOrNull(1)
        if (second != null && top.second - second.second < 0.1) {
            return Pair(null, "ambiguous_top=${top.first}:${"%.2f".format(top.second)}_2nd=${second.first}:${"%.2f".format(second.second)}")
        }
        return Pair(top.first, "bigram_${"%.2f".format(top.second)}")
    }

    /**
     * 答案渲染日志：把同一道题三次搜索的差异打到日志里，方便排查
     * "为什么三次答案不一样"。一行包含：
     *   mode    - 悬浮窗显示模式
     *   source  - 来源标签（精准题库/模糊匹配/兜底模式/无障碍文字/...）
     *   raw     - 主链路返回的原始答案（A / AB / 文本 / 无法判断）
     *   opts    - 当前屏抓到的选项快照（label:text 顺序保留），数量 + 前 3 个
     *   show    - 经过"字母+选项内容"增强后实际显示给用户的文本
     *   enriched- 是否触发增强（true=用 opts 拼了文本，false=原样显示）
     */
    private fun logAnswerRendered(mode: String, displayed: String) {
        val raw = currentState.answerText
        val source = currentState.sourceLabel.ifBlank { "-" }
        val opts = currentState.answerOptions
        val optsSummary = if (opts.isEmpty()) "0" else {
            val preview = opts.entries.take(3).joinToString("|") { (k, v) -> "$k:${v.take(12)}" }
            "${opts.size}[$preview]"
        }
        val enriched = displayed != raw
        AppLogger.log(
            "[AnswerDisplay] mode=$mode source=$source raw=${raw.take(30)} " +
                "opts=$optsSummary enriched=$enriched show=${displayed.replace("\n", " | ").take(80)}"
        )
    }
}
