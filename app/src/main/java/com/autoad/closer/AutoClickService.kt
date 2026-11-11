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
        private const val CHECK_INTERVAL_MS = 3500L // 固定每 3.5 秒執行一次巡檢與點擊

        // 唯二保護的系統安全黑名單：只有在桌面、設定、狀態列時才不動作
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

    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            try {
                processScreenAction()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 確保閉環循環，絕不中斷
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true

        showToast("AutoAdCloser 已啟動 (已開啟非系統畫面全覆蓋模式)")

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
        // 系統產生畫面異動時連帶觸發
    }

    override fun onInterrupt() {}

    /**
     * 核心邏輯：只要不是系統安全畫面，一律執行關閉
     */
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

        // 唯一檢查：是否在手機桌面或系統設定頁？
        for (r in roots) {
            if (isSystemSafeScreen(r)) {
                return // 在桌面或設定頁時完全靜音
            }
        }

        // 只要不是系統操作畫面，每 3.5 秒固定跳出提示並執行關閉
        showToast("🔍 正在嘗試關閉廣告...")

        // 1. 優先嘗試點擊帶有符號/文字的精準按鈕
        var clicked = false
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                clicked = true
                break
            }
        }

        // 2. 無論有沒有文字按鈕，全面對左上、右上（含金幣內縮角）發動實體熱區補刀
        if (!clicked) {
            triggerAllCornerHotspots()
        }
    }

    /**
     * 判斷是否為手機桌面、系統設定或本 App 介面
     */
    private fun isSystemSafeScreen(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains("AutoAdCloser", ignoreCase = true) || 
            desc.contains("AutoAdCloser", ignoreCase = true)) {
            return true
        }

        for (safe in SYSTEM_SAFE_PACKAGES) {
            if (pkg.contains(safe)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isSystemSafeScreen(child)) return true
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
                    showToast("⚡ 手勢點擊：$pattern")
                    return true
                }
            }
        }

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

        if (rect.width() in 10..450 && rect.height() in 10..450) {
            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    /**
     * 全面覆蓋雙邊角落：極限右上角、金幣內縮右上角、極限左上角、金幣內縮左上角
     */
    private fun triggerAllCornerHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        // 1. 極限邊緣座標（適用於你截圖中的圓形細叉與全螢幕廣告叉叉）
        val extremeRightX = if (isLandscape) width * 0.945f else width * 0.938f
        val extremeLeftX  = if (isLandscape) width * 0.055f else width * 0.062f

        // 2. 內縮金幣旁座標（適用於試玩廣告金幣右側）
        val innerRightX   = if (isLandscape) width * 0.900f else width * 0.835f
        val innerLeftX    = if (isLandscape) width * 0.100f else width * 0.165f

        val topY          = if (isLandscape) height * 0.080f else height * 0.068f

        // 梯次點擊：極限右上 -> 內縮右上 -> 極限左上 -> 內縮左上
        clickAt(extremeRightX, topY)
        mainHandler.postDelayed({ clickAt(innerRightX, topY) }, 200L)
        mainHandler.postDelayed({ clickAt(extremeLeftX, topY) }, 400L)
        mainHandler.postDelayed({ clickAt(innerLeftX, topY) }, 600L)
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
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
