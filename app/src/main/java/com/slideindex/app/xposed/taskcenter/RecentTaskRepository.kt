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
import android.os.IBinder
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import com.slideindex.app.shizuku.SystemReflect
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

    /** 系统服务接口的一次查询（不同 ROM 上签名不同，返回结构一样）。 */
    private data class TaskQuery(val name: String, val call: (Any) -> Any?)

    fun loadRunningTasks(maximum: Int, includeSnapshots: Boolean): List<RecentTaskPreview> {
        val limit = maximum.coerceIn(2, 10)
        val viaActivityManager = ArrayList<RecentTaskPreview>()
        val activityManagerSource = loadViaActivityManager(limit, includeSnapshots, viaActivityManager)
        if (viaActivityManager.isNotEmpty()) {
            TaskCenterLog.i(
                "TaskCenter task list source=$activityManagerSource count=${viaActivityManager.size}"
            )
            return viaActivityManager
        }
        // ActivityManager.getRecentTasks() 只保证返回调用方自己的任务（第三方应用拿不到别人的）。
        // 本仓库在 SystemUI 进程里调用它，不同 ROM / 不同调用路径并不一致，拿不到就退回
        // IActivityTaskManager 直接问系统服务要（与仓库既有的 SystemRecentsAccess 同一条通路）。
        val viaTaskManager = ArrayList<RecentTaskPreview>()
        val taskManagerSource = loadViaTaskManager(limit, includeSnapshots, viaTaskManager)
        TaskCenterLog.i(
            "TaskCenter task list source=$taskManagerSource count=${viaTaskManager.size}"
        )
        return viaTaskManager
    }

    /** 走 ActivityManager 拿任务（拿到什么算什么，返回值即"数据来源"标签）。 */
    private fun loadViaActivityManager(
        limit: Int,
        includeSnapshots: Boolean,
        out: ArrayList<RecentTaskPreview>,
    ): String {
        val am = activityManager ?: return "am.unavailable"
        return try {
            val recents = am.getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            for (task in recents) {
                if (out.size >= limit) break
                if (task == null || task.taskId < 0 || !task.isRunning || task.numActivities <= 0) continue
                val component = task.topActivity ?: task.baseActivity ?: task.baseIntent?.component
                if (component == null) continue
                appendPreview(out, task.taskId, component.packageName, includeSnapshots)
            }
            "am.getRecentTasks"
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot load running task previews", error)
            "am.getRecentTasks(error)"
        }
    }

    /** 走 IActivityTaskManager 系统服务接口拿任务（各签名依次尝试，第一个非空的即采用）。 */
    private fun loadViaTaskManager(
        limit: Int,
        includeSnapshots: Boolean,
        out: ArrayList<RecentTaskPreview>,
    ): String {
        val (source, raw) = rawTasksViaTaskManager(TASK_QUERY_MAX)
        if (raw.isEmpty()) return source
        for (item in raw) {
            if (out.size >= limit) break
            val taskId = SystemReflect.readInt(item, "getTaskId", "getId", "taskId", "id") ?: continue
            if (taskId <= 0) continue
            val component = SystemReflect.readComponent(
                item,
                "getTopActivity",
                "getRealActivity",
                "getBaseActivity",
                "getOrigActivity",
                "topActivity",
                "realActivity",
                "baseActivity",
                "origActivity",
            )
                ?: SystemReflect.readComponentFromInfo(item, "topActivityInfo", "baseActivityInfo")
                ?: (SystemReflect.readField(item, "baseIntent") as? Intent)?.component
                ?: continue
            val numActivities = SystemReflect.readInt(item, "getNumActivities", "numActivities") ?: 1
            if (numActivities <= 0) continue
            val running = (SystemReflect.invoke(item, "isRunning") as? Boolean)
                ?: (SystemReflect.readField(item, "isRunning") as? Boolean)
                ?: true
            if (!running) continue
            appendPreview(out, taskId, component.packageName, includeSnapshots)
        }
        return source
    }

    /**
     * 直接问系统服务要原始任务列表。
     *
     * 依次尝试 [ActivityTaskManager.getService]、[ActivityManager.getService]、
     * `ServiceManager.getService("activity_task")` 三种拿法，再对每个实例按多个签名尝试查询，
     * 第一个拿到非空列表的即采用。返回 (来源标签, 原始任务列表)。
     */
    private fun rawTasksViaTaskManager(maxNum: Int): Pair<String, List<Any>> {
        for (manager in taskManagerServices()) {
            for (query in taskManagerQueries(maxNum)) {
                val raw = runCatching { SystemReflect.unwrapList(query.call(manager)) }.getOrNull() ?: continue
                if (raw.isEmpty()) continue
                return query.name to raw
            }
        }
        return "atm.none" to emptyList()
    }

    /**
     * 拿 IActivityTaskManager / IActivityManager 的系统服务实例。
     *
     * `ActivityManager.getService()` / `ServiceManager.getService()` 都是非 SDK 接口，
     * 公开 SDK 里查不到。Lint 依据 App 自身的 targetSdk 判为 BlockedPrivateApi（error 级），
     * 但本类只在 **SystemUI 进程**内使用（见 [TaskCenterSystemUiHook]），SystemUI 是系统应用、
     * 走 hidden API 白名单豁免，不受 targetSdk 的非 SDK 接口限制影响，故按事实豁免。
     *
     * 抑制写法与仓库既有先例一致（见 shizuku/SystemRecentsAccess.taskManagers）。
     */
    @SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    private fun taskManagerServices(): List<Any> {
        val managers = LinkedHashSet<Any>()
        activityTaskManagerService()?.let { managers += it }
        runCatching {
            val clazz = Class.forName("android.app.ActivityManager")
            managers += clazz.getDeclaredMethod("getService").invoke(null)!!
        }
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            managers += stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        }
        return managers.toList()
    }

    private fun taskManagerQueries(maxNum: Int): List<TaskQuery> {
        val callingUid = runCatching { android.os.Process.myUid() }.getOrDefault(0)
        val callingPackage = context.packageName
        return listOf(
            TaskQuery("atm.getRecentTasks6") {
                SystemReflect.invoke(it, "getRecentTasks", maxNum, RECENT_FLAGS, 0, callingPackage, false, callingUid)
            },
            TaskQuery("atm.getRecentTasks3") {
                SystemReflect.invoke(it, "getRecentTasks", maxNum, RECENT_FLAGS, 0)
            },
            TaskQuery("atm.getRecentTasks2") {
                SystemReflect.invoke(it, "getRecentTasks", maxNum, RECENT_FLAGS)
            },
            TaskQuery("atm.getTasks3") {
                SystemReflect.invoke(it, "getTasks", maxNum, false, false)
            },
            TaskQuery("atm.getTasks2") {
                SystemReflect.invoke(it, "getTasks", maxNum, false)
            },
            TaskQuery("atm.getTasksFlags") {
                SystemReflect.invoke(it, "getTasks", maxNum, RECENT_FLAGS)
            },
            TaskQuery("atm.getTasks1") {
                SystemReflect.invoke(it, "getTasks", maxNum)
            },
        )
    }

    /** 把一条任务转成列表项（取图标 / 应用名 / 快照），单项失败不影响整张列表。 */
    private fun appendPreview(
        out: ArrayList<RecentTaskPreview>,
        taskId: Int,
        packageName: String,
        includeSnapshots: Boolean,
    ) {
        // 排除本模块 / SystemUI / 桌面自身，避免任务中心把自己或桌面列进来。
        if (EXCLUDED_PACKAGES.contains(packageName)) return
        try {
            val app = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(app)
            val icon = packageManager.getApplicationIcon(app)
            // 快照拿不到就留空，绝不让整个列表失败。
            val snapshot = if (includeSnapshots) loadSnapshot(taskId) else null
            out.add(RecentTaskPreview(taskId, packageName, label, snapshot, icon))
        } catch (itemError: Throwable) {
            TaskCenterLog.i(
                "Skipping unavailable recent task package=$packageName err=${itemError.javaClass.simpleName}"
            )
        }
    }

    fun release(previews: List<RecentTaskPreview>?) {
        previews ?: return
        for (p in previews) {
            if (p.snapshot != null && !p.snapshot.isRecycled) p.snapshot.recycle()
        }
    }

    /** 列表打开瞬间的前台任务 id（分屏时填到另一侧 stage 用）。 */
    fun foregroundTaskId(): Int {
        val am = activityManager
        if (am != null) {
            try {
                val recents = am.getRecentTasks(5, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                for (task in recents) {
                    if (task == null || task.taskId < 0 || !task.isRunning || task.numActivities <= 0) continue
                    return task.taskId
                }
            } catch (error: Throwable) {
                TaskCenterLog.i("Cannot read foreground task id err=${error.javaClass.simpleName}")
            }
        }
        // 兜底：ActivityManager 拿不到时走系统服务接口。
        val (_, raw) = rawTasksViaTaskManager(TASK_QUERY_MAX)
        for (item in raw) {
            val taskId = SystemReflect.readInt(item, "getTaskId", "getId", "taskId", "id") ?: continue
            if (taskId <= 0) continue
            TaskCenterLog.i("Foreground task id resolved via task manager id=$taskId")
            return taskId
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

        /** 一次向系统服务要多少条任务。 */
        private const val TASK_QUERY_MAX = 40

        /** AOSP 的 RECENT_WITH_EXCLUDED / RECENT_IGNORE_UNAVAILABLE / RECENT_INCLUDE_PROFILES。 */
        private const val RECENT_WITH_EXCLUDED = 0x0001
        private const val RECENT_IGNORE_UNAVAILABLE = 0x0002
        private const val RECENT_INCLUDE_PROFILES = 0x0004
        private const val RECENT_FLAGS =
            RECENT_WITH_EXCLUDED or RECENT_IGNORE_UNAVAILABLE or RECENT_INCLUDE_PROFILES

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

        /**
         * 取 IActivityTaskManager 实例（任务快照 / 从最近任务唤起 / 移除任务都靠它）。
         *
         * `ActivityTaskManager.getService()` 是隐藏 API，公开 SDK 里没有；但本类只在
         * **SystemUI 进程**内使用（见 [TaskCenterSystemUiHook]），SystemUI 是系统应用、
         * 走 hidden API 白名单豁免，不受 targetSdk 的非 SDK 接口限制影响。
         *
         * Lint 依据 App 自身的 targetSdk 判为 BlockedPrivateApi，此处按事实豁免。
         */
        @SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
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
