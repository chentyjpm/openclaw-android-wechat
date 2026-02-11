package com.ws.wx_server.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
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
        if (cfg.captureStrategy == CAPTURE_STRATEGY_SCREEN_FIRST) {
            Thread {
                val ok = ppOcrRecognizer.warmUp()
                Logger.i("NCNN OCR warmup result=$ok", tag = "LanBotOCR")
            }.start()
        }
        try {
            val i = Intent(ACTION_CONNECTED)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) { }
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
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = null
        Logger.i("Accessibility destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        AccessibilityDebug.onEvent(this, event)
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
        }
        intent.putExtra(EXTRA_TEXT, recognizedText)
        maybeLogOcrTextChange(recognizedText)

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
    ): CapturedPayload? {
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
        val captured = captureScreenFrame() ?: return null
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
        )
    }

    private data class CapturedPayload(
        val payload: TaskBridge.CapturePayload,
        val text: String,
    )

    private data class CapturedFrame(
        val mode: String,
        val width: Int,
        val height: Int,
        val base64Jpeg: String,
        val ocrText: String,
    )

    private fun captureScreenFrame(): CapturedFrame? {
        val projected = try {
            ScreenCaptureManager.captureBitmap(applicationContext, timeoutMs = 600L)
        } catch (t: Throwable) {
            Logger.w("MediaProjection capture failed: ${t.message}", tag = "LanBotOCR")
            null
        }
        if (projected != null) {
            return bitmapToFrame(projected, "media_projection")
        }
        Logger.i("OCR skip capture: media projection unavailable", tag = "LanBotOCR")
        return null
    }

    private fun bitmapToFrame(source: Bitmap, mode: String): CapturedFrame? {
        val scaled = scaleBitmap(source, CAPTURE_MAX_WIDTH)
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        val ocrText = try {
            ppOcrRecognizer.recognize(scaled) ?: ""
        } catch (_: Throwable) {
            ""
        }
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, out)) {
            if (scaled !== source) scaled.recycle()
            source.recycle()
            return null
        }
        if (scaled !== source) scaled.recycle()
        source.recycle()
        val bytes = out.toByteArray()
        saveOcrCaptureToSdcard(bytes, ocrText)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return CapturedFrame(
            mode = mode,
            width = outputWidth,
            height = outputHeight,
            base64Jpeg = encoded,
            ocrText = ocrText,
        )
    }

    private fun scaleBitmap(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth || source.width <= 0 || source.height <= 0) return source
        val ratio = maxWidth.toFloat() / source.width.toFloat()
        val dstWidth = maxWidth
        val dstHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
    }

    private fun saveOcrCaptureToSdcard(jpeg: ByteArray, text: String) {
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
        val preview = if (normalized.isEmpty()) "<EMPTY>" else normalized.take(200)
        Logger.i("NCNN OCR changed: $preview", tag = "LanBotOCR")
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
        private const val SNAPSHOT_THROTTLE_MS = 800L
        private const val SCREEN_CAPTURE_THROTTLE_MS = 2200L
        private const val CAPTURE_JPEG_QUALITY = 55
        private const val CAPTURE_MAX_WIDTH = 960
        private const val SAVE_CAPTURE_TO_SDCARD = true
        private const val SDCARD_OCR_DIR = "/sdcard/ocr"
        const val ACTION_SNAPSHOT = "com.ws.wx_server.ACC_SNAPSHOT"
        const val ACTION_CONNECTED = "com.ws.wx_server.ACC_CONNECTED"
        const val ACTION_DISCONNECTED = "com.ws.wx_server.ACC_DISCONNECTED"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CLS = "cls"
        const val EXTRA_WECHAT_JSON = "wechat_json"
        const val EXTRA_STATE_JSON = "state_json"
        const val EXTRA_CAPTURE_JSON = "capture_json"
    }
}
