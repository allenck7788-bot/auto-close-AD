package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        private const val COOLDOWN_MS = 3500L // 安全冷卻 3.5 秒
    }

    private var lastActionTime = 0L

    // 特殊跳過符號與關鍵字清單（包含圖 02 的 >> 符號）
    private val CLOSE_PATTERNS = listOf(
        ">>", ">>", "»", ">",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip",
        "✕", "×", "✖"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) {
            return
        }

        try {
            val root = rootInActiveWindow ?: event.source ?: return

            // 1. 最高防護：只要當前畫面有 "AutoAdCloser"，代表在設定頁或自家頁面，一律強制退出不動作！
            if (isSafeScreen(root)) {
                return
            }

            // 2. 僅針對具備關閉/跳過特徵的精準節點發動點擊（不盲點，防跳轉）
            scanAndClickNodes(root)
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    /**
     * 檢查當前畫面是否為設定頁面或安全白名單
     */
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
     * 掃描並精準點擊關閉按鈕
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) return true

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 比對關鍵字（含 >> 符號）
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performSafeClick(node)) {
                    lastActionTime = System.currentTimeMillis()
                    showToast("⚡ 已自動點擊：$pattern")
                    return true
                }
            }
        }

        // 2. 比對廣告 SDK 的 ID 特徵
        if (viewId.contains("btn_close") || viewId.contains("close_btn") || 
            viewId.contains("btn_skip") || viewId.contains("skip_btn") ||
            viewId.contains("iv_close") || viewId.contains("ad_close")) {
            if (performSafeClick(node)) {
                lastActionTime = System.currentTimeMillis()
                showToast("⚡ 已點擊廣告按鈕")
                return true
            }
        }

        // 3. 遞迴子節點
        for (i in 0 until node.childCount) {
            try {
                if (System.currentTimeMillis() - lastActionTime < COOLDOWN_MS) return true
                val child = node.getChild(i) ?: continue
                if (scanAndClickNodes(child)) return true
            } catch (_: Exception) {}
        }

        return false
    }

    private fun isMatchingPattern(source: String, pattern: String): Boolean {
        if (source.isEmpty()) return false
        // 對於 >> 等符號，進行精確或包含判定
        return if (pattern == ">>" || pattern == ">" || pattern == "»") {
            source.contains(pattern)
        } else {
            source == pattern || (pattern.length > 1 && source.contains(pattern))
        }
    }

    /**
     * 安全點擊節點
     */
    private fun performSafeClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // 若不可點擊，取得按鈕的實體座標模擬點擊其中心
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() in 20..400 && rect.height() in 20..400) { // 限制在一般按鈕大小，避免點擊到全螢幕廣告圖層
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
