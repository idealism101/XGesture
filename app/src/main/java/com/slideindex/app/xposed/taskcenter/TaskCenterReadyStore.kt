package com.slideindex.app.xposed.taskcenter

import android.content.Context

/**
 * SystemUI 侧钩子"已就绪"标记的落盘位置。
 *
 * 任务中心本体跑在 SystemUI 进程里（overlay ty=2038），App 进程只负责发广播。若用户没在
 * LSPosed 里给 XGesture 勾上"系统界面"作用域，SystemUI 侧钩子根本不会安装，广播无人接收，
 * 任务中心表现为"完全没反应"。这个标记让 App 侧至少能知道钩子装没装，从而给出可见提示。
 *
 * 写入方：App 进程的 [com.slideindex.app.receiver.TaskCenterReadyReceiver]（收到 SystemUI 的 READY）。
 * 读取方：[TaskCenterBridge.isSystemUiReady]，供手势派发前判定。
 */
object TaskCenterReadyStore {
    private const val PREFS_NAME = "task_center_state"

    /** 就绪标记的键（团队约定的固定名，便于外部排查）。 */
    const val KEY_READY_AT = "taskcenter_systemui_ready_at"

    /**
     * 就绪标记的有效期：超过这个时间没再收到 READY，就认为钩子可能已经失效
     * （SystemUI 被杀、LSPosed 作用域被取消勾选等）。
     */
    private const val READY_TTL_MS = 5 * 60 * 1000L

    /** 记录"刚刚收到 SystemUI 的 READY"。 */
    fun markReady(context: Context, timestampMs: Long = System.currentTimeMillis()) {
        runCatching {
            prefs(context).edit().putLong(KEY_READY_AT, timestampMs).apply()
        }
    }

    /** 最近一次收到 READY 的时间戳（毫秒）；没有则 null。 */
    fun readyAt(context: Context): Long? {
        return runCatching {
            prefs(context).getLong(KEY_READY_AT, 0L).takeIf { it > 0L }
        }.getOrNull()
    }

    /** 钩子是否处于"最近确认过就绪"的状态。 */
    fun isReady(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = readyAt(context) ?: return false
        val age = nowMs - at
        return age in 0..READY_TTL_MS
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
