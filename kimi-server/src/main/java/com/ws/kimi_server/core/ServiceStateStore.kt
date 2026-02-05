package com.ws.kimi_server.core

import android.content.Context

object ServiceStateStore {
    private const val PREFS = "lanbot_service_state"
    private const val KEY_RUNNING = "running"

    fun setRunning(context: Context, running: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()
    }

    fun isRunning(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RUNNING, false)
    }
}
