package com.ws.wx_server.exec

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.ws.wx_server.acc.ServiceHolder
import com.ws.wx_server.util.Logger

class AccessibilityAgent {
    fun nav_to_app(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        // 1) Try direct launch intent
        pm.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = try { context.startActivity(intent); true } catch (_: Throwable) { false }
            if (!started) return false
            when (waitForPackageOrChooser(packageName, 3000)) {
                AwaitResult.TARGET -> return true
                AwaitResult.CHOOSER -> {
                    autoResolveChooser(packageName)
                    return waitForPackage(packageName, 3000)
                }
                AwaitResult.TIMEOUT -> return false
            }
        }
        // 2) Fallback: query MAIN/LAUNCHER activities for the package
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val list = pm.queryIntentActivities(query, 0)
        if (list.isNotEmpty()) {
            val info = list[0].activityInfo
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = try { context.startActivity(intent); true } catch (_: Throwable) { false }
            if (!started) return false
            when (waitForPackageOrChooser(packageName, 3000)) {
                AwaitResult.TARGET -> return true
                AwaitResult.CHOOSER -> {
                    autoResolveChooser(packageName)
                    return waitForPackage(packageName, 3000)
                }
                AwaitResult.TIMEOUT -> return false
            }
        }
        // 3) Last resort: open app details
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            false
        } catch (_: Throwable) { false }
    }

    fun tapPct(x: Float, y: Float, holdMs: Long = 1): Boolean {
        // TODO: implement tap later
        return false
    }

    fun nav_to_via_recents(context: Context, packageName: String): Boolean {
        val svc = ServiceHolder.service ?: return false
        val label = appLabel(context, packageName)
        Logger.i("nav_to_via_recents: open recents for pkg=$packageName, label=$label")
        // Open recents
        val ok = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        if (!ok) return false
        // Phase 1: try finding by app label text if available (often more robust on some launchers)
        val tEnd = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < tEnd) {
            val root = svc.rootInActiveWindow
            if (root != null && !label.isNullOrBlank()) {
                val nodes = try { root.findAccessibilityNodeInfosByText(label) } catch (_: Throwable) { emptyList() }
                val node = nodes?.firstOrNull() ?: findNodeByTextOrDesc(root, label)
                if (node != null) {
                    val clickNode = findClickableAncestor(node) ?: node
                    val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.i("recents: click by label '$label' -> $clicked")
                    if (clicked) {
                        if (waitForPackage(packageName, 3000)) return true
                        // Not target; go back to recents and continue scanning
                        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                        try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    }
                }
            }
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        // Phase 2: Fallback - collect large clickable cards, try from left-most to right-most.
        // After clicking, verify full-screen packageName; if not target, return to recents and try next.
        val dm = context.resources.displayMetrics
        val minW = (dm.widthPixels * 0.3).toInt()
        val minH = (dm.heightPixels * 0.2).toInt()
        val tried = mutableSetOf<Int>() // record left positions tried
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end) {
            val root = svc.rootInActiveWindow ?: break
            val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
            fun collect(n: AccessibilityNodeInfo?) {
                if (n == null) return
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                if ((n.isClickable || n.isLongClickable) && r.width() >= minW && r.height() >= minH) {
                    candidates += r.left to n
                }
                for (i in 0 until n.childCount) collect(n.getChild(i))
            }
            collect(root)
            candidates.sortBy { it.first }
            var progressed = false
            for ((left, node) in candidates) {
                if (left in tried) continue
                tried += left
                val clickNode = if (node.isClickable) node else findClickableAncestor(node) ?: node
                val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.i("recents: click card left=$left -> $clicked, trying to verify pkg…")
                if (clicked) {
                    if (waitForPackage(packageName, 3000)) {
                        Logger.i("recents: opened target pkg=$packageName")
                        return true
                    }
                    // Not the target; go back to recents and continue
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    try { Thread.sleep(250) } catch (_: InterruptedException) {}
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return false
    }

    private fun appLabel(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai)?.toString()
    } catch (_: Throwable) { null }

    private fun findNodeByTextOrDesc(node: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(keyword, ignoreCase = true) == true) return node
        if (node.contentDescription?.toString()?.contains(keyword, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findNodeByTextOrDesc(c, keyword)
            if (r != null) return r
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node?.parent
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable || cur.isLongClickable) return cur
            depth++
            cur = cur.parent
        }
        return null
    }

    // Long-press functionality removed in rollback

    private enum class AwaitResult { TARGET, CHOOSER, TIMEOUT }

    private fun waitForPackage(targetPkg: String, timeoutMs: Long): Boolean {
        val svc = ServiceHolder.service ?: return false
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val pkg = svc.rootInActiveWindow?.packageName?.toString()
            if (pkg == targetPkg) return true
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
        return false
    }

    private fun waitForPackageOrChooser(targetPkg: String, timeoutMs: Long): AwaitResult {
        val svc = ServiceHolder.service ?: return AwaitResult.TIMEOUT
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = svc.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (pkg == targetPkg) return AwaitResult.TARGET
            if (isChooserVisible(root)) return AwaitResult.CHOOSER
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
        return AwaitResult.TIMEOUT
    }

    private fun isChooserVisible(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg != "android" && pkg != "com.android.systemui") return false
        val cls = root.className?.toString() ?: ""
        if (cls.contains("Resolver", ignoreCase = true) ||
            cls.contains("Chooser", ignoreCase = true) ||
            cls.contains("RecyclerView", ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun autoResolveChooser(targetPkg: String) {
        val svc = ServiceHolder.service ?: return
        val dm = svc.resources.displayMetrics
        val screenMidY = dm.heightPixels / 2
        val iconMinW = (dm.widthPixels * 0.15).toInt()
        val iconMinH = (dm.heightPixels * 0.12).toInt()
        val end = System.currentTimeMillis() + 4000
        var phase = 0 // 0: pick app, 1: pick "just once"
        while (System.currentTimeMillis() < end) {
            val root = svc.rootInActiveWindow ?: break
            if (phase == 0) {
                // Find clickable tiles in upper area (resolver grid), pick left-most
                val tiles = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                    if (n.isClickable && r.bottom <= screenMidY + iconMinH && r.width() >= iconMinW && r.height() >= iconMinH) {
                        tiles += r.left to n
                    }
                    for (i in 0 until n.childCount) collect(n.getChild(i))
                }
                collect(root)
                tiles.sortBy { it.first }
                val node = tiles.firstOrNull()?.second
                if (node != null) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        phase = 1
                        try { Thread.sleep(150) } catch (_: InterruptedException) {}
                        continue
                    }
                }
            } else {
                // Press "Just once" if present
                val onceTexts = arrayOf("仅此一次", "JUST ONCE", "Just once")
                val btn = onceTexts.asSequence()
                    .mapNotNull { t -> try { root.findAccessibilityNodeInfosByText(t) } catch (_: Throwable) { emptyList() } }
                    .flatten()
                    .firstOrNull()
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    // Wait for target package to appear; if so, done
                    if (waitForPackage(targetPkg, 3000)) return
                }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
    }
}
