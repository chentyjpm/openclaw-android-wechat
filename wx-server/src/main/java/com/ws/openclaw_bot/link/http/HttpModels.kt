package com.ws.wx_server.link.http

import org.json.JSONArray
import org.json.JSONObject

data class Hello(
    val device: String = "",
    val appVersion: String = "",
    val buildTs: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device", device)
        .put("app_version", appVersion)
        .put("build_ts", buildTs)
}

data class ChatMessage(
    val msgId: String = "",
    val sequence: Long = 0L,
    val sender: String = "",
    val text: String = "",
    val desc: String = "",
    val type: String = "",
    val tsClient: Long = 0L,
    val delivered: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("msg_id", msgId)
        .put("sequence", sequence)
        .put("sender", sender)
        .put("text", text)
        .put("desc", desc)
        .put("type", type)
        .put("ts_client", tsClient)
        .put("delivered", delivered)
}

data class WeChatMessages(
    val items: List<ChatMessage> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put(
        "items",
        JSONArray().apply { items.forEach { put(it.toJson()) } }
    )
}

data class WeChatState(
    val screen: String = "unknown",
    val chatId: String = "",
    val title: String = "",
    val isGroup: Boolean = false,
    val messages: WeChatMessages? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("screen", screen)
        if (chatId.isNotBlank()) put("chat_id", chatId)
        if (title.isNotBlank()) put("title", title)
        put("is_group", isGroup)
        messages?.let { put("messages", it.toJson()) }
    }
}

data class WindowStateEvent(
    val pkg: String = "",
    val cls: String = "",
    val tsMs: Long = 0L,
    val nodes: Int = 0,
    val clickable: Int = 0,
    val focusable: Int = 0,
    val editable: Int = 0,
    val recyclers: Int = 0,
    val wechat: WeChatState? = null,
    val capture: CaptureFrame? = null,
    val ocr: OcrPayload? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg)
        put("cls", cls)
        put("ts_ms", tsMs)
        put("nodes", nodes)
        put("clickable", clickable)
        put("focusable", focusable)
        put("editable", editable)
        put("recyclers", recyclers)
        wechat?.let { put("wechat", it.toJson()) }
        capture?.let { put("capture", it.toJson()) }
        ocr?.let { put("ocr", it.toJson()) }
    }
}

data class CaptureFrame(
    val mode: String = "",
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val tsMs: Long = 0L,
    val dataBase64: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("mime", mime)
        put("width", width)
        put("height", height)
        put("ts_ms", tsMs)
        put("data_base64", dataBase64)
    }
}

data class OcrPayload(
    val text: String = "",
    val lines: List<OcrLine> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
    }
}

data class OcrLine(
    val text: String = "",
    val prob: Float = 0f,
    val quad: List<OcrPoint> = emptyList(),
    val bbox: OcrBbox = OcrBbox(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("prob", prob)
        put("quad", JSONArray().apply { quad.forEach { put(it.toJson()) } })
        put("bbox", bbox.toJson())
    }
}

data class OcrPoint(
    val x: Float = 0f,
    val y: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("x", x)
        .put("y", y)
}

data class OcrBbox(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
}

data class ClientAck(
    val requestId: String,
    val ok: Boolean,
    val error: String? = null,
    val stage: String? = null,
    val detail: String? = null,
    val tsMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("ok", ok)
        error?.let { put("error", it) }
        stage?.let { put("stage", it) }
        detail?.let { put("detail", it) }
        put("ts_ms", tsMs)
    }
}

data class ClientEnvelope(
    val hello: Hello? = null,
    val windowState: WindowStateEvent? = null,
    val ack: ClientAck? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        hello?.let { put("hello", it.toJson()) }
        windowState?.let { put("window_state", it.toJson()) }
        ack?.let { put("ack", it.toJson()) }
    }
}

data class ClientPush(
    val envelopes: List<ClientEnvelope>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("envelopes", JSONArray().apply { envelopes.forEach { put(it.toJson()) } })
    }
}

data class ClientPull(
    val afterId: Long = 0L,
    val limit: Int = 10,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("after_id", afterId)
        .put("limit", limit)
}

data class ServerTask(
    val taskId: Long,
    val type: String,
    val payload: JSONObject,
)

data class NavigateCommand(
    val requestId: String = "",
    val pkg: String = "",
    val preferRecents: Boolean = false,
    val argsJson: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject): NavigateCommand = NavigateCommand(
            requestId = json.optString("request_id"),
            pkg = json.optString("pkg"),
            preferRecents = json.optBoolean("prefer_recents", false),
            argsJson = json.optString("args_json"),
        )
    }
}

data class SendTextCommand(
    val requestId: String = "",
    val chatId: String = "",
    val text: String = "",
    val mode: String = "",
    val imageUrl: String = "",
    val imageMimeType: String = "",
    val targetTitle: String = "",
    val targetIsGroup: Boolean = false,
) {
    companion object {
        fun fromJson(json: JSONObject): SendTextCommand = SendTextCommand(
            requestId = json.optString("request_id"),
            chatId = json.optString("chat_id"),
            text = json.optString("text"),
            mode = json.optString("mode"),
            imageUrl = json.optString("image_url"),
            imageMimeType = json.optString("image_mime_type"),
            targetTitle = json.optString("target_title"),
            targetIsGroup = json.optBoolean("target_is_group", false),
        )
    }
}

data class DebugCommand(
    val message: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject): DebugCommand = DebugCommand(
            message = json.optString("message"),
        )
    }
}
