package com.lk.studyassistant.quantum.data

import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别日志持久化存储。
 *
 * 设计目标（离线单机版）：
 * - 每次应用进程启动开一个新的 Session
 * - 一次进程内多次搜题，逐条 append 到当前 Session
 * - 历史 Session 仅保留最近 3 次启动
 * - 不联网，全部存 SharedPreferences（数据量小，~30KB 上限即可覆盖几百次记录）
 *
 * 用法：
 *   QuantumApp.onCreate() → RecognitionLogStore.onAppLaunch(ctx)
 *   FloatingWindowService 搜题结束 → RecognitionLogStore.appendRecord(ctx, record)
 *   UserCenterActivity → RecognitionLogStore.formatForDisplay(ctx)
 */
object RecognitionLogStore {

    private const val PREFS_NAME = "recognition_log_store"
    private const val KEY_SESSIONS = "sessions_json"
    private const val MAX_SESSIONS = 3
    private const val MAX_RECORDS_PER_SESSION = 60

    /** 一次搜题尝试某个识别源的小段记录。 */
    data class SourceAttempt(
        val source: String,   // ACCESSIBILITY_NODE_TEXT / ACCESSIBILITY_SCREENSHOT / MEDIA_PROJECTION / MLKIT_OCR / VISION_API / FALLBACK_TO_TEXT_PIPELINE
        val outcome: String,  // success / failed / skipped / insufficient
        val detail: String    // 失败/跳过的原因 或 关键值
    )

    /** 一条搜题完整记录。 */
    data class RecognitionRecord(
        val timestamp: Long,
        val attempts: List<SourceAttempt>,
        val finalAnswer: String,
        val errorCode: String,
        val questionPreview: String,
        val durationMs: Long,
        /**
         * 诊断信息（多行）：来自 AppLogger 的 [Extract] 提取详情、[QuestionBank] miss 候选对比等。
         * 出错时用来快速定位是噪音污染、题库缺题、还是 normalize 不一致。可为空。
         */
        val diagnosis: String = ""
    )

    /** 一次应用启动周期内的所有搜题记录。 */
    data class Session(
        val startTime: Long,
        val records: MutableList<RecognitionRecord>
    )

    @Volatile
    private var currentSessionStart: Long = 0L

    fun onAppLaunch(context: Context) {
        currentSessionStart = System.currentTimeMillis()
        // 写入"会话开始"占位 Session（即使本次没有搜题也会显示进入过 App）
        val sessions = loadSessions(context).toMutableList()
        sessions.add(Session(startTime = currentSessionStart, records = mutableListOf()))
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        saveSessions(context, sessions)
        AppLogger.log("[RecognitionLog] session_started ts=$currentSessionStart kept=${sessions.size}")
    }

    fun appendRecord(context: Context, record: RecognitionRecord) {
        val sessions = loadSessions(context).toMutableList()
        if (sessions.isEmpty() || sessions.last().startTime != currentSessionStart) {
            // 极端情况：进程被杀复活但 onAppLaunch 没跑，自动补一个
            sessions.add(Session(startTime = currentSessionStart.ifZero { System.currentTimeMillis() }, records = mutableListOf()))
            while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        }
        val cur = sessions.last()
        cur.records.add(record)
        while (cur.records.size > MAX_RECORDS_PER_SESSION) cur.records.removeAt(0)
        saveSessions(context, sessions)
    }

    fun loadSessions(context: Context): List<Session> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching { parseJson(raw) }.getOrElse {
            AppLogger.log("[RecognitionLog] parse_failed err=${it.message?.take(80)}")
            emptyList()
        }
    }

    fun clearAll(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSIONS).apply()
        AppLogger.log("[RecognitionLog] cleared_all")
    }

    fun formatForDisplay(context: Context): String {
        val sessions = loadSessions(context)
        if (sessions.isEmpty()) return "(尚无搜题记录)"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        // 倒序，最新的在最上面
        sessions.asReversed().forEachIndexed { sessionIdx, session ->
            val label = when (sessionIdx) {
                0 -> "本次启动"
                1 -> "上次启动"
                else -> "${sessionIdx + 1} 次前"
            }
            sb.append("━━━━━━━━━━ $label ━━━━━━━━━━\n")
            sb.append("启动时间: ${df.format(Date(session.startTime))}\n")
            if (session.records.isEmpty()) {
                sb.append("(本次启动尚未触发搜题)\n\n")
            } else {
                sb.append("搜题次数: ${session.records.size}\n\n")
                session.records.asReversed().forEachIndexed { recIdx, rec ->
                    sb.append("[#${session.records.size - recIdx}] ${tf.format(Date(rec.timestamp))}  耗时${rec.durationMs}ms\n")
                    if (rec.questionPreview.isNotBlank()) {
                        sb.append("  题干: ${rec.questionPreview.take(60)}${if (rec.questionPreview.length > 60) "…" else ""}\n")
                    }
                    rec.attempts.forEach { att ->
                        val mark = when (att.outcome) {
                            "success" -> "✓"
                            "failed" -> "✗"
                            "skipped" -> "○"
                            "insufficient" -> "△"
                            else -> "·"
                        }
                        sb.append("  $mark ${att.source.padEnd(28)} ${att.outcome}")
                        if (att.detail.isNotBlank()) sb.append(" — ${att.detail.take(60)}")
                        sb.append("\n")
                    }
                    // 诊断信息：[Extract] / [QuestionBank] miss_diagnosis 等
                    if (rec.diagnosis.isNotBlank()) {
                        rec.diagnosis.lines().forEach { line ->
                            if (line.isNotBlank()) sb.append("  📋 ").append(line.trim()).append("\n")
                        }
                    }
                    val ansLine = if (rec.finalAnswer.isBlank() || rec.finalAnswer == "无法判断") {
                        "  ⇒ 无法判断${if (rec.errorCode.isNotBlank()) "（${rec.errorCode}）" else ""}"
                    } else {
                        "  ⇒ 答案: ${rec.finalAnswer}"
                    }
                    sb.append(ansLine).append("\n\n")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    // ──────────────────────────────────────────
    // JSON 序列化
    // ──────────────────────────────────────────

    private fun saveSessions(context: Context, sessions: List<Session>) {
        val root = JSONArray()
        for (s in sessions) {
            val records = JSONArray()
            for (r in s.records) {
                val attempts = JSONArray()
                for (a in r.attempts) {
                    attempts.put(JSONObject().apply {
                        put("source", a.source)
                        put("outcome", a.outcome)
                        put("detail", a.detail)
                    })
                }
                records.put(JSONObject().apply {
                    put("timestamp", r.timestamp)
                    put("attempts", attempts)
                    put("finalAnswer", r.finalAnswer)
                    put("errorCode", r.errorCode)
                    put("questionPreview", r.questionPreview)
                    put("durationMs", r.durationMs)
                    put("diagnosis", r.diagnosis)
                })
            }
            root.put(JSONObject().apply {
                put("startTime", s.startTime)
                put("records", records)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSIONS, root.toString()).apply()
    }

    private fun parseJson(raw: String): List<Session> {
        val root = JSONArray(raw)
        val out = mutableListOf<Session>()
        for (i in 0 until root.length()) {
            val sObj = root.optJSONObject(i) ?: continue
            val records = mutableListOf<RecognitionRecord>()
            val recArr = sObj.optJSONArray("records")
            if (recArr != null) {
                for (j in 0 until recArr.length()) {
                    val rObj = recArr.optJSONObject(j) ?: continue
                    val attemptsArr = rObj.optJSONArray("attempts")
                    val attempts = mutableListOf<SourceAttempt>()
                    if (attemptsArr != null) {
                        for (k in 0 until attemptsArr.length()) {
                            val aObj = attemptsArr.optJSONObject(k) ?: continue
                            attempts.add(SourceAttempt(
                                source = aObj.optString("source"),
                                outcome = aObj.optString("outcome"),
                                detail = aObj.optString("detail")
                            ))
                        }
                    }
                    records.add(RecognitionRecord(
                        timestamp = rObj.optLong("timestamp"),
                        attempts = attempts,
                        finalAnswer = rObj.optString("finalAnswer"),
                        errorCode = rObj.optString("errorCode"),
                        questionPreview = rObj.optString("questionPreview"),
                        durationMs = rObj.optLong("durationMs"),
                        diagnosis = rObj.optString("diagnosis")
                    ))
                }
            }
            out.add(Session(
                startTime = sObj.optLong("startTime"),
                records = records
            ))
        }
        return out
    }

    private fun Long.ifZero(producer: () -> Long): Long = if (this == 0L) producer() else this
}
