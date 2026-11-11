package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        private const val CHECK_INTERVAL_MS = 4000L // 嚴格固定每 4 秒執行一次
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var audioManager: AudioManager? = null

    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // 1. 無論任何狀態、任何畫面，第一順位絕對先跳提示證明定時器在線！
            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                // 2. 音量極速震盪喚醒底層視窗管線
                triggerVolumeWakeup()

                // 3. 執行關閉流程
                processScreenAction()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 保證 4 秒後閉環排程
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        showAlwaysToast("AutoAdCloser 服務已連線！(每 4 秒定時巡檢)")

        mainHandler.removeCallbacks(inspectionRunnable)
        mainHandler.postDelayed(inspectionRunnable, 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        mainHandler.removeCallbacks(inspectionRunnable)
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 系統產生畫面異動時連帶觸發
    }

    override fun onInterrupt() {}

    private fun triggerVolumeWakeup() {
        val am = audioManager ?: return
        try {
            val streamType = AudioManager.STREAM_MUSIC
            val currentVol = am.getStreamVolume(streamType)
            val maxVol = am.getStreamMaxVolume(streamType)

            if (currentVol > 0) {
                am.setStreamVolume(streamType, currentVol - 1, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                mainHandler.postDelayed({
                    am.setStreamVolume(streamType, currentVol, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                }, 40L)
            } else if (currentVol < maxVol) {
                am.setStreamVolume(streamType, currentVol + 1, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                mainHandler.postDelayed({
                    am.setStreamVolume(streamType, currentVol, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                }, 40L)
            }
        } catch (_: Exception) {}
    }

    private fun processScreenAction() {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                windows?.forEach { win ->
                    win.root?.let { roots.add(it) }
                }
            }
        } catch (_: Exception) {}

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }

        // 避免在手機設定頁面關閉自身服務
        for (r in roots) {
            if (isSafeScreen(r)) return
        }

        // 1. 優先掃描節點文字與元件 ID
        var clicked = false
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                clicked = true
                break
            }
        }

        // 2. 若為純貼圖/無文字按鈕，直接無條件發動各類角落熱區點擊（含 Unity Ads 卡片叉叉）
        if (!clicked) {
            triggerAllCornerHotspots()
        }
    }

    private fun isSafeScreen(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val pkg = node.packageName?.toString()?.lowercase() ?: ""

        if (text.contains("AutoAdCloser", ignoreCase = true) || 
            desc.contains("AutoAdCloser", ignoreCase = true) ||
            pkg.contains("settings")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isSafeScreen(child)) return true
        }
        return false
    }

    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showAlwaysToast("⚡ 手勢點擊：$pattern")
                    return true
                }
            }
        }

        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 手勢點擊：關閉元件")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scanAndClickNodes(child)) return true
        }
        return false
    }

    private fun isMatchingPattern(source: String, pattern: String): Boolean {
        if (source.isEmpty()) return false
        return if (pattern.length == 1) {
            source.contains(pattern)
        } else {
            source == pattern || source.contains(pattern)
        }
    }

    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() in 10..450 && rect.height() in 10..450) {
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    /**
     * 支援直向與橫向：梯次打擊四種最核心的關閉熱區
     */
    private fun triggerAllCornerHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        // 1. 截圖中的 Unity Ads 圓角卡片右上角叉叉 (X: 約 89.2%, Y: 約 9.0%)
        val unityCardRightX = if (isLandscape) width * 0.920f else width * 0.892f
        val unityCardTopY   = if (isLandscape) height * 0.085f else height * 0.090f

        // 2. 全螢幕極限右上角叉叉
        val extremeRightX   = if (isLandscape) width * 0.945f else width * 0.938f
        val extremeTopY     = if (isLandscape) height * 0.080f else height * 0.068f

        // 3. 試玩廣告金幣旁內縮右上角
        val innerRightX     = if (isLandscape) width * 0.900f else width * 0.835f

        // 4. 左上角常規熱區
        val extremeLeftX    = if (isLandscape) width * 0.055f else width * 0.085f

        // 依序連續發動點擊補刀：Unity 卡片叉 -> 極限右上 -> 內縮右上 -> 左上
        clickAt(unityCardRightX, unityCardTopY)
        mainHandler.postDelayed({ clickAt(extremeRightX, extremeTopY) }, 150L)
        mainHandler.postDelayed({ clickAt(innerRightX, extremeTopY) }, 300L)
        mainHandler.postDelayed({ clickAt(extremeLeftX, unityCardTopY) }, 450L)
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * 強制每次建立新 Toast 實體，徹底繞過 Android 11+ 系統頻率防抖限制
     */
    private fun showAlwaysToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
}
