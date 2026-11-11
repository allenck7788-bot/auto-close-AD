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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        private const val CHECK_INTERVAL_MS = 4000L // 每 4 秒巡檢一次

        // 系統安全清單
        private val SYSTEM_SAFE_PACKAGES = listOf(
            "settings",
            "launcher",
            "systemui",
            "inputmethod",
            "closer"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var audioManager: AudioManager? = null

    // 關閉與快進特徵清單
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // 每 4 秒固定提示正在運作
            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                // 音量微震盪喚醒底層視窗管線
                triggerVolumeWakeup()

                // 執行精準比對與關閉
                processScreenAction()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        showAlwaysToast("AutoAdCloser 服務已連線！")

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
        // 系統事件連帶巡檢
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

        if (roots.isEmpty()) return

        // 避免在設定頁面誤觸
        for (r in roots) {
            if (isSafeScreen(r)) return
        }

        // 唯一動作：精準比對。只有抓到符合的按鈕節點才會點擊！
        // 找不到按鈕就什麼都不做，絕不盲點！
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                break
            }
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

    /**
     * 嚴格掃描：只有節點文字、標籤或 ID 含有關閉/跳過特徵時才觸發
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 文字或無障礙標籤符合關閉/快進符號
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showAlwaysToast("⚡ 點擊關閉按鈕：$pattern")
                    return true
                }
            }
        }

        // 2. 元件 ID 包含 close / skip / dismiss
        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 點擊關閉元件")
                return true
            }
        }

        // 3. 遞迴子節點
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
            source.equals(pattern, ignoreCase = true) || source.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * 取得該按鈕的實體座標模擬點擊中心
     * 限制寬高在 10px ~ 450px 之間，避免按到全螢幕大背景
     */
    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() in 10..450 && rect.height() in 10..450) {
            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            return true
        }
        return false
    }

    private fun showAlwaysToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
}
