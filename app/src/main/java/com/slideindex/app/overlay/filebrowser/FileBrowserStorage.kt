package com.slideindex.app.overlay.filebrowser

import android.os.Environment
import android.os.StatFs

data class FileBrowserStorageUsage(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat()
}

object FileBrowserStorage {
    @Suppress("DEPRECATION")
    fun internalStoragePath(): String = Environment.getExternalStorageDirectory()
        ?.absolutePath
        ?.takeIf { it.isNotBlank() }
        ?: FileBrowserPaths.DEFAULT_INTERNAL_ROOT

    fun read(path: String): FileBrowserStorageUsage? = runCatching {
        val stat = StatFs(path)
        FileBrowserStorageUsage(
            totalBytes = stat.blockCountLong * stat.blockSizeLong,
            availableBytes = stat.availableBlocksLong * stat.blockSizeLong,
        )
    }.getOrNull()
}
