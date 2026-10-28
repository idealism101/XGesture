package com.slideindex.app.xposed.taskcenter

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * 任务中心子系统的对外契约（枚举 / 接口 / 配置），不依赖任何呈现或编排类。
 *
 * 移植自 FanFreeform 的 TaskCenterListView 附属枚举与 TaskSwitcherOverlayController.Surface，
 * 仅做命名收敛（去掉 Fan* 前缀）以契合 XGesture 包结构，语义与行为保持一致。
 */

/** 任务面板可发起的动作（业务语义，不隶属任何具体视图）。 */
enum class TaskPanelAction(val legacyCode: Int) {
    /** 切换该应用的锁定状态（走系统原生 ProcessManager）。 */
    LOCK(0),
    /** 在分屏视图中打开。 */
    SPLIT(1),
    /** 在弹出视图（小窗）中打开。 */
    FREEFORM(2),
    /** 打开系统「应用信息」页。 */
    DETAILS(3),
    /** 启动该应用发布的桌面快捷方式（需同时带 shortcut 数据）。 */
    SHORTCUT(4),
    /** 拉起系统全局搜索。 */
    SEARCH(5),
}

/** 任务面板的出场因果（由业务层指定，由呈现层解释）。 */
enum class TaskPanelExit(val legacyCode: Int) {
    /** 回落：点外部 / 返回键。 */
    RECEDE(0),
    /** 让位：点应用 / 菜单项。 */
    YIELD(1),
    /** 甩出：跟手上划被放行。 */
    FLING(2),
    /** 安静让路：系统事件（开小窗等）。 */
    QUIET(3),
}

/** 手势触发侧（面板从被划出的那一侧长出）。 */
enum class GestureCorner { LEFT, RIGHT, TOP, BOTTOM }

/**
 * 呈现层所需的配置快照。对应原 FanFreeform 的 GestureConfig 中任务中心相关字段，
 * 这里只保留列表真正用到的几项，避免把整个 GestureConfig 搬进来。
 */
data class TaskCenterConfig(
    val haptic: Boolean = true,
    val forceCircularIcons: Boolean = true,
    val sideTaskAnimationSpeed: Int = 2,
)

/** 视图生命周期统一表面（被承载层 addView 的 View 需实现）。 */
interface TaskCenterSurface {
    fun playEntry()
    fun onExternalMove(x: Float, y: Float)
    fun onExternalUp(x: Float, y: Float, cancelled: Boolean)
    fun onExternalCancel()
    fun playDismissal()

    /** 按出场因果退场（承载层据此决定是否等待动画结束再撤窗口）。 */
    fun playExit(exitReason: TaskPanelExit, velocity: Float)

    /** 系统面板展开时暂停列表交互（退位但不销毁）。 */
    fun setPaused(paused: Boolean)

    /** 侧边返回手势跟手进度（0f = 未让位，1f = 完全让出）。 */
    fun setBackYieldProgress(progress: Float, fromLeft: Boolean)

    /** 跟手结束，取消偏移。 */
    fun releaseBackYield()

    fun releaseResources()
    fun getHandler(): android.os.Handler?
}

/** 列表动作回调：所有动作经此回到 SystemUI 侧运行时，由原生桥接执行。 */
interface TaskCenterListener {
    fun onLaunch(task: RecentTaskPreview)
    fun onCloseTask(task: RecentTaskPreview)
    fun onCloseAll()
    fun onListAction(action: TaskPanelAction, task: RecentTaskPreview?, shortcut: RecentTaskRepository.ShortcutEntry?)
    fun onToggleLock(task: RecentTaskPreview, shouldLock: Boolean)
    fun onClosed()
}

/** 最近任务预览（数据结构）。 */
data class RecentTaskPreview(
    val taskId: Int,
    val packageName: String,
    val label: CharSequence?,
    val snapshot: Bitmap?,
    val icon: Drawable?,
)

/** 列表菜单卡里一行可选的快捷方式。 */
data class ShortcutEntry(
    val packageName: String,
    val id: String,
    val label: CharSequence,
    val user: android.os.UserHandle,
)

/** 强调色：与设置 App 的令牌同值。 */
const val TASK_CENTER_ACCENT = 0xFF5A67F2.toInt()
