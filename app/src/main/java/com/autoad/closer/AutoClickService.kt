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
        private const val CHECK_INTERVAL_MS = 4000L // 每 4 秒定時巡檢

        // 系統安全清單：在設定與特定系統畫面不觸發點擊
        private val SYSTEM_SAFE_PACKAGES = listOf(
            "settings",
            "inputmethod",
            "closer"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    // 常規廣告關閉與快進特徵
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // 1. 每 4 秒固定提示正在運作，絕對第一執行
            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 系統事件連帶巡檢
    }

    override fun onInterrupt() {}

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

        // 避免在設定頁面誤觸
        for (r in roots) {
            if (isSafeScreen(r)) return
        }

        // =========================================================
        // 【獨立專屬永久功能】：若出現「完整快取中」則自動點擊左上角 ←
        // =========================================================
        for (r in roots) {
            if (checkAndHandleCachingScreen(r)) {
                return
            }
        }

        // =========================================================
        // 【純精準比對，絕不盲點】：尋找符合條件的實體元件
        // =========================================================
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

    /**
     * 精準元件掃描：
     * 1. 文字/標籤特徵符合
     * 2. View ID 符合
     * 3. 針對無文字圖片叉叉（Unity Ads）：尺寸小於 180px 且位於卡片頂部角落的可點擊小元件
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 文字特徵比對
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showAlwaysToast("⚡ 點擊關閉按鈕：$pattern")
                    return true
                }
            }
        }

        // 2. 元件 ID 比對
        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss")) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 點擊關閉元件")
                return true
            }
        }

        // 3. 純圖片型卡片角落叉叉精準辨識（針對 Unity Ads 圓角卡片右/左上角的無文字小按鈕）
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val (screenWidth, screenHeight) = getRealScreenSize()

        if (screenWidth > 0 && screenHeight > 0) {
            val isSmallButton = rect.width() in 20..180 && rect.height() in 20..180
            val isTopCorner = rect.top < screenHeight * 0.20f && 
                              (rect.right > screenWidth * 0.80f || rect.left < screenWidth * 0.20f)

            if (isSmallButton && isTopCorner && node.isClickable) {
                if (performGestureClick(node)) {
                    showAlwaysToast("⚡ 精準點擊角落關閉元件")
                    return true
                }
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
