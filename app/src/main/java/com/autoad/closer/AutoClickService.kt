package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.*

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isRunning = false

    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        showFloatingBubble()
        startDetectionLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        removeFloatingBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 畫面產生異動時即時檢查 UI 節點文字
        event?.source?.let { node ->
            scanAndClickNodes(node)
        }
    }

    override fun onInterrupt() {}

    /**
     * 建立可隨意拖曳的懸浮狀態小標籤
     */
    private fun showFloatingBubble() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 30
                y = 150
            }

            floatingView = TextView(this).apply {
                text = "● 運作中"
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(20, 10, 20, 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#992E7D32")) // 半透明綠底
                    cornerRadius = 30f
                }
            }

            // 支援在螢幕上按住拖動位置
            floatingView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatingView, params)
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingBubble() {
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
    }

    /**
     * 觸發關閉動作時，懸浮窗切換成橘色閃爍提示
     */
    private fun notifyActionTriggered() {
        serviceScope.launch {
            floatingView?.let { view ->
                (view.background as? GradientDrawable)?.setColor(Color.parseColor("#CCF57C00")) // 橘色
                view.text = "⚡ 關閉廣告"
                delay(1200L)
                (view.background as? GradientDrawable)?.setColor(Color.parseColor("#992E7D32")) // 恢復綠色
                view.text = "● 運作中"
            }
        }
    }

    /**
     * 背景自動偵測迴圈
     */
    private fun startDetectionLoop() {
        serviceScope.launch(Dispatchers.Default) {
            while (isRunning) {
                // 1. 快速檢測可點擊的關閉關鍵字節點
                rootInActiveWindow?.let { root ->
                    scanAndClickNodes(root)
                }

                // 2. Android 11+ 原生無痕背景截圖檢測角落按鈕
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            val copyBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap?.recycle()
                            screenshot.hardwareBuffer.close()

                            copyBitmap?.let { bmp ->
                                val target = findCloseButtonInBitmap(bmp)
                                bmp.recycle()
                                if (target != null) {
                                    clickAt(target.x, target.y)
                                    notifyActionTriggered()
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {}
                    })
                }

                delay(1500L) // 每 1.5 秒執行一次掃描
            }
        }
    }

    /**
     * 掃描無障礙節點樹，比對常見的叉叉與關閉字樣
     */
    private fun scanAndClickNodes(node: AccessibilityNodeInfo) {
        val closeKeywords = listOf("關閉", "跳過", "Close", "close", "✕", "×", "X")
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (kw in closeKeywords) {
            if (text.contains(kw) || desc.contains(kw)) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    notifyActionTriggered()
                    return
                } else {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                    notifyActionTriggered()
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { scanAndClickNodes(it) }
        }
    }

    fun getScreenSize(): Pair<Int, Int> {
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

    private fun getDynamicCornerBoxes(screenWidth: Int, screenHeight: Int): List<Rect> {
        val cornerWidth = (screenWidth * 0.22).toInt()
        val cornerHeight = (screenHeight * 0.16).toInt()

        return listOf(
            Rect(screenWidth - cornerWidth, 0, screenWidth, cornerHeight), // 右上角 ROI
            Rect(0, 0, cornerWidth, cornerHeight)                           // 左上角 ROI
        )
    }

    fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun findCloseButtonInBitmap(bitmap: Bitmap): PointF? {
        val (screenWidth, screenHeight) = getScreenSize()
        val corners = getDynamicCornerBoxes(screenWidth, screenHeight)

        for (box in corners) {
            var whitePixelCount = 0
            var sumX = 0L
            var sumY = 0L

            val step = 4
            for (y in box.top until box.bottom step step) {
                for (x in box.left until box.right step step) {
                    if (x >= bitmap.width || y >= bitmap.height) continue
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    if (r > 225 && g > 225 && b > 225) {
                        whitePixelCount++
                        sumX += x
                        sumY += y
                    }
                }
            }

            // 統計像素密度，命中特定大小白色圖標時回傳中心點
            if (whitePixelCount in 25..500) {
                return PointF((sumX / whitePixelCount).toFloat(), (sumY / whitePixelCount).toFloat())
            }
        }
        return null
    }
}
