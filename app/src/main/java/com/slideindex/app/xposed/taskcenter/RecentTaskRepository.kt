package com.slideindex.app.xposed.taskcenter

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * 最近任务数据层。仅依赖系统 API（ActivityTaskManager / PackageManager / LauncherApps），
 * 不引用 FanRuntime，可在 SystemUI 进程内独立使用。
 *
 * 移植自 FanFreeform 的 RecentTaskRepository，去掉了 Xposed 依赖（反射一律用标准库），
 * 并把被排除的包名收敛为本模块常量。
 */
class RecentTaskRepository(private val context: Context) {
    private val activityManager: ActivityManager? = context.getSystemService(ActivityManager::class.java)
    private val packageManager: PackageManager = context.packageManager

    fun loadRunningTasks(maximum: Int, includeSnapshots: Boolean): List<RecentTaskPreview> {
        val result = ArrayList<RecentTaskPreview>()
        val am = activityManager ?: return result
        val limit = maximum.coerceIn(2, 10)
        try {
            val recents = am.getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            for (task in recents) {
                if (result.size >= limit) break
                if (task == null || task.taskId < 0 || !task.isRunning || task.numActivities <= 0) continue
                var component = task.topActivity ?: task.baseActivity
                if (component == null && task.baseIntent != null) component = task.baseIntent.component
                if (component == null) continue
                val packageName = component.packageName
                // 排除本模块 / SystemUI / 桌面自身，避免任务中心把自己或桌面列进来。
                if (EXCLUDED_PACKAGES.contains(packageName)) continue
                try {
                    val app = packageManager.getApplicationInfo(packageName, 0)
                    val label = packageManager.getApplicationLabel(app)
                    val icon = packageManager.getApplicationIcon(app)
                    val snapshot = if (includeSnapshots) loadSnapshot(task.taskId) else null
                    result.add(RecentTaskPreview(task.taskId, packageName, label, snapshot, icon))
                } catch (itemError: Throwable) {
                    TaskCenterLog.i("Skipping unavailable recent task package=$packageName err=${itemError.javaClass.simpleName}")
                }
            }
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot load running task previews", error)
        }
        TaskCenterLog.i("Running task previews loaded count=${result.size} snapshots=$includeSnapshots")
        return result
    }

    fun release(previews: List<RecentTaskPreview>?) {
        previews ?: return
        for (p in previews) {
            if (p.snapshot != null && !p.snapshot.isRecycled) p.snapshot.recycle()
        }
    }

    /** 列表打开瞬间的前台任务 id（分屏时填到另一侧 stage 用）。 */
    fun foregroundTaskId(): Int {
        val am = activityManager ?: return -1
        try {
            val recents = am.getRecentTasks(5, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            for (task in recents) {
                if (task == null || task.taskId < 0 || !task.isRunning || task.numActivities <= 0) continue
                return task.taskId
            }
        } catch (error: Throwable) {
            TaskCenterLog.i("Cannot read foreground task id err=${error.javaClass.simpleName}")
        }
        return -1
    }

    fun removeTask(taskId: Int): Boolean {
        return try {
            val result = invoke(activityTaskManagerService(), "removeTask", taskId)
            val ok = result !is Boolean || result
            TaskCenterLog.i("Recent task removed id=$taskId ok=$ok")
            ok
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot remove recent task id=$taskId", error)
            false
        }
    }

    fun removeAllTasks(tasks: List<RecentTaskPreview>): Int {
        var removed = 0
        for (t in tasks) if (t.taskId >= 0 && removeTask(t.taskId)) removed++
        TaskCenterLog.i("Close-all removed count=$removed/${tasks.size}")
        return removed
    }

    fun openAppDetails(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot open app details package=$packageName", error)
            false
        }
    }

    fun openHomeSearch(): Boolean {
        val intent = globalSearchIntent() ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            TaskCenterLog.i("Home search launched intent=$intent")
            true
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot open home search", error)
            false
        }
    }

    private fun globalSearchIntent(): Intent? {
        val searchManager = context.getSystemService(Context.SEARCH_SERVICE) as? android.app.SearchManager
        val registered = searchManager?.globalSearchActivity
        if (registered != null) {
            val direct = Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH).setComponent(registered)
            if (direct.resolveActivity(packageManager) != null) return direct
        }
        val byAction = Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
        return if (byAction.resolveActivity(packageManager) != null) byAction else null
    }

    data class ShortcutEntry(
        val packageName: String,
        val id: String,
        val label: CharSequence,
        val user: UserHandle,
    )

    fun launchShortcut(entry: ShortcutEntry): Boolean {
        return try {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
            launcherApps.startShortcut(entry.packageName, entry.id, null, null, entry.user)
            TaskCenterLog.i("Shortcut launched package=${entry.packageName} id=${entry.id}")
            true
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot launch shortcut package=${entry.packageName} id=${entry.id}", error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun launch(task: RecentTaskPreview): Boolean {
        if (task.taskId < 0) return false
        val centered = centeredScaleOptions(context).toBundle()
        if (launchViaRecents(task, centered)) return true
        if (launchViaRecents(task, ActivityOptions.makeBasic().toBundle())) return true
        return try {
            activityManager?.moveTaskToFront(task.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
            TaskCenterLog.i("Recent task moved to front id=${task.taskId}")
            true
        } catch (finalFallback: Throwable) {
            TaskCenterLog.e("Cannot launch recent task id=${task.taskId}", finalFallback)
            false
        }
    }

    private fun launchViaRecents(task: RecentTaskPreview, options: Bundle): Boolean {
        return try {
            val service = activityTaskManagerService()
            val result = invoke(service, "startActivityFromRecents", task.taskId, options)
            if (result is Number && result.toInt() < 0) {
                TaskCenterLog.i("Recent task start reported failure id=${task.taskId} result=$result")
                false
            } else {
                TaskCenterLog.i("Recent task launched id=${task.taskId} result=$result")
                true
            }
        } catch (error: Throwable) {
            TaskCenterLog.i("Recent task start threw id=${task.taskId} err=${error.javaClass.simpleName}")
            false
        }
    }

    private fun loadSnapshot(taskId: Int): Bitmap? {
        var buffer: HardwareBuffer? = null
        return try {
            val service = activityTaskManagerService()
            var snapshot = invoke(service, "getTaskSnapshot", taskId, true)
            if (snapshot == null) snapshot = invoke(service, "takeTaskSnapshot", taskId, true)
            if (snapshot == null) return null
            buffer = invoke(snapshot, "getHardwareBuffer") as? HardwareBuffer ?: return null
            val colorSpace = invoke(snapshot, "getColorSpace") as? ColorSpace
            if (buffer.isClosed) return null
            val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            hardware.setHasMipMap(true)
            hardware
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot load task snapshot id=$taskId", error)
            null
        } finally {
            if (buffer != null && !buffer.isClosed) buffer.close()
        }
    }

    companion object {
        private val EXCLUDED_PACKAGES = setOf(
            "com.slideindex.app",
            "com.android.systemui",
            "com.miui.home",
        )

        /** 反射调用目标方法，沿父类向上查找（标准库，不依赖 Xposed）。 */
        fun invoke(target: Any?, method: String, vararg args: Any?): Any? {
            if (target == null) return null
            val types = arrayOfNulls<Class<*>?>(args.size)
            for (i in args.indices) {
                types[i] = when (val a = args[i]) {
                    is Int -> Int::class.javaPrimitiveType
                    is Boolean -> Boolean::class.javaPrimitiveType
                    is Long -> Long::class.javaPrimitiveType
                    is Float -> Float::class.javaPrimitiveType
                    is Double -> Double::class.javaPrimitiveType
                    else -> a?.javaClass
                }
            }
            var owner: Class<*>? = target.javaClass
            while (owner != null) {
                try {
                    val found = owner.getDeclaredMethod(method, *types) ?: continue
                    found.isAccessible = true
                    return found.invoke(target, *args)
                } catch (_: NoSuchMethodException) {
                    owner = owner.superclass
                } catch (error: Throwable) {
                    TaskCenterLog.i("Reflection call failed method=$method err=${error.javaClass.simpleName}")
                    return null
                }
            }
            TaskCenterLog.i("Reflection target method unavailable method=$method")
            return null
        }

        private fun activityTaskManagerService(): Any? = try {
            val manager = Class.forName("android.app.ActivityTaskManager")
            val getService = manager.getDeclaredMethod("getService")
            getService.isAccessible = true
            getService.invoke(null)
        } catch (error: Throwable) {
            TaskCenterLog.i("ActivityTaskManager service unavailable err=${error.javaClass.simpleName}")
            null
        }

        /** 居中缩放启动动画选项（移植自 FullscreenAppLauncher.centeredScaleOptions）。 */
        fun centeredScaleOptions(context: Context): ActivityOptions {
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            val display = wm?.currentWindowMetrics?.bounds
                ?: android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
            val view = android.view.View(context)
            view.layout(0, 0, display.width(), display.height())
            val density = context.resources.displayMetrics.density
            val startSize = maxOf(1, (56f * density).roundToInt())
            val startX = display.centerX() - startSize / 2
            val startY = display.centerY() - startSize / 2
            val options = ActivityOptions.makeScaleUpAnimation(view, startX, startY, startSize, startSize)
            try {
                val f = ActivityOptions::class.java.getDeclaredMethod("setOverrideTaskTransition", Boolean::class.javaPrimitiveType)
                f.isAccessible = true
                f.invoke(options, true)
            } catch (_: Throwable) {
                // 旧版本无该隐藏 API，忽略。
            }
            return options
        }
    }
}
