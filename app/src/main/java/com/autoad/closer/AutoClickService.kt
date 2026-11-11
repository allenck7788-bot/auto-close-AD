package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
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
        private const val COOLDOWN_MS = 4000L // 維持強制 4 秒冷卻防卡頓
    }

    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動 (支援直/橫向 左上+右上 雙角落關閉)")
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
            
            // 1. 優先掃描帶有文字或 ID 的關閉節點
            val handled = scanAndClickNodes(root)
            
            // 2. 若全為圖片/畫布渲染（無文字節點），發動直橫向「右上 + 左上」雙角落熱區點擊
            if (!handled) {
                checkAndClickAdCornerHotspots()
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) return true

        val closeKeywords = listOf(
            "關閉", "跳過", "關閉廣告", "跳過廣告", 
            "Close", "close", "SKIP", "Skip", "skip", 
            "✕", "×", "✖", "X", "x"
        )

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        for (kw in closeKeywords) {
            if (text == kw || desc == kw || (kw.length > 1 && (text.contains(kw) || desc.contains(kw)))) {
                if (performClickOnNode(node)) {
                    lastActionTime = System.currentTimeMillis()
                    showToast("⚡ 自動點擊：$kw")
                    return true
                }
            }
        }

        if (viewId.contains("close") || viewId.contains("btn_skip") || viewId.contains("dismiss") || viewId.contains("cancel")) {
            if (performClickOnNode(node)) {
                lastActionTime = System.currentTimeMillis()
                showToast("⚡ 自動點擊廣告關閉元件")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            try {
                if (System.currentTimeMillis() - lastActionTime < COOLDOWN_MS) return true
                val child = node.getChild(i) ?: continue
                if (scanAndClickNodes(child)) return true
            } catch (_: Exception) {}
        }

        return false
    }

    /**
     * 支援直立與橫向螢幕：自動取得即時邊界，並輪流點擊右上與左上熱區
     */
    private fun checkAndClickAdCornerHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        // 依據螢幕直橫向動態微調點擊邊距比例
        val isLandscape = width > height

        val rightX = if (isLandscape) width * 0.935f else width * 0.915f
        val leftX = if (isLandscape) width * 0.065f else width * 0.085f
        val topY = if (isLandscape) height * 0.085f else height * 0.055f

        // 1. 點擊右上角
        clickAt(rightX, topY)

        // 2. 延遲 300ms 補充點擊左上角（防止按鈕隨機擺放在左側）
        Handler(Looper.getMainLooper()).postDelayed({
            clickAt(leftX, topY)
        }, 300L)

        lastActionTime = System.currentTimeMillis()
        showToast("⚡ 已點擊雙邊角落關閉熱區")
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

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
