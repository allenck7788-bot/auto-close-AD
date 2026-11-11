package com.autoad.closer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var switchDebugMode: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        switchDebugMode = findViewById(R.id.switchDebugMode)

        // 讀取除錯模式設定
        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)
        val isDebug = sp.getBoolean("debug_mode", false)
        switchDebugMode.isChecked = isDebug

        switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean("debug_mode", isChecked).apply()
        }

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isRunning = AutoClickService.instance != null
        if (isRunning) {
            tvStatus.text = "● 服務狀態：運行中\n(正在背景自動監控)"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#43A047"))
        } else {
            tvStatus.text = "○ 服務狀態：未啟動\n(請點擊下方按鈕前往開啟)"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#E53935"))
        }
    }
}
