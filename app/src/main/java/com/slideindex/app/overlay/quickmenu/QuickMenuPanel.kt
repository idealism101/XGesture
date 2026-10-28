package com.slideindex.app.overlay.quickmenu

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.barcode.BarcodeScanResult
import com.slideindex.app.overlay.filebrowser.FileBrowserQuickCard
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.MiuixIcons

/**
 * 「快捷菜单」面板内容。
 *
 * 形态刻意对齐"聚合"这个产品目标：
 * - **出厂即满**：没有任何"添加/收藏/绑定"操作，[QuickMenuTool] 里注册的工具全都在；
 * - **搜索框**用于「查找」而不是「配置」——工具变多以后靠打字直达；
 * - **最近使用**是自动排前（用户在做什么，什么就靠前），不需要手动置顶；
 * - 每行带**一句描述**，因为九宫格只能靠图标猜，而这里的工具彼此不像。
 *
 * ⚠️ 本文件是纯 UI，**没有单测**（Compose 交互需要真机/仪器测试）。其依赖的逻辑全部放在
 * 可单测的纯 JVM 件里：`UnitConversionEngine` / `CurrencyRateEngine` / `PasswordGenerator` /
 * `StopwatchLogic` / `QrCodeGenerator` / `QrPayloadParser`。
 */
@Composable
fun QuickMenuPanel(
    visible: Boolean,
    page: QuickMenuTool?,
    query: String,
    recents: List<QuickMenuTool>,
    /** 识码页的原文。由宿主的 Session 承载，这样外部 Activity 期间窗口被摘掉也不丢。 */
    scanContent: String,
    /** 识码页的候选列表（含归一化定位点，用于在图上画标记）；只有一个码时为空列表。 */
    scanResults: List<BarcodeScanResult>,
    /** 被扫描的那张图；为 null 时只显示候选列表（例如相机路径在定格之前）。 */
    scanImage: Bitmap?,
    onQueryChange: (String) -> Unit,
    onScanContentChange: (String) -> Unit,
    onScanResultsChange: (List<BarcodeScanResult>) -> Unit,
    onScanImageChange: (Bitmap?) -> Unit,
    onOpenTool: (QuickMenuTool) -> Unit,
    onOpenFileBrowser: (String?) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titles = QuickMenuTool.entries.associateWith { stringResource(it.titleRes) }
    val descs = QuickMenuTool.entries.associateWith { stringResource(it.descRes) }
    val context = LocalContext.current
    // **必须放在 if (page == null) 之外**：列表在切到子页时会离开组合，状态若留在里面就会被重置，
    // 表现就是"从子页返回后回到了顶部"（真机反馈）。
    val listState = rememberLazyListState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 罩层：点空白处关闭。指示涟漪在这里是噪音，显式关掉。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.86f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (page == null) {
                        ListHeader(onDismiss = onDismiss)
                        FileBrowserQuickCard(onOpen = onOpenFileBrowser)
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                )
                            },
                            placeholder = { Text(stringResource(R.string.quick_menu_search_hint)) },
                            // 顶部一键扫码：用户要的是"从最上面直接扫"，不必先翻到识码页。
                            // 先切到识码页（结果在那里展示），再拉起相机；与识码页的按钮共用 launchCameraScan。
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onOpenTool(QuickMenuTool.SCAN_DECODER)
                                        launchCameraScan(
                                            context = context,
                                            onContentChange = onScanContentChange,
                                            onResultsChange = onScanResultsChange,
                                            onImageChange = onScanImageChange,
                                        )
                                    },
                                ) {
                                    Icon(
                                        // 用二维码图标（与 quickdash 的扫码入口一致），相机图标更像"拍照"
                                        imageVector = Icons.Filled.QrCodeScanner,
                                        contentDescription = stringResource(R.string.quick_menu_scan_camera),
                                    )
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ToolList(
                            query = query,
                            recents = recents,
                            titles = titles,
                            descs = descs,
                            state = listState,
                            onOpenTool = onOpenTool,
                        )
                    } else {
                        ToolPageHeader(
                            title = titles.getValue(page),
                            onBack = onBack,
                            onDismiss = onDismiss,
                        )
                        QuickMenuToolScreen(
                            tool = page,
                            onDismiss = onDismiss,
                            scanContent = scanContent,
                            scanResults = scanResults,
                            scanImage = scanImage,
                            onScanContentChange = onScanContentChange,
                            onScanResultsChange = onScanResultsChange,
                            onScanImageChange = onScanImageChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.gesture_action_quick_menu),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(imageVector = MiuixIcons.Medium.Close, contentDescription = stringResource(R.string.quick_menu_back))
        }
    }
}

@Composable
private fun ToolPageHeader(title: String, onBack: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.quick_menu_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDismiss) {
            Icon(imageVector = MiuixIcons.Medium.Close, contentDescription = stringResource(R.string.quick_menu_back))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun ToolList(
    query: String,
    recents: List<QuickMenuTool>,
    titles: Map<QuickMenuTool, String>,
    descs: Map<QuickMenuTool, String>,
    state: LazyListState,
    onOpenTool: (QuickMenuTool) -> Unit,
) {
    val matched = remember(query, titles, descs) {
        QuickMenuTool.matching(query, { titles.getValue(it) }, { descs.getValue(it) })
    }
    val grouped = remember(matched) { matched.groupBy { it.group } }
    // 折叠状态：默认全部展开。搜索时强制展开（正在找东西时不该还要再点开一层）。
    val collapsed = remember { mutableStateMapOf<QuickMenuGroup, Boolean>() }
    val searching = query.isNotBlank()

    if (matched.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.quick_menu_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        // 状态由上层持有：从子页返回时列表位置不会被重置到顶部（真机反馈）。
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // 最近使用：只在没有搜索词时展示（搜索时用户已经知道要找什么，横排只是干扰）。
        if (query.isBlank() && recents.isNotEmpty()) {
            item(key = "recent") {
                SectionTitle(stringResource(R.string.quick_menu_section_recent))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recents, key = { "recent-${it.name}" }) { tool ->
                        RecentChip(title = titles.getValue(tool)) { onOpenTool(tool) }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        QuickMenuGroup.entries.forEach { group ->
            val tools = grouped[group].orEmpty()
            if (tools.isEmpty()) return@forEach
            val isCollapsed = !searching && collapsed[group] == true
            item(key = "header-${group.name}") {
                SectionTitle(
                    text = stringResource(group.titleRes),
                    collapsed = isCollapsed,
                    onToggle = { collapsed[group] = !isCollapsed },
                )
            }
            if (isCollapsed) return@forEach
            items(tools, key = { it.name }) { tool ->
                ToolRow(
                    tool = tool,
                    title = titles.getValue(tool),
                    description = descs.getValue(tool),
                    onClick = { onOpenTool(tool) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val base = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)
    if (onToggle == null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = base,
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (collapsed == true) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.KeyboardArrowUp
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RecentChip(title: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ToolRow(tool: QuickMenuTool, title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 工具页分发：新增工具时**只需要**在这里加一个分支（外加注册表里的一项与两条字符串）。 */
@Composable
private fun QuickMenuToolScreen(
    tool: QuickMenuTool,
    onDismiss: () -> Unit,
    scanContent: String,
    scanResults: List<BarcodeScanResult>,
    scanImage: Bitmap?,
    onScanContentChange: (String) -> Unit,
    onScanResultsChange: (List<BarcodeScanResult>) -> Unit,
    onScanImageChange: (Bitmap?) -> Unit,
) {
    when (tool) {
        QuickMenuTool.UNIT_CONVERTER -> QuickMenuUnitConverterScreen()
        QuickMenuTool.CURRENCY_CONVERTER -> QuickMenuCurrencyConverterScreen()
        QuickMenuTool.QR_GENERATOR -> QuickMenuQrGeneratorScreen()
        QuickMenuTool.SCAN_DECODER -> QuickMenuScanDecoderScreen(
            content = scanContent,
            results = scanResults,
            image = scanImage,
            onContentChange = onScanContentChange,
            onResultsChange = onScanResultsChange,
            onImageChange = onScanImageChange,
        )
        QuickMenuTool.PASSWORD -> QuickMenuPasswordScreen()
        QuickMenuTool.TEXT_TOOL -> QuickMenuTextToolScreen()
        QuickMenuTool.TRANSLATE -> QuickMenuTranslateScreen()
        QuickMenuTool.STOPWATCH -> QuickMenuStopwatchScreen()
        QuickMenuTool.COUNTDOWN -> QuickMenuCountdownScreen()
        QuickMenuTool.REMINDER -> QuickMenuReminderScreen()
        QuickMenuTool.TIMER_HISTORY -> QuickMenuHistoryScreen()
        QuickMenuTool.CALCULATOR -> QuickMenuCalculatorScreen()
        QuickMenuTool.POMODORO -> QuickMenuPomodoroScreen()
        QuickMenuTool.COLOR_PICKER -> QuickMenuColorPickerScreen()
        QuickMenuTool.MACRO_RUN -> QuickMenuMacroScreen(onRan = onDismiss)
        // 预置入口**不切页**（点击处已直接执行并收起菜单），这里只是让 when 穷尽
        QuickMenuTool.ENTRY_FOREGROUND_ACTIVITY,
        QuickMenuTool.ENTRY_FREEZER,
        QuickMenuTool.ENTRY_SHELL_PANEL,
        QuickMenuTool.ENTRY_SEARCH_PANEL,
        QuickMenuTool.ENTRY_CLIPBOARD_PANEL,
        QuickMenuTool.ENTRY_PIN_TO_SCREEN,
        QuickMenuTool.ENTRY_STASH_PANEL,
        QuickMenuTool.PAGE_SCROLL,
        QuickMenuTool.WIDGET_PANEL,
        QuickMenuTool.FILE_BROWSER,
        -> Unit
    }
}
