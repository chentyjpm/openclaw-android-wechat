package com.ws.wx_server.ui

import android.app.Service
import android.content.Intent
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
import android.widget.TextView
import android.widget.Toast
import com.ws.wx_server.R
import com.ws.wx_server.acc.MyAccessibilityService
import com.ws.wx_server.core.CoreForegroundService
import com.ws.wx_server.core.ServiceStateStore
import com.ws.wx_server.util.Logger

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var stateText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) {
            Logger.w("Floating control requires overlay permission")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureOverlay()
        updateStateText()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    private fun ensureOverlay() {
        if (rootView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.kimi_float_control, null, false)
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
        stateText = null
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
        stateText = view.findViewById(R.id.tv_float_state)
        val startBtn = view.findViewById<Button>(R.id.btn_float_start)
        val stopBtn = view.findViewById<Button>(R.id.btn_float_stop)
        val tabScanStartBtn = view.findViewById<Button>(R.id.btn_float_tab_scan_start)
        val tabScanStopBtn = view.findViewById<Button>(R.id.btn_float_tab_scan_stop)

        startBtn.setOnClickListener {
            CoreForegroundService.start(this)
            sendBroadcast(
                Intent(MyAccessibilityService.ACTION_START_TAB_SCAN).apply { setPackage(packageName) }
            )
            updateStateText()
            Toast.makeText(this, "Service started + tab scan", Toast.LENGTH_SHORT).show()
        }
        stopBtn.setOnClickListener {
            CoreForegroundService.stop(this)
            updateStateText()
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }
        tabScanStartBtn.setOnClickListener {
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

    private fun updateStateText() {
        val running = ServiceStateStore.isRunning(this)
        stateText?.text = if (running) "Running" else "Stopped"
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }
}
