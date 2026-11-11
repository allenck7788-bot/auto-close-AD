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
        private const val CHECK_INTERVAL_MS = 4000L // 嚴格每 4 秒定時跳出並執行

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
    private var currentToast: Toast? = null

    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            try {
                // 1. 保證第一順位：無條件強制彈出偵測提示！
                showToast("🔍 正在偵測廣告中...")

                // 2. 音量微震盪喚醒系統視窗管線
                triggerVolumeWakeup()

                // 3. 執行關閉動作
                processScreenAction()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 閉環排程：確保 4 秒後必定再次執行
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        showToast("AutoAdCloser 服務已連線！")

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
        // 系統事件照常監聽
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
                }, 50L)
            } else if (currentVol < maxVol) {
                am.setStreamVolume(streamType, currentVol + 1, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                mainHandler.postDelayed({
                    am.setStreamVolume(streamType, currentVol, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                }, 50L)
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

        if (roots.isEmpty()) {
            // 就算節點樹被廣告商遮蔽拿不到，也無條件盲點角落熱區
            triggerAllCornerHotspots()
            return
        }

        // 桌面與設定安全檢查
        for (r in roots) {
            if (isSystemSafeScreen(r)) {
                return
            }
        }

        // 1. 優先掃描節點
        var clicked = false
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                clicked = true
                break
            }
        }

        // 2. 無文字按鈕時執行多點熱區補刀
        if (!clicked) {
            triggerAllCornerHotspots()
        }
    }

    private fun isSystemSafeScreen(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains("AutoAdCloser", ignoreCase = true) || 
            desc.contains("AutoAdCloser", ignoreCase = true)) {
            return true
        }

        for (safe in SYSTEM_SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isSystemSafeScreen(child)) return true
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
                    showToast("⚡ 手勢點擊：$pattern")
                    return true
                }
            }
        }

        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showToast("⚡ 手勢點擊：關閉元件")
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
            clickAt(rect.centerX().toFloat(),
