package com.lk.studyassistant.quantum

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lk.studyassistant.quantum.data.RecognitionLogStore
import com.lk.studyassistant.quantum.local.ImportReportStore
import com.lk.studyassistant.quantum.local.LocalQuestionBankRepository
import kotlinx.coroutines.*

class UserCenterActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_center)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        // 1.1.43：已移除激活/授权体系，App 安装即全功能，用户中心不再展示授权信息。

        findViewById<android.view.View>(R.id.layoutLegal).setOnClickListener { showLegalDialog() }
        findViewById<android.view.View>(R.id.layoutOssLicense).setOnClickListener { showOssLicenseDialog() }

        // Version info
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tvVersionCode).text = "版本 ${pkgInfo.versionName} (build ${pkgInfo.versionCode})"
        } catch (_: Exception) {}

        // Recognition log
        findViewById<Button>(R.id.btnViewRecognitionLog).setOnClickListener { showRecognitionLog() }

        // Question bank diagnosis
        findViewById<Button>(R.id.btnQuestionBankDiagnosis).setOnClickListener { showQuestionBankDiagnosis() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * 开源许可页面。
     *
     * Apache-2.0 要求**分发二进制时**随附许可证文本与署名——仓库里有 LICENSE 不算数，
     * 因为拿到 APK 的人接触不到仓库。所以这一页在应用内是合规必需的，不是装饰。
     */
    private fun showOssLicenseDialog() {
        val text = """
            ━━━━━━━━━━ 本应用 ━━━━━━━━━━

            AI 搜题（aisouti）
            Copyright 2026 xdada439
            采用 Apache License 2.0 授权。

            源码：https://github.com/xdada439/aisouti
            许可证全文：http://www.apache.org/licenses/LICENSE-2.0


            ━━━━━━━━━━ 第三方组件 ━━━━━━━━━━

            以下组件按各自许可证条款使用，版权归各自作者所有。

            · AndroidX（core-ktx / appcompat / lifecycle-service）
              Copyright The Android Open Source Project
              Apache License 2.0

            · Material Components for Android
              Copyright Google LLC
              Apache License 2.0

            · Kotlin Coroutines
              Copyright JetBrains s.r.o.
              Apache License 2.0

            · OkHttp
              Copyright Square, Inc.
              Apache License 2.0

            · HiddenApiBypass
              Copyright LSPosed
              Apache License 2.0

            · Google ML Kit（中文文字识别）
              Copyright Google LLC
              适用 Google APIs 服务条款与 ML Kit 条款
              https://developers.google.com/ml-kit/terms


            ━━━━━━━━━━ Apache License 2.0 要点 ━━━━━━━━━━

            允许自由使用、修改、再分发，包括商业用途。
            要求保留版权声明与许可证副本，并注明所做的修改。
            软件按"现状"提供，不附带任何明示或暗示的担保；
            在法律允许的范围内，作者不对使用本软件产生的
            任何损害承担责任。
        """.trimIndent()

        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("开源许可")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showQuestionBankDiagnosis() {
        // 后台线程查 DB，UI 线程弹对话框
        scope.launch {
            val bank = LocalQuestionBankRepository(this@UserCenterActivity)
            val total = bank.countQuestions()
            val byType = bank.countByType()
            val sources = bank.listSourceFiles()
            val importLog = ImportReportStore.formatForDisplay(this@UserCenterActivity)

            val statText = buildString {
                append("━━━━━━━━━━ 题库当前状态 ━━━━━━━━━━\n")
                append("总题数: $total\n")
                append("分类统计:\n")
                append("  单选: ${byType["single"] ?: 0}\n")
                append("  多选: ${byType["multiple"] ?: 0}\n")
                append("  判断: ${byType["judge"] ?: 0}\n")
                append("  填空: ${byType["blank"] ?: 0}\n")
                val other = (byType["unknown"] ?: 0)
                if (other > 0) append("  未分类: $other\n")
                append("\n")
                if (sources.isEmpty()) {
                    append("(无导入文件)\n\n")
                } else {
                    append("━━━━━━━━━━ 来源文件 ━━━━━━━━━━\n")
                    val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    sources.forEach {
                        append("  · ${it.sourceFile.ifBlank { "(未命名)" }}  ${it.count} 题")
                        if (it.latestCreatedAt > 0) append("  [${df.format(java.util.Date(it.latestCreatedAt))}]")
                        append("\n")
                    }
                    append("\n")
                }
                append(importLog)
            }

            withContext(Dispatchers.Main) {
                val scroll = android.widget.ScrollView(this@UserCenterActivity)
                val tv = TextView(this@UserCenterActivity).apply {
                    this.text = statText
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(
                        resources.displayMetrics.density.times(20).toInt(),
                        resources.displayMetrics.density.times(12).toInt(),
                        resources.displayMetrics.density.times(20).toInt(),
                        resources.displayMetrics.density.times(12).toInt()
                    )
                }
                scroll.addView(tv)
                AlertDialog.Builder(this@UserCenterActivity)
                    .setTitle("题库诊断")
                    .setView(scroll)
                    .setPositiveButton("测试搜题") { _, _ ->
                        showTestSearchDialog()
                    }
                    .setNeutralButton("复制全部") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("question_bank_diagnosis", statText))
                        Toast.makeText(this@UserCenterActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /**
     * 1.1.11 新增：测试搜题对话框。
     * 用户粘贴题干+选项 → 跑完整题库匹配流程 → 显示候选 + 命中。
     * 不需要截图就能验证"题库能否命中某道题"，方便排查/客户自查。
     */
    private fun showTestSearchDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val tip = TextView(this).apply {
            text = "粘贴题干（含选项最佳，每行一条）：\n例：\n肺癌的主要临床表现有\nA 刺激性咳嗽\nB 咯血\nC 痰中带血\nD 胸痛\nE 发热"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@UserCenterActivity, R.color.text_tertiary))
        }
        val input = android.widget.EditText(this).apply {
            hint = "在这里粘贴题干和选项..."
            minLines = 6
            maxLines = 12
            textSize = 13f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setBackgroundColor(0x10000000)
            setPadding(20, 16, 20, 16)
        }
        container.addView(tip)
        container.addView(android.widget.Space(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24
            )
        })
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("测试搜题（不消耗 API）")
            .setView(container)
            .setPositiveButton("开始匹配") { _, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "请粘贴题干", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runTestSearch(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解析用户粘贴的文本 → 构造 QuestionExtractResult → 跑题库匹配 → 显示结果 */
    private fun runTestSearch(text: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (stem, options) = parsePastedQuestion(text)
                    val opts = options.entries.map {
                        com.lk.studyassistant.quantum.util.OptionItem(it.key, it.value)
                    }
                    // 题型根据选项数+关键词推断
                    val type = when {
                        opts.size >= 2 && (stem.contains("包括") || stem.contains("有哪") || stem.contains("哪几")) ->
                            com.lk.studyassistant.quantum.util.QuestionType.MULTIPLE_CHOICE
                        opts.size >= 2 -> com.lk.studyassistant.quantum.util.QuestionType.SINGLE_CHOICE
                        // 1.1.42：占位符不再意味着填空题（单选题干普遍带括号占位符），
                        // 判不出就 UNKNOWN——UNKNOWN 在题库侧是"题型不设约束"，比标错安全
                        else -> com.lk.studyassistant.quantum.util.QuestionType.UNKNOWN
                    }
                    val q = com.lk.studyassistant.quantum.util.QuestionExtractResult(
                        questionType = type,
                        questionNumber = "",
                        questionText = stem,
                        options = opts,
                        blanks = emptyList(),
                        isComplete = stem.isNotBlank(),
                        missingFields = emptyList(),
                        unsupportedReason = null,
                        confidence = 0.9f,
                        source = "AI视觉",   // 走 Vision 路径的评分（选项内容权重高）
                        rawJson = ""
                    )
                    com.lk.studyassistant.quantum.local.LocalQuestionBankRepository(this@UserCenterActivity)
                        .searchWithCandidates(q) to (stem to opts)
                }.getOrElse { e ->
                    Pair(Pair(null, emptyList()), Pair("解析失败: ${e.message}", emptyList()))
                }
            }
            val (matchOut, parsed) = result
            val (bankAnswer, candidates) = matchOut
            val (stem, opts) = parsed

            val text2 = buildString {
                append("━━ 解析结果 ━━\n")
                append("题干: $stem\n")
                if (opts.isEmpty()) append("（未解析到选项）\n")
                else opts.forEach { append("  ${it.label}: ${it.text}\n") }
                append("\n━━ 匹配结果 ━━\n")
                if (bankAnswer != null) {
                    append("✓ 命中题库\n")
                    append("答案: ${bankAnswer.answer}\n")
                    append("置信度: ${"%.3f".format(bankAnswer.confidence)}\n")
                    append("匹配题干: ${bankAnswer.detail}\n")
                } else {
                    append("✗ 未命中（score < 0.30 阈值）\n")
                }
                append("\n━━ Top 候选 ━━\n")
                if (candidates.isEmpty()) {
                    append("（题库未召回任何候选 — 可能是题库为空或这道题不在题库里）\n")
                } else {
                    candidates.take(5).forEachIndexed { i, c ->
                        append("[${i+1}] score=${"%.3f".format(c.score)} 答案=${c.answer}\n")
                        append("    stem=${c.stem.take(80)}\n")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val scroll = android.widget.ScrollView(this@UserCenterActivity)
                val tv2 = TextView(this@UserCenterActivity).apply {
                    this.text = text2
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(40, 24, 40, 24)
                }
                scroll.addView(tv2)
                AlertDialog.Builder(this@UserCenterActivity)
                    .setTitle("测试搜题结果")
                    .setView(scroll)
                    .setPositiveButton("再测一次") { _, _ -> showTestSearchDialog() }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 解析用户粘贴的文本，按行分析题干 + 选项 */
    private fun parsePastedQuestion(text: String): Pair<String, LinkedHashMap<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val options = LinkedHashMap<String, String>()
        val stemLines = mutableListOf<String>()
        val optRegex = Regex("^([A-Ha-h])[\\s.、．:：](.+)$")
        val optRegex2 = Regex("^[\\(（]?([A-Ha-h])[\\)）]\\s*(.+)$")
        for (ln in lines) {
            val m1 = optRegex.find(ln) ?: optRegex2.find(ln)
            if (m1 != null) {
                val label = m1.groupValues[1].uppercase()
                val content = m1.groupValues[2].trim()
                if (label !in options) options[label] = content
            } else {
                stemLines.add(ln)
            }
        }
        return Pair(stemLines.joinToString(" "), options)
    }

    private fun showRecognitionLog() {
        val text = RecognitionLogStore.formatForDisplay(this)
        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(
                resources.displayMetrics.density.times(20).toInt(),
                resources.displayMetrics.density.times(12).toInt(),
                resources.displayMetrics.density.times(20).toInt(),
                resources.displayMetrics.density.times(12).toInt()
            )
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("识别日志（近 3 次启动）")
            .setView(scroll)
            .setPositiveButton("复制全部") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("recognition_log", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清空") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("清空识别日志？")
                    .setMessage("将清除全部历史记录，此操作不可撤销。")
                    .setPositiveButton("确认清空") { _, _ ->
                        RecognitionLogStore.clearAll(this)
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle("用户协议 & 隐私政策")
            .setMessage(
                "━━━━━━━━━━ 用户使用协议 ━━━━━━━━━━\n\n" +
                "AI搜题助手 用户使用协议\n" +
                "更新日期：2026年5月19日\n\n" +
                "一、总则\n" +
                "1.1 本协议是您（以下简称「用户」）与AI搜题助手软件（以下简称「本软件」）之间关于使用本软件服务所订立的协议。\n" +
                "1.2 您在使用本软件之前，应当仔细阅读本协议，并同意本协议的全部条款。如您不同意本协议的任何条款，请立即停止使用本软件。\n" +
                "1.3 您开始使用本软件，即表示您已阅读、理解并同意接受本协议的全部内容。\n\n" +
                "二、使用方式\n" +
                "2.1 本软件为完全离线的单机工具，安装后即可使用全部功能，无需注册账号，无需激活码，无需联网验证。\n" +
                "2.2 本软件不设账号体系，不生成设备指纹，不与任何服务器通信来校验您的使用权限。\n" +
                "2.3 本软件唯一会产生的网络请求，是您自行配置 AI 接口后、由您的设备直接发往您选择的模型服务商的请求。\n" +
                "2.4 您可自由使用、卸载本软件，不存在到期、续费或吊销。\n\n" +
                "三、使用场景约束\n" +
                "3.1 本软件是学习辅助工具，用户应在符合所在学校、机构、考试组织方规定的前提下使用。\n" +
                "3.2 用户因在禁止使用辅助工具的场合使用本软件而产生的一切后果，由用户自行承担。\n" +
                "3.3 本软件需要无障碍服务与悬浮窗权限才能读取屏幕题目并显示答案，用户可随时在系统设置中关闭这些权限。\n\n" +
                "四、AI API配置\n" +
                "4.1 本软件不提供默认的AI API服务，用户需自行向AI服务提供商（如通义千问、豆包、智谱、Kimi 等）申请API密钥。\n" +
                "4.2 用户配置的API密钥仅保存在设备本地，本软件不会上传或转发至任何第三方。\n" +
                "4.3 用户使用AI服务所产生的费用由用户自行承担，本软件不参与任何费用结算。\n" +
                "4.4 用户应遵守AI服务提供商的使用条款和法律法规，不得利用AI生成违法违规内容。\n\n" +
                "五、知识产权\n" +
                "5.1 本软件（包括但不限于程序代码、界面设计、图标、文档等）的知识产权归软件开发者所有。\n" +
                "5.2 未经开发者书面许可，任何人不得对本软件进行反向工程、反编译、破解、修改、复制或分发。\n" +
                "5.3 用户通过本软件导入的题库资料，其知识产权归用户或原权利人所有。\n\n" +
                "六、免责声明\n" +
                "6.1 本软件按「现状」提供，不提供任何明示或暗示的担保。\n" +
                "6.2 本软件不保证AI答题结果的准确性、完整性或适用性，用户应自行判断和使用AI生成的内容。\n" +
                "6.3 因用户自行配置的API服务不可用、网络故障等原因造成的损失，本软件不承担责任。\n" +
                "6.4 因不可抗力、设备故障、系统维护等原因导致的服务中断，本软件不承担责任。\n\n" +
                "七、终止\n" +
                "7.1 您可随时停止使用并卸载本软件，无需任何手续。\n" +
                "7.2 本协议终止后，您应停止使用本软件并自行删除相关文件。\n\n" +
                "八、其他\n" +
                "8.1 本协议的解释、效力及争议的解决，均适用中华人民共和国法律。\n" +
                "8.2 如本协议的任何条款被认定为无效或不可执行，其余条款仍然有效。\n" +
                "8.3 开发者有权在必要时修改本协议，修改后的协议将在软件更新时一并发布。\n\n\n" +
                "━━━━━━━━━━ 隐私政策 ━━━━━━━━━━\n\n" +
                "AI搜题助手 隐私政策\n" +
                "更新日期：2026年5月19日\n\n" +
                "一、信息收集\n" +
                "1.1 本软件是一款离线单机工具，我们高度重视您的隐私保护。\n" +
                "1.2 本软件不收集任何信息。没有账号体系，不生成设备指纹，不上报设备标识。\n" +
                "1.3 我们不收集您的姓名、手机号码、身份证号、地理位置、通讯录、相册、短信、通话记录等任何个人隐私数据。\n" +
                "1.4 本软件会读取屏幕内容（通过无障碍服务或截屏）以识别题目。这些内容仅在您的设备内存中处理，" +
                "除非您配置了 AI 接口——此时截图或题目文字会由您的设备直接发送给您选择的模型服务商，不经过我们。\n\n" +
                "二、信息存储\n" +
                "2.1 您的所有数据（包括API密钥、题库资料、识别日志、导入记录等）均存储在您的设备本地存储空间内。\n" +
                "2.2 本软件不会将您的任何数据上传至任何远程服务器、云端或第三方平台。\n" +
                "2.3 您的数据安全完全由您自己掌控。我们建议您定期备份重要数据。\n\n" +
                "三、信息使用\n" +
                "3.1 本软件不采集信息，因此不存在信息使用场景。\n" +
                "3.2 您自行配置的AI API密钥仅保存在本机，用于向您指定的AI服务提供商发起请求，本软件不会截获、存储或转发您的API密钥。\n" +
                "3.3 我们不会将您的任何信息用于广告投放、用户画像、数据分析或任何商业用途。\n\n" +
                "四、第三方服务\n" +
                "4.1 本软件不嵌入任何第三方统计SDK、广告SDK或数据采集SDK。\n" +
                "4.2 当您使用AI服务时，相关的数据将由您直接发送至您配置的AI服务提供商，请参考该服务商的隐私政策。\n\n" +
                "五、数据安全\n" +
                "5.1 本软件不上传数据，因此不存在传输环节的泄露风险。\n" +
                "5.2 您的 API 密钥保存在本机私有目录，仅在向您指定的服务商发起请求时使用。\n" +
                "5.3 我们采取了合理的技术措施保护您的本地数据安全，但由于单机离线特性，数据备份由您自行负责。\n\n" +
                "六、您的权利\n" +
                "6.1 您可以随时卸载本软件，卸载后设备上的所有相关数据将被删除。\n" +
                "6.2 由于我们不收集您的个人数据，因此不存在数据导出、更正或删除的个人信息请求处理。\n" +
                "6.3 由于本软件不联网校验、不上传数据，您对数据拥有完全控制权。\n\n" +
                "七、儿童隐私\n" +
                "7.1 本软件不针对13岁以下儿童设计，我们不会故意收集儿童的个人信息。\n" +
                "7.2 如监护人发现儿童在使用本软件，请监督其使用行为并管理AI API的配置。\n\n" +
                "八、政策更新\n" +
                "8.1 我们可能会不时更新本隐私政策，更新后的政策将在软件新版本中发布。\n" +
                "8.2 重大变更将以弹窗或公告形式通知用户。\n" +
                "8.3 本政策的最终解释权归软件开发者所有。"
            )
            .setPositiveButton("我已阅读并同意", null)
            .show()
    }
}
