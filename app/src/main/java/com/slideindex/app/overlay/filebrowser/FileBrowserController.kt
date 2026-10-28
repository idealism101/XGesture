package com.slideindex.app.overlay.filebrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileBrowserUiState(
    val path: String,
    val entries: List<FileBrowserEntry> = emptyList(),
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: FileSortOrder = FileSortOrder.NAME_ASC,
    val loading: Boolean = false,
    val error: FileBrowserError? = null,
    val details: FileBrowserEntry? = null,
    val access: FileBrowserAccessInfo? = null,
    val limited: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * 文件浏览器状态机。
 *
 * 所有状态写入都发生在其 [scope]（Main.immediate），目录读取在 IO；切换目录时取消旧任务，
 * 避免“先点 A 再点 B，A 的结果回来覆盖 B”。Session 跨窗口保留，所以关闭面板不会重置路径。
 */
internal class FileBrowserController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val dataSource = FileBrowserDataSource(context)
    private var loadJob: Job? = null

    val internalRoot: String get() = dataSource.internalRoot

    var state by mutableStateOf(
        FileBrowserUiState(path = dataSource.initialPath()),
    )
        private set

    /** 面板重新可见：清掉搜索词/详情，保留路径与排序，然后刷新。 */
    fun onShow() {
        state = state.copy(
            query = "",
            details = null,
            loading = true,
            error = null,
        )
        reload()
    }

    fun navigateTo(path: String) {
        val normalized = FileBrowserPaths.normalize(path, internalRoot)
        if (normalized == state.path && state.error == null) return
        state = state.copy(
            path = normalized,
            entries = emptyList(),
            query = "",
            details = null,
            loading = true,
            error = null,
            limited = false,
            truncated = false,
        )
        reload()
    }

    fun goParent() {
        FileBrowserPaths.parent(state.path, internalRoot)?.let(::navigateTo)
    }

    fun refresh() {
        reload()
    }

    /** 授权返回后调用：重新探测访问能力并重载当前目录。 */
    fun refreshAccessAndReload() {
        reload()
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setShowHidden(show: Boolean) {
        state = state.copy(showHidden = show)
    }

    fun setSort(order: FileSortOrder) {
        state = state.copy(sort = order)
    }

    fun showDetails(entry: FileBrowserEntry) {
        state = state.copy(details = entry)
    }

    fun hideDetails() {
        state = state.copy(details = null)
    }

    fun cancel() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun reload() {
        loadJob?.cancel()
        val targetPath = state.path
        loadJob = scope.launch {
            state = state.copy(loading = true, error = null)
            val access = withContext(Dispatchers.IO) { dataSource.resolveAccess() }
            if (!isActive) return@launch
            state = state.copy(access = access)

            val result = withContext(Dispatchers.IO) { dataSource.list(targetPath, access) }
            if (!isActive) return@launch

            state = when (result) {
                is FileBrowserListingResult.Success -> state.copy(
                    path = targetPath,
                    entries = result.entries,
                    loading = false,
                    error = null,
                    limited = result.limited,
                    truncated = result.truncated,
                )
                is FileBrowserListingResult.Failure -> state.copy(
                    path = targetPath,
                    entries = emptyList(),
                    loading = false,
                    error = result.error,
                    limited = false,
                    truncated = false,
                )
            }
        }
    }
}
