package com.ws.wx_server.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import com.ws.wx_server.capture.ScreenCaptureManager
import com.ws.wx_server.debug.AccessibilityDebug
import com.ws.wx_server.exec.TaskBridge
import com.ws.wx_server.ime.LanBotImeService
import com.ws.wx_server.link.CAPTURE_STRATEGY_SCREEN_FIRST
import com.ws.wx_server.ocr.PPOcrRecognizer
import com.ws.wx_server.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

class MyAccessibilityService : AccessibilityService() {
    private var lastSent = 0L
    private var lastCaptureAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFollowUp: Runnable? = null
    private var lastWindowKey: String? = null
    private var lastPkg: String = ""
    private var lastCls: String = ""
    private val ppOcrRecognizer by lazy { PPOcrRecognizer(applicationContext) }
    private var lastLoggedOcrText: String? = null
    private var lastLoggedOcrJson: String? = null
    private var commandReceiver: BroadcastReceiver? = null

    private var tabScanActive = false
    private var tabScanSeenFirst = false
    private var tabScanSessionStartedAt = 0L
    private var tabScanCycleStartedAt = 0L
    private var tabScanSteps = 0
    private val tabScanFocusedEvents = mutableListOf<org.json.JSONObject>()
    private val tabScanTargetEvents = mutableListOf<org.json.JSONObject>()
    private var tabScanCycleIndex = 0
    private var tabScanChangedCycleCount = 0
    private var tabScanPreviousDescList: List<String> = emptyList()
    private var tabScanBaselineInitialized = false
    private var tabScanLastCycleFile: String? = null
    private var tabScanLastDeltaFile: String? = null
    private var tabScanTicker: Runnable? = null
    private val tabScanPushExecutor = Executors.newSingleThreadExecutor()
    private val tabScanPushClient = OkHttpClient()
    private val tabScanOutboundQueue = ArrayDeque<String>()
    private var tabScanSendingText: String? = null
    private var tabScanAwaitSendButton = false
    private val tabScanRecentSentCache = ArrayDeque<SentEcho>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceHolder.service = this
        AccessibilityStateStore.setConnected(applicationContext, true)
        try {
            val info = serviceInfo
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        } catch (_: Throwable) { }
        Logger.i("Accessibility connected")
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(applicationContext)
        Logger.i("AccDebug config: events=${cfg.debugEvents} xml=${cfg.debugXml} capture=${cfg.captureStrategy}")
        if (!TEMP_DISABLE_CAPTURE_AND_OCR &&
            cfg.captureStrategy == CAPTURE_STRATEGY_SCREEN_FIRST &&
            cfg.ocrEnabled
        ) {
            Thread {
                val ok = ppOcrRecognizer.warmUp()
                Logger.i("NCNN OCR warmup result=$ok", tag = "LanBotOCR")
            }.start()
        } else if (TEMP_DISABLE_CAPTURE_AND_OCR) {
            Logger.i("Capture/OCR temporarily disabled", tag = "LanBotOCR")
        }
        try {
            val i = Intent(ACTION_CONNECTED)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) { }
        registerCommandReceiver()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityStateStore.setConnected(applicationContext, false)
        try {
            val i = Intent(ACTION_DISCONNECTED)
            i.setPackage(packageName)
            sendBroadcast(i)
        } catch (_: Throwable) { }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceHolder.service = null
        AccessibilityStateStore.setConnected(applicationContext, false)
        ScreenCaptureManager.release()
        unregisterCommandReceiver()
        stopTabScan("service_destroyed")
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = null
        tabScanPushExecutor.shutdownNow()
        Logger.i("Accessibility destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        AccessibilityDebug.onEvent(this, event)
        handleTabScanFocusEvent(event)
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
                val title = extractNotificationTitle(event)
                if (!title.isNullOrBlank()) {
                    com.ws.wx_server.apps.wechat.WeChatNotifyGate.handleNotification(applicationContext, title)
                }
            }
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        lastPkg = pkg
        lastCls = cls

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val key = "$pkg|$cls"
            if (key != lastWindowKey) {
                lastWindowKey = key
                lastSent = 0L
            }
            val result = emitSnapshot(force = true)
            when (result.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, force = false)
            }
            return
        }

        val result = emitSnapshot(force = false)
        when (result.status) {
            SnapshotStatus.SENT -> Unit
            SnapshotStatus.THROTTLED -> scheduleFollowUp(result.retryDelayMs, force = false)
        }
    }

    override fun onInterrupt() {
        // No-op for now
    }

    private fun registerCommandReceiver() {
        if (commandReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_START_TAB_SCAN -> startTabScan()
                    ACTION_STOP_TAB_SCAN -> stopTabScan("manual_stop")
                    ACTION_ENQUEUE_OUTBOUND_MSG -> {
                        val text = intent.getStringExtra(EXTRA_OUTBOUND_TEXT).orEmpty().trim()
                        if (text.isNotEmpty()) {
                            tabScanOutboundQueue.addLast(text)
                            Logger.i(
                                "TabScan outbound queued size=${tabScanOutboundQueue.size} len=${text.length}",
                                tag = "LanBotTabScan",
                            )
                        }
                    }
                }
            }
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACTION_START_TAB_SCAN)
                addAction(ACTION_STOP_TAB_SCAN)
                addAction(ACTION_ENQUEUE_OUTBOUND_MSG)
            },
            RECEIVER_NOT_EXPORTED,
        )
        commandReceiver = receiver
    }

    private fun unregisterCommandReceiver() {
        val receiver = commandReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        commandReceiver = null
    }

    private fun startTabScan() {
        if (tabScanActive) {
            Logger.i("TabScan already running", tag = "LanBotTabScan")
            return
        }
        tabScanActive = true
        tabScanSteps = 0
        tabScanSessionStartedAt = System.currentTimeMillis()
        tabScanCycleIndex = 0
        tabScanChangedCycleCount = 0
        tabScanPreviousDescList = emptyList()
        tabScanBaselineInitialized = false
        tabScanLastCycleFile = null
        tabScanLastDeltaFile = null
        tabScanRecentSentCache.clear()
        Logger.i("TabScan started: loop mode, stepping TAB via IME every 250ms", tag = "LanBotTabScan")
        if (!LanBotImeService.isServiceActive()) {
            Logger.w("TabScan IME inactive: enable/select LanBot Keyboard first", tag = "LanBotTabScan")
        }
        startNextTabScanCycle()
        scheduleTabTick()
    }

    private fun stopTabScan(reason: String) {
        if (!tabScanActive && tabScanFocusedEvents.isEmpty() && tabScanTargetEvents.isEmpty()) {
            return
        }
        tabScanActive = false
        tabScanTicker?.let { handler.removeCallbacks(it) }
        tabScanTicker = null
        if (tabScanFocusedEvents.isNotEmpty() || tabScanTargetEvents.isNotEmpty()) {
            completeTabScanCycle(
                reason = "session_stop:$reason",
                continueLoop = false,
            )
        }
        val summary = org.json.JSONObject()
            .put("reason", reason)
            .put("steps", tabScanSteps)
            .put("elapsed_ms", (System.currentTimeMillis() - tabScanSessionStartedAt).coerceAtLeast(0L))
            .put("cycles", tabScanCycleIndex)
            .put("changed_cycles", tabScanChangedCycleCount)
            .put("last_cycle_file", tabScanLastCycleFile.orEmpty())
            .put("last_delta_file", tabScanLastDeltaFile.orEmpty())
        val summaryString = summary.toString()
        logLong("LanBotTabScan", "TabScan session done: ", summaryString)
        try {
            sendBroadcast(Intent(ACTION_TAB_SCAN_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_TAB_SCAN_JSON, summaryString)
                putExtra(EXTRA_TAB_SCAN_FILE, tabScanLastCycleFile.orEmpty())
            })
        } catch (_: Throwable) {
        }
        tabScanFocusedEvents.clear()
        tabScanTargetEvents.clear()
        tabScanSeenFirst = false
        tabScanSendingText = null
        tabScanAwaitSendButton = false
        tabScanRecentSentCache.clear()
    }

    private fun scheduleTabTick() {
        tabScanTicker?.let { handler.removeCallbacks(it) }
        tabScanTicker = object : Runnable {
            override fun run() {
                if (!tabScanActive) return
                performTabStep()
                tabScanSteps += 1
                handler.postDelayed(this, TAB_SCAN_INTERVAL_MS)
            }
        }.also { handler.postDelayed(it, TAB_SCAN_INTERVAL_MS) }
    }

    private fun performTabStep() {
        val imeOk = LanBotImeService.sendTabFromService()
        Logger.i("TabScan step=${tabScanSteps + 1} mode=ime_tab ok=$imeOk", tag = "LanBotTabScan")
        if (!imeOk && (tabScanSteps + 1) % 10 == 1) {
            Logger.w(
                "TabScan IME tab failed: confirm LanBot Keyboard is selected and EditText has input focus",
                tag = "LanBotTabScan",
            )
        }
    }

    private fun handleTabScanFocusEvent(event: AccessibilityEvent) {
        if (!tabScanActive) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val textJoined = event.text?.joinToString("|").orEmpty()
        val evt = org.json.JSONObject()
            .put("ts_ms", System.currentTimeMillis())
            .put("step", tabScanSteps)
            .put("type", "VIEW_FOCUSED")
            .put("pkg", pkg)
            .put("cls", cls)
            .put("window_id", event.windowId)
            .put("text", textJoined)
            .put("content_desc", event.contentDescription?.toString().orEmpty())
            .put("item_count", event.itemCount)
            .put("from_index", event.fromIndex)
            .put("to_index", event.toIndex)
        val isTarget = pkg == TAB_SCAN_TARGET_PKG && cls == TAB_SCAN_TARGET_CLS
        evt.put("is_target_edittext", isTarget)

        tabScanFocusedEvents.add(evt)
        Logger.i(
            "TabScan focused #${tabScanFocusedEvents.size} step=$tabScanSteps pkg=$pkg cls=$cls target=$isTarget",
            tag = "LanBotTabScan",
        )

        tryHandleOutboundSendOnFocus(pkg = pkg, cls = cls, text = textJoined)

        if (!isTarget) return
        tabScanTargetEvents.add(evt)
        Logger.i("TabScan captured EditText focus #${tabScanTargetEvents.size}", tag = "LanBotTabScan")

        if (!tabScanSeenFirst) {
            tabScanSeenFirst = true
            Logger.i("TabScan first EditText found", tag = "LanBotTabScan")
        } else {
            completeTabScanCycle(reason = "second_edittext_found", continueLoop = true)
        }
    }

    private fun tryHandleOutboundSendOnFocus(pkg: String, cls: String, text: String) {
        if (pkg != TAB_SCAN_TARGET_PKG) return

        if (!tabScanAwaitSendButton && cls == TAB_SCAN_TARGET_CLS && tabScanSendingText == null) {
            val next = tabScanOutboundQueue.removeFirstOrNull()
            if (next != null) {
                val committed = LanBotImeService.commitTextFromService(next)
                if (committed) {
                    tabScanSendingText = next
                    tabScanAwaitSendButton = true
                    Logger.i(
                        "TabScan outbound pasted len=${next.length}; waiting send button focus",
                        tag = "LanBotTabScan",
                    )
                } else {
                    tabScanOutboundQueue.addFirst(next)
                    Logger.w(
                        "TabScan outbound paste failed; re-queued size=${tabScanOutboundQueue.size}",
                        tag = "LanBotTabScan",
                    )
                }
            }
        }

        if (!tabScanAwaitSendButton) return
        if (cls != TAB_SCAN_SEND_BUTTON_CLS) return
        if (!text.contains(TAB_SCAN_SEND_BUTTON_TEXT)) return

        val sendingText = tabScanSendingText
        val enterOk = LanBotImeService.sendEnterFromService()
        Logger.i(
            "TabScan outbound send button focused -> enter=$enterOk",
            tag = "LanBotTabScan",
        )
        if (enterOk) {
            if (!sendingText.isNullOrBlank()) {
                cacheRecentSentEcho(sendingText)
            }
            tabScanSendingText = null
            tabScanAwaitSendButton = false
            Logger.i(
                "TabScan outbound sent ok len=${sendingText?.length ?: 0} queue=${tabScanOutboundQueue.size}",
                tag = "LanBotTabScan",
            )
        } else {
            if (!sendingText.isNullOrEmpty()) {
                tabScanOutboundQueue.addFirst(sendingText)
            }
            tabScanSendingText = null
            tabScanAwaitSendButton = false
            Logger.w(
                "TabScan outbound send failed; message re-queued size=${tabScanOutboundQueue.size}",
                tag = "LanBotTabScan",
            )
        }
    }

    private fun startNextTabScanCycle() {
        tabScanCycleIndex += 1
        tabScanCycleStartedAt = System.currentTimeMillis()
        tabScanSeenFirst = false
        tabScanFocusedEvents.clear()
        tabScanTargetEvents.clear()
        Logger.i("TabScan cycle #$tabScanCycleIndex started", tag = "LanBotTabScan")
    }

    private fun completeTabScanCycle(reason: String, continueLoop: Boolean) {
        if (tabScanFocusedEvents.isEmpty() && tabScanTargetEvents.isEmpty()) {
            if (continueLoop && tabScanActive) {
                startNextTabScanCycle()
            }
            return
        }
        val segment = extractTabScanDescSegment(tabScanFocusedEvents)
        val currentDescList = segment?.descList ?: emptyList()
        val baselineReadyBefore = tabScanBaselineInitialized
        val addedDescList = if (baselineReadyBefore) {
            findOrderedAddedDesc(tabScanPreviousDescList, currentDescList)
        } else {
            emptyList()
        }
        val changed = baselineReadyBefore && currentDescList != tabScanPreviousDescList

        val cycle = org.json.JSONObject()
            .put("cycle", tabScanCycleIndex)
            .put("reason", reason)
            .put("steps_total", tabScanSteps)
            .put("elapsed_ms", (System.currentTimeMillis() - tabScanCycleStartedAt).coerceAtLeast(0L))
            .put("focused_count", tabScanFocusedEvents.size)
            .put("target_count", tabScanTargetEvents.size)
            .put("segment_found", segment != null)
            .put("segment_window_id", segment?.windowId ?: -1)
            .put("segment_start_index", segment?.startIndex ?: -1)
            .put("segment_end_index", segment?.endIndex ?: -1)
            .put("desc_count", currentDescList.size)
            .put("desc_list", org.json.JSONArray().apply { currentDescList.forEach { put(it) } })
            .put("baseline_initialized", baselineReadyBefore)
            .put("changed", changed)
            .put("added_count", addedDescList.size)
            .put("added_contents", org.json.JSONArray().apply { addedDescList.forEach { put(it) } })
            .put("events", org.json.JSONArray().apply { tabScanFocusedEvents.forEach { put(it) } })
            .put("target_events", org.json.JSONArray().apply { tabScanTargetEvents.forEach { put(it) } })
        val cycleString = cycle.toString()
        logLong("LanBotTabScan", "TabScan cycle result: ", cycleString)
        val cyclePath = saveTabScanResultToSdcard(cycleString)
        tabScanLastCycleFile = cyclePath
        Logger.i("TabScan cycle file: ${cyclePath ?: "<failed>"}", tag = "LanBotTabScan")
        if (segment == null) {
            Logger.w(
                "TabScan segment not found: start=LinearLayout end=ImageButton(${TAB_SCAN_SEGMENT_END_MARKER})",
                tag = "LanBotTabScan",
            )
        }

        if (!baselineReadyBefore && segment != null) {
            tabScanBaselineInitialized = true
            tabScanPreviousDescList = currentDescList
            Logger.i(
                "TabScan baseline initialized: desc_count=${currentDescList.size}, skip initial history push",
                tag = "LanBotTabScan",
            )
        }

        if (changed) {
            tabScanChangedCycleCount += 1
            val filtered = filterOutboundEchoFromAdded(addedDescList)
            val pushDescList = filtered.forward
            val suppressedDescList = filtered.suppressed
            val delta = org.json.JSONObject()
                .put("cycle", tabScanCycleIndex)
                .put("segment_found", segment != null)
                .put("segment_window_id", segment?.windowId ?: -1)
                .put("desc_count", currentDescList.size)
                .put("desc_list", org.json.JSONArray().apply { currentDescList.forEach { put(it) } })
                .put("added_count", pushDescList.size)
                .put("added_contents", org.json.JSONArray().apply { pushDescList.forEach { put(it) } })
                .put("suppressed_count", suppressedDescList.size)
                .put("suppressed_contents", org.json.JSONArray().apply { suppressedDescList.forEach { put(it) } })
            val deltaString = delta.toString()
            logLong("LanBotTabScan", "TabScan delta: ", deltaString)
            if (suppressedDescList.isNotEmpty()) {
                suppressedDescList.forEachIndexed { index, content ->
                    logLong(
                        tag = "LanBotTabScan",
                        prefix = "TabScan suppressed[${index + 1}/${suppressedDescList.size}]: ",
                        text = content,
                    )
                }
            }
            if (pushDescList.isEmpty()) {
                Logger.i("TabScan added contents: <NONE>", tag = "LanBotTabScan")
            } else {
                pushDescList.forEachIndexed { index, content ->
                    logLong(
                        tag = "LanBotTabScan",
                        prefix = "TabScan added[${index + 1}/${pushDescList.size}]: ",
                        text = content,
                    )
                }
                pushAddedMessagesToClientPush(pushDescList, segment)
            }
            tabScanLastDeltaFile = saveTabScanDeltaToSdcard(deltaString)
            Logger.i("TabScan delta file: ${tabScanLastDeltaFile ?: "<failed>"}", tag = "LanBotTabScan")
        } else {
            Logger.i("TabScan cycle #$tabScanCycleIndex unchanged", tag = "LanBotTabScan")
        }

        if (tabScanBaselineInitialized) {
            tabScanPreviousDescList = currentDescList
        }

        if (continueLoop && tabScanActive) {
            startNextTabScanCycle()
        }
    }

    private data class TabScanDescSegment(
        val windowId: Int,
        val startIndex: Int,
        val endIndex: Int,
        val descList: List<String>,
    )

    private data class SentEcho(
        val normalized: String,
        val expiresAtMs: Long,
    )

    private data class AddedFilterResult(
        val forward: List<String>,
        val suppressed: List<String>,
    )

    private fun cacheRecentSentEcho(text: String) {
        val normalized = normalizeCompareText(text)
        if (normalized.isEmpty()) return
        pruneExpiredSentEcho()
        while (tabScanRecentSentCache.size >= TAB_SCAN_SENT_CACHE_MAX) {
            tabScanRecentSentCache.removeFirstOrNull()
        }
        tabScanRecentSentCache.addLast(
            SentEcho(
                normalized = normalized,
                expiresAtMs = System.currentTimeMillis() + TAB_SCAN_SENT_CACHE_TTL_MS,
            )
        )
        Logger.i(
            "TabScan sent cache add size=${tabScanRecentSentCache.size} len=${text.length}",
            tag = "LanBotTabScan",
        )
    }

    private fun filterOutboundEchoFromAdded(added: List<String>): AddedFilterResult {
        if (added.isEmpty()) return AddedFilterResult(emptyList(), emptyList())
        pruneExpiredSentEcho()
        val forward = ArrayList<String>(added.size)
        val suppressed = ArrayList<String>()
        added.forEach { text ->
            if (consumeRecentSentEcho(text)) {
                suppressed.add(text)
            } else {
                forward.add(text)
            }
        }
        return AddedFilterResult(forward = forward, suppressed = suppressed)
    }

    private fun consumeRecentSentEcho(text: String): Boolean {
        val normalized = normalizeCompareText(text)
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        val iterator = tabScanRecentSentCache.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.expiresAtMs <= now) {
                iterator.remove()
                continue
            }
            if (item.normalized == normalized) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    private fun pruneExpiredSentEcho() {
        if (tabScanRecentSentCache.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = tabScanRecentSentCache.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.expiresAtMs <= now) {
                iterator.remove()
            }
        }
    }

    private fun normalizeCompareText(text: String): String {
        return text.trim()
    }

    private fun extractTabScanDescSegment(events: List<org.json.JSONObject>): TabScanDescSegment? {
        var startIndex = -1
        var windowId = -1
        for (i in events.indices) {
            val evt = events[i]
            if (evt.optString("pkg") != TAB_SCAN_TARGET_PKG) continue
            if (evt.optString("cls") != TAB_SCAN_SEGMENT_START_CLS) continue
            startIndex = i
            windowId = evt.optInt("window_id", -1)
            break
        }
        if (startIndex < 0) return null

        var endIndex = -1
        for (i in startIndex until events.size) {
            val evt = events[i]
            if (evt.optString("pkg") != TAB_SCAN_TARGET_PKG) continue
            if (windowId >= 0 && evt.optInt("window_id", -1) != windowId) continue
            if (evt.optString("cls") != TAB_SCAN_SEGMENT_END_CLS) continue
            val text = evt.optString("text")
            val desc = evt.optString("content_desc")
            if (text.contains(TAB_SCAN_SEGMENT_END_MARKER) || desc.contains(TAB_SCAN_SEGMENT_END_MARKER)) {
                endIndex = i
                break
            }
        }
        if (endIndex < 0) return null

        val descList = ArrayList<String>()
        for (i in startIndex..endIndex) {
            val evt = events[i]
            if (evt.optString("pkg") != TAB_SCAN_TARGET_PKG) continue
            if (windowId >= 0 && evt.optInt("window_id", -1) != windowId) continue
            val desc = evt.optString("content_desc").trim()
            if (desc.isEmpty()) continue
            if (descList.isEmpty() || descList.last() != desc) {
                descList.add(desc)
            }
        }
        return TabScanDescSegment(
            windowId = windowId,
            startIndex = startIndex,
            endIndex = endIndex,
            descList = descList,
        )
    }

    private fun findOrderedAddedDesc(previous: List<String>, current: List<String>): List<String> {
        if (previous.isEmpty()) return current.toList()
        if (current.isEmpty()) return emptyList()

        val n = previous.size
        val m = current.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (previous[i] == current[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    max(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val added = ArrayList<String>()
        var i = 0
        var j = 0
        while (j < m) {
            if (i < n && previous[i] == current[j]) {
                i += 1
                j += 1
            } else if (i < n && dp[i + 1][j] >= dp[i][j + 1]) {
                i += 1
            } else {
                added.add(current[j])
                j += 1
            }
        }
        return added
    }

    private fun pushAddedMessagesToClientPush(
        addedDescList: List<String>,
        segment: TabScanDescSegment?,
    ) {
        if (addedDescList.isEmpty()) return

        val cfg = com.ws.wx_server.link.LinkConfigStore.load(applicationContext)
        val scheme = if (cfg.useTls) "https" else "http"
        val pushUrl = "$scheme://${cfg.host}:${cfg.port}$TAB_SCAN_CLIENT_PUSH_PATH"
        val windowId = segment?.windowId ?: -1
        val cycle = tabScanCycleIndex
        Logger.i(
            "TabScan push added_messages=${addedDescList.size} -> $pushUrl",
            tag = "LanBotTabScan",
        )

        addedDescList.forEachIndexed { index, text ->
            val order = index + 1
            val total = addedDescList.size
            tabScanPushExecutor.execute {
                postAddedMessagePush(
                    pushUrl = pushUrl,
                    cycle = cycle,
                    order = order,
                    total = total,
                    windowId = windowId,
                    text = text,
                )
            }
        }
    }

    private fun postAddedMessagePush(
        pushUrl: String,
        cycle: Int,
        order: Int,
        total: Int,
        windowId: Int,
        text: String,
    ) {
        val body = org.json.JSONObject()
            .put(
                "envelopes",
                org.json.JSONArray().put(
                    org.json.JSONObject().put(
                        "msg",
                        org.json.JSONObject()
                            .put("text", text),
                    ),
                ),
            )
            .toString()
        val req = Request.Builder()
            .url(pushUrl)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        try {
            tabScanPushClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val respBody = resp.body?.string().orEmpty()
                    Logger.w(
                        "TabScan added push failed [$order/$total] http=${resp.code} body=$respBody",
                        tag = "LanBotTabScan",
                    )
                    return
                }
                Logger.i(
                    "TabScan added pushed [$order/$total] cycle=$cycle windowId=$windowId",
                    tag = "LanBotTabScan",
                )
            }
        } catch (t: Throwable) {
            Logger.w(
                "TabScan added push exception [$order/$total]: ${t.message}",
                tag = "LanBotTabScan",
            )
        }
    }

    private fun saveTabScanResultToSdcard(content: String): String? {
        return try {
            val dir = File(SDCARD_OCR_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, "tab_scan_cycle_${tabScanCycleIndex}_${System.currentTimeMillis()}.json")
            file.writeText(content)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveTabScanDeltaToSdcard(content: String): String? {
        return try {
            val dir = File(SDCARD_OCR_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, "tab_scan_delta_${tabScanCycleIndex}_${System.currentTimeMillis()}.json")
            file.writeText(content)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private data class SnapshotResult(val status: SnapshotStatus, val retryDelayMs: Long = 0L)

    private enum class SnapshotStatus { SENT, THROTTLED }

    private fun emitSnapshot(force: Boolean): SnapshotResult {
        val now = System.currentTimeMillis()
        val cfg = com.ws.wx_server.link.LinkConfigStore.load(applicationContext)
        if (!force) {
            val elapsed = now - lastSent
            if (elapsed < SNAPSHOT_THROTTLE_MS) {
                val wait = (SNAPSHOT_THROTTLE_MS - elapsed).coerceIn(80L, SNAPSHOT_THROTTLE_MS)
                return SnapshotResult(SnapshotStatus.THROTTLED, wait)
            }
        }
        val pkg = lastPkg
        val cls = lastCls
        if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
            Logger.i("OCR snapshot trigger: force=$force cls=$cls", tag = "LanBotOCR")
        }
        var recognizedText = ""
        val intent = Intent(ACTION_SNAPSHOT).apply {
            putExtra(EXTRA_PKG, pkg)
            putExtra(EXTRA_CLS, cls)
        }

        val capture = maybeCapturePayload(
            pkg = pkg,
            now = now,
            force = force,
            strategy = cfg.captureStrategy,
            ocrEnabled = cfg.ocrEnabled,
        )
        capture?.let {
            recognizedText = it.text
            val captureJson = org.json.JSONObject()
                .put("mode", it.payload.mode)
                .put("mime", it.payload.mime)
                .put("width", it.payload.width)
                .put("height", it.payload.height)
                .put("ts_ms", it.payload.tsMs)
                .put("data_base64", it.payload.dataBase64)
            intent.putExtra(EXTRA_CAPTURE_JSON, captureJson.toString())
            if (!it.ocrJson.isNullOrBlank()) {
                intent.putExtra(EXTRA_OCR_JSON, it.ocrJson)
            }
        }
        intent.putExtra(EXTRA_TEXT, recognizedText)
        maybeLogOcrTextChange(recognizedText)
        maybeLogOcrJsonChange(capture?.ocrJson)

        intent.setPackage(packageName)
        sendBroadcast(intent)
        lastSent = now
        return SnapshotResult(SnapshotStatus.SENT)
    }

    private fun maybeCapturePayload(
        pkg: String,
        now: Long,
        force: Boolean,
        strategy: String,
        ocrEnabled: Boolean,
    ): CapturedPayload? {
        if (TEMP_DISABLE_CAPTURE_AND_OCR) {
            return null
        }
        val shouldCapture = strategy == CAPTURE_STRATEGY_SCREEN_FIRST &&
            pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG
        if (!shouldCapture) {
            if (pkg == com.ws.wx_server.apps.wechat.WeChatSpec.PKG) {
                Logger.i(
                    "OCR skip capture: strategy=$strategy sdk=${Build.VERSION.SDK_INT}",
                    tag = "LanBotOCR",
                )
            }
            return null
        }
        if (!force && now - lastCaptureAt < SCREEN_CAPTURE_THROTTLE_MS) {
            Logger.i("OCR skip capture: throttled", tag = "LanBotOCR")
            return null
        }
        val captured = captureScreenFrame(ocrEnabled = ocrEnabled) ?: return null
        lastCaptureAt = now
        return CapturedPayload(
            payload = TaskBridge.CapturePayload(
                mode = captured.mode,
                mime = "image/jpeg",
                width = captured.width,
                height = captured.height,
                tsMs = now,
                dataBase64 = captured.base64Jpeg,
            ),
            text = captured.ocrText,
            ocrJson = captured.ocrJson,
            ocrPayload = captured.ocrPayload,
        )
    }

    private data class CapturedPayload(
        val payload: TaskBridge.CapturePayload,
        val text: String,
        val ocrJson: String?,
        val ocrPayload: TaskBridge.OcrPayloadData?,
    )

    private data class CapturedFrame(
        val mode: String,
        val width: Int,
        val height: Int,
        val base64Jpeg: String,
        val ocrText: String,
        val ocrJson: String?,
        val ocrPayload: TaskBridge.OcrPayloadData?,
    )

    private fun captureScreenFrame(ocrEnabled: Boolean): CapturedFrame? {
        val projected = try {
            ScreenCaptureManager.captureBitmap(applicationContext, timeoutMs = 600L)
        } catch (t: Throwable) {
            Logger.w("MediaProjection capture failed: ${t.message}", tag = "LanBotOCR")
            null
        }
        if (projected != null) {
            return bitmapToFrame(projected, "media_projection", ocrEnabled)
        }
        Logger.i("OCR skip capture: media projection unavailable", tag = "LanBotOCR")
        return null
    }

    private fun bitmapToFrame(source: Bitmap, mode: String, ocrEnabled: Boolean): CapturedFrame? {
        val scaled = scaleBitmap(source, CAPTURE_MAX_WIDTH)
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        val ocrResult = if (ocrEnabled) {
            try {
                ppOcrRecognizer.recognizeDetailed(scaled)
            } catch (_: Throwable) {
                null
            }
        } else null
        val ocrText = ocrResult?.text ?: ""
        val ocrJson = ocrResult?.let { toOcrJson(it) }
        val ocrPayload = ocrResult?.let { toOcrPayload(it) }
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, out)) {
            if (scaled !== source) scaled.recycle()
            source.recycle()
            return null
        }
        val overlayJpeg = buildOverlayJpeg(scaled, ocrPayload)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        val bytes = out.toByteArray()
        saveOcrCaptureToSdcard(bytes, overlayJpeg, ocrText)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return CapturedFrame(
            mode = mode,
            width = outputWidth,
            height = outputHeight,
            base64Jpeg = encoded,
            ocrText = ocrText,
            ocrJson = ocrJson,
            ocrPayload = ocrPayload,
        )
    }

    private fun toOcrPayload(result: PPOcrRecognizer.OcrResult): TaskBridge.OcrPayloadData {
        return TaskBridge.OcrPayloadData(
            text = result.text,
            lines = result.lines.map { line ->
                TaskBridge.OcrLineData(
                    text = line.text,
                    prob = line.prob,
                    quad = line.quad.map { p -> TaskBridge.OcrPointData(x = p.x, y = p.y) },
                    left = line.left,
                    top = line.top,
                    right = line.right,
                    bottom = line.bottom,
                )
            },
        )
    }

    private fun toOcrJson(result: PPOcrRecognizer.OcrResult): String {
        val lines = org.json.JSONArray()
        for (line in result.lines) {
            val quad = org.json.JSONArray()
            for (p in line.quad) {
                quad.put(org.json.JSONObject().put("x", p.x).put("y", p.y))
            }
            lines.put(
                org.json.JSONObject()
                    .put("text", line.text)
                    .put("prob", line.prob)
                    .put("quad", quad)
                    .put(
                        "bbox",
                        org.json.JSONObject()
                            .put("left", line.left)
                            .put("top", line.top)
                            .put("right", line.right)
                            .put("bottom", line.bottom),
                    ),
            )
        }
        return org.json.JSONObject()
            .put("text", result.text)
            .put("lines", lines)
            .toString()
    }

    private fun scaleBitmap(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth || source.width <= 0 || source.height <= 0) return source
        val ratio = maxWidth.toFloat() / source.width.toFloat()
        val dstWidth = maxWidth
        val dstHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
    }

    private fun buildOverlayJpeg(
        base: Bitmap,
        ocrPayload: TaskBridge.OcrPayloadData?,
    ): ByteArray? {
        val lines = ocrPayload?.lines.orEmpty()
        if (lines.isEmpty()) return null
        return try {
            val overlay = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val canvas = Canvas(overlay)
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(255, 0, 255, 80)
                strokeWidth = (overlay.width / 320f).coerceAtLeast(2f)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(255, 255, 208, 0)
                textSize = (overlay.width / 38f).coerceAtLeast(20f)
            }
            val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(150, 0, 0, 0)
            }
            for (line in lines) {
                val quad = line.quad
                if (quad.size >= 4) {
                    for (i in quad.indices) {
                        val p1 = quad[i]
                        val p2 = quad[(i + 1) % quad.size]
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, strokePaint)
                    }
                }
                val label = "${line.text} (${String.format(java.util.Locale.US, "%.2f", line.prob)})"
                val textX = line.left.coerceAtLeast(0f)
                val textY = (line.top - 8f).coerceAtLeast(textPaint.textSize + 4f)
                val textW = textPaint.measureText(label)
                val pad = 6f
                canvas.drawRect(
                    textX - pad,
                    textY - textPaint.textSize - pad,
                    textX + textW + pad,
                    textY + pad,
                    textBgPaint,
                )
                canvas.drawText(label, textX, textY, textPaint)
            }
            val out = ByteArrayOutputStream()
            if (!overlay.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, out)) {
                overlay.recycle()
                return null
            }
            overlay.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveOcrCaptureToSdcard(jpeg: ByteArray, overlayJpeg: ByteArray?, text: String) {
        if (!SAVE_CAPTURE_TO_SDCARD) return
        try {
            val dir = File(SDCARD_OCR_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.w("OCR save failed: cannot mkdir $SDCARD_OCR_DIR", tag = "LanBotOCR")
                return
            }
            val ts = System.currentTimeMillis()
            val imgFile = File(dir, "ocr_${ts}.jpg")
            FileOutputStream(imgFile).use { it.write(jpeg) }
            if (overlayJpeg != null) {
                val overlayFile = File(dir, "ocr_${ts}_overlay.jpg")
                FileOutputStream(overlayFile).use { it.write(overlayJpeg) }
            }
            val txtFile = File(dir, "ocr_${ts}.txt")
            txtFile.writeText(text.ifBlank { "<EMPTY>" })
            Logger.i("OCR saved: ${imgFile.absolutePath}", tag = "LanBotOCR")
        } catch (t: Throwable) {
            Logger.w("OCR save exception: ${t.message}", tag = "LanBotOCR")
        }
    }

    private fun maybeLogOcrTextChange(text: String) {
        val normalized = text.trim()
        if (normalized == lastLoggedOcrText) return
        lastLoggedOcrText = normalized
    }

    private fun maybeLogOcrJsonChange(ocrJson: String?) {
        val normalized = ocrJson?.trim().orEmpty()
        if (normalized == lastLoggedOcrJson) return
        lastLoggedOcrJson = normalized
        if (normalized.isEmpty()) {
            Logger.i("NCNN OCR boxes changed: <EMPTY>", tag = "LanBotOCR")
            return
        }
        logLong(tag = "LanBotOCR", prefix = "NCNN OCR boxes changed: ", text = normalized)
    }

    private fun logLong(tag: String, prefix: String, text: String) {
        if (text.isEmpty()) {
            Logger.i(prefix, tag = tag)
            return
        }
        val maxChunk = LOGCAT_CHUNK_SIZE.coerceAtLeast(256)
        var start = 0
        var idx = 1
        while (start < text.length) {
            val end = (start + maxChunk).coerceAtMost(text.length)
            val part = text.substring(start, end)
            Logger.i("$prefix[$idx] $part", tag = tag)
            start = end
            idx += 1
        }
    }

    private fun extractNotificationTitle(event: AccessibilityEvent): String? {
        val notification = event.parcelableData as? android.app.Notification ?: return null
        val title = notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        return title?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun scheduleFollowUp(delayMs: Long, force: Boolean, attempt: Int = 1) {
        val delay = delayMs.coerceAtLeast(80L)
        val runnable = Runnable {
            pendingFollowUp = null
            val nextResult = emitSnapshot(force)
            when (nextResult.status) {
                SnapshotStatus.SENT -> Unit
                SnapshotStatus.THROTTLED -> if (!force && attempt < 2) {
                    scheduleFollowUp(nextResult.retryDelayMs, force = false, attempt = attempt + 1)
                }
            }
        }
        pendingFollowUp?.let { handler.removeCallbacks(it) }
        pendingFollowUp = runnable
        handler.postDelayed(runnable, delay)
    }

    companion object {
        private const val TEMP_DISABLE_CAPTURE_AND_OCR = true
        private const val SNAPSHOT_THROTTLE_MS = 800L
        private const val SCREEN_CAPTURE_THROTTLE_MS = 2200L
        private const val CAPTURE_JPEG_QUALITY = 55
        private const val CAPTURE_MAX_WIDTH = 960
        private const val SAVE_CAPTURE_TO_SDCARD = true
        private const val SDCARD_OCR_DIR = "/sdcard/ocr"
        private const val LOGCAT_CHUNK_SIZE = 2800
        private const val TAB_SCAN_INTERVAL_MS = 250L
        private const val TAB_SCAN_SENT_CACHE_MAX = 200
        private const val TAB_SCAN_SENT_CACHE_TTL_MS = 120_000L
        private const val TAB_SCAN_TARGET_PKG = "com.tencent.mm"
        private const val TAB_SCAN_TARGET_CLS = "android.widget.EditText"
        private const val TAB_SCAN_SEND_BUTTON_CLS = "android.widget.Button"
        private const val TAB_SCAN_SEND_BUTTON_TEXT = "\u53D1\u9001"
        private const val TAB_SCAN_SEGMENT_START_CLS = "android.widget.LinearLayout"
        private const val TAB_SCAN_SEGMENT_END_CLS = "android.widget.ImageButton"
        private const val TAB_SCAN_SEGMENT_END_MARKER = "切换到按住说话"
        private const val TAB_SCAN_CLIENT_PUSH_PATH = "/client/push"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ACTION_SNAPSHOT = "com.ws.wx_server.ACC_SNAPSHOT"
        const val ACTION_CONNECTED = "com.ws.wx_server.ACC_CONNECTED"
        const val ACTION_DISCONNECTED = "com.ws.wx_server.ACC_DISCONNECTED"
        const val ACTION_START_TAB_SCAN = "com.ws.wx_server.START_TAB_SCAN"
        const val ACTION_STOP_TAB_SCAN = "com.ws.wx_server.STOP_TAB_SCAN"
        const val ACTION_ENQUEUE_OUTBOUND_MSG = "com.ws.wx_server.ENQUEUE_OUTBOUND_MSG"
        const val ACTION_TAB_SCAN_DONE = "com.ws.wx_server.TAB_SCAN_DONE"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CLS = "cls"
        const val EXTRA_WECHAT_JSON = "wechat_json"
        const val EXTRA_STATE_JSON = "state_json"
        const val EXTRA_CAPTURE_JSON = "capture_json"
        const val EXTRA_OCR_JSON = "ocr_json"
        const val EXTRA_TAB_SCAN_JSON = "tab_scan_json"
        const val EXTRA_TAB_SCAN_FILE = "tab_scan_file"
        const val EXTRA_OUTBOUND_TEXT = "outbound_text"
    }
}
