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
        private const val COOLDOWN_MS = 3000L // 3 秒冷卻防抖

        // 系統與日常絕對安全清單：在這些畫面中一律不進行任何動作
        private val SAFE_PACKAGES = listOf(
            "settings",
            "launcher",
            "systemui",
            "inputmethod",
            "closer"
        )
    }

    private var lastActionTime = 0L

    // 關閉與快進特徵清單（含 >>, ⏯, ✕, 關閉等）
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showToast("AutoAdCloser 已啟動 (已支援左上+右上雙邊隨機圖示)")
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

        // 1. 安全防護：檢查當前 App 是否在系統設定或桌面清單內
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        for (safe in SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return
            }
        }

        try {
            val root = rootInActiveWindow ?: event.source ?: return

            // 2. 自身頁面熔斷保護
            if (isSafeScreen(root)) {
                return
            }

            // 3. 優先尋找帶有符號/文字的精準節點（並以「實體座標手勢」點擊）
            val clicked = scanAndClickNodes(root)

            // 4. 若為無文字廣告（純 Canvas 貼圖的 ⏯ 或 ✕），且畫面帶有廣告下載特徵，依序發動左上與右上熱區手勢
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
     * 檢查畫面是否處於廣告結算/試玩中（例如出現「安裝」、「開始遊戲」、「立即購買」等按鈕）
     */
    private fun isAdScreenWithActionBtn(node: AccessibilityNodeInfo): Boolean {
        val adBtnKeywords = listOf("安裝", "开始游戏", "開始遊戲", "立即購買", "立即下载", "Download", "Install")
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (kw in adBtnKeywords) {
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

    /**
     * 掃描可見節點並強制以「實體螢幕座標手勢」點擊按鈕中心
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) return true

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 符號與關鍵字比對
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    lastActionTime = System.currentTimeMillis()
                    showToast("⚡ 手勢點擊：$pattern")
                    return true
                }
            }
        }

        // 2. View ID 比對
        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                lastActionTime = System.currentTimeMillis()
                showToast("⚡ 手勢點擊：廣告按鈕")
                return true
            }
        }

        // 3. 遞迴子元件
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
        return if (pattern.length == 1) {
            source.contains(pattern)
        } else {
            source == pattern || source.contains(pattern)
        }
    }

    /**
     * 計算實體位置派發觸控手勢（GestureDescription），防止廣告 SDK 阻擋軟體點擊
     */
    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 過濾掉全螢幕的大容器，只點擊按鈕尺寸（長寬小於 350px 且大於 10px）
        if (rect.width() in 10..350 && rect.height() in 10..350) {
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    /**
     * 支援直向與橫向：雙角落（左上 + 右上）實體熱區手勢點擊
     */
    private fun triggerCornerAdHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        // 根據直橫向計算左上角與右上角座標比例
        val leftX = if (isLandscape) width * 0.065f else width * 0.085f
        val rightX = if (isLandscape) width * 0.935f else width * 0.915f
        val topY = if (isLandscape) height * 0.085f else height * 0.088f

        // 1. 先點擊左上角熱區
        clickAt(leftX, topY)

        // 2. 延遲 350ms 接著點擊右上角熱區，兼顧隨機出現在兩側的按鈕
        Handler(Looper.getMainLooper()).postDelayed({
            clickAt(rightX, topY)
        }, 350L)

        lastActionTime = System.currentTimeMillis()
        showToast("⚡ 已點擊雙邊角落按鈕熱區")
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
