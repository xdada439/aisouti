package com.lk.studyassistant.quantum.local

import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 题库导入报告持久化。
 *
 * 每次 Excel 导入完成后写入一条；保留最近 [MAX_REPORTS] 条供用户在「题库诊断」中查看。
 * 包含失败行明细（cap 50 行避免存储膨胀），定位"哪些题没导进来"。
 */
object ImportReportStore {

    private const val PREFS_NAME = "question_bank_import_reports"
    private const val KEY_REPORTS = "reports_json"
    private const val MAX_REPORTS = 5
    private const val MAX_FAILURES_PER_REPORT = 50

    data class StoredReport(
        val timestamp: Long,
        val sourceName: String,
        val status: String,
        val errorCode: String,
        val totalRows: Int,
        val imported: Int,
        val failedRows: Int,
        val singleCount: Int,
        val multipleCount: Int,
        val judgeCount: Int,
        val blankCount: Int,
        val unsupportedCount: Int,
        val failureReasons: List<String>
    )

    fun append(context: Context, sourceName: String, report: LocalQuestionBankRepository.ImportReport) {
        val stored = StoredReport(
            timestamp = System.currentTimeMillis(),
            sourceName = sourceName,
            status = report.status,
            errorCode = report.errorCode,
            totalRows = report.totalRows,
            imported = report.imported,
            failedRows = report.failedRows,
            singleCount = report.singleCount,
            multipleCount = report.multipleCount,
            judgeCount = report.judgeCount,
            blankCount = report.blankCount,
            unsupportedCount = report.unsupportedCount,
            failureReasons = report.failureReasons.take(MAX_FAILURES_PER_REPORT)
        )
        val list = loadAll(context).toMutableList()
        list.add(stored)
        while (list.size > MAX_REPORTS) list.removeAt(0)
        saveAll(context, list)
        AppLogger.log("[ImportReportStore] saved source=$sourceName imported=${stored.imported} failed=${stored.failedRows}")
    }

    fun loadAll(context: Context): List<StoredReport> {
        val raw = prefs(context).getString(KEY_REPORTS, null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrElse {
            AppLogger.log("[ImportReportStore] parse_failed err=${it.message?.take(80)}")
            emptyList()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_REPORTS).apply()
    }

    /**
     * 渲染为用户中心对话框可直接展示的文本。
     * 含每次导入的总计 + 失败行明细。
     */
    fun formatForDisplay(context: Context): String {
        val all = loadAll(context)
        if (all.isEmpty()) return "(尚无导入记录)"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        // 倒序：最近一次在最上
        all.asReversed().forEachIndexed { idx, r ->
            val label = if (idx == 0) "最近一次导入" else "${idx + 1} 次前"
            sb.append("━━━━━━━━━━ $label ━━━━━━━━━━\n")
            sb.append("时间: ${df.format(Date(r.timestamp))}\n")
            sb.append("文件: ${r.sourceName}\n")
            sb.append("状态: ${if (r.status == "success") "成功" else "失败"}")
            if (r.errorCode.isNotBlank()) sb.append("（${r.errorCode}）")
            sb.append("\n")
            sb.append("总行数: ${r.totalRows}  成功: ${r.imported}  失败: ${r.failedRows}\n")
            sb.append("分类: 单选 ${r.singleCount}  多选 ${r.multipleCount}  判断 ${r.judgeCount}  填空 ${r.blankCount}  不支持 ${r.unsupportedCount}\n")
            if (r.failureReasons.isNotEmpty()) {
                sb.append("\n失败明细（最多 $MAX_FAILURES_PER_REPORT 条）:\n")
                r.failureReasons.forEach { sb.append("  · $it\n") }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    // ───── JSON 序列化 ─────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveAll(context: Context, list: List<StoredReport>) {
        val root = JSONArray()
        for (r in list) {
            val reasons = JSONArray().apply { r.failureReasons.forEach { put(it) } }
            root.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("sourceName", r.sourceName)
                put("status", r.status)
                put("errorCode", r.errorCode)
                put("totalRows", r.totalRows)
                put("imported", r.imported)
                put("failedRows", r.failedRows)
                put("singleCount", r.singleCount)
                put("multipleCount", r.multipleCount)
                put("judgeCount", r.judgeCount)
                put("blankCount", r.blankCount)
                put("unsupportedCount", r.unsupportedCount)
                put("failureReasons", reasons)
            })
        }
        prefs(context).edit().putString(KEY_REPORTS, root.toString()).apply()
    }

    private fun parse(raw: String): List<StoredReport> {
        val root = JSONArray(raw)
        val out = mutableListOf<StoredReport>()
        for (i in 0 until root.length()) {
            val o = root.optJSONObject(i) ?: continue
            val reasons = mutableListOf<String>()
            o.optJSONArray("failureReasons")?.let { arr ->
                for (j in 0 until arr.length()) reasons.add(arr.optString(j))
            }
            out.add(
                StoredReport(
                    timestamp = o.optLong("timestamp"),
                    sourceName = o.optString("sourceName"),
                    status = o.optString("status"),
                    errorCode = o.optString("errorCode"),
                    totalRows = o.optInt("totalRows"),
                    imported = o.optInt("imported"),
                    failedRows = o.optInt("failedRows"),
                    singleCount = o.optInt("singleCount"),
                    multipleCount = o.optInt("multipleCount"),
                    judgeCount = o.optInt("judgeCount"),
                    blankCount = o.optInt("blankCount"),
                    unsupportedCount = o.optInt("unsupportedCount"),
                    failureReasons = reasons
                )
            )
        }
        return out
    }
}
