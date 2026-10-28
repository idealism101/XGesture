package com.slideindex.app.gesture

import com.slideindex.app.overlay.OverlayPanelMode

/**
 * 「持续触发」松手后，外层手势会话该怎么收尾。
 *
 * 松手确认经常顺带在 [GestureSession.openDiscretePanel] 里开出新的 leave-open 面板
 * （圆环 / 指尖圆环的槽位绑「快速启动器 / 任务切换器 / 应用索引」就属于这种）。
 * 若外层随后无条件 [GestureSession.endSession]，`forceReset()` 会把 panelMode 清成 NONE 并回调
 * `onSessionEnd`：刚开出来的面板连一帧都画不出来，表现为「松手后什么都没弹出」。
 *
 * 规则集中在这里，避免再出现「指尖圆环修了、圆环启动器没修」这种按入口各写一份的分叉。
 */
internal enum class ContinuousReleaseHandoff {
    /** 松手没有开出面板：照旧结束会话。 */
    END_SESSION,

    /** 开出了快速启动器 / 任务切换器 / Shell：只结束手指跟踪，保留 panelMode。 */
    KEEP_FINGER_TRACKING,

    /** 开出了应用索引：索引归会话自己持有，不能复位。 */
    KEEP_INDEX,
}

internal fun continuousReleaseHandoff(panelMode: OverlayPanelMode): ContinuousReleaseHandoff =
    when (panelMode) {
        OverlayPanelMode.QUICK_LAUNCHER,
        OverlayPanelMode.TASK_SWITCHER,
        OverlayPanelMode.SHELL_COMMANDS,
        -> ContinuousReleaseHandoff.KEEP_FINGER_TRACKING

        OverlayPanelMode.INDEX -> ContinuousReleaseHandoff.KEEP_INDEX

        OverlayPanelMode.NONE -> ContinuousReleaseHandoff.END_SESSION
    }
