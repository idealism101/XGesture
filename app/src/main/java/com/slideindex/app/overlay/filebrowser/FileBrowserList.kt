package com.slideindex.app.overlay.filebrowser

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.R

@Composable
internal fun FileBrowserListContent(
    state: FileBrowserUiState,
    filtered: List<FileBrowserEntry>,
    internalRoot: String,
    listState: LazyListState,
    onEntryClick: (FileBrowserEntry) -> Unit,
    onEntryLongClick: (FileBrowserEntry) -> Unit,
    onRetry: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val error = state.error
    when {
        error != null -> FileBrowserErrorState(
            error = error,
            state = state,
            internalRoot = internalRoot,
            onRetry = onRetry,
            onRequestAllFiles = onRequestAllFiles,
            onRequestShizuku = onRequestShizuku,
        )

        state.loading && state.entries.isEmpty() -> FileBrowserLoadingState()

        filtered.isEmpty() -> FileBrowserEmptyState(
            searching = state.query.isNotBlank() || state.entries.isNotEmpty(),
        )

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(
                items = filtered,
                key = { _, entry -> entry.path },
            ) { index, entry ->
                FileBrowserEntryRow(
                    entry = entry,
                    showDivider = index < filtered.lastIndex,
                    onClick = { onEntryClick(entry) },
                    onLongClick = { onEntryLongClick(entry) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserEntryRow(
    entry: FileBrowserEntry,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fileKind = FileKind.from(entry.name, entry.kind)
    val isFolder = entry.kind == FileEntryKind.DIRECTORY
    val tint = if (isFolder) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    var thumbnail by remember(entry.path, entry.modifiedEpochSeconds) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(entry.path, entry.modifiedEpochSeconds, fileKind) {
        if (fileKind == FileKind.IMAGE) {
            thumbnail = FileBrowserThumbnailLoader.load(
                path = entry.path,
                modifiedEpochSeconds = entry.modifiedEpochSeconds,
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = iconFor(fileKind),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleFor(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.kind == FileEntryKind.DIRECTORY || entry.kind == FileEntryKind.SYMLINK) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
    }
}

@Composable
private fun subtitleFor(entry: FileBrowserEntry): String = when (entry.kind) {
    FileEntryKind.DIRECTORY -> stringResource(R.string.file_browser_kind_folder)
    FileEntryKind.SYMLINK -> stringResource(R.string.file_browser_kind_link)
    FileEntryKind.FILE, FileEntryKind.OTHER ->
        "${FileBrowserFormat.size(entry.sizeBytes)} · " +
            FileBrowserFormat.date(entry.modifiedEpochSeconds)
}

@Composable
private fun FileBrowserEmptyState(searching: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (searching) Icons.Outlined.SearchOff else Icons.Outlined.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (searching) {
                        R.string.file_browser_empty_search
                    } else {
                        R.string.file_browser_empty_dir
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FileBrowserLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.file_browser_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileBrowserErrorState(
    error: FileBrowserError,
    state: FileBrowserUiState,
    internalRoot: String,
    onRetry: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val access = state.access
    val isAndroidData = FileBrowserPaths.isAndroidDataOrObb(state.path, internalRoot)
    val isExternal = FileBrowserPaths.isExternal(state.path, internalRoot)
    val isRootOnly = FileBrowserPaths.isRootOnly(state.path)
    val canOfferAllFiles = access?.allFiles != true && isExternal && !isAndroidData
    val canOfferShizuku = access?.rootMode == false &&
        access.privileged == false &&
        !isRootOnly

    val titleRes: Int
    val messageRes: Int?
    when (error) {
        FileBrowserError.PermissionDenied -> {
            titleRes = R.string.file_browser_error_permission
            messageRes = R.string.file_browser_error_permission_hint
        }
        FileBrowserError.NeedsRoot -> {
            titleRes = R.string.file_browser_error_needs_root
            messageRes = R.string.file_browser_error_needs_root_hint
        }
        FileBrowserError.PrivilegeUnavailable -> {
            titleRes = R.string.file_browser_error_privilege
            messageRes = when {
                access?.rootMode == true -> R.string.file_browser_error_privilege_root_hint
                isRootOnly -> R.string.file_browser_error_needs_root_hint
                else -> R.string.file_browser_error_privilege_shizuku_hint
            }
        }
        FileBrowserError.NotFound -> {
            titleRes = R.string.file_browser_error_not_found
            messageRes = null
        }
        FileBrowserError.NotDirectory -> {
            titleRes = R.string.file_browser_error_not_dir
            messageRes = null
        }
        FileBrowserError.Timeout -> {
            titleRes = R.string.file_browser_error_timeout
            messageRes = null
        }
        is FileBrowserError.Unknown -> {
            titleRes = R.string.file_browser_error_unknown
            messageRes = null
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (messageRes != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (error is FileBrowserError.Unknown && !error.detail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error.detail.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (canOfferAllFiles) {
                Button(
                    onClick = onRequestAllFiles,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.file_browser_grant_all_files))
                }
            }
            if (canOfferShizuku &&
                (error == FileBrowserError.PrivilegeUnavailable ||
                    isAndroidData ||
                    state.path == "/")
            ) {
                if (canOfferAllFiles) Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.file_browser_grant_shizuku))
                }
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.file_browser_retry))
            }
        }
    }
}

/**
 * 只读访问受限时的轻量横幅。真正失败时由错误卡给动作，这里只在“列出来了但不完整”时提示。
 */
@Composable
internal fun FileBrowserLimitedBanner(
    state: FileBrowserUiState,
    internalRoot: String,
    onRequestAllFiles: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    if (!state.limited) return
    val access = state.access
    val isAndroidData = FileBrowserPaths.isAndroidDataOrObb(state.path, internalRoot)
    val canOfferAllFiles = access?.allFiles != true && !isAndroidData
    val canOfferShizuku = access?.rootMode == false && access.privileged == false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.file_browser_banner_limited),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (canOfferAllFiles) {
            TextButton(onClick = onRequestAllFiles) {
                Text(stringResource(R.string.file_browser_grant_all_files))
            }
        } else if (canOfferShizuku) {
            TextButton(onClick = onRequestShizuku) {
                Text(stringResource(R.string.file_browser_grant_shizuku))
            }
        }
    }
}

private fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.SYMLINK -> Icons.Outlined.Link
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.APK -> Icons.Outlined.Android
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.TEXT -> Icons.Outlined.Description
    FileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
