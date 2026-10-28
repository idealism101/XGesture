package com.slideindex.app.xposed.taskcenter

/**
 * App 进程（边缘手势识别所在）↔ SystemUI 进程（任务中心窗口所在）之间的广播契约。
 *
 * 任务中心整体运行在 SystemUI 进程里（overlay ty=2038），而触发它的边缘手势识别在 App 进程。
 * 两者分属不同进程，因此用广播跨进程把"打开 / 关闭"信号送进 SystemUI 钩子。
 * 所有动作（锁定 / 分屏 / 小窗 / 关闭 / 切换）都在 SystemUI 进程内由列表直接发起，无需再跨进程。
 *
 * 注意 `setPackage` 定向广播的归属规则：接收端必须是**用目标包名那个 Context 注册**的
 * runtime receiver，否则广播按包名过滤时被静默丢弃（没有任何日志）。
 */
object TaskCenterBroadcastContract {
    /** 触发方（App 进程）发出的"打开任务中心"动作。 */
    const val ACTION_OPEN = "com.slideindex.app.taskcenter.action.OPEN"

    /** 触发方发出的"关闭任务中心"动作（例如手势被系统面板接管）。 */
    const val ACTION_CLOSE = "com.slideindex.app.taskcenter.action.CLOSE"

    /** 触发方发出的"探活"动作：SystemUI 侧收到后立刻回一条 [ACTION_READY]。 */
    const val ACTION_PING = "com.slideindex.app.taskcenter.action.PING"

    /** SystemUI 进程发出的"钩子已就绪"回执：App 侧据此判断 SystemUI 作用域是否生效。 */
    const val ACTION_READY = "com.slideindex.app.taskcenter.action.READY"

    /** 触发侧（应用自身）包名，用于 setPackage 限定广播只达本模块注入的 SystemUI。 */
    const val SENDER_PACKAGE = "com.slideindex.app"

    /** 目标进程包名，广播只发给 SystemUI。 */
    const val TARGET_PACKAGE = "com.android.systemui"

    /** 回执的目标包名：SystemUI 侧的 READY 只发给 App 自己。 */
    const val TARGET_APP_PACKAGE = "com.slideindex.app"

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
    const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000

    /** 触发角：LEFT / RIGHT / TOP / BOTTOM。 */
    const val EXTRA_CORNER = "extra_corner"

    /** 触发锚点 X（屏幕坐标，dp）。 */
    const val EXTRA_ANCHOR_X = "extra_anchor_x"

    /** 触发锚点 Y（屏幕坐标，dp）。 */
    const val EXTRA_ANCHOR_Y = "extra_anchor_y"
}
