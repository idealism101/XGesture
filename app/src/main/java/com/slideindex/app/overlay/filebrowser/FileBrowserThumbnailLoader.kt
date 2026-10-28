package com.slideindex.app.overlay.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可直读文件的缩略图加载器：图片、视频首帧、APK 图标、PDF 首页。
 *
 * Root/Shizuku 才能读的路径**没有**在这里做 base64 中转：那会把滚动变成大量 su 调用。
 * 这类路径继续用类型图标，等做“按需缩略图”时再接 SuFile/base64。
 */
internal object FileBrowserThumbnailLoader {
    private const val MAX_EDGE_PX = 128
    private const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(
        context: Context,
        path: String,
        modifiedEpochSeconds: Long,
        kind: FileKind,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isEmpty()) return@withContext null
        val key = "$kind|$path:$modifiedEpochSeconds"
        cache.get(key)?.let { return@withContext it }
        val file = File(path)
        if (!file.isFile || !file.canRead()) return@withContext null
        val bitmap = when (kind) {
            FileKind.IMAGE -> image(file)
            FileKind.VIDEO -> video(file)
            FileKind.APK -> apk(context, file)
            FileKind.PDF -> pdf(file)
            else -> null
        } ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    private fun image(file: File): Bitmap? {
        if (file.length() > MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
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
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
    }

    private fun video(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )?.let { scale(it) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun apk(context: Context, file: File): Bitmap? {
        val packageManager = context.packageManager
        val info = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            0,
        ) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val drawable = runCatching { appInfo.loadIcon(packageManager) }.getOrNull()
            ?: return null
        return runCatching {
            drawable.toBitmap(MAX_EDGE_PX, MAX_EDGE_PX)
        }.getOrNull()
    }

    private fun pdf(file: File): Bitmap? {
        val descriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return null
        val renderer = runCatching { PdfRenderer(descriptor) }.getOrNull()
            ?: run {
                runCatching { descriptor.close() }
                return null
            }
        return try {
            if (renderer.pageCount <= 0) return null
            renderer.openPage(0).use { page ->
                val width = page.width.coerceAtLeast(1)
                val height = page.height.coerceAtLeast(1)
                val scale = (MAX_EDGE_PX.toFloat() / maxOf(width, height)).coerceAtMost(1f)
                val bitmap = Bitmap.createBitmap(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.RGB_565,
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun scale(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= MAX_EDGE_PX) return source
        val ratio = MAX_EDGE_PX.toFloat() / maxEdge
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }
}
