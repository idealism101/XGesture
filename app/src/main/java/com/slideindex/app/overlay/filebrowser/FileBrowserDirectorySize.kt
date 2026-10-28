package com.slideindex.app.overlay.filebrowser

import com.slideindex.app.shizuku.TaskManagerShellExecutor
import com.slideindex.app.util.TaskManagerUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 当前目录的递归大小。
 *
 * - Root：走常驻 su 会话里的 `du -sk`（已由 RootNamespace 切到全局 mount namespace）；
 * - Shizuku：走 UserService 里的 `du`；
 * - 普通可读路径：Java 递归遍历。
 *
 * 这是尽力而为：大目录/超时/没有 du 都返回 null，UI 保留“本层文件大小”而不是卡住。
 */
internal object FileBrowserDirectorySize {
    private const val ROOT_TIMEOUT_MS = 20_000L
    private const val SHIZUKU_TIMEOUT_MS = 15_000L
    private const val DIRECT_WALK_LIMIT = 50_000

    suspend fun compute(path: String, access: FileBrowserAccessInfo): Long? =
        withContext(Dispatchers.IO) {
            // 根、/proc、/sys 递归统计没有意义且会卡很久；直接放弃，让 UI 保留本层大小。
            if (path == "/" ||
                path.startsWith("/proc") ||
                path.startsWith("/sys") ||
                path.startsWith("/dev")
            ) {
                return@withContext null
            }
            when (access.mode) {
                AccessMode.ROOT -> shellSize(path, useRoot = true)
                AccessMode.SHIZUKU -> shellSize(path, useRoot = false)
                AccessMode.ALL_FILES, AccessMode.LIMITED -> directSize(path)
            }
        }

    private suspend fun shellSize(path: String, useRoot: Boolean): Long? {
        val command = "du -sk ${TaskManagerShellExecutor.shellQuote(path)} 2>/dev/null"
        val output = if (useRoot) {
            TaskManagerShellExecutor.runAsRootUser(command, ROOT_TIMEOUT_MS).output
        } else {
            TaskManagerUtil.runShellCommandLine(
                command = command,
                useRoot = false,
                timeoutMs = SHIZUKU_TIMEOUT_MS,
            ).output
        }
        return parseDuKb(output)
    }

    private suspend fun directSize(path: String): Long? {
        val root = File(path)
        if (!root.isDirectory) return null
        var total = 0L
        var visited = 0
        return runCatching {
            root.walkTopDown().forEach { file ->
                coroutineContext.ensureActive()
                visited++
                if (visited > DIRECT_WALK_LIMIT) return null
                if (file.isFile) total += file.length()
            }
            total
        }.getOrNull()
    }

    /** `du -sk` 输出："12345\t/path" → 12345 * 1024。 */
    internal fun parseDuKb(output: String): Long? {
        val line = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val kb = line.trim().substringBefore(' ').substringBefore('\t').toLongOrNull() ?: return null
        return kb * 1024L
    }
}
