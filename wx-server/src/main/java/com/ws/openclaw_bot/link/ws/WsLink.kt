package com.ws.wx_server.link.ws

import com.ws.wx_server.link.Link
import com.ws.wx_server.link.http.ClientEnvelope
import com.ws.wx_server.link.http.ServerTask
import com.ws.wx_server.util.Logger
import okhttp3.OkHttpClient

class WsLink : Link {
    override var onEnvelope: (ServerTask) -> Unit = {}
    override var onDebugText: (String) -> Unit = {}
    override var onState: (String) -> Unit = {}
    override var onError: (String) -> Unit = {}
    private val client by lazy { OkHttpClient() }

    override fun connect() {
        // TODO: real WS later
        Logger.i("WsLink.connect() stub")
        onState.invoke("connected")
    }

    override fun requestReconnect(reason: String, immediate: Boolean) {
        Logger.d("WsLink.requestReconnect() stub: $reason immediate=$immediate")
        connect()
    }

    override fun close() {
        Logger.i("WsLink.close() stub")
        onState.invoke("disconnected")
    }

    override fun sendText(text: String) {
        Logger.d("WsLink.sendText() stub: $text")
    }

    override fun sendEnvelope(envelope: ClientEnvelope) {
        Logger.d("WsLink.sendEnvelope() stub: $envelope")
    }
}
