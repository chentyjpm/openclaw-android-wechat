package com.ws.kimi_server.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ws.kimi_server.exec.TaskBridge
import com.ws.kimi_server.debug.AccessibilityDebug
import com.ws.kimi_server.util.Logger

class MyAccessibilityService : AccessibilityService() {
    private var lastSent = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFollowUp: Runnable? = null
    private var lastWindowKey: String? = null
    private var lastPkg: String = ""
    private var lastCls: String = ""

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
        val cfg = com.ws.kimi_server.link.LinkConfigStore.load(applicationContext)
        Logger.i("AccDebug config: events=${cfg.debugEvents} xml=${cfg.debugXml}")
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
            if (pkg == com.ws.kimi_server.apps.wechat.WeChatSpec.PKG) {
                val title = extractNotificationTitle(event)
                if (!title.isNullOrBlank()) {
                    com.ws.kimi_server.apps.wechat.WeChatNotifyGate.handleNotification(applicationContext, title)
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
            val result = emitSnapshot(type, force = true)
            when (result.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.NO_ROOT -> scheduleFollowUp(result.retryDelayMs, type, force = true)
                SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, type, force = false)
            }
            return
        }

        val result = emitSnapshot(type, force = false)
        when (result.status) {
            SnapshotStatus.SENT -> Unit
            SnapshotStatus.NO_ROOT, SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, type, force = false)
        }
    }

    override fun onInterrupt() {
        // No-op for now
    }

    private data class SnapshotResult(val status: SnapshotStatus, val retryDelayMs: Long = 0L)

    private enum class SnapshotStatus { SENT, THROTTLED, NO_ROOT }

    private fun emitSnapshot(eventType: Int, force: Boolean): SnapshotResult {
        val now = System.currentTimeMillis()
        if (!force) {
            val elapsed = now - lastSent
            if (elapsed < SNAPSHOT_THROTTLE_MS) {
                val wait = (SNAPSHOT_THROTTLE_MS - elapsed).coerceIn(80L, SNAPSHOT_THROTTLE_MS)
                return SnapshotResult(SnapshotStatus.THROTTLED, wait)
            }
        }
        val root = rootInActiveWindow ?: return SnapshotResult(SnapshotStatus.NO_ROOT, 160L)
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: lastPkg
        val cls = root.className?.toString()?.takeIf { it.isNotBlank() } ?: lastCls
        val text = tryCollectText(root)
        val intent = Intent(ACTION_SNAPSHOT).apply {
            putExtra(EXTRA_PKG, pkg)
            putExtra(EXTRA_CLS, cls)
            putExtra(EXTRA_TEXT, text)
        }

        var stateJson: org.json.JSONObject? = null
        var nodes = 0
        var clickable = 0
        var focusable = 0
        var editable = 0
        var recyclers = 0
        var wechatPayload: TaskBridge.WeChatStatePayload? = null
        try {
            val state = com.ws.kimi_server.parser.WindowStateBuilder.build(root, pkg, cls)
            stateJson = state
            intent.putExtra(EXTRA_STATE_JSON, state.toString())
            nodes = state.optInt("nodes")
            clickable = state.optInt("clickable")
            focusable = state.optInt("focusable")
            editable = state.optInt("editable")
            recyclers = state.optInt("recyclers")
        } catch (_: Throwable) { }

        if (pkg == com.ws.kimi_server.apps.wechat.WeChatSpec.PKG) {
            val snap = com.ws.kimi_server.apps.wechat.WeChatParser.parse(this, root, cls)
            if (snap != null) {
                if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    (snap.screen == "conversations" || snap.screen == "home")
                ) {
                    if (!com.ws.kimi_server.apps.wechat.WeChatNotifyGate.isLocked()) {
                        com.ws.kimi_server.apps.wechat.WeChatAgent.maybeOpenChatFromHome(this, snap)
                    }
                }
                val allowScroll = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                val filtered = com.ws.kimi_server.apps.wechat.WeChatAgent.filterSnapshot(this, snap, allowScroll)
                val json = com.ws.kimi_server.apps.wechat.WeChatParser.toJson(filtered)
                intent.putExtra(EXTRA_WECHAT_JSON, json.toString())
                wechatPayload = TaskBridge.WeChatStatePayload(
                    screen = TaskBridge.screenFromSnapshot(filtered.screen),
                    chatId = filtered.chatId ?: filtered.title,
                    title = filtered.title,
                    isGroup = filtered.isGroup,
                )
            }
        }

        TaskBridge.sendWindowState(
            pkg = pkg,
            cls = cls,
            nodes = nodes,
            clickable = clickable,
            focusable = focusable,
            editable = editable,
            recyclers = recyclers,
            wechat = wechatPayload,
        )

        intent.setPackage(packageName)
        sendBroadcast(intent)
        lastSent = now
        return SnapshotResult(SnapshotStatus.SENT)
    }

    private fun extractNotificationTitle(event: AccessibilityEvent): String? {
        val notification = event.parcelableData as? android.app.Notification ?: return null
        val title = notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        return title?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun scheduleFollowUp(delayMs: Long, eventType: Int, force: Boolean, attempt: Int = 1) {
        val delay = delayMs.coerceAtLeast(80L)
        val runnable = Runnable {
            pendingFollowUp = null
            val nextResult = emitSnapshot(eventType, force)
            when (nextResult.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.NO_ROOT -> if (force && attempt < 3) {
                    scheduleFollowUp(nextResult.retryDelayMs, eventType, force = true, attempt = attempt + 1)
                }
                SnapshotStatus.THROTTLED -> if (!force && attempt < 2) {
                    scheduleFollowUp(nextResult.retryDelayMs, eventType, force = false, attempt = attempt + 1)
                }
            }
        }
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun tryCollectText(root: AccessibilityNodeInfo, maxNodes: Int = 200, maxLen: Int = 2000): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= maxNodes || sb.length >= maxLen) return
            count++
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            if (!t.isNullOrBlank()) sb.appendLine(t.trim())
            if (!d.isNullOrBlank()) sb.appendLine(d.trim())
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
                if (count >= maxNodes || sb.length >= maxLen) break
            }
        }
        walk(root)
        return sb.toString().trim()
    }

    companion object {
        private const val SNAPSHOT_THROTTLE_MS = 800L
        const val ACTION_SNAPSHOT = "com.ws.kimi_server.ACC_SNAPSHOT"
        const val ACTION_CONNECTED = "com.ws.kimi_server.ACC_CONNECTED"
        const val ACTION_DISCONNECTED = "com.ws.kimi_server.ACC_DISCONNECTED"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CLS = "cls"
        const val EXTRA_WECHAT_JSON = "wechat_json"
        const val EXTRA_STATE_JSON = "state_json"
    }
}
