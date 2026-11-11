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
        private const val CHECK_INTERVAL_MS = 4000L // 每 4 秒定時巡檢

        // 系統安全清單：設定、相簿、桌面不執行點擊
        private val SYSTEM_SAFE_PACKAGES = listOf(
            "settings",
            "launcher",
            "systemui",
            "inputmethod",
            "closer",
            "gallery",
            "photos"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var isActionPending = false // 防重入標記
    private var audioManager: AudioManager? = null
    private var windowManager: WindowManager? = null

    // 常規廣告關閉特徵清單
    private val CLOSE_PATTERNS = listOf(
        "✕", "×", "✖", "X", "x",
        "⏯", "▶|", "|▶",
        "關閉", "跳過", "關閉廣告", "跳過廣告",
        "Close", "close", "SKIP", "Skip", "skip"
    )

    private val inspectionRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // 1. 每輪巡檢前強制彈出提示
            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                // 2. 踢醒系統視窗事件流，防止系統休眠停擺
                wakeUpSystemPipeline()

                // 3. 執行比對與關閉
                if (!isActionPending) {
                    processScreenAction()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 閉環保活
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
        // 系統事件連帶巡檢
    }

    override fun onInterrupt() {}

    /**
     * 視窗管線看門狗踢醒：
     * 利用 ADJUST_SAME 發送無害音量廣播，刺激系統事件分發，防同一畫面凍結休眠
     */
    private fun wakeUpSystemPipeline() {
        try {
            val am = audioManager ?: return
            // ADJUST_SAME 不改變音量數值、不震動、不發聲，但能強迫系統拉起事件通道
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
     * 精準元件掃描：
     * 1. 文字/符號特徵比對
     * 2. View ID 比對
     * 3. Unity Ads 圖片卡片叉叉：右上角小按鈕，不被頂部英文字串阻擋
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val (screenWidth, screenHeight) = getRealScreenSize()

        // 排除頂部導航列的中右功能區 (Y < 12% 且 X 在 50%~85% 之間)
        if (screenWidth > 0 && screenHeight > 0) {
            val isInTopNavBarFunctionZone = rect.centerY() < screenHeight * 0.12f &&
                    rect.centerX() > screenWidth * 0.50f && rect.centerX() < screenWidth * 0.85f
            if (isInTopNavBarFunctionZone) {
                return false
            }
        }

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. 文字與符號比對
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

        // 3. Unity Ads 圖片卡片右上角叉叉精準辨識：
        // 寬高在 20px..280px 之間，位於頂部 Y < 26% 且 X > 80% 的右上角元件
        if (screenWidth > 0 && screenHeight > 0) {
            val isSmallButton = rect.width() in 20..280 && rect.height() in 20..280
            val isTopRightCorner = rect.top < screenHeight * 0.26f && rect.right > screenWidth * 0.80f

            if (isSmallButton && isTopRightCorner) {
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
            val clickX = rect.centerX().toFloat()
            val clickY = rect.centerY().toFloat()

            if (isDebugModeEnabled()) {
                isActionPending = true
                // 緊貼目標按鈕正下方顯示紅標
                showDebugMarkerUnderButton(rect, 3500L)
                showAlwaysToast("🛠️ 除錯中：已鎖定按鈕，3 秒後執行點擊...")

                // 延遲 3 秒後再觸發點擊，給予充足時間截圖驗證
                mainHandler.postDelayed({
                    dispatchClick(clickX, clickY)
                    isActionPending = false
                }, 3000L)
            } else {
                dispatchClick(clickX, clickY)
            }
            return true
        }
        return false
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

    /**
     * 在按鈕正下方緊貼顯示「▲ 關閉！」
     */
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
