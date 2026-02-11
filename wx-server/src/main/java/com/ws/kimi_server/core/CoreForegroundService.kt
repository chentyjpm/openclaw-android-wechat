package com.ws.wx_server.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Build
import android.os.SystemClock
import android.os.IBinder
import android.provider.MediaStore
import android.net.Uri
import android.content.ContentValues
import androidx.core.app.NotificationCompat
import com.ws.wx_server.link.Link
import com.ws.wx_server.link.http.ClientAck
import com.ws.wx_server.link.http.ClientEnvelope
import com.ws.wx_server.link.http.NavigateCommand
import com.ws.wx_server.link.http.RestLink
import com.ws.wx_server.link.http.SendTextCommand
import com.ws.wx_server.link.http.ServerTask
import com.ws.wx_server.link.LinkConfigStore
import com.ws.wx_server.exec.TaskExecutor
import com.ws.wx_server.ui.MainActivity
import com.ws.wx_server.util.Logger
import com.ws.wx_server.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale


class CoreForegroundService : Service() {
    private val channelId = "lanbot_fg"
    private val notificationId = 1001

    private lateinit var link: Link
    private lateinit var executor: TaskExecutor
    private val httpClient by lazy { OkHttpClient() }
    private var snapshotReceiver: BroadcastReceiver? = null
    private var nm: NotificationManager? = null
    private var lastNotifText: String = "Connecting…"
    private var lastState: String = "disconnected"
    private var lastError: String? = null
    private var commandReceiver: BroadcastReceiver? = null
    private fun broadcast(action: String, extras: Intent.() -> Unit = {}) {
        val i = Intent(action)
        i.extras?.clear()
        i.apply(extras)
        sendBroadcast(i)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cfg = LinkConfigStore.load(this)
        link = RestLink(cfg)
        link.onEnvelope = { envelope ->
            Logger.i("server task: ${envelope.type}")
            handleServerTask(envelope)
        }
        link.onDebugText = { _ -> }
        link.onState = { state ->
            Logger.i("link state: $state")
            broadcast(ACTION_LINK_STATE) { putExtra(EXTRA_STATE, state) }
            lastState = state
            when (state) {
                "connecting" -> updateNotificationText("Connecting…")
                "connected" -> updateNotificationText("Connected")
                "failed" -> updateNotificationText("Connection failed")
                "disconnected" -> updateNotificationText("Disconnected")
            }
        }
        link.onError = { err ->
            Logger.e("link error: $err")
            broadcast(ACTION_LINK_ERROR) { putExtra(EXTRA_ERROR, err) }
            lastError = err
            updateNotificationText("Error: connection issue")
        }
        com.ws.wx_server.exec.TaskBridge.sendEnvelope = { envelope ->
            link.sendEnvelope(envelope)
        }
        executor = TaskExecutor()
        // Receive snapshots from AccessibilityService
        val filter = IntentFilter().apply { addAction(com.ws.wx_server.acc.MyAccessibilityService.ACTION_SNAPSHOT) }
        snapshotReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == com.ws.wx_server.acc.MyAccessibilityService.ACTION_SNAPSHOT) {
                    if (!ServiceStateStore.isRunning(this@CoreForegroundService)) return
                    if (lastState == "disconnected" || lastState == "failed") {
                        link.requestReconnect("snapshot")
                    }
                    val pkg = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_PKG) ?: ""
                    val cls = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_CLS) ?: ""
                    val text = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_TEXT) ?: ""
                    val debug = isDebugEnabled()
                    val json = org.json.JSONObject().apply {
                        put("type", "snapshot")
                        put("ts", System.currentTimeMillis())
                        put("pkg", pkg)
                        put("cls", cls)
                        put("text", text)
                        // attach structured wechat data if available
                        val wx = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_WECHAT_JSON)
                        if (!wx.isNullOrBlank()) {
                            try { put("wechat", org.json.JSONObject(wx)) } catch (_: Throwable) {}
                        }
                        val capture = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_CAPTURE_JSON)
                        if (!capture.isNullOrBlank()) {
                            try { put("capture", org.json.JSONObject(capture)) } catch (_: Throwable) {}
                        }
                        val ocr = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_OCR_JSON)
                        if (!ocr.isNullOrBlank()) {
                            try { put("ocr", org.json.JSONObject(ocr)) } catch (_: Throwable) {}
                        }
                    }
                    if (debug) {
                        link.sendText(json.toString())
                    }
                    // Forward the structured window state as a separate message for realtime logging on server
                    val state = intent.getStringExtra(com.ws.wx_server.acc.MyAccessibilityService.EXTRA_STATE_JSON)
                    if (!state.isNullOrBlank() && debug) {
                        link.sendText(state)
                    }
                }
            }
        }
        registerReceiver(snapshotReceiver, filter)

        // Commands from UI (e.g., query current state)
        val cmdFilter = IntentFilter().apply { addAction(ACTION_QUERY_STATE) }
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_QUERY_STATE) {
                    broadcast(ACTION_LINK_STATE) { putExtra(EXTRA_STATE, lastState) }
                    lastError?.let { broadcast(ACTION_LINK_ERROR) { putExtra(EXTRA_ERROR, it) } }
                }
            }
        }
        registerReceiver(commandReceiver, cmdFilter, RECEIVER_NOT_EXPORTED)
    }

    private fun handleServerTask(task: ServerTask) {
        Logger.i("handleServerTask id=${task.taskId} type=${task.type}")
        when (task.type) {
            "navigate" -> handleNavigateCommand(NavigateCommand.fromJson(task.payload))
            "send_text" -> handleSendTextCommand(SendTextCommand.fromJson(task.payload))
            "debug" -> link.onDebugText(task.payload.optString("message"))
            else -> Logger.w("unknown task type: ${task.type}")
        }
    }

    private fun handleNavigateCommand(nav: NavigateCommand) {
        val pkg = nav.pkg
        if (pkg.isBlank()) return
        val agent = com.ws.wx_server.exec.AccessibilityAgent()
        val preferRecents = when {
            nav.preferRecents -> true
            pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG && nav.argsJson.isBlank() -> true
            else -> false
        }
        var ok = false
        if (preferRecents) {
            ok = agent.nav_to_via_recents(this, pkg)
            if (!ok) ok = agent.nav_to_app(this, pkg)
        } else {
            ok = agent.nav_to_app(this, pkg)
            if (!ok) ok = agent.nav_to_via_recents(this, pkg)
        }
        Logger.i("nav_to $pkg -> $ok (preferRecents=$preferRecents)")
    }

    private fun handleSendTextCommand(cmd: SendTextCommand) {
        val requestId = cmd.requestId.takeIf { it.isNotBlank() }
        val chatId = cmd.chatId.takeIf { it.isNotBlank() }
        val targetTitle = cmd.targetTitle.takeIf { it.isNotBlank() }
        val rawMode = cmd.mode.ifBlank { "" }.lowercase(Locale.US)
        Logger.i(
            "handleSendTextCommand req=$requestId mode=$rawMode target=$targetTitle chatId=$chatId"
        )
        val mode = when {
            rawMode == "image" -> "image"
            rawMode == "latest_image" || rawMode == "send_latest_image" -> "latest_image"
            rawMode.isBlank() && cmd.imageUrl.isNotBlank() -> "image"
            else -> if (rawMode.isBlank()) "text" else rawMode
        }
        when (mode) {
            "image" -> handleImageCommand(requestId, cmd, targetTitle)
            "latest_image" -> {
                val ensured = if (targetTitle.isNullOrBlank()) true else com.ws.wx_server.apps.wechat.WeChatAgent.ensureChat(targetTitle)
                sendCommandAck(requestId, true, null, "QUEUED")
                val ok = if (ensured) {
                    com.ws.wx_server.apps.wechat.WeChatAgent.sendLatestImage { stage ->
                        if (stage != com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.SENT) {
                            sendCommandAck(requestId, true, null, mapImageStage(stage))
                        }
                    }
                } else false
                Logger.i("send_latest_image target=$targetTitle -> $ok")
                val error = when {
                    !ensured -> "target_not_found"
                    ok -> null
                    else -> "attachment_failed"
                }
                sendCommandAck(
                    requestId,
                    ok,
                    error,
                    if (ok) "SENT" else "FAILED",
                )
            }
            else -> {
                val text = cmd.text
                val (ok, error) = if (text.isNullOrBlank()) {
                    false to "empty_text"
                } else {
                    val ensured = if (targetTitle.isNullOrBlank()) true else com.ws.wx_server.apps.wechat.WeChatAgent.ensureChat(targetTitle)
                    if (!ensured) {
                        false to "target_not_found"
                    } else {
                        val sendOk = com.ws.wx_server.apps.wechat.WeChatAgent.sendText(text)
                        sendOk to (if (sendOk) null else "input_or_send_failed")
                    }
                }
                Logger.i("send_text target=$targetTitle chatId=$chatId len=${text?.length ?: 0} -> $ok")
                sendCommandAck(
                    requestId,
                    ok,
                    error,
                    if (ok) "SENT" else "FAILED",
                )
            }
        }
    }

    private fun handleImageCommand(
        requestId: String?,
        cmd: SendTextCommand,
        targetTitle: String?,
    ) {
        val mimeType = cmd.imageMimeType.takeIf { it.isNotBlank() } ?: "image/jpeg"
        val imageUrl = cmd.imageUrl.takeIf { it.isNotBlank() }
        val imageData = null
        if (!targetTitle.isNullOrBlank()) {
            val ensureOk = com.ws.wx_server.apps.wechat.WeChatAgent.ensureChat(targetTitle)
            if (!ensureOk) {
                sendCommandAck(requestId, false, "target_not_found", "FAILED")
                return
            }
        }
        Thread {
            var ok = false
            var error: String? = null
            sendCommandAck(requestId, true, null, "QUEUED")
            try {
                val bytes = imageData ?: imageUrl?.let { downloadImage(it) }
                if (bytes == null) {
                    error = "no_image_payload"
                } else {
                    val uri = saveImageToGallery(bytes, mimeType)
                    if (uri == null) {
                        error = "save_failed"
                    } else {
                        val persisted = waitForImagePersisted(uri, IMAGE_PERSIST_TIMEOUT_MS)
                        if (!persisted) {
                            error = "image_not_persisted"
                        } else {
                            sendCommandAck(requestId, true, null, "SAVED")
                            SystemClock.sleep(1500)
                            ok = com.ws.wx_server.apps.wechat.WeChatAgent.sendLatestImage { stage ->
                                if (stage != com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.SENT) {
                                    sendCommandAck(requestId, true, null, mapImageStage(stage))
                                }
                            }
                            if (!ok) {
                                error = "album_send_failed"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("send_image exception: ${e.message}", e)
                error = e.message ?: "exception"
            }
            sendCommandAck(
                requestId,
                ok,
                error,
                if (ok) "SENT" else "FAILED",
            )
        }.start()
    }

    private fun sendCommandAck(
        requestId: String?,
        ok: Boolean,
        error: String?,
        stage: String? = null,
    ) {
        if (requestId.isNullOrBlank()) return
        Logger.i("sendCommandAck req=$requestId ok=$ok stage=$stage error=$error")
        val ack = ClientAck(
            requestId = requestId,
            ok = ok,
            error = error,
            stage = stage,
            tsMs = System.currentTimeMillis(),
        )
        link.sendEnvelope(ClientEnvelope(ack = ack))
    }

    private fun mapImageStage(stage: com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage): String {
        return when (stage) {
            com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.ATTACH_PANEL -> "ATTACH_PANEL"
            com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.ALBUM_OPEN -> "ALBUM_OPEN"
            com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.SELECTED -> "SELECTED"
            com.ws.wx_server.apps.wechat.WeChatAgent.ImageSendStage.SENT -> "SENT"
        }
    }

    private fun isDebugEnabled(): Boolean = LinkConfigStore.load(this).debugEvents

    private fun downloadImage(url: String): ByteArray? {
        repeat(IMAGE_DOWNLOAD_RETRY) { attempt ->
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.bytes()
                    }
                    Logger.w("downloadImage failed: HTTP ${response.code} (attempt=${attempt + 1})")
                }
            } catch (e: IOException) {
                Logger.e("downloadImage error: ${e.message} (attempt=${attempt + 1})", e)
            }
            SystemClock.sleep(IMAGE_DOWNLOAD_RETRY_DELAY_MS)
        }
        return null
    }

    private fun waitForImagePersisted(uri: Uri, timeoutMs: Long): Boolean {
        val resolver = contentResolver
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (isImageReadable(resolver, uri)) return true
            SystemClock.sleep(200)
        }
        return false
    }

    private fun isImageReadable(resolver: ContentResolver, uri: Uri): Boolean {
        try {
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.IS_PENDING,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val pendingIdx = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val pending = if (pendingIdx >= 0) cursor.getInt(pendingIdx) else 0
                    if (pending == 0 && size > 0L) return true
                }
            }
        } catch (_: Throwable) {
        }
        try {
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                val size = fd.statSize
                if (size > 0L) return true
            }
        } catch (_: Throwable) {
        }
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1)
                input.read(buf) > 0
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun saveImageToGallery(data: ByteArray, mimeType: String): Uri? {
        val resolver = contentResolver
        val extension = mimeTypeToExtension(mimeType)
        val displayName = "lanbot_${System.currentTimeMillis()}$extension"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/LanBot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Logger.e("saveImageToGallery error: ${e.message}", e)
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            null
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String {
        val lower = mimeType.lowercase(Locale.US)
        return when {
            lower.contains("png") -> ".png"
            lower.contains("webp") -> ".webp"
            lower.contains("gif") -> ".gif"
            lower.contains("bmp") -> ".bmp"
            else -> ".jpg"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ServiceStateStore.isRunning(this)) {
            Logger.i("CoreForegroundService start ignored: not running")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(notificationId, buildNotification())
        Logger.i("CoreForegroundService started")
        link.requestReconnect("start", immediate = true)
        // Always echo current state to help UI sync when user re-enters from notification
        broadcast(ACTION_LINK_STATE) { putExtra(EXTRA_STATE, lastState) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        link.close()
        com.ws.wx_server.exec.TaskBridge.sendEnvelope = null
        snapshotReceiver?.let { unregisterReceiver(it) }
        commandReceiver?.let { unregisterReceiver(it) }
        Logger.i("CoreForegroundService destroyed")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val chan = NotificationChannel(channelId, "LanBot", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(chan)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("LanBot")
            .setContentText(lastNotifText)
            .setSmallIcon(R.drawable.kimi_ic_status_on)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationText(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        try {
            nm?.notify(notificationId, buildNotification())
        } catch (_: Throwable) { }
    }

    companion object {
        const val ACTION_LINK_STATE = "com.ws.wx_server.LINK_STATE"
        const val EXTRA_STATE = "state"
        const val ACTION_LINK_ERROR = "com.ws.wx_server.LINK_ERROR"
        const val EXTRA_ERROR = "error"
        const val ACTION_QUERY_STATE = "com.ws.wx_server.QUERY_STATE"
        private const val IMAGE_PERSIST_TIMEOUT_MS = 2500L
        private const val IMAGE_DOWNLOAD_RETRY = 5
        private const val IMAGE_DOWNLOAD_RETRY_DELAY_MS = 3000L
        fun start(context: Context) {
            ServiceStateStore.setRunning(context, true)
            val i = Intent(context, CoreForegroundService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            ServiceStateStore.setRunning(context, false)
            val i = Intent(context, CoreForegroundService::class.java)
            context.stopService(i)
        }
    }
}
