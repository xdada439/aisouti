package com.lk.studyassistant.quantum.local

import android.content.Context
import com.lk.studyassistant.quantum.util.AppLogger

class DebugTraceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_search_debug_trace", Context.MODE_PRIVATE)

    fun save(debugText: String, cropPath: String?) {
        prefs.edit()
            .putString(KEY_TEXT, debugText)
            .putString(KEY_CROP_PATH, cropPath.orEmpty())
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        writeToLogcat(debugText, cropPath)
    }

    fun getDebugText(): String = prefs.getString(KEY_TEXT, "暂无调试记录").orEmpty()

    fun getCropPath(): String = prefs.getString(KEY_CROP_PATH, "").orEmpty()

    fun getTimeMillis(): Long = prefs.getLong(KEY_TIME, 0L)

    private fun writeToLogcat(debugText: String, cropPath: String?) {
        AppLogger.log("===== Local search debug trace saved =====")
        AppLogger.log("crop preview: ${cropPath.orEmpty()}")
        debugText.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            AppLogger.log("debug trace chunk ${index + 1}:\n$chunk")
        }
    }

    companion object {
        private const val KEY_TEXT = "text"
        private const val KEY_CROP_PATH = "crop_path"
        private const val KEY_TIME = "time"
        private const val LOG_CHUNK_SIZE = 3500
    }
}
