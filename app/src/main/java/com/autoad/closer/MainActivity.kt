package com.autoad.closer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val btnAccessibility = Button(this).apply {
            text = "開啟 / 重啟無障礙服務"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(statusText)
        layout.addView(btnAccessibility)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isRunning = AutoClickService.instance != null
        if (isRunning) {
            statusText.text = "✅ 服務正常運作中\n(正在背景自動監控廣告)"
            statusText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            statusText.text = "⚠️ 服務尚未開啟\n請點擊下方按鈕前往開啟"
            statusText.setTextColor(Color.parseColor("#C62828"))
        }
    }
}
