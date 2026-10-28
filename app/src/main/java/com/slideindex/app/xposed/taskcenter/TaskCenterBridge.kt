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
    /**
     * AOSP 的 `Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND`，值 `0x01000000`。
     *
     * 它是 @hide 常量，公开 SDK 查不到，且在 API 31+ 属于会被拦截的非 SDK 接口 ——
     * 反射读取会抛 NoSuchFieldException，拿不到值。反射读一个编译期常量也没有收益：
     * 所有 ROM 上它的值都固定为 0x01000000。因此直接按 AOSP 文档值声明，
     * 与本仓库既有的隐藏常量处理方式一致（见 SystemGestureTakeoverController.POLICY_FLAG_INJECTED）。
     *
     * 作用：让广播能送到处于后台/缓存态的接收方 —— 任务中心在 SystemUI 进程里常驻，
     * 但手势触发时 App 侧可能在后台，这个标志保证送达不被丢。
     */
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000

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
            .addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { TaskCenterLog.e("TaskCenter open broadcast failed", it) }
        TaskCenterLog.i("TaskCenter open requested corner=$resolved anchor=($anchorX,$anchorY)")
    }

    /** 请求 SystemUI 关闭任务中心（例如手势被系统面板接管时）。 */
    fun sendClose(context: Context) {
        val intent = Intent(TaskCenterBroadcastContract.ACTION_CLOSE)
            .setPackage(TaskCenterBroadcastContract.TARGET_PACKAGE)
            .addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND)
        runCatching { context.sendBroadcast(intent) }
    }
}
