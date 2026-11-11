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
import kotlinx.coroutines.*

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        private const val COOLDOWN_MS = 3000L // 3 秒冷卻
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isServiceRunning = false
    private var lastActionTime = 0L

    // 特徵清單（含快進、關閉、X 符號）
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        showToast("AutoAdCloser 已啟動 (已開啟多視窗主動巡檢)")
        
        // 啟動主動輪詢循環（解決畫面靜止或分層彈窗不拋出事件的問題）
        startActiveInspectionLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件驅動觸發巡檢
        inspectAndProcessScreen()
    }

    override fun onInterrupt() {}

    /**
     * 主動式每 3.5 秒定時巡檢，防止彈窗靜態卡死
     */
    private fun startActiveInspectionLoop() {
        serviceScope.launch {
            while (isServiceRunning) {
                delay(3500L)
                inspectAndProcessScreen()
            }
        }
    }

    /**
     * 核心多視窗分析流程
     */
    private fun inspectAndProcessScreen() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < COOLDOWN_MS) return

        // 1. 取得目前螢幕上所有可互動的視窗（突破彈窗/分層盲點）
        val allRoots = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // 嘗試取得所有層級視窗（包含 Google Play 覆蓋層）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                windows?.forEach { win ->
                    win.root?.let { allRoots.add(it) }
                }
            }
        } catch (_: Exception) {}

        // 若無多視窗權限，回退使用根視窗
        if (allRoots.isEmpty()) {
            rootInActiveWindow?.let { allRoots.add(it) }
        }

        if (allRoots.isEmpty()) return

        // 2. 自身設定頁安全防護
        for (root in allRoots) {
            if (isSafeScreen(root)) return
        }

        // 3. 逐層掃描所有視窗的文字與符號節點
        for (root in allRoots) {
            if (scanAndClickNodes(root)) {
                lastActionTime = System.currentTimeMillis()
                return
            }
        }

        // 4. 若無文字節點，但畫面存在廣告特徵（如 Unity Ads、Play Now、安裝等），發動彈窗雙熱區盲點
        for (root in allRoots) {
            if (isAdScreenWithActionBtn(root)) {
                lastActionTime = System.currentTimeMillis()
                triggerCardCornerHotspots()
                return
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
     * 檢查是否帶有廣告標籤特徵（加入 Unity Ads, Play Now, 安裝等）
     */
    private fun isAdScreenWithActionBtn(node: AccessibilityNodeInfo): Boolean {
        val adKeywords = listOf(
            "play now", "learn more", "install", "download", "unity ads",
            "安裝", "开始游戏", "開始遊戲", "立即購買", "立即下载", "搶購", "優惠", "免費"
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

        // 比對精準符號與詞彙
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showToast("⚡ 手勢點擊：$pattern")
                    return true
                }
            }
        }

        // 比對常見 View ID
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

        if (rect.width() in 10..400 && rect.height() in 10..400) {
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    /**
     * 針對截圖中 Unity Ads 圓角卡片與 Google Play 彈窗量身微調的實體手勢座標
     */
    private fun triggerCardCornerHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        // 卡片右上角叉叉比例（直向約 89%, 橫向約 92%）
        val rightX = if (isLandscape) width * 0.92f else width * 0.89f
        // Google Play 安裝彈窗左上角叉叉比例
        val leftX = if (isLandscape) width * 0.08f else width * 0.09f
        val topY = if (isLandscape) height * 0.085f else height * 0.09f

        // 1. 優先點擊卡片右上角 (圖中的白色細叉叉)
        clickAt(rightX, topY)

        // 2. 延遲 350ms 補點左上角 (針對 Google Play 浮動彈窗的左上叉叉)
        Handler(Looper.getMainLooper()).postDelayed({
            clickAt(leftX, topY)
        }, 350L)

        showToast("⚡ 已點擊廣告卡片關閉熱區")
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
