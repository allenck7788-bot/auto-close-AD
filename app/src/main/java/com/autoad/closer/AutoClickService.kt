package com.autoad.closer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isScanning = false
    private val scanIntervalMs = 1500L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 監聽到視窗狀態改變時可彈性調頻
    }

    override fun onInterrupt() {}

    /**
     * 動態取得即時螢幕寬高（手機、平板、直橫向通用）
     */
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

    /**
     * 依螢幕比例動態設定掃描角落 (ROI)
     */
    private fun getDynamicCornerBoxes(screenWidth: Int, screenHeight: Int): List<android.graphics.Rect> {
        val cornerWidth = (screenWidth * 0.22).toInt()
        val cornerHeight = (screenHeight * 0.16).toInt()

        return listOf(
            android.graphics.Rect(screenWidth - cornerWidth, 0, screenWidth, cornerHeight), // 右上角
            android.graphics.Rect(0, 0, cornerWidth, cornerHeight)                           // 左上角
        )
    }

    /**
     * 模擬點擊手勢
     */
    fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 啟動背景定時掃描循環
     */
    fun startAutoScan(frameCaptureProvider: () -> Bitmap?) {
        if (isScanning) return
        isScanning = true

        serviceScope.launch {
            while (isScanning) {
                val bitmap = frameCaptureProvider()
                if (bitmap != null) {
                    val clickTarget = findCloseButtonInBitmap(bitmap)
                    bitmap.recycle()

                    if (clickTarget != null) {
                        clickAt(clickTarget.x, clickTarget.y)
                        delay(3000L) // 點擊後等待 3 秒避免重複觸發
                    }
                }
                delay(scanIntervalMs)
            }
        }
    }

    fun stopAutoScan() {
        isScanning = false
    }

    /**
     * 本地輕量高亮特徵檢測 (支援手機與平板角落比例)
     */
    private fun findCloseButtonInBitmap(bitmap: Bitmap): PointF? {
        val (screenWidth, screenHeight) = getScreenSize()
        val corners = getDynamicCornerBoxes(screenWidth, screenHeight)

        for (box in corners) {
            var whitePixelCount = 0
            var sumX = 0L
            var sumY = 0L

            val step = 4 // 降採樣步長以提升速度與降低耗電
            for (y in box.top until box.bottom step step) {
                for (x in box.left until box.right step step) {
                    if (x >= bitmap.width || y >= bitmap.height) continue
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // 檢測高亮白色叉叉特徵像素
                    if (r > 220 && g > 220 && b > 220) {
                        whitePixelCount++
                        sumX += x
                        sumY += y
                    }
                }
            }

            // 若在角落聚集了適量的白色特徵像素，判定為關閉按鈕區域
            if (whitePixelCount in 25..500) {
                val avgX = (sumX / whitePixelCount).toFloat()
                val avgY = (sumY / whitePixelCount).toFloat()
                return PointF(avgX, avgY)
            }
        }
        return null
    }
}
