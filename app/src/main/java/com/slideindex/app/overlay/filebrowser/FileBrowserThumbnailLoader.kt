package com.slideindex.app.overlay.filebrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可直读图片的缩略图加载器。
 *
 * Root/Shizuku 才能读的路径**没有**在这里做 base64 中转：那会把滚动变成大量 su 调用。
 * 这类路径继续用类型图标，等做“按需缩略图”时再接 SuFile/base64。
 */
internal object FileBrowserThumbnailLoader {
    private const val MAX_EDGE_PX = 128
    private const val MAX_SOURCE_BYTES = 12L * 1024L * 1024L

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(path: String, modifiedEpochSeconds: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            if (path.isEmpty()) return@withContext null
            val key = "$path:$modifiedEpochSeconds"
            cache.get(key)?.let { return@withContext it }
            val file = File(path)
            if (!file.isFile || !file.canRead()) return@withContext null
            if (file.length() > MAX_SOURCE_BYTES) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE_PX * 2 ||
                bounds.outHeight / sample > MAX_EDGE_PX * 2
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
                ?: return@withContext null
            cache.put(key, bitmap)
            bitmap
        }
}
