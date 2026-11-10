package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 僅在視窗狀態改變或內容更新時觸發，完全由系統驅動，不耗電且不崩潰
        if (event == null) return

        try {
            val root = rootInActiveWindow ?: event.source ?: return
            scanAndClickNodes(root)
        } catch (_: Exception) {
            // 安全防護：忽略任何節點讀取過程中的例外
        }
    }

    override fun onInterrupt() {}

    /**
     * 遞迴掃描畫面中所有帶有廣告關閉特徵的按鈕與文字
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo) {
        val closeKeywords = listOf(
            "關閉", "跳過", "關閉廣告", "跳過廣告", 
            "Close", "close", "SKIP", "Skip", "skip", 
            "✕", "×", "✖", "X", "x"
        )

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        // 1. 比對文字或內容說明
        for (kw in closeKeywords) {
            if (text == kw || desc == kw || text.contains(kw) || desc.contains(kw)) {
                if (performClickOnNode(node)) return
            }
        }

        // 2. 比對常見廣告 SDK 的關閉按鈕 ID 特徵
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("close") || viewId.contains("btn_skip") || viewId.contains("dismiss")) {
            if (performClickOnNode(node)) return
        }

        // 3. 遞迴搜尋所有子節點
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { scanAndClickNodes(it) }
            } catch (_: Exception) {}
        }
    }

    /**
     * 執行安全點擊：若節點可點擊則直接觸發，若不可點擊則用手勢模擬點擊中心
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // 節點本身不可點擊時，模擬螢幕點擊該區域中心
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
}
