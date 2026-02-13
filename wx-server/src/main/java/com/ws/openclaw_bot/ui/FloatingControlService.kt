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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ws.wx_server.R
import com.ws.wx_server.acc.MyAccessibilityService
import com.ws.wx_server.core.CoreForegroundService
import com.ws.wx_server.core.ServiceStateStore
import com.ws.wx_server.ime.LanBotImeService
import com.ws.wx_server.link.LinkConfigStore
import com.ws.wx_server.util.Logger
import com.ws.wx_server.util.isAccessibilityEnabled
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var accStatusIcon: ImageView? = null
    private var botStatusIcon: ImageView? = null
    private var foregroundStatusIcon: ImageView? = null
    private var lastBotState: String = "disconnected"
    private var logPanel: View? = null
    private var logScrollView: ScrollView? = null
    private var logContent: TextView? = null
    private var logToggleBtn: Button? = null
    private var wsLogSocket: WebSocket? = null
    private val wsLogLines = ArrayDeque<String>()
    private var logPanelVisible = true
    private var lastStatusSummary: String = ""
    private var wsLogReqSeq = 0L
    private var wsTailCursor: Long? = null
    private var wsHandshakeReady = false
    private var wsTailInFlight = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wsLogClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private val wsReconnectRunnable = Runnable {
        if (logPanelVisible && wsLogSocket == null) {
            connectWsLog()
        }
    }
    private val wsTailPollRunnable = Runnable {
        requestLogsTail()
    }

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
        if (intent?.action == ACTION_RECONNECT_LOG_WS) {
            reconnectWsLog("manual_reconnect")
        }
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
        if (logPanelVisible) {
            connectWsLog()
        }
        Logger.i("Floating control shown")
    }

    private fun removeOverlay() {
        stopWsLog("overlay_removed")
        mainHandler.removeCallbacks(wsReconnectRunnable)
        mainHandler.removeCallbacks(wsTailPollRunnable)
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
        logPanel = null
        logScrollView = null
        logContent = null
        logToggleBtn = null
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
        logToggleBtn = view.findViewById(R.id.btn_float_log_toggle)
        logPanel = view.findViewById(R.id.layout_float_log_panel)
        logScrollView = view.findViewById(R.id.sv_float_log)
        logContent = view.findViewById(R.id.tv_float_log_content)
        val logClearBtn = view.findViewById<Button>(R.id.btn_float_log_clear)
        logPanel?.visibility = if (logPanelVisible) View.VISIBLE else View.GONE
        logToggleBtn?.text = if (logPanelVisible) "收起日志" else "日志"

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
        logToggleBtn?.setOnClickListener { toggleLogPanel() }
        logClearBtn.setOnClickListener {
            wsLogLines.clear()
            renderLogPanel()
            appendLog("log buffer cleared")
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
        val botStateLabel = when (botState) {
            "connected", "connecting" -> "on"
            else -> "off"
        }

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
        val summary = "status acc=${if (accessibilityEnabled) "on" else "off"} bot=$botStateLabel fg=${if (foregroundRunning) "on" else "off"}"
        if (summary != lastStatusSummary) {
            lastStatusSummary = summary
            appendLog(summary)
        }
    }

    private fun toggleLogPanel() {
        logPanelVisible = !logPanelVisible
        logPanel?.visibility = if (logPanelVisible) View.VISIBLE else View.GONE
        logToggleBtn?.text = if (logPanelVisible) "收起日志" else "日志"
        if (logPanelVisible) {
            appendLog("open log panel")
            connectWsLog()
        } else {
            stopWsLog("panel_hidden")
        }
    }

    private fun buildWsLogUrl(): String {
        val cfg = LinkConfigStore.load(applicationContext)
        val scheme = if (cfg.useTls) "wss" else "ws"
        val rawHost = cfg.host.trim().ifBlank { "127.0.0.1" }
        val normalized = if (
            rawHost.startsWith("http://") ||
            rawHost.startsWith("https://") ||
            rawHost.startsWith("ws://") ||
            rawHost.startsWith("wss://")
        ) {
            rawHost
        } else {
            "http://$rawHost"
        }
        return runCatching {
            val uri = URI(normalized)
            val host = uri.host?.ifBlank { null } ?: "127.0.0.1"
            val port = if (uri.port > 0) uri.port else cfg.port
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            "$scheme://$host:$port$path"
        }.getOrElse {
            "$scheme://127.0.0.1:${cfg.port}/"
        }
    }

    private fun connectWsLog() {
        if (!logPanelVisible || wsLogSocket != null) return
        val url = buildWsLogUrl()
        appendLog("ws connecting: $url")
        val req = Request.Builder().url(url).build()
        wsLogSocket = wsLogClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                appendLog("ws connected")
                mainHandler.removeCallbacks(wsReconnectRunnable)
                wsHandshakeReady = false
                wsTailInFlight = false
                wsTailCursor = null
                sendGatewayConnect(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsTextFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                appendLog("<< [binary ${bytes.size} bytes]")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("ws closing: code=$code reason=${shortenForLog(reason)}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("ws closed: code=$code reason=${shortenForLog(reason)}")
                wsLogSocket = null
                wsHandshakeReady = false
                wsTailInFlight = false
                mainHandler.removeCallbacks(wsTailPollRunnable)
                scheduleWsReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                appendLog("ws failure: ${t.message ?: "unknown_error"}")
                wsLogSocket = null
                wsHandshakeReady = false
                wsTailInFlight = false
                mainHandler.removeCallbacks(wsTailPollRunnable)
                scheduleWsReconnect()
            }
        })
    }

    private fun scheduleWsReconnect() {
        if (!logPanelVisible) return
        mainHandler.removeCallbacks(wsReconnectRunnable)
        mainHandler.postDelayed(wsReconnectRunnable, 3000L)
        appendLog("ws reconnect in 3s")
    }

    private fun stopWsLog(reason: String) {
        mainHandler.removeCallbacks(wsReconnectRunnable)
        mainHandler.removeCallbacks(wsTailPollRunnable)
        wsTailInFlight = false
        wsHandshakeReady = false
        val ws = wsLogSocket ?: return
        wsLogSocket = null
        appendLog("ws disconnect: $reason")
        runCatching { ws.close(1000, reason.take(64)) }
    }

    private fun reconnectWsLog(reason: String) {
        stopWsLog(reason)
        wsTailCursor = null
        wsTailInFlight = false
        wsHandshakeReady = false
        if (logPanelVisible) {
            appendLog("ws reconnect requested")
            connectWsLog()
        } else {
            appendLog("ws reconnect requested (log panel hidden)")
        }
    }

    private fun sendGatewayConnect(webSocket: WebSocket) {
        val reqId = nextWsReqId("connect")
        val authToken = resolveGatewayToken()
        if (authToken.isNullOrBlank()) {
            appendLog("openclaw token is empty; connect may be rejected")
        } else {
            appendLog("openclaw token loaded")
        }

        val connectParams = JSONObject()
            .put("minProtocol", 1)
            .put("maxProtocol", 99)
            .put(
                "client",
                JSONObject()
                    .put("id", "openclaw-android")
                    .put("displayName", "OpenClawBot Floating")
                    .put("version", "1.0.0")
                    .put("platform", "android")
                    .put("mode", "ui"),
            )
            .put("caps", JSONArray())
            .put("role", "operator")
            .put("scopes", JSONArray().put("operator.read"))
        if (!authToken.isNullOrBlank()) {
            connectParams.put(
                "auth",
                JSONObject()
                    .put("token", authToken)
                    .put("password", authToken),
            )
        }

        val frameJson = JSONObject()
        frameJson.put("type", "req")
        frameJson.put("id", reqId)
        frameJson.put("method", "connect")
        frameJson.put("params", connectParams)
        val frame: String = frameJson.toString()

        val sent = runCatching { webSocket.send(frame) }.getOrDefault(false)
        if (sent) {
            appendLog(">> connect")
        } else {
            appendLog("failed to send connect request")
        }
    }

    private fun handleWsTextFrame(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null) {
            appendLog("<< ${shortenForLog(text)}")
            return
        }
        when (obj.optString("type")) {
            "res" -> handleWsResponse(obj)
            "event" -> handleWsEvent(obj)
            else -> appendLog("<< ${shortenForLog(text)}")
        }
    }

    private fun handleWsEvent(obj: JSONObject) {
        val eventName = obj.optString("event")
        when (eventName) {
            "connect.challenge" -> appendLog("ws challenge received")
            else -> appendLog("event: $eventName")
        }
    }

    private fun handleWsResponse(obj: JSONObject) {
        val id = obj.optString("id")
        when {
            id.startsWith("connect-") -> handleConnectResponse(obj)
            id.startsWith("logs-tail-") -> handleLogsTailResponse(obj)
            else -> appendLog("response: id=$id ok=${obj.optBoolean("ok")}")
        }
    }

    private fun handleConnectResponse(obj: JSONObject) {
        if (!obj.optBoolean("ok")) {
            val err = obj.optJSONObject("error")?.optString("message") ?: "connect failed"
            appendLog("connect rejected: ${shortenForLog(err)}")
            return
        }

        wsHandshakeReady = true
        appendLog("gateway handshake ok")

        val methods = obj.optJSONObject("payload")
            ?.optJSONObject("features")
            ?.optJSONArray("methods")
        var hasLogsTail = false
        if (methods != null) {
            for (i in 0 until methods.length()) {
                if (methods.optString(i) == "logs.tail") {
                    hasLogsTail = true
                    break
                }
            }
        }

        if (!hasLogsTail) {
            appendLog("gateway method logs.tail unavailable")
            return
        }
        requestLogsTail()
    }

    private fun requestLogsTail() {
        if (!logPanelVisible || !wsHandshakeReady || wsTailInFlight) return
        val ws = wsLogSocket ?: return

        val reqId = nextWsReqId("logs-tail")
        val params = JSONObject()
            .put("limit", 80)
            .put("maxBytes", 200000)
        wsTailCursor?.let { params.put("cursor", it) }

        val frame = JSONObject()
            .put("type", "req")
            .put("id", reqId)
            .put("method", "logs.tail")
            .put("params", params)
            .toString()

        val sent = runCatching { ws.send(frame) }.getOrDefault(false)
        if (!sent) {
            appendLog("failed to send logs.tail request")
            scheduleNextTailPoll(2000L)
            return
        }
        wsTailInFlight = true
    }

    private fun handleLogsTailResponse(obj: JSONObject) {
        wsTailInFlight = false
        if (!obj.optBoolean("ok")) {
            val err = obj.optJSONObject("error")?.optString("message") ?: "logs.tail failed"
            appendLog("logs.tail failed: ${shortenForLog(err)}")
            scheduleNextTailPoll(2000L)
            return
        }

        val payload = obj.optJSONObject("payload")
        if (payload == null) {
            appendLog("logs.tail returned empty payload")
            scheduleNextTailPoll()
            return
        }

        if (payload.has("cursor")) {
            wsTailCursor = payload.optLong("cursor")
        }
        if (payload.optBoolean("reset")) {
            appendLog("log cursor reset")
        }
        if (payload.optBoolean("truncated")) {
            appendLog("log output truncated to latest chunk")
        }

        val lines = payload.optJSONArray("lines")
        if (lines != null && lines.length() > 0) {
            for (i in 0 until lines.length()) {
                val line = lines.optString(i).trim()
                if (line.isNotEmpty()) {
                    appendLog(line)
                }
            }
        }

        scheduleNextTailPoll()
    }

    private fun scheduleNextTailPoll(delayMs: Long = WS_TAIL_POLL_MS) {
        mainHandler.removeCallbacks(wsTailPollRunnable)
        if (!logPanelVisible || !wsHandshakeReady || wsLogSocket == null) return
        mainHandler.postDelayed(wsTailPollRunnable, delayMs)
    }

    private fun nextWsReqId(prefix: String): String {
        wsLogReqSeq += 1
        return "$prefix-$wsLogReqSeq"
    }

    private fun resolveGatewayToken(): String? {
        val configToken = LinkConfigStore.load(applicationContext).openclawToken.trim()
        if (configToken.isNotEmpty()) {
            return configToken
        }

        val files = linkedSetOf<File>()
        val appHome = File(applicationInfo.dataDir, "files/home")
        files.add(File(appHome, ".bashrc"))
        files.add(File(appHome, ".profile"))
        files.add(File(appHome, ".zshrc"))

        val envHome = System.getenv("HOME")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }
        if (envHome != null) {
            files.add(File(envHome, ".bashrc"))
            files.add(File(envHome, ".profile"))
            files.add(File(envHome, ".zshrc"))
        }

        for (file in files) {
            val token = parseGatewayToken(file)
            if (!token.isNullOrBlank()) {
                return token
            }
        }
        return null
    }

    private fun parseGatewayToken(file: File): String? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null

        val exportRe = Regex("""(?m)^\s*export\s+OPENCLAW_GATEWAY_TOKEN\s*=\s*(['\"]?)([^'\"\r\n]+)\1\s*$""")
        val direct = exportRe.find(text)?.groupValues?.getOrNull(2)?.trim()
        if (!direct.isNullOrEmpty()) {
            return direct
        }

        val fallbackRe = Regex("""(?m)^\s*OPENCLAW_GATEWAY_TOKEN\s*=\s*(['\"]?)([^'\"\r\n]+)\1\s*$""")
        return fallbackRe.find(text)?.groupValues?.getOrNull(2)?.trim()
    }

    private fun appendLog(raw: String) {
        val line = "[${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}] $raw"
        if (wsLogLines.size >= MAX_LOG_LINES) {
            wsLogLines.removeFirstOrNull()
        }
        wsLogLines.addLast(line)
        renderLogPanel()
    }

    private fun renderLogPanel() {
        val text = wsLogLines.joinToString(separator = "\n")
        rootView?.post {
            logContent?.text = text
            if (logPanelVisible) {
                logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun shortenForLog(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ')
        return if (oneLine.length <= 240) oneLine else oneLine.take(240) + "..."
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "lanbot_floating_control"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_LOG_LINES = 120
        private const val WS_TAIL_POLL_MS = 1200L
        const val ACTION_RECONNECT_LOG_WS = "com.ws.wx_server.FLOAT_RECONNECT_LOG_WS"
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
