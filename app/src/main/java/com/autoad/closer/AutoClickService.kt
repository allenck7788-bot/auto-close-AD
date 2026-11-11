package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        private const val CHECK_INTERVAL_MS = 3500L // 3.5 秒定時巡檢

        // 系統安全清單
        private val SYSTEM_SAFE_PACKAGES = listOf(
            "settings",
            "inputmethod",
            "closer"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var isActionPending = false // 防重入標記
    private var audioManager: AudioManager? = null
    private var windowManager: WindowManager? = null

    // 常規文字與符號特徵清單
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // 1. 固定提示正在偵測
            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                // 2. 針對完全不動的靜態畫面，發送系統級無感事件強制喚醒無障礙快取
                wakeUpSystemPipeline()

                // 3. 執行精確比對
                if (!isActionPending) {
                    processScreenAction()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mainHandler.removeCallbacks(this)
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

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
        // 畫面有變化時立即觸發檢驗
        if (!isActionPending) {
            processScreenAction()
        }
    }

    override fun onInterrupt() {}

    /**
     * 靜態畫面喚醒看門狗：
     * 發送無害音量微廣播刺激系統事件總線，徹底解決靜態畫面無障礙節點凍結問題
     */
    private fun wakeUpSystemPipeline() {
        try {
            val am = audioManager ?: return
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
        } catch (_: Exception) {}
    }

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
        // 【純精準比對，絕不盲點】：僅掃描頂部左上角與右上角，嚴格忽略下半部
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
            if (isSafeScreen(child)) return true
        }
        return false
    }

    /**
     * 掃描元件：
     * 1. 嚴格忽略下半部（Y > 26% 包含左下角、右下角完全排除）
     * 2. 排除頂部導航列中右功能區
     * 3. 鎖定「左上角」與「右上角」微型關閉按鈕
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val (screenWidth, screenHeight) = getRealScreenSize()

        if (screenWidth <= 0 || screenHeight <= 0) return false

        val centerYRatio = rect.centerY().toFloat() / screenHeight
        val centerXRatio = rect.centerX().toFloat() / screenWidth

        // 【核心規則 1】：絕對排除螢幕下半部！Y 軸超過 26% 一律不予判定（徹底杜絕左下角、右下角誤判）
        if (centerYRatio > 0.26f) {
            return false
        }

        // 【核心規則 2】：排除主頁頂部導航列中右功能區 (Y < 12%, X 在 50%~85% 之間)
        val isInTopNavBarFunctionZone = centerYRatio < 0.12f && centerXRatio in 0.50f..0.85f
        if (isInTopNavBarFunctionZone) {
            return false
        }

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 文字/符號比對（限於頂部區域）
        for (pattern in CLOSE_PATTERNS) {
            if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                if (performGestureClick(node)) {
                    showAlwaysToast("⚡ 點擊關閉按鈕：$pattern")
                    return true
                }
            }
        }

        // 2. 元件 ID 比對
        if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss") || viewId.contains("btn_close")) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 點擊關閉元件")
                return true
            }
        }

        // 3. 【純幾何角落按鈕精準辨識】：針對右上角與左上角的微型圓形/方形叉叉（含全黑圓底 ✕）
        // (A) 位置限定：右上角 (X: 75%~98%, Y: 3%~22%) 或 左上角 (X: 2%~22%, Y: 3%~20%)
        val isTopRightSpot = (centerXRatio in 0.75f..0.98f) && (centerYRatio in 0.03f..0.22f)
        val isTopLeftSpot = (centerXRatio in 0.02f..0.22f) && (centerYRatio in 0.03f..0.20f)

        // (B) 尺寸限定：寬度佔螢幕 2.5%~16%，高度佔螢幕 1.5%~12%
        val widthRatio = rect.width().toFloat() / screenWidth
        val heightRatio = rect.height().toFloat() / screenHeight
        val isButtonProportion = (widthRatio in 0.025f..0.16f) && (heightRatio in 0.015f..0.12f)

        // (C) 方形/圓形長寬比（0.55 ~ 1.80）
        val aspectRatio = if (rect.height() > 0) rect.width().toFloat() / rect.height().toFloat() else 0f
        val isSquareOrCircle = aspectRatio in 0.55f..1.80f

        if ((isTopRightSpot || isTopLeftSpot) && isButtonProportion && isSquareOrCircle) {
            if (performGestureClick(node)) {
                showAlwaysToast("⚡ 精準鎖定角落關閉按鈕")
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
            source.equals(pattern, ignoreCase = true) || source.contains(pattern, ignoreCase = true)
        }
    }

    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val (screenWidth, screenHeight) = getRealScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) return false

        // 再次保險：若為下半部或全螢幕大圖，絕不點擊
        if (rect.centerY() > screenHeight * 0.26f || rect.width() > screenWidth * 0.5f) {
            return false
        }

        val clickX = rect.centerX().toFloat()
        val clickY = rect.centerY().toFloat()

        if (isDebugModeEnabled()) {
            isActionPending = true
            showDebugMarkerUnderButton(rect, 3500L)
            showAlwaysToast("🛠️ 除錯中：已鎖定按鈕，3 秒後執行點擊...")

            mainHandler.postDelayed({
                dispatchClick(clickX, clickY)
                isActionPending = false
            }, 3000L)
        } else {
            dispatchClick(clickX, clickY)
        }
        return true
    }

    private fun dispatchClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun isDebugModeEnabled(): Boolean {
        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean("debug_mode", false)
    }

    private fun showDebugMarkerUnderButton(buttonRect: Rect, displayDurationMs: Long = 1500L) {
        mainHandler.post {
            val wm = windowManager ?: return@post
            try {
                val markView = TextView(applicationContext).apply {
                    text = "▲ 關閉！"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setBackgroundColor(Color.parseColor("#E53935"))
                    setPadding(12, 4, 12, 4)
                }

                markView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val markWidth = markView.measuredWidth

                val (screenWidth, _) = getRealScreenSize()

                var targetX = buttonRect.centerX() - (markWidth / 2)
                if (screenWidth > 0) {
                    targetX = targetX.coerceIn(8, screenWidth - markWidth - 8)
                }

                val targetY = buttonRect.bottom + 2

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = targetX
                    y = targetY
                }

                wm.addView(markView, params)

                mainHandler.postDelayed({
                    try {
                        wm.removeView(markView)
                    } catch (_: Exception) {}
                }, displayDurationMs)
            } catch (_: Exception) {}
        }
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
