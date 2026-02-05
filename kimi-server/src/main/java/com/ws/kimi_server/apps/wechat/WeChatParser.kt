package com.ws.kimi_server.apps.wechat

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min

private val IMAGE_HINTS = listOf("图片", "image", "photo", "表情", "动图", "emoji")
private val VOICE_HINTS = listOf("语音", "voice", "时长", "语音信息")
private val VIDEO_HINTS = listOf("视频", "video")
private val STICKER_HINTS = listOf("表情", "sticker")

private object WXIds {
    val chatRow = listOf(
        "com.tencent.mm:id/bn1",
        "com.tencent.mm:id/bot",
        "com.tencent.mm:id/bop",
        "com.tencent.mm:id/igc"
    )
    val chatAvatar = listOf(
        "com.tencent.mm:id/bk1",
        "com.tencent.mm:id/ajr",
        "com.tencent.mm:id/aoq"
    )
    val chatSender = listOf(
        "com.tencent.mm:id/brc",
        "com.tencent.mm:id/fq",
        "com.tencent.mm:id/ao3"
    )
    val chatContent = listOf(
        "com.tencent.mm:id/bkl",
        "com.tencent.mm:id/bkf",
        "com.tencent.mm:id/ao9",
        "com.tencent.mm:id/iof"
    )
    val chatDesc = listOf(
        "com.tencent.mm:id/iof",
        "com.tencent.mm:id/bkm"
    )
    val title = listOf(
        "com.tencent.mm:id/obn",
        "com.tencent.mm:id/g1",
        "com.tencent.mm:id/gj",
        "com.tencent.mm:id/b8m",
        "com.tencent.mm:id/bb1"
    )
    val conversationRow = listOf(
        "com.tencent.mm:id/bm4",
        "com.tencent.mm:id/b4s",
        "com.tencent.mm:id/b4u",
        "com.tencent.mm:id/fc"
    )
    val unreadBadge = listOf(
        "com.tencent.mm:id/qi",
        "com.tencent.mm:id/avb",
        "com.tencent.mm:id/fj",
        "com.tencent.mm:id/j59"
    )
}

/** Snapshot for WeChat windows. */
data class WeChatMessage(
    val id: String,
    val type: String,
    val incoming: Boolean,
    val sender: String?,
    val text: String?,
    val desc: String?,
    val time: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val nodeRef: String,
    val hidden: Boolean = false,
    val ignore: Boolean = false,
    val sequence: Long? = null,
    val delivered: Boolean = false,
)

data class WeChatHomeItem(
    val title: String,
    val snippet: String?,
    val unread: Int?,
    val muted: Boolean,
    val pin: Boolean,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int,
    val nodeRef: String,
)

data class WeChatSnapshot(
    val screen: String,
    val title: String?,
    val chatId: String?,
    val messages: List<WeChatMessage>?,
    val homes: List<WeChatHomeItem>?,
    val isGroup: Boolean = false,
)

object WeChatParser {
    fun parse(context: Context, root: AccessibilityNodeInfo?, cls: String?): WeChatSnapshot? {
        if (root == null) return null
        val screenHint = classifyScreen(cls)
        val title = findTitle(root)
        val chatId = title?.let { makeChatId(it) }
        val messages = parseChat(context, root)
        if (messages.isNotEmpty()) {
            val isGroup = isGroupChat(messages, title)
            return WeChatSnapshot("chat", title, chatId, messages, null, isGroup)
        }
        val homes = parseConversationList(root)
        if (homes.isNotEmpty()) {
            return WeChatSnapshot("conversations", title, chatId, null, homes, false)
        }
        return WeChatSnapshot(screenHint, title, chatId, null, null, false)
    }

    private fun parseChat(context: Context, root: AccessibilityNodeInfo): List<WeChatMessage> {
        val rows = collectChatRows(root)
        if (rows.isEmpty()) return emptyList()
        val dm = context.resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val results = LinkedHashMap<String, WeChatMessage>()
        rows.forEach { row ->
            val msg = buildMessageFromRow(row, midX)
            if (msg != null && (msg.incoming || msg.type == "system")) {
                results[msg.id] = msg
            }
        }
        return results.values.toList()
    }

    private fun parseConversationList(root: AccessibilityNodeInfo): List<WeChatHomeItem> {
        val rows = collectConversationRows(root)
        if (rows.isEmpty()) return emptyList()
        val items = ArrayList<WeChatHomeItem>()
        rows.forEach { row ->
            val item = buildHomeItem(row)
            if (item != null) items.add(item)
        }
        return items
    }

    private fun collectChatRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rows = mutableListOf<AccessibilityNodeInfo>()
        val seenBounds = HashSet<String>()
        WXIds.chatRow.forEach { id ->
            val nodes = safeFindById(root, id)
            nodes?.forEach { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@forEach
                val key = bounds.toShortString()
                if (seenBounds.add(key)) rows.add(node)
            }
        }
        // Fallback: walk tree, pick RelativeLayouts containing known content id
        if (rows.isEmpty()) {
            walk(root) { node ->
                if (node.className?.toString()?.contains("RelativeLayout", true) == true) {
                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)
                    if (nodeBounds.isEmpty) return@walk
                    val content = findFirstById(node, WXIds.chatContent, nodeBounds)
                    if (content != null) {
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            val key = bounds.toShortString()
                            if (seenBounds.add(key)) rows.add(node)
                        }
                    }
                }
            }
        }
        rows.sortBy { boundsTop(it) }
        return rows
    }

    private fun collectConversationRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rows = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        WXIds.conversationRow.forEach { id ->
            val nodes = safeFindById(root, id)
            nodes?.forEach { node ->
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@forEach
                val key = bounds.toShortString()
                if (seen.add(key)) rows.add(node)
            }
        }
        if (rows.isEmpty()) {
            walk(root) { node ->
                if (node.className?.toString()?.contains("LinearLayout", true) == true && node.isClickable) {
                    val texts = collectTexts(node)
                    if (texts.size >= 1) {
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            val key = bounds.toShortString()
                            if (seen.add(key)) rows.add(node)
                        }
                    }
                }
            }
        }
        rows.sortBy { boundsTop(it) }
        return rows
    }

    private fun buildMessageFromRow(row: AccessibilityNodeInfo, midX: Float): WeChatMessage? {
        val rowBounds = Rect()
        row.getBoundsInScreen(rowBounds)
        if (rowBounds.isEmpty) return null

        val rowBoundsCopy = Rect(rowBounds)
        val avatarNode = findFirstById(row, WXIds.chatAvatar, rowBoundsCopy)
        val avatarBounds = Rect()
        avatarNode?.getBoundsInScreen(avatarBounds)
        val incoming = when {
            avatarNode != null -> avatarBounds.centerX() <= midX
            else -> rowBounds.centerX() <= midX
        }

        val senderNode = findFirstById(row, WXIds.chatSender, rowBoundsCopy)
        val sender = senderNode?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val contentNode = findFirstById(row, WXIds.chatContent, rowBoundsCopy)
        val descNode = contentNode ?: findFirstById(row, WXIds.chatDesc, rowBoundsCopy)
        if (contentNode == null && descNode == null) {
            val texts = collectTexts(row)
            if (avatarNode == null && texts.isNotEmpty()) {
                val bounds = mergeBounds(texts.map { it.bounds }) ?: rowBounds
                val text = texts.joinToString(" ") { it.text }.trim()
                if (text.isNotEmpty() && isCentered(bounds, rowBounds, midX)) {
                    val nodeRef = nodeRefFor(texts.first().node)
                    val id = buildMessageId(bounds, true, "system", null, text, null, null)
                    return WeChatMessage(
                        id = id,
                        type = "system",
                        incoming = true,
                        sender = null,
                        text = text,
                        desc = null,
                        time = null,
                        bounds = bounds,
                        clickable = false,
                        longClickable = false,
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                        nodeRef = nodeRef,
                    )
                }
            }
            return null
        }
        val contentText = contentNode?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val descText = descNode?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val messageText = contentText
        val timeText: String? = null
        val desc = descText

        val type = when {
            !messageText.isNullOrEmpty() && containsHint(messageText, IMAGE_HINTS) -> "image"
            !messageText.isNullOrEmpty() && containsHint(messageText, VOICE_HINTS) -> "voice"
            !messageText.isNullOrEmpty() && containsHint(messageText, VIDEO_HINTS) -> "video"
            !messageText.isNullOrEmpty() && containsHint(messageText, STICKER_HINTS) -> "sticker"
            messageText.isNullOrEmpty() && desc != null && containsHint(desc, VOICE_HINTS) -> "voice"
            messageText.isNullOrEmpty() && desc != null && containsHint(desc, IMAGE_HINTS) -> "image"
            messageText.isNullOrEmpty() && desc != null && containsHint(desc, VIDEO_HINTS) -> "video"
            messageText.isNullOrEmpty() && hasImageView(row, avatarBounds) -> "image"
            messageText.isNullOrEmpty() && desc != null -> "unknown"
            else -> "text"
        }

        if (type == "text" && messageText == null) return null
        if (type == "image" && contentNode == null && desc == null) return null

        val bubbleBounds = computeBubbleBounds(contentNode, rowBounds)
        val incomingOut = if (!bubbleBounds.isEmpty) {
            bubbleBounds.centerX() <= midX
        } else {
            incoming
        }
        val targetNode = contentNode ?: descNode ?: row
        val nodeRef = nodeRefFor(targetNode)
        val clickable = targetNode.isClickable || row.isClickable
        val longClickable = targetNode.isLongClickable || row.isLongClickable
        val senderOut = sender
        val id = buildMessageId(bubbleBounds, incomingOut, type, senderOut, messageText, desc, timeText)

        return WeChatMessage(
            id = id,
            type = type,
            incoming = incomingOut,
            sender = senderOut,
            text = messageText,
            desc = desc,
            time = timeText,
            bounds = bubbleBounds,
            clickable = clickable,
            longClickable = longClickable,
            centerX = bubbleBounds.centerX(),
            centerY = bubbleBounds.centerY(),
            nodeRef = nodeRef,
        )
    }

    private fun buildHomeItem(row: AccessibilityNodeInfo): WeChatHomeItem? {
        val bounds = Rect()
        row.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        val texts = collectTexts(row)
        if (texts.isEmpty()) return null
        val sorted = texts.sortedBy { it.bounds.left }
        val title = sorted.first().text.trim()
        if (title.isEmpty()) return null
        val snippet = sorted.drop(1).joinToString(" ") { it.text }.trim().ifEmpty { null }
        val unread = extractUnread(row, texts)
        val nodeRef = nodeRefFor(row)
        return WeChatHomeItem(
            title = title,
            snippet = snippet,
            unread = unread,
            muted = false,
            pin = false,
            bounds = bounds,
            centerX = bounds.centerX(),
            centerY = bounds.centerY(),
            nodeRef = nodeRef,
        )
    }

    private fun extractUnread(row: AccessibilityNodeInfo, texts: List<TextNode>): Int? {
        WXIds.unreadBadge.forEach { id ->
            val nodes = safeFindById(row, id)
            nodes?.firstOrNull()?.let { badge ->
                val text = badge.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && text.all { it.isDigit() }) return text.toInt()
                val desc = badge.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty()) {
                    val digits = desc.filter { it.isDigit() }
                    if (digits.isNotEmpty()) return digits.toInt()
                }
            }
        }
        texts.forEach { node ->
            val value = node.text
            if (value.contains("未读") || value.contains("unread", true)) {
                val digits = value.filter { it.isDigit() }
                if (digits.isNotEmpty()) return digits.toInt()
            }
            if (value.matches(Regex("""\(\d{1,3}\)"""))) {
                return value.filter { it.isDigit() }.toInt()
            }
            if (value.matches(Regex("""\d{1,3}"""))) {
                return value.toInt()
            }
        }
        return null
    }

    private data class TextNode(val node: AccessibilityNodeInfo, val text: String, val bounds: Rect)

    private fun collectTexts(node: AccessibilityNodeInfo): List<TextNode> {
        val list = ArrayList<TextNode>()
        walk(node) { current ->
            val text = current.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                val bounds = Rect()
                current.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    list.add(TextNode(current, text, bounds))
                }
            }
        }
        return list
    }

    private fun computeBubbleBounds(contentNode: AccessibilityNodeInfo?, fallback: Rect): Rect {
        val bounds = Rect()
        if (contentNode != null) {
            contentNode.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) return bounds
        }
        return fallback
    }

    private fun mergeBounds(bounds: List<Rect>): Rect? {
        if (bounds.isEmpty()) return null
        val res = Rect(bounds[0])
        for (i in 1 until bounds.size) {
            val b = bounds[i]
            res.left = min(res.left, b.left)
            res.top = min(res.top, b.top)
            res.right = max(res.right, b.right)
            res.bottom = max(res.bottom, b.bottom)
        }
        return res
    }

    private fun isCentered(bounds: Rect, row: Rect, midX: Float): Boolean {
        if (bounds.isEmpty || row.isEmpty) return false
        val tolerance = row.width() * 0.18f
        return kotlin.math.abs(bounds.centerX() - midX) <= tolerance
    }

    private fun nodeRefFor(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrEmpty()) return viewId
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) return bounds.toShortString()
        return node.className?.toString() ?: ""
    }

    private fun buildMessageId(bounds: Rect, incoming: Boolean, type: String, sender: String?, text: String?, desc: String?, time: String?): String {
        val key = listOf(
            bounds.toShortString(),
            incoming.toString(),
            type,
            sender ?: "",
            text ?: "",
            desc ?: "",
            time ?: ""
        ).joinToString("|")
        return key.hashCode().toUInt().toString(16)
    }

    private fun classifyScreen(cls: String?): String = when (cls) {
        WeChatSpec.Classes.ChattingUI -> "chat"
        WeChatSpec.Classes.ConversationList, WeChatSpec.Classes.LauncherUI -> "conversations"
        WeChatSpec.Classes.SearchUI -> "search"
        else -> cls ?: "unknown"
    }

    fun makeChatId(title: String): String = ("wx:" + title.trim().lowercase()).hashCode().toUInt().toString(16)

    private fun findTitle(root: AccessibilityNodeInfo): String? {
        WXIds.title.forEach { id ->
            val nodes = safeFindById(root, id)
            val text = nodes?.firstOrNull()?.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        val texts = collectTexts(root)
        return texts.firstOrNull { it.bounds.top < 200 && it.text.length in 2..20 }?.text
    }

    private fun isGroupChat(messages: List<WeChatMessage>, title: String?): Boolean {
        val senders = messages.mapNotNull { it.sender?.takeIf { name -> name.isNotBlank() } }.toSet()
        if (senders.size > 1) return true
        if (senders.size == 1) {
            val sender = senders.first()
            if (!title.isNullOrBlank() && !sender.equals(title, ignoreCase = true)) return true
        }
        return false
    }

    private fun safeFindById(node: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo>? = try {
        node.findAccessibilityNodeInfosByViewId(id)
    } catch (_: Throwable) {
        null
    }

    private fun findFirstById(node: AccessibilityNodeInfo, ids: List<String>, container: Rect): AccessibilityNodeInfo? {
        ids.forEach { id ->
            val list = safeFindById(node, id)
            if (!list.isNullOrEmpty()) {
                val candidate = list.firstOrNull { info ->
                    val bounds = Rect(); info.getBoundsInScreen(bounds)
                    !bounds.isEmpty && isInside(bounds, container)
                }
                if (candidate != null) return candidate
            }
        }
        return null
    }

    private fun isInside(child: Rect, container: Rect): Boolean {
        val tolerance = 6
        return child.left >= container.left - tolerance &&
            child.right <= container.right + tolerance &&
            child.top >= container.top - tolerance &&
            child.bottom <= container.bottom + tolerance
    }

    private fun containsHint(text: String, hints: List<String>): Boolean {
        val lower = text.lowercase()
        return hints.any { lower.contains(it.lowercase()) }
    }

    private fun hasImageView(node: AccessibilityNodeInfo, excludeBounds: Rect?): Boolean {
        var found = false
        walk(node) { current ->
            if (current.className?.toString()?.contains("ImageView", true) == true && current.text.isNullOrEmpty()) {
                val bounds = Rect(); current.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                    if (excludeBounds != null && intersectRatio(bounds, excludeBounds) > 0.8f) return@walk
                    found = true
                }
            }
        }
        return found
    }

    private fun intersectRatio(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return 0f
        val area = width.toFloat() * height.toFloat()
        val base = (a.width() * a.height()).toFloat().takeIf { it > 0f } ?: return 0f
        return area / base
    }

    private fun boundsTop(node: AccessibilityNodeInfo): Int {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.top
    }

    private fun walk(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(node)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            action(current)
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    fun toJson(s: WeChatSnapshot): JSONObject {
        val o = JSONObject()
        o.put("screen", s.screen)
        o.put("title", s.title)
        o.put("chatId", s.chatId)
        s.messages?.let { list ->
            val arr = JSONArray()
            list.forEach { m ->
                val mo = JSONObject()
                mo.put("id", m.id)
                mo.put("type", m.type)
                mo.put("incoming", m.incoming)
                mo.put("sender", m.sender)
                mo.put("text", m.text)
                mo.put("desc", m.desc)
                mo.put("time", m.time)
                mo.put("bounds", m.bounds.toShortString())
                mo.put("clickable", m.clickable)
                mo.put("longClickable", m.longClickable)
                mo.put("centerX", m.centerX)
                mo.put("centerY", m.centerY)
                mo.put("nodeRef", m.nodeRef)
                mo.put("hidden", m.hidden)
                mo.put("ignore", m.ignore)
                mo.put("delivered", m.delivered)
                m.sequence?.let { mo.put("sequence", it) }
                arr.put(mo)
            }
            o.put("messages", arr)
        }
        s.homes?.let { list ->
            val arr = JSONArray()
            list.forEach { h ->
                val ho = JSONObject()
                ho.put("title", h.title)
                ho.put("snippet", h.snippet)
                ho.put("unread", h.unread)
                ho.put("muted", h.muted)
                ho.put("pin", h.pin)
                ho.put("bounds", h.bounds.toShortString())
                ho.put("centerX", h.centerX)
                ho.put("centerY", h.centerY)
                ho.put("nodeRef", h.nodeRef)
                arr.put(ho)
            }
            o.put("homes", arr)
        }
        return o
    }

}
