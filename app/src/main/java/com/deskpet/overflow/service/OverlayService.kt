package com.deskpet.overflow.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPackageName = ""
    private val lastGestureTimes = mutableListOf<Long>()

    // 充电感知
    private var powerReceiver: BroadcastReceiver? = null
    // 截图检测
    private var screenshotObserver: android.os.FileObserver? = null
    // 喝水提醒
    private var drinkWaterHandler: Handler? = null
    private var drinkRemindCount = 0
    // 通知碎碎念
    private var notificationUpdateHandler: Handler? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 64
        private const val PET_HEIGHT_DP = 64

        // 构建时替换
        const val SUPABASE_URL = "https://gvzhoxepylrhseuumgls.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_shYLYrERVyrXtjiDbG62Ng_9TUH3pg8"

        // App反应映射表
        val APP_REACTIONS: Map<String, AppReaction> = mapOf(
            "com.taobao.taobao" to AppReaction("买啥呢？让我看看！", "jealous", "金链子呢？戴上！"),
            "com.tmall.wireless" to AppReaction("又在逛天猫…", "jealous", "买买买！"),
            "com.ss.android.ugc.aweme" to AppReaction("又刷抖音！", "angry", "别看啦看我！"),
            "com.chaoxing.mobile" to AppReaction("学习呢…我不吵你", "shy", "加油哦"),
            "com.tencent.mm" to AppReaction("跟谁聊天呢…", "whisper", "哼"),
            "com.tencent.mobileqq" to AppReaction("QQ？还在用QQ？", "surprise", ""),
        )

        // 通知碎碎念 — 按时段
        val NOTIFICATION_TEXTS = mapOf(
            "night" to listOf("Zzz…", "呼噜……", "zzzZZZ", "夜深了喵"),
            "morning" to listOf("早安喵～", "新的一天！", "起床啦懒虫", "今天天气不错"),
            "afternoon" to listOf("好困…", "你在干嘛呢", "喵～", "想出去玩"),
            "evening" to listOf("该吃饭了", "晚上好呀", "今天辛苦了", "抱抱"),
        )
    }

    data class AppReaction(val bubble: String, val style: String, val extraBubble: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("……"))
        setupOverlay()
        startAppDetection()
        registerPowerReceiver()
        startScreenshotObserver()
        startDrinkWaterReminder()
        startNotificationUpdater()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // ===== 手势处理 =====
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

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
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
        postGesture("tap")
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
        postGesture("double_tap")
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
        postGesture("long_press")
    }

    // ===== Supabase 上报 =====
    private fun postGesture(type: String) {
        if (SUPABASE_URL.startsWith("%%")) return
        Thread {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/gesture_log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.outputStream.use {
                    it.write(JSONObject().apply { put("gesture_type", type) }.toString().toByteArray())
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun postAppUsage(packageName: String, appName: String) {
        if (SUPABASE_URL.startsWith("%%")) return
        Thread {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/app_usage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("package_name", packageName)
                    put("app_name", appName)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ===== 前台App检测 =====
    private fun startAppDetection() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                detectForegroundApp()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(runnable, 3000)
    }

    private fun detectForegroundApp() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 10000,
                now
            )
            val foreground = stats?.maxByOrNull { it.lastTimeUsed }
            if (foreground != null && foreground.packageName != lastPackageName) {
                lastPackageName = foreground.packageName
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(foreground.packageName, 0)
                    ).toString()
                } catch (_: Exception) { foreground.packageName }
                postAppUsage(foreground.packageName, appName)

                // App反应映射
                val reaction = APP_REACTIONS[foreground.packageName]
                if (reaction != null) {
                    overlayView?.evaluateJavascript(
                        "window.petEngine && (function(){ showReaction('${reaction.style}'); say('${reaction.bubble}','${reaction.style}',4000); })()",
                        null
                    )
                }
            }
        } catch (_: Exception) {}
    }

    // ===== 充电感知 =====
    private fun registerPowerReceiver() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && (function(){ showReaction('surprise'); say('嗯？来电了？','normal',3000); })()",
                            null
                        )
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && (function(){ showReaction('angry'); say('啊！断电了！','angry',3000); })()",
                            null
                        )
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && (function(){ showReaction('sleepy'); say('没电了喵……','shy',4000); })()",
                            null
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(powerReceiver, filter)
    }

    // ===== 截图检测 =====
    private fun startScreenshotObserver() {
        try {
            val path = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            ).absolutePath + "/Screenshots"
            screenshotObserver = object : android.os.FileObserver(path, android.os.FileObserver.CREATE) {
                override fun onEvent(event: Int, file: String?) {
                    if (event == android.os.FileObserver.CREATE && file != null) {
                        mainHandler.post {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && (function(){ showReaction('shy'); say('嗯？被偷拍了？','shy',3500); })()",
                                null
                            )
                        }
                    }
                }
            }
            screenshotObserver?.startWatching()
        } catch (_: Exception) {}
    }

    // ===== 喝水提醒 =====
    private fun startDrinkWaterReminder() {
        drinkWaterHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                drinkRemindCount++
                val msg = when {
                    drinkRemindCount <= 1 -> "喝水！"
                    drinkRemindCount <= 2 -> "喝水啊！渴死了！"
                    drinkRemindCount <= 4 -> "给老子喝水！！！"
                    else -> "你他妈喝水啊！！嗓子不干吗！！"
                }
                overlayView?.evaluateJavascript(
                    "window.petEngine && (function(){ showReaction('angry'); say('$msg','shout',5000); })()",
                    null
                )
                drinkWaterHandler?.postDelayed(this, 2 * 60 * 60 * 1000L)
            }
        }
        drinkWaterHandler?.postDelayed(runnable, 2 * 60 * 60 * 1000L)
    }

    // ===== 通知碎碎念 =====
    private fun startNotificationUpdater() {
        notificationUpdateHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val slot = when (h) {
                    in 0..5 -> "night"
                    in 6..11 -> "morning"
                    in 12..17 -> "afternoon"
                    else -> "evening"
                }
                val texts = NOTIFICATION_TEXTS[slot] ?: NOTIFICATION_TEXTS["afternoon"]!!
                val text = texts.random()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(text))
                notificationUpdateHandler?.postDelayed(this, 60 * 60 * 1000L)
            }
        }
        notificationUpdateHandler?.postDelayed(runnable, 60 * 60 * 1000L)
    }

    // ===== 通知 =====
    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pet", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        powerReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenshotObserver?.stopWatching()
        drinkWaterHandler?.removeCallbacksAndMessages(null)
        notificationUpdateHandler?.removeCallbacksAndMessages(null)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}