package com.slideindex.app.overlay.dragdrop

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slideindex.app.R
import com.slideindex.app.overlay.LocalFrostedGlassBackdrop
import com.slideindex.app.settings.DragDropFlowPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 拖放流转目标动作项数据模型。
 */
data class DragDropFlowItem(
    val id: String,
    val title: String,
    val iconBitmap: Bitmap? = null,
    val iconVector: ImageVector? = null,
    val iconPainter: Painter? = null,
    val iconTintColor: Color? = null,
)

/**
 * 全局拖拽流转 Compose 界面总入口。
 */
@Composable
fun DragDropFlowBarLayout(
    visible: Boolean,
    position: DragDropFlowPosition,
    items: List<DragDropFlowItem>,
    dragPosition: Offset?,
    hapticEnabled: Boolean,
    onHoverChanged: (String?) -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onSelectTarget: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var cancelBounds by remember { mutableStateOf<Rect?>(null) }
    var restoreBounds by remember { mutableStateOf<Rect?>(null) }
    var barBounds by remember { mutableStateOf<Rect?>(null) }
    var ringGeometry by remember { mutableStateOf<RingGeometry?>(null) }
    var hoveredId by remember { mutableStateOf<String?>(null) }
    var lastVibratedTarget by remember { mutableStateOf<String?>(null) }

    // 当可见性关闭或 items 列表变更时及时清理幽灵热区
    LaunchedEffect(visible, items) {
        if (!visible) {
            itemBounds.clear()
            cancelBounds = null
            restoreBounds = null
            barBounds = null
            ringGeometry = null
            hoveredId = null
            lastVibratedTarget = null
            onHoverChanged(null)
        } else {
            val validIds = items.map { it.id }.toSet()
            itemBounds.keys.retainAll(validIds)
        }
    }

    // 监听手指拖拽位置并计算当前命中的目标项
    LaunchedEffect(
        dragPosition,
        itemBounds,
        cancelBounds,
        restoreBounds,
        barBounds,
        ringGeometry,
        position,
        items,
    ) {
        val pos = dragPosition
        if (pos == null) {
            hoveredId = null
            lastVibratedTarget = null
            onHoverChanged(null)
            return@LaunchedEffect
        }

        val curBar = barBounds
        val curRing = ringGeometry
        val targetId: String? = when {
            cancelBounds?.contains(pos) == true -> "__cancel__"
            restoreBounds?.contains(pos) == true -> "__restore__"
            position == DragDropFlowPosition.RING && curRing != null -> {
                // 圆盘按角度取最近的目标，但只在环带里算数：环带以内（圆心）和以外都算没瞄准。
                RingFlowGeometry.targetIndexAt(
                    x = pos.x,
                    y = pos.y,
                    centerX = curRing.center.x,
                    centerY = curRing.center.y,
                    count = items.size,
                    innerRadiusPx = curRing.bandInnerPx,
                    outerRadiusPx = curRing.bandOuterPx,
                )?.let { index -> items.getOrNull(index)?.id }
            }
            curBar?.contains(pos) == true -> {
                // 仅在当前 Dock 视口内部查找，且确保项中心未被移出视口
                itemBounds.entries.firstOrNull { (_, rect) ->
                    rect.contains(pos) && curBar.contains(rect.center)
                }?.key
            }
            else -> null
        }

        if (targetId != hoveredId) {
            hoveredId = targetId
            onHoverChanged(targetId)
            if (targetId != null && targetId != lastVibratedTarget && hapticEnabled) {
                lastVibratedTarget = targetId
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (position) {
                DragDropFlowPosition.BOTTOM -> {
                    BottomFlowContent(
                        items = items,
                        hoveredId = hoveredId,
                        dragPosition = dragPosition,
                        itemBounds = itemBounds,
                        barBounds = barBounds,
                        onBarPositioned = { barBounds = it },
                        onCancelPositioned = { cancelBounds = it },
                        onRestorePositioned = { restoreBounds = it },
                    )
                }
                DragDropFlowPosition.LEFT -> {
                    SideFlowContent(
                        isLeft = true,
                        items = items,
                        hoveredId = hoveredId,
                        dragPosition = dragPosition,
                        itemBounds = itemBounds,
                        barBounds = barBounds,
                        onBarPositioned = { barBounds = it },
                        onCancelPositioned = { cancelBounds = it },
                        onRestorePositioned = { restoreBounds = it },
                    )
                }
                DragDropFlowPosition.RIGHT -> {
                    SideFlowContent(
                        isLeft = false,
                        items = items,
                        hoveredId = hoveredId,
                        dragPosition = dragPosition,
                        itemBounds = itemBounds,
                        barBounds = barBounds,
                        onBarPositioned = { barBounds = it },
                        onCancelPositioned = { cancelBounds = it },
                        onRestorePositioned = { restoreBounds = it },
                    )
                }
                DragDropFlowPosition.RING -> {
                    RingFlowContent(
                        items = items,
                        hoveredId = hoveredId,
                        onGeometryChanged = { ringGeometry = it },
                        onCancelPositioned = { cancelBounds = it },
                        onRestorePositioned = { restoreBounds = it },
                    )
                }
            }
        }
    }
}

/**
 * 底部横向流转栏排布
 */
@Composable
private fun BottomFlowContent(
    items: List<DragDropFlowItem>,
    hoveredId: String?,
    dragPosition: Offset?,
    itemBounds: MutableMap<String, Rect>,
    barBounds: Rect?,
    onBarPositioned: (Rect) -> Unit,
    onCancelPositioned: (Rect) -> Unit,
    onRestorePositioned: (Rect) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val edgeZonePx = with(density) { 54.dp.toPx() }

    // 边缘自适应自动滚动：当手指拖动至底栏左端或右端时，持续平滑滚动
    LaunchedEffect(dragPosition, barBounds) {
        val pos = dragPosition ?: return@LaunchedEffect
        val bar = barBounds ?: return@LaunchedEffect
        if (!bar.contains(pos)) return@LaunchedEffect

        val leftEdgeZone = bar.left + edgeZonePx
        val rightEdgeZone = bar.right - edgeZonePx

        when {
            pos.x < leftEdgeZone -> {
                while (isActive) {
                    val factor = ((leftEdgeZone - pos.x) / edgeZonePx).coerceIn(0.2f, 1.5f)
                    listState.scrollBy(-18f * factor)
                    delay(16)
                }
            }
            pos.x > rightEdgeZone -> {
                while (isActive) {
                    val factor = ((pos.x - rightEdgeZone) / edgeZonePx).coerceIn(0.2f, 1.5f)
                    listState.scrollBy(18f * factor)
                    delay(16)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. 中间偏下居中展示【恢复】与【取消】药丸胶囊（安全避让间距 20dp）
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 20.dp),
        ) {
            PillActionCapsule(
                text = stringResource(R.string.drag_drop_flow_restore),
                isHovered = hoveredId == "__restore__",
                onPositioned = onRestorePositioned,
            )
            PillActionCapsule(
                text = stringResource(R.string.drag_drop_flow_cancel),
                isHovered = hoveredId == "__cancel__",
                onPositioned = onCancelPositioned,
            )
        }

        // 2. 底部横向滑动引擎栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .onGloballyPositioned { onBarPositioned(it.boundsInWindow()) },
            contentAlignment = Alignment.Center,
        ) {
            GlassCapsuleContainer(cornerRadius = 24.dp) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items, key = { it.id }) { item ->
                        FlowItemCard(
                            item = item,
                            isHovered = hoveredId == item.id,
                            isVertical = false,
                            onPositioned = { rect -> itemBounds[item.id] = rect },
                            onDisposed = { itemBounds.remove(item.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 侧边纵向流转栏排布（左侧或右侧）
 */
@Composable
private fun SideFlowContent(
    isLeft: Boolean,
    items: List<DragDropFlowItem>,
    hoveredId: String?,
    dragPosition: Offset?,
    itemBounds: MutableMap<String, Rect>,
    barBounds: Rect?,
    onBarPositioned: (Rect) -> Unit,
    onCancelPositioned: (Rect) -> Unit,
    onRestorePositioned: (Rect) -> Unit,
) {
    val listState = rememberLazyListState()

    val density = LocalDensity.current
    val edgeZonePx = with(density) { 54.dp.toPx() }

    // 边缘自适应自动滚动：当手指拖动至侧栏上端或下端时，持续平滑滚动
    LaunchedEffect(dragPosition, barBounds) {
        val pos = dragPosition ?: return@LaunchedEffect
        val bar = barBounds ?: return@LaunchedEffect
        if (!bar.contains(pos)) return@LaunchedEffect

        val topEdgeZone = bar.top + edgeZonePx
        val bottomEdgeZone = bar.bottom - edgeZonePx

        when {
            pos.y < topEdgeZone -> {
                while (isActive) {
                    val factor = ((topEdgeZone - pos.y) / edgeZonePx).coerceIn(0.2f, 1.5f)
                    listState.scrollBy(-18f * factor)
                    delay(16)
                }
            }
            pos.y > bottomEdgeZone -> {
                while (isActive) {
                    val factor = ((pos.y - bottomEdgeZone) / edgeZonePx).coerceIn(0.2f, 1.5f)
                    listState.scrollBy(18f * factor)
                    delay(16)
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(
                    start = if (isLeft) 10.dp else 0.dp,
                    end = if (!isLeft) 10.dp else 0.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 1. 侧边栏主体 Dock
            Box(
                modifier = Modifier
                    .width(78.dp)
                    .fillMaxHeight(0.58f)
                    .onGloballyPositioned { onBarPositioned(it.boundsInWindow()) },
            ) {
                GlassCapsuleContainer(cornerRadius = 24.dp) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            FlowItemCard(
                                item = item,
                                isHovered = hoveredId == item.id,
                                isVertical = true,
                                onPositioned = { rect -> itemBounds[item.id] = rect },
                                onDisposed = { itemBounds.remove(item.id) },
                            )
                        }
                    }
                }
            }

            // 2. 下方【恢复】与【取消】药丸胶囊（同轴严格居中对齐）
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                PillActionCapsule(
                    text = stringResource(R.string.drag_drop_flow_restore),
                    isHovered = hoveredId == "__restore__",
                    onPositioned = onRestorePositioned,
                )
                PillActionCapsule(
                    text = stringResource(R.string.drag_drop_flow_cancel),
                    isHovered = hoveredId == "__cancel__",
                    onPositioned = onCancelPositioned,
                )
            }
        }
    }
}

/**
 * 环形排布对外公布的几何：圆心、图标圈半径、环带内外沿（窗口坐标，像素）。
 *
 * 命中判定只要环带这两条边，不用图标圈半径——图标圈只影响画在哪。
 */
private data class RingGeometry(
    val center: Offset,
    val iconRadiusPx: Float,
    val bandInnerPx: Float,
    val bandOuterPx: Float,
)

/**
 * 环形罗盘排布（Radial / Ring Flow Bar）。
 *
 * 与条状栏最大的区别是命中方式：圆盘按“角度”判定，手指从圆心往外划，落在哪个扇区
 * 就是哪个目标，不用精确压在 48dp 图标上；环带以内、以外都算没瞄准。
 */
@Composable
private fun RingFlowContent(
    items: List<DragDropFlowItem>,
    hoveredId: String?,
    onGeometryChanged: (RingGeometry) -> Unit,
    onCancelPositioned: (Rect) -> Unit,
    onRestorePositioned: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    val hoveredTitle = items.firstOrNull { it.id == hoveredId }?.title

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        // 图标中心所在圆：比窗口短边再收 96dp，环带和图标标签才不会被窗口边缘裁掉。
        val radiusPx = with(density) { ((minOf(maxWidth, maxHeight) - 96.dp) / 2).toPx() }

        // 环带底盘：只给“图标那一圈”上底，中间留空。
        // 真模糊直接画在画布上再按环带裁剪 —— 跨窗模糊是个 Drawable，可以画进任意画布，
        // 这样模糊只出现在环上，圆心能看到下面的内容，也不会再出现整块圆盘。
        val bandOuterPx = radiusPx + with(density) { 44.dp.toPx() }
        val bandInnerPx = radiusPx - with(density) { 40.dp.toPx() }

        LaunchedEffect(rootBounds, radiusPx, bandInnerPx, bandOuterPx) {
            val bounds = rootBounds ?: return@LaunchedEffect
            if (bandOuterPx > bandInnerPx) {
                onGeometryChanged(
                    RingGeometry(
                        center = bounds.center,
                        iconRadiusPx = radiusPx,
                        bandInnerPx = bandInnerPx,
                        bandOuterPx = bandOuterPx,
                    )
                )
            }
        }

        val scheme = MaterialTheme.colorScheme
        val plateShape = RoundedCornerShape(18.dp)
        items.forEachIndexed { index, item ->
            val (dx, dy) = RingFlowGeometry.offsetFor(index, items.size, radiusPx)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) },
                contentAlignment = Alignment.Center,
            ) {
                // 每个目标一块独立底板：模糊只落在这块板子里，圆心保持透明。
                // 用整块圆盘再挖洞会被系统跨窗模糊绕过裁剪，所以走"一格一块"。
                Box(
                    modifier = Modifier
                        .size(width = 66.dp, height = 86.dp)
                        .clip(plateShape)
                        .border(BorderStroke(1.dp, scheme.outline.copy(alpha = 0.16f)), plateShape),
                ) {
                    LocalFrostedGlassBackdrop(
                        modifier = Modifier.matchParentSize(),
                        cornerRadiusPx = with(density) { 18.dp.toPx() },
                        blurRadiusPx = 26,
                        tintColor = scheme.surface.copy(alpha = 0.50f).toArgb(),
                        solidFallbackColor = scheme.surface.toArgb(),
                    )
                }
                FlowItemCard(
                    item = item,
                    isHovered = hoveredId == item.id,
                    isVertical = false,
                    onPositioned = {},
                    onDisposed = {},
                )
            }
        }

        // 圆心：当前瞄准的目标 +【恢复】/【取消】。指在圆心里不算命中任何目标。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .widthIn(min = 96.dp, max = 168.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.46f))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
                        RoundedCornerShape(17.dp),
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hoveredTitle ?: stringResource(R.string.drag_drop_flow_release_to_send),
                    fontSize = 12.sp,
                    fontWeight = if (hoveredTitle != null) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillActionCapsule(
                    text = stringResource(R.string.drag_drop_flow_restore),
                    isHovered = hoveredId == "__restore__",
                    onPositioned = onRestorePositioned,
                )
                PillActionCapsule(
                    text = stringResource(R.string.drag_drop_flow_cancel),
                    isHovered = hoveredId == "__cancel__",
                    onPositioned = onCancelPositioned,
                )
            }
        }
    }
}

/**
 * 【恢复】/【取消】药丸胶囊按钮（真毛玻璃 + 动态荧光高亮）
 */
@Composable
private fun PillActionCapsule(
    text: String,
    isHovered: Boolean,
    onPositioned: (Rect) -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.15f else 1.0f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "pillScale",
    )
    val shape = RoundedCornerShape(20.dp)
    val isDark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val tintColor = when {
        isHovered -> scheme.primary.copy(alpha = 0.82f)
        isDark -> scheme.surface.copy(alpha = 0.42f)
        else -> scheme.surface.copy(alpha = 0.38f)
    }
    val textColor = when {
        isHovered -> Color.White
        else -> scheme.onSurface.copy(alpha = 0.92f)
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .width(82.dp)
            .height(38.dp)
            .onGloballyPositioned { onPositioned(it.boundsInWindow()) }
            .shadow(if (isHovered) 10.dp else 4.dp, shape)
            .clip(shape)
            .border(
                BorderStroke(
                    width = 1.dp,
                    color = if (isHovered) Color.White.copy(alpha = 0.60f) else scheme.outline.copy(alpha = 0.22f),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LocalFrostedGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = with(density) { 20.dp.toPx() },
            blurRadiusPx = 25,
            tintColor = tintColor.toArgb(),
            solidFallbackColor = scheme.surface.toArgb(),
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/**
 * 单个搜索引擎/动作卡片项（精准 44dp 热区绑定 + 桌面原生圆角自适应）
 */
@Composable
private fun FlowItemCard(
    item: DragDropFlowItem,
    isHovered: Boolean,
    isVertical: Boolean,
    onPositioned: (Rect) -> Unit,
    onDisposed: () -> Unit,
) {
    DisposableEffect(item.id) {
        onDispose {
            onDisposed()
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.18f else 1.0f,
        animationSpec = tween(120),
        label = "itemScale",
    )
    val iconShape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .scale(scale)
            .width(56.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 图标圆角容器：精确热区只绑定在此 48dp 区域，杜绝边缘虚假震动
        Box(
            modifier = Modifier
                .size(48.dp)
                .onGloballyPositioned { onPositioned(it.boundsInWindow()) }
                .background(
                    if (isHovered) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    } else {
                        Color.Transparent
                    },
                    shape = iconShape,
                )
                .border(
                    BorderStroke(
                        width = if (isHovered) 2.5.dp else 0.dp,
                        color = if (isHovered) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
                    shape = iconShape,
                )
                .padding(if (isHovered) 2.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                item.iconBitmap != null -> {
                    Image(
                        bitmap = item.iconBitmap.asImageBitmap(),
                        contentDescription = item.title,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(iconShape),
                    )
                }
                item.iconVector != null -> {
                    Icon(
                        imageVector = item.iconVector,
                        contentDescription = item.title,
                        tint = item.iconTintColor ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp),
                    )
                }
                item.iconPainter != null -> {
                    Icon(
                        painter = item.iconPainter,
                        contentDescription = item.title,
                        tint = item.iconTintColor ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }

        // 标签文字
        Text(
            text = item.title,
            fontSize = 11.sp,
            fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Normal,
            color = if (isHovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 柔光毛玻璃背景胶囊容器（高透水润质感）
 */
@Composable
private fun GlassCapsuleContainer(
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val isDark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val containerColor = if (isDark) scheme.surface.copy(alpha = 0.58f) else scheme.surface.copy(alpha = 0.56f)

    val softGlassBorder = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.05f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.85f),
                Color(0xFF8E8E93).copy(alpha = 0.20f),
                Color.Black.copy(alpha = 0.12f),
            )
        )
    }

    Box(
        modifier = Modifier
            .shadow(16.dp, shape = shape)
            .clip(shape)
            .border(BorderStroke(1.dp, softGlassBorder), shape),
    ) {
        LocalFrostedGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = with(LocalDensity.current) { cornerRadius.toPx() },
            blurRadiusPx = 36,
            tintColor = containerColor.toArgb(),
            solidFallbackColor = scheme.surface.toArgb(),
        )
        content()
    }
}
