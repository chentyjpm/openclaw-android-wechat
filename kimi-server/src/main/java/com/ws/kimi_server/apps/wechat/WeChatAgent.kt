package com.ws.kimi_server.apps.wechat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.ws.kimi_server.acc.ServiceHolder
import com.ws.kimi_server.util.Logger
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque

/**
 * Helpers for acting on top of the WeChat UI.
 */
private val SEND_BUTTON_TEXTS = listOf("发送", "Send")

object WeChatAgent {
    private val clickDebounce = ClickDebounce(intervalMs = 1500)
    private const val SCROLL_TO_END_MAX = 6
    private const val SCROLL_STEP_DELAY_MS = 420L
    private const val MAX_GAP_MESSAGES = 20
    private const val SCROLL_COOLDOWN_MS = 6000L
    private const val SCROLL_NO_CHANGE_COOLDOWN_MS = 5000L
    private val scrollStates = HashMap<String, ScrollState>()
    private const val HOME_AUTO_OPEN_COOLDOWN_MS = 8000L
    private val homeStates = HashMap<String, HomeState>()
    private val lastAutoOpen = HashMap<String, Long>()
    private val lastScreenByChat = HashMap<String, String>()

    private const val ID_MORE_FUNCTION = "com.tencent.mm:id/bjz"
    private const val ID_ATTACHMENT_ICON = "com.tencent.mm:id/a10"
    private const val ID_ATTACHMENT_LABEL = "com.tencent.mm:id/a12"
    private const val ID_MEDIA_THUMB = "com.tencent.mm:id/jec"
    private const val ID_MEDIA_CHECKBOX = "com.tencent.mm:id/jdh"
    private const val ID_SEND_BUTTON = "com.tencent.mm:id/kaq"
    private const val ID_CONVERSATION_TITLE = "com.tencent.mm:id/kbq"
    private const val TAP_MORE_X = 0.92f
    private const val TAP_MORE_Y = 0.95f
    private const val TAP_ALBUM_X = 0.15f
    private const val TAP_ALBUM_Y = 0.73f
    private const val TAP_MEDIA_X = 0.14f
    private const val TAP_MEDIA_Y = 0.20f
    private const val TAP_SEND_X = 0.90f
    private const val TAP_SEND_Y = 0.96f
    private const val SCREEN_WIDTH = 1080f
    private const val SCREEN_HEIGHT = 2376f
    private const val IMAGE_STATE_TIMEOUT_MS = 10_000L
    private const val IMAGE_FLOW_TIMEOUT_MS = 45_000L
    private const val IMAGE_STATE_POLL_MS = 280L
    private const val IMAGE_RECOVER_DELAY_MS = 800L
    private const val TAP_RETRY_COUNT = 1
    private const val TAP_RETRY_DELAY_MS = 180L
    private val ALBUM_TITLE_HINTS = listOf("图像和视频", "图片和视频", "Image and Video", "Images and Videos")

    enum class ImageSendStage {
        ATTACH_PANEL,
        ALBUM_OPEN,
        SELECTED,
        SENT,
    }

    private enum class ImageSendState {
        CHAT,
        ATTACH_PANEL,
        ALBUM_PICK,
        ALBUM_SELECTED,
        UNKNOWN,
    }

    /**
     * Mark seen messages and flag duplicates as hidden/ignored.
     */
    fun filterSnapshot(service: AccessibilityService, snapshot: WeChatSnapshot, allowScroll: Boolean): WeChatSnapshot {
        if (snapshot.screen != "chat") return WeChatHistory.process(snapshot)
        WeChatNotifyGate.onChatSnapshot(snapshot)
        val locked = WeChatNotifyGate.isLocked()
        val scrollAllowed = allowScroll && !locked
        val chatKey = snapshot.chatId?.takeIf { it.isNotBlank() }
            ?: snapshot.title?.takeIf { it.isNotBlank() }
            ?: return WeChatHistory.process(snapshot)
        var working = snapshot
        val hasContext = WeChatHistory.hasConversation(chatKey)
        val prevScreen = lastScreenByChat[chatKey]
        val chatJustEntered = prevScreen != "chat"
        lastScreenByChat[chatKey] = "chat"
        if ((scrollAllowed || chatJustEntered) && !locked) {
            val sig = snapshotSignature(working)
            if (shouldScrollDown(chatKey, sig)) {
                working = scrollToEndAndRefresh(service, working, chatKey)
            }
            if (hasContext) {
                val stats = WeChatHistory.matchStats(chatKey, working.messages.orEmpty())
                if (stats.matched == 0 && stats.gap > 0) {
                    val maxUp = stats.gap.coerceAtMost(MAX_GAP_MESSAGES)
                    if (shouldScrollUp(chatKey, snapshotSignature(working))) {
                        val res = scrollUpForAnchor(service, working, chatKey, maxUp)
                        working = res.snapshot
                    }
                }
            }
        }
        return WeChatHistory.process(working)
    }

    fun maybeOpenChatFromHome(service: AccessibilityService, snapshot: WeChatSnapshot): Boolean {
        if (snapshot.screen != "conversations" && snapshot.screen != "home") return false
        val homes = snapshot.homes ?: return false
        if (homes.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        var target: WeChatHomeItem? = null
        homes.forEach { item ->
            val title = item.title.trim()
            if (title.isEmpty()) return@forEach
            val chatKey = WeChatParser.makeChatId(title)
            if (!WeChatHistory.hasConversation(chatKey)) return@forEach
            val key = normalizeTitle(title)
            val prev = homeStates[key]
            val prevUnread = prev?.unread ?: 0
            val curUnread = item.unread ?: 0
            val changed = prev == null || curUnread > prevUnread || item.snippet != prev.snippet
            if (changed && target == null) {
                val last = lastAutoOpen[key] ?: 0L
                if (now - last >= HOME_AUTO_OPEN_COOLDOWN_MS) {
                    target = item
                }
            }
        }
        homes.forEach { item ->
            val key = normalizeTitle(item.title)
            homeStates[key] = HomeState(item.unread ?: 0, item.snippet)
        }
        if (target == null) return false
        val openKey = normalizeTitle(target.title)
        lastAutoOpen[openKey] = now
        val targetRaw = target.title.trim()
        if (targetRaw.isEmpty()) return false
        return openChatFromHome(normalizeTitle(targetRaw), targetRaw)
    }

    fun ensureChat(targetTitle: String?): Boolean {
        if (targetTitle.isNullOrBlank()) return true
        if (!isWeChatForeground()) return false
        val trimmedTarget = targetTitle.trim()
        val normalizedTarget = normalizeTitle(trimmedTarget)
        repeat(4) {
            val snapshot = currentSnapshot()
            if (snapshot == null) {
                SystemClock.sleep(250)
                return@repeat
            }
            when (snapshot.screen.lowercase(Locale.US)) {
                "chat" -> {
                    val current = snapshot.title
                    if (current != null && titlesMatch(current, normalizedTarget)) {
                        return true
                    }
                    if (!navigateBackToHome()) return false
                }
                "conversations", "home" -> {
                    if (openChatFromHome(normalizedTarget, trimmedTarget)) {
                        if (waitForChat(normalizedTarget)) return true
                    }
                    return false
                }
                else -> {
                    if (!navigateBackToHome()) return false
                }
            }
        }
        return false
    }

    fun sendLatestImage(onStage: ((ImageSendStage) -> Unit)? = null): Boolean {
        val service = ServiceHolder.service ?: return false
        if (!clickDebounce.allow()) {
            Logger.w("WeChatAgent.sendLatestImage throttled")
            return false
        }
        return runImageSendStateMachine(service, onStage)
    }

    private fun runImageSendStateMachine(
        service: AccessibilityService,
        onStage: ((ImageSendStage) -> Unit)?,
    ): Boolean {
        val start = SystemClock.uptimeMillis()
        var lastState = ImageSendState.UNKNOWN
        var stateSince = start
        val sentStages = HashSet<ImageSendStage>()
        while (SystemClock.uptimeMillis() - start < IMAGE_FLOW_TIMEOUT_MS) {
            val now = SystemClock.uptimeMillis()
            val current = detectImageSendState()
            if (current != lastState) {
                lastState = current
                stateSince = now
            }
            emitStageFromState(current, sentStages, onStage)
            if (now - stateSince > IMAGE_STATE_TIMEOUT_MS) {
                if (!recoverImageState(service, current)) return false
                stateSince = SystemClock.uptimeMillis()
                continue
            }
            when (current) {
                ImageSendState.CHAT -> {
                    if (!openAttachmentPanel()) {
                        continue
                    }
                    if (!waitForImageState(setOf(ImageSendState.ATTACH_PANEL), 2_000L)) {
                        continue
                    }
                }
                ImageSendState.ATTACH_PANEL -> {
                    if (!openAlbum()) {
                        continue
                    }
                    if (!waitForImageState(setOf(ImageSendState.ALBUM_PICK, ImageSendState.ALBUM_SELECTED), 3_000L)) {
                        continue
                    }
                }
                ImageSendState.ALBUM_PICK -> {
                    if (!selectFirstMedia()) {
                        continue
                    }
                    if (!waitForImageState(setOf(ImageSendState.ALBUM_SELECTED), 2_000L)) {
                        performBack(service)
                        continue
                    }
                }
                ImageSendState.ALBUM_SELECTED -> {
                    if (!tapSendButton()) {
                        performBack(service)
                        continue
                    }
                    if (!waitForImageState(setOf(ImageSendState.CHAT), 2_500L)) {
                        performBack(service)
                        continue
                    }
                    if (sentStages.add(ImageSendStage.SENT)) {
                        onStage?.invoke(ImageSendStage.SENT)
                    }
                    return true
                }
                ImageSendState.UNKNOWN -> {
                    if (!performBack(service)) return false
                }
            }
            SystemClock.sleep(IMAGE_STATE_POLL_MS)
        }
        return false
    }

    private fun emitStageFromState(
        state: ImageSendState,
        sent: MutableSet<ImageSendStage>,
        onStage: ((ImageSendStage) -> Unit)?,
    ) {
        when (state) {
            ImageSendState.ATTACH_PANEL -> emitStage(ImageSendStage.ATTACH_PANEL, sent, onStage)
            ImageSendState.ALBUM_PICK -> emitStage(ImageSendStage.ALBUM_OPEN, sent, onStage)
            ImageSendState.ALBUM_SELECTED -> {
                emitStage(ImageSendStage.ALBUM_OPEN, sent, onStage)
                emitStage(ImageSendStage.SELECTED, sent, onStage)
            }
            else -> Unit
        }
    }

    private fun emitStage(
        stage: ImageSendStage,
        sent: MutableSet<ImageSendStage>,
        onStage: ((ImageSendStage) -> Unit)?,
    ) {
        if (sent.add(stage)) {
            onStage?.invoke(stage)
        }
    }

    private fun waitForImageState(targets: Set<ImageSendState>, timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (detectImageSendState() in targets) return true
            SystemClock.sleep(200)
        }
        return false
    }

    private fun recoverImageState(service: AccessibilityService, state: ImageSendState): Boolean {
        Logger.w("WeChatAgent.sendLatestImage stalled in $state")
        return when (state) {
            ImageSendState.CHAT -> openAttachmentPanel()
            ImageSendState.ATTACH_PANEL -> openAlbum()
            ImageSendState.ALBUM_PICK -> selectFirstMedia() || performBack(service)
            ImageSendState.ALBUM_SELECTED -> tapSendButton() || performBack(service)
            ImageSendState.UNKNOWN -> performBack(service)
        }.also { if (it) SystemClock.sleep(IMAGE_RECOVER_DELAY_MS) }
    }

    private fun detectImageSendState(): ImageSendState {
        val root = rootInWindow() ?: return ImageSendState.UNKNOWN
        if (hasAttachmentPanel(root)) return ImageSendState.ATTACH_PANEL
        if (isAlbumPage(root)) {
            return if (hasSelectedMedia(root)) ImageSendState.ALBUM_SELECTED else ImageSendState.ALBUM_PICK
        }
        if (findInputField(root) != null) return ImageSendState.CHAT
        return ImageSendState.UNKNOWN
    }

    private fun hasAttachmentPanel(root: AccessibilityNodeInfo): Boolean {
        return findNodesByViewId(root, ID_ATTACHMENT_ICON).isNotEmpty() ||
                findNodesByViewId(root, ID_ATTACHMENT_LABEL).isNotEmpty()
    }

    private fun isAlbumPage(root: AccessibilityNodeInfo): Boolean {
        val hasTitle = ALBUM_TITLE_HINTS.any { hint ->
            try {
                val nodes = root.findAccessibilityNodeInfosByText(hint)
                !nodes.isNullOrEmpty()
            } catch (_: Throwable) {
                false
            }
        }
        if (hasTitle) return true
        return findNodesByViewId(root, ID_MEDIA_THUMB).isNotEmpty() ||
                findNodesByViewId(root, ID_MEDIA_CHECKBOX).isNotEmpty()
    }

    private fun hasSelectedMedia(root: AccessibilityNodeInfo): Boolean {
        val nodes = findNodesByViewId(root, ID_MEDIA_CHECKBOX)
        if (nodes.isNotEmpty()) {
            nodes.forEach { node ->
                if (node.isChecked) return true
                if (hasSelectedCountText(node.text)) return true
            }
            return false
        }
        val sendButtons = findNodesByViewId(root, ID_SEND_BUTTON)
        if (sendButtons.any { hasSelectedCountText(it.text) }) return true
        SEND_BUTTON_TEXTS.forEach { text ->
            val hits = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { null }
            hits?.firstOrNull { hasSelectedCountText(it.text) }?.let { return true }
        }
        return false
    }

    private fun hasSelectedCountText(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim() ?: return false
        if (value.isEmpty()) return false
        return value.any { it.isDigit() }
    }

    private fun performBack(service: AccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun rootInWindow(): AccessibilityNodeInfo? = ServiceHolder.service?.rootInActiveWindow

    private fun openAttachmentPanel(): Boolean {
        val button = waitForView(ID_MORE_FUNCTION, attempts = 10, delayMs = 300)
        if (button != null) {
            val clicked = performClick(button)
            button.recycleSafe()
            if (clicked) {
                SystemClock.sleep(500)
                return true
            }
        }
        val tapFallback = performTapAtRatio(TAP_MORE_X, TAP_MORE_Y, "image_more")
        if (tapFallback) SystemClock.sleep(1000)
        return tapFallback
    }

    private fun openAlbum(): Boolean {
        SystemClock.sleep(2000)
        val tapped = performTapAtRatio(TAP_ALBUM_X, TAP_ALBUM_Y, "image_album")
        if (tapped) SystemClock.sleep(2000)
        return tapped
    }

    private fun selectFirstMedia(): Boolean {
        SystemClock.sleep(1500)
        repeat(2) {
            val root = rootInWindow() ?: return false
            if (openAlbumListIfNeeded(root)) {
                SystemClock.sleep(800)
                return@repeat
            }
            val clicked = when {
                clickFirstByViewId(root, ID_MEDIA_CHECKBOX) -> true
                clickFirstByClass(root, "android.widget.CheckBox") -> true
                clickFirstByViewId(root, ID_MEDIA_THUMB) -> true
                clickFirstGridCandidate(root) -> true
                else -> false
            }
            if (clicked && waitForSelectedMedia(900L)) return true
            SystemClock.sleep(200)
        }
        val tapped = performTapAtRatio(TAP_MEDIA_X, TAP_MEDIA_Y, "image_media")
        if (tapped) {
            SystemClock.sleep(600)
            return waitForSelectedMedia(1200L)
        }
        return false
    }

    private fun openAlbumListIfNeeded(root: AccessibilityNodeInfo): Boolean {
        val service = ServiceHolder.service ?: return false
        val dm = service.resources?.displayMetrics ?: return false
        val minY = (dm.heightPixels * 0.18f).toInt()
        val maxY = (dm.heightPixels * 0.90f).toInt()
        ALBUM_TITLE_HINTS.forEach { hint ->
            val nodes = try { root.findAccessibilityNodeInfosByText(hint) } catch (_: Throwable) { null }
            nodes?.forEach { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@forEach
                val centerY = bounds.centerY()
                if (centerY < minY || centerY > maxY) return@forEach
                if (performClick(node)) {
                    node.recycleSafe()
                    return true
                }
                node.recycleSafe()
            }
        }
        return false
    }

    private fun waitForSelectedMedia(timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val root = rootInWindow() ?: return false
            if (hasSelectedMedia(root)) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun tapSendButton(): Boolean {
        repeat(8) {
            val root = rootInWindow() ?: return false
            findNodesByViewId(root, ID_SEND_BUTTON).firstOrNull()?.let { node ->
                val ok = performClick(node)
                if (ok) {
                    SystemClock.sleep(800)
                    return true
                }
            }
            SystemClock.sleep(500)
        }
        val tapped = performTapAtRatio(TAP_SEND_X, TAP_SEND_Y, "image_send")
        if (tapped) SystemClock.sleep(2000)
        return tapped
    }


    private fun findByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return findNodesByViewId(root, id).firstOrNull()
    }

    private fun clickFirstByViewId(root: AccessibilityNodeInfo, id: String): Boolean {
        val nodes = findNodesByViewId(root, id)
        for (node in nodes) {
            val ok = performClick(node)
            node.recycleSafe()
            if (ok) {
                SystemClock.sleep(500)
                return true
            }
        }
        return false
    }

    private fun clickFirstByClass(root: AccessibilityNodeInfo, className: String): Boolean {
        val node = findFirstByClass(root, className) ?: return false
        val ok = performClick(node)
        node.recycleSafe()
        if (ok) {
            SystemClock.sleep(500)
            return true
        }
        return false
    }

    private fun clickFirstGridCandidate(root: AccessibilityNodeInfo): Boolean {
        val node = findFirstGridItem(root) ?: return false
        val ok = performClick(node)
        node.recycleSafe()
        if (ok) {
            SystemClock.sleep(100)
            return true
        }
        return false
    }

    private fun waitForView(id: String, attempts: Int = 5, delayMs: Long = 120): AccessibilityNodeInfo? {
        repeat(attempts) {
            val root = rootInWindow()
            if (root != null) {
                val node = findByViewId(root, id)
                if (node != null) return node
            }
            SystemClock.sleep(delayMs)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findNodesByViewId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> {
        return try {
            root.findAccessibilityNodeInfosByViewId(id)?.map { AccessibilityNodeInfo.obtain(it) } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findFirstByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.toString() == className) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue += it }
            }
        }
        return null
    }

    private fun findFirstGridItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val service = ServiceHolder.service ?: return null
        val dm = service.resources?.displayMetrics ?: return null
        val minY = (dm.heightPixels * 0.16f).toInt()
        val maxY = (dm.heightPixels * 0.86f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (cls == "android.widget.ImageView" || cls == "android.view.View") {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.top >= minY && bounds.bottom <= maxY) {
                    val score = bounds.top.toLong() * 10_000L + bounds.left.toLong()
                    if (score < bestScore) {
                        best?.recycleSafe()
                        best = node
                        bestScore = score
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue += it }
            }
        }
        return best
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun performTapAtRatio(xRatio: Float, yRatio: Float, reason: String): Boolean {
        val service = ServiceHolder.service ?: return false
        val dm = service.resources?.displayMetrics ?: return false
        val x = (dm.widthPixels * xRatio).toInt().coerceIn(0, dm.widthPixels - 1)
        val y = (dm.heightPixels * yRatio).toInt().coerceIn(0, dm.heightPixels - 1)
        val ok = performTap(service, x.toFloat(), y.toFloat(), reason)
        return ok
    }

    private fun performTap(service: AccessibilityService, x: Float, y: Float, reason: String): Boolean {
        repeat(TAP_RETRY_COUNT + 1) { attempt ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()
            val latch = CountDownLatch(1)
            var success = false
            val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)
            if (!dispatched) {
                Logger.w("WeChatAgent.tap dispatch failed reason=$reason (x=$x,y=$y)")
            } else {
                val completed = latch.await(800, TimeUnit.MILLISECONDS)
                if (!completed) {
                    Logger.w("WeChatAgent.tap timeout reason=$reason (x=$x,y=$y)")
                }
            }
            if (success) return true
            if (attempt < TAP_RETRY_COUNT) {
                SystemClock.sleep(TAP_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun performSwipe(service: AccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        val latch = CountDownLatch(1)
        var success = false
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        if (!dispatched) {
            Logger.w("WeChatAgent.swipe dispatch failed")
            return false
        }
        latch.await(1200, TimeUnit.MILLISECONDS)
        return success
    }

    private fun scrollToEndAndRefresh(
        service: AccessibilityService,
        snapshot: WeChatSnapshot,
        chatKey: String,
    ): WeChatSnapshot {
        var current = snapshot
        var lastSig = snapshotSignature(current)
        val state = scrollState(chatKey)
        state.lastDownAt = SystemClock.uptimeMillis()
        state.lastDownSig = lastSig
        var steps = 0
        while (steps < SCROLL_TO_END_MAX) {
            val dm = service.resources?.displayMetrics ?: break
            val x = dm.widthPixels * 0.5f
            val startY = dm.heightPixels * 0.78f
            val endY = dm.heightPixels * 0.28f
            val ok = performSwipe(service, x, startY, x, endY)
            if (!ok) break
            SystemClock.sleep(SCROLL_STEP_DELAY_MS)
            val refreshed = refreshSnapshot(service, current)
            val sig = snapshotSignature(refreshed)
            if (sig == null || sig == lastSig) {
                state.lastDownNoChangeAt = SystemClock.uptimeMillis()
                state.lastDownSig = sig ?: lastSig
                current = refreshed
                break
            }
            current = refreshed
            lastSig = sig
            state.lastDownSig = sig
            steps++
        }
        return current
    }

    private fun scrollUpForAnchor(
        service: AccessibilityService,
        snapshot: WeChatSnapshot,
        chatKey: String,
        maxSteps: Int,
    ): ScrollResult {
        var current = snapshot
        var lastSig = snapshotSignature(current)
        val state = scrollState(chatKey)
        state.lastUpAt = SystemClock.uptimeMillis()
        state.lastUpSig = lastSig
        var steps = 0
        var anchorFound = false
        while (steps < maxSteps) {
            val dm = service.resources?.displayMetrics ?: break
            val x = dm.widthPixels * 0.5f
            val startY = dm.heightPixels * 0.32f
            val endY = dm.heightPixels * 0.82f
            val ok = performSwipe(service, x, startY, x, endY)
            if (!ok) break
            SystemClock.sleep(SCROLL_STEP_DELAY_MS)
            val refreshed = refreshSnapshot(service, current)
            val sig = snapshotSignature(refreshed)
            current = refreshed
            if (sig == null || sig == lastSig) break
            val stats = WeChatHistory.matchStats(chatKey, current.messages.orEmpty())
            if (stats.matched > 0) {
                anchorFound = true
                break
            }
            if (stats.gap == 0) break
            lastSig = sig
            state.lastUpSig = sig
            steps++
        }
        return ScrollResult(current, anchorFound)
    }

    private fun refreshSnapshot(service: AccessibilityService, fallback: WeChatSnapshot): WeChatSnapshot {
        val root = service.rootInActiveWindow ?: return fallback
        val cls = root.className?.toString() ?: ""
        return try {
            WeChatParser.parse(service, root, cls) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun snapshotSignature(snapshot: WeChatSnapshot?): String? {
        val list = snapshot?.messages ?: return null
        if (list.isEmpty()) return null
        val top = list.first().id
        val bottom = list.last().id
        return "$top|$bottom|${list.size}"
    }

    private fun shouldScrollDown(chatKey: String, sig: String?): Boolean {
        if (sig.isNullOrBlank()) return false
        val state = scrollState(chatKey)
        val now = SystemClock.uptimeMillis()
        if (state.lastDownSig == sig && now - state.lastDownNoChangeAt < SCROLL_NO_CHANGE_COOLDOWN_MS) {
            return false
        }
        if (state.lastDownSig == sig && now - state.lastDownAt < SCROLL_COOLDOWN_MS) {
            return false
        }
        return true
    }

    private fun shouldScrollUp(chatKey: String, sig: String?): Boolean {
        if (sig.isNullOrBlank()) return false
        val state = scrollState(chatKey)
        val now = SystemClock.uptimeMillis()
        if (state.lastUpSig == sig && now - state.lastUpAt < SCROLL_COOLDOWN_MS) {
            return false
        }
        return true
    }

    private fun scrollState(chatKey: String): ScrollState {
        return scrollStates.getOrPut(chatKey) { ScrollState() }
    }

    private data class ScrollResult(
        val snapshot: WeChatSnapshot,
        val anchorFound: Boolean,
    )

    private data class HomeState(
        val unread: Int,
        val snippet: String?,
    )

    private data class ScrollState(
        var lastDownAt: Long = 0L,
        var lastDownSig: String? = null,
        var lastDownNoChangeAt: Long = 0L,
        var lastUpAt: Long = 0L,
        var lastUpSig: String? = null,
    )

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleSafe() {
        try { recycle() } catch (_: Throwable) {}
    }

    /**
     * Attempt to input [text] into the active chat and press send.
     */
    fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        val service = ServiceHolder.service ?: return false
        if (!clickDebounce.allow()) {
            Logger.w("WeChatAgent.sendText throttled")
            return false
        }
        return performSend(service, text)
    }

    private fun performSend(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val input = findInputField(root) ?: return false
        if (!setNodeText(input, text)) {
            Logger.w("WeChatAgent: failed to set text")
            return false
        }
        val sendNode = findSendButton(root)
        return if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            true
        } else {
            Logger.w("WeChatAgent: send button not found or click failed")
            false
        }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidateIds = listOf(
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/awj",
            "com.tencent.mm:id/a_z",
            "com.tencent.mm:id/awq"
        )
        candidateIds.forEach { id ->
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                nodes?.firstOrNull { it.className == android.widget.EditText::class.java.name }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className == android.widget.EditText::class.java.name) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue += it }
            }
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        SEND_BUTTON_TEXTS.forEach { text ->
            val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { null }
            nodes?.firstOrNull { it.isClickable }?.let { return it }
        }
        val candidateIds = listOf(
            "com.tencent.mm:id/awv",
            "com.tencent.mm:id/awo",
            "com.tencent.mm:id/awp"
        )
        candidateIds.forEach { id ->
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                nodes?.firstOrNull { it.isClickable }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun currentSnapshot(): WeChatSnapshot? {
        val service = ServiceHolder.service ?: return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != WeChatSpec.PKG) return null
        val cls = root.className?.toString() ?: ""
        return try {
            WeChatParser.parse(service, root, cls)
        } catch (t: Throwable) {
            Logger.w("WeChatParser.parse failed: ${t.message}")
            null
        }
    }

    private fun openChatFromHome(targetNormalized: String, targetRaw: String): Boolean {
        repeat(TAP_RETRY_COUNT + 1) { attempt ->
            val service = ServiceHolder.service ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = findNodesByViewId(root, ID_CONVERSATION_TITLE)
            for (node in nodes) {
                val title = node.text?.toString() ?: continue
                if (titlesMatch(title, targetNormalized)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (tapBoundsCenter(bounds)) {
                        SystemClock.sleep(400)
                        return true
                    }
                }
            }
            val candidates = try {
                root.findAccessibilityNodeInfosByText(targetRaw)
            } catch (_: Throwable) {
                null
            }
            candidates?.firstOrNull()?.let { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (tapBoundsCenter(bounds)) {
                    SystemClock.sleep(400)
                    return true
                }
            }
            if (attempt < TAP_RETRY_COUNT) {
                SystemClock.sleep(TAP_RETRY_DELAY_MS)
            }
        }
        Logger.w("openChatFromHome: target not found -> $targetRaw")
        return false
    }

    private fun waitForChat(targetNormalized: String, attempts: Int = 10, delayMs: Long = 300): Boolean {
        repeat(attempts) {
            val snapshot = currentSnapshot()
            if (snapshot != null) {
                when (snapshot.screen.lowercase(Locale.US)) {
                    "chat" -> {
                        val currentTitle = snapshot.title
                        if (currentTitle != null && titlesMatch(currentTitle, targetNormalized)) return true
                    }
                    "conversations", "home" -> return false
                }
            }
            SystemClock.sleep(delayMs)
        }
        return false
    }

    private fun navigateBackToHome(): Boolean {
        val service = ServiceHolder.service ?: return false
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!ok) return false
        repeat(8) {
            SystemClock.sleep(300)
            val snapshot = currentSnapshot()
            if (snapshot != null) {
                val screen = snapshot.screen.lowercase(Locale.US)
                if (screen == "conversations" || screen == "home") return true
                if (screen == "chat") return false
            }
        }
        return false
    }

    private fun isWeChatForeground(): Boolean {
        val root = ServiceHolder.service?.rootInActiveWindow ?: return false
        return root.packageName?.toString() == WeChatSpec.PKG
    }

    private fun normalizeTitle(raw: String): String {
        var value = raw.trim()
        value = value.replace(Regex("\\s*[（(][^（）()]*[）)]\\s*$"), "").trim()
        return value.lowercase(Locale.getDefault())
    }

    private fun titlesMatch(actual: String, normalizedTarget: String): Boolean {
        return normalizeTitle(actual) == normalizedTarget
    }

    private fun tapBoundsCenter(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val xRatio = (bounds.centerX() / SCREEN_WIDTH).coerceIn(0f, 1f)
        val yRatio = (bounds.centerY() / SCREEN_HEIGHT).coerceIn(0f, 1f)
        return performTapAtRatio(xRatio, yRatio, "open_chat")
    }

    private class ClickDebounce(private val intervalMs: Long) {
        @Volatile
        private var last = 0L

        fun allow(): Boolean {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (last == 0L || now - last >= intervalMs) {
                    last = now
                    return true
                }
                return false
            }
        }
    }

}
