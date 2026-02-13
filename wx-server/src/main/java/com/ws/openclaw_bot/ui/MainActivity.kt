package com.ws.wx_server.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ws.wx_server.R
import com.ws.wx_server.capture.ScreenCapturePermissionStore
import com.ws.wx_server.core.CoreForegroundService
import com.ws.wx_server.core.ServiceStateStore
import com.ws.wx_server.util.Logger
import com.ws.wx_server.util.isAccessibilityEnabled
import com.ws.wx_server.util.openAccessibilitySettings
import com.ws.wx_server.link.CAPTURE_STRATEGY_SCREEN_FIRST

open class MainActivity : AppCompatActivity() {
    private lateinit var openTerminalBtn: Button
    private lateinit var statusText: TextView
    private lateinit var serviceStateText: TextView
    private lateinit var serviceStartBtn: Button
    private lateinit var serviceStopBtn: Button
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var serverStatusIcon: android.widget.ImageView
    private lateinit var serviceStatusIcon: android.widget.ImageView
    private lateinit var serverStatusText: TextView
    private lateinit var openServerSettings: Button
    private lateinit var openConfigEditorBtn: Button
    private lateinit var openAccessibilityBtn: Button
    private lateinit var openImeSettingsBtn: Button
    private lateinit var pickImeBtn: Button
    private var pendingStartServiceAfterGrant = false
    private var promptedCaptureThisResume = false
    private var promptedOverlayThisResume = false

    private val accListener = AccessibilityManager.AccessibilityStateChangeListener {
        updateAccessibilityStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.openclaw_activity_main)

        openTerminalBtn = findViewById(R.id.btn_open_terminal)
        statusText = findViewById(R.id.tv_status)
        statusIcon = findViewById(R.id.iv_status)
        serverStatusIcon = findViewById(R.id.iv_server_status)
        serviceStatusIcon = findViewById(R.id.iv_service_status)
        serverStatusText = findViewById(R.id.tv_server_status)
        serviceStateText = findViewById(R.id.tv_service_state)
        serviceStartBtn = findViewById(R.id.btn_service_start)
        serviceStopBtn = findViewById(R.id.btn_service_stop)
        openServerSettings = findViewById(R.id.btn_open_server_settings)
        openConfigEditorBtn = findViewById(R.id.btn_open_config_editor)
        openAccessibilityBtn = findViewById(R.id.btn_open_accessibility)
        openImeSettingsBtn = findViewById(R.id.btn_open_ime_settings)
        pickImeBtn = findViewById(R.id.btn_pick_ime)
        updateServiceStateUi(ServiceStateStore.isRunning(this))

        openTerminalBtn.setOnClickListener { openTermuxTerminal() }
        openServerSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        openConfigEditorBtn.setOnClickListener { startActivity(Intent(this, OpenClawConfigEditorActivity::class.java)) }
        openAccessibilityBtn.setOnClickListener { openAccessibilitySettings(this) }
        openImeSettingsBtn.setOnClickListener { openInputMethodSettings() }
        pickImeBtn.setOnClickListener { showInputMethodPicker() }
        ensureFloatingControlAlwaysOn()

        serviceStartBtn.setOnClickListener {
            val cfg = com.ws.wx_server.link.LinkConfigStore.load(this)
            if (!TEMP_DISABLE_CAPTURE_AND_OCR &&
                cfg.captureStrategy == CAPTURE_STRATEGY_SCREEN_FIRST &&
                ScreenCapturePermissionStore.load(this) == null
            ) {
                Logger.i("Start service requires MediaProjection permission", tag = "LanBotOCR")
                pendingStartServiceAfterGrant = true
                requestScreenCapturePermission()
                return@setOnClickListener
            }
            updateServiceStateUi(true)
            serverStatusText.text = "OpenClaw服务: 连接中"
            serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_waiting)
            CoreForegroundService.start(this)
        }

        serviceStopBtn.setOnClickListener {
            updateServiceStateUi(false)
            CoreForegroundService.stop(this)
            serverStatusText.text = "OpenClaw服务: 未连接"
            serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_off)
        }

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.addAccessibilityStateChangeListener(accListener)

        registerReceiver(linkReceiver, IntentFilter(CoreForegroundService.ACTION_LINK_STATE))
        registerReceiver(accConnectedReceiver, IntentFilter(com.ws.wx_server.acc.MyAccessibilityService.ACTION_CONNECTED))
        registerReceiver(accDisconnectedReceiver, IntentFilter(com.ws.wx_server.acc.MyAccessibilityService.ACTION_DISCONNECTED))
    }

    override fun onResume() {
        super.onResume()
        promptedCaptureThisResume = false
        promptedOverlayThisResume = false
        updateAccessibilityStatus()
        updateServiceStateUi(ServiceStateStore.isRunning(this))
        try {
            val i = Intent(CoreForegroundService.ACTION_QUERY_STATE)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) {
        }
        maybePromptScreenCapturePermission()
        ensureFloatingControlAlwaysOn()
    }

    override fun onDestroy() {
        super.onDestroy()
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.removeAccessibilityStateChangeListener(accListener)
        unregisterReceiver(linkReceiver)
        unregisterReceiver(accConnectedReceiver)
        unregisterReceiver(accDisconnectedReceiver)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCapturePermissionStore.save(this, resultCode, data)
                Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show()
                Logger.i("MediaProjection permission granted", tag = "LanBotOCR")
                if (pendingStartServiceAfterGrant) {
                    pendingStartServiceAfterGrant = false
                    updateServiceStateUi(true)
                    serverStatusText.text = "OpenClaw服务: 连接中"
                    serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_waiting)
                    CoreForegroundService.start(this)
                }
            } else {
                pendingStartServiceAfterGrant = false
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                Logger.w("MediaProjection permission denied", tag = "LanBotOCR")
            }
            return
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (canDrawOverlays()) {
                startFloatingControl()
                Toast.makeText(this, "Floating control enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityEnabled(this)
        statusText.text = if (enabled) "无障碍服务授权: 已授权" else "无障碍服务授权: 未授权"
        statusIcon.setImageResource(if (enabled) R.drawable.openclaw_ic_status_on else R.drawable.openclaw_ic_status_off)
    }

    private fun updateServiceStateUi(running: Boolean) {
        serviceStateText.text = if (running) "前台服务: 运行中" else "前台服务: 已停止"
        serviceStatusIcon.setImageResource(
            if (running) R.drawable.openclaw_ic_status_on else R.drawable.openclaw_ic_status_off
        )
        serviceStartBtn.isEnabled = !running
        serviceStopBtn.isEnabled = running
        if (!running) {
            serverStatusText.text = "OpenClaw服务: 未连接"
            serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_off)
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            Toast.makeText(this, "MediaProjection unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        } catch (_: Throwable) {
            Toast.makeText(this, "Failed to open permission dialog", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybePromptScreenCapturePermission() {
        if (TEMP_DISABLE_CAPTURE_AND_OCR) return
        if (promptedCaptureThisResume) return
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(this)
        if (cfg.captureStrategy != CAPTURE_STRATEGY_SCREEN_FIRST) return
        if (ScreenCapturePermissionStore.load(this) != null) return
        promptedCaptureThisResume = true
        Logger.i("Auto requesting MediaProjection permission on resume", tag = "LanBotOCR")
        requestScreenCapturePermission()
    }

    private fun requestOverlayPermissionAndStartFloatControl() {
        if (canDrawOverlays()) {
            startFloatingControl()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val uri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } catch (_: Throwable) {
                Toast.makeText(this, "Failed to open overlay settings", Toast.LENGTH_SHORT).show()
            }
        } else {
            startFloatingControl()
        }
    }

    private fun openInputMethodSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: Throwable) {
            Toast.makeText(this, "Failed to open keyboard settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTermuxTerminal() {
        try {
            val intent = Intent()
            intent.setClassName(this, "com.termux.app.TermuxActivity")
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Failed to open terminal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm == null) {
            Toast.makeText(this, "InputMethodManager unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            imm.showInputMethodPicker()
        } catch (_: Throwable) {
            Toast.makeText(this, "Failed to show keyboard picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFloatingControl() {
        try {
            val serviceIntent = Intent(this, FloatingControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Throwable) {
            Toast.makeText(this, "Failed to start floating control", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureFloatingControlAlwaysOn() {
        if (canDrawOverlays()) {
            startFloatingControl()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !promptedOverlayThisResume) {
            promptedOverlayThisResume = true
            requestOverlayPermissionAndStartFloatControl()
        }
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val state = intent?.getStringExtra(CoreForegroundService.EXTRA_STATE) ?: return
            when (state) {
                "connecting" -> {
                    serverStatusText.text = "OpenClaw服务: 连接中"
                    serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_waiting)
                }
                "connected" -> {
                    serverStatusText.text = "OpenClaw服务: 已连接"
                    serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_on)
                }
                "disconnected" -> {
                    serverStatusText.text = "OpenClaw服务: 未连接"
                    serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_off)
                }
                "failed" -> {
                    serverStatusText.text = "OpenClaw服务: 连接失败"
                    serverStatusIcon.setImageResource(R.drawable.openclaw_ic_status_off)
                }
            }
        }
    }

    private val accConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateAccessibilityStatus()
        }
    }

    private val accDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateAccessibilityStatus()
        }
    }

    companion object {
        private const val TEMP_DISABLE_CAPTURE_AND_OCR = true
        private const val REQUEST_SCREEN_CAPTURE = 7311
        private const val REQUEST_OVERLAY_PERMISSION = 7312
    }
}
