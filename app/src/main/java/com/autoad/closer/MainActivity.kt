package com.autoad.closer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 80, 60, 80)
        }

        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }

        // 1. 手動前往無障礙設定按鈕（絕不自動跳轉）
        val btnAccessibility = Button(this).apply {
            text = "前往無障礙設定頁面"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // 2. 測試按鈕：讓無障礙服務點擊它，驗證功能是否活著
        val btnTest = Button(this).apply {
            text = "測試按鈕 (包含「關閉」關鍵字)"
            textSize = 16f
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "🎉 成功！無障礙服務有在運作並點擊了此按鈕！", Toast.LENGTH_LONG).show()
            }
        }

        val hintText = TextView(this).apply {
            text = "\n說明：\n若服務運作正常，當畫面上出現含有「關閉」文字的按鈕時，服務會在背景自動替你按下。"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }

        layout.addView(statusText)
        layout.addView(btnAccessibility)
        layout.addView(btnTest)
        layout.addView(hintText)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // 僅刷新文字顯示，絕不在此處執行 startActivity 跳轉
        refreshStatusDisplay()
    }

    private fun refreshStatusDisplay() {
        if (AutoClickService.instance != null) {
            statusText.text = "● 服務狀態：運作中\n(背景監控中)"
            statusText.setTextColor(Color.parseColor("#2E7D32")) // 綠色
        } else {
            statusText.text = "○ 服務狀態：未啟動\n(請點擊下方按鈕前往開啟)"
            statusText.setTextColor(Color.parseColor("#D32F2F")) // 紅色
        }
    }
}
