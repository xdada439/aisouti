package com.lk.studyassistant.quantum

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.lk.studyassistant.quantum.data.AntiDetectionStore
import com.lk.studyassistant.quantum.data.ApiConfigStore
import com.lk.studyassistant.quantum.local.LocalQuestionBankRepository
import com.lk.studyassistant.quantum.local.MaterialRepository
import com.lk.studyassistant.quantum.service.FloatingWindowService
import com.lk.studyassistant.quantum.service.MyAccessibilityService
import com.lk.studyassistant.quantum.util.AppLogger

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "quantum_floating_prefs"
        private const val PREF_OVERLAY_ENABLED = "pref_overlay_enabled"
        private const val PREF_FLOATING_MODE = "pref_floating_mode"
        /** 首启用户协议是否已同意（1.1.43 起代替原激活流程的协议勾选） */
        private const val PREF_AGREEMENT_ACCEPTED = "pref_agreement_accepted"
        private var titleClickCount = 0
        private var titleLastClickTime = 0L
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvDataStatus: TextView
    private lateinit var btnUserCenter: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnOpenAccessibilityService: Button
    private lateinit var btnOpenOverlayPermission: Button
    private lateinit var btnOpenBatteryOptimization: Button
    private lateinit var btnToggleFloating: Button
    private lateinit var btnModeNormal: Button
    private lateinit var btnModeMinimal: Button
    private lateinit var btnImportData: Button
    private lateinit var btnExit: Button
    private lateinit var swAntiDetection: SwitchCompat
    private var isMinimalMode = false

    private val configStore by lazy { ApiConfigStore(this) }
    private val antiDetectionStore by lazy { AntiDetectionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogger.log("[App] start MainActivity")

        tvTitle = findViewById(R.id.tvTitle)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvDataStatus = findViewById(R.id.tvDataStatus)
        btnUserCenter = findViewById(R.id.btnUserCenter)
        tvStatus = findViewById(R.id.tvStatus)
        btnOpenAccessibilityService = findViewById(R.id.btnOpenAccessibilityService)
        btnOpenOverlayPermission = findViewById(R.id.btnOpenOverlayPermission)
        btnOpenBatteryOptimization = findViewById(R.id.btnOpenBatteryOptimization)
        btnToggleFloating = findViewById(R.id.btnToggleFloating)
        btnModeNormal = findViewById(R.id.btnModeNormal)
        btnModeMinimal = findViewById(R.id.btnModeMinimal)
        btnImportData = findViewById(R.id.btnImportData)
        btnExit = findViewById(R.id.btnExit)
        swAntiDetection = findViewById(R.id.swAntiDetection)

        // 防检测开关：变更后立即生效（重启悬浮窗以应用新 LayoutParams.flags）
        swAntiDetection.isChecked = antiDetectionStore.enabled
        swAntiDetection.setOnCheckedChangeListener { _, checked ->
            antiDetectionStore.enabled = checked
            AppLogger.log("[AntiDetect] switch_changed enabled=$checked")
            if (isOverlayEnabled() && MyAccessibilityService.isServiceRunning()) {
                FloatingWindowService.hide(this)
                FloatingWindowService.show(this)
            }
        }

        // 加载保存的模式
        isMinimalMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_FLOATING_MODE, "normal") == "minimal"
        updateModeButtons()

        // 隐藏入口：连续点击标题5次进入调试/测试详情面板
        tvTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - titleLastClickTime > 2000) titleClickCount = 0
            titleLastClickTime = now
            titleClickCount++
            if (titleClickCount >= 5) {
                titleClickCount = 0
                AppLogger.log("[Debug] open_debug_panel via title_click_5")
                startActivity(Intent(this, DebugPanelActivity::class.java))
            }
        }

        btnOpenAccessibilityService.setOnClickListener {
            AppLogger.log("[Home] click_accessibility_permission")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenOverlayPermission.setOnClickListener {
            AppLogger.log("[Home] click_overlay_permission")
            openOverlayPermissionPage()
        }

        btnOpenBatteryOptimization.setOnClickListener {
            AppLogger.log("[Home] click_battery_optimization")
            requestIgnoreBatteryOptimization()
        }

        btnToggleFloating.setOnClickListener { handleToggleFloating() }

        btnModeNormal.setOnClickListener {
            isMinimalMode = false
            saveMode()
            updateModeButtons()
        }
        btnModeMinimal.setOnClickListener {
            isMinimalMode = true
            saveMode()
            updateModeButtons()
        }

        btnImportData.setOnClickListener {
            AppLogger.log("[Home] click_import_data")
            startActivity(Intent(this, UploadCenterActivity::class.java))
        }

        findViewById<android.view.View>(R.id.layoutApiConfig).setOnClickListener {
            AppLogger.log("[Home] click_api_config")
            startActivity(Intent(this, AISettingsActivity::class.java))
        }

        findViewById<android.view.View>(R.id.layoutDisplaySettings).setOnClickListener {
            AppLogger.log("[Home] click_display_settings")
            startActivity(Intent(this, DisplaySettingsActivity::class.java))
        }

        btnUserCenter.setOnClickListener {
            AppLogger.log("[Home] click_user_center")
            startActivity(Intent(this, UserCenterActivity::class.java))
        }

        btnExit.setOnClickListener {
            AppLogger.log("[App] exit")
            handleExit()
        }

        showAgreementIfFirstLaunch()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /**
     * 首启用户协议（1.1.43）。
     *
     * 1.1.43 移除了激活/授权体系，App 安装即全功能、不联网校验。原来的协议勾选在
     * 激活页上，激活页删除后用这个弹窗接替：只弹一次，同意后写本地标记。
     * 不做"不同意就退出"的硬拦截——这不是收费墙，只是告知。
     */
    private fun showAgreementIfFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_AGREEMENT_ACCEPTED, false)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("使用前须知")
            .setMessage(
                "本应用为学习辅助工具，需要无障碍服务与悬浮窗权限来读取屏幕题目并显示答案。\n\n" +
                    "· 题库与资料全部保存在你的手机本地，我们不收集、不上传\n" +
                    "· 如需 AI 识别能力，请在「AI 接口设置」里填写你自己的模型 API Key，" +
                    "请求直连你选择的模型服务商，不经过我们的服务器\n" +
                    "· 请在符合所在机构规定的前提下使用\n\n" +
                    "继续使用即表示你已知悉以上内容。"
            )
            .setCancelable(false)
            .setPositiveButton("我已知悉") { _, _ ->
                prefs.edit().putBoolean(PREF_AGREEMENT_ACCEPTED, true).apply()
                AppLogger.log("[App] agreement_accepted")
            }
            .show()
    }

    private fun updateModeButtons() {
        if (isMinimalMode) {
            btnModeNormal.setBackgroundResource(R.drawable.bg_segment_right)
            btnModeNormal.setTextColor(0xFF64748B.toInt())
            btnModeMinimal.setBackgroundResource(R.drawable.bg_btn_gradient)
            btnModeMinimal.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btnModeNormal.setBackgroundResource(R.drawable.bg_btn_gradient)
            btnModeNormal.setTextColor(0xFFFFFFFF.toInt())
            btnModeMinimal.setBackgroundResource(R.drawable.bg_segment_right)
            btnModeMinimal.setTextColor(0xFF64748B.toInt())
        }
    }

    private fun saveMode() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_FLOATING_MODE, if (isMinimalMode) "minimal" else "normal")
            .apply()
        // 如果悬浮球已开启，通知服务切换模式
        if (isOverlayEnabled()) {
            FloatingWindowService.toggleMode(this)
        }
    }

    private fun handleToggleFloating() {
        if (isOverlayEnabled()) {
            AppLogger.log("[FloatingBall] hide")
            setOverlayEnabled(false)
            FloatingWindowService.hide(this)
            syncOverlayButton()
            tvStatus.text = ""
        } else {
            if (!canDrawOverlay()) {
                AppLogger.log("[FloatingBall] failed NO_OVERLAY_PERMISSION")
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                openOverlayPermissionPage()
                return
            }
            if (!MyAccessibilityService.isServiceRunning()) {
                AppLogger.log("[FloatingBall] failed NO_ACCESSIBILITY_PERMISSION")
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            AppLogger.log("[FloatingBall] show")
            setOverlayEnabled(true)
            FloatingWindowService.show(this)
            syncOverlayButton()
            tvStatus.text = ""
        }
    }

    private fun handleExit() {
        setOverlayEnabled(false)
        FloatingWindowService.hide(this)
        AppLogger.log("[App] exit_complete")
        finishAffinity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask()
    }

    private fun refreshStatus() {
        val overlayGranted = canDrawOverlay()
        val accessibilityEnabled = MyAccessibilityService.isServiceRunning()
        val apiReady = configStore.isReady()
        val batteryIgnored = isIgnoringBatteryOptimization()

        AppLogger.log("[Permission] accessibility_enabled=$accessibilityEnabled")
        AppLogger.log("[Permission] overlay_enabled=$overlayGranted")
        AppLogger.log("[Permission] battery_ignored=$batteryIgnored")
        AppLogger.log("[Startup] api_config_exists=$apiReady")

        tvAccessibilityStatus.text = if (accessibilityEnabled) "已开启" else "未开启"
        tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this,
            if (accessibilityEnabled) R.color.status_good else R.color.status_warn))

        tvOverlayStatus.text = if (overlayGranted) "已开启" else "未开启"
        tvOverlayStatus.setTextColor(ContextCompat.getColor(this,
            if (overlayGranted) R.color.status_good else R.color.status_warn))

        tvBatteryStatus.text = if (batteryIgnored) "已忽略" else "受限"
        tvBatteryStatus.setTextColor(ContextCompat.getColor(this,
            if (batteryIgnored) R.color.status_good else R.color.status_warn))
        btnOpenBatteryOptimization.text = if (batteryIgnored) "已忽略" else "去忽略"
        btnOpenBatteryOptimization.isEnabled = !batteryIgnored

        Thread {
            try {
                val qCount = LocalQuestionBankRepository(this).countQuestions()
                val mCount = MaterialRepository(this).countChunks()
                AppLogger.log("[Startup] question_bank_total_count=$qCount material_chunks_count=$mCount")
                runOnUiThread {
                    tvDataStatus.text = "题库 ${qCount}题  资料 ${mCount}条"
                }
            } catch (_: Exception) { }
        }.start()

        syncOverlayButton()
        tvStatus.text = ""
    }

    private fun syncOverlayButton() {
        btnToggleFloating.text = if (isOverlayEnabled()) "关闭悬浮球" else "开启悬浮球"
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    }

    private fun openOverlayPermissionPage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统版本无需设置", Toast.LENGTH_SHORT).show()
            return
        }
        if (isIgnoringBatteryOptimization()) {
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (direct.resolveActivity(packageManager) != null) {
            startActivity(direct)
            return
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (fallback.resolveActivity(packageManager) != null) {
            startActivity(fallback)
        } else {
            Toast.makeText(this, "无法打开电池优化设置，请在系统设置中手动开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun isOverlayEnabled() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_OVERLAY_ENABLED, false)

    private fun setOverlayEnabled(enabled: Boolean) =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_OVERLAY_ENABLED, enabled).apply()
}
