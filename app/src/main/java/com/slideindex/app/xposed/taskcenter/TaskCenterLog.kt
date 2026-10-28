package com.slideindex.app.xposed.taskcenter

import android.util.Log

/** 任务中心专用日志包装，统一 TAG，便于在 LSPosed 日志里过滤。 */
object TaskCenterLog {
    private const val TAG = "SlideIndexTaskCenter"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }
}
