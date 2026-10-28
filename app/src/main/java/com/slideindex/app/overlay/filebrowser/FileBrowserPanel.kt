package com.slideindex.app.overlay.filebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.overlay.LocalFrostedGlassBackdrop
import com.slideindex.app.ui.miuix.MiuixSearchField
import com.slideindex.app.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbBarDefaults
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文件浏览器面板内容（浮窗内）。
 *
 * 纯 UI：目录读取/权限判断全部在 [FileBrowserController] 里；这里只负责列表、面包屑、
 * 搜索与详情，并通过回调把“授权/关闭”交给宿主 [FileBrowserOverlayWindow]。
 */
@Composable
internal fun FileBrowserPanel(
    visible: Boolean,
    controller: FileBrowserController,
    onDismiss: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val state = controller.state
    val internalRoot = controller.internalRoot
    val filtered = remember(state.entries, state.query, state.showHidden, state.sort) {
        FileBrowserSort.apply(
            entries = state.entries,
            order = state.sort,
            showHidden = state.showHidden,
            query = state.query,
        )
    }
    val listState = rememberLazyListState()
    val levelBytes = remember(state.entries) {
        state.entries.asSequence()
            .filter { it.kind != FileEntryKind.DIRECTORY }
            .sumOf { it.sizeBytes }
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var copied by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()

    LaunchedEffect(state.details?.path) {
        exportBusy = false
        exportFailed = false
    }

    LaunchedEffect(state.path) {
        runCatching { listState.scrollToItem(0) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600L)
            copied = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(
            initialScale = 0.96f,
            animationSpec = tween(180),
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            targetScale = 0.98f,
            animationSpec = tween(150),
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            val cardWidth = minOf(maxWidth * 0.94f, 560.dp)
            val cardHeight = minOf(maxHeight * 0.90f, 820.dp)
            FileBrowserCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(cardWidth)
                    .height(cardHeight),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FileBrowserHeader(
                            access = state.access,
                            showHidden = state.showHidden,
                            sort = state.sort,
                            pinned = FileBrowserPreferences.isPinned(state.path),
                            onRefresh = controller::refresh,
                            onSortSelected = controller::setSort,
                            onShowHiddenChange = controller::setShowHidden,
                            onTogglePin = { FileBrowserPreferences.togglePin(state.path) },
                            onOpenPathDialog = { showPathDialog = true },
                            onCopyCurrentPath = {
                                copyToClipboard(context, state.path)
                                copied = true
                            },
                            onDismiss = onDismiss,
                        )
                        FileBrowserBreadcrumb(
                            path = state.path,
                            internalRoot = internalRoot,
                            onNavigate = { target ->
                                focusManager.clearFocus()
                                controller.navigateTo(target)
                            },
                        )
                        MiuixSearchField(
                            query = state.query,
                            onQueryChange = controller::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            hintResId = R.string.file_browser_search_hint,
                        )
                        FileBrowserQuickChips(
                            internalRoot = internalRoot,
                            showAppData = state.access?.rootMode == true,
                            onNavigate = { target ->
                                focusManager.clearFocus()
                                controller.navigateTo(target)
                            },
                        )
                        if (state.loading && state.entries.isNotEmpty()) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            )
                        }
                        FileBrowserLimitedBanner(
                            state = state,
                            internalRoot = internalRoot,
                            onRequestAllFiles = onRequestAllFiles,
                            onRequestShizuku = onRequestShizuku,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            FileBrowserListContent(
                                state = state,
                                filtered = filtered,
                                internalRoot = internalRoot,
                                listState = listState,
                                onEntryClick = { entry ->
                                    focusManager.clearFocus()
                                    when (entry.kind) {
                                        FileEntryKind.DIRECTORY,
                                        FileEntryKind.SYMLINK,
                                        -> controller.navigateTo(entry.path)

                                        FileEntryKind.FILE,
                                        FileEntryKind.OTHER,
                                        -> controller.showDetails(entry)
                                    }
                                },
                                onRetry = controller::refresh,
                                onRequestAllFiles = onRequestAllFiles,
                                onRequestShizuku = onRequestShizuku,
                            )
                        }
                        if (state.error == null && (filtered.isNotEmpty() || state.truncated)) {
                            FileBrowserFooter(
                                count = filtered.size,
                                sizeBytes = levelBytes,
                                directorySizeBytes = state.directorySizeBytes,
                                directorySizeLoading = state.directorySizeLoading,
                                directorySizeComputed = state.directorySizeComputed,
                                truncated = state.truncated,
                                onComputeSize = controller::computeDirectorySize,
                            )
                        }
                    }

                    if (copied) {
                        Text(
                            text = stringResource(R.string.file_browser_copied),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    val details = state.details
                    if (details != null) {
                        val openWithTitle = stringResource(R.string.file_browser_open_with)
                        val canDirectOpen = FileBrowserOpen.canOpen(details.path)
                        val canExport = !canDirectOpen &&
                            state.access?.mode == AccessMode.ROOT
                        val openWithLabel = when {
                            exportBusy -> stringResource(R.string.file_browser_exporting)
                            exportFailed -> stringResource(R.string.file_browser_export_failed)
                            canDirectOpen -> stringResource(R.string.file_browser_open_with)
                            canExport -> stringResource(R.string.file_browser_export_open)
                            else -> null
                        }
                        FileBrowserDetailsOverlay(
                            entry = details,
                            copied = copied,
                            onCopy = {
                                copyToClipboard(context, details.path)
                                copied = true
                            },
                            onOpenWith = when {
                                canDirectOpen -> {
                                    {
                                        FileBrowserOpen.open(
                                            context = context,
                                            path = details.path,
                                            chooserTitle = openWithTitle,
                                        )
                                    }
                                }
                                canExport -> {
                                    {
                                        if (!exportBusy) {
                                            actionScope.launch {
                                                exportBusy = true
                                                exportFailed = false
                                                val exported = FileBrowserExport.exportToCache(
                                                    context = context,
                                                    sourcePath = details.path,
                                                )
                                                exportBusy = false
                                                if (exported != null) {
                                                    FileBrowserOpen.openFile(
                                                        context = context,
                                                        file = exported,
                                                        chooserTitle = openWithTitle,
                                                    )
                                                } else {
                                                    exportFailed = true
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> null
                            },
                            openWithLabel = openWithLabel,
                            openWithEnabled = !exportBusy,
                            onClose = controller::hideDetails,
                        )
                    }
                    if (showPathDialog) {
                        FileBrowserPathDialog(
                            initialPath = state.path,
                            onDismiss = { showPathDialog = false },
                            onNavigate = { target ->
                                showPathDialog = false
                                focusManager.clearFocus()
                                controller.navigateTo(target)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserHeader(
    access: FileBrowserAccessInfo?,
    showHidden: Boolean,
    sort: FileSortOrder,
    pinned: Boolean,
    onRefresh: () -> Unit,
    onSortSelected: (FileSortOrder) -> Unit,
    onShowHiddenChange: (Boolean) -> Unit,
    onTogglePin: () -> Unit,
    onOpenPathDialog: () -> Unit,
    onCopyCurrentPath: () -> Unit,
    onDismiss: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.file_browser_title),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FileBrowserAccessBadge(access)
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.file_browser_refresh),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.file_browser_menu),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            ) {
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_name,
                    order = FileSortOrder.NAME_ASC,
                    selected = sort,
                    ascending = true,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_name,
                    order = FileSortOrder.NAME_DESC,
                    selected = sort,
                    ascending = false,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_time,
                    order = FileSortOrder.TIME_DESC,
                    selected = sort,
                    ascending = false,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_time,
                    order = FileSortOrder.TIME_ASC,
                    selected = sort,
                    ascending = true,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_size,
                    order = FileSortOrder.SIZE_DESC,
                    selected = sort,
                    ascending = false,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                SortMenuItem(
                    labelRes = R.string.file_browser_sort_size,
                    order = FileSortOrder.SIZE_ASC,
                    selected = sort,
                    ascending = true,
                    onSelect = {
                        onSortSelected(it)
                        menuExpanded = false
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_browser_show_hidden)) },
                    onClick = {
                        onShowHiddenChange(!showHidden)
                        menuExpanded = false
                    },
                    trailingIcon = {
                        if (showHidden) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_browser_copy_current_path)) },
                    onClick = {
                        onCopyCurrentPath()
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_browser_jump_path)) },
                    onClick = {
                        onOpenPathDialog()
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (pinned) {
                                    R.string.file_browser_unpin_current
                                } else {
                                    R.string.file_browser_pin_current
                                },
                            ),
                        )
                    },
                    onClick = {
                        onTogglePin()
                        menuExpanded = false
                    },
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.file_browser_close),
            )
        }
    }
}

@Composable
private fun SortMenuItem(
    labelRes: Int,
    order: FileSortOrder,
    selected: FileSortOrder,
    ascending: Boolean,
    onSelect: (FileSortOrder) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes) + if (ascending) " ↑" else " ↓",
            )
        },
        onClick = { onSelect(order) },
        trailingIcon = {
            if (selected == order) {
                Icon(Icons.Filled.Check, contentDescription = null)
            }
        },
    )
}

@Composable
private fun FileBrowserAccessBadge(access: FileBrowserAccessInfo?) {
    val mode = access?.mode
    val textRes = when (mode) {
        null -> R.string.file_browser_access_detecting
        AccessMode.ROOT -> R.string.file_browser_access_root
        AccessMode.SHIZUKU -> R.string.file_browser_access_shizuku
        AccessMode.ALL_FILES -> R.string.file_browser_access_all_files
        AccessMode.LIMITED -> R.string.file_browser_access_limited
    }
    val color = when (mode) {
        AccessMode.ROOT, AccessMode.SHIZUKU -> MaterialTheme.colorScheme.primary
        AccessMode.ALL_FILES -> MaterialTheme.colorScheme.tertiary
        AccessMode.LIMITED -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileBrowserBreadcrumb(
    path: String,
    internalRoot: String,
    onNavigate: (String) -> Unit,
) {
    val rootLabel = stringResource(R.string.file_browser_crumb_root)
    val internalLabel = stringResource(R.string.file_browser_crumb_internal)
    val normalizedInternal = remember(internalRoot) {
        FileBrowserPaths.normalize(internalRoot, internalRoot)
    }
    val items = remember(path, internalRoot, rootLabel, internalLabel) {
        FileBrowserPaths.crumbs(path, internalRoot).map { crumb ->
            val label = when (crumb.path) {
                "/" -> rootLabel
                normalizedInternal -> internalLabel
                else -> crumb.label
            }
            BreadcrumbItem(path = crumb.path, text = label)
        }
    }
    val scrollState = rememberScrollState()

    BreadcrumbBar(
        items = items,
        onItemClick = { index ->
            if (index != items.lastIndex) {
                onNavigate(items[index].path)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        highlightIndex = items.lastIndex,
        colors = BreadcrumbBarDefaults.breadcrumbBarColors(
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            highlightColor = MiuixTheme.colorScheme.primary,
            backgroundColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f),
            highlightBackgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.16f),
        ),
        itemMaxWidth = 120.dp,
        scrollState = scrollState,
    )
}

private data class QuickTarget(
    val labelRes: Int,
    val path: String,
    val icon: ImageVector,
)

@Composable
private fun FileBrowserQuickChips(
    internalRoot: String,
    showAppData: Boolean,
    onNavigate: (String) -> Unit,
) {
    val targets = remember(internalRoot, showAppData) {
        buildList {
            add(
                QuickTarget(
                    labelRes = R.string.file_browser_quick_root,
                    path = "/",
                    icon = Icons.Outlined.Storage,
                ),
            )
            add(
                QuickTarget(
                    labelRes = R.string.file_browser_quick_internal,
                    path = internalRoot,
                    icon = Icons.Outlined.PhoneAndroid,
                ),
            )
            add(
                QuickTarget(
                    labelRes = R.string.file_browser_quick_android_data,
                    path = "$internalRoot/Android/data",
                    icon = Icons.Outlined.Android,
                ),
            )
            add(
                QuickTarget(
                    labelRes = R.string.file_browser_quick_download,
                    path = "$internalRoot/Download",
                    icon = Icons.Outlined.Download,
                ),
            )
            if (showAppData) {
                add(
                    QuickTarget(
                        labelRes = R.string.file_browser_quick_app_data,
                        path = "/data/data",
                        icon = Icons.Outlined.Apps,
                    ),
                )
            }
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(targets) { target ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable { onNavigate(target.path) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = target.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(target.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FileBrowserFooter(
    count: Int,
    sizeBytes: Long,
    directorySizeBytes: Long?,
    directorySizeLoading: Boolean,
    directorySizeComputed: Boolean,
    truncated: Boolean,
    onComputeSize: () -> Unit,
) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val footerText = when {
            directorySizeLoading -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                stringResource(R.string.file_browser_size_calculating),
            )
            directorySizeComputed && directorySizeBytes != null -> stringResource(
                R.string.file_browser_footer_count_directory,
                count,
                FileBrowserFormat.size(directorySizeBytes),
            )
            directorySizeComputed -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                stringResource(R.string.file_browser_size_unavailable),
            )
            else -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                FileBrowserFormat.size(sizeBytes),
            )
        }
        Text(
            text = footerText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!directorySizeLoading && !directorySizeComputed) {
            TextButton(
                onClick = onComputeSize,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.file_browser_compute_size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (truncated) {
            Text(
                text = stringResource(
                    R.string.file_browser_truncated,
                    FileBrowserShell.MAX_ENTRIES,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileBrowserCard(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = LocalAppDarkTheme.current
    val corner = 28.dp
    val shape = RoundedCornerShape(corner)
    val fallback = MaterialTheme.colorScheme.surface.copy(
        alpha = if (isDark) 0.88f else 0.82f,
    )
    val borderColor = if (isDark) Color(0x28FFFFFF) else Color(0x18000000)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cornerPx = with(density) { corner.toPx() }
    val blurRadiusPx = with(density) { 57.dp.toPx().toInt() }

    Box(
        modifier = modifier
            .shadow(20.dp, shape, clip = false)
            .clip(shape)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(fallback),
        )
        LocalFrostedGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
            blurRadiusPx = blurRadiusPx,
            tintColor = if (isDark) 0x661C1C1E else 0x66F5F5F7,
            solidFallbackColor = fallback.toArgb(),
        )
        Box(modifier = Modifier.fillMaxSize(), content = content)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("path", text))
    }
}

@Composable
private fun FileBrowserPathDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var value by remember(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_browser_jump_path)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_browser_jump_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = value.trim()
                    if (target.isNotEmpty()) onNavigate(target)
                },
            ) {
                Text(stringResource(R.string.file_browser_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_browser_cancel))
            }
        },
    )
}
