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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var switchDebugMode: Switch
    private lateinit var rgDelay: RadioGroup
    private lateinit var rb8s: RadioButton
    private lateinit var rb15s: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)

        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(48, 48, 48, 48)
        }

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

        // 1. 服務狀態
        tvStatus = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(12f, 1f)
            setTextColor(Color.parseColor("#E53935"))
            text = "○ 服務狀態：未啟動\n(請點擊下方按鈕前往開啟)"
        }
        centerLayout.addView(tvStatus)

        // 2. 前往設定按鈕
        val btnOpenAccessibility = Button(this).apply {
            text = "前往無障礙設定頁面"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                topMargin = 48
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        centerLayout.addView(btnOpenAccessibility)

        // 3. 等待秒數選擇設定 (預設 8 秒，可選 15 秒)
        val delaySettingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(32, 20, 32, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
        }

        val tvDelayLabel = TextView(this).apply {
            text = "關閉前等待秒數："
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#333333"))
        }
        delaySettingLayout.addView(tvDelayLabel)

        rgDelay = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
        }

        val currentDelay = sp.getInt("click_delay_sec", 8)

        rb8s = RadioButton(this).apply {
            text = "等待 8 秒 (預設)"
            textSize = 14f
            id = 8
            isChecked = (currentDelay == 8)
        }

        rb15s = RadioButton(this).apply {
            text = "等待 15 秒"
            textSize = 14f
            id = 15
            isChecked = (currentDelay == 15)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 36
            }
        }

        rgDelay.addView(rb8s)
        rgDelay.addView(rb15s)
        rgDelay.setOnCheckedChangeListener { _, checkedId ->
            val sec = if (checkedId == 15) 15 else 8
            sp.edit().putInt("click_delay_sec", sec).apply()
        }
        delaySettingLayout.addView(rgDelay)
        centerLayout.addView(delaySettingLayout)

        // 4. 除錯模式開關
        val debugBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(32, 20, 32, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        }

        val tvDebugLabel = TextView(this).apply {
            text = "開啟除錯標記模式\n(在偵測到的圖示下方顯示「關閉！」)"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        switchDebugMode = Switch(this).apply {
            isChecked = sp.getBoolean("debug_mode", false)
            setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("debug_mode", isChecked).apply()
            }
        }

        debugBar.addView(tvDebugLabel)
        debugBar.addView(switchDebugMode)
        centerLayout.addView(debugBar)

        // 5. 說明文字
        val tvDesc = TextView(this).apply {
            text = "說明：\n偵測到關閉按鈕時，將依所選秒數倒數等待，並在按鈕正下方顯示警示，防太早點擊跳轉。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setLineSpacing(8f, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
        }
        centerLayout.addView(tvDesc)

        rootLayout.addView(centerLayout)

        // 6. 右下角：版本與編譯時間
        val buildInfoText = try {
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})"
        } catch (_: Exception) {
            "v1.0"
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
