package com.ws.wx_server.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.ws.wx_server.R
import com.ws.wx_server.link.LinkConfigStore
import com.ws.wx_server.link.ServerConfig

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kimi_activity_settings)

        val host = findViewById<EditText>(R.id.et_host)
        val port = findViewById<EditText>(R.id.et_port)
        val keepAlive = findViewById<EditText>(R.id.et_keepalive)
        val tls = findViewById<CheckBox>(R.id.cb_tls)
        val debug = findViewById<CheckBox>(R.id.cb_debug)
        val debugXml = findViewById<CheckBox>(R.id.cb_debug_xml)
        val save = findViewById<Button>(R.id.btn_save)

        val cfg = LinkConfigStore.load(this)
        host.setText(cfg.host)
        port.setText(cfg.port.toString())
        keepAlive.setText(cfg.keepAliveSeconds.toString())
        tls.isChecked = cfg.useTls
        debug.isChecked = cfg.debugEvents
        debugXml.isChecked = cfg.debugXml

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
                debugXml = debugXml.isChecked
            )
            LinkConfigStore.save(this, newCfg)
            finish()
        }
    }
}
