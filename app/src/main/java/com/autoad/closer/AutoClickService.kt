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
        private const val CLICK_COOLDOWN_MS = 3000L // 嚴格限制：3 秒內最多只觸發一次點擊
    }

    private var lastClickTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動 (已設定 3 秒安全偵測)")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 時間節流檢查：若距離上次點擊未滿 3 秒，直接略過，不執行任何掃描
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN_MS) {
            return
        }

        try {
            val root = rootInActiveWindow ?: event.source ?: return
            scanAndClickNodes(root)
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    private fun scanAndClickNodes(node: AccessibilityNodeInfo) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN_MS) return

        val closeKeywords = listOf(
            "關閉", "跳過", "關閉廣告", "跳過廣告", 
            "Close", "close", "SKIP", "Skip", "skip", 
            "✕", "×", "✖", "X", "x"
        )

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        for (kw in closeKeywords) {
            if (text == kw || desc == kw || (kw.length > 1 && (text.contains(kw) || desc.contains(kw)))) {
                if (performClickOnNode(node)) {
                    lastClickTime = System.currentTimeMillis() // 鎖定時間戳記
                    showToast("⚡ 已自動點擊：$kw")
                    return
                }
            }
        }

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("close") || viewId.contains("btn_skip") || viewId.contains("dismiss")) {
            if (performClickOnNode(node)) {
                lastClickTime = System.currentTimeMillis() // 鎖定時間戳記
                showToast("⚡ 已點擊廣告關閉按鈕")
                return
            }
        }

        for (i in 0 until node.childCount) {
            try {
                if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return
                node.getChild(i)?.let { scanAndClickNodes(it) }
            } catch (_: Exception) {}
        }
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
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
