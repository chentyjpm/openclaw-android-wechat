package com.ws.wx_server.link.http

import com.ws.wx_server.link.Link
import com.ws.wx_server.link.ServerConfig
import com.ws.wx_server.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RestLink(private val cfg: ServerConfig) : Link {
    override var onEnvelope: (ServerTask) -> Unit = {}
    override var onDebugText: (String) -> Unit = {}
    override var onState: (String) -> Unit = {}
    override var onError: (String) -> Unit = {}

    private val client = OkHttpClient()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var lastTaskId = 0L
    @Volatile private var lastAnnouncedState = "disconnected"
    private var pollFuture: ScheduledFuture<*>? = null
    private val pending = ArrayDeque<ClientEnvelope>()
    @Volatile private var serverBootId: String? = null

    override fun connect() {
        if (closed) closed = false
        if (pollFuture?.isDone == false) return
        updateState("connecting")
        sendHello()
        pollFuture = scheduler.scheduleAtFixedRate(
            { pullOnce() },
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    override fun requestReconnect(reason: String, immediate: Boolean) {
        if (closed) return
        Logger.d("RestLink.requestReconnect(): $reason")
        pollFuture?.cancel(false)
        pollFuture = null
        connect()
    }

    override fun close() {
        closed = true
        pollFuture?.cancel(false)
        pollFuture = null
        scheduler.shutdownNow()
        exec.shutdownNow()
        serverBootId = null
        updateState("disconnected")
    }

    override fun sendText(text: String) {
        // Debug text is not transported in HTTP mode.
        Logger.d("RestLink.sendText(): ignored")
    }

    override fun sendEnvelope(envelope: ClientEnvelope) {
        exec.execute {
            if (pending.size >= MAX_PENDING) {
                pending.removeFirst()
            }
            pending.addLast(envelope)
            flushPending()
        }
    }

    private fun sendHello() {
        val hello = Hello(
            device = android.os.Build.MODEL ?: "",
            appVersion = android.os.Build.VERSION.RELEASE ?: "",
            buildTs = System.currentTimeMillis().toString(),
        )
        sendEnvelope(ClientEnvelope(hello = hello))
    }

    private fun pullOnce() {
        if (closed) return
        val body = ClientPull(afterId = lastTaskId, limit = PULL_LIMIT).toJson().toString()
        val req = Request.Builder()
            .url("${baseUrl()}/client/pull")
            .post(body.toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError.invoke("HTTP ${resp.code}")
                    updateState("failed")
                    return
                }
                val raw = resp.body?.string() ?: ""
                val obj = JSONObject(raw)
                val bootId = obj.optString("server_boot_id")
                if (bootId.isNotBlank() && bootId != serverBootId) {
                    serverBootId = bootId
                    lastTaskId = 0L
                    Logger.i("RestLink.boot_id changed -> reset afterId")
                }
                val tasks = obj.optJSONArray("tasks") ?: JSONArray()
                Logger.d("RestLink.pullOnce afterId=$lastTaskId tasks=${tasks.length()}")
                for (i in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(i) ?: continue
                    val taskId = task.optLong("task_id")
                    if (taskId > lastTaskId) lastTaskId = taskId
                    val type = task.optString("type")
                    val payload = task.optJSONObject("payload") ?: JSONObject()
                    Logger.i("RestLink.task id=$taskId type=$type")
                    onEnvelope(ServerTask(taskId = taskId, type = type, payload = payload))
                }
                exec.execute { flushPending() }
                updateState("connected")
            }
        } catch (t: Throwable) {
            onError.invoke(t.message ?: "pull failed")
            updateState("failed")
        }
    }

    private fun flushPending() {
        if (closed || pending.isEmpty()) return
        val batch = ArrayList<ClientEnvelope>(pending.size)
        while (pending.isNotEmpty()) {
            batch.add(pending.removeFirst())
        }
        if (!push(batch)) {
            pending.addAll(batch)
        }
    }

    private fun push(envelopes: List<ClientEnvelope>): Boolean {
        if (closed || envelopes.isEmpty()) return false
        val body = ClientPush(envelopes).toJson().toString()
        val req = Request.Builder()
            .url("${baseUrl()}/client/push")
            .post(body.toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError.invoke("HTTP ${resp.code}")
                    updateState("failed")
                    return false
                } else {
                    updateState("connected")
                    return true
                }
            }
        } catch (t: Throwable) {
            onError.invoke(t.message ?: "push failed")
            updateState("failed")
            return false
        }
    }

    private fun baseUrl(): String {
        val scheme = if (cfg.useTls) "https" else "http"
        return "$scheme://${cfg.host}:${cfg.port}"
    }

    private fun updateState(newState: String) {
        if (lastAnnouncedState == newState) return
        lastAnnouncedState = newState
        onState.invoke(newState)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val POLL_INTERVAL_MS = 3_000L
        private const val PULL_LIMIT = 10
        private const val MAX_PENDING = 200
    }
}
