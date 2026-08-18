package com.lk.studyassistant.quantum

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lk.studyassistant.quantum.service.FloatingWindowService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugPanelActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val copy = Button(this).apply {
            text = "复制详情"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI搜题测试详情", FloatingWindowService.latestTestDetail.toDisplayText()))
                Toast.makeText(this@DebugPanelActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = TextView(this).apply {
            textSize = 18f
            text = "测试详情\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"
            setPadding(0, 18, 0, 18)
        }
        root.addView(title)

        val detail = FloatingWindowService.latestTestDetail
        if (detail.fallbackUsed) {
            val warning = TextView(this).apply {
                text = "⚠ 兜底模式答案可能不准确，仅作为参考。"
                textSize = 14f
                setTextColor(0xFFFF6600.toInt())
                setPadding(0, 0, 0, 12)
            }
            root.addView(warning)
        }

        val body = TextView(this).apply {
            textSize = 12f
            text = detail.toDisplayText()
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(scroll)
    }
}
