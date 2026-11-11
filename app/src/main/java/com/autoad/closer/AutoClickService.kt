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
        private const val COOLDOWN_MS = 4000L // 4 秒強制冷卻

        // 系統與日常絕對安全清單：在這些畫面中一律不進行任何動作
        private val SAFE_PACKAGES = listOf(
            "settings",          // 系統設定（避免點擊到無障礙開關）
            "launcher",          // 各廠牌桌面
            "systemui",         // 系統狀態列與導航
            "inputmethod",       // 鍵盤輸入法
            "closer"            // 本 App 自身頁面
        )
    }

    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動 (已加入系統設定安全防護)")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. 冷卻時間未滿 4 秒直接忽略
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) {
            return
        }

        // 2. 安全防護：檢查當前 App 是否在安全清單內（如截圖中的無障礙設定）
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        for (safe in SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return // 直接返回，絕不在設定頁面發動點擊
            }
        }

        try {
            val root = rootInActiveWindow ?: event.source ?: return
            scanAndClickNodes(root)
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) return true

        // 僅保留精確的關閉特徵，剔除容易誤點的單英文字母 "x" / "X"
        val exactKeywords = listOf(
            "關閉", "跳過", "關閉廣告", "跳過廣告", 
            "Close", "close", "SKIP", "Skip", "skip", 
            "✕", "×", "✖"
        )

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 比對精準文字或 ContentDescription
        for (kw in exactKeywords) {
            if (text == kw || desc == kw || text.contains(kw) || desc.contains(kw)) {
                if (performClickOnNode(node)) {
                    lastActionTime = System.currentTimeMillis()
                    showToast("⚡ 自動點擊廣告關閉：$kw")
                    return true
                }
            }
        }

        // 2. 比對廣告專用 View ID 特徵
        if (viewId.contains("btn_close") || viewId.contains("close_button") || 
            viewId.contains("btn_skip") || viewId.contains("ad_close")) {
            if (performClickOnNode(node)) {
                lastActionTime = System.currentTimeMillis()
                showToast("⚡ 已點擊廣告關閉按鈕")
                return true
            }
        }

        // 3. 遞迴掃描子元件
        for (i in 0 until node.childCount) {
            try {
                if (System.currentTimeMillis() - lastActionTime < COOLDOWN_MS) return true
                val child = node.getChild(i) ?: continue
                if (scanAndClickNodes(child)) return true
            } catch (_: Exception) {}
        }

        return false
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
