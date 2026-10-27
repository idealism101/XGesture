@file:Suppress("DEPRECATION")

package com.slideindex.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import kotlin.math.roundToInt

/**
 * 悬浮球 GIF 的软件绘制面（对齐 FV 的做法）。
 *
 * 球的窗口本身仍走 HWUI，只有这一块 surface 由独立渲染线程
 * `lockCanvas/drawBitmap/unlockCanvasAndPost` 软件绘制：省掉 HWUI 的 Vulkan 提交、
 * 逐帧大纹理上传，以及逐帧 `invalidate` 唤醒主线程。
 */
internal class FloatBallGifView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val circlePath = Path()
    private val circleRect = RectF()
    private val bitmapDst = Rect()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    @Volatile private var cachedBitmap: Bitmap? = null
    @Volatile private var streamingMovie: Movie? = null
    @Volatile private var streamingElapsedMs = 0
    @Volatile private var streamingOutW = 0
    @Volatile private var streamingOutH = 0
    @Volatile private var streamingMode = false

    @Volatile private var surfaceReady = false
    @Volatile private var renderScheduled = false
    @Volatile private var frameAlpha = 1f

    private val renderThread = HandlerThread("FloatBallGifSurface").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    private val renderRunnable = Runnable { renderFrame() }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
    }

    /**
     * 悬浮球不透明度。
     *
     * SurfaceView 的画面在独立图层里，`View.setAlpha` 管不到它，所以只能画的时候乘进去。
     */
    fun setFrameAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        if (frameAlpha == clamped) return
        frameAlpha = clamped
        scheduleRender()
    }

    fun showCachedFrame(bitmap: Bitmap) {
        streamingMode = false
        streamingMovie = null
        cachedBitmap = bitmap
        scheduleRender()
    }

    fun showStreamingFrame(movie: Movie, elapsedMs: Int, outW: Int, outH: Int) {
        streamingMode = true
        cachedBitmap = null
        streamingMovie = movie
        streamingElapsedMs = elapsedMs
        streamingOutW = outW
        streamingOutH = outH
        scheduleRender()
    }

    fun clearFrame() {
        streamingMode = false
        streamingMovie = null
        cachedBitmap = null
        scheduleRender()
    }

    /** Current visible frame for drag shell — never mutates internal state. */
    fun snapshotCurrentFrame(): Bitmap? {
        val outW = width.coerceAtLeast(1)
        val outH = height.coerceAtLeast(1)
        if (outW <= 0 || outH <= 0) return null
        return if (!streamingMode) {
            cachedBitmap
                ?.takeIf { !it.isRecycled }
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val movie = streamingMovie ?: return null
            val durationMs = movie.duration().takeIf { it > 0 } ?: 1_000
            val bitmap = createBitmap(
                streamingOutW.coerceAtLeast(1),
                streamingOutH.coerceAtLeast(1)
            )
            val canvas = Canvas(bitmap)
            movie.setTime(streamingElapsedMs % durationMs)
            val scaleX = streamingOutW.toFloat() / movie.width().coerceAtLeast(1)
            val scaleY = streamingOutH.toFloat() / movie.height().coerceAtLeast(1)
            canvas.scale(scaleX, scaleY)
            movie.draw(canvas, 0f, 0f)
            bitmap
        }
    }

    /** 软件 surface 不走 HWUI 图层，这里保持幂等，仅为兼容旧调用点。 */
    fun setAnimating(@Suppress("UNUSED_PARAMETER") animating: Boolean) = Unit

    /** 球不可见（窗口隐藏 / 从窗口树摘掉）或熄屏时，逐帧重绘没有意义。 */
    fun canAnimateFrame(): Boolean {
        if (!isShown || windowVisibility != VISIBLE) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive != false
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        renderHandler.post(renderRunnable)
    }

    private fun renderFrame() {
        renderScheduled = false
        if (!surfaceReady) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        bitmapDst.set(0, 0, w, h)
        circleRect.set(0f, 0f, w.toFloat(), h.toFloat())
        circlePath.reset()
        circlePath.addOval(circleRect, Path.Direction.CW)

        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val alphaInt = (frameAlpha * 255f).roundToInt().coerceIn(0, 255)
            val layer = if (alphaInt < 255) {
                canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), alphaInt)
            } else {
                -1
            }
            canvas.withClip(circlePath) {
                if (streamingMode) {
                    val movie = streamingMovie ?: return@withClip
                    val durationMs = movie.duration().takeIf { it > 0 } ?: 1_000
                    movie.setTime(streamingElapsedMs % durationMs)
                    val scaleX = streamingOutW.toFloat() / movie.width().coerceAtLeast(1)
                    val scaleY = streamingOutH.toFloat() / movie.height().coerceAtLeast(1)
                    withScale(scaleX, scaleY) {
                        movie.draw(this, 0f, 0f)
                    }
                } else {
                    val bitmap = cachedBitmap ?: return@withClip
                    if (bitmap.isRecycled) return@withClip
                    drawBitmap(bitmap, null, bitmapDst, bitmapPaint)
                }
            }
            if (layer >= 0) canvas.restoreToCount(layer)
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        scheduleRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        scheduleRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onDetachedFromWindow() {
        surfaceReady = false
        renderHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
