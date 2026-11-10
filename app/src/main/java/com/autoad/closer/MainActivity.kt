package com.autoad.closer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val btnAccessibility = Button(this).apply {
            text = "1. 開啟/重啟無障礙服務"
            textSize = 15f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnOverlay = Button(this).apply {
            text = "2. 授予懸浮視窗權限"
            textSize = 15f
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        layout.addView(statusText)
        layout.addView(btnAccessibility)
        layout.addView(btnOverlay)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val isRunning = AutoClickService.instance != null

        if (isRunning) {
            val overlayMsg = if (hasOverlay) " (已開啟懸浮狀態球)" else " (懸浮窗未授權，無小球提示)"
            statusText.text = "✅ AutoAdCloser 服務運作中$overlayMsg"
            statusText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            statusText.text = "⚠️ AutoAdCloser 服務尚未啟動\n請點擊按鈕 1 前往開啟"
            statusText.setTextColor(Color.parseColor("#C62828"))
        }
    }
}
