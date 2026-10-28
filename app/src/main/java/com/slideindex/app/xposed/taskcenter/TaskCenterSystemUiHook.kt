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
        TaskCenterLog.i("TaskCenter SystemUI hook installed")
    }

    /** 与 XGesture 现有 hook 一致的取 Context 方式：ActivityThread.currentActivityThread().getSystemContext()。 */
    private fun resolveSystemContext(classLoader: ClassLoader): Context? = runCatching {
        val activityThread = LibXposedReflect.findClass("android.app.ActivityThread", classLoader)
        val current = LibXposedReflect.callStaticMethod(activityThread, "currentActivityThread")
        LibXposedReflect.callMethod(current!!, "getSystemContext") as Context
    }.getOrElse { error ->
        TaskCenterLog.e("resolveSystemContext failed", error)
        null
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(TaskCenterBroadcastContract.ACTION_OPEN)
            addAction(TaskCenterBroadcastContract.ACTION_CLOSE)
        }
        // 跨进程广播（App → SystemUI），必须 exported。
        // 用 ContextCompat 而不是 Context.RECEIVER_EXPORTED：后者要 API 33，
        // 而本模块 minSdk 是 31，ContextCompat 会按版本补齐（API 26+ 走旧重载）。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        TaskCenterLog.i("TaskCenter receiver registered")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TaskCenterBroadcastContract.ACTION_OPEN -> openTaskCenter(intent)
                TaskCenterBroadcastContract.ACTION_CLOSE -> dismiss()
            }
        }
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
