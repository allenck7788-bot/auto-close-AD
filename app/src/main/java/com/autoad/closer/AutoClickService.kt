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
    private var isActionPending = false // 防重入與冷卻標記
    private var audioManager: AudioManager? = null
    private var windowManager: WindowManager? = null

    // 關閉按鈕特徵清單
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
        if (!isServiceRunning || isActionPending) return

        val roots = getAllWindowRoots()
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

    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
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
        return roots
    }

    /**
     * 處理完整快取中：加入 2.5 秒冷卻機制，防連續重複點擊兩次返回
     */
    private fun checkAndHandleCachingScreen(root: AccessibilityNodeInfo): Boolean {
        if (!hasTextKeyword(root, "完整快取中")) {
            return false
        }

        // 立即上鎖防止連點
        isActionPending = true
        showAlwaysToast("⚡ 偵測到「完整快取中」，點擊左上返回")

        val clicked = findAndClickBackButton(root)
        if (!clicked) {
            clickTopLeftBackButton()
        }

        // 延遲 2.5 秒冷卻解鎖，確保只點擊一次且頁面平順跳轉
        mainHandler.postDelayed({
            isActionPending = false
        }, 2500L)

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
                dispatchClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                return true
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

        dispatchClick(backX, backY)
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
     * 掃描元件（維持漢堡選單與三點更多選項白名單排除）
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

            // 白名單排除 1：左上角三條線漢堡選單 (≡)
            val isHamburgerMenu = text == "≡" || desc == "≡" || text.contains("≡") || desc.contains("≡") ||
                    desc.contains("menu", ignoreCase = true) || desc.contains("drawer", ignoreCase = true) ||
                    desc.contains("選單", ignoreCase = true) || desc.contains("菜单", ignoreCase = true) ||
                    desc.contains("導航", ignoreCase = true) || desc.contains("navigation", ignoreCase = true) ||
                    viewId.contains("menu") || viewId.contains("drawer")

            if (isHamburgerMenu) {
                return false
            }

            // 白名單排除 2：右上角三個直點更多選項 (⋮)
            val isMoreOptionsButton = text == "⋮" || desc == "⋮" || text.contains("⋮") || desc.contains("⋮") ||
                    text == "..." || desc == "..." || text == "…" || desc == "…" ||
                    desc.contains("more options", ignoreCase = true) || desc.contains("overflow", ignoreCase = true) ||
                    desc.contains("更多選項", ignoreCase = true) || desc.contains("更多选项", ignoreCase = true) ||
                    desc.contains("更多", ignoreCase = true) || viewId.contains("overflow") || viewId.contains("more")

            if (isMoreOptionsButton) {
                return false
            }

            if (!isInTopNavBarFunctionZone && (isTopRightSpot || isTopLeftSpot)) {
                // 1. 文字/符號比對
                for (pattern in CLOSE_PATTERNS) {
                    if (isMatchingPattern(text, pattern) || isMatchingPattern(desc, pattern)) {
                        if (performGestureClick(node)) {
                            val sec = getClickDelaySec()
                            showAlwaysToast("⚡ 偵測到關閉按鈕：$pattern (倒數${sec}秒)")
                            return true
                        }
                    }
                }

                // 2. 元件 ID 比對
                if (viewId.contains("close") || viewId.contains("skip") || viewId.contains("dismiss") || viewId.contains("btn_close")) {
                    if (performGestureClick(node)) {
                        val sec = getClickDelaySec()
                        showAlwaysToast("⚡ 偵測到關閉元件 (倒數${sec}秒)")
                        return true
                    }
                }

                // 3. Unity Ads 卡片右上角微型按鈕比對
                val widthRatio = rect.width().toFloat() / screenWidth
                val heightRatio = rect.height().toFloat() / screenHeight
                val isButtonProportion = (widthRatio in 0.025f..0.18f) && (heightRatio in 0.015f..0.14f)

                val aspectRatio = if (rect.height() > 0) rect.width().toFloat() / rect.height().toFloat() else 0f
                val isSquareOrCircle = aspectRatio in 0.50f..1.90f

                if (isTopRightSpot && isButtonProportion && isSquareOrCircle) {
                    if (performGestureClick(node)) {
                        val sec = getClickDelaySec()
                        showAlwaysToast("⚡ 鎖定關閉按鈕 (倒數${sec}秒)")
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

    private fun getClickDelaySec(): Int {
        val sp = getSharedPreferences("autoad_prefs", Context.MODE_PRIVATE)
        return sp.getInt("click_delay_sec", 8)
    }

    /**
     * 檢查指定按鈕座標區域在當前畫面上是否依然存在有效按鈕
     */
    private fun isTargetButtonStillPresent(targetRect: Rect): Boolean {
        val roots = getAllWindowRoots()
        for (r in roots) {
            if (checkNodeOverlapsTarget(r, targetRect)) {
                return true
            }
        }
        return false
    }

    private fun checkNodeOverlapsTarget(node: AccessibilityNodeInfo, targetRect: Rect): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 判斷中心點是否依舊重疊於鎖定區域（容許 35px 微小位移）
        val isCenterMatched = Math.abs(rect.centerX() - targetRect.centerX()) < 35 &&
                              Math.abs(rect.centerY() - targetRect.centerY()) < 35

        if (isCenterMatched && rect.width() in 20..350 && rect.height() in 20..350) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (checkNodeOverlapsTarget(child, targetRect)) return true
        }
        return false
    }

    /**
     * 手勢延遲執行：
     * 倒數期間每一秒重新驗證按鈕是否存在；若消失則即刻撤銷點擊並取消倒數！
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

        val totalSec = getClickDelaySec()

        startVerifiedCountdownOverlay(rect, totalSec) { isCompleted ->
            if (isCompleted && isServiceRunning) {
                dispatchClick(clickX, clickY)
            }
            // 延遲 500ms 解鎖，防手勢送出瞬間的視窗殘影
            mainHandler.postDelayed({
                isActionPending = false
            }, 500L)
        }

        return true
    }

    /**
     * 具備每秒即時驗證能力的懸浮倒數視窗
     */
    private fun startVerifiedCountdownOverlay(buttonRect: Rect, totalSec: Int, onFinish: (Boolean) -> Unit) {
        mainHandler.post {
            val wm = windowManager ?: run {
                onFinish(true)
                return@post
            }

            var currentSec = totalSec

            val markView = TextView(applicationContext).apply {
                text = "▲ 關閉！ $currentSec"
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

            try {
                wm.addView(markView, params)
            } catch (_: Exception) {
                onFinish(true)
                return@post
            }

            // 每秒倒數與掃描驗證 Runnable
            val countdownRunnable = object : Runnable {
                override fun run() {
                    if (!isServiceRunning) {
                        try { wm.removeView(markView) } catch (_: Exception) {}
                        return
                    }

                    // 【核心機制】：在扣減秒數前，即時複檢按鈕是否還在畫面上
                    val isStillThere = isTargetButtonStillPresent(buttonRect)
                    if (!isStillThere) {
                        // 按鈕已不存在（提前被關閉或畫面切換），立即撤回倒數與點擊！
                        try { wm.removeView(markView) } catch (_: Exception) {}
                        showAlwaysToast("ℹ️ 廣告關閉按鈕已消失，取消點擊")
                        onFinish(false)
                        return
                    }

                    currentSec--
                    if (currentSec > 0) {
                        markView.text = "▲ 關閉！ $currentSec"
                        mainHandler.postDelayed(this, 1000L)
                    } else {
                        // 倒數結束，執行點擊
                        try { wm.removeView(markView) } catch (_: Exception) {}
                        onFinish(true)
                    }
                }
            }

            mainHandler.postDelayed(countdownRunnable, 1000L)
        }
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
