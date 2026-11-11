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
        private const val COOLDOWN_MS = 4000L // 4 秒安全冷卻

        // 系統與日常絕對安全清單
        private val SAFE_PACKAGES = listOf(
            "settings",
            "launcher",
            "systemui",
            "inputmethod",
            "closer"
        )
    }

    private var lastActionTime = 0L

    // 關閉與快進特徵清單
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動 (已開啟每 4 秒偵測狀態提示)")
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

        // 1. 安全防護：系統設定或日常介面不動作
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        for (safe in SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return
            }
        }

        try {
            val root = rootInActiveWindow ?: event.source ?: return

            // 2. 自家 App 介面熔斷保護
            if (isSafeScreen(root)) {
                return
            }

            // 3. 執行輪詢心跳提示（讓你知道程式正在運作中）
            lastActionTime = currentTime
            showToast("🔍 偵測廣告中...")

            // 4. 優先嘗試比對文字/符號節點
            val clicked = scanAndClickNodes(root)

            // 5. 若為純貼圖/無文字按鈕，但畫面包含廣告特徵（如 Learn More、安裝等），發動雙角落手勢
            if (!clicked && isAdScreenWithActionBtn(root)) {
                triggerCornerAdHotspots()
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

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
     * 檢查是否帶有廣告標籤特徵（加入 Learn More、搶購、PChome 等商業廣告特徵）
     */
    private fun isAdScreenWithActionBtn(node: AccessibilityNodeInfo): Boolean {
        val adKeywords = listOf(
            "learn more", "install", "download", "play",
            "安裝", "开始游戏", "開始遊戲", "立即購買", "立即下载", "搶購", "優惠"
        )
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (kw in adKeywords) {
            if (text.contains(kw) || desc.contains(kw)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isAdScreenWithActionBtn(child)) return true
        }
        return false
    }

    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 符號與關鍵字比對
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showToast("⚡ 已點擊：$pattern")
                    return true
                }
            }
        }

        // 2. View ID 比對
        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showToast("⚡ 已點擊廣告按鈕元件")
                return true
            }
        }

        // 3. 遞迴子元件
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

        // 針對按鈕區域做點擊
        if (rect.width() in 10..380 && rect.height() in 10..380) {
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    /**
     * 雙角落（右上 + 左上）熱區點擊（針對照片中圓形灰色叉叉位置精準補刀）
     */
    private fun triggerCornerAdHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        // 根據直橫向微調，針對照片中的右上角叉叉比例（X 約 89.5%, Y 約 9.2%）
        val rightX = if (isLandscape) width * 0.935f else width * 0.895f
        val leftX = if (isLandscape) width * 0.065f else width * 0.095f
        val topY = if (isLandscape) height * 0.085f else height * 0.092f

        // 1. 優先點擊右上角
        clickAt(rightX, topY)

        // 2. 延遲 300ms 點擊左上角，兼顧左右隨機按鈕
        Handler(Looper.getMainLooper()).postDelayed({
            clickAt(leftX, topY)
        }, 300L)

        showToast("⚡ 已執行角落按鈕點擊")
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
