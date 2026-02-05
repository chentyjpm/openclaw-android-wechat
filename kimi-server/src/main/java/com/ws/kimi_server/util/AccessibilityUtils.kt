package com.ws.kimi_server.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ws.kimi_server.acc.MyAccessibilityService

fun isAccessibilityEnabled(context: Context): Boolean {
    val setting = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    } catch (_: Throwable) {
        null
    }
    val idFull = ComponentName(context, MyAccessibilityService::class.java).flattenToString()
    val idShort = ComponentName(context, MyAccessibilityService::class.java).flattenToShortString()
    if (setting.isNullOrBlank()) return false
    return setting.split(':').any { entry ->
        val e = entry.trim()
        e.equals(idFull, ignoreCase = true) || e.equals(idShort, ignoreCase = true)
    }
}

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
