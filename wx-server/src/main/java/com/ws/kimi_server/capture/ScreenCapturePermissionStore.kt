package com.ws.wx_server.capture

import android.content.Context
import android.content.Intent

object ScreenCapturePermissionStore {
    private const val PREFS = "lanbot_screen_capture"
    private const val K_RESULT_CODE = "result_code"
    private const val K_DATA_URI = "data_uri"

    fun save(context: Context, resultCode: Int, data: Intent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_RESULT_CODE, resultCode)
            .putString(K_DATA_URI, data.toUri(Intent.URI_INTENT_SCHEME))
            .apply()
    }

    fun load(context: Context): PermissionData? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val resultCode = sp.getInt(K_RESULT_CODE, Int.MIN_VALUE)
        val dataUri = sp.getString(K_DATA_URI, null) ?: return null
        if (resultCode == Int.MIN_VALUE) return null
        return try {
            PermissionData(resultCode, Intent.parseUri(dataUri, Intent.URI_INTENT_SCHEME))
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(K_RESULT_CODE)
            .remove(K_DATA_URI)
            .apply()
    }

    data class PermissionData(
        val resultCode: Int,
        val dataIntent: Intent,
    )
}
