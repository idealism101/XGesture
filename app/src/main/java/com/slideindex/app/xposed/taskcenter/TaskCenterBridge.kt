package com.slideindex.app.xposed.taskcenter

import android.content.Context
import android.content.Intent

/**
 * App 进程侧桥：把手势识别出的"打开任务中心"意图，跨进程送到 SystemUI 钩子。
 *
 * 与 [TaskCenterBroadcastContract] 配套。仅负责发广播；SystemUI 侧由
 * [TaskCenterSystemUiHook] 接收并拉起任务中心窗口（overlay ty=2038）。
 */
object TaskCenterBridge {
    /** 在 App 进程（手势识别所在）调用：请求 SystemUI 打开任务中心。 */
    fun sendOpen(context: Context, anchorX: Float, anchorY: Float, corner: GestureCorner? = null) {
        val w = context.resources.displayMetrics.widthPixels
        val resolved = corner
            ?: if (anchorX <= w / 2f) GestureCorner.LEFT else GestureCorner.RIGHT
        val intent = Intent(TaskCenterBroadcastContract.ACTION_OPEN)
            .setPackage(TaskCenterBroadcastContract.TARGET_PACKAGE)
            .putExtra(TaskCenterBroadcastContract.EXTRA_CORNER, resolved.name)
            .putExtra(TaskCenterBroadcastContract.EXTRA_ANCHOR_X, anchorX)
            .putExtra(TaskCenterBroadcastContract.EXTRA_ANCHOR_Y, anchorY)
            .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { TaskCenterLog.e("TaskCenter open broadcast failed", it) }
        TaskCenterLog.i("TaskCenter open requested corner=$resolved anchor=($anchorX,$anchorY)")
    }

    /** 请求 SystemUI 关闭任务中心（例如手势被系统面板接管时）。 */
    fun sendClose(context: Context) {
        val intent = Intent(TaskCenterBroadcastContract.ACTION_CLOSE)
            .setPackage(TaskCenterBroadcastContract.TARGET_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
        runCatching { context.sendBroadcast(intent) }
    }
}
