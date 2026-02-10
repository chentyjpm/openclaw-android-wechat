package com.ws.wx_server.debug

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ws.wx_server.link.LinkConfigStore
import com.ws.wx_server.util.Logger
import java.io.File

object AccessibilityDebug {
    private const val XML_THROTTLE_MS = 150L
    private const val XML_FILE_THROTTLE_MS = 1500L
    private const val MAX_NODES = 700
    private const val MAX_DEPTH = 18
    private const val MAX_TEXT = 120
    private const val LOGCAT_CHUNK_CHARS = 3200
    @Volatile private var lastXmlAt = 0L
    @Volatile private var lastXmlFileAt = 0L

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        val cfg = LinkConfigStore.load(service.applicationContext)
        if (!cfg.debugEvents && !cfg.debugXml) return
        if (cfg.debugEvents) {
            val summary = buildEventSummary(event)
            Logger.i("AccEvent: $summary", tag = "LanBotAccEvent")
        }
        if (cfg.debugXml && shouldDumpXml(event)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastXmlAt >= XML_THROTTLE_MS) {
                lastXmlAt = now
                val root = service.rootInActiveWindow
                val xml = if (root != null) buildXml(root) else "<node root=\"null\"/>"
                logXmlToLogcat(xml)
                maybeWriteXmlToFile(service, xml)
            }
        }
    }

    private fun shouldDumpXml(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
            else -> false
        }
    }

    private fun buildEventSummary(event: AccessibilityEvent): String {
        val typeName = eventTypeName(event.eventType)
        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        val windowId = event.windowId
        val changeTypes = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            contentChangeTypes(event.contentChangeTypes)
        } else {
            null
        }
        val text = event.text?.joinToString(" | ") { it.toString().trim() }
        val desc = event.contentDescription?.toString()?.trim()
        val notification = if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            notificationSummary(event)
        } else {
            null
        }
        return buildString {
            append("type=").append(typeName)
            append(" pkg=").append(pkg)
            append(" cls=").append(cls)
            append(" windowId=").append(windowId)
            if (!changeTypes.isNullOrBlank()) append(" changes=").append(changeTypes)
            if (!text.isNullOrBlank()) append(" text=").append(quote(trimText(text)))
            if (!desc.isNullOrBlank()) append(" desc=").append(quote(trimText(desc)))
            if (!notification.isNullOrBlank()) append(" notif=").append(notification)
        }
    }

    private fun notificationSummary(event: AccessibilityEvent): String {
        val data = event.parcelableData as? Notification ?: return "null"
        val extras = data.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val ticker = data.tickerText?.toString()
        return buildString {
            append("{")
            if (!title.isNullOrBlank()) append("title=").append(quote(trimText(title))).append(",")
            if (!text.isNullOrBlank()) append("text=").append(quote(trimText(text))).append(",")
            if (!subText.isNullOrBlank()) append("sub=").append(quote(trimText(subText))).append(",")
            if (!ticker.isNullOrBlank()) append("ticker=").append(quote(trimText(ticker))).append(",")
            if (last() == ',') deleteCharAt(lastIndex)
            append("}")
        }
    }

    private fun buildXml(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var count = 0
        fun dump(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val indent = "  ".repeat(depth)
            val cls = node.className?.toString() ?: ""
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val id = node.viewIdResourceName ?: ""
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            sb.append(indent)
                .append("<node")
                .append(" cls=\"").append(escapeAttr(cls)).append("\"")
                .append(" id=\"").append(escapeAttr(id)).append("\"")
                .append(" text=\"").append(escapeAttr(trimText(text))).append("\"")
                .append(" desc=\"").append(escapeAttr(trimText(desc))).append("\"")
                .append(" clickable=\"").append(node.isClickable).append("\"")
                .append(" focusable=\"").append(node.isFocusable).append("\"")
                .append(" enabled=\"").append(node.isEnabled).append("\"")
                .append(" bounds=\"").append(bounds.toShortString()).append("\"")
            val childCount = node.childCount
            if (childCount == 0) {
                sb.append(" />\n")
                return
            }
            sb.append(">\n")
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                dump(child, depth + 1)
                if (count >= MAX_NODES) break
            }
            sb.append(indent).append("</node>\n")
        }
        dump(root, 0)
        if (count >= MAX_NODES) {
            sb.append("<!-- truncated -->\n")
        }
        return sb.toString()
    }

    private fun logXmlToLogcat(xml: String) {
        if (xml.length <= LOGCAT_CHUNK_CHARS) {
            Logger.d("AccXml:\n$xml", tag = "LanBotAccXml")
            return
        }
        val totalParts = (xml.length + LOGCAT_CHUNK_CHARS - 1) / LOGCAT_CHUNK_CHARS
        Logger.d("AccXml: len=${xml.length} parts=$totalParts", tag = "LanBotAccXml")
        var start = 0
        var part = 1
        while (start < xml.length) {
            val end = (start + LOGCAT_CHUNK_CHARS).coerceAtMost(xml.length)
            val chunk = xml.substring(start, end)
            Logger.d("AccXml[$part/$totalParts]:\n$chunk", tag = "LanBotAccXml")
            start = end
            part++
        }
    }

    private fun maybeWriteXmlToFile(service: AccessibilityService, xml: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastXmlFileAt < XML_FILE_THROTTLE_MS) return
        lastXmlFileAt = now
        try {
            val dir = File(service.applicationContext.filesDir, "accxml")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "accxml-latest.xml")
            out.writeText(xml)
            Logger.i("AccXml saved: ${out.absolutePath} (${xml.length} chars)", tag = "LanBotAccXml")
        } catch (t: Throwable) {
            Logger.w("AccXml save failed: ${t.message}", tag = "LanBotAccXml")
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "VIEW_ACCESSIBILITY_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "VIEW_ACCESSIBILITY_FOCUS_CLEARED"
        else -> "TYPE_$type"
    }

    private fun contentChangeTypes(mask: Int): String {
        if (mask == 0) return "0"
        val parts = ArrayList<String>()
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) parts.add("TEXT")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) parts.add("DESC")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) parts.add("SUBTREE")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0) parts.add("STATE_DESC")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0) parts.add("PANE_TITLE")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED != 0) parts.add("UNDEFINED")
        return parts.joinToString("|")
    }

    private fun escapeAttr(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun trimText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val trimmed = value.replace("\n", " ").replace("\r", " ").trim()
        return if (trimmed.length > MAX_TEXT) trimmed.substring(0, MAX_TEXT) + "..." else trimmed
    }

    private fun quote(value: String): String = "\"$value\""
}
