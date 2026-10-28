package com.slideindex.app.xposed.taskcenter

/**
 * App 进程（边缘手势识别所在）↔ SystemUI 进程（任务中心窗口所在）之间的广播契约。
 *
 * 任务中心整体运行在 SystemUI 进程里（overlay ty=2038），而触发它的边缘手势识别在 App 进程。
 * 两者分属不同进程，因此用广播跨进程把"打开 / 关闭"信号送进 SystemUI 钩子。
 * 所有动作（锁定 / 分屏 / 小窗 / 关闭 / 切换）都在 SystemUI 进程内由列表直接发起，无需再跨进程。
 */
object TaskCenterBroadcastContract {
    /** 触发方（App 进程）发出的"打开任务中心"动作。 */
    const val ACTION_OPEN = "com.slideindex.app.taskcenter.action.OPEN"

    /** 触发方发出的"关闭任务中心"动作（例如手势被系统面板接管）。 */
    const val ACTION_CLOSE = "com.slideindex.app.taskcenter.action.CLOSE"

    /** 触发侧（应用自身）包名，用于 setPackage 限定广播只达本模块注入的 SystemUI。 */
    const val SENDER_PACKAGE = "com.slideindex.app"

    /** 目标进程包名，广播只发给 SystemUI。 */
    const val TARGET_PACKAGE = "com.android.systemui"

    /** 触发角：LEFT / RIGHT / TOP / BOTTOM。 */
    const val EXTRA_CORNER = "extra_corner"

    /** 触发锚点 X（屏幕坐标，dp）。 */
    const val EXTRA_ANCHOR_X = "extra_anchor_x"

    /** 触发锚点 Y（屏幕坐标，dp）。 */
    const val EXTRA_ANCHOR_Y = "extra_anchor_y"
}
