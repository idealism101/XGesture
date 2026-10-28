package com.slideindex.app.gesture

import com.slideindex.app.overlay.OverlayPanelMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「持续触发」松手后的收尾规则。
 *
 * 回归点：触钮持续触发圆环启动器 → 圆环槽位绑「快速启动器」→ 松手什么都没弹出。
 * 根因是松手回调里已经开好的面板被外层 `endSession()` 立刻收掉，所以圆环 / 指尖圆环
 * 这类松手确认入口必须走同一张判定表。
 */
class ContinuousReleaseHandoffTest {

    @Test
    fun leaveOpenPanels_keepFingerTrackingOnly() {
        assertEquals(
            ContinuousReleaseHandoff.KEEP_FINGER_TRACKING,
            continuousReleaseHandoff(OverlayPanelMode.QUICK_LAUNCHER),
        )
        assertEquals(
            ContinuousReleaseHandoff.KEEP_FINGER_TRACKING,
            continuousReleaseHandoff(OverlayPanelMode.TASK_SWITCHER),
        )
        assertEquals(
            ContinuousReleaseHandoff.KEEP_FINGER_TRACKING,
            continuousReleaseHandoff(OverlayPanelMode.SHELL_COMMANDS),
        )
    }

    @Test
    fun index_keepsSessionForIndex() {
        assertEquals(
            ContinuousReleaseHandoff.KEEP_INDEX,
            continuousReleaseHandoff(OverlayPanelMode.INDEX),
        )
    }

    @Test
    fun noPanelOpened_endsSession() {
        assertEquals(
            ContinuousReleaseHandoff.END_SESSION,
            continuousReleaseHandoff(OverlayPanelMode.NONE),
        )
    }
}
