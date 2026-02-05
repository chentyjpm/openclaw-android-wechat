package com.ws.wx_server.acc

import android.content.Context

object AccessibilityStateStore {
    private const val PREFS = "accessibility_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_UPDATED_AT = "updated_at"

    fun setConnected(context: Context, connected: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONNECTED, false)
    }
}
