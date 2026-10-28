package com.slideindex.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.hardware.display.DisplayManager
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.slideindex.app.message.MessageAction
import com.slideindex.app.message.MessageDisplayPlan
import com.slideindex.app.message.MessagePlacementFractions
import com.slideindex.app.message.MessageSettings
import com.slideindex.app.message.NotificationContentGate
import com.slideindex.app.message.NotificationData
import com.slideindex.app.message.SideBubbleHorizontalEdge
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 贴边通知卡：贴在屏幕侧缘的一组卡片，每个会话一张。
 *
 * 纯 View 实现（不使用 Compose），与浮岛（悬浮菜单）侧边样式同一套思路：
 * - **窗口宽度固定为展开宽度**，收起/展开只平移 [WindowManager.LayoutParams.x]
 * - 收起 = 窗口大部分移出屏幕，只露头像那 48dp
 * - 动画只改 x 和整体 alpha，从不改窗口尺寸，因此没有 measure/layout 抖动
 * - 多张卡按最新在前纵向堆叠
 */
object EdgeCardOverlayWindow {
    private const val TAG = "EdgeCardOverlay"
    private const val PREVIEW_KEY = "__edge_card_preview__"

    private const val CARD_HEIGHT_DP = 42f
    /** 每多一行正文，卡片高度增加的高度（与悬浮菜单侧边样式一致）。 */
    private const val LINE_HEIGHT_DP = 16f
    private const val MAX_LINES = 3
    private const val EXPANDED_WIDTH_DP = 126f
    private const val WIDE_WIDTH_DP = 184f
    private const val VISIBLE_COLLAPSED_DP = 42f
    private const val ICON_DP = 40f
    private const val BADGE_DP = 13f
    private const val CARD_GAP_DP = 8f
    private const val TEXT_GAP_DP = 8f

    private const val HORIZONTAL_SWIPE_DP = 24f
    private const val VERTICAL_DISMISS_DP = 48f
    private const val ANIM_MS = 240L
    private const val USER_INTERACTION_GRACE_MS = 1_500L

    /** 同一会话的连续通知合并窗口，参考悬浮菜单的 200ms 防抖。 */
    private const val SHOW_DEBOUNCE_MS = 200L

    /** 与悬浮菜单侧边样式一致：stiffness = 300、damping = 0.75（欠阻尼，收尾有轻微回弹）。 */
    internal const val SPRING_STIFFNESS = 300f
    internal const val SPRING_DAMPING = 0.75f

    /** 直接驱动窗口 x 的弹簧属性。 */
    private val cardXProperty = object : FloatPropertyCompat<EdgeCardEntry>("edgeCardX") {
        override fun getValue(entry: EdgeCardEntry): Float =
            (entry.params?.x ?: 0).toFloat()

        override fun setValue(entry: EdgeCardEntry, value: Float) {
            entry.params?.x = value.roundToInt()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = mutableListOf<EdgeCardEntry>()
    private val pendingShows = mutableMapOf<String, Runnable>()

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    val isShowing: Boolean get() = entries.isNotEmpty()

    fun containsNotification(data: NotificationData): Boolean = entries.any { it.matches(data) }

    fun show(
        context: Context,
        plan: MessageDisplayPlan,
        onAction: (MessageAction) -> Unit,
        onDismiss: () -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, plan, onAction, onDismiss) }
            return
        }
        // 会话级 200ms 防抖：TG 一次刷新会连发多条同一会话的通知，合并成最后一次处理。
        val key = plan.data.key
        pendingShows.remove(key)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingShows.remove(key)
            showNow(context, plan, onAction, onDismiss)
        }
        pendingShows[key] = runnable
        mainHandler.postDelayed(runnable, SHOW_DEBOUNCE_MS)
    }

    private fun showNow(
        context: Context,
        plan: MessageDisplayPlan,
        onAction: (MessageAction) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val hostContext = MessageOverlayHost.resolveHostContext(context) ?: run {
            Log.w(TAG, "overlay host unavailable")
            return
        }
        if (appContext == null || windowManager == null) {
            appContext = hostContext
            windowManager = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            registerDisplayListener(hostContext)
        }
        entries.filter { it.preview }.forEach { removeEntry(it, notifyDismiss = false) }

        val existing = entries.firstOrNull { it.matches(plan.data) && !it.preview }
        if (existing != null) {
            val changed = NotificationContentGate.signature(existing.plan.data) != NotificationContentGate.signature(plan.data)
            val unread = if (changed) {
                maxOf(plan.data.badgeCount, existing.state.unreadCount + 1)
            } else {
                maxOf(plan.data.badgeCount, existing.state.unreadCount)
            }
            existing.plan = plan
            existing.actionCallback = onAction
            existing.dismissCallback = onDismiss
            existing.state = if (changed) {
                existing.state.onNewMessage(unread, autoExpand = !existing.interacting)
            } else {
                existing.state.withUnread(unread)
            }
            if (changed) moveToTop(existing)
            // 同会话来新消息：窗口直接瞬移到展开位，再播一次“从屏外滑入”的入场动画。
            applyEntry(existing, animated = false)
            if (changed) {
                existing.view?.playEntrance(1f)
                scheduleAutoCollapse(existing)
            }
            return
        }

        val entry = EdgeCardEntry(key = plan.data.key)
        entry.plan = plan
        entry.state = EdgeCardUiState(
            phase = EdgeCardPhase.Expanded,
            unreadCount = plan.data.badgeCount.coerceAtLeast(1),
        )
        entry.actionCallback = onAction
        entry.dismissCallback = onDismiss
        entries.add(0, entry)
        trimToCount(plan.settings)
        ensureWindow(entry, hostContext)
        relayout()
        applyEntry(entry, animated = false)
        entry.view?.playEntrance(1f)
        scheduleAutoCollapse(entry)
    }

    /** 设置页预览：示例数据常驻，不自动收起。 */
    fun installPreview(context: Context, settings: MessageSettings) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { installPreview(context, settings) }
            return
        }
        val hostContext = MessageOverlayHost.resolveHostContext(context) ?: return
        if (appContext == null || windowManager == null) {
            appContext = hostContext
            windowManager = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            registerDisplayListener(hostContext)
        }
        dismissAllInternal(notifyDismiss = false)

        val appCtx = context.applicationContext
        val appLabel = runCatching {
            appCtx.packageManager.getApplicationLabel(
                appCtx.packageManager.getApplicationInfo(appCtx.packageName, 0),
            ).toString()
        }.getOrDefault(appCtx.packageName)
        val previewData = NotificationData(
            packageName = appCtx.packageName,
            key = PREVIEW_KEY,
            title = appLabel,
            content = context.getString(com.slideindex.app.R.string.message_preview_content),
            largeIcon = null,
            appIcon = null,
            contentIntent = null,
            postTime = -1L,
            badgeCount = 3,
        )
        val previewSettings = settings.copy(edgeCardAutoCollapseMs = 0)
        val previewPlan = MessageDisplayPlan(
            data = previewData,
            showFloatIcon = false,
            showSideBubble = false,
            showDanmaku = false,
            showCNotice = false,
            sideTheme = null,
            danmakuTheme = null,
            settings = previewSettings,
            showEdgeCard = true,
        )
        val entry = EdgeCardEntry(key = PREVIEW_KEY, preview = true)
        entry.plan = previewPlan
        entry.state = EdgeCardUiState(phase = EdgeCardPhase.Expanded, unreadCount = 3)
        entry.actionCallback = {}
        entry.dismissCallback = {}
        entries.add(entry)
        ensureWindow(entry, hostContext)
        relayout()
        applyEntry(entry, animated = false)
    }

    fun updatePreviewPlan(plan: MessageDisplayPlan) {
        entries.firstOrNull { it.preview }?.let {
            it.plan = plan
            bindView(it)
        }
    }

    fun dismissPreview() {
        entries.filter { it.preview }.forEach { removeEntry(it, notifyDismiss = false) }
    }

    fun updateWindowPlacement(context: Context, settings: MessageSettings) {
        entries.forEach { it.plan = it.plan.copy(settings = settings) }
        val hostContext = MessageOverlayHost.resolveHostContext(context) ?: return
        appContext = hostContext
        windowManager = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        entries.forEach { entry ->
            val view = entry.view ?: return@forEach
            val params = entry.params ?: return@forEach
            applyHorizontalGravity(settings, params)
            view.exclusionEdge = settings.edgeCardHorizontalEdge
            applyEntry(entry, animated = false)
        }
        relayout()
    }

    fun dismissEntry(key: String, postTime: Long) {
        entries.filter { entry ->
            val data = entry.plan.data
            data.key == key && (postTime < 0 || data.postTime == postTime)
        }.forEach { removeEntry(it, notifyDismiss = false) }
    }

    fun dismissEntriesForKey(key: String) {
        entries.filter { it.plan.data.key == key }.forEach { removeEntry(it, notifyDismiss = false) }
    }

    fun dismissSameSource(sourceKey: String) {
        entries.filter { it.plan.data.conversationSourceKey == sourceKey }
            .forEach { removeEntry(it, notifyDismiss = false) }
    }

    fun dismiss() = dismissAllInternal(notifyDismiss = false)

    fun dismissImmediate() = dismiss()

    /** 正文行数（1..3）。 */
    internal fun lineCount(settings: MessageSettings): Int =
        settings.edgeCardMaxLines.coerceIn(1, MAX_LINES)

    /** 卡片高度随行数增长：42dp 起步，每多一行 +16dp。 */
    internal fun cardHeightDp(settings: MessageSettings): Float =
        CARD_HEIGHT_DP + (lineCount(settings) - 1) * LINE_HEIGHT_DP

    fun snapshotDisplayedKeys(): Set<String> =
        entries.map { "${it.plan.data.key}|${it.plan.data.postTime}" }.toSet()

    fun snapshotDisplayedKeysForSource(sourceKey: String): Set<String> =
        entries.filter { it.plan.data.conversationSourceKey == sourceKey }
            .map { "${it.plan.data.key}|${it.plan.data.postTime}" }
            .toSet()

    fun pauseAutoDismiss(key: String, postTime: Long) {
        entries.filter {
            val data = it.plan.data
            data.key == key && (postTime < 0 || data.postTime == postTime)
        }.forEach { cancelAutoCollapse(it) }
    }

    fun resumeAutoDismiss(key: String, postTime: Long) {
        entries.filter {
            val data = it.plan.data
            data.key == key && (postTime < 0 || data.postTime == postTime)
        }.forEach { scheduleAutoCollapse(it) }
    }

    // ---- 布局与动画 ----

    private fun density(): Float = appContext?.resources?.displayMetrics?.density ?: 1f

    private fun dp(value: Float): Int = (value * density()).roundToInt()

    private fun expandedWidthDp(state: EdgeCardUiState): Float =
        if (state.unreadCount > 1) WIDE_WIDTH_DP else EXPANDED_WIDTH_DP

    private fun screenWidth(): Int = appContext?.resources?.displayMetrics?.widthPixels ?: 0

    /**
     * 窗口统一用 TOP|START，`params.x` 就是窗口左边缘的屏幕坐标（允许负值、可超出右侧）。
     * 这样左右贴边的位置计算只需一套绝对坐标，避免 END gravity 下 x 含义反转。
     */
    private fun expandedX(entry: EdgeCardEntry): Int {
        val width = entry.view?.width?.takeIf { it > 0 } ?: entry.windowWidthPx
        return when (entry.plan.settings.edgeCardHorizontalEdge) {
            SideBubbleHorizontalEdge.Right -> screenWidth() - width
            SideBubbleHorizontalEdge.Left -> 0
        }
    }

    /** 收起位置：窗口大部分移出屏幕，只露头像那一截。 */
    private fun collapsedX(entry: EdgeCardEntry): Int {
        val width = entry.view?.width?.takeIf { it > 0 } ?: entry.windowWidthPx
        return when (entry.plan.settings.edgeCardHorizontalEdge) {
            SideBubbleHorizontalEdge.Right -> screenWidth() - dp(VISIBLE_COLLAPSED_DP)
            SideBubbleHorizontalEdge.Left -> dp(VISIBLE_COLLAPSED_DP) - width
        }
    }

    private fun applyEntry(entry: EdgeCardEntry, animated: Boolean) {
        val view = entry.view ?: return
        val params = entry.params ?: return
        bindView(entry)
        // 内容刚换，View 的宽度要等下一帧布局才更新；同帧算 x 会用到旧宽度，
        // 表现为卡片右端离屏幕边缘差一截（内容变短时）或被推出屏幕（变长时）。
        view.post {
            if (entry.view !== view) return@post
            val expanded = entry.state.isExpanded
            val targetX = if (expanded) expandedX(entry) else collapsedX(entry)
            val targetAlpha = if (expanded) 1f else entry.plan.settings.edgeCardOpacity.coerceIn(0f, 1f)
            animateTo(entry, view, params, targetX, targetAlpha, animated)
        }
    }

    /** 旋转等屏幕变化后，x/y 都要按新的屏幕尺寸重算，否则卡片会停在旧坐标上。 */
    private fun registerDisplayListener(context: Context) {
        if (displayListener != null) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post { onDisplayChanged() }
                    return
                }
                onDisplayChanged()
            }

            private fun onDisplayChanged() {
                if (entries.isEmpty()) return
                relayout()
                entries.toList().forEach { entry -> applyEntry(entry, animated = false) }
            }
        }
        manager.registerDisplayListener(listener, mainHandler)
        displayListener = listener
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        runCatching {
            (appContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.unregisterDisplayListener(listener)
        }
        displayListener = null
    }

    private fun animateTo(
        entry: EdgeCardEntry,
        view: EdgeCardView,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetAlpha: Float,
        animated: Boolean,
    ) {
        entry.spring?.cancel()
        entry.spring = null
        val startAlpha = view.alpha
        if (!animated || (params.x == targetX && abs(startAlpha - targetAlpha) < 0.01f)) {
            params.x = targetX
            view.alpha = targetAlpha
            view.marqueeActive = entry.state.isExpanded
            if (view.isAttachedToWindow) {
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
            return
        }
        view.marqueeActive = false
        val from = collapsedX(entry)
        val to = expandedX(entry)
        val span = (to - from).toFloat()
        val force = SpringForce(targetX.toFloat()).apply {
            stiffness = SPRING_STIFFNESS
            dampingRatio = SPRING_DAMPING
        }
        val spring = SpringAnimation(entry, cardXProperty).apply {
            // 注意：SpringAnimation(object, property) 这个构造器不会初始化 spring force，
            // 必须显式 setSpring，否则 spring 为 null，设置 stiffness 时直接 NPE。
            setSpring(force)
            setMinimumVisibleChange(1f)
            addUpdateListener { _, _, _ ->
                // alpha 跟 x 的进度走，和浮岛侧边样式一致，不单独做一条动画曲线。
                val params = entry.params ?: return@addUpdateListener
                val x = params.x.toFloat()
                val p = if (span == 0f) 1f else ((x - from) / span).coerceIn(0f, 1f)
                view.alpha = entry.plan.settings.edgeCardOpacity.coerceIn(0f, 1f) * (1f - p) + p
                if (view.isAttachedToWindow) {
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
            }
            addEndListener { _, _, _, _ ->
                entry.spring = null
                view.marqueeActive = entry.state.isExpanded
            }
        }
        entry.spring = spring
        spring.animateToFinalPosition(targetX.toFloat())
        view.alpha = startAlpha
    }

    private fun bindView(entry: EdgeCardEntry) {
        entry.view?.bind(entry.plan, entry.state)
    }

    private fun applyHorizontalGravity(
        @Suppress("UNUSED_PARAMETER") settings: MessageSettings,
        params: WindowManager.LayoutParams,
    ) {
        // 位置全部由 x 的绝对坐标决定，gravity 只固定到左上，左右贴边在 x 里算。
        params.gravity = Gravity.TOP or Gravity.START
    }

    private fun ensureWindow(entry: EdgeCardEntry, hostContext: Context) {
        val settings = entry.plan.settings
        val view = EdgeCardView(hostContext).apply {
            exclusionEdge = settings.edgeCardHorizontalEdge
            setOnTouchListener(EdgeCardTouchListener(entry))
        }
        // 先绑内容再测量：宽高都由内容决定（正文行数多则卡片更高）。
        view.bind(entry.plan, entry.state)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(
                dp(120f) + dp(52f),
                View.MeasureSpec.AT_MOST,
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val widthPx = view.measuredWidth.coerceAtLeast(dp(1f))
        val measuredHeight = view.measuredHeight.coerceAtLeast(dp(CARD_HEIGHT_DP))
        view.layout(0, 0, widthPx, measuredHeight)

        entry.view = view
        entry.windowWidthPx = widthPx
        entry.heightPx = measuredHeight
        entry.onHeightChanged = { newHeight ->
            if (entry.heightPx != newHeight) {
                entry.heightPx = newHeight
                relayout()
            }
        }
        entry.onWidthChanged = { newWidth ->
            entry.windowWidthPx = newWidth
            val params = entry.params
            if (params != null) {
                val target = if (entry.state.isExpanded) expandedX(entry) else collapsedX(entry)
                val running = entry.spring
                if (running != null) {
                    running.animateToFinalPosition(target.toFloat())
                } else {
                    params.x = target
                    if (view.isAttachedToWindow) {
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                }
            }
        }
        entry.y = computeTops(settings, entries.size)
            .getOrElse(entries.indexOf(entry).coerceAtLeast(0)) { 0 }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.overlayWindowType(hostContext),WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            applyHorizontalGravity(settings, this)
            x = collapsedX(entry)
            y = entry.y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }
        entry.params = params
        view.alpha = settings.edgeCardOpacity.coerceIn(0f, 1f)

        val wm = windowManager ?: return
        val added = runCatching { wm.addView(view, params) }
            .onFailure { Log.e(TAG, "addView failed", it) }
            .isSuccess
        if (!added) {
            entry.view = null
            entry.params = null
            return
        }
        view.post { relayout() }
    }

    private fun computeTops(settings: MessageSettings, count: Int): List<Int> {
        val ctx = appContext ?: return emptyList()
        if (count <= 0) return emptyList()
        val metrics = ctx.resources.displayMetrics
        val gapPx = dp(CARD_GAP_DP)
        // 每张卡行数可能不同，高度各自算。
        val heights = entries.map {
            it.heightPx.takeIf { h -> h > 0 } ?: dp(CARD_HEIGHT_DP)
        }
        val total = heights.sum() + gapPx * (count - 1).coerceAtLeast(0)
        val anchor = (MessagePlacementFractions.coerceY(settings.edgeCardYFraction) *
            metrics.heightPixels).roundToInt()
        val top = (anchor - total / 2).coerceIn(0, (metrics.heightPixels - total).coerceAtLeast(0))
        var cursor = top
        return heights.map { height ->
            val y = cursor
            cursor += height + gapPx
            y
        }
    }

    private fun relayout() {
        val wm = windowManager ?: return
        if (entries.isEmpty()) return
        val settings = entries.firstOrNull()?.plan?.settings ?: return
        val tops = computeTops(settings, entries.size)
        entries.forEachIndexed { index, entry ->
            entry.y = tops.getOrElse(index) { entry.y }
            val view = entry.view ?: return@forEachIndexed
            val params = entry.params ?: return@forEachIndexed
            if (!view.isAttachedToWindow) return@forEachIndexed
            params.y = entry.y
            runCatching { wm.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
        }
    }

    private fun moveToTop(entry: EdgeCardEntry) {
        if (entries.remove(entry)) entries.add(0, entry)
    }

    private fun trimToCount(settings: MessageSettings) {
        val max = settings.edgeCardMaxCount.coerceIn(1, 5)
        while (entries.count { !it.preview } > max) {
            val newest = entries.firstOrNull { !it.preview } ?: break
            val group = newest.plan.data.packageName
            val pool = entries.filter { !it.preview && it !== newest }
            // 同分组优先淘汰最旧的：避免两个群互挤，导致还有未读的会话被顶掉。
            val victim = pool.filter { it.plan.data.packageName == group }
                .minByOrNull { it.createdAt }
                ?: pool.minByOrNull { it.createdAt }
                ?: break
            removeEntry(victim, notifyDismiss = false)
        }
    }

    private fun removeEntry(entry: EdgeCardEntry, notifyDismiss: Boolean) {
        cancelAutoCollapse(entry)
        entry.spring?.cancel()
        entry.spring = null
        entry.view?.cancelEntrance()
        entries.remove(entry)
        val view = entry.view
        val callback = entry.dismissCallback
        entry.view = null
        entry.params = null
        entry.dismissCallback = null
        entry.actionCallback = null
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        if (notifyDismiss) callback?.invoke()
        relayout()
    }

    private fun dismissAllInternal(notifyDismiss: Boolean) {
        unregisterDisplayListener()
        pendingShows.values.forEach { mainHandler.removeCallbacks(it) }
        pendingShows.clear()
        entries.toList().forEach { removeEntry(it, notifyDismiss) }
        appContext = null
        windowManager = null
    }

    // ---- 手势 ----

    private fun onGesture(entry: EdgeCardEntry, totalX: Float, totalY: Float, swipe: EdgeCardSwipe) {
        val settings = entry.plan.settings
        val expanded = entry.state.isExpanded
        when (swipe) {
            EdgeCardSwipe.None -> {
                if (!expanded) {
                    entry.state = entry.state.onTap()
                    scheduleAutoCollapse(entry)
                    applyEntry(entry, animated = true)
                } else {
                    val action = settings.singleTapAction
                    if (action == MessageAction.Ignore) {
                        openConversation(entry)
                    } else {
                        dispatch(entry, action)
                    }
                }
            }
            EdgeCardSwipe.Expand -> {
                if (!expanded) {
                    entry.state = entry.state.onSwipeLeft()
                    scheduleAutoCollapse(entry)
                    applyEntry(entry, animated = true)
                } else {
                    dispatchOrKeep(entry, settings.swipeLeftAction)
                }
            }
            EdgeCardSwipe.Collapse -> {
                if (expanded) {
                    cancelAutoCollapse(entry)
                    entry.state = entry.state.onSwipeRight()
                    applyEntry(entry, animated = true)
                } else {
                    dispatchOrKeep(entry, settings.swipeRightAction)
                }
            }
            EdgeCardSwipe.Dismiss -> {
                if (!expanded) {
                    mainHandler.post { removeEntry(entry, notifyDismiss = true) }
                } else {
                    val action = if (totalY < 0f) settings.swipeUpAction else settings.swipeDownAction
                    if (action == MessageAction.Ignore) {
                        mainHandler.post { removeEntry(entry, notifyDismiss = true) }
                    } else {
                        dispatch(entry, action)
                    }
                }
            }
        }
    }

    /** 拖动过程中直接跟手改窗口 x，不触发任何测量或布局。 */
    private fun dragTo(entry: EdgeCardEntry, dx: Float) {
        val view = entry.view ?: return
        val params = entry.params ?: return
        entry.spring?.cancel()
        entry.spring = null
        val from = collapsedX(entry)
        val to = expandedX(entry)
        val baseX = if (entry.state.isExpanded) to else from
        params.x = (baseX + dx).roundToInt().coerceIn(minOf(from, to), maxOf(from, to))
        val span = (to - from).toFloat()
        val p = if (span == 0f) 1f else ((params.x - from) / span).coerceIn(0f, 1f)
        val collapsedAlpha = entry.plan.settings.edgeCardOpacity.coerceIn(0f, 1f)
        view.alpha = collapsedAlpha + (1f - collapsedAlpha) * p
        view.marqueeActive = false
        if (view.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun openConversation(entry: EdgeCardEntry) {
        val action = entry.actionCallback
        mainHandler.post { removeEntry(entry, notifyDismiss = false) }
        action?.invoke(MessageAction.Read)
    }

    private fun dispatchOrKeep(entry: EdgeCardEntry, action: MessageAction) {
        if (action == MessageAction.Ignore) return
        dispatch(entry, action)
    }

    private fun dispatch(entry: EdgeCardEntry, action: MessageAction) {
        val callback = entry.actionCallback
        mainHandler.post { removeEntry(entry, notifyDismiss = false) }
        callback?.invoke(action)
    }

    // ---- 计时 ----

    private fun scheduleAutoCollapse(entry: EdgeCardEntry) {
        cancelAutoCollapse(entry)
        val delayMs = entry.plan.settings.edgeCardAutoCollapseMs.coerceIn(0, 30_000)
        if (delayMs <= 0 || !entry.state.isExpanded) return
        val runnable = object : Runnable {
            override fun run() {
                entry.autoCollapse = null
                if (entry.interacting) {
                    mainHandler.postDelayed(this, USER_INTERACTION_GRACE_MS)
                    return
                }
                entry.state = entry.state.collapse()
                applyEntry(entry, animated = true)
            }
        }
        entry.autoCollapse = runnable
        mainHandler.postDelayed(runnable, delayMs.toLong())
    }

    private fun cancelAutoCollapse(entry: EdgeCardEntry) {
        entry.autoCollapse?.let { mainHandler.removeCallbacks(it) }
        entry.autoCollapse = null
    }

    // ---- 触摸 ----

    private class EdgeCardTouchListener(
        private val entry: EdgeCardEntry,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var totalX = 0f
        private var totalY = 0f
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    totalX = 0f
                    totalY = 0f
                    dragging = false
                    entry.interacting = true
                    EdgeCardOverlayWindow.cancelAutoCollapse(entry)
                    entry.spring?.cancel()
                    entry.spring = null
                }
                MotionEvent.ACTION_MOVE -> {
                    totalX = event.rawX - downX
                    totalY = event.rawY - downY
                    if (abs(totalX) > abs(totalY) &&
                        abs(totalX) > EdgeCardOverlayWindow.dp(4f)
                    ) {
                        dragging = true
                        EdgeCardOverlayWindow.dragTo(entry, totalX)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    entry.interacting = false
                    val swipe = EdgeCardUiState.resolveSwipe(
                        totalX = totalX,
                        totalY = totalY,
                        horizontalThresholdPx =
                            EdgeCardOverlayWindow.dp(EdgeCardOverlayWindow.HORIZONTAL_SWIPE_DP).toFloat(),
                        verticalThresholdPx =
                            EdgeCardOverlayWindow.dp(EdgeCardOverlayWindow.VERTICAL_DISMISS_DP).toFloat(),
                    )
                    if (!dragging) {
                        EdgeCardOverlayWindow.onGesture(entry, totalX, totalY, EdgeCardSwipe.None)
                    } else {
                        EdgeCardOverlayWindow.onGesture(entry, totalX, totalY, swipe)
                    }
                    if (entry.view != null && entry.state.isExpanded) {
                        EdgeCardOverlayWindow.scheduleAutoCollapse(entry)
                    }
                }
            }
            return true
        }
    }
}

private class EdgeCardEntry(
    val key: String,
    val preview: Boolean = false,
) {
    var plan: MessageDisplayPlan = placeholderPlan()
    var state: EdgeCardUiState = EdgeCardUiState()
    var view: EdgeCardView? = null
    var params: WindowManager.LayoutParams? = null
    var spring: SpringAnimation? = null
    var autoCollapse: Runnable? = null
    var actionCallback: ((MessageAction) -> Unit)? = null
    var dismissCallback: (() -> Unit)? = null
    var interacting: Boolean = false
    var y: Int = 0
    var windowWidthPx: Int = 0
    /** 卡片实际高度（随正文行数变化），用于多卡堆叠定位。 */
    var heightPx: Int = 0
    var onHeightChanged: ((Int) -> Unit)? = null
    var onWidthChanged: ((Int) -> Unit)? = null
    val createdAt: Long = android.os.SystemClock.uptimeMillis()

    fun matches(data: NotificationData): Boolean {
        if (plan.data.key == data.key) return true
        val a = NotificationData.conversationIdentityKey(plan.data)
        val b = NotificationData.conversationIdentityKey(data)
        return a.isNotBlank() && a == b
    }
}

private fun placeholderPlan(): MessageDisplayPlan = MessageDisplayPlan(
    data = NotificationData(
        packageName = "",
        key = "",
        title = "",
        content = "",
        largeIcon = null,
        appIcon = null,
        contentIntent = null,
        postTime = 0L,
    ),
    showFloatIcon = false,
    showSideBubble = false,
    showDanmaku = false,
    showCNotice = false,
    sideTheme = null,
    danmakuTheme = null,
    settings = MessageSettings(),
)

/**
 * 单张卡片的 View：固定展开宽度，头像 + 两行文字 + 角标。
 * 收起时整窗移出屏幕，内容本身不做任何尺寸或透明度动画。
 */
private class EdgeCardView(context: Context) : ViewGroup(context) {
    var exclusionEdge: SideBubbleHorizontalEdge = SideBubbleHorizontalEdge.Right
        set(value) {
            field = value
            updateGestureExclusion()
        }

    /** 跑马灯仅在展开态启用，收起立即停止，避免常驻滚动耗电。 */
    var marqueeActive: Boolean
        get() = contentView.isSelected
        set(value) {
            if (contentView.isSelected == value) return
            contentView.isSelected = value
        }

    private var entranceSpring: SpringAnimation? = null

    /**
     * 入场动画：整卡从屏幕外侧滑入、由透明变实，图标顺带转一圈。
     * 只改 View 的 translationX/alpha，不动窗口位置，所以不会触发任何 resize。
     */
    fun playEntrance(targetAlpha: Float) {
        val span = width.toFloat().coerceAtLeast(1f)
        entranceSpring?.cancel()
        entranceSpring = null
        translationX = if (exclusionEdge == SideBubbleHorizontalEdge.Right) span else -span
        alpha = 0f
        iconView.rotation = 0f
        val spring = SpringAnimation(this, DynamicAnimation.TRANSLATION_X).apply {
            setSpring(
                SpringForce(0f).apply {
                    stiffness = EdgeCardOverlayWindow.SPRING_STIFFNESS
                    dampingRatio = EdgeCardOverlayWindow.SPRING_DAMPING
                },
            )
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ ->
                val p = 1f - (abs(value) / span).coerceIn(0f, 1f)
                alpha = targetAlpha * p
                iconView.rotation = p * 360f
            }
            addEndListener { _, _, _, _ ->
                entranceSpring = null
                translationX = 0f
                alpha = targetAlpha
                iconView.rotation = 0f
            }
        }
        entranceSpring = spring
        spring.animateToFinalPosition(0f)
    }

    fun cancelEntrance() {
        entranceSpring?.cancel()
        entranceSpring = null
    }

    private val iconView = ImageView(context)
    /** 连 App 图标都取不到时的占位：首字圆形底。 */
    private val fallbackView = TextView(context)
    private val badgeView = TextView(context)
    private val titleView = TextView(context)
    private val contentView = TextView(context)
    /** 38dp 头像容器：图标 30dp 缩进 4dp，角标也挂在这一层，正好压在圆形图标左上角。 */
    private val avatarContainer = FrameLayout(context)
    private val textLayer = LinearLayout(context)
    private val cardLayer = FrameLayout(context)


    init {
        clipChildren = false
        clipToPadding = false

        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.clipToOutline = true
        iconView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        badgeView.gravity = Gravity.CENTER
        badgeView.setTextColor(Color.WHITE)
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        badgeView.includeFontPadding = false
        badgeView.setBackgroundDrawable(circleDrawable(Color.parseColor("#E74749")))
        fallbackView.gravity = Gravity.CENTER
        fallbackView.setTextColor(Color.WHITE)
        fallbackView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        fallbackView.includeFontPadding = false
        fallbackView.setBackgroundDrawable(circleDrawable(Color.parseColor("#9AA0A6")))
        fallbackView.visibility = View.GONE
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setTextColor(Color.parseColor("#2B2B2B"))
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        titleView.includeFontPadding = false
        contentView.maxLines = 1
        contentView.isSingleLine = true
        contentView.ellipsize = TextUtils.TruncateAt.MARQUEE
        contentView.marqueeRepeatLimit = -1
        contentView.setTextColor(Color.parseColor("#6B6B6B"))
        contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        contentView.includeFontPadding = false

        textLayer.orientation = LinearLayout.VERTICAL
        textLayer.gravity = Gravity.CENTER_VERTICAL
        textLayer.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        textLayer.addView(
            contentView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
        )
        avatarContainer.clipChildren = false
        avatarContainer.clipToPadding = false
        avatarContainer.addView(
            iconView,
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER).apply {
                leftMargin = dp(4)
                topMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(4)
            },
        )
        avatarContainer.addView(
            fallbackView,
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER).apply {
                leftMargin = dp(4)
                topMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(4)
            },
        )
        avatarContainer.addView(
            badgeView,
            FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(4)
                topMargin = dp(4)
            },
        )
        cardLayer.addView(
            avatarContainer,
            FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(2)
            },
        )
        cardLayer.addView(
            textLayer,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(44)
                rightMargin = dp(8)
            },
        )
        addView(cardLayer, LayoutParams(-1, -1))
    }

    /**
     * 卡片皮肤：复用侧边气泡主题的左右背景 drawable（形状与贴边方向一致），
     * 文字色也取自主题；主题没有侧边资源时退回白底 + 1px 描边。
     */
    /** 固定外观：半圆 + 矩形，白底 + 1 物理像素描边。圆角按实际高度算，多行时仍是半圆。 */
    private fun applyCardBackground(right: Boolean, heightPx: Int) {
        val corner = (heightPx.coerceAtLeast(dp(40)) / 2).toFloat()
        cardLayer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            setStroke(1, Color.parseColor("#A1A2A3"))
            cornerRadii = if (right) {
                floatArrayOf(corner, corner, 0f, 0f, 0f, 0f, corner, corner)
            } else {
                floatArrayOf(0f, 0f, corner, corner, corner, corner, 0f, 0f)
            }
        }
    }

    fun bind(plan: MessageDisplayPlan, state: EdgeCardUiState) {
        val data = plan.data
        val settings = plan.settings
        val title = NotificationData.overlayHeaderTitle(data)
        // 三级回落：会话/通知大图 → App 图标 → 首字占位，避免 Threads 这类无大图通知出现白板。
        val icon = data.conversationIcon ?: data.largeIcon ?: data.appIcon
        if (icon != null) {
            iconView.setImageBitmap(icon)
            iconView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
        } else {
            iconView.setImageBitmap(null)
            iconView.visibility = View.GONE
            fallbackView.text = title.trim().firstOrNull()?.toString().orEmpty()
            fallbackView.visibility = View.VISIBLE
        }
        titleView.text = title
        contentView.text = data.content
        val badge = state.badgeText()
        if (badge != null) {
            badgeView.text = badge
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }

        val right = settings.edgeCardHorizontalEdge == SideBubbleHorizontalEdge.Right
        alignedRight = right
        applyCardBackground(right, if (height > 0) height else dp(42))

        // 行数上限：完全照悬浮菜单侧边样式——
        // 单行：singleLine + 省略号；多行：不限行、不省略，靠 onMeasure 里钉死的高度裁切。
        maxLines = EdgeCardOverlayWindow.lineCount(settings)
        contentView.isSingleLine = maxLines == 1
        if (maxLines == 1) {
            contentView.maxLines = 1
            contentView.ellipsize = TextUtils.TruncateAt.MARQUEE
        } else {
            contentView.maxLines = Int.MAX_VALUE
            contentView.ellipsize = null
        }

        val avatarParams = avatarContainer.layoutParams as FrameLayout.LayoutParams
        avatarParams.gravity = (if (right) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        avatarParams.leftMargin = if (right) dp(2) else 0
        avatarParams.rightMargin = if (right) 0 else dp(2)
        avatarContainer.layoutParams = avatarParams

        val textParams = textLayer.layoutParams as FrameLayout.LayoutParams
        textParams.gravity = (if (right) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        textParams.leftMargin = if (right) dp(44) else dp(8)
        textParams.rightMargin = if (right) dp(8) else dp(44)
        textLayer.layoutParams = textParams

        val badgeParams = badgeView.layoutParams as FrameLayout.LayoutParams
        badgeParams.gravity = (if (right) Gravity.START else Gravity.END) or Gravity.TOP
        badgeView.layoutParams = badgeParams

        // 卡片背景铺满整个窗口，贴边侧才能顶到屏幕边缘。
        setPadding(0, 0, 0, 0)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度按内容算：图标区固定，文字区取标题/正文里较宽的那个，限制在 40..120dp。
        val maxText = dp(120)
        val minText = dp(40)
        val textW = maxOf(
            measureTextWidth(titleView, maxText),
            measureTextWidth(contentView, maxText),
        ).coerceIn(minText, maxText)
        val totalW = dp(48) + textW + dp(8)
        // 始终用内容算出的宽度：窗口传 EXACTLY（上一帧宽度）时也不能锁死，
        // 否则文字变短后卡片缩不回去，右侧会留一片空白。
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            minOf(totalW, MeasureSpec.getSize(widthMeasureSpec))
        } else {
            totalW
        }
        // 照悬浮菜单的做法：先把正文按最终宽度排一遍拿到行数（maxLines 已生效），
        // 再把正文高度钉死成 lineHeight × 行数（行高不低于 16dp）。
        // 高度钉死之后，多出来的行会被裁在固定高度里，不会露第三行，也不会裁到字。
        val exactText = MeasureSpec.makeMeasureSpec(textW, MeasureSpec.EXACTLY)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        titleView.measure(exactText, unbounded)
        contentView.measure(exactText, unbounded)
        val lines = (contentView.layout?.lineCount ?: 1).coerceIn(1, maxLines)
        val lineHeight = maxOf(contentView.lineHeight, dp(16))
        val contentHeight = lines * lineHeight
        (contentView.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (it.height != contentHeight) {
                it.height = contentHeight
                contentView.layoutParams = it
            }
        }
        val textHeight = titleView.measuredHeight + dp(2) + contentHeight
        val cardHeight = maxOf(textHeight + dp(8), dp(42))
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            minOf(cardHeight, MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: cardHeight)
        } else {
            cardHeight
        }
        setMeasuredDimension(w, h)
        cardLayer.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }

    private fun measureTextWidth(view: TextView, maxWidth: Int): Int {
        view.measure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return view.measuredWidth
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cardLayer.layout(0, 0, r - l, b - t)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
        if (w != oldw) onWidthChanged?.invoke(w)
        if (h != oldh && h > 0) {
            // 高度随正文行数变化：圆角要重算，外层也要重排堆叠位置。
            applyCardBackground(alignedRight, h)
            onHeightChanged?.invoke(h)
        }
    }

    /** 卡片内容宽度变化（文字长短变了）时通知外层重算贴边位置。 */
    var onWidthChanged: ((Int) -> Unit)? = null

    /** 卡片高度变化（正文行数变了）时通知外层重排。 */
    var onHeightChanged: ((Int) -> Unit)? = null

    private var alignedRight = true
    private var maxLines = 1

    private fun updateGestureExclusion() {
        if (width <= 0 || height <= 0) return
        val band = dp(24).coerceAtMost(width)
        if (band <= 0) return
        val rect = if (exclusionEdge == SideBubbleHorizontalEdge.Right) {
            Rect(width - band, 0, width, height)
        } else {
            Rect(0, 0, band, height)
        }
        ViewCompat.setSystemGestureExclusionRects(this, listOf(rect))
    }

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}
