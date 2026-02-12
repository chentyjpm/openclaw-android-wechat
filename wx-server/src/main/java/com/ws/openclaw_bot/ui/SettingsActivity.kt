package com.ws.wx_server.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.ws.wx_server.R
import com.ws.wx_server.link.CAPTURE_STRATEGY_NODE_ONLY
import com.ws.wx_server.link.CAPTURE_STRATEGY_SCREEN_FIRST
import com.ws.wx_server.link.LinkConfigStore
import com.ws.wx_server.link.ServerConfig

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.openclaw_activity_settings)

        val host = findViewById<EditText>(R.id.et_host)
        val port = findViewById<EditText>(R.id.et_port)
        val keepAlive = findViewById<EditText>(R.id.et_keepalive)
        val tls = findViewById<CheckBox>(R.id.cb_tls)
        val debug = findViewById<CheckBox>(R.id.cb_debug)
        val debugXml = findViewById<CheckBox>(R.id.cb_debug_xml)
        val captureScreen = findViewById<CheckBox>(R.id.cb_capture_screen)
        val ocrEnabled = findViewById<CheckBox>(R.id.cb_ocr_enabled)
        val tabScanKeyword = findViewById<EditText>(R.id.et_tabscan_keyword)
        val save = findViewById<Button>(R.id.btn_save)

        val cfg = LinkConfigStore.load(this)
        host.setText(cfg.host)
        port.setText(cfg.port.toString())
        keepAlive.setText(cfg.keepAliveSeconds.toString())
        tls.isChecked = cfg.useTls
        debug.isChecked = cfg.debugEvents
        debugXml.isChecked = cfg.debugXml
        captureScreen.isChecked = cfg.captureStrategy == CAPTURE_STRATEGY_SCREEN_FIRST
        ocrEnabled.isChecked = cfg.ocrEnabled
        tabScanKeyword.setText(cfg.tabScanForwardKeyword)

        save.setOnClickListener {
            val p = port.text.toString().toIntOrNull() ?: cfg.port
            val ka = keepAlive.text.toString().toIntOrNull() ?: cfg.keepAliveSeconds
            val keepAliveSeconds = ka.coerceAtLeast(20)
            val newCfg = ServerConfig(
                host = host.text.toString().ifBlank { cfg.host },
                port = p,
                keepAliveSeconds = keepAliveSeconds,
                useTls = tls.isChecked,
                debugEvents = debug.isChecked,
                debugXml = debugXml.isChecked,
                captureStrategy = if (captureScreen.isChecked) CAPTURE_STRATEGY_SCREEN_FIRST else CAPTURE_STRATEGY_NODE_ONLY,
                ocrEnabled = ocrEnabled.isChecked,
                tabScanForwardKeyword = tabScanKeyword.text.toString().trim(),
            )
            LinkConfigStore.save(this, newCfg)
            finish()
        }
    }
}
