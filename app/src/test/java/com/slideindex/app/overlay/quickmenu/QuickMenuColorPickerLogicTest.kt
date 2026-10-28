package com.slideindex.app.overlay.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「屏幕取色」纯逻辑的单测。
 *
 * 重点是**几何换算**：视图坐标 ↔ 位图像素、以及放大镜该取哪一块源像素、准星像素落在放大区哪里。
 * 这几处错了都不会崩，只会"准星指着这儿、颜色却是隔壁那颗"——在 6 倍放大下差一个像素肉眼也
 * 判断不出是谁算错了，所以必须靠单测钉住。
 */
class QuickMenuColorPickerLogicTest {

    // 常见机型的整屏截图（1080×2400）与容器（540×1200，正好一半）。
    private val bitmap = QuickMenuColorPickerGeometry.BitmapSize(width = 1080, height = 2400)
    private val viewport = QuickMenuColorPickerGeometry.Viewport(width = 540, height = 1200)

    // ------------------------------------------------------------ 视图坐标 ↔ 位图像素

    @Test
    fun scale_isHalfForHalfSizeViewport() {
        assertEquals(0.5f, QuickMenuColorPickerGeometry.scaleX(viewport, bitmap), 1e-6f)
        assertEquals(0.5f, QuickMenuColorPickerGeometry.scaleY(viewport, bitmap), 1e-6f)
    }

    @Test
    fun viewOffsetToPixel_scalesBothAxes() {
        val center = QuickMenuColorPickerGeometry.viewOffsetToPixel(
            viewX = 270f,
            viewY = 600f,
            viewport = viewport,
            bitmap = bitmap,
        )
        assertEquals(540, center.x)
        assertEquals(1200, center.y)
        // 容器内坐标原样带回：准星要画在手指位置上，不能画到换算后的像素位置上。
        assertEquals(270f, center.viewX, 1e-6f)
        assertEquals(600f, center.viewY, 1e-6f)
    }

    @Test
    fun viewOffsetToPixel_mapsCornersToCornerPixels() {
        val topLeft = QuickMenuColorPickerGeometry.viewOffsetToPixel(0f, 0f, viewport, bitmap)
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)

        // 右下角：容器右下角**不属于**最后一个像素的中心，而是它的边缘，所以夹到最后一颗。
        val bottomRight = QuickMenuColorPickerGeometry.viewOffsetToPixel(540f, 1200f, viewport, bitmap)
        assertEquals(bitmap.width - 1, bottomRight.x)
        assertEquals(bitmap.height - 1, bottomRight.y)
    }

    @Test
    fun viewOffsetToPixel_clampsOutsideOffsets() {
        // 拖动时手指会跑到容器外面，不能让坐标变成负数或越界（越界 getPixel 会抛）。
        val outside = QuickMenuColorPickerGeometry.viewOffsetToPixel(-500f, 9999f, viewport, bitmap)
        assertEquals(0, outside.x)
        assertEquals(bitmap.height - 1, outside.y)
    }

    @Test
    fun viewOffsetToPixel_degenerateSizesDoNotCrash() {
        val emptyViewport = QuickMenuColorPickerGeometry.viewOffsetToPixel(
            viewX = 10f,
            viewY = 10f,
            viewport = QuickMenuColorPickerGeometry.Viewport(0, 0),
            bitmap = bitmap,
        )
        assertEquals(0, emptyViewport.x)
        assertEquals(0, emptyViewport.y)

        val emptyBitmap = QuickMenuColorPickerGeometry.viewOffsetToPixel(
            viewX = 10f,
            viewY = 10f,
            viewport = viewport,
            bitmap = QuickMenuColorPickerGeometry.BitmapSize(0, 0),
        )
        assertEquals(0, emptyBitmap.x)
        assertEquals(0, emptyBitmap.y)
    }

    @Test
    fun viewOffsetToPixel_roundsToNearestPixel() {
        // 视口 100px、位图 1000px（10 倍）时，1 个视图像素 = 10 个位图像素。
        val small = QuickMenuColorPickerGeometry.Viewport(width = 100, height = 100)
        val big = QuickMenuColorPickerGeometry.BitmapSize(width = 1000, height = 1000)
        assertEquals(150, QuickMenuColorPickerGeometry.viewOffsetToPixel(15f, 0f, small, big).x)
        // 15.5 → 155（roundToInt 四舍五入），不是截断成 150。
        assertEquals(155, QuickMenuColorPickerGeometry.viewOffsetToPixel(15.5f, 0f, small, big).x)
    }

    // ------------------------------------------------------------ 全屏 Fit 映射

    @Test
    fun fitRect_coversViewportWhenAspectMatches() {
        val rect = QuickMenuColorPickerGeometry.fitRect(viewport, bitmap)
        assertEquals(0f, rect.left, 1e-6f)
        assertEquals(0f, rect.top, 1e-6f)
        assertEquals(540f, rect.width, 1e-6f)
        assertEquals(1200f, rect.height, 1e-6f)
    }

    @Test
    fun fitRect_letterboxesTallAndWideViewports() {
        // 屏幕比截图更高（窗口含刘海/导航栏那一截）：上下各一条黑边，图按宽度铺满。
        val tall = QuickMenuColorPickerGeometry.fitRect(
            viewport = QuickMenuColorPickerGeometry.Viewport(width = 1080, height = 2600),
            bitmap = bitmap,
        )
        assertEquals(0f, tall.left, 1e-6f)
        assertEquals(1080f, tall.width, 1e-6f)
        assertEquals(2400f, tall.height, 1e-6f)
        assertEquals(100f, tall.top, 1e-6f)

        // 屏幕比截图更宽：左右各一条黑边。
        val wide = QuickMenuColorPickerGeometry.fitRect(
            viewport = QuickMenuColorPickerGeometry.Viewport(width = 1200, height = 2400),
            bitmap = bitmap,
        )
        assertEquals(60f, wide.left, 1e-6f)
        assertEquals(1080f, wide.width, 1e-6f)
        assertEquals(2400f, wide.height, 1e-6f)
        assertEquals(0f, wide.top, 1e-6f)
    }

    @Test
    fun fitOffsetToPixel_mapsThroughLetterbox() {
        val wide = QuickMenuColorPickerGeometry.Viewport(width = 1200, height = 2400)
        // 屏幕中心 = 图的中心 = 位图中心（540, 1200）。
        val center = QuickMenuColorPickerGeometry.fitOffsetToPixel(600f, 1200f, wide, bitmap)
        assertEquals(540, center.x)
        assertEquals(1200, center.y)
        // 落在左侧黑边里的点夹到图的左边缘，不能算出负数（负坐标 getPixel 会抛）。
        val inLeftBar = QuickMenuColorPickerGeometry.fitOffsetToPixel(10f, 1200f, wide, bitmap)
        assertEquals(0, inLeftBar.x)
        assertEquals(1200, inLeftBar.y)
    }

    @Test
    fun fitOffsetToPixel_degenerateSizesDoNotCrash() {
        val empty = QuickMenuColorPickerGeometry.fitOffsetToPixel(
            viewX = 10f,
            viewY = 10f,
            viewport = QuickMenuColorPickerGeometry.Viewport(0, 0),
            bitmap = bitmap,
        )
        assertEquals(0, empty.x)
        assertEquals(0, empty.y)
    }

    // ------------------------------------------------------------ 放大镜源矩形

    @Test
    fun loupeSource_isCenteredWhenFarFromEdges() {
        val source = QuickMenuColorPickerGeometry.loupeSource(x = 500, y = 500, span = 6, bitmap = bitmap)
        // 跨度 6 = 中心左边 3 颗 + 右边 3 颗，**不能变成 7 颗**（两头各自夹一次就会多一颗，
        // 放大倍数跟着差一档，而且"6 像素窗口"这个设定就名不副实了）。
        assertEquals(497, source.x)
        assertEquals(497, source.y)
        assertEquals(6, source.width)
        assertEquals(6, source.height)
    }

    @Test
    fun loupeSource_clampsToTopLeftWithoutShrinkingTheWindow() {
        val source = QuickMenuColorPickerGeometry.loupeSource(x = 0, y = 0, span = 6, bitmap = bitmap)
        // 采样点左边没有像素：窗口起点夹到 0，但**宽度不变**（不补黑边，也不缩窗口——
        // 缩了倍数就跳，手指一往边上挪镜子里所见大小会变）。准星会自然偏到可见范围的中间。
        assertEquals(0, source.x)
        assertEquals(0, source.y)
        assertEquals(6, source.width)
        assertEquals(6, source.height)
    }

    @Test
    fun loupeSource_clampsToBottomRight() {
        val source = QuickMenuColorPickerGeometry.loupeSource(
            x = bitmap.width - 1,
            y = bitmap.height - 1,
            span = 6,
            bitmap = bitmap,
        )
        assertEquals(bitmap.width, source.x + source.width)
        assertEquals(bitmap.height, source.y + source.height)
        assertEquals(6, source.width)
        assertEquals(6, source.height)
        assertTrue(source.x >= 0)
        assertTrue(source.y >= 0)
    }

    @Test
    fun loupeSource_spanIsClampedToAtLeastOnePixel() {
        val source = QuickMenuColorPickerGeometry.loupeSource(x = 10, y = 10, span = 0, bitmap = bitmap)
        assertEquals(1, source.width)
        assertEquals(1, source.height)
        assertEquals(10, source.x)
        assertEquals(10, source.y)
    }

    @Test
    fun loupeSource_spanNeverExceedsTheBitmap() {
        val tiny = QuickMenuColorPickerGeometry.BitmapSize(width = 3, height = 5)
        val source = QuickMenuColorPickerGeometry.loupeSource(x = 1, y = 1, span = 16, bitmap = tiny)
        // 跨度大于位图时按位图来：否则源矩形会有大半天在图外。
        assertEquals(3, source.width)
        assertEquals(5, source.height)
        assertEquals(0, source.x)
        assertEquals(0, source.y)
    }

    @Test
    fun maxSpanFor_keepsWindowInsideBothLensAndBitmap() {
        // 正常整屏截图 + 264px 的镜子：跨度上限就是镜子的 264，不受位图限制。
        assertEquals(264, QuickMenuColorPickerGeometry.maxSpanFor(bitmap, 264))
        // 镜子比位图还小时，按位图（这里 width=3 是瓶颈）。
        assertEquals(3, QuickMenuColorPickerGeometry.maxSpanFor(QuickMenuColorPickerGeometry.BitmapSize(3, 900), 264))
        // 退化输入不返回 0/负数（会让 scale 变成 Infinity）。
        assertTrue(QuickMenuColorPickerGeometry.maxSpanFor(QuickMenuColorPickerGeometry.BitmapSize(0, 0), 264) >= 1)
        assertTrue(QuickMenuColorPickerGeometry.maxSpanFor(bitmap, 0) >= 1)
    }

    // ------------------------------------------------------------ 准星像素在放大区的位置

    /**
     * 界面上真实的一组参数：镜子 ~264px（96dp @ 2.75x）、首选跨度 16、允许区间 14..22。
     *
     * [QuickMenuColorPickerGeometry.alignedSpanAndLens] 会做两件事：
     * ① 把跨度强制成**奇数**（16 → 17）；② 把镜子边长上取整到跨度的整数倍（264 → 272）。
     * 于是 `scale = 272/17 = 16` 是整数，且采样点恰好是窗口正中那颗。
     */
    private val alignedLens: Pair<Int, Int> = QuickMenuColorPickerGeometry.alignedSpanAndLens(
        bitmap = bitmap,
        lensSidePx = 264,
        span = 16,
        minSpan = 14,
        maxSpan = 22,
    )

    @Test
    fun alignedSpanAndLens_usesOddSpanAndWholeMultipleLens() {
        // 首选 16（偶数）被强制成 17（奇数），镜子 264 上取整到 17 的倍数 → 272，倍数 = 16（整数）。
        assertEquals(17 to 272, alignedLens)
        val content = QuickMenuColorPickerGeometry.loupeContent(
            x = 500,
            y = 900,
            span = alignedLens.first,
            lensSide = alignedLens.second,
            bitmap = bitmap,
        )
        assertEquals(16f, content.scale, 1e-6f)
    }

    @Test
    fun alignedSpanAndLens_alwaysReturnsOddSpanWithinRange() {
        // 跨度先夹进 [min, max]，再强制奇数；镜子边长始终是它的整数倍。
        for (requested in intArrayOf(-5, 0, 1, 14, 15, 16, 17, 22, 100)) {
            for ((min, max) in listOf(14 to 22, 15 to 21, 4 to 8)) {
                val (span, lens) = QuickMenuColorPickerGeometry.alignedSpanAndLens(bitmap, 264, requested, min, max)
                assertTrue("requested=$requested min=$min max=$max span=$span", span % 2 == 1)
                assertTrue("requested=$requested span=$span", span >= 1)
                assertTrue("requested=$requested span=$span max=$max", span <= max.coerceAtLeast(min))
                assertEquals("requested=$requested span=$span", 0, lens % span)
                assertTrue("lens=$lens", lens >= span)
            }
        }
    }

    @Test
    fun alignedSpanAndLens_neverExceedsTheBitmap() {
        // 位图比跨度还小时，跨度不得超过位图（否则源矩形必然越界）；且仍要保证是奇数。
        val tiny = QuickMenuColorPickerGeometry.BitmapSize(10, 10)
        val (span, lens) = QuickMenuColorPickerGeometry.alignedSpanAndLens(tiny, 264, 16, 14, 22)
        assertEquals(9, span)
        assertEquals(0, lens % span)
        // 退化输入不返回 0（0 会让 scale 变成 Infinity）。
        assertTrue(QuickMenuColorPickerGeometry.alignLensSide(0, 0) >= 1)
        assertTrue(QuickMenuColorPickerGeometry.alignLensSide(-5, 16) >= 16)
    }

    @Test
    fun loupeContent_crosshairSitsExactlyAtLensCenter() {
        // 奇数跨度 + 整数倍镜子 → 采样点就是窗口正中那颗，于是准星**精确**落在镜子正中，
        // 不需要任何容差。这正是"跨度必须取奇数"要换来的东西。
        val (span, lensSide) = alignedLens
        val content = QuickMenuColorPickerGeometry.loupeContent(
            x = 500,
            y = 900,
            span = span,
            lensSide = lensSide,
            bitmap = bitmap,
        )
        assertEquals(lensSide / 2f, content.crosshairX, 1e-3f)
        assertEquals(lensSide / 2f, content.crosshairY, 1e-3f)
        // 准星居中与"位图铺满镜子"是同时成立的：目标偏移正好是 0。
        assertEquals(0, content.dstOffsetX)
        assertEquals(0, content.dstOffsetY)
    }

    @Test
    fun loupeContent_evenSpanPushesCrosshairOffCenterByHalfAPixel() {
        // 反向钉住上面那条规则：跨度给成偶数时（窗口左右邻域差一颗），准星会固定偏 `scale/2`。
        // 界面不会传偶数跨度（`alignedSpanAndLens` 强制奇数），但几何本身必须表现得可预测。
        val lensSide = QuickMenuColorPickerGeometry.alignLensSide(264, 16) // 272
        val content = QuickMenuColorPickerGeometry.loupeContent(
            x = 500,
            y = 900,
            span = 16,
            lensSide = lensSide,
            bitmap = bitmap,
        )
        assertEquals(17f, content.scale, 1e-6f)
        assertEquals(lensSide / 2f + content.scale / 2f, content.crosshairX, 1e-3f)
    }

    @Test
    fun loupeContent_crosshairTracksThePixelThatIsActuallyDrawn() {
        // 最要紧的一致性：准星坐标必须等于**那颗像素实际被画到的中心**。两者一旦脱钩，
        // 就会出现"十字线画在正中、放大区正中却是隔壁那颗像素"——取色器最致命的 bug。
        // 这里刻意把奇数/偶数跨度、对齐/未对齐的镜子都遍历一遍。
        for (span in intArrayOf(4, 6, 15, 16, 17, 21, 22)) {
            for (lensSide in intArrayOf(56, 264)) {
                for (x in intArrayOf(0, 1, 2, 3, 500, bitmap.width - 2, bitmap.width - 1)) {
                    for (y in intArrayOf(0, 1, 900, bitmap.height - 1)) {
                        val content = QuickMenuColorPickerGeometry.loupeContent(x, y, span, lensSide, bitmap)
                        val drawnCenterX = content.dstOffsetX + (x - content.src.x + 0.5f) * content.scale
                        val drawnCenterY = content.dstOffsetY + (y - content.src.y + 0.5f) * content.scale
                        assertEquals("lens=$lensSide x=$x span=$span", drawnCenterX, content.crosshairX, 1e-3f)
                        assertEquals("lens=$lensSide y=$y span=$span", drawnCenterY, content.crosshairY, 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun loupeContent_fillsTheLensAndCentersTheCrosshair() {
        // 界面真实参数（奇数跨度 + 整数倍镜子）下，遍历各类采样点（含四个角）钉住：
        //   ① 位图**严丝合缝铺满**镜子（目标偏移 0、覆盖区间恰好 [0, lens]，不露缝）；
        //   ② 窗口没被夹时准星**精确**居中；
        //   ③ 采样那一颗像素完整可见；④ 源矩形完全在位图内；⑤ 准星始终在镜子内。
        for (span in intArrayOf(15, 17, 21)) {
            val lensSide = QuickMenuColorPickerGeometry.alignLensSide(264, span)
            for (x in intArrayOf(0, 1, 2, 3, 500, bitmap.width - 2, bitmap.width - 1)) {
                for (y in intArrayOf(0, 1, 900, bitmap.height - 1)) {
                    val content = QuickMenuColorPickerGeometry.loupeContent(x, y, span, lensSide, bitmap)
                    val label = "lens=$lensSide span=$span x=$x y=$y"
                    // ① 铺满镜子：目标偏移为 0，位图覆盖区间恰好就是 [0, lens]，两侧都不露缝。
                    assertEquals("$label dstX=${content.dstOffsetX}", 0, content.dstOffsetX)
                    assertEquals("$label dstY=${content.dstOffsetY}", 0, content.dstOffsetY)
                    assertEquals("$label 宽", lensSide.toFloat(), content.src.width * content.scale, 1e-3f)
                    assertEquals("$label 高", lensSide.toFloat(), content.src.height * content.scale, 1e-3f)
                    // ② 采样像素完整落在镜子内。
                    val pixelLeft = content.dstOffsetX + (x - content.src.x) * content.scale
                    val pixelTop = content.dstOffsetY + (y - content.src.y) * content.scale
                    assertTrue("$label pixelLeft=$pixelLeft", pixelLeft >= 0f)
                    assertTrue("$label pixelTop=$pixelTop", pixelTop >= 0f)
                    assertTrue("$label pixelRight", pixelLeft + content.scale <= lensSide + 1e-3f)
                    assertTrue("$label pixelBottom", pixelTop + content.scale <= lensSide + 1e-3f)
                    // ③ 源矩形完全在位图内。
                    assertTrue(content.src.x >= 0 && content.src.y >= 0)
                    assertTrue(content.src.x + content.src.width <= bitmap.width)
                    assertTrue(content.src.y + content.src.height <= bitmap.height)
                    // ④ 准星在镜子内。
                    assertTrue("$label crossX", content.crosshairX in 0f..lensSide.toFloat())
                    assertTrue("$label crossY", content.crosshairY in 0f..lensSide.toFloat())
                    // ⑤ 窗口没被夹（离边缘超过半个跨度）时准星精确居中。贴边时窗口被夹回图内，
                    //    准星会朝那一侧镜边移——图外没有像素，这是如实反映"已经到边了"。
                    val unclampedX = x - span / 2 >= 0 && x + span / 2 < bitmap.width
                    val unclampedY = y - span / 2 >= 0 && y + span / 2 < bitmap.height
                    if (unclampedX) {
                        assertEquals("$label crossX 居中", lensSide / 2f, content.crosshairX, 1e-3f)
                    }
                    if (unclampedY) {
                        assertEquals("$label crossY 居中", lensSide / 2f, content.crosshairY, 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun loupeContent_clampedWindowShiftsCrosshairTowardTheEdgeItHit() {
        // 贴到左上角：窗口只能从 (0,0) 开始，采样点退到窗口最左/最上，准星因此贴近镜子的左上。
        // 关键是不能跑出镜子，也不能因此让位图露缝。
        val span = 17
        val lensSide = QuickMenuColorPickerGeometry.alignLensSide(264, span)
        val topLeft = QuickMenuColorPickerGeometry.loupeContent(0, 0, span, lensSide, bitmap)
        assertEquals(0, topLeft.dstOffsetX)
        assertEquals(0, topLeft.dstOffsetY)
        assertTrue("crossX=${topLeft.crosshairX}", topLeft.crosshairX in 0f..lensSide.toFloat())
        assertTrue("crossY=${topLeft.crosshairY}", topLeft.crosshairY in 0f..lensSide.toFloat())
        // 贴左边缘时准星位于镜子左侧「半个跨度」之内（即正好是首像素块的中心）。
        assertEquals(topLeft.crosshairX, topLeft.scale / 2f, 1e-3f)

        // 贴右下角时对称地贴近镜子右下。
        val bottomRight = QuickMenuColorPickerGeometry.loupeContent(
            x = bitmap.width - 1,
            y = bitmap.height - 1,
            span = span,
            lensSide = lensSide,
            bitmap = bitmap,
        )
        assertEquals(0, bottomRight.dstOffsetX)
        assertEquals(0, bottomRight.dstOffsetY)
        assertTrue("crossX=${bottomRight.crosshairX}", bottomRight.crosshairX in 0f..lensSide.toFloat())
        assertTrue("crossY=${bottomRight.crosshairY}", bottomRight.crosshairY in 0f..lensSide.toFloat())
        assertEquals(bottomRight.crosshairX, lensSide - bottomRight.scale / 2f, 1e-3f)
    }

    @Test
    fun loupeContent_degenerateInputsDoNotCrash() {
        val empty = QuickMenuColorPickerGeometry.loupeContent(
            x = 0,
            y = 0,
            span = 6,
            lensSide = 56,
            bitmap = QuickMenuColorPickerGeometry.BitmapSize(0, 0),
        )
        assertEquals(0, empty.src.width)
        assertEquals(0, empty.src.height)
        assertEquals(28f, empty.crosshairX, 1e-6f)
        assertEquals(28f, empty.crosshairY, 1e-6f)
    }

    // ------------------------------------------------------------ 放大倍数

    @Test
    fun zoomFor_keepsMagnificationInFourToEightTimes() {
        // 56dp 的镜子在 xhdpi(2x) 上是 112px。
        val zoom = QuickMenuColorPickerGeometry.zoomFor(
            loupeSidePx = 112f,
            span = 6,
            minSpan = 4,
            maxSpan = 8,
        )
        // 跨度 6 被夹在 4..8 之间不变，所以倍数是 112/6 ≈ 18.7；界面那边再反推回跨度 6。
        assertEquals(112f / 6f, zoom, 1e-4f)
        // 不管传什么跨度，反推回来的跨度都在 4..8 之间 → 倍数也就在 4~8 像素的观感区间里。
        for (requested in intArrayOf(-3, 0, 1, 4, 6, 8, 40)) {
            val pixels = (112f / QuickMenuColorPickerGeometry.zoomFor(112f, requested, 4, 8))
                .toInt()
                .coerceIn(4, 8)
            assertTrue("requested=$requested", pixels in 4..8)
        }
    }

    @Test
    fun zoomFor_degenerateInputsFallBackToOne() {
        assertEquals(1f, QuickMenuColorPickerGeometry.zoomFor(0f, 6, 4, 8), 1e-6f)
        assertEquals(1f, QuickMenuColorPickerGeometry.zoomFor(-10f, 6, 4, 8), 1e-6f)
        // min > max 时按 min 处理，不返回 NaN/负数。
        val zoom = QuickMenuColorPickerGeometry.zoomFor(56f, 6, 8, 4)
        assertTrue(zoom > 0f)
    }

    // ------------------------------------------------------------ 颜色格式

    @Test
    fun hsv_matchesKnownValues() {
        assertEquals("hsv(0, 100%, 100%)", QuickMenuColorFormats.toHsv(0xFFFF0000.toInt()))
        assertEquals("hsv(120, 100%, 100%)", QuickMenuColorFormats.toHsv(0xFF00FF00.toInt()))
        assertEquals("hsv(240, 100%, 100%)", QuickMenuColorFormats.toHsv(0xFF0000FF.toInt()))
        assertEquals("hsv(0, 0%, 100%)", QuickMenuColorFormats.toHsv(0xFFFFFFFF.toInt()))
        assertEquals("hsv(0, 0%, 0%)", QuickMenuColorFormats.toHsv(0xFF000000.toInt()))
        // 灰阶：s=0、v=50%
        assertEquals("hsv(0, 0%, 50%)", QuickMenuColorFormats.toHsv(0xFF808080.toInt()))
    }

    @Test
    fun hsv_alphaDoesNotAffectHue() {
        assertEquals(
            QuickMenuColorFormats.toHsv(0xFFFF8000.toInt()),
            QuickMenuColorFormats.toHsv(0x80FF8000.toInt()),
        )
    }

    @Test
    fun all_returnsFiveFormatsInStableOrder() {
        val argb = 0xFF3366CC.toInt()
        val formats = QuickMenuColorFormats.all(argb)
        assertEquals(listOf("HEX", "HEX(A)", "RGB", "HSL", "HSV"), formats.map { it.first })
        // 取值直接复用已有件，不在这里另算一份。
        assertEquals(QuickMenuColorMath.toHex(argb), formats[0].second)
        assertEquals(QuickMenuColorMath.toHexWithAlpha(argb), formats[1].second)
        assertEquals(QuickMenuColorMath.toRgb(argb), formats[2].second)
        assertEquals(QuickMenuColorMath.toHsl(argb), formats[3].second)
        assertEquals(QuickMenuColorFormats.toHsv(argb), formats[4].second)
        assertEquals("#3366CC", formats[0].second)
        assertEquals("#FF3366CC", formats[1].second)
    }

    // ------------------------------------------------------------ 取色历史

    @Test
    fun sanitize_dropsFullyTransparentColors() {
        // alpha=0 的位置（截图里未绘制的区域）取出来没有意义，不该污染历史。
        val colors = listOf(0x00FF0000, 0xFF00FF00.toInt(), 0x00000000)
        assertEquals(listOf(0xFF00FF00.toInt()), RecentColorsCodec.sanitize(colors))
    }

    @Test
    fun sanitize_keepsMostRecentOccurrenceAndOrder() {
        val first = 0xFF112233.toInt()
        val second = 0xFF445566.toInt()
        // 重复颜色只保留最先出现的那次（调用方保证"最近的在前"）。
        assertEquals(listOf(first, second), RecentColorsCodec.sanitize(listOf(first, second, first)))
    }

    @Test
    fun sanitize_truncatesToMaxEntries() {
        val many = (0 until 40).map { 0xFF000000.toInt() or it }
        assertEquals(RecentColorsCodec.MAX_ENTRIES, RecentColorsCodec.sanitize(many).size)
        assertEquals(12, RecentColorsCodec.MAX_ENTRIES)
    }

    @Test
    fun record_putsNewColorFirstAndDeduplicates() {
        val old = listOf(0xFFAAAAAA.toInt(), 0xFFBBBBBB.toInt())
        val updated = RecentColorsCodec.record(old, 0xFFBBBBBB.toInt())
        // 已存在 → 提到最前，不产生重复条目。
        assertEquals(listOf(0xFFBBBBBB.toInt(), 0xFFAAAAAA.toInt()), updated)
        assertEquals(2, updated.size)
    }

    @Test
    fun record_dropsTransparentSample() {
        val old = listOf(0xFFAAAAAA.toInt())
        assertEquals(old, RecentColorsCodec.record(old, 0x00FFFFFF))
    }
}
