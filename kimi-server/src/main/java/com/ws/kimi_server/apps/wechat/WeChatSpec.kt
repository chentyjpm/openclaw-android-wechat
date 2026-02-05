package com.ws.kimi_server.apps.wechat

/**
 * WeChat UI spec (partial, subject to change). Many views are dynamic; prefer robust heuristics.
 */
object WeChatSpec {
    const val PKG = "com.tencent.mm"

    // Common class names observed (may vary by version/locale)
    object Classes {
        const val LauncherUI = "com.tencent.mm.ui.LauncherUI" // main tabs
        const val ConversationList = "com.tencent.mm.ui.conversation.MainUI"
        const val ChattingUI = "com.tencent.mm.ui.chatting.ChattingUI"
        const val ContactUI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        const val SearchUI = "com.tencent.mm.plugin.fts.ui.FTSMainUI"
    }

    // Heuristic keywords for tab titles
    object Tabs {
        val Conversation = listOf("微信", "Chats", "消息")
        val Contacts = listOf("通讯录", "Contacts")
        val Discover = listOf("发现", "Discover")
        val Me = listOf("我", "Me")
    }
}

