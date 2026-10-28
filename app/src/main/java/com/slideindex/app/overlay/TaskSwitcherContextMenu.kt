package com.slideindex.app.overlay

import android.content.Intent
import com.slideindex.app.util.ShortcutKind

/**
 * 任务切换面板的菜单项数据模型。
 *
 * 注意：任务切换面板本体已迁移到 SystemUI 进程（ty=2038 overlay），其绘制 / 布局代码
 * （TaskSwitcherContextMenuLayout / TaskSwitcherContextMenuLayoutFactory 等）已随面板一起删除。
 * 但 [TaskSwitcherMenuItem] 与 [TaskSwitcherMenuItemType] 这两个数据类被**应用快捷方式 / 搜索子系统**
 * 共享（AppShortcutLoader、KnownAppShortcuts、SearchPanelScreen、ActionExecutorLaunch 均直接构造
 * 或使用它们），因此作为共享数据类型保留，不随面板代码删除。
 */
enum class TaskSwitcherMenuItemType {
    SHORTCUT,
    FREE_WINDOW,
    APP_INFO,
    FORCE_STOP,
}

data class TaskSwitcherMenuItem(
    val label: String,
    val type: TaskSwitcherMenuItemType,
    val shortcutId: String? = null,
    val shortcutIntent: Intent? = null,
    /** Intent URI strings from manifest shortcuts.xml (SideGesture-style). */
    val intentUris: List<String>? = null,
    val useShellLaunch: Boolean = false,
    val kind: ShortcutKind? = null,
    val targetComponent: String? = null
)
