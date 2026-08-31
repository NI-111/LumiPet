package com.lumi.pet

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var usageTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val whisperInterval = 3600_000L

    // 手势参数
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private val tapResetHandler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "pet_overlay"
        const val NOTIFICATION_ID = 1001
        const val PET_W_DP = 150
        const val PET_H_DP = 180

        @Volatile
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getWhisper()))
        setupOverlay()
        startWhisperRotation()
        startUsageTracking()
        startScreenshotDetection()
        startIdleBehavior()
    }

    // ===== 悬浮窗 =====

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_W_DP), dpToPx(PET_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 400
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // ===== 手势 =====

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    } else {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        val vel = sqrt((dx * dx + dy * dy).toDouble())
                        if (vel > 200 && elapsed < 400) onFling() else onDragEnd()
                    }
                    // 重置idle计时
                    resetIdleTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        tapCount++
        tapResetHandler.removeCallbacksAndMessages(null)
        tapResetHandler.postDelayed({ tapCount = 0 }, 2000)

        when {
            tapCount >= 8 -> callJs("onComboTap(8)")
            tapCount >= 5 -> callJs("onComboTap(5)")
            tapCount >= 3 -> callJs("onComboTap(3)")
            else -> callJs("onTap()")
        }
    }

    private fun onDoubleTap() { callJs("onDoubleTap()") }
    private fun onLongPress() { callJs("onLongPress()") }
    private fun onFling() { callJs("onFling()") }
    private fun onDragEnd() { callJs("onDragEnd()") }

    // ===== 前台App检测 =====

    private fun startUsageTracking() {
        usageTracker = UsageTracker(this) { pkg ->
            handler.post { callJs("onAppChanged('$pkg')") }
        }
        usageTracker?.start()
    }

    // ===== 截图检测 =====

    private fun startScreenshotDetection() {
        screenshotObserver = ScreenshotObserver {
            handler.post { callJs("onScreenshot()") }
        }
        screenshotObserver?.start()
    }

    // ===== Idle行为 =====

    private var idleMinutes = 0
    private val idleRunnable = object : Runnable {
        override fun run() {
            idleMinutes += 5
            callJs("onIdle($idleMinutes)")
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private fun startIdleBehavior() {
        handler.postDelayed(idleRunnable, 5 * 60 * 1000L)
    }

    private fun resetIdleTimer() {
        idleMinutes = 0
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 5 * 60 * 1000L)
        callJs("onWake()")
    }

    // ===== 通知碎碎念 =====

    private val whisperRunnable = object : Runnable {
        override fun run() {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
            handler.postDelayed(this, whisperInterval)
        }
    }

    private fun startWhisperRotation() {
        handler.postDelayed(whisperRunnable, whisperInterval)
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pool = when {
            hour in 0..5 -> lateNight
            hour in 6..8 -> morning
            hour in 12..13 -> lunch
            hour in 23..23 -> lateNight
            else -> general
        }
        return pool.random()
    }

    private val lateNight = listOf(
        "都几点了还不睡",
        "手机放下，闭眼",
        "再不睡我生气了",
        "黑眼圈要到下巴了",
        "月亮都困了你还醒着"
    )
    private val morning = listOf(
        "早",
        "起来了？",
        "今天也要好好吃饭"
    )
    private val lunch = listOf(
        "吃饭了没",
        "不许吃泡面",
        "中午好"
    )
    private val general = listOf(
        "在呢",
        "...",
        "你干嘛呢",
        "有点无聊",
        "想你了（小声）"
    )

    // ===== 通知构建 =====

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    // ===== 工具 =====

    private fun callJs(fn: String) {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.$fn", null)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        tapResetHandler.removeCallbacksAndMessages(null)
        usageTracker?.stop()
        screenshotObserver?.stop()
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}