package com.shuzhi.clawd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import kotlin.math.abs

/**
 * 桌宠悬浮窗服务。
 * 结构参考 AI-Live-Overflow 的 ExampleOverlayService，改了两处：
 *  1. 手势判定：原版 lastTapTime 在判双击前就被覆盖，会先响应单击再响应双击。
 *     这里改成单击延迟 300ms 确认，期间来第二次点就升级为双击。
 *  2. 通信层：去掉 Supabase，改为轮询本地 state.json。
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "clawd_pet"
        private const val NOTIFY_ID = 1001
        private const val PET_SIZE_DP = 120
        private const val PET_HEIGHT_DP = 160
        private const val DRAG_SLOP_PX = 10
        private const val DOUBLE_TAP_MS = 300L
        private const val LONG_PRESS_MS = 600L
        private const val POLL_INTERVAL_MS = 2000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var webView: WebView? = null
    private var pageReady = false

    private val main = Handler(Looper.getMainLooper())

    // 手势状态
    private var initialX = 0
    private var initialY = 0
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var downTime = 0L
    private var moved = false
    private var pendingTap: Runnable? = null

    /** 只听 127.0.0.1 的本地通信服务 */
    private val server = LocalServer()

    override fun onCreate() {
        super.onCreate()
        StateBridge.init(this)
        server.start()
        createChannel()
        startForeground(NOTIFY_ID, buildNotification("在你桌面上待着"))
        setupOverlay()
        main.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    // ---------- 悬浮窗 ----------

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(160)
        }

        val wv = WebView(this)
        // 顺序要紧：背景透明必须在 loadUrl 之前设，否则会闪一下白底
        wv.setBackgroundColor(0x00000000)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                // 页面刚就绪，把当前状态推一次，避免开机是默认表情
                StateBridge.readStateIfChanged()?.let { applyState(it) }
            }
        }
        wv.setOnTouchListener { _, event -> handleTouch(event) }
        wv.loadUrl("file:///android_asset/pet.html")

        webView = wv
        windowManager.addView(wv, params)
    }

    // ---------- 手势 ----------

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchRawX = event.rawX
                touchRawY = event.rawY
                downTime = System.currentTimeMillis()
                moved = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchRawX
                val dy = event.rawY - touchRawY
                if (!moved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) {
                    moved = true
                    cancelPendingTap()
                }
                if (moved) {
                    // 用 rawX/rawY 的位移，不用相对坐标，否则拖动会跳
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(webView, params)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - downTime
                when {
                    moved -> {
                        StateBridge.appendEvent(
                            "drag",
                            mapOf("x" to params.x, "y" to params.y)
                        )
                    }

                    elapsed > LONG_PRESS_MS -> {
                        cancelPendingTap()
                        callJs("window.petEngine && window.petEngine.onLongPress()")
                        StateBridge.appendEvent("long_press")
                    }

                    pendingTap != null -> {
                        // 上一次单击还在等确认 —— 这次就是双击
                        cancelPendingTap()
                        callJs("window.petEngine && window.petEngine.onDoubleTap()")
                        StateBridge.appendEvent("double_tap")
                    }

                    else -> {
                        // 先挂起，等 300ms 没有第二下再算单击
                        val r = Runnable {
                            pendingTap = null
                            callJs("window.petEngine && window.petEngine.onTap()")
                            StateBridge.appendEvent("tap")
                        }
                        pendingTap = r
                        main.postDelayed(r, DOUBLE_TAP_MS)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingTap()
                return true
            }
        }
        return false
    }

    private fun cancelPendingTap() {
        pendingTap?.let { main.removeCallbacks(it) }
        pendingTap = null
    }

    // ---------- 状态轮询 ----------

    private val pollTask = object : Runnable {
        override fun run() {
            if (pageReady) {
                StateBridge.readStateIfChanged()?.let { applyState(it) }
            }
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun applyState(raw: String) {
        val literal = raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")
        callJs("window.petEngine && window.petEngine.applyState('$literal')")
    }

    private fun callJs(script: String) {
        main.post { webView?.evaluateJavascript(script, null) }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Clawd",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("Clawd")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server.stop()
        main.removeCallbacksAndMessages(null)
        webView?.let {
            runCatching { windowManager.removeView(it) }
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}