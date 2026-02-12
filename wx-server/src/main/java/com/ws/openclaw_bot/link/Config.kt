package com.ws.wx_server.link

import android.content.Context
import android.content.SharedPreferences

const val CAPTURE_STRATEGY_SCREEN_FIRST = "screen_first"
const val CAPTURE_STRATEGY_NODE_ONLY = "node_only"

data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 18789,
    val keepAliveSeconds: Int = 60,
    val useTls: Boolean = false,
    val debugEvents: Boolean = false,
    val debugXml: Boolean = false,
    val captureStrategy: String = CAPTURE_STRATEGY_SCREEN_FIRST,
    val ocrEnabled: Boolean = true,
    val tabScanForwardKeyword: String = "@龙虾钳",
)

object LinkConfigStore {
    private const val PREFS = "lanbot_cfg"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_KEEPALIVE = "keepalive_seconds"
    private const val K_TLS = "tls"
    private const val K_DEBUG_EVENTS = "debug"
    private const val K_DEBUG_XML = "debug_xml"
    private const val K_CAPTURE_STRATEGY = "capture_strategy"
    private const val K_OCR_ENABLED = "ocr_enabled"
    private const val K_TABSCAN_FORWARD_KEYWORD = "tabscan_forward_keyword"

    fun load(ctx: Context): ServerConfig {
        val sp = prefs(ctx)
        val def = ServerConfig()
        return ServerConfig(
            host = sp.getString(K_HOST, null) ?: def.host,
            port = sp.getInt(K_PORT, def.port),
            keepAliveSeconds = sp.getInt(K_KEEPALIVE, def.keepAliveSeconds),
            useTls = sp.getBoolean(K_TLS, def.useTls),
            debugEvents = sp.getBoolean(K_DEBUG_EVENTS, def.debugEvents),
            debugXml = sp.getBoolean(K_DEBUG_XML, def.debugXml),
            captureStrategy = sp.getString(K_CAPTURE_STRATEGY, def.captureStrategy)
                ?.takeIf { it == CAPTURE_STRATEGY_SCREEN_FIRST || it == CAPTURE_STRATEGY_NODE_ONLY }
                ?: def.captureStrategy,
            ocrEnabled = sp.getBoolean(K_OCR_ENABLED, def.ocrEnabled),
            tabScanForwardKeyword = sp.getString(K_TABSCAN_FORWARD_KEYWORD, def.tabScanForwardKeyword)
                ?: def.tabScanForwardKeyword,
        )
    }

    fun save(ctx: Context, cfg: ServerConfig) {
        prefs(ctx).edit()
            .putString(K_HOST, cfg.host)
            .putInt(K_PORT, cfg.port)
            .putInt(K_KEEPALIVE, cfg.keepAliveSeconds)
            .putBoolean(K_TLS, cfg.useTls)
            .putBoolean(K_DEBUG_EVENTS, cfg.debugEvents)
            .putBoolean(K_DEBUG_XML, cfg.debugXml)
            .putString(K_CAPTURE_STRATEGY, cfg.captureStrategy)
            .putBoolean(K_OCR_ENABLED, cfg.ocrEnabled)
            .putString(K_TABSCAN_FORWARD_KEYWORD, cfg.tabScanForwardKeyword)
            .apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
