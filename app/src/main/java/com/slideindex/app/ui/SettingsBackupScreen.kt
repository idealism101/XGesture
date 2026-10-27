@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.settings.SettingsBackupModuleSummary
import com.slideindex.app.settings.SettingsModule
import com.slideindex.app.ui.miuix.MiuixFormDialog
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.LazySettingsItem
import com.slideindex.app.ui.settings.components.SettingNavigationRow
import com.slideindex.app.ui.settings.components.SettingSwitchRow
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import com.slideindex.app.ui.viewmodel.SettingsBackupPreviewState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    onBack: () -> Unit,
    onExport: (includeSensitiveData: Boolean, uri: android.net.Uri) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    importPreviewState: SettingsBackupPreviewState?,
    onDismissPreview: () -> Unit,
    onConfirmImport: (android.net.Uri, Set<SettingsModule>) -> Unit,
    missingPermissionCount: Int,
    onOpenMissingPermissions: () -> Unit,
    onOpenCloudBackup: () -> Unit,
) {
    val resources = LocalResources.current
    var includeSensitiveData by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onExport(includeSensitiveData, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImport(uri)
        }
    }

    val actionsSectionTitle = stringResource(R.string.settings_backup_section_actions)
    val permissionsSectionTitle = stringResource(R.string.settings_backup_section_permissions)
    val cloudSectionTitle = stringResource(R.string.settings_backup_cloud_entry_title)

    SettingsScreenScaffold(
        title = stringResource(R.string.settings_backup_title),
        onBack = onBack,
    ) {
        settingsLazySmallTitle(key = "backup_actions_section", title = actionsSectionTitle)
        groupedCardItems(
            keyPrefix = "backup_sensitive",
            items = buildList {
                add(
                    settingsCardScopeItem("include-sensitive") {
                        SettingSwitchRow(
                            title = stringResource(R.string.settings_backup_include_sensitive),
                            subtitle = stringResource(R.string.settings_backup_sensitive_hint),
                            checked = includeSensitiveData,
                            enabled = true,
                            onCheckedChange = { includeSensitiveData = it },
                        )
                    },
                )
            },
        )

        LazySettingsItem(key = "backup-actions") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val defaultName = resources.getString(
                            R.string.settings_backup_default_filename,
                            System.currentTimeMillis(),
                        )
                        exportLauncher.launch(defaultName)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Icon(
                        MiuixIcons.Download,
                        contentDescription = stringResource(R.string.cd_export_settings),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_export),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
                Button(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "multipart/x-zip"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        MiuixIcons.UploadCloud,
                        contentDescription = stringResource(R.string.cd_import_settings),
                        tint = MiuixTheme.colorScheme.onSecondaryVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_import),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.onSecondaryVariant,
                    )
                }
            }
        }

        // 云端备份（WebDAV / S3 兼容对象存储）：独立的页面，保留策略与远端列表都在那边
        settingsLazySmallTitle(key = "backup_cloud_section", title = cloudSectionTitle)
        groupedCardItems(
            keyPrefix = "backup_cloud",
            items = buildList {
                add(
                    settingsCardScopeItem("cloud-backup") {
                        SettingNavigationRow(
                            icon = { label ->
                                Icon(
                                    MiuixIcons.UploadCloud,
                                    contentDescription = label,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            title = stringResource(R.string.settings_backup_cloud_entry_title),
                            subtitle = stringResource(R.string.settings_backup_cloud_entry_desc),
                            onClick = onOpenCloudBackup,
                        )
                    },
                )
            },
        )

        if (missingPermissionCount > 0) {
            settingsLazySmallTitle(
                key = "backup_permissions_section",
                title = permissionsSectionTitle,
            )
            groupedCardItems(
                keyPrefix = "backup_permissions",
                items = buildList {
                    add(
                        settingsCardScopeItem("missing-permissions") {
                            MissingPermissionsEntryCard(
                                missingCount = missingPermissionCount,
                                onClick = onOpenMissingPermissions,
                            )
                        },
                    )
                },
            )
        }
    }

    if (importPreviewState != null) {
        val preview = importPreviewState.preview
        val context = LocalContext.current
        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val dateString = dateFormat.format(Date(preview.exportedAtEpochMs))
        var selectedModules by remember(preview) {
            mutableStateOf(preview.moduleSummaries.keys.toSet())
        }
        val presentCount = preview.moduleSummaries.size
        val selectedCount = selectedModules.count { it in preview.moduleSummaries }

        MiuixFormDialog(
            show = true,
            onDismissRequest = onDismissPreview,
            title = stringResource(R.string.settings_backup_preview_title),
            confirmText = stringResource(R.string.settings_backup_restore_selected, selectedCount),
            confirmEnabled = selectedCount > 0,
            onConfirm = { onConfirmImport(importPreviewState.uri, selectedModules) },
            dismissText = stringResource(android.R.string.cancel),
        ) {
            Text(
                text = stringResource(R.string.settings_backup_preview_info, dateString, preview.appVersionName),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(
                    R.string.settings_backup_summary,
                    preview.totalPreferencesCount,
                    presentCount,
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )

            // 警告放在列表**上面**：长列表一滚，底部的东西就永远看不到了。
            val unselectedCount = presentCount - selectedCount
            Text(
                text = if (unselectedCount > 0) {
                    stringResource(
                        R.string.settings_backup_restore_warning,
                        selectedCount,
                        unselectedCount,
                    )
                } else {
                    stringResource(R.string.settings_backup_restore_warning_all, selectedCount)
                },
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_backup_selected_summary,
                        selectedCount,
                        presentCount,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f),
                )
                SelectionAction(
                    text = stringResource(R.string.settings_backup_select_all),
                    onClick = { selectedModules = preview.moduleSummaries.keys },
                )
                SelectionAction(
                    text = stringResource(R.string.settings_backup_select_none),
                    onClick = { selectedModules = emptySet() },
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            SETTINGS_MODULE_GROUPS.forEach { group ->
                val modules = group.modules.filter { it in preview.moduleSummaries }
                if (modules.isEmpty()) return@forEach
                Text(
                    text = stringResource(group.titleRes),
                    style = MiuixTheme.textStyles.headline2,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
                modules.forEach { module ->
                    SettingsBackupModuleRow(
                        context = context,
                        module = module,
                        summary = preview.moduleSummaries.getValue(module),
                        checked = module in selectedModules,
                        onToggle = {
                            selectedModules = if (module in selectedModules) {
                                selectedModules - module
                            } else {
                                selectedModules + module
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 弹窗里的小号文字按钮（全选 / 全不选）。 */
@Composable
private fun SelectionAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.primary,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsBackupModuleRow(
    context: android.content.Context,
    module: SettingsModule,
    summary: SettingsBackupModuleSummary,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = settingsModuleLabel(context, module),
                style = MiuixTheme.textStyles.body1,
            )
            val privacy = if (module.sensitive && (summary.hasHistory || summary.hasFiles)) {
                stringResource(R.string.settings_backup_privacy_badge)
            } else {
                null
            }
            val history = if (summary.hasHistory) {
                stringResource(R.string.settings_backup_has_history)
            } else {
                null
            }
            val files = if (summary.hasFiles) {
                stringResource(R.string.settings_backup_has_files)
            } else {
                null
            }
            val badges = listOfNotNull(privacy, history, files)
            if (badges.isNotEmpty()) {
                Text(
                    text = badges.joinToString(" · "),
                    style = MiuixTheme.textStyles.body2,
                    color = if (module.sensitive) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
        Text(
            text = if (summary.settingsCount > 0) {
                stringResource(R.string.settings_backup_module_item_count, summary.settingsCount)
            } else {
                "—"
            },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private data class SettingsModuleGroup(
    @StringRes val titleRes: Int,
    val modules: List<SettingsModule>,
)

/**
 * 弹窗里的分组顺序：先入口，再启动器，最后外观/通用。
 * 只展示备份里真实存在的模块，空组自动跳过。
 */
private val SETTINGS_MODULE_GROUPS = listOf(
    SettingsModuleGroup(
        R.string.settings_module_group_input,
        listOf(
            SettingsModule.EDGE_GESTURE,
            SettingsModule.CORNER_WHEEL,
            SettingsModule.QUICK_WHEEL,
            SettingsModule.FLOAT_BALL,
            SettingsModule.FLOATING_POINTER,
            SettingsModule.FINGERTIP_RING,
            SettingsModule.SHAKE,
        ),
    ),
    SettingsModuleGroup(
        R.string.settings_module_group_launcher,
        listOf(
            SettingsModule.QUICK_LAUNCHER,
            SettingsModule.WIDGET_PANEL,
            SettingsModule.STASH,
            SettingsModule.DRAG_DROP,
        ),
    ),
    SettingsModuleGroup(
        R.string.settings_module_group_message,
        listOf(SettingsModule.MESSAGE, SettingsModule.OTP),
    ),
    SettingsModuleGroup(
        R.string.settings_module_group_data,
        listOf(
            SettingsModule.CLIPBOARD,
            SettingsModule.SEARCH,
            SettingsModule.SHELL,
            SettingsModule.ACTIVITY_SHORTCUTS,
        ),
    ),
    SettingsModuleGroup(
        R.string.settings_module_group_window,
        listOf(SettingsModule.FREE_WINDOW, SettingsModule.FREEZER),
    ),
    SettingsModuleGroup(
        R.string.settings_module_group_appearance,
        listOf(SettingsModule.THEME_UI, SettingsModule.GENERAL),
    ),
)

fun settingsModuleLabel(context: android.content.Context, module: SettingsModule): String = when (module) {
    SettingsModule.EDGE_GESTURE -> context.getString(R.string.settings_module_edge_gesture)
    SettingsModule.CORNER_WHEEL -> context.getString(R.string.settings_module_corner_wheel)
    SettingsModule.QUICK_WHEEL -> context.getString(R.string.settings_module_quick_wheel)
    SettingsModule.FLOAT_BALL -> context.getString(R.string.settings_module_float_ball)
    SettingsModule.FLOATING_POINTER -> context.getString(R.string.settings_module_floating_pointer)
    SettingsModule.FINGERTIP_RING -> context.getString(R.string.settings_module_fingertip_ring)
    SettingsModule.QUICK_LAUNCHER -> context.getString(R.string.settings_module_quick_launcher)
    SettingsModule.WIDGET_PANEL -> context.getString(R.string.settings_module_widget_panel)
    SettingsModule.MESSAGE -> context.getString(R.string.settings_module_message)
    SettingsModule.OTP -> context.getString(R.string.settings_module_otp)
    SettingsModule.SHAKE -> context.getString(R.string.settings_module_shake)
    SettingsModule.CLIPBOARD -> context.getString(R.string.settings_module_clipboard)
    SettingsModule.DRAG_DROP -> context.getString(R.string.settings_module_drag_drop)
    SettingsModule.STASH -> context.getString(R.string.settings_module_stash)
    SettingsModule.SEARCH -> context.getString(R.string.settings_module_search)
    SettingsModule.FREE_WINDOW -> context.getString(R.string.settings_module_free_window)
    SettingsModule.FREEZER -> context.getString(R.string.settings_module_freezer)
    SettingsModule.SHELL -> context.getString(R.string.settings_module_shell)
    SettingsModule.ACTIVITY_SHORTCUTS -> context.getString(R.string.settings_module_activity_shortcuts)
    SettingsModule.THEME_UI -> context.getString(R.string.settings_module_theme_ui)
    SettingsModule.GENERAL -> context.getString(R.string.settings_module_general)
}
