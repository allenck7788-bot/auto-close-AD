package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
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

            showAlwaysToast("🔍 正在偵測廣告中...")

            try {
                wakeUpSystemPipeline()

                if (!isActionPending) {
                    processScreenAction()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isServiceRunning) {
                    mainHandler.removeCallbacks(this)
                    mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        isActionPending = false
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        showAlwaysToast("AutoAdCloser 服務已連線！")

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(inspectionRunnable, 1000L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAllTasks()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllTasks()
    }

    override fun onInterrupt() {
        stopAllTasks()
    }

    private fun stopAllTasks() {
        isServiceRunning = false
        isActionPending = false
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isServiceRunning && !isActionPending) {
            processScreenAction()
        }
    }

    private fun wakeUpSystemPipeline() {
        try {
            val am = audioManager ?: return
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
        } catch (_: Exception) {}
    }

    private fun processScreenAction() {
        if (!isServiceRunning) return

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
        // 【純精準比對】：尋找符合條件的關閉按鈕
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
     * 掃描元件：加入左上角漢堡選單 (≡) 排除白名單
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val (screenWidth, screenHeight) = getRealScreenSize()

        if (screenWidth > 0 && screenHeight > 0) {
            val centerYRatio = rect.centerY().toFloat() / screenHeight
            val centerXRatio = rect.centerX().toFloat() / screenWidth

            val isTopZone = centerYRatio in 0.02f..0.25f
            val isTopRightSpot = isTopZone && (centerXRatio in 0.75f..0.98f)
            val isTopLeftSpot = isTopZone && (centerXRatio in 0.02f..0.22f)

            // 避開主頁頂部導航列中右功能區 (Y < 12%, X 在 50%~85% 之間)
            val isInTopNavBarFunctionZone = centerYRatio < 0.12f && centerXRatio in 0.50f..0.85f

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // 【核心防護 1】：嚴格排除左上角三條線漢堡選單 (≡ / Menu / Drawer)
            val isHamburgerMenu = text == "≡" || desc == "≡" || text.contains("≡") || desc.contains("≡") ||
                    desc.contains("menu", ignoreCase = true) || desc.contains("drawer", ignoreCase = true) ||
                    desc.contains("選單", ignoreCase = true) || desc.contains("菜单", ignoreCase = true) ||
                    desc.contains("導航", ignoreCase = true) || desc.contains("navigation", ignoreCase = true) ||
                    viewId.contains("menu") || viewId.contains("drawer")

            if (isHamburgerMenu) {
                return false
            }

            if (!isInTopNavBarFunctionZone && (isTopRightSpot || isTopLeftSpot)) {
                // 1. 文字/符號比對
                for (pattern in CLOSE_PATTERNS) {
                    if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                        if (performGestureClick(node)) {
                            val sec = getClickDelaySec()
                            showAlwaysToast("⚡ 偵測到關閉按鈕：$pattern (等待${sec}秒後點擊)")
                            return true
                        }
                    }
                }

                // 2. 元件 ID 比對
                if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss") || viewId.contains("btn_close")) {
                    if (performGestureClick(node)) {
                        val sec = getClickDelaySec()
                        showAlwaysToast("⚡ 偵測到關閉元件 (等待${sec}秒後點擊)")
                        return true
                    }
                }

                // 3. Unity Ads 卡片右上角微型按鈕比對（排除左上角無文字元件，防止誤觸三條線漢堡圖）
                val widthRatio = rect.width().toFloat() / screenWidth
                val heightRatio = rect.height().toFloat() / screenHeight
                val isButtonProportion = (widthRatio in 0.025f..0.18f) && (heightRatio in 0.015f..0.14f)

                val aspectRatio = if (rect.height() > 0) rect.width().toFloat() / rect.height().toFloat() else 0f
                val isSquareOrCircle = aspectRatio in 0.50f..1.90f

                // 純幾何判定僅套用於「右上角」按鈕，左上角若無文字絕對不盲點
                if (isTopRightSpot && isButtonProportion && isSquareOrCircle) {
                    if (performGestureClick(node)) {
                        val sec = getClickDelaySec()
                        showAlwaysToast("⚡ 鎖定關閉按鈕 (等待${sec}秒後點擊)")
                        return true
                    }
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

    /**
     * 讀取使用者自訂的等待秒數（預設 8 秒）
     */
    private fun getClickDelaySec(): Int {
        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)
        return sp.getInt("click_delay_sec", 8)
    }

    /**
     * 手勢延遲執行：依使用者選擇的秒數進行動態倒數與標籤提示
     */
    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val (screenWidth, screenHeight) = getRealScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) return false

        if (rect.width() > screenWidth * 0.35f || rect.centerY() > screenHeight * 0.25f) {
            return false
        }

        val clickX = rect.centerX().toFloat()
        val clickY = rect.centerY().toFloat()

        isActionPending = true

        val delaySec = getClickDelaySec()
        val delayMillis = delaySec * 1000L

        // 標籤展示時間為倒數時間加 500 毫秒，維持全程陪伴提示
        showDebugMarkerUnderButton(rect, "▲ 關閉！ (${delaySec}秒)", delayMillis + 500L)

        mainHandler.postDelayed({
            if (isServiceRunning) {
                dispatchClick(clickX, clickY)
            }
            isActionPending = false
        }, delayMillis)

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

    /**
     * 在按鈕下方顯示紅底白字「▲ 關閉！ (X秒)」
     */
    private fun showDebugMarkerUnderButton(buttonRect: Rect, labelText: String, displayDurationMs: Long) {
        mainHandler.post {
            val wm = windowManager ?: return@post
            try {
                val markView = TextView(applicationContext).apply {
                    text = labelText
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
        if (!isServiceRunning) return
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
}
