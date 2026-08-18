package com.lk.studyassistant.quantum

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.lk.studyassistant.quantum.local.LocalQuestionBankRepository
import com.lk.studyassistant.quantum.local.MaterialRepository
import com.lk.studyassistant.quantum.local.XlsxTemplateWriter
import com.lk.studyassistant.quantum.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UploadCenterActivity : AppCompatActivity() {

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        private const val PREFS = "upload_records"
    }

    private lateinit var tvStatus: TextView
    private lateinit var etMaterialName: EditText
    private lateinit var btnImportQuestionBank: Button
    private lateinit var btnImportMaterial: Button
    private lateinit var btnDownloadTemplate: Button
    private lateinit var btnClearQuestionBank: Button
    private lateinit var btnClearMaterial: Button
    private lateinit var tvQbRecord: TextView
    private lateinit var tvMatRecord: TextView

    private var pendingType: ImportType = ImportType.QUESTION_BANK
    private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val questionBankRepo by lazy { LocalQuestionBankRepository(this) }
    private val materialRepo by lazy { MaterialRepository(this) }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importSelectedFile(uri, pendingType)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_center)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        tvStatus = findViewById(R.id.tvStatus)
        etMaterialName = findViewById(R.id.etMaterialName)
        btnImportQuestionBank = findViewById(R.id.btnImportQuestionBank)
        btnImportMaterial = findViewById(R.id.btnImportMaterial)
        tvQbRecord = findViewById(R.id.tvQbRecord)
        tvMatRecord = findViewById(R.id.tvMatRecord)

        btnImportQuestionBank.text = "导入题库 (.xlsx)"
        btnImportMaterial.text = "导入资料"

        btnImportQuestionBank.setOnClickListener {
            AppLogger.log("[Import] click_import_question_bank")
            pendingType = ImportType.QUESTION_BANK
            filePicker.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }

        btnImportMaterial.setOnClickListener {
            AppLogger.log("[Import] click_import_material")
            pendingType = ImportType.MATERIAL
            filePicker.launch("*/*")
        }

        btnDownloadTemplate = findViewById(R.id.btnDownloadTemplate)
        btnDownloadTemplate.setOnClickListener {
            AppLogger.log("[Template] click_download")
            downloadTemplate()
        }

        btnClearQuestionBank = findViewById(R.id.btnClearQuestionBank)
        btnClearQuestionBank.setOnClickListener {
            showManageQuestionBankDialog()
        }

        btnClearMaterial = findViewById(R.id.btnClearMaterial)
        btnClearMaterial.setOnClickListener {
            showManageMaterialDialog()
        }

        loadRecords()
    }

    private fun downloadTemplate() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = XlsxTemplateWriter.createQuestionBankTemplateBytes()
                    val fileName = "question_bank_template.xlsx"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+: use MediaStore for Downloads
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            uri
                        } else null
                    } else {
                        // Android 9 and below
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val file = File(dir, fileName)
                        FileOutputStream(file).use { it.write(bytes) }
                        file.toURI()
                    }
                }
            }
            result.fold(
                onSuccess = { uri ->
                    Toast.makeText(this@UploadCenterActivity, "模板已保存到下载目录: question_bank_template.xlsx", Toast.LENGTH_LONG).show()
                    AppLogger.log("[Template] download_success")
                },
                onFailure = { e ->
                    Toast.makeText(this@UploadCenterActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    AppLogger.log("[Template] download_failed ${e.message}")
                }
            )
        }
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val qbCount = withContext(Dispatchers.IO) { runCatching { questionBankRepo.countQuestions() }.getOrDefault(0) }
            val matChunkCount = withContext(Dispatchers.IO) { runCatching { materialRepo.countChunks() }.getOrDefault(0) }
            val matCount = withContext(Dispatchers.IO) { runCatching { materialRepo.countMaterials() }.getOrDefault(0) }

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val qbRecord = prefs.getString("qb_record", "")
            val matRecord = prefs.getString("mat_record", "")

            tvQbRecord.text = if (qbCount > 0) {
                "已导入 ${qbCount} 题${if (qbRecord?.isNotBlank() == true) "\n$qbRecord" else ""}"
            } else "尚未导入"

            tvMatRecord.text = if (matChunkCount > 0) {
                "已导入 ${matCount} 份资料，${matChunkCount} 条片段${if (matRecord?.isNotBlank() == true) "\n$matRecord" else ""}"
            } else "尚未导入"
        }
    }

    private fun showManageQuestionBankDialog() {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                runCatching { questionBankRepo.listSourceFiles() }.getOrDefault(emptyList())
            }
            val items = mutableListOf<String>()
            items.add("【清空全部题库】")
            sources.forEach { s ->
                val name = s.sourceFile.ifBlank { "(未命名来源)" }
                items.add("$name  ·  ${s.count} 题")
            }
            AlertDialog.Builder(this@UploadCenterActivity)
                .setTitle(if (sources.isEmpty()) "管理题库（尚无文件）" else "管理题库（点击单文件删除）")
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) confirmClearAllQuestionBank()
                    else confirmDeleteQuestionBankSource(sources[which - 1])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmClearAllQuestionBank() {
        AlertDialog.Builder(this)
            .setTitle("清空精准题库")
            .setMessage("确定要清空所有精准题库数据吗？此操作不可恢复。")
            .setPositiveButton("确定清空") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val count = withContext(Dispatchers.IO) { questionBankRepo.clearAll() }
                        AppLogger.log("[QuestionBank] clear_done count=$count")
                        Toast.makeText(this@UploadCenterActivity, "已清空 $count 道题", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@UploadCenterActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    loadRecords()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteQuestionBankSource(summary: LocalQuestionBankRepository.SourceFileSummary) {
        val name = summary.sourceFile.ifBlank { "(未命名来源)" }
        AlertDialog.Builder(this)
            .setTitle("删除题库文件")
            .setMessage("确定删除「$name」对应的 ${summary.count} 道题？此操作不可恢复。")
            .setPositiveButton("确定删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { questionBankRepo.deleteBySourceFile(summary.sourceFile) }.getOrDefault(0)
                    }
                    Toast.makeText(this@UploadCenterActivity, "已删除 $deleted 道题", Toast.LENGTH_SHORT).show()
                    loadRecords()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageMaterialDialog() {
        lifecycleScope.launch {
            val materials = withContext(Dispatchers.IO) {
                runCatching { materialRepo.listMaterials() }.getOrDefault(emptyList())
            }
            val items = mutableListOf<String>()
            items.add("【清空全部资料】")
            materials.forEach { m ->
                val name = m.name.ifBlank { m.sourceFile.ifBlank { "(未命名资料)" } }
                items.add("$name  ·  ${m.chunkCount} 条片段")
            }
            AlertDialog.Builder(this@UploadCenterActivity)
                .setTitle(if (materials.isEmpty()) "管理资料（尚无文件）" else "管理资料（点击单文件删除）")
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) confirmClearAllMaterial()
                    else confirmDeleteMaterial(materials[which - 1])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmClearAllMaterial() {
        AlertDialog.Builder(this)
            .setTitle("清空模糊匹配资料")
            .setMessage("确定要清空所有模糊匹配资料（包括切块索引）吗？此操作不可恢复。")
            .setPositiveButton("确定清空") { _, _ ->
                lifecycleScope.launch {
                    val count = withContext(Dispatchers.IO) { materialRepo.clearAll() }
                    AppLogger.log("[Material] clear_done chunk_count=$count")
                    Toast.makeText(this@UploadCenterActivity, "已清空 $count 条片段", Toast.LENGTH_SHORT).show()
                    loadRecords()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteMaterial(summary: MaterialRepository.MaterialSummary) {
        val name = summary.name.ifBlank { summary.sourceFile.ifBlank { "(未命名资料)" } }
        AlertDialog.Builder(this)
            .setTitle("删除资料文件")
            .setMessage("确定删除「$name」对应的 ${summary.chunkCount} 条片段？此操作不可恢复。")
            .setPositiveButton("确定删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { materialRepo.deleteMaterial(summary.id) }.getOrDefault(0)
                    }
                    Toast.makeText(this@UploadCenterActivity, "已删除 $deleted 条片段", Toast.LENGTH_SHORT).show()
                    loadRecords()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importSelectedFile(uri: Uri, type: ImportType) {
        val displayName = getDisplayName(uri)
        val size = getFileSize(uri)
        if (size <= 0L) { showStatus("无法读取文件大小，请换一个文件重试"); return }
        if (size > MAX_FILE_BYTES) { showStatus("文件过大，建议控制在50MB以内"); return }
        if (!isAllowedFile(displayName, type)) { showStatus(allowedMessage(type)); return }

        // 1.1.11: 防重复导入 — 题库类型先检查同名文件是否已存在
        if (type == ImportType.QUESTION_BANK) {
            val (exists, count) = questionBankRepo.hasSourceFile(displayName)
            if (exists) {
                AppLogger.log("[Import] duplicate_detected file=$displayName existing=$count")
                showDuplicateImportDialog(displayName, count, uri, type)
                return
            }
        }

        doImportSelectedFile(uri, type, displayName, size)
    }

    /** 1.1.11: 检测到同名文件已导入时，让用户选择覆盖 / 追加 / 取消 */
    private fun showDuplicateImportDialog(displayName: String, existingCount: Int, uri: Uri, type: ImportType) {
        AlertDialog.Builder(this)
            .setTitle("题库已存在")
            .setMessage(
                "「$displayName」已经导入过（${existingCount} 题）。\n\n" +
                "请选择处理方式：\n" +
                "· 覆盖：先删除旧的，再导入新的\n" +
                "· 追加：保留旧的，加入新的（可能产生重复题）\n" +
                "· 取消：放弃本次导入"
            )
            .setPositiveButton("覆盖") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { questionBankRepo.deleteBySourceFile(displayName) }.getOrDefault(0)
                    }
                    AppLogger.log("[Import] overwrite_deleted_old count=$deleted file=$displayName")
                    Toast.makeText(this@UploadCenterActivity, "已清除旧的 $deleted 题", Toast.LENGTH_SHORT).show()
                    val size = getFileSize(uri)
                    doImportSelectedFile(uri, type, displayName, size)
                }
            }
            .setNeutralButton("追加") { _, _ ->
                AppLogger.log("[Import] append_chosen file=$displayName")
                val size = getFileSize(uri)
                doImportSelectedFile(uri, type, displayName, size)
            }
            .setNegativeButton("取消") { _, _ ->
                AppLogger.log("[Import] duplicate_cancelled file=$displayName")
            }
            .setCancelable(false)
            .show()
    }

    private fun doImportSelectedFile(uri: Uri, type: ImportType, displayName: String, size: Long) {
        setBusy(true)
        showStatus("正在导入：$displayName")
        AppLogger.log("[Import] start type=$type file=$displayName size=${size / 1024}KB")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (type) {
                        ImportType.QUESTION_BANK -> {
                            val report = questionBankRepo.importExcelWithReport(uri, displayName)
                            saveRecord("qb_record", "文件: $displayName\n题数: ${report.imported}道\n时间: ${sdf.format(Date())}")
                            Pair("精准题库", report)
                        }
                        ImportType.MATERIAL -> {
                            val matName = etMaterialName.text?.toString().orEmpty().trim()
                            val report = materialRepo.importMaterialWithReport(uri, displayName, matName)
                            saveRecord("mat_record", "文件: $displayName\n片段: ${report.chunkCount}条\n时间: ${sdf.format(Date())}")
                            Pair("模糊匹配资料", report)
                        }
                    }
                }
            }

            setBusy(false)
            result.fold(
                onSuccess = { (label, report) ->
                    showImportDialog(label, report)
                    loadRecords()
                },
                onFailure = { showStatus(it.message ?: "导入失败") }
            )
        }
    }

    private fun showImportDialog(label: String, report: Any) {
        val message = when (report) {
            is LocalQuestionBankRepository.ImportReport -> buildString {
                append("【${label}导入结果】\n\n")
                if (report.status == "success") {
                    append("导入成功：${report.imported}题\n")
                    append("导入失败：${report.failedRows}题\n\n")
                    append("── 题型统计 ──\n")
                    append("单选题：${report.singleCount}\n")
                    append("多选题：${report.multipleCount}\n")
                    append("判断题：${report.judgeCount}\n")
                    append("填空题：${report.blankCount}\n")
                    if (report.unsupportedCount > 0) append("未识别题型：${report.unsupportedCount}\n")
                    if (report.failureReasons.isNotEmpty()) {
                        append("\n── 失败详情 ──\n")
                        report.failureReasons.take(20).forEach { append("$it\n") }
                        if (report.failureReasons.size > 20) append("...还有${report.failureReasons.size - 20}条\n")
                    }
                } else {
                    append("导入失败\n")
                    append("错误码：${report.errorCode}\n")
                    if (report.failureReasons.isNotEmpty()) {
                        append("原因：${report.failureReasons.first()}\n")
                    }
                }
            }
            is MaterialRepository.ImportReport -> buildString {
                append("【${label}导入结果】\n\n")
                if (report.success) {
                    append("导入成功\n")
                    append("成功切块：${report.chunkCount}条\n")
                    if (report.failedChunks > 0) {
                        append("失败切块：${report.failedChunks}条\n")
                    }
                    if (report.failureReasons.isNotEmpty()) {
                        append("\n── 失败详情 ──\n")
                        report.failureReasons.take(15).forEach { append("$it\n") }
                    }
                } else {
                    append("导入失败\n")
                    if (report.failureReasons.isNotEmpty()) {
                        append("原因：${report.failureReasons.first()}\n")
                    }
                }
            }
            else -> "导入完成"
        }

        AlertDialog.Builder(this)
            .setTitle("导入结果")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun saveRecord(key: String, value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun isAllowedFile(fileName: String, type: ImportType): Boolean {
        val lower = fileName.lowercase()
        return when (type) {
            ImportType.QUESTION_BANK -> lower.endsWith(".xlsx")
            ImportType.MATERIAL -> lower.endsWith(".txt") || lower.endsWith(".md") ||
                lower.endsWith(".xlsx") || lower.endsWith(".docx") || lower.endsWith(".pdf")
        }
    }

    private fun allowedMessage(type: ImportType): String = when (type) {
        ImportType.QUESTION_BANK -> "精准题库支持 .xlsx 格式（兼容考试宝模板）"
        ImportType.MATERIAL -> "模糊匹配资料支持 txt / xlsx / docx / pdf"
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "local_file"
        contentResolver.query(uri, null, null, null, null)?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) name = it.getString(index) ?: name
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use {
            val index = it.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && it.moveToFirst()) return it.getLong(index)
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                total += r
                if (total > MAX_FILE_BYTES) break
            }
            total
        } ?: -1L
    }

    private fun setBusy(busy: Boolean) {
        btnImportQuestionBank.isEnabled = !busy
        btnImportMaterial.isEnabled = !busy
    }

    private fun showStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
    }

    private enum class ImportType { QUESTION_BANK, MATERIAL }
}
