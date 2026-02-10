package com.ws.wx_server.apps.wechat

import com.ws.wx_server.exec.TaskBridge
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.text.Charsets

/**
 * Per-chat message history for deduplication and sequencing.
 */
internal object WeChatHistory {
    private const val MAX_CONVERSATIONS = 24
    private const val MAX_MESSAGES_PER_CHAT = 200
    private const val PREV_ANCHOR = "__start__"
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    private val conversations = object : LinkedHashMap<String, Conversation>(MAX_CONVERSATIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation>?): Boolean {
            return size > MAX_CONVERSATIONS
        }
    }

    fun process(snapshot: WeChatSnapshot): WeChatSnapshot {
        if (snapshot.screen != "chat") return snapshot
        val messages = snapshot.messages ?: return snapshot
        val chatKey = chatKey(snapshot) ?: return snapshot
        val processed = synchronized(this) {
            val conversation = conversations.getOrPut(chatKey) { Conversation() }
            conversation.apply(chatKey, snapshot.title, snapshot.isGroup, messages)
        }
        return snapshot.copy(messages = processed)
    }

    fun hasConversation(chatKey: String): Boolean = synchronized(this) {
        conversations.containsKey(chatKey)
    }

    data class MatchStats(
        val matched: Int,
        val gap: Int,
    )

    fun matchStats(chatKey: String, messages: List<WeChatMessage>): MatchStats = synchronized(this) {
        val conversation = conversations[chatKey] ?: return MatchStats(0, 0)
        conversation.matchStats(messages)
    }

    private fun chatKey(snapshot: WeChatSnapshot): String? {
        val key = snapshot.chatId?.takeIf { it.isNotBlank() } ?: snapshot.title?.takeIf { it.isNotBlank() }
        return key?.trim()?.takeIf { it.isNotEmpty() }
    }

    private class Conversation {
        private val entries = ArrayList<MessageEntry>()
        private val baseIndex = HashMap<String, MutableList<MessageEntry>>()
        private val contentIndex = HashMap<String, MutableList<MessageEntry>>()
        private val chainIndex = HashMap<Pair<String, String>, MutableList<MessageEntry>>()
        private var nextSeq = 1L
        private var initialized = false
        private var latestTitle: String? = null
        private var latestIsGroup: Boolean = false

        fun apply(chatKey: String, title: String?, isGroup: Boolean, messages: List<WeChatMessage>): List<WeChatMessage> {
            if (messages.isEmpty()) return emptyList()
            latestTitle = title?.takeIf { it.isNotBlank() } ?: latestTitle ?: chatKey
            latestIsGroup = isGroup

            val items = buildItems(messages)
            val prevBases = computePrevBases(items)
            val baseline = !initialized
            val baselineSendIndex = if (baseline) findBaselineSendIndex(items) else -1
            val usedEntries = HashSet<MessageEntry>()
            val result = ArrayList<WeChatMessage>(messages.size)

            items.forEachIndexed { idx, item ->
                val original = item.message
                val fingerprint = item.fingerprint
                if (fingerprint == null) {
                    result.add(original.copy(hidden = true, ignore = true, delivered = true))
                    return@forEachIndexed
                }
                val prevBase = prevBases[idx]
                if (baseline) {
                    val shouldSendBaseline = idx == baselineSendIndex && shouldSend(original)
                    val matched = findMatch(prevBase, fingerprint, usedEntries)
                    if (matched != null) {
                        if (matched.senderKey.isBlank() && fingerprint.senderKey.isNotBlank()) {
                            matched.senderKey = fingerprint.senderKey
                        }
                        registerContentAlias(matched, fingerprint.contentHash)
                        usedEntries.add(matched)
                        val out = original.copy(
                            id = matched.id,
                            sequence = matched.sequence,
                            hidden = !shouldSendBaseline,
                            ignore = !shouldSendBaseline,
                            delivered = shouldSendBaseline,
                        )
                        result.add(out)
                        if (shouldSendBaseline) {
                            TaskBridge.pushMessage(chatKey, latestTitle, latestIsGroup, out)
                        }
                    } else {
                        val entry = appendEntry(fingerprint, prevBase)
                        usedEntries.add(entry)
                        val out = original.copy(
                            id = entry.id,
                            sequence = entry.sequence,
                            hidden = !shouldSendBaseline,
                            ignore = !shouldSendBaseline,
                            delivered = shouldSendBaseline,
                        )
                        result.add(out)
                        if (shouldSendBaseline) {
                            TaskBridge.pushMessage(chatKey, latestTitle, latestIsGroup, out)
                        }
                    }
                    return@forEachIndexed
                }

                val matched = findMatch(prevBase, fingerprint, usedEntries)
                if (matched != null) {
                    if (matched.senderKey.isBlank() && fingerprint.senderKey.isNotBlank()) {
                        matched.senderKey = fingerprint.senderKey
                    }
                    registerContentAlias(matched, fingerprint.contentHash)
                    usedEntries.add(matched)
                    result.add(
                        original.copy(
                            id = matched.id,
                            sequence = matched.sequence,
                            hidden = true,
                            ignore = true,
                            delivered = true,
                        )
                    )
                    return@forEachIndexed
                }

                val entry = appendEntry(fingerprint, prevBase)
                usedEntries.add(entry)
                val shouldSend = shouldSend(original)
                val out = original.copy(
                    id = entry.id,
                    sequence = entry.sequence,
                    delivered = shouldSend,
                    hidden = !shouldSend,
                    ignore = !shouldSend,
                )
                result.add(out)
                if (shouldSend) {
                    TaskBridge.pushMessage(chatKey, latestTitle, latestIsGroup, out)
                }
            }

            trim()
            initialized = true
            return result
        }

        fun matchStats(messages: List<WeChatMessage>): MatchStats {
            if (messages.isEmpty()) return MatchStats(0, 0)
            val items = buildItems(messages)
            val prevBases = computePrevBases(items)
            val usedEntries = HashSet<MessageEntry>()
            var gap = 0
            var matchedCount = 0
            items.forEachIndexed { idx, item ->
                val fingerprint = item.fingerprint ?: return@forEachIndexed
                val msg = item.message
                if (!shouldSend(msg)) return@forEachIndexed
                val prevBase = prevBases[idx]
                val hit = findMatch(prevBase, fingerprint, usedEntries)
                if (hit != null) {
                    usedEntries.add(hit)
                    matchedCount++
                } else {
                    gap++
                }
            }
            return MatchStats(matched = matchedCount, gap = gap)
        }

        private fun findMatch(
            prevBase: String,
            fingerprint: Fingerprint,
            used: MutableSet<MessageEntry>?
        ): MessageEntry? {
            val chainKey = prevBase to fingerprint.baseHash
            val chain = chainIndex[chainKey]
            val chainMatch = chain?.firstOrNull { entry ->
                (used == null || entry !in used) && matchesSender(entry.senderKey, fingerprint.senderKey)
            }
            if (chainMatch != null) return chainMatch

            val contentList = contentIndex[fingerprint.contentHash]
            val contentMatch = contentList?.firstOrNull { entry ->
                (used == null || entry !in used) && matchesSender(entry.senderKey, fingerprint.senderKey)
            }
            if (contentMatch != null) return contentMatch

            val baseList = baseIndex[fingerprint.baseHash]
            if (baseList.isNullOrEmpty()) return null
            if (baseList.size == 1) {
                val entry = baseList.first()
                if ((used == null || entry !in used) && matchesSender(entry.senderKey, fingerprint.senderKey)) {
                    return entry
                }
                return null
            }
            val exactPos = baseList.firstOrNull { entry ->
                (used == null || entry !in used) &&
                    matchesSender(entry.senderKey, fingerprint.senderKey) &&
                    matchesPos(entry.posHash, fingerprint.posHash)
            }
            if (exactPos != null) return exactPos
            return baseList.firstOrNull { entry ->
                (used == null || entry !in used) && matchesSender(entry.senderKey, fingerprint.senderKey)
            }
        }

        private fun appendEntry(fingerprint: Fingerprint, prevBase: String): MessageEntry {
            val entry = MessageEntry(
                id = buildStableId(),
                sequence = nextSeq++,
                baseHash = fingerprint.baseHash,
                prevBaseHash = prevBase,
                contentHashes = linkedSetOf(fingerprint.contentHash),
                senderKey = fingerprint.senderKey,
                posHash = fingerprint.posHash,
            )
            entries.add(entry)
            baseIndex.getOrPut(entry.baseHash) { mutableListOf() }.add(entry)
            contentIndex.getOrPut(fingerprint.contentHash) { mutableListOf() }.add(entry)
            chainIndex.getOrPut(entry.prevBaseHash to entry.baseHash) { mutableListOf() }.add(entry)
            return entry
        }

        private fun registerContentAlias(entry: MessageEntry, contentHash: String) {
            if (entry.contentHashes.add(contentHash)) {
                contentIndex.getOrPut(contentHash) { mutableListOf() }.add(entry)
            }
        }

        private fun trim() {
            while (entries.size > MAX_MESSAGES_PER_CHAT) {
                val removed = entries.removeAt(0)
                baseIndex[removed.baseHash]?.apply {
                    removeAll { it === removed }
                    if (isEmpty()) baseIndex.remove(removed.baseHash)
                }
                val chainKey = removed.prevBaseHash to removed.baseHash
                chainIndex[chainKey]?.apply {
                    removeAll { it === removed }
                    if (isEmpty()) chainIndex.remove(chainKey)
                }
                removed.contentHashes.forEach { hash ->
                    contentIndex[hash]?.apply {
                        removeAll { it === removed }
                        if (isEmpty()) contentIndex.remove(hash)
                    }
                }
            }
        }

        private fun buildItems(messages: List<WeChatMessage>): List<Item> {
            val items = ArrayList<Item>(messages.size)
            messages.forEach { msg ->
                val normalized = normalize(msg)
                val fingerprint = if (isValidForHistory(normalized)) buildFingerprint(normalized) else null
                items.add(Item(normalized, fingerprint))
            }
            return items
        }

        private fun computePrevBases(items: List<Item>): List<String> {
            val prevBases = ArrayList<String>(items.size)
            var runningPrev = PREV_ANCHOR
            items.forEach { item ->
                prevBases.add(runningPrev)
                item.fingerprint?.let { runningPrev = it.baseHash }
            }
            return prevBases
        }

        private fun findBaselineSendIndex(items: List<Item>): Int {
            for (i in items.indices.reversed()) {
                val msg = items[i].message
                if (items[i].fingerprint == null) continue
                if (!shouldSend(msg)) continue
                return i
            }
            return -1
        }

        private fun shouldSend(message: WeChatMessage): Boolean {
            return message.incoming
        }

        private fun matchesPos(existing: String, candidate: String): Boolean {
            if (existing.isBlank() || candidate.isBlank()) return true
            return existing == candidate
        }
    }

    private data class Item(
        val message: WeChatMessage,
        val fingerprint: Fingerprint?,
    )

    private data class MessageEntry(
        val id: String,
        val sequence: Long,
        val baseHash: String,
        var prevBaseHash: String,
        val contentHashes: MutableSet<String>,
        var senderKey: String,
        val posHash: String,
    )

    private data class Fingerprint(
        val baseHash: String,
        val contentHash: String,
        val senderKey: String,
        val posHash: String,
    )

    private fun normalize(msg: WeChatMessage): WeChatMessage =
        msg.copy(hidden = false, ignore = false, sequence = null)

    private fun buildFingerprint(msg: WeChatMessage): Fingerprint {
        val direction = if (msg.incoming) "in" else "out"
        val type = msg.type.lowercase(Locale.US)
        val senderKey = normalizeSender(msg.sender, type)
        val text = normalizeForHash(msg.text)
        val desc = normalizeForHash(msg.desc)
        val baseHash = hashPayload(listOf(direction, type, text, desc))
        val contentHash = hashPayload(listOf(direction, type, senderKey.ifBlank { "?" }, text, desc))
        val posHash = buildPosHash(msg)
        return Fingerprint(baseHash = baseHash, contentHash = contentHash, senderKey = senderKey, posHash = posHash)
    }

    private fun normalizeSender(value: String?, type: String): String {
        val normalized = normalizeForHash(value)
        return if (normalized.isEmpty()) "" else normalized
    }

    private fun buildPosHash(msg: WeChatMessage): String {
        val b = msg.bounds
        if (b.isEmpty) return ""
        val step = 24
        val cx = b.centerX() / step
        val cy = b.centerY() / step
        val w = b.width() / step
        val h = b.height() / step
        return "$cx,$cy,$w,$h"
    }

    private fun normalizeForHash(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().lowercase(Locale.US).replace(WHITESPACE_REGEX, " ")
    }

    private fun hashPayload(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val joined = parts.joinToString("|")
        val hashed = digest.digest(joined.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(hashed.size * 2)
        hashed.forEach { b ->
            val hex = (b.toInt() and 0xFF).toString(16).padStart(2, '0')
            sb.append(hex)
        }
        return sb.toString()
    }

    private fun matchesSender(existing: String, candidate: String): Boolean {
        if (existing.isNotBlank() && candidate.isNotBlank()) {
            return existing == candidate
        }
        return true
    }

    private fun isValidForHistory(msg: WeChatMessage): Boolean {
        if (!msg.incoming) return false
        if (msg.type.equals("system", ignoreCase = true)) return false
        val hasContent = !msg.text.isNullOrBlank() || !msg.desc.isNullOrBlank()
        val textInvalid = msg.text?.equals("none", ignoreCase = true) == true
        val descInvalid = msg.desc?.equals("none", ignoreCase = true) == true
        return hasContent && !textInvalid && !descInvalid
    }

    private fun buildStableId(): String = "wx-" + UUID.randomUUID().toString()
}
