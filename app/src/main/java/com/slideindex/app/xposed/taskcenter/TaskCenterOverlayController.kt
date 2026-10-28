package com.slideindex.app.xposed.taskcenter

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * 任务中心承载层：以 overlay（type=2038）承载列表视图，统一管理窗口生命周期。
 *
 * 仅保留「卡片列表」档位（我们的任务中心形态）；原 FanFreeform 的「视差卡片」档位不移植。
 * 窗口"浮"在当前应用之上，不改变界面身份；带 FLAG_WATCH_OUTSIDE_TOUCH 收 ACTION_OUTSIDE，
 * 点外部即关；关闭是 removeViewImmediate，瞬时干脆，不走系统 task 过渡。
 *
 * 视图存活/关闭判据一律以 [isAttached]（窗口是否真在屏幕上）为准，绝不对手势状态机的 state 判定
 * （见项目长期约定：视图生命周期不以 state 为准）。
 */
class TaskCenterOverlayController(
    private val context: Context,
    private val mainHandler: Handler,
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val moveLock = Any()
    private var view: TaskCenterSurface? = null
    private var attached = false
    private var pendingMoveView: TaskCenterSurface? = null
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var moveFrameScheduled = false
    private val deliverPendingMove = Runnable {
        val target: TaskCenterSurface?
        val x: Float
        val y: Float
        synchronized(moveLock) {
            target = pendingMoveView
            x = pendingMoveX
            y = pendingMoveY
            pendingMoveView = null
            moveFrameScheduled = false
        }
        if (attached && target != null && view == target) target.onExternalMove(x, y)
    }

    fun show(
        tasks: List<RecentTaskPreview>,
        corner: GestureCorner,
        anchorX: Float,
        anchorY: Float,
        config: TaskCenterConfig,
        listener: TaskCenterListener,
        lockedApps: LockedAppStore?,
    ): Boolean {
        removeNow()
        if (windowManager == null || tasks.isEmpty()) return false
        val next = createListSurface(tasks, corner, anchorX, anchorY, config, listener, lockedApps)
        TaskCenterLog.i("TaskCenter surface creating tasks=${tasks.size}")
        val height = windowManager.getCurrentWindowMetrics().bounds.height()
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            height,
            2038,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.setFitInsetsTypes(0)
        params.setTitle("SlideIndexTaskCenter")
        params.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            windowManager.addView(next as View, params)
            view = next
            attached = true
            next.playEntry()
            return true
        } catch (error: Throwable) {
            TaskCenterLog.e("Cannot attach task center", error)
            next.releaseResources()
            view = null
            attached = false
            return false
        }
    }

    private fun createListSurface(
        tasks: List<RecentTaskPreview>,
        corner: GestureCorner,
        anchorX: Float,
        anchorY: Float,
        config: TaskCenterConfig,
        listener: TaskCenterListener,
        lockedApps: LockedAppStore?,
    ): TaskCenterSurface {
        val list = TaskCenterListView(context)
        list.configure(
            tasks,
            corner,
            anchorX,
            anchorY,
            config,
            object : TaskCenterListener {
                override fun onLaunch(task: RecentTaskPreview) {
                    listener.onLaunch(task)
                    playExit(list, TaskPanelExit.YIELD)
                }

                override fun onCloseTask(task: RecentTaskPreview) {
                    listener.onCloseTask(task)
                }

                override fun onCloseAll() {
                    listener.onCloseAll()
                    playExit(list, TaskPanelExit.YIELD)
                }

                override fun onListAction(
                    action: TaskPanelAction,
                    task: RecentTaskPreview?,
                    shortcut: RecentTaskRepository.ShortcutEntry?,
                ) {
                    listener.onListAction(action, task, shortcut)
                    // 只有"会跳到别处"的动作才直接关面板；锁定是开关，与行内锁形同一语义，关掉面板用户就没法连着锁下一个了。
                    if (action != TaskPanelAction.LOCK) {
                        playExit(list, TaskPanelExit.YIELD)
                    }
                }

                override fun onToggleLock(task: RecentTaskPreview, shouldLock: Boolean) {
                    // 锁定不关列表：像开关，用户会想连着锁好几个应用。
                    listener.onToggleLock(task, shouldLock)
                }

                override fun onClosed() {
                    removeNow()
                    listener.onClosed()
                }
            },
            lockedApps,
        )
        return list
    }

    /**
     * 按出场因果退场：列表播完动画再回调 onClosed 撤窗口；兜底在预期时长后补一刀移除，
     * 防止动画结束回调丢失导致全屏 overlay 一直挂在屏幕上（整个屏幕不可用）。
     */
    fun playExit(current: TaskCenterSurface, exitReason: TaskPanelExit) {
        if (attached && view == current) {
            runOnViewThread(current) { current.playExit(exitReason, 0f) }
        } else {
            removeNow()
            return
        }
        mainHandler.postDelayed({ if (view == current) removeNow() }, 900L)
    }

    fun playExit(exitReason: TaskPanelExit) {
        val current = view ?: return
        playExit(current, exitReason)
    }

    /** 系统面板展开：退位但不销毁，收起后原样弹回。 */
    fun setPaused(paused: Boolean) {
        val current = view ?: return
        runOnViewThread(current) { current.setPaused(paused) }
    }

    /** 侧边返回手势跟手：把进度透传给列表面板。 */
    fun setBackYield(progress: Float, fromLeft: Boolean) {
        val current = view ?: return
        runOnViewThread(current) { current.setBackYieldProgress(progress, fromLeft) }
    }

    fun releaseBackYield() {
        val current = view ?: return
        runOnViewThread(current) { current.releaseBackYield() }
    }

    fun isVisible(): Boolean = attached && view != null

    fun externalMove(x: Float, y: Float) {
        val current = view ?: return
        if (!attached) return
        synchronized(moveLock) {
            pendingMoveView = current
            pendingMoveX = x
            pendingMoveY = y
            if (moveFrameScheduled) return
            moveFrameScheduled = true
        }
        (current as View).postOnAnimation(deliverPendingMove)
    }

    fun externalUp(x: Float, y: Float, cancelled: Boolean) {
        val current = view ?: return
        cancelPendingMove(current)
        runOnViewThread(current) { current.onExternalUp(x, y, cancelled) }
    }

    fun externalCancel() {
        val current = view ?: return
        cancelPendingMove(current)
        runOnViewThread(current) { current.onExternalCancel() }
    }

    fun dismiss() {
        val current = view ?: return
        runOnViewThread(current) { current.playDismissal() }
    }

    /** 窗口当前是否挂在 WindowManager 上（诊断用）。 */
    fun isAttached(): Boolean = attached && view != null

    fun removeNow() {
        val current = view
        cancelPendingMove(current)
        view = null
        if (!attached || current == null || windowManager == null) {
            attached = false
            return
        }
        attached = false
        mainHandler.removeCallbacksAndMessages(current)
        val removal = Runnable {
            try {
                windowManager.removeViewImmediate(current as View)
            } catch (error: Throwable) {
                TaskCenterLog.e("Cannot remove task center", error)
            } finally {
                current.releaseResources()
            }
        }
        val owner = current.getHandler()
        if (owner != null && owner.looper != Looper.myLooper()) {
            owner.post(removal)
        } else {
            removal.run()
        }
    }

    private fun runOnViewThread(current: TaskCenterSurface, action: Runnable) {
        if (!attached || current == null) return
        val owner = current.getHandler()
        if (owner != null && owner.looper != Looper.myLooper()) {
            owner.post {
                if (attached && view == current) action.run()
            }
        } else if (attached && view == current) {
            action.run()
        }
    }

    private fun cancelPendingMove(current: TaskCenterSurface?) {
        current?.getHandler()?.removeCallbacks(deliverPendingMove)
        synchronized(moveLock) {
            if (pendingMoveView == current) pendingMoveView = null
            moveFrameScheduled = false
        }
    }
}
