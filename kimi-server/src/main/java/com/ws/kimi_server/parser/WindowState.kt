package com.ws.kimi_server.parser

import android.view.accessibility.AccessibilityNodeInfo
import com.ws.kimi_server.apps.wechat.WeChatSpec

object WindowStateBuilder {
    data class Stats(
        var nodes: Int = 0,
        var clickable: Int = 0,
        var focusable: Int = 0,
        var editable: Int = 0,
        var recyclerViews: Int = 0,
        var texts: MutableList<String> = mutableListOf(),
        var focusedText: String? = null,
    )

    fun build(root: AccessibilityNodeInfo?, pkg: String, cls: String): org.json.JSONObject {
        val stats = Stats()
        if (root != null) {
            try { traverse(root, stats, 0, 600) } catch (_: Throwable) {}
        }
        val screenId = cls.substringAfterLast('.')
        return org.json.JSONObject().apply {
            put("type", "state")
            put("ts", System.currentTimeMillis())
            put("pkg", pkg)
            put("cls", cls)
            put("screen", screenId)
            put("nodes", stats.nodes)
            put("clickable", stats.clickable)
            put("focusable", stats.focusable)
            put("editable", stats.editable)
            put("recyclers", stats.recyclerViews)
            if (stats.focusedText != null) put("focusedText", stats.focusedText)
            // Provide a small sample of visible texts to aid debugging
            if (stats.texts.isNotEmpty()) {
                val sample = org.json.JSONArray()
                stats.texts.take(6).forEach { sample.put(it) }
                put("sampleTexts", sample)
            }
            val weChatTitle = extractWeChatTitle(root, pkg)
            if (!weChatTitle.isNullOrEmpty()) {
                put("chatTitle", weChatTitle)
            }
        }
    }

    private fun traverse(node: AccessibilityNodeInfo, s: Stats, depth: Int, budget: Int) {
        if (s.nodes >= budget) return
        s.nodes++
        if (node.isClickable) s.clickable++
        if (node.isFocusable) s.focusable++
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText", ignoreCase = true)) s.editable++
        if (cls.contains("RecyclerView", ignoreCase = true)) s.recyclerViews++
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            if (s.texts.size < 20) s.texts.add(t)
            if (node.isFocused && s.focusedText == null) s.focusedText = t
        }
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty()) {
            if (s.texts.size < 20) s.texts.add(d)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            traverse(c, s, depth + 1, budget)
            if (s.nodes >= budget) break
        }
    }

    private fun extractWeChatTitle(root: AccessibilityNodeInfo?, pkg: String): String? {
        if (root == null || pkg != WeChatSpec.PKG) return null
        val targetId = "com.tencent.mm:id/obn"
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(targetId)
        } catch (_: Throwable) {
            null
        }
        val title = nodes?.firstOrNull()?.text?.toString()?.trim()
        return title?.takeIf { it.isNotEmpty() && !it.equals("none", true) && !it.equals("null", true) }
    }
}
