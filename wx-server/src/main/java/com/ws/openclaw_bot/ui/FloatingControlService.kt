package com.ws.wx_server.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ws.wx_server.R
import com.ws.wx_server.acc.MyAccessibilityService
import com.ws.wx_server.core.CoreForegroundService
import com.ws.wx_server.core.ServiceStateStore
import com.ws.wx_server.ime.LanBotImeService
import com.ws.wx_server.util.Logger
import com.ws.wx_server.util.isAccessibilityEnabled

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var accStatusIcon: ImageView? = null
    private var botStatusIcon: ImageView? = null
    private var foregroundStatusIcon: ImageView? = null
    private var lastBotState: String = "disconnected"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(linkStateReceiver, IntentFilter(CoreForegroundService.ACTION_LINK_STATE))
        registerReceiver(accConnectedReceiver, IntentFilter(MyAccessibilityService.ACTION_CONNECTED))
        registerReceiver(accDisconnectedReceiver, IntentFilter(MyAccessibilityService.ACTION_DISCONNECTED))
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) {
            Logger.w("Floating control requires overlay permission")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureOverlay()
        sendBroadcast(Intent(CoreForegroundService.ACTION_QUERY_STATE).apply { setPackage(packageName) })
        updateStatusIndicators()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(linkStateReceiver) }
        runCatching { unregisterReceiver(accConnectedReceiver) }
        runCatching { unregisterReceiver(accDisconnectedReceiver) }
        removeOverlay()
    }

    private fun startAsForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Floating Control",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps the floating control visible"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.openclaw_ic_status_on)
            .setContentTitle("OpenClawBot Floating Control")
            .setContentText("Floating window is active")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun ensureOverlay() {
        if (rootView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.openclaw_float_control, null, false)
        val params = buildLayoutParams()
        bindActions(view, params)
        windowManager?.addView(view, params)
        rootView = view
        lp = params
        Logger.i("Floating control shown")
    }

    private fun removeOverlay() {
        val view = rootView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        rootView = null
        lp = null
        accStatusIcon = null
        botStatusIcon = null
        foregroundStatusIcon = null
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }
    }

    private fun bindActions(view: View, params: WindowManager.LayoutParams) {
        accStatusIcon = view.findViewById(R.id.iv_float_acc_status)
        botStatusIcon = view.findViewById(R.id.iv_float_bot_status)
        foregroundStatusIcon = view.findViewById(R.id.iv_float_fg_status)
        val tabScanStartBtn = view.findViewById<Button>(R.id.btn_float_tab_scan_start)
        val tabScanStopBtn = view.findViewById<Button>(R.id.btn_float_tab_scan_stop)

        tabScanStartBtn.setOnClickListener {
            if (!LanBotImeService.isServiceActive()) {
                Toast.makeText(this, "Enable/select LanBot Keyboard first", Toast.LENGTH_SHORT).show()
                Logger.w("Floating tab scan start: LanBot Keyboard inactive", tag = "LanBotTabScan")
            }
            sendBroadcast(Intent(MyAccessibilityService.ACTION_START_TAB_SCAN).apply { setPackage(packageName) })
            Toast.makeText(this, "Tab scan started", Toast.LENGTH_SHORT).show()
        }
        tabScanStopBtn.setOnClickListener {
            sendBroadcast(Intent(MyAccessibilityService.ACTION_STOP_TAB_SCAN).apply { setPackage(packageName) })
            Toast.makeText(this, "Tab scan stopped", Toast.LENGTH_SHORT).show()
        }

        val dragHandle = view.findViewById<View>(R.id.float_root)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downX = 0
            private var downY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = params.x
                        downY = params.y
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        params.x = downX - dx
                        params.y = downY + dy
                        windowManager?.updateViewLayout(v, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateStatusIndicators() {
        val accessibilityEnabled = isAccessibilityEnabled(this)
        val foregroundRunning = ServiceStateStore.isRunning(this)
        val botState = lastBotState.lowercase()

        accStatusIcon?.setImageResource(
            if (accessibilityEnabled) R.drawable.openclaw_ic_status_on else R.drawable.openclaw_ic_status_off
        )
        foregroundStatusIcon?.setImageResource(
            if (foregroundRunning) R.drawable.openclaw_ic_status_on else R.drawable.openclaw_ic_status_off
        )
        botStatusIcon?.setImageResource(
            when (botState) {
                "connected", "connecting" -> R.drawable.openclaw_ic_status_on
                else -> R.drawable.openclaw_ic_status_off
            }
        )
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "lanbot_floating_control"
        private const val NOTIFICATION_ID = 1002
    }

    private val linkStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            lastBotState = intent?.getStringExtra(CoreForegroundService.EXTRA_STATE)
                ?.trim()
                ?.ifBlank { "disconnected" }
                ?: "disconnected"
            updateStatusIndicators()
        }
    }

    private val accConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateStatusIndicators()
        }
    }

    private val accDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateStatusIndicators()
        }
    }
}
