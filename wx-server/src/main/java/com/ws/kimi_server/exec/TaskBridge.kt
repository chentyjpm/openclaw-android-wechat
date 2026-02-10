package com.ws.wx_server.exec

import com.ws.wx_server.apps.wechat.WeChatMessage
import com.ws.wx_server.apps.wechat.WeChatSpec
import com.ws.wx_server.link.http.ChatMessage
import com.ws.wx_server.link.http.ClientEnvelope
import com.ws.wx_server.link.http.CaptureFrame
import com.ws.wx_server.link.http.WeChatMessages
import com.ws.wx_server.link.http.WeChatState
import com.ws.wx_server.link.http.WindowStateEvent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.ws.wx_server.util.Logger
/**
 * Task bridge that forwards window state snapshots to the HTTP link and tracks
 * minimal message state for debugging.
 */
object TaskBridge {
    private val sequence = AtomicLong(1L)
    private val tasks = ConcurrentHashMap<String, PendingTask>()
    var sendEnvelope: ((ClientEnvelope) -> Unit)? = null

    fun pushMessage(chatKey: String, title: String?, isGroup: Boolean, message: WeChatMessage): String {
        val taskId = buildTaskId(message)
        val pending = PendingTask(taskId, chatKey, title, isGroup, message)
        tasks[taskId] = pending
        sendWindowState(
            pkg = WeChatSpec.PKG,
            cls = "",
            nodes = 0,
            clickable = 0,
            focusable = 0,
            editable = 0,
            recyclers = 0,
            wechat = WeChatStatePayload(
                screen = WeChatScreen.CHAT,
                chatId = chatKey,
                title = title ?: "",
                isGroup = isGroup,
                messages = listOf(message)
            )
        )
        return taskId
    }

    fun pull(taskId: String): TaskStatus {
        val task = tasks[taskId]
        return if (task != null) {
            TaskStatus(taskId, state = "completed", result = "stub_result")
        } else {
            TaskStatus(taskId, state = "unknown", result = null)
        }
    }

    fun sendWindowState(
        pkg: String,
        cls: String,
        nodes: Int,
        clickable: Int,
        focusable: Int,
        editable: Int,
        recyclers: Int,
        wechat: WeChatStatePayload? = null,
        capture: CapturePayload? = null,
    ) {
        val event = WindowStateEvent(
            pkg = pkg,
            cls = cls,
            tsMs = System.currentTimeMillis(),
            nodes = nodes.coerceAtLeast(0),
            clickable = clickable.coerceAtLeast(0),
            focusable = focusable.coerceAtLeast(0),
            editable = editable.coerceAtLeast(0),
            recyclers = recyclers.coerceAtLeast(0),
            wechat = wechat?.let { buildWeChatState(it) },
            capture = capture?.let { buildCaptureFrame(it) },
        )

        val env = ClientEnvelope(windowState = event)
        sendEnvelope?.invoke(env)
    }

    private fun WeChatMessage.toHttp(): ChatMessage =
        ChatMessage(
            msgId = id,
            sequence = sequence ?: 0L,
            sender = sender ?: "",
            text = text ?: "",
            desc = desc ?: "",
            type = type,
            tsClient = System.currentTimeMillis(),
            delivered = delivered,
        )

    enum class WeChatScreen { UNKNOWN, HOME, CHAT, SEARCH, OTHER }

    data class WeChatStatePayload(
        val screen: WeChatScreen,
        val chatId: String?,
        val title: String?,
        val isGroup: Boolean,
        val messages: List<WeChatMessage>? = null,
    )

    data class CapturePayload(
        val mode: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val tsMs: Long,
        val dataBase64: String,
    )

    private fun WeChatScreen.toWire(): String = when (this) {
        WeChatScreen.HOME -> "home"
        WeChatScreen.CHAT -> "chat"
        WeChatScreen.SEARCH -> "search"
        WeChatScreen.OTHER -> "other"
        WeChatScreen.UNKNOWN -> "unknown"
    }

    private fun buildWeChatState(payload: WeChatStatePayload): WeChatState {
        val items = payload.messages.orEmpty()
        val messages = if (items.isEmpty()) null else {
            Logger.i("window_state wechat messages=${items.size} screen=${payload.screen} title=${payload.title}", tag = "LanBotWeChat")
            WeChatMessages(items = items.map { msg -> msg.toHttp() })
        }
        return WeChatState(
            screen = payload.screen.toWire(),
            chatId = payload.chatId ?: "",
            title = payload.title ?: "",
            isGroup = payload.isGroup,
            messages = messages,
        )
    }

    private fun buildCaptureFrame(payload: CapturePayload): CaptureFrame {
        return CaptureFrame(
            mode = payload.mode,
            mime = payload.mime,
            width = payload.width,
            height = payload.height,
            tsMs = payload.tsMs,
            dataBase64 = payload.dataBase64,
        )
    }

    fun screenFromSnapshot(screen: String?): WeChatScreen {
        val value = screen?.lowercase(Locale.US) ?: return WeChatScreen.UNKNOWN
        return when (value) {
            "chat" -> WeChatScreen.CHAT
            "conversations", "home" -> WeChatScreen.HOME
            "search" -> WeChatScreen.SEARCH
            "unknown" -> WeChatScreen.UNKNOWN
            else -> WeChatScreen.OTHER
        }
    }

    private fun buildTaskId(message: WeChatMessage): String {
        val base = message.id.takeIf { !it.isNullOrBlank() }
        if (!base.isNullOrBlank()) {
            return "task-${base}"
        }
        val fallback = sequence.getAndIncrement()
        return "task-${fallback.toString().padStart(6, '0')}"
    }

    private data class PendingTask(
        val id: String,
        val chatKey: String,
        val title: String?,
        val isGroup: Boolean,
        val message: WeChatMessage
    )

    data class TaskStatus(
        val id: String,
        val state: String,
        val result: String?
    )
}
