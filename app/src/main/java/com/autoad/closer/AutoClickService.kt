package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        startDetectionLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event?.source?.let { node ->
                scanAndClickNodes(node)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    // 1. 掃描文字節點（關閉、跳過、X 等）
                    rootInActiveWindow?.let { root ->
                        scanAndClickNodes(root)
                    }

                    // 2. Android 11+ 原生無痕背景截圖（偵測角落按鈕）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    val copyBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    bitmap?.recycle()
                                    screenshot.hardwareBuffer.close()

                                    copyBitmap?.let { bmp ->
                                        val target = findCloseButtonInBitmap(bmp)
                                        bmp.recycle()
                                        if (target != null) {
                                            clickAt(target.x, target.y)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            override fun onFailure(errorCode: Int) {}
                        })
                    }
                } catch (_: Exception) {}

                delay(1200L) // 每 1.2 秒輪詢一次
            }
        }
    }

    private fun scanAndClickNodes(node: AccessibilityNodeInfo) {
        val closeKeywords = listOf("關閉", "跳過", "Close", "close", "✕", "×", "X")
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (kw in closeKeywords) {
            if (text.contains(kw) || desc.contains(kw)) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                } else {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { scanAndClickNodes(it) }
            } catch (_: Exception) {}
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
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
            Rect(screenWidth - cornerWidth, 0, screenWidth, cornerHeight),
            Rect(0, 0, cornerWidth, cornerHeight)
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

            if (whitePixelCount in 25..500) {
                return PointF((sumX / whitePixelCount).toFloat(), (sumY / whitePixelCount).toFloat())
            }
        }
        return null
    }
}
