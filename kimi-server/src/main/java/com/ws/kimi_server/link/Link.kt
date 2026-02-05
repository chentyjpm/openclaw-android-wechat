package com.ws.kimi_server.link

import com.ws.kimi_server.link.http.ClientEnvelope
import com.ws.kimi_server.link.http.ServerTask

interface Link {
    var onEnvelope: (ServerTask) -> Unit
    var onDebugText: (String) -> Unit
    var onState: (String) -> Unit
    var onError: (String) -> Unit
    fun connect()
    fun requestReconnect(reason: String = "manual", immediate: Boolean = false)
    fun close()
    fun sendText(text: String)
    fun sendEnvelope(envelope: ClientEnvelope)
}
