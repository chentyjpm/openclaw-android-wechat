package com.ws.wx_server.ui

import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.widget.Button
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ws.wx_server.core.CoreForegroundService
import com.ws.wx_server.core.ServiceStateStore
import com.ws.wx_server.util.isAccessibilityEnabled
import com.ws.wx_server.util.openAccessibilitySettings
import com.ws.wx_server.R

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var openSettingsBtn: Button
    private lateinit var serviceStateText: TextView
    private lateinit var serviceStartBtn: Button
    private lateinit var serviceStopBtn: Button
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var serverStatusIcon: android.widget.ImageView
    private lateinit var serverStatusText: TextView
    private lateinit var openServerSettings: Button
    private lateinit var openUsageAccess: Button
    private lateinit var recentPkgText: TextView
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var debugXmlSwitch: SwitchCompat

    private val accListener = AccessibilityManager.AccessibilityStateChangeListener {
        updateAccessibilityStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kimi_activity_main)

        statusText = findViewById(R.id.tv_status)
        statusIcon = findViewById(R.id.iv_status)
        serverStatusIcon = findViewById(R.id.iv_server_status)
        serverStatusText = findViewById(R.id.tv_server_status)
        openSettingsBtn = findViewById(R.id.btn_open_settings)
        serviceStateText = findViewById(R.id.tv_service_state)
        serviceStartBtn = findViewById(R.id.btn_service_start)
        serviceStopBtn = findViewById(R.id.btn_service_stop)
        debugSwitch = findViewById(R.id.switch_debug)
        debugXmlSwitch = findViewById(R.id.switch_debug_xml)
        openServerSettings = findViewById(R.id.btn_open_server_settings)
        openUsageAccess = findViewById(R.id.btn_open_usage_access)
        recentPkgText = findViewById(R.id.tv_recent_pkg)
        updateServiceStateUi(ServiceStateStore.isRunning(this))

        openSettingsBtn.setOnClickListener {
            openAccessibilitySettings(this)
        }

        openServerSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        openUsageAccess.setOnClickListener {
            com.ws.wx_server.util.openUsageAccessSettings(this)
        }

        serviceStartBtn.setOnClickListener {
            updateServiceStateUi(true)
            serverStatusText.text = "服务器连接：连接中…"
            serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_waiting)
            CoreForegroundService.start(this)
        }

        serviceStopBtn.setOnClickListener {
            updateServiceStateUi(false)
            CoreForegroundService.stop(this)
            serverStatusText.text = "服务器连接：未连接"
            serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_off)
        }

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.addAccessibilityStateChangeListener(accListener)

        // listen link status/messages
        registerReceiver(linkReceiver, IntentFilter(CoreForegroundService.ACTION_LINK_STATE))
        // Accessibility connected hint (extra safety to sync UI on first enable)
        registerReceiver(accConnectedReceiver, IntentFilter(com.ws.wx_server.acc.MyAccessibilityService.ACTION_CONNECTED))
        registerReceiver(accDisconnectedReceiver, IntentFilter(com.ws.wx_server.acc.MyAccessibilityService.ACTION_DISCONNECTED))

        // Initialize debug switches from config and persist changes
        val cfg0 = com.ws.wx_server.link.LinkConfigStore.load(this)
        debugSwitch.isChecked = cfg0.debugEvents
        debugXmlSwitch.isChecked = cfg0.debugXml
        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            val cur = com.ws.wx_server.link.LinkConfigStore.load(this)
            com.ws.wx_server.link.LinkConfigStore.save(this, cur.copy(debugEvents = isChecked))
        }
        debugXmlSwitch.setOnCheckedChangeListener { _, isChecked ->
            val cur = com.ws.wx_server.link.LinkConfigStore.load(this)
            com.ws.wx_server.link.LinkConfigStore.save(this, cur.copy(debugXml = isChecked))
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateRecentPkg()
        updateServiceStateUi(ServiceStateStore.isRunning(this))
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(this)
        debugSwitch.isChecked = cfg.debugEvents
        debugXmlSwitch.isChecked = cfg.debugXml
        // Ask the service to emit its latest link state so UI can sync
        try {
            val i = Intent(CoreForegroundService.ACTION_QUERY_STATE)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.removeAccessibilityStateChangeListener(accListener)
        unregisterReceiver(linkReceiver)
        unregisterReceiver(accConnectedReceiver)
        unregisterReceiver(accDisconnectedReceiver)
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityEnabled(this)
        statusText.text = if (enabled) "无障碍权限状态：已开启" else "无障碍权限状态：未开启"
        statusIcon.setImageResource(if (enabled) R.drawable.kimi_ic_status_on else R.drawable.kimi_ic_status_off)
    }

    private fun updateRecentPkg() {
        val has = com.ws.wx_server.util.isUsageAccessGranted(this)
        if (!has) {
            recentPkgText.text = "未授权"
            return
        }
        val pkg = com.ws.wx_server.util.getLatestForegroundPackage(this, 60_000) ?: "未知"
        recentPkgText.text = pkg
    }

    private fun updateServiceStateUi(running: Boolean) {
        serviceStateText.text = if (running) "前台服务：运行中" else "前台服务：已停止"
        serviceStartBtn.isEnabled = !running
        serviceStopBtn.isEnabled = running
        if (!running) {
            serverStatusText.text = "服务器连接：未连接"
            serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_off)
        }
    }

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val state = intent?.getStringExtra(CoreForegroundService.EXTRA_STATE) ?: return
            when (state) {
                "connecting" -> {
                    serverStatusText.text = "服务器连接：连接中…"
                    serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_waiting)
                }
                "connected" -> {
                    serverStatusText.text = "服务器连接：已连接"
                    serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_on)
                }
                "disconnected" -> {
                    serverStatusText.text = "服务器连接：未连接"
                    serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_off)
                }
                "failed" -> {
                    serverStatusText.text = "服务器连接：连接失败"
                    serverStatusIcon.setImageResource(R.drawable.kimi_ic_status_off)
                }
            }
        }
    }

    private val accConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            // When service reports connected, refresh the UI status immediately
            updateAccessibilityStatus()
        }
    }

    private val accDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateAccessibilityStatus()
        }
    }
}
