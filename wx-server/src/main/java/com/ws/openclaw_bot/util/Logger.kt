package com.ws.wx_server.util

import android.util.Log

object Logger {
    private const val DEFAULT_TAG = "LanBot"
    fun d(msg: String, tag: String = DEFAULT_TAG) = Log.d(tag, msg)
    fun i(msg: String, tag: String = DEFAULT_TAG) = Log.i(tag, msg)
    fun w(msg: String, tag: String = DEFAULT_TAG) = Log.w(tag, msg)
    fun e(msg: String, tr: Throwable? = null, tag: String = DEFAULT_TAG) =
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
}
