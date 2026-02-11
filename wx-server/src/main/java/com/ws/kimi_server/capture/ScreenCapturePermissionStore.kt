package com.ws.wx_server.capture

import android.content.Context
import android.content.Intent

object ScreenCapturePermissionStore {
    @Volatile
    private var cache: PermissionData? = null

    fun save(context: Context, resultCode: Int, data: Intent) {
        cache = PermissionData(resultCode, Intent(data))
    }

    fun load(context: Context): PermissionData? {
        return cache
    }

    fun clear(context: Context) {
        cache = null
    }

    data class PermissionData(
        val resultCode: Int,
        val dataIntent: Intent,
    )
}
