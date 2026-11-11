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
        private const val CHECK_INTERVAL_MS = 4000L // 強制固定每 4 秒主動偵測一次
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    // 關閉與快進特徵清單
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶", ">>", "»", ">", "⏩",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    // 閉環定時任務：每次跑完保證排程下一次，永不中斷
    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            try {
                processCurrentScreen()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 無論中間發生任何狀況，4 秒後絕對再次執行
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true

        showToast("AutoAdCloser 服務已連線！(已啟動每 4 秒強制定時)")

        // 立即啟動不中斷的每 4 秒定時循環
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
        // 系統事件照常接收，但不依賴它做定時
    }

    override fun onInterrupt() {}

    /**
     * 核心偵測流程
     */
    private fun processCurrentScreen() {
        // 1. 取得所有層級視窗
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

        // 2. 自家 App 與系統設定頁安全防護（避免誤按到開關）
        for (r in roots) {
            if (isSafeScreen(r)) return
        }

        // 3. 固定跳出提示：證明此定時器 100% 正常活著！
        showToast("🔍 正在偵測廣告...")

        // 4. 文字/符號節點掃描
        for (r in roots) {
            if (scanAndClickNodes(r)) {
                return
            }
        }

        // 5. 若為試玩廣告或含下載特徵的廣告，發動雙邊熱區點擊
        for (r in roots) {
            if (isPlayableOrAdScreen(r)) {
                triggerPlayableCornerHotspots()
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

    private fun isPlayableOrAdScreen(node: AccessibilityNodeInfo): Boolean {
        val keywords = listOf(
            "拖拽", "移動", "開始遊戲", "开始游戏",
            "play now", "learn more", "install", "download", "安裝", "免費", "免费", "搶購"
        )
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (kw in keywords) {
            if (text.contains(kw) || desc.contains(kw)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isPlayableOrAdScreen(child)) return true
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

    private fun triggerPlayableCornerHotspots() {
        val (width, height) = getRealScreenSize()
        if (width <= 0 || height <= 0) return

        val isLandscape = width > height

        val playableRightX = if (isLandscape) width * 0.90f else width * 0.835f
        val standardRightX = if (isLandscape) width * 0.94f else width * 0.910f
        val leftX = if (isLandscape) width * 0.07f else width * 0.085f
        val topY = if (isLandscape) height * 0.08f else height * 0.065f

        // 1. 優先戳金幣旁的位置
        clickAt(playableRightX, topY)

        // 2. 補點常規右上角
        mainHandler.postDelayed({
            clickAt(standardRightX, topY)
        }, 300L)

        // 3. 補點左上角
        mainHandler.postDelayed({
            clickAt(leftX, topY)
        }, 600L)

        showToast("⚡ 已點擊試玩關閉熱區")
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
