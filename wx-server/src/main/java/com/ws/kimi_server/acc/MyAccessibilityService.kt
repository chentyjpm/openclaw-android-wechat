package com.ws.wx_server.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import com.ws.wx_server.capture.ScreenCaptureManager
import com.ws.wx_server.debug.AccessibilityDebug
import com.ws.wx_server.exec.TaskBridge
import com.ws.wx_server.link.CAPTURE_STRATEGY_SCREEN_FIRST
import com.ws.wx_server.ocr.PPOcrRecognizer
import com.ws.wx_server.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MyAccessibilityService : AccessibilityService() {
    private var lastSent = 0L
    private var lastCaptureAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFollowUp: Runnable? = null
    private var lastWindowKey: String? = null
    private var lastPkg: String = ""
    private var lastCls: String = ""
    private val ppOcrRecognizer by lazy { PPOcrRecognizer(applicationContext) }
    private var lastLoggedOcrText: String? = null
    private var lastLoggedOcrJson: String? = null
    private var commandReceiver: BroadcastReceiver? = null

    private var tabScanActive = false
    private var tabScanSeenFirst = false
    private var tabScanStartedAt = 0L
    private var tabScanSteps = 0
    private val tabScanEvents = mutableListOf<org.json.JSONObject>()
    private var tabScanTicker: Runnable? = null
    private var tabScanTimeout: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceHolder.service = this
        AccessibilityStateStore.setConnected(applicationContext, true)
        try {
            val info = serviceInfo
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        } catch (_: Throwable) { }
        Logger.i("Accessibility connected")
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(applicationContext)
        Logger.i("AccDebug config: events=${cfg.debugEvents} xml=${cfg.debugXml} capture=${cfg.captureStrategy}")
        if (!TEMP_DISABLE_CAPTURE_AND_OCR &&
            cfg.captureStrategy == CAPTURE_STRATEGY_SCREEN_FIRST &&
            cfg.ocrEnabled
        ) {
            Thread {
                val ok = ppOcrRecognizer.warmUp()
                Logger.i("NCNN OCR warmup result=$ok", tag = "LanBotOCR")
            }.start()
        } else if (TEMP_DISABLE_CAPTURE_AND_OCR) {
            Logger.i("Capture/OCR temporarily disabled", tag = "LanBotOCR")
        }
        try {
            val i = Intent(ACTION_CONNECTED)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) { }
        registerCommandReceiver()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityStateStore.setConnected(applicationContext, false)
        try {
            val i = Intent(ACTION_DISCONNECTED)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) { }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceHolder.service = null
        AccessibilityStateStore.setConnected(applicationContext, false)
        ScreenCaptureManager.release()
        unregisterCommandReceiver()
        stopTabScan("service_destroyed")
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = null
        Logger.i("Accessibility destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        AccessibilityDebug.onEvent(this, event)
        handleTabScanFocusEvent(event)
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
                val title = extractNotificationTitle(event)
                if (!title.isNullOrBlank()) {
                    com.ws.wx_server.apps.wechat.WeChatNotifyGate.handleNotification(applicationContext, title)
                }
            }
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        lastPkg = pkg
        lastCls = cls

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val key = "$pkg|$cls"
            if (key != lastWindowKey) {
                lastWindowKey = key
                lastSent = 0L
            }
            val result = emitSnapshot(force = true)
            when (result.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, force = false)
            }
            return
        }

        val result = emitSnapshot(force = false)
        when (result.status) {
            SnapshotStatus.SENT -> Unit
            SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, force = false)
        }
    }

    override fun onInterrupt() {
        // No-op for now
    }

    private fun registerCommandReceiver() {
        if (commandReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_START_TAB_SCAN -> startTabScan()
                    ACTION_STOP_TAB_SCAN -> stopTabScan("manual_stop")
                }
            }
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACTION_START_TAB_SCAN)
                addAction(ACTION_STOP_TAB_SCAN)
            },
            RECEIVER_NOT_EXPORTED,
        )
        commandReceiver = receiver
    }

    private fun unregisterCommandReceiver() {
        val receiver = commandReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        commandReceiver = null
    }

    private fun startTabScan() {
        if (tabScanActive) {
            Logger.i("TabScan already running", tag = "LanBotTabScan")
            return
        }
        tabScanActive = true
        tabScanSeenFirst = false
        tabScanSteps = 0
        tabScanStartedAt = System.currentTimeMillis()
        tabScanEvents.clear()
        Logger.i("TabScan started: stepping TAB every 250ms and listening VIEW_FOCUSED EditText", tag = "LanBotTabScan")
        scheduleTabTick()
        tabScanTimeout = Runnable { stopTabScan("timeout") }.also {
            handler.postDelayed(it, TAB_SCAN_MAX_DURATION_MS)
        }
    }

    private fun stopTabScan(reason: String) {
        if (!tabScanActive && tabScanEvents.isEmpty()) return
        tabScanActive = false
        tabScanSeenFirst = false
        tabScanTicker?.let { handler.removeCallbacks(it) }
        tabScanTicker = null
        tabScanTimeout?.let { handler.removeCallbacks(it) }
        tabScanTimeout = null

        val result = org.json.JSONObject()
            .put("reason", reason)
            .put("steps", tabScanSteps)
            .put("elapsed_ms", (System.currentTimeMillis() - tabScanStartedAt).coerceAtLeast(0L))
            .put("count", tabScanEvents.size)
            .put("events", org.json.JSONArray().apply { tabScanEvents.forEach { put(it) } })
        val resultString = result.toString()
        logLong("LanBotTabScan", "TabScan result: ", resultString)
        val path = saveTabScanResultToSdcard(resultString)
        Logger.i("TabScan file: ${path ?: "<failed>"}", tag = "LanBotTabScan")
        try {
            sendBroadcast(Intent(ACTION_TAB_SCAN_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_TAB_SCAN_JSON, resultString)
                putExtra(EXTRA_TAB_SCAN_FILE, path ?: "")
            })
        } catch (_: Throwable) {
        }
        tabScanEvents.clear()
    }

    private fun scheduleTabTick() {
        tabScanTicker?.let { handler.removeCallbacks(it) }
        tabScanTicker = object : Runnable {
            override fun run() {
                if (!tabScanActive) return
                performTabStep()
                tabScanSteps += 1
                if (tabScanSteps >= TAB_SCAN_MAX_STEPS) {
                    stopTabScan("max_steps")
                    return
                }
                handler.postDelayed(this, TAB_SCAN_INTERVAL_MS)
            }
        }.also { handler.postDelayed(it, TAB_SCAN_INTERVAL_MS) }
    }

    private fun performTabStep() {
        val shellOk = sendTabKeyByShell()
        Logger.i("TabScan step=${tabScanSteps + 1} mode=shell_tab ok=$shellOk", tag = "LanBotTabScan")
    }

    private fun sendTabKeyByShell(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 61"))
            p.waitFor()
            val ok = p.exitValue() == 0
            Logger.i("TabScan shell input keyevent 61 exit=${p.exitValue()}", tag = "LanBotTabScan")
            ok
        } catch (_: Throwable) {
            Logger.w("TabScan shell input keyevent 61 exception", tag = "LanBotTabScan")
            false
        }
    }

    private fun handleTabScanFocusEvent(event: AccessibilityEvent) {
        if (!tabScanActive) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        if (pkg != TAB_SCAN_TARGET_PKG || cls != TAB_SCAN_TARGET_CLS) return

        val evt = org.json.JSONObject()
            .put("ts_ms", System.currentTimeMillis())
            .put("type", "VIEW_FOCUSED")
            .put("pkg", pkg)
            .put("cls", cls)
            .put("window_id", event.windowId)
            .put("text", event.text?.joinToString("|").orEmpty())
            .put("content_desc", event.contentDescription?.toString().orEmpty())
            .put("item_count", event.itemCount)
            .put("from_index", event.fromIndex)
            .put("to_index", event.toIndex)
        tabScanEvents.add(evt)
        Logger.i("TabScan captured EditText focus #${tabScanEvents.size}", tag = "LanBotTabScan")

        if (!tabScanSeenFirst) {
            tabScanSeenFirst = true
            Logger.i("TabScan first EditText found", tag = "LanBotTabScan")
        } else {
            stopTabScan("second_edittext_found")
        }
    }

    private fun saveTabScanResultToSdcard(content: String): String? {
        return try {
            val dir = File(SDCARD_OCR_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, "tab_scan_${System.currentTimeMillis()}.json")
            file.writeText(content)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private data class SnapshotResult(val status: SnapshotStatus, val retryDelayMs: Long = 0L)

    private enum class SnapshotStatus { SENT, THROTTLED }

    private fun emitSnapshot(force: Boolean): SnapshotResult {
        val now = System.currentTimeMillis()
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(applicationContext)
        if (!force) {
            val elapsed = now - lastSent
            if (elapsed < SNAPSHOT_THROTTLE_MS) {
                val wait = (SNAPSHOT_THROTTLE_MS - elapsed).coerceIn(80L, SNAPSHOT_THROTTLE_MS)
                return SnapshotResult(SnapshotStatus.THROTTLED, wait)
            }
        }
        val pkg = lastPkg
        val cls = lastCls
        if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
            Logger.i("OCR snapshot trigger: force=$force cls=$cls", tag = "LanBotOCR")
        }
        var recognizedText = ""
        val intent = Intent(ACTION_SNAPSHOT).apply {
            putExtra(EXTRA_PKG, pkg)
            putExtra(EXTRA_CLS, cls)
        }

        val capture = maybeCapturePayload(
            pkg = pkg,
            now = now,
            force = force,
            strategy = cfg.captureStrategy,
            ocrEnabled = cfg.ocrEnabled,
        )
        capture?.let {
            recognizedText = it.text
            val captureJson = org.json.JSONObject()
                .put("mode", it.payload.mode)
                .put("mime", it.payload.mime)
                .put("width", it.payload.width)
                .put("height", it.payload.height)
                .put("ts_ms", it.payload.tsMs)
                .put("data_base64", it.payload.dataBase64)
            intent.putExtra(EXTRA_CAPTURE_JSON, captureJson.toString())
            if (!it.ocrJson.isNullOrBlank()) {
                intent.putExtra(EXTRA_OCR_JSON, it.ocrJson)
            }
        }
        intent.putExtra(EXTRA_TEXT, recognizedText)
        maybeLogOcrTextChange(recognizedText)
        maybeLogOcrJsonChange(capture?.ocrJson)

        TaskBridge.sendWindowState(
            pkg = pkg,
            cls = cls,
            nodes = 0,
            clickable = 0,
            focusable = 0,
            editable = 0,
            recyclers = 0,
            wechat = null,
            capture = capture?.payload,
            ocr = capture?.ocrPayload,
        )

        intent.setPackage(packageName)
        sendBroadcast(intent)
        lastSent = now
        return SnapshotResult(SnapshotStatus.SENT)
    }

    private fun maybeCapturePayload(
        pkg: String,
        now: Long,
        force: Boolean,
        strategy: String,
        ocrEnabled: Boolean,
    ): CapturedPayload? {
        if (TEMP_DISABLE_CAPTURE_AND_OCR) {
            return null
        }
        val shouldCapture = strategy == CAPTURE_STRATEGY_SCREEN_FIRST &&
            pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG
        if (!shouldCapture) {
            if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
                Logger.i(
                    "OCR skip capture: strategy=$strategy sdk=${Build.VERSION.SDK_INT}",
                    tag = "LanBotOCR",
                )
            }
            return null
        }
        if (!force && now - lastCaptureAt < SCREEN_CAPTURE_THROTTLE_MS) {
            Logger.i("OCR skip capture: throttled", tag = "LanBotOCR")
            return null
        }
        val captured = captureScreenFrame(ocrEnabled = ocrEnabled) ?: return null
        lastCaptureAt = now
        return CapturedPayload(
            payload = TaskBridge.CapturePayload(
                mode = captured.mode,
                mime = "image/jpeg",
                width = captured.width,
                height = captured.height,
                tsMs = now,
                dataBase64 = captured.base64Jpeg,
            ),
            text = captured.ocrText,
            ocrJson = captured.ocrJson,
            ocrPayload = captured.ocrPayload,
        )
    }

    private data class CapturedPayload(
        val payload: TaskBridge.CapturePayload,
        val text: String,
        val ocrJson: String?,
        val ocrPayload: TaskBridge.OcrPayloadData?,
    )

    private data class CapturedFrame(
        val mode: String,
        val width: Int,
        val height: Int,
        val base64Jpeg: String,
        val ocrText: String,
        val ocrJson: String?,
        val ocrPayload: TaskBridge.OcrPayloadData?,
    )

    private fun captureScreenFrame(ocrEnabled: Boolean): CapturedFrame? {
        val projected = try {
            ScreenCaptureManager.captureBitmap(applicationContext, timeoutMs = 600L)
        } catch (t: Throwable) {
            Logger.w("MediaProjection capture failed: ${t.message}", tag = "LanBotOCR")
            null
        }
        if (projected != null) {
            return bitmapToFrame(projected, "media_projection", ocrEnabled)
        }
        Logger.i("OCR skip capture: media projection unavailable", tag = "LanBotOCR")
        return null
    }

    private fun bitmapToFrame(source: Bitmap, mode: String, ocrEnabled: Boolean): CapturedFrame? {
        val scaled = scaleBitmap(source, CAPTURE_MAX_WIDTH)
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        val ocrResult = if (ocrEnabled) {
            try {
                ppOcrRecognizer.recognizeDetailed(scaled)
            } catch (_: Throwable) {
                null
            }
        } else null
        val ocrText = ocrResult?.text ?: ""
        val ocrJson = ocrResult?.let { toOcrJson(it) }
        val ocrPayload = ocrResult?.let { toOcrPayload(it) }
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, out)) {
            if (scaled !== source) scaled.recycle()
            source.recycle()
            return null
        }
        val overlayJpeg = buildOverlayJpeg(scaled, ocrPayload)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        val bytes = out.toByteArray()
        saveOcrCaptureToSdcard(bytes, overlayJpeg, ocrText)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return CapturedFrame(
            mode = mode,
            width = outputWidth,
            height = outputHeight,
            base64Jpeg = encoded,
            ocrText = ocrText,
            ocrJson = ocrJson,
            ocrPayload = ocrPayload,
        )
    }

    private fun toOcrPayload(result: PPOcrRecognizer.OcrResult): TaskBridge.OcrPayloadData {
        return TaskBridge.OcrPayloadData(
            text = result.text,
            lines = result.lines.map { line ->
                TaskBridge.OcrLineData(
                    text = line.text,
                    prob = line.prob,
                    quad = line.quad.map { p -> TaskBridge.OcrPointData(x = p.x, y = p.y) },
                    left = line.left,
                    top = line.top,
                    right = line.right,
                    bottom = line.bottom,
                )
            },
        )
    }

    private fun toOcrJson(result: PPOcrRecognizer.OcrResult): String {
        val lines = org.json.JSONArray()
        for (line in result.lines) {
            val quad = org.json.JSONArray()
            for (p in line.quad) {
                quad.put(org.json.JSONObject().put("x", p.x).put("y", p.y))
            }
            lines.put(
                org.json.JSONObject()
                    .put("text", line.text)
                    .put("prob", line.prob)
                    .put("quad", quad)
                    .put(
                        "bbox",
                        org.json.JSONObject()
                            .put("left", line.left)
                            .put("top", line.top)
                            .put("right", line.right)
                            .put("bottom", line.bottom),
                    ),
            )
        }
        return org.json.JSONObject()
            .put("text", result.text)
            .put("lines", lines)
            .toString()
    }

    private fun scaleBitmap(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth || source.width <= 0 || source.height <= 0) return source
        val ratio = maxWidth.toFloat() / source.width.toFloat()
        val dstWidth = maxWidth
        val dstHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
    }

    private fun buildOverlayJpeg(
        base: Bitmap,
        ocrPayload: TaskBridge.OcrPayloadData?,
    ): ByteArray? {
        val lines = ocrPayload?.lines.orEmpty()
        if (lines.isEmpty()) return null
        return try {
            val overlay = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val canvas = Canvas(overlay)
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(255, 0, 255, 80)
                strokeWidth = (overlay.width / 320f).coerceAtLeast(2f)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(255, 255, 208, 0)
                textSize = (overlay.width / 38f).coerceAtLeast(20f)
            }
            val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(150, 0, 0, 0)
            }
            for (line in lines) {
                val quad = line.quad
                if (quad.size >= 4) {
                    for (i in quad.indices) {
                        val p1 = quad[i]
                        val p2 = quad[(i + 1) % quad.size]
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, strokePaint)
                    }
                }
                val label = "${line.text} (${String.format(java.util.Locale.US, "%.2f", line.prob)})"
                val textX = line.left.coerceAtLeast(0f)
                val textY = (line.top - 8f).coerceAtLeast(textPaint.textSize + 4f)
                val textW = textPaint.measureText(label)
                val pad = 6f
                canvas.drawRect(
                    textX - pad,
                    textY - textPaint.textSize - pad,
                    textX + textW + pad,
                    textY + pad,
                    textBgPaint,
                )
                canvas.drawText(label, textX, textY, textPaint)
            }
            val out = ByteArrayOutputStream()
            if (!overlay.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, out)) {
                overlay.recycle()
                return null
            }
            overlay.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveOcrCaptureToSdcard(jpeg: ByteArray, overlayJpeg: ByteArray?, text: String) {
        if (!SAVE_CAPTURE_TO_SDCARD) return
        try {
            val dir = File(SDCARD_OCR_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.w("OCR save failed: cannot mkdir $SDCARD_OCR_DIR", tag = "LanBotOCR")
                return
            }
            val ts = System.currentTimeMillis()
            val imgFile = File(dir, "ocr_${ts}.jpg")
            FileOutputStream(imgFile).use { it.write(jpeg) }
            if (overlayJpeg != null) {
                val overlayFile = File(dir, "ocr_${ts}_overlay.jpg")
                FileOutputStream(overlayFile).use { it.write(overlayJpeg) }
            }
            val txtFile = File(dir, "ocr_${ts}.txt")
            txtFile.writeText(text.ifBlank { "<EMPTY>" })
            Logger.i("OCR saved: ${imgFile.absolutePath}", tag = "LanBotOCR")
        } catch (t: Throwable) {
            Logger.w("OCR save exception: ${t.message}", tag = "LanBotOCR")
        }
    }

    private fun maybeLogOcrTextChange(text: String) {
        val normalized = text.trim()
        if (normalized == lastLoggedOcrText) return
        lastLoggedOcrText = normalized
    }

    private fun maybeLogOcrJsonChange(ocrJson: String?) {
        val normalized = ocrJson?.trim().orEmpty()
        if (normalized == lastLoggedOcrJson) return
        lastLoggedOcrJson = normalized
        if (normalized.isEmpty()) {
            Logger.i("NCNN OCR boxes changed: <EMPTY>", tag = "LanBotOCR")
            return
        }
        logLong(tag = "LanBotOCR", prefix = "NCNN OCR boxes changed: ", text = normalized)
    }

    private fun logLong(tag: String, prefix: String, text: String) {
        if (text.isEmpty()) {
            Logger.i(prefix, tag = tag)
            return
        }
        val maxChunk = LOGCAT_CHUNK_SIZE.coerceAtLeast(256)
        var start = 0
        var idx = 1
        while (start < text.length) {
            val end = (start + maxChunk).coerceAtMost(text.length)
            val part = text.substring(start, end)
            Logger.i("$prefix[$idx] $part", tag = tag)
            start = end
            idx += 1
        }
    }

    private fun extractNotificationTitle(event: AccessibilityEvent): String? {
        val notification = event.parcelableData as? android.app.Notification ?: return null
        val title = notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        return title?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun scheduleFollowUp(delayMs: Long, force: Boolean, attempt: Int = 1) {
        val delay = delayMs.coerceAtLeast(80L)
        val runnable = Runnable {
            pendingFollowUp = null
            val nextResult = emitSnapshot(force)
            when (nextResult.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.THROTTLED -> if (!force && attempt < 2) {
                    scheduleFollowUp(nextResult.retryDelayMs, force = false, attempt = attempt + 1)
                }
            }
        }
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = runnable
        handler.postDelayed(runnable, delay)
    }

    companion object {
        private const val TEMP_DISABLE_CAPTURE_AND_OCR = true
        private const val SNAPSHOT_THROTTLE_MS = 800L
        private const val SCREEN_CAPTURE_THROTTLE_MS = 2200L
        private const val CAPTURE_JPEG_QUALITY = 55
        private const val CAPTURE_MAX_WIDTH = 960
        private const val SAVE_CAPTURE_TO_SDCARD = true
        private const val SDCARD_OCR_DIR = "/sdcard/ocr"
        private const val LOGCAT_CHUNK_SIZE = 2800
        private const val TAB_SCAN_INTERVAL_MS = 250L
        private const val TAB_SCAN_MAX_STEPS = 120
        private const val TAB_SCAN_MAX_DURATION_MS = 45_000L
        private const val TAB_SCAN_TARGET_PKG = "com.tencent.mm"
        private const val TAB_SCAN_TARGET_CLS = "android.widget.EditText"
        const val ACTION_SNAPSHOT = "com.ws.wx_server.ACC_SNAPSHOT"
        const val ACTION_CONNECTED = "com.ws.wx_server.ACC_CONNECTED"
        const val ACTION_DISCONNECTED = "com.ws.wx_server.ACC_DISCONNECTED"
        const val ACTION_START_TAB_SCAN = "com.ws.wx_server.START_TAB_SCAN"
        const val ACTION_STOP_TAB_SCAN = "com.ws.wx_server.STOP_TAB_SCAN"
        const val ACTION_TAB_SCAN_DONE = "com.ws.wx_server.TAB_SCAN_DONE"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CLS = "cls"
        const val EXTRA_WECHAT_JSON = "wechat_json"
        const val EXTRA_STATE_JSON = "state_json"
        const val EXTRA_CAPTURE_JSON = "capture_json"
        const val EXTRA_OCR_JSON = "ocr_json"
        const val EXTRA_TAB_SCAN_JSON = "tab_scan_json"
        const val EXTRA_TAB_SCAN_FILE = "tab_scan_file"
    }
}
