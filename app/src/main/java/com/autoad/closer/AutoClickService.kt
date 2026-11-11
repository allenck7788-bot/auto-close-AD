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
        private const val CHECK_INTERVAL_MS = 4000L

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

    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                triggerVolumeWakeup()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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

        for (r in roots) {
            if (isSafeScreen(r)) return
        }

        // 專屬功能：完整快取中 -> 點擊左上角 ←
        for (r in roots) {
            if (checkAndHandleCachingScreen(r)) {
                return
            }
        }

        // 純精準比對：尋找關閉按鈕，絕不盲點
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                break
            }
        }
    }

    private fun checkAndHandleCachingScreen(root: AccessibilityNodeInfo): Boolean {
        if (!hasTextKeyword(root, "完整快取中")) {
            return false
        }

        showAlwaysToast("⚡ 偵測到「完整快取中」，點擊左上返回")

        if (findAndClickBackButton(root)) {
            return true
        }

        clickTopLeftBackButton()
        return true
    }

    private fun hasTextKeyword(node: AccessibilityNodeInfo, keyword: String): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasTextKeyword(child, keyword)) return true
        }
        return false
    }

    private fun findAndClickBackButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isBackKey = text == "←" || desc == "←" ||
                desc.contains("返回", ignoreCase = true) ||
                desc.contains("navigate up", ignoreCase = true) ||
                desc.contains("back", ignoreCase = true) ||
                viewId.contains("back") || viewId.contains("up")

        if (isBackKey) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.centerX() < 300 && rect.centerY() < 400) {
                if (performGestureClick(node)) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickBackButton(child)) return true
        }
        return false
    }

    private fun clickTopLeftBackButton() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height
        val backX = if (isLandscape) width * 0.05f else width * 0.075f
        val backY = if (isLandscape) height * 0.06f else height * 0.048f

        val path = Path().apply { moveTo(backX, backY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
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

        for (safe in SYSTEM_SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return true
            }
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
                    showAlwaysToast("⚡ 點擊關閉按鈕：$pattern")
                    return true
                }
            }
        }

        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 點擊關閉元件")
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
            source.equals(pattern, ignoreCase = true) || source.contains(pattern, ignoreCase = true)
        }
    }

    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() in 10..450 && rect.height() in 10..450) {
            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            return true
        }
        return false
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

    private fun showAlwaysToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
}
