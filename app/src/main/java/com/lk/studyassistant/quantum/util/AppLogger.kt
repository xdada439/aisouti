package com.lk.studyassistant.quantum.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 统一日志系统。
 * adb logcat -s AI_SOUTI
 *
 * 内存环形缓冲供「用户中心 → 识别日志」展示。**容量必须大于一次完整搜题的日志量**，
 * 否则 [com.lk.studyassistant.quantum.service.FloatingWindowService] 在搜题结束时
 * 回读日志聚合 RecognitionRecord，会读不到本次搜题前半段（OCR 阶段）的记录。
 *
 * 实测一次最长链路（截屏 → OCR+题库 → Vision+题库+资料+兜底 → 无障碍+题库）
 * 约 130~160 行，其中题库检索单次就有 18~40 行（五层召回 + top5 候选 + 逐字母 remap）。
 * 原来的 100 行会被一次搜题直接冲掉，[MAX_LOG_SIZE] 因此提到 600（约 3~4 次搜题的历史）。
 */
object AppLogger {

    const val TAG = "AI_SOUTI"

    /** 约 3~4 次完整搜题的量。600 × ~150 字符 ≈ 90KB，内存代价可以忽略。 */
    private const val MAX_LOG_SIZE = 600

    // 用 ArrayDeque + synchronized 而不是 CopyOnWriteArrayList：
    // 后者每次 removeAt(0) 都要全量复制数组，缓冲一满就是每条日志复制 600 个元素。
    private val logs = ArrayDeque<String>(MAX_LOG_SIZE)
    private val lock = Any()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun append(entry: String) {
        synchronized(lock) {
            logs.addLast(entry)
            while (logs.size > MAX_LOG_SIZE) logs.removeFirst()
        }
    }

    private fun stamp(): String = synchronized(sdf) { sdf.format(Date()) }

    fun log(message: String) {
        Log.i(TAG, message)
        append("[${stamp()}] $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        append("[${stamp()}] ERROR $message")
    }

    fun getLogs(): List<String> = synchronized(lock) { logs.toList() }

    fun clearLogs() {
        synchronized(lock) { logs.clear() }
        log("[Logger] logs_cleared")
    }

    fun getLogsAsString(): String = getLogs().joinToString("\n")
}
