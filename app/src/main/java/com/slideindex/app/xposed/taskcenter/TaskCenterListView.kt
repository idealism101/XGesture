package com.slideindex.app.xposed.taskcenter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * 任务中心列表视图（自定义绘制）。
 *
 * 每行 = 圆形应用图标 + 应用名 + 快照缩略 + 锁定徽标 + 动作工具栏（分屏/小窗/锁/关）。
 * 交互：点行 → 切换；长按行 → 切换锁定；横向滑动行 → 关闭该任务；点动作按钮 → 对应动作。
 *
 * 为非选中行退后一档（FOCUS_RECEDE），选中行放大 + 强调环 + 淡底圆，符合项目图标定调。
 * 颜色只引本文件的令牌常量，不引外部 Ui（保持承载层自包含）。
 */
class TaskCenterListView(context: Context) : View(context), TaskCenterSurface {

    // ---- 设计令牌（与项目定调一致）----
    private val accent = TASK_CENTER_ACCENT
    private val rowHeightPx = dp(76f)
    private val iconSizePx = dp(44f)
    private val snapSizePx = dp(56f)
    private val panelMarginPx = dp(16f)
    private val cornerRadiusPx = dp(18f)
    private val buttonSizePx = dp(40f)
    private val FOCUS_RECEDE = 0.94f
    private val panelBg = Color.argb(232, 18, 18, 22)
    private val rowBg = Color.argb(60, 255, 255, 255)
    private val rowBgSelected = Color.argb(96, 255, 255, 255)
    private val textMain = Color.argb(238, 255, 255, 255)
    private val textSub = Color.argb(150, 255, 255, 255)
    private val dimColor = Color.argb(120, 0, 0, 0)

    // ---- 数据 ----
    private var tasks: List<RecentTaskPreview> = emptyList()
    private var corner = GestureCorner.BOTTOM
    private var anchorX = 0f
    private var anchorY = 0f
    private var config: TaskCenterConfig = TaskCenterConfig()
    private var listener: TaskCenterListener? = null
    private var lockedStore: LockedAppStore? = null
    private var lockedSet: Set<String> = emptySet()

    // ---- 布局缓存 ----
    private var panelWidthPx = 0
    private var panelX = 0f
    private var panelY = 0f
    private val rowRects = ArrayList<RectF>()
    /** 第 i 行的 4 个动作按钮矩形：split / freeform / lock / close（右→左）。 */
    private val actionRects = ArrayList<Array<RectF>>()
    private val iconBitmaps = ArrayList<Bitmap?>()
    private val snapBitmaps = ArrayList<Bitmap?>()

    // ---- 绘制状态 ----
    private var panelAlpha = 1f
    private var backYieldOffset = 0f
    private var paused = false

    // ---- 触摸 ----
    private val handler = Handler(Looper.getMainLooper())
    private val gestureDetector: GestureDetector
    private var downX = 0f
    private var downY = 0f
    private var activeRow = -1
    private var isSwiping = false
    private var rowOffsetX = 0f
    private val touchSlop = dp(8f)
    private val closeThreshold = dp(96f)
    private var suppressTap = false
    private val longPressRunnable = Runnable { /* 由 detector 接管 */ }

    init {
        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (suppressTap || isSwiping) return true
                    val row = rowAt(e.y)
                    if (row < 0) return true
                    val action = actionAt(row, e.x, e.y)
                    if (action != null) {
                        performAction(row, action)
                        return true
                    }
                    val task = tasks[row]
                    listener?.onLaunch(task)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val row = rowAt(e.y)
                    if (row < 0) return
                    if (actionAt(row, e.x, e.y) != null) return
                    val task = tasks[row]
                    val shouldLock = !lockedSet.contains(task.packageName)
                    listener?.onToggleLock(task, shouldLock)
                    lockedSet = lockedSet.toMutableSet().apply {
                        if (shouldLock) add(task.packageName) else remove(task.packageName)
                    }
                    invalidate()
                }
            },
        )
        gestureDetector.setIsLongpressEnabled(true)
        isClickable = true
        isFocusable = true
    }

    fun configure(
        tasks: List<RecentTaskPreview>,
        corner: GestureCorner,
        anchorX: Float,
        anchorY: Float,
        config: TaskCenterConfig,
        listener: TaskCenterListener,
        lockedApps: LockedAppStore?,
    ) {
        releaseResources()
        this.tasks = tasks
        this.corner = corner
        this.anchorX = anchorX
        this.anchorY = anchorY
        this.config = config
        this.listener = listener
        this.lockedStore = lockedApps
        this.lockedSet = lockedApps?.snapshot() ?: emptySet()
        precomputeBitmaps()
        requestLayout()
        invalidate()
    }

    // ---------- 布局 ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        panelWidthPx = minOf(dp(280f), (screenW * 0.78f)).toInt()
        val rows = tasks.size
        val panelH = (rows * rowHeightPx).toInt()
        val w = screenW
        val h = screenH
        setMeasuredDimension(w, h)
        computePanelPosition(screenW, screenH, panelH)
    }

    private fun computePanelPosition(screenW: Int, screenH: Int, panelH: Int) {
        val clampedH = panelH.coerceAtMost((screenH * 0.82f).toInt())
        panelX = when (corner) {
            GestureCorner.LEFT -> panelMarginPx
            GestureCorner.RIGHT -> screenW - panelWidthPx - panelMarginPx
            else -> (anchorX - panelWidthPx / 2f).coerceIn(panelMarginPx, screenW - panelWidthPx - panelMarginPx)
        }
        panelY = when (corner) {
            GestureCorner.TOP -> panelMarginPx
            GestureCorner.BOTTOM -> screenH - clampedH - panelMarginPx
            else -> (anchorY - clampedH / 2f).coerceIn(panelMarginPx, screenH - clampedH - panelMarginPx)
        }
        rowRects.clear()
        actionRects.clear()
        val rows = tasks.size
        for (i in 0 until rows) {
            val top = panelY + i * rowHeightPx
            rowRects.add(RectF(panelX, top, panelX + panelWidthPx, top + rowHeightPx))
            // 动作按钮：从右往左排 4 个（close / lock / freeform / split）
            val btns = Array(4) { RectF() }
            var bx = panelX + panelWidthPx - panelMarginPx - buttonSizePx
            for (b in 0 until 4) {
                val by = top + (rowHeightPx - buttonSizePx) / 2f
                btns[3 - b] = RectF(bx, by, bx + buttonSizePx, by + buttonSizePx)
                bx -= buttonSizePx + dp(4f)
            }
            actionRects.add(btns)
        }
    }

    // ---------- 绘制 ----------

    // 绘制期复用的临时对象，避免 onDraw 每帧分配（DrawAllocation）。
    private val tmpRect = RectF()
    private val tmpRect2 = RectF()
    private val tmpPath = Path()
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint().apply { isFakeBoldText = true }
    private val subTextPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        if (tasks.isEmpty()) return
        canvas.save()
        if (backYieldOffset != 0f) canvas.translate(backYieldOffset, 0f)
        // 面板背板
        tmpRect.set(panelX, panelY, panelX + panelWidthPx, panelY + rowRects.size * rowHeightPx)
        drawRoundRect(canvas, tmpRect, cornerRadiusPx, panelBg)
        // 逐行
        for (i in tasks.indices) {
            drawRow(canvas, i)
        }
        canvas.restore()
        if (paused) {
            canvas.drawColor(dimColor)
        }
        if (panelAlpha < 1f) invalidate()
    }

    private fun drawRow(canvas: Canvas, i: Int) {
        val r = rowRects[i]
        val task = tasks[i]
        val alphaMul = if (i == activeRow) 1f else FOCUS_RECEDE
        val selected = i == activeRow
        // 行背景
        tmpRect.set(r.left + dp(6f), r.top + dp(4f), r.right - dp(6f), r.bottom - dp(4f))
        drawRoundRect(canvas, tmpRect, dp(14f), if (selected) rowBgSelected else rowBg)
        val cx = r.left + dp(6f) + rowOffsetX
        // 图标（圆形）
        val iconLeft = cx + dp(14f)
        val iconTop = r.top + (rowHeightPx - iconSizePx) / 2f
        val iconBmp = iconBitmaps.getOrNull(i)
        if (iconBmp != null) {
            canvas.save()
            tmpPath.reset()
            tmpPath.addCircle(iconLeft + iconSizePx / 2f, iconTop + iconSizePx / 2f, iconSizePx / 2f, Path.Direction.CW)
            canvas.clipPath(tmpPath)
            canvas.drawBitmap(iconBmp, iconLeft, iconTop, null)
            canvas.restore()
            // 图标淡投影垫圈（功能件，保留）
            strokePaint.color = Color.argb(40, 0, 0, 0)
            strokePaint.strokeWidth = dp(2f)
            canvas.drawCircle(iconLeft + iconSizePx / 2f, iconTop + iconSizePx / 2f, iconSizePx / 2f + dp(1f), strokePaint)
        }
        // 快照缩略
        val snapLeft = iconLeft + iconSizePx + dp(10f)
        val snapTop = r.top + (rowHeightPx - snapSizePx) / 2f
        val snapBmp = snapBitmaps.getOrNull(i)
        if (snapBmp != null) {
            tmpRect.set(snapLeft, snapTop, snapLeft + snapSizePx, snapTop + snapSizePx)
            drawRoundRect(canvas, tmpRect, dp(8f), Color.DKGRAY)
            canvas.save()
            tmpPath.reset()
            tmpPath.addRoundRect(tmpRect, dp(8f), dp(8f), Path.Direction.CW)
            canvas.clipPath(tmpPath)
            canvas.drawBitmap(snapBmp, null, tmpRect, null)
            canvas.restore()
        }
        // 应用名 + 包名
        val textLeft = snapLeft + snapSizePx + dp(12f)
        textPaint.color = textMain
        textPaint.textSize = dp(15f)
        textPaint.alpha = (alphaMul * 255).toInt().coerceIn(0, 255)
        val label = task.label?.toString() ?: task.packageName
        canvas.drawText(label, textLeft, r.top + rowHeightPx / 2f - dp(6f), textPaint)
        subTextPaint.color = textSub
        subTextPaint.textSize = dp(11f)
        subTextPaint.alpha = (alphaMul * 255).toInt().coerceIn(0, 255)
        canvas.drawText(task.packageName, textLeft, r.top + rowHeightPx / 2f + dp(12f), subTextPaint)
        // 锁定徽标（矢量锁形，不用文字：本视图跑在 SystemUI 进程，模块自身 strings 不可用）
        if (lockedSet.contains(task.packageName)) {
            drawLockGlyph(canvas, textLeft + dp(4.5f), r.top + dp(9f), dp(11f), accent, closed = true)
        }
        // 动作按钮
        val btns = actionRects.getOrNull(i)
        if (btns != null) {
            val isLocked = lockedSet.contains(task.packageName)
            drawActionButton(canvas, btns[0]) { cx, cy -> drawSplitGlyph(canvas, cx, cy, accent) }   // split
            drawActionButton(canvas, btns[1]) { cx, cy -> drawFreeformGlyph(canvas, cx, cy, accent) } // freeform
            // 已锁 → 显示开锁（锁梁断开）；未锁 → 显示上锁（锁梁闭合）
            drawActionButton(canvas, btns[2]) { cx, cy ->
                drawLockGlyph(canvas, cx, cy, dp(15f), accent, closed = !isLocked)
            }
            drawActionButton(canvas, btns[3]) { cx, cy ->
                drawCrossGlyph(canvas, cx, cy, Color.argb(200, 255, 90, 90))
            } // close
        }
    }

    /** 动作按钮底板（圆角浅底），图标由调用方绘制。 */
    private fun drawActionButton(canvas: Canvas, rect: RectF, glyph: (Float, Float) -> Unit) {
        drawRoundRect(canvas, rect, dp(10f), Color.argb(40, 255, 255, 255))
        glyph(rect.centerX(), rect.centerY())
    }

    /**
     * 锁形：上方锁梁（半圆环）+ 下方锁体（圆角矩形）。
     *
     * [closed] = true 锁梁闭合（上锁动作）；false 锁梁右移断开（开锁动作）。
     */
    private fun drawLockGlyph(canvas: Canvas, cx: Float, cy: Float, height: Float, color: Int, closed: Boolean) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.6f)
        val bodyH = height * 0.56f
        val bodyTop = cy + height / 2f - bodyH
        val bodyBottom = cy + height / 2f
        val bodyHalfW = bodyH * 0.62f
        tmpRect.set(cx - bodyHalfW, bodyTop, cx + bodyHalfW, bodyBottom)
        canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), strokePaint)
        val shackleR = bodyHalfW * 0.72f
        // 未闭合时锁梁整体右移并抬高，与锁体断开 —— 视觉上就是"锁打开了"
        val shackleCx = if (closed) cx else cx + shackleR * 0.75f
        val shackleTop = if (closed) bodyTop - shackleR * 2f else bodyTop - shackleR * 2.4f
        tmpRect.set(shackleCx - shackleR, shackleTop, shackleCx + shackleR, bodyTop)
        canvas.drawArc(tmpRect, 180f, 180f, false, strokePaint)
    }

    /** 分屏：两条并排竖条。 */
    private fun drawSplitGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        fillPaint.color = color
        val barW = dp(2.6f)
        val gap = dp(2.4f)
        val h = dp(13f)
        val top = cy - h / 2f
        tmpRect.set(cx - gap / 2f - barW, top, cx - gap / 2f, top + h)
        canvas.drawRoundRect(tmpRect, dp(1.3f), dp(1.3f), fillPaint)
        tmpRect.set(cx + gap / 2f, top, cx + gap / 2f + barW, top + h)
        canvas.drawRoundRect(tmpRect, dp(1.3f), dp(1.3f), fillPaint)
    }

    /** 小窗：圆角窗口框 + 左上角标题条实心块（浮在整屏之上的一扇窗）。 */
    private fun drawFreeformGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.6f)
        val w = dp(13f)
        val h = dp(11f)
        tmpRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), strokePaint)
        fillPaint.color = color
        tmpRect2.set(tmpRect.left + dp(2.5f), tmpRect.top + dp(2.5f), tmpRect.left + dp(7f), tmpRect.top + dp(4.2f))
        canvas.drawRoundRect(tmpRect2, dp(1f), dp(1f), fillPaint)
    }

    /** 关闭：两笔交叉。 */
    private fun drawCrossGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.6f)
        val arm = dp(5f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, strokePaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, strokePaint)
    }

    private fun drawRoundRect(canvas: Canvas, rect: RectF, radius: Float, color: Int) {
        fillPaint.color = color
        tmpPath.reset()
        tmpPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.drawPath(tmpPath, fillPaint)
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                suppressTap = false
                isSwiping = false
                rowOffsetX = 0f
                downX = event.x
                downY = event.y
                activeRow = rowAt(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeRow >= 0 && !suppressTap) {
                    val dx = event.x - downX
                    if (Math.abs(dx) > touchSlop) {
                        isSwiping = true
                        rowOffsetX = dx.coerceIn(-width.toFloat(), width.toFloat())
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwiping && activeRow >= 0) {
                    suppressTap = true
                    if (Math.abs(rowOffsetX) > closeThreshold) {
                        closeRow(activeRow)
                    } else {
                        rowOffsetX = 0f
                        invalidate()
                    }
                }
                activeRow = -1
                isSwiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activeRow = -1
                isSwiping = false
                rowOffsetX = 0f
                invalidate()
            }
        }
        gestureDetector.onTouchEvent(event)
        suppressTap = false
        return true
    }

    private fun rowAt(y: Float): Int {
        for (i in rowRects.indices) {
            if (y >= rowRects[i].top && y <= rowRects[i].bottom) return i
        }
        return -1
    }

    private fun actionAt(row: Int, x: Float, y: Float): RowAction? {
        val btns = actionRects.getOrNull(row) ?: return null
        for (b in btns.indices) {
            if (btns[b].contains(x, y)) {
                return when (b) {
                    0 -> RowAction.SPLIT
                    1 -> RowAction.FREEFORM
                    2 -> RowAction.LOCK
                    3 -> RowAction.CLOSE
                    else -> null
                }
            }
        }
        return null
    }

    private fun performAction(row: Int, action: RowAction) {
        val task = tasks[row]
        when (action) {
            RowAction.CLOSE -> closeRow(row)
            RowAction.LOCK -> {
                val shouldLock = !lockedSet.contains(task.packageName)
                listener?.onToggleLock(task, shouldLock)
                lockedSet = lockedSet.toMutableSet().apply {
                    if (shouldLock) add(task.packageName) else remove(task.packageName)
                }
                invalidate()
            }
            else -> listener?.onListAction(
                when (action) {
                    RowAction.SPLIT -> TaskPanelAction.SPLIT
                    RowAction.FREEFORM -> TaskPanelAction.FREEFORM
                    else -> TaskPanelAction.LOCK
                },
                task,
                null,
            )
        }
    }

    private fun closeRow(row: Int) {
        val task = tasks[row]
        listener?.onCloseTask(task)
        // 从当前列表移除该行并重新布局（数据源由上层 removeTask 真正清理）。
        tasks = tasks.toMutableList().also { it.removeAt(row) }
        iconBitmaps.removeAt(row)
        snapBitmaps.removeAt(row)
        lockedSet = lockedSet.toMutableSet().apply { remove(task.packageName) }
        requestLayout()
        invalidate()
        if (tasks.isEmpty()) {
            listener?.onCloseAll()
        }
    }

    // ---------- TaskCenterSurface ----------

    override fun playEntry() {
        panelAlpha = 0f
        animateAlpha(0f, 1f, 180L) { panelAlpha = 1f; invalidate() }
    }

    override fun onExternalMove(x: Float, y: Float) { /* 列表内交互自给，外部手势不透传 */ }

    override fun onExternalUp(x: Float, y: Float, cancelled: Boolean) {}

    override fun onExternalCancel() {}

    override fun playDismissal() {
        animateAlpha(panelAlpha, 0f, 160L) {
            panelAlpha = 0f
            listener?.onClosed()
        }
    }

    override fun playExit(exitReason: TaskPanelExit, velocity: Float) {
        playDismissal()
    }

    override fun releaseResources() {
        for (b in iconBitmaps) b?.recycle()
        for (b in snapBitmaps) b?.recycle()
        iconBitmaps.clear()
        snapBitmaps.clear()
    }

    override fun getHandler(): Handler = handler

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        invalidate()
    }

    override fun setBackYieldProgress(progress: Float, fromLeft: Boolean) {
        val screenW = context.resources.displayMetrics.widthPixels
        backYieldOffset = if (fromLeft) -progress * screenW else progress * screenW
        invalidate()
    }

    override fun releaseBackYield() {
        backYieldOffset = 0f
        invalidate()
    }

    // ---------- 工具 ----------

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: () -> Unit) {
        val start = System.currentTimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = (System.currentTimeMillis() - start).toFloat() / duration
                if (t >= 1f) {
                    onEnd()
                    invalidate()
                    return
                }
                panelAlpha = from + (to - from) * t
                invalidate()
                handler.postDelayed(this, 16L)
            }
        }
        handler.post(step)
    }

    private fun precomputeBitmaps() {
        for (task in tasks) {
            iconBitmaps.add(task.icon?.let { drawableToBitmap(it, iconSizePx.toInt()) })
            snapBitmaps.add(task.snapshot?.let { scaleBitmap(it, snapSizePx.toInt()) })
        }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(c)
        return bmp
    }

    private fun scaleBitmap(src: Bitmap, size: Int): Bitmap {
        val target = size.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, target, target, true)
    }

    private fun dp(v: Float): Float {
        return v * context.resources.displayMetrics.density
    }

    /** 行内动作按钮的语义（区别于上报业务层的 [TaskPanelAction]）。 */
    private enum class RowAction { SPLIT, FREEFORM, LOCK, CLOSE }
}
