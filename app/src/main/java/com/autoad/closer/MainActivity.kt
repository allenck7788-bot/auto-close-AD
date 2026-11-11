package com.autoad.closer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var switchDebugMode: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 最外層 RelativeLayout，方便將版本時間固定在右下角
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(48, 48, 48, 48)
        }

        // 中央垂直排列的主內容區
        val centerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val centerParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            layoutParams = centerParams
        }

        // 1. 服務狀態文字
        tvStatus = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(12f, 1f)
            setTextColor(Color.parseColor("#E53935"))
            text = "○ 服務狀態：未啟動\n(請點擊下方按鈕前往開啟)"
        }
        centerLayout.addView(tvStatus)

        // 2. 前往無障礙設定按鈕
        val btnOpenAccessibility = Button(this).apply {
            text = "前往無障礙設定頁面"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                topMargin = 64
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        centerLayout.addView(btnOpenAccessibility)

        // 3. 除錯模式開關列（取代原有的測試按鈕）
        val debugBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
        }

        val tvDebugLabel = TextView(this).apply {
            text = "開啟除錯標記模式\n(在偵測到的圖示下方顯示「關閉！」)"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)
        switchDebugMode = Switch(this).apply {
            isChecked = sp.getBoolean("debug_mode", false)
            setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("debug_mode", isChecked).apply()
            }
        }

        debugBar.addView(tvDebugLabel)
        debugBar.addView(switchDebugMode)
        centerLayout.addView(debugBar)

        // 4. 說明文字
        val tvDesc = TextView(this).apply {
            text = "說明：\n開啟除錯模式後，若精準識別到關閉按鈕，會在該按鈕正下方顯示「關閉！」字樣，方便截圖排查。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setLineSpacing(8f, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
        }
        centerLayout.addView(tvDesc)

        // 將中央區加入最外層
        rootLayout.addView(centerLayout)

        // 5. 右下角：動態程式修改時間與自動遞增版本號
        val buildInfoText = try {
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})"
        } catch (_: Exception) {
            "v1.0.0"
        }

        val tvBuildInfo = TextView(this).apply {
            text = buildInfoText
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_END)
            }
        }
        rootLayout.addView(tvBuildInfo)

        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isRunning = AutoClickService.instance != null
        if (isRunning) {
            tvStatus.text = "● 服務狀態：運行中\n(正在背景自動監控)"
            tvStatus.setTextColor(Color.parseColor("#43A047"))
        } else {
            tvStatus.text = "○ 服務狀態：未啟動\n(請點擊下方按鈕前往開啟)"
            tvStatus.setTextColor(Color.parseColor("#E53935"))
        }
    }
}
