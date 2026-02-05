package com.ws.kimi_server.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun openUsageAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
}

fun getLatestForegroundPackage(context: Context, windowMs: Long = 60_000): String? {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val end = System.currentTimeMillis()
    val begin = end - windowMs
    val events = usm.queryEvents(begin, end)
    val e = UsageEvents.Event()
    var latestPkg: String? = null
    var latestTs = 0L
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        val type = e.eventType
        val isFg = if (Build.VERSION.SDK_INT >= 29) {
            type == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            legacyIsMoveToForeground(type)
        }
        if (isFg && e.timeStamp > latestTs) {
            latestPkg = e.packageName
            latestTs = e.timeStamp
        }
    }
    return latestPkg
}

@Suppress("DEPRECATION")
private fun legacyIsMoveToForeground(type: Int): Boolean =
    type == UsageEvents.Event.MOVE_TO_FOREGROUND
