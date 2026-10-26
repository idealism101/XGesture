package com.slideindex.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FloatBallPositionMode
import com.slideindex.app.settings.FloatBallSide
import com.slideindex.app.settings.FloatBallStyleType
import com.slideindex.app.ui.miuix.theme.resolveAppAccentArgb

/**
 * Unified native float-ball surface for idle + drag (FV FloatIconView-style).
 * Parent positions this view; [bind] only manages appearance and inner layout gravity.
 */
internal class FloatBallIconView(context: Context) : FrameLayout(context) {

    private enum class ContentMode {
        STATIC,
        GIF,
        BUILTIN,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val staticImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val gifView = FloatBallGifView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val builtinAnimView = FloatBallBuiltinAnimView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val gifPlayer = FloatBallGifPlayer()

    private var contentMode = ContentMode.STATIC
    private var boundKey: String? = null
    /** 最近一次 bind 的设置：图片读取失败时用来渲染兜底球。 */
    private var boundSettings: AppSettings? = null
    private var ownedBitmap: Bitmap? = null
    private var slideshowUris: List<String> = emptyList()
    private var slideshowIndex = 0
    private var gifDecodeToken = 0
    private var gifSequenceHandle: FloatBallGifSequenceCache.Handle? = null
    /** 平台 [AnimatedImageDrawable] 播放中的 GIF；非空时由它在渲染线程推进动画。 */
    private var gifDrawable: AnimatedImageDrawable? = null

    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (contentMode != ContentMode.STATIC || slideshowUris.size <= 1) return
            slideshowIndex = (slideshowIndex + 1) % slideshowUris.size
            showStaticUri(slideshowUris[slideshowIndex], currentOpacity())
            mainHandler.postDelayed(this, SLIDESHOW_INTERVAL_MS)
        }
    }

    private var currentOpacityValue = 1f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        addView(staticImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gifView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(builtinAnimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gifPlayer.attach(gifView)
        showContentMode(ContentMode.STATIC)
    }

    fun bind(
        settings: AppSettings,
        activeSide: FloatBallSide,
        styleGeneration: Int
    ) {
        val density = resources.displayMetrics.density
        val ballSize = FloatBallLayout.ballSize(settings, density)
        // 「不处理」：按原图比例显示，不裁剪也不套圆。只有自定义图片会走到这里。
        val noCrop = FloatBallLayout.usesOriginalImageGeometry(settings)
        val opacity = settings.floatBallOpacity.coerceIn(0f, 1f)
        currentOpacityValue = opacity
        alpha = opacity
        boundSettings = settings

        val key = buildBindKey(settings, activeSide, styleGeneration, ballSize)
        if (key == boundKey) {
            applyChildGravity(settings, activeSide)
            applyBallClip(circular = !noCrop)
            // 兜底：内置动画的画面完全依赖 View 内部的"当前样式"。窗口被摘掉再挂回（取词/截图）时，
            // 即使样式没变也必须把样式重新喂给它，否则它没有内容可画（球会变透明）。setStyle 同样式是幂等的。
            if (FloatBallBuiltinAnimCatalog.isBuiltinAnimated(settings.floatBallStyleType)) {
                builtinAnimView.setStyle(settings.floatBallStyleType)
            }
            return
        }
        boundKey = key
        applyChildGravity(settings, activeSide)
        applyBallClip(circular = !noCrop)
        staticImage.scaleType = if (noCrop) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP

        when (settings.floatBallStyleType) {
            FloatBallStyleType.ANIMATED_PLANE,
            FloatBallStyleType.ANIMATED_PULSE,
            FloatBallStyleType.ANIMATED_ORBIT,
            -> showBuiltin(settings.floatBallStyleType, opacity)

            FloatBallStyleType.GIF -> showGif(settings, opacity, ballSize.heightPx)

            FloatBallStyleType.CUSTOM_IMAGE -> {
                stopSlideshow()
                showStaticUri(settings.floatBallCustomImageUri, opacity)
            }

            FloatBallStyleType.SLIDESHOW -> {
                val uris = settings.floatBallSlideshowUris
                if (uris.isEmpty()) {
                    stopSlideshow()
                    showStaticBitmap(FloatBallDragVisualRenderer.render(context, settings), owns = true)
                } else {
                    slideshowUris = uris
                    slideshowIndex = 0
                    showStaticUri(uris.first(), opacity)
                    stopSlideshow()
                    if (uris.size > 1) {
                        mainHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL_MS)
                    }
                }
            }

            FloatBallStyleType.DEFAULT -> {
                stopSlideshow()
                showStaticBitmap(FloatBallDragVisualRenderer.render(context, settings), owns = true)
            }
        }
    }

    fun setDragging(dragging: Boolean) {
        when (contentMode) {
            ContentMode.GIF -> gifPlayer.setPaused(false)
            ContentMode.BUILTIN -> builtinAnimView.setPaused(false)
            ContentMode.STATIC -> Unit
        }
    }

    fun release() {
        stopSlideshow()
        stopGifDrawable()
        gifDecodeToken++
        gifPlayer.release()
        releaseGifSequence()
        builtinAnimView.releaseAnimation()
        releaseOwnedBitmap()
        staticImage.setImageDrawable(null)
        gifView.clearFrame()
        boundKey = null
        boundSettings = null
        slideshowUris = emptyList()
    }

    private fun buildBindKey(
        settings: AppSettings,
        activeSide: FloatBallSide,
        styleGeneration: Int,
        ballSize: FloatBallSize
    ): String = buildString {
        append(styleGeneration)
        append('|')
        append(settings.floatBallStyleType.name)
        append('|')
        append(activeSide.name)
        append('|')
        append(ballSize.widthPx)
        append('x')
        append(ballSize.heightPx)
        append('|')
        // ⚠️ 这里必须是**解析后的强调色**（含"自定义色开关 / 动态取色 / 色板 / 深浅"的影响），
        // 不能只放 themeColorArgb：开关一翻而色值没变时，键不变 → 球不会重画 → 还是旧颜色。
        append(resolveAppAccentArgb(context, settings))
        append('|')
        append(settings.floatBallOpacity)
        append('|')
        append(settings.floatBallCustomImageUri)
        append('|')
        // 不处理开关与宽高比都要进键：否则改了开关/换了图（同 URI 不会被换，但比例会变）球不重绘。
        append(settings.floatBallCustomImageNoCrop)
        append('|')
        append(settings.floatBallCustomImageAspect)
        append('|')
        append(settings.floatBallGifUri)
        append('|')
        append(settings.floatBallSlideshowUris.joinToString(","))
    }

    private fun currentOpacity(): Float = currentOpacityValue

    private fun showContentMode(mode: ContentMode) {
        contentMode = mode
        staticImage.visibility = if (mode == ContentMode.STATIC) View.VISIBLE else View.GONE
        gifView.visibility = if (mode == ContentMode.GIF) View.VISIBLE else View.GONE
        builtinAnimView.visibility = if (mode == ContentMode.BUILTIN) View.VISIBLE else View.GONE
    }

    private fun showBuiltin(styleType: FloatBallStyleType, opacity: Float) {
        stopSlideshow()
        stopGifDrawable()
        gifPlayer.release()
        releaseGifSequence()
        releaseOwnedBitmap()
        showContentMode(ContentMode.BUILTIN)
        builtinAnimView.alpha = opacity
        builtinAnimView.setStyle(styleType)
        builtinAnimView.setPaused(false)
    }

    private fun showGif(settings: AppSettings, opacity: Float, decodePx: Int) {
        stopSlideshow()
        stopGifDrawable()
        releaseOwnedBitmap()
        builtinAnimView.releaseAnimation()
        showContentMode(ContentMode.GIF)
        gifView.alpha = opacity

        val uri = settings.floatBallGifUri
        if (uri.isBlank() || !FloatBallStyleAssetStore.canRead(context, uri)) {
            showStaticBitmap(FloatBallDragVisualRenderer.render(context, settings), owns = true)
            return
        }

        // 实验路径：交给平台 AnimatedImageDrawable —— 动画帧由渲染线程推进，
        // 不再由 Handler 每 33ms 唤醒主线程 invalidate。失败则退回自绘播放器。
        if (showGifDrawable(uri, opacity)) return

        val token = ++gifDecodeToken
        Thread {
            // 共享缓存：同一 (uri, 尺寸) 只解码一份，不再每次进 GIF 样式都解一整只。
            val handle = FloatBallGifSequenceCache.acquire(context, uri, decodePx)
            mainHandler.post {
                if (token != gifDecodeToken) {
                    handle?.close()
                    return@post
                }
                if (handle != null) {
                    retainGifSequence(handle)
                    FloatBallGifDragSnapshot.update(uri, decodePx, handle.sequence)
                    gifPlayer.setSequence(handle.sequence)
                    gifPlayer.setPaused(false)
                    gifPlayer.start()
                } else {
                    showStaticBitmap(FloatBallDragVisualRenderer.render(context, settings), owns = true)
                }
            }
        }.start()
    }

    /**
     * 用平台 [AnimatedImageDrawable] 播放 GIF（实验路径）。
     *
     * 与自绘播放器的区别：帧推进在渲染线程完成，主线程不再每 33ms 被 Handler 唤醒，
     * 也不经过 [FloatBallGifView] 的逐帧 `invalidate`。失败时返回 false，退回原路径。
     */
    private fun showGifDrawable(uri: String, opacity: Float): Boolean {
        val drawable = runCatching {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))
                ?.use { it.readBytes() }
                ?: return false
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        }.onFailure { android.util.Log.w("FloatBallIconView", "gif decode failed", it) }
            .getOrNull() as? AnimatedImageDrawable ?: return false
        val started = runCatching {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }.isSuccess
        if (!started) return false
        gifDrawable?.stop()
        gifDrawable = drawable
        staticImage.setImageDrawable(drawable)
        staticImage.alpha = opacity
        showContentMode(ContentMode.STATIC)
        android.util.Log.i("FloatBallIconView", "gif path=AnimatedImageDrawable")
        return true
    }

    /** 停掉平台 GIF 播放器；离开 GIF 样式或视图释放时调用。 */
    private fun stopGifDrawable() {
        gifDrawable?.stop()
        gifDrawable = null
        if (staticImage.drawable is AnimatedImageDrawable) {
            staticImage.setImageDrawable(null)
        }
    }

    private fun showStaticUri(uri: String, opacity: Float) {
        if (uri.isBlank()) {
            releaseOwnedBitmap()
            staticImage.setImageDrawable(null)
            staticImage.alpha = opacity
            showContentMode(ContentMode.STATIC)
            return
        }
        val bitmap = FloatBallImageLoader.loadBitmap(context, uri)
        if (bitmap != null) {
            showStaticBitmap(bitmap, owns = false)
            staticImage.alpha = opacity
        } else {
            // 图片读不出来（文件被删/权限失效）：画兜底球而不是留一块透明窗口。
            // 「不处理」模式下窗口可能很宽，全透明会变成"看不见但点得到"的死区。
            val fallbackSettings = boundSettings
            if (fallbackSettings != null) {
                showStaticBitmap(
                    FloatBallDragVisualRenderer.render(context, fallbackSettings),
                    owns = true
                )
                staticImage.alpha = opacity
            } else {
                releaseOwnedBitmap()
                staticImage.setImageDrawable(null)
                staticImage.alpha = opacity
                showContentMode(ContentMode.STATIC)
            }
        }
    }

    private fun showStaticBitmap(bitmap: Bitmap, owns: Boolean) {
        stopGifDrawable()
        gifPlayer.release()
        releaseGifSequence()
        builtinAnimView.releaseAnimation()
        releaseOwnedBitmap()
        ownedBitmap = if (owns) bitmap else null
        staticImage.setImageBitmap(bitmap)
        staticImage.alpha = currentOpacity()
        showContentMode(ContentMode.STATIC)
    }

    private fun releaseOwnedBitmap() {
        ownedBitmap?.recycle()
        ownedBitmap = null
    }

    /** 记下当前持有的共享序列；换样式时把旧的还回缓存——这才是真正释放的时机。 */
    private fun retainGifSequence(handle: FloatBallGifSequenceCache.Handle) {
        if (gifSequenceHandle === handle) return
        gifSequenceHandle?.close()
        gifSequenceHandle = handle
    }

    private fun releaseGifSequence() {
        gifSequenceHandle?.close()
        gifSequenceHandle = null
    }

    private fun stopSlideshow() {
        mainHandler.removeCallbacks(slideshowRunnable)
    }

    private fun applyChildGravity(settings: AppSettings, activeSide: FloatBallSide) {
        val gravity = layoutGravity(settings, activeSide)
        listOf(staticImage, gifView, builtinAnimView).forEach { child ->
            val params = child.layoutParams as LayoutParams
            params.gravity = gravity
            child.layoutParams = params
        }
    }

    /**
     * 球体裁剪形状。
     *
     * - 圆形样式（默认）：内切椭圆轮廓，方形窗口下就是正圆。
     * - 「不处理」：彻底不裁，图片四角/边缘按原样显示。
     */
    private fun applyBallClip(circular: Boolean) {
        if (!circular) {
            listOf(staticImage, gifView, builtinAnimView).forEach { child ->
                child.clipToOutline = false
                child.outlineProvider = null
            }
            return
        }
        val clipper = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
            }
        }
        listOf(staticImage, gifView, builtinAnimView).forEach { child ->
            child.clipToOutline = true
            child.outlineProvider = clipper
        }
    }

    private fun layoutGravity(settings: AppSettings, activeSide: FloatBallSide): Int {
        if (settings.floatBallPositionMode == FloatBallPositionMode.CUSTOM) {
            return Gravity.CENTER
        }
        return when (activeSide) {
            FloatBallSide.LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
            FloatBallSide.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
        }
    }

    companion object {
        private const val SLIDESHOW_INTERVAL_MS = 3_000L
    }
}
