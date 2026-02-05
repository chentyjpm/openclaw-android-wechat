package com.ws.kimi_server.apps.wechat

import android.content.Context
import android.os.SystemClock
import com.ws.kimi_server.acc.ServiceHolder
import com.ws.kimi_server.exec.AccessibilityAgent
import java.util.concurrent.Executors

object WeChatNotifyGate {
    private const val NAV_TIMEOUT_MS = 12_000L
    private const val NAV_RETRY_DELAY_MS = 500L
    private const val QUEUE_MAX = 10
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LanBot-WeChatNotify").apply { isDaemon = true }
    }
    private val queue = ArrayDeque<NotifyTask>()
    private val pendingKeys = HashSet<String>()
    @Volatile private var queuedCount = 0
    @Volatile private var activeKey: String? = null
    @Volatile private var activeUntil = 0L
    @Volatile private var activeReached = false
    @Volatile private var processing = false

    fun isLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (activeUntil != 0L && now >= activeUntil) {
            clearActive()
        }
        return processing || activeKey != null || queuedCount > 0
    }

    fun handleNotification(context: Context, title: String): Boolean {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return false
        val key = WeChatParser.makeChatId(cleaned)
        synchronized(this) {
            if (key == activeKey || pendingKeys.contains(key)) return false
            if (queue.size >= QUEUE_MAX) {
                val removed = queue.removeFirst()
                pendingKeys.remove(removed.key)
            }
            queue.addLast(NotifyTask(cleaned, key))
            queuedCount = queue.size
            pendingKeys.add(key)
        }
        startProcessing(context)
        return true
    }

    fun onChatSnapshot(snapshot: WeChatSnapshot) {
        if (snapshot.screen != "chat") return
        val key = snapshot.chatId ?: snapshot.title?.let { WeChatParser.makeChatId(it) } ?: return
        if (key == activeKey) {
            activeReached = true
        }
    }

    private fun startProcessing(context: Context) {
        synchronized(this) {
            if (processing) return
            processing = true
        }
        executor.execute { processQueue(context) }
    }

    private fun processQueue(context: Context) {
        while (true) {
            val task = synchronized(this) {
                val next = if (queue.isEmpty()) null else queue.removeFirst()
                if (next == null) {
                    processing = false
                    queuedCount = 0
                    return
                }
                pendingKeys.remove(next.key)
                queuedCount = queue.size
                activeKey = next.key
                activeReached = false
                activeUntil = SystemClock.uptimeMillis() + NAV_TIMEOUT_MS
                next
            }
            runNavToChat(context, task.title)
            clearActive()
        }
    }

    private fun runNavToChat(context: Context, title: String): Boolean {
        val agent = AccessibilityAgent()
        val deadline = SystemClock.uptimeMillis() + NAV_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (activeReached) return true
            val opened = if (!isWeChatForeground()) {
                agent.nav_to_app(context, WeChatSpec.PKG) || agent.nav_to_via_recents(context, WeChatSpec.PKG)
            } else {
                true
            }
            if (opened && WeChatAgent.ensureChat(title)) {
                return true
            }
            SystemClock.sleep(NAV_RETRY_DELAY_MS)
        }
        return activeReached
    }

    private fun clearActive() = synchronized(this) {
        activeKey = null
        activeUntil = 0L
        activeReached = false
    }

    private data class NotifyTask(val title: String, val key: String)

    private fun isWeChatForeground(): Boolean {
        val root = ServiceHolder.service?.rootInActiveWindow ?: return false
        return root.packageName?.toString() == WeChatSpec.PKG
    }
}
