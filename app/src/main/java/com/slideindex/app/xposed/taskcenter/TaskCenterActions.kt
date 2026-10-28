package com.slideindex.app.xposed.taskcenter

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

/**
 * 任务中心动作桥：把业务动作落到系统原生能力，不自造轮子。
 *
 * - LOCK：走 miui.process.ProcessManager.updateApplicationLockedState（原生通知链完整），
 *   不可用时由 LockedAppStore 降级直接写 Settings（见其注释）。
 * - FREEFORM（小窗）：调 miui.app.MiuiFreeFormManager.getActivityOptions 拿原生小窗 ActivityOptions，
 *   再 startActivity 启动目标包。这正是系统自己的小窗入口。
 * - SPLIT（分屏）：用 ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) 启动，
 *   由系统把目标与当前前台任务并排。
 * - DETAILS / SEARCH：走 RecentTaskRepository 的现成系统 Intent。
 */
object TaskCenterActions {
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3

    /** 锁定 / 解锁某应用。返回是否成功。 */
    fun setLocked(context: Context, packageName: String, shouldLock: Boolean): Boolean {
        if (packageName.isEmpty()) return false
        val store = LockedAppStore(context, LockedAppStore.currentUserId())
        store.load()
        return store.setLocked(packageName, shouldLock)
    }

    /** 分发列表动作（除 LOCK 外，LOCK 由 onToggleLock 单独走 [setLocked]）。 */
    fun execute(
        context: Context,
        action: TaskPanelAction,
        task: RecentTaskPreview,
        repo: RecentTaskRepository,
        shortcut: RecentTaskRepository.ShortcutEntry? = null,
    ) {
        when (action) {
            TaskPanelAction.LOCK -> setLocked(context, task.packageName, true)
            TaskPanelAction.SPLIT -> startSplit(context, task)
            TaskPanelAction.FREEFORM -> startFreeform(context, task)
            TaskPanelAction.DETAILS -> repo.openAppDetails(task.packageName)
            TaskPanelAction.SHORTCUT -> {
                if (shortcut != null) repo.launchShortcut(shortcut)
            }
            TaskPanelAction.SEARCH -> repo.openHomeSearch()
        }
    }

    /** 在当前前台之上以小窗（MIUI freeform）打开目标应用。 */
    fun startFreeform(context: Context, task: RecentTaskPreview): Boolean {
        return try {
            val manager = Class.forName("miui.app.MiuiFreeFormManager")
            val options: ActivityOptions = try {
                val result = manager.getMethod(
                    "getActivityOptions",
                    Context::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).invoke(null, context, task.packageName, true, false)
                if (result is ActivityOptions) result else ActivityOptions.makeBasic()
            } catch (resolveError: Throwable) {
                TaskCenterLog.i("MiuiFreeFormManager.getActivityOptions unavailable err=${resolveError.javaClass.simpleName}")
                ActivityOptions.makeBasic()
            }
            ensureWindowingMode(options, WINDOWING_MODE_FREEFORM)
            val intent = launchIntent(context, task.packageName) ?: return false
            context.startActivity(intent, options.toBundle())
            TaskCenterLog.i("Freeform launch dispatched pkg=${task.packageName}")
            true
        } catch (error: Throwable) {
            TaskCenterLog.e("Freeform launch failed pkg=${task.packageName}", error)
            false
        }
    }

    /** 以分屏形态打开目标应用（与当前前台任务并排）。 */
    fun startSplit(context: Context, task: RecentTaskPreview): Boolean {
        return try {
            val options = ActivityOptions.makeBasic()
            ensureWindowingMode(options, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
            val intent = launchIntent(context, task.packageName) ?: return false
            context.startActivity(intent, options.toBundle())
            TaskCenterLog.i("Split launch dispatched pkg=${task.packageName}")
            true
        } catch (error: Throwable) {
            TaskCenterLog.e("Split launch failed pkg=${task.packageName}", error)
            false
        }
    }

    private fun launchIntent(context: Context, packageName: String): Intent? {
        val base = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun ensureWindowingMode(options: ActivityOptions, mode: Int) {
        try {
            val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(options, mode)
        } catch (_: Throwable) {
            // 旧版本无该隐藏 API，忽略（MIUI freeform 路径已自带形态）。
        }
    }
}
