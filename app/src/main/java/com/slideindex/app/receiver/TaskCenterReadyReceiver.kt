package com.slideindex.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.slideindex.app.xposed.taskcenter.TaskCenterBroadcastContract
import com.slideindex.app.xposed.taskcenter.TaskCenterReadyStore

/**
 * 接收 SystemUI 进程发来的"钩子已就绪"回执。
 *
 * 任务中心本体跑在 SystemUI 进程里（overlay ty=2038），App 进程只负责发广播。若用户没在
 * LSPosed 里给 XGesture 勾上"系统界面"作用域，SystemUI 侧钩子根本不会安装，广播无人接收，
 * 任务中心就表现为"完全没反应"。这个接收器把回执落盘，让手势派发前能判断钩子装没装，
 * 从而给出可见提示而不是静默失败。
 *
 * 与 [ModuleHookBridgeReceiver] 同一形态：manifest 静态注册 + exported，跨进程收信。
 */
class TaskCenterReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskCenterBroadcastContract.ACTION_READY) return
        TaskCenterReadyStore.markReady(context)
    }
}
