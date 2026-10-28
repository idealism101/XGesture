package com.slideindex.app.xposed.taskcenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.slideindex.app.xposed.LibXposedReflect

/**
 * 任务中心的 SystemUI 侧运行时。
 *
 * 由 [com.slideindex.app.xposed.SlideIndexLibXposedModule] 在 `com.android.systemui` 就绪时安装。
 * 负责：① 解析 SystemUI 的 Context（用于挂 ty=2038 窗口）；② 监听 App 进程发来的"打开 / 关闭"
 * 广播；③ 拉取最近任务 + 锁定状态，调起 [TaskCenterOverlayController] 显示任务中心。
 *
 * 所有原生动作（锁定 / 分屏 / 小窗 / 关闭 / 切换）都在此进程内执行，不再跨进程。
 */
object TaskCenterSystemUiHook {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var systemContext: Context? = null
    private var controller: TaskCenterOverlayController? = null
    private var lockedStore: LockedAppStore? = null
    private var currentTasks: List<RecentTaskPreview> = emptyList()
    private var receiverRegistered = false

    fun install(classLoader: ClassLoader) {
        val ctx = resolveSystemContext(classLoader)
        if (ctx == null) {
            TaskCenterLog.e("Cannot resolve SystemUI context; task center hook skipped")
            return
        }
        systemContext = ctx
        registerReceiver(ctx)
        TaskCenterLog.i("TaskCenter SystemUI hook installed package=${ctx.packageName}")
        // 主动回一条 READY：App 侧据此判断"SystemUI 作用域是否生效"。
        // 没勾作用域时这个钩子根本不会装，App 侧就收不到 READY，能给出可见提示而不是静默失败。
        notifyReady(ctx)
    }

    /**
     * 取 SystemUI 进程的 Context。
     *
     * **必须优先用 SystemUI 自己的应用 Context（包名 = com.android.systemui）**，原因有二：
     *
     * ① 运行时广播接收器的"归属包名"取自注册它的 Context 的包名。App 侧用
     *    `setPackage("com.android.systemui")` 定向发送，只有用同一个包名注册的 receiver 才会命中；
     *    用系统 Context（包名 = "android"）注册会让广播被按包名过滤掉，**静默丢弃、没有任何日志**。
     * ② overlay、PackageManager（图标/应用名）、startActivity 都应当以 SystemUI 的身份发起。
     *
     * `ActivityThread.currentActivityThread().getSystemContext()` 返回的是**系统 Context**
     *（包名 = "android"），只作为最后兜底；优先走 `currentApplication()`。
     */
    private fun resolveSystemContext(classLoader: ClassLoader): Context? {
        val activityThread = runCatching {
            LibXposedReflect.findClass("android.app.ActivityThread", classLoader)
        }.getOrNull()
        if (activityThread == null) {
            TaskCenterLog.e("Cannot load ActivityThread class; task center hook skipped")
            return null
        }
        // ① SystemUI 应用 Context（包名 = com.android.systemui）—— 正常路径。
        val application = runCatching {
            LibXposedReflect.callStaticMethod(activityThread, "currentApplication")
        }.getOrNull() as? Context
        if (application != null) {
            val appContext = runCatching { application.applicationContext }.getOrNull() ?: application
            TaskCenterLog.i("TaskCenter resolved SystemUI application context package=${appContext.packageName}")
            return appContext
        }
        // ② 钩子装得太早、Application 还没建起来时，退回系统 Context 再造一个
        //    "com.android.systemui" 包上下文的 Context（归属包名依旧正确）。
        val systemContext = runCatching {
            val current = LibXposedReflect.callStaticMethod(activityThread, "currentActivityThread")
            LibXposedReflect.callMethod(current!!, "getSystemContext") as? Context
        }.getOrNull()
        if (systemContext != null) {
            val packageContext = runCatching {
                systemContext.createPackageContext(
                    TaskCenterBroadcastContract.TARGET_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY,
                )
            }.getOrNull()
            if (packageContext != null) {
                TaskCenterLog.i("TaskCenter resolved SystemUI package context package=${packageContext.packageName}")
                return packageContext
            }
            TaskCenterLog.e("SystemUI application context unavailable; falling back to system context")
            return systemContext
        }
        TaskCenterLog.e("Cannot resolve SystemUI context; task center hook skipped")
        return null
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(TaskCenterBroadcastContract.ACTION_OPEN)
            addAction(TaskCenterBroadcastContract.ACTION_CLOSE)
            addAction(TaskCenterBroadcastContract.ACTION_PING)
        }
        // 跨进程广播（App → SystemUI），必须 exported。
        // 用 ContextCompat 而不是 Context.RECEIVER_EXPORTED：后者要 API 33，
        // 而本模块 minSdk 是 31，ContextCompat 会按版本补齐（API 26+ 走旧重载）。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        TaskCenterLog.i("TaskCenter receiver registered package=${context.packageName}")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TaskCenterBroadcastContract.ACTION_OPEN -> openTaskCenter(intent)
                TaskCenterBroadcastContract.ACTION_CLOSE -> dismiss()
                TaskCenterBroadcastContract.ACTION_PING -> notifyReady(context ?: systemContext)
            }
        }
    }

    /** 回一条 READY，告诉 App 侧"SystemUI 钩子已装好、任务中心可以打开了"。 */
    private fun notifyReady(context: Context?) {
        val ctx = context ?: return
        val intent = Intent(TaskCenterBroadcastContract.ACTION_READY)
            .setPackage(TaskCenterBroadcastContract.TARGET_APP_PACKAGE)
            .addFlags(TaskCenterBroadcastContract.FLAG_RECEIVER_INCLUDE_BACKGROUND)
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { TaskCenterLog.e("TaskCenter ready broadcast failed", it) }
        TaskCenterLog.i("TaskCenter ready sent to ${TaskCenterBroadcastContract.TARGET_APP_PACKAGE}")
    }

    private fun openTaskCenter(intent: Intent) {
        val ctx = systemContext ?: return
        val corner = when (intent.getStringExtra(TaskCenterBroadcastContract.EXTRA_CORNER)) {
            "LEFT" -> GestureCorner.LEFT
            "RIGHT" -> GestureCorner.RIGHT
            "TOP" -> GestureCorner.TOP
            else -> GestureCorner.BOTTOM
        }
        val anchorX = intent.getFloatExtra(TaskCenterBroadcastContract.EXTRA_ANCHOR_X, 0f)
        val anchorY = intent.getFloatExtra(TaskCenterBroadcastContract.EXTRA_ANCHOR_Y, 0f)

        val repo = RecentTaskRepository(ctx)
        val store = LockedAppStore(ctx, LockedAppStore.currentUserId())
        lockedStore = store
        store.load()
        val tasks = repo.loadRunningTasks(10, true)
        if (tasks.isEmpty()) {
            TaskCenterLog.i("No running tasks; task center not shown")
            repo.release(tasks)
            return
        }
        currentTasks = tasks

        if (controller == null) controller = TaskCenterOverlayController(ctx, mainHandler)
        controller?.show(
            tasks,
            corner,
            anchorX,
            anchorY,
            TaskCenterConfig(),
            object : TaskCenterListener {
                override fun onLaunch(task: RecentTaskPreview) {
                    repo.launch(task)
                }

                override fun onCloseTask(task: RecentTaskPreview) {
                    repo.removeTask(task.taskId)
                }

                override fun onCloseAll() {
                    repo.removeAllTasks(currentTasks)
                }

                override fun onListAction(
                    action: TaskPanelAction,
                    task: RecentTaskPreview?,
                    shortcut: RecentTaskRepository.ShortcutEntry?,
                ) {
                    if (task == null) return
                    TaskCenterActions.execute(ctx, action, task, repo)
                }

                override fun onToggleLock(task: RecentTaskPreview, shouldLock: Boolean) {
                    TaskCenterActions.setLocked(ctx, task.packageName, shouldLock)
                    store.refresh()
                }

                override fun onClosed() {
                    TaskCenterLog.i("Task center closed")
                    repo.release(currentTasks)
                    currentTasks = emptyList()
                }
            },
            store,
        )
    }

    /** 供 FanRuntime 侧联动（如开小窗 / 通知栏展开）调用：安静关掉任务中心。 */
    fun dismiss() {
        controller?.removeNow()
    }
}
