package dev.rawrec.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Transform-convention tests for ViewfinderMath.contentTransform /
 * presentationScale. The matrix maps VIEW coordinates (buffer pre-stretched
 * anisotropically to the view) — pinned from AOSP TextureView.java + Open
 * Camera's configureTransform; entries a = k*bw/viewW, d = k*bh/viewH make
 * the final view-px-per-buffer-px equal k in BOTH axes (square sensor
 * pixels). Regression guard for the 139px right black bar + zoom measured
 * 2026-08-30 (portrait 1220x2712, 1920x1440 buffer) when buffer-pixel
 * scales were written directly as matrix entries.
 */
class ViewfinderTransformTest {

    private val vw = 1220f
    private val vh = 2712f
    private val bw = 1920f
    private val bh = 1440f

    /** Map a view-space point through the row-major 3x3. */
    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val xp = m[0] * x + m[1] * y + m[2]
        val yp = m[3] * x + m[4] * y + m[5]
        return xp to yp
    }

    /**
     * Extent of the effW x effH content crop after the transform. The crop
     * occupies a centered (effW/bw*viewW) x (effH/bh*viewH) rect in the
     * pre-stretched view space; map those corners through the matrix.
     * Returns {widthPx, heightPx, x0, y0}.
     */
    private fun cropExtent(m: FloatArray, effW: Float, effH: Float): FloatArray {
        val preW = effW / bw * vw
        val preH = effH / bh * vh
        val (x0, y0) = map(m, vw / 2f - preW / 2f, vh / 2f - preH / 2f)
        val (x1, y1) = map(m, vw / 2f + preW / 2f, vh / 2f + preH / 2f)
        return floatArrayOf(x1 - x0, y1 - y0, x0, y0)
    }

    @Test
    fun `fill covers the whole view - no right gap`() {
        // k = max(1220/1920, 2712/1440) = 1.8833 -> crop 3616 x 2712 view
        // px, centered: view fully covered.
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
        assertEquals(1.8833f, k, 1e-3f)
        val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, 0)
        val e = cropExtent(m, bw, bh)
        assertEquals(3616f, e[0], 0.5f)
        assertEquals(2712f, e[1], 0.5f)
        assertTrue("content starts left of view", e[2] <= 0.5f)
        assertTrue("content covers right edge", e[2] + e[0] >= vw - 0.5f)
    }

    @Test
    fun `buffer pixels stay square - anisotropic pre-stretch cancelled`() {
        // Final view-px-per-buffer-px must equal k in both axes for uniform modes.
        // STRETCH is intentionally non-square (anamorphic) and is tested separately.
        for (mode in ViewfinderMath.ScaleMode.entries.filter { it != ViewfinderMath.ScaleMode.STRETCH }) {
            val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, mode)
            val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, 0)
            val perBufX = (vw / bw) * m[0]
            val perBufY = (vh / bh) * m[4]
            assertEquals("mode=$mode x-axis", k, perBufX, 1e-3f)
            assertEquals("mode=$mode y-axis", k, perBufY, 1e-3f)
        }
    }

    @Test
    fun `stretch transform maps content corners exactly to view corners`() {
        // Per-axis scales: the effW x effH crop fills the whole view, no crop.
        // 2.39 band = full width, cropped height (effH = bw/2.39).
        val effH = bw / 2.39f
        val m = ViewfinderMath.contentTransformStretch(vw, vh, bw, bh, bw, effH, 0)
        // Band size in pre-stretched view px: (effW/bw*vw) x (effH/bh*vh).
        val bandVw = effW(of = bw)
        val bandVh = effH / bh * vh
        // Band top-left in pre-stretched view coords maps to view origin (0,0);
        // band bottom-right maps to view corner (vw,vh) — whole band fills the view.
        val bandTop = (bh - effH) / 2f / bh * vh
        val (x0, y0) = map(m, 0f, bandTop)
        val (x1, y1) = map(m, vw, bandTop + bandVh)
        assertEquals(0f, x0, 0.5f)
        assertEquals(0f, y0, 0.5f)
        assertEquals(vw, x1, 0.5f)
        assertEquals(vh, y1, 0.5f)
        // View center is a fixed point (centered composition).
        val (cx, cy) = map(m, vw / 2f, vh / 2f)
        assertEquals(vw / 2f, cx, 0.5f)
        assertEquals(vh / 2f, cy, 0.5f)
    }

    /** Band width in pre-stretched view px. */
    private fun effW(of: Float): Float = of / bw * vw

    @Test
    fun `stretch transform with full buffer is identity`() {
        // Full-buffer stretch = the TextureView default anamorphic fill.
        val m = ViewfinderMath.contentTransformStretch(vw, vh, bw, bh, bw, bh, 0)
        assertEquals(1f, m[0], 1e-4f)
        assertEquals(1f, m[4], 1e-4f)
        assertEquals(0f, m[2], 1e-3f)
        assertEquals(0f, m[5], 1e-3f)
    }

    @Test
    fun `activeFramingRectStretch letterboxes wide bands and pillarboxes squares`() {
        // 2712x1220 view, 2.39 band: full width, centered height band
        val wide = ViewfinderMath.activeFramingRectStretch(2712f, 1220f, 2.39f)
        assertEquals(2712f, wide[2] - wide[0], 0.5f)
        assertEquals(2712f / 2.39f, wide[3] - wide[1], 0.5f)
        assertEquals((1220f - 2712f / 2.39f) / 2f, wide[1], 0.5f)

        // 1:1 square pillarboxes centered
        val square = ViewfinderMath.activeFramingRectStretch(2712f, 1220f, 1.0f)
        assertEquals(1220f, square[3] - square[1], 0.5f)
        assertEquals(1220f, square[2] - square[0], 0.5f)
        assertEquals((2712f - 1220f) / 2f, square[0], 0.5f)

        // Null limit = whole view
        val full = ViewfinderMath.activeFramingRectStretch(2712f, 1220f, null)
        assertEquals(0f, full[0], 1e-3f)
        assertEquals(0f, full[1], 1e-3f)
        assertEquals(2712f, full[2], 1e-3f)
        assertEquals(1220f, full[3], 1e-3f)
    }

    @Test
    fun `fit letterboxes vertically with symmetric bars`() {
        // k = min = 0.6354 -> crop 1220 x 915: full width, bars 898.5 top
        // and bottom (4:3 content in a 9:16 window).
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FIT)
        assertEquals(0.6354f, k, 1e-3f)
        val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, 0)
        val e = cropExtent(m, bw, bh)
        assertEquals(1220f, e[0], 0.5f)
        assertEquals(915f, e[1], 0.5f)
        assertEquals(898.5f, e[3], 0.5f)                 // top bar
        assertEquals(vh - 898.5f, e[3] + e[1], 0.5f)     // symmetric bottom
    }

    @Test
    fun `aspect-limited fit produces the widescreen band centered`() {
        // 2.39:1 limit: effH = 1920/2.39 = 803.35; FIT k = 0.6354 ->
        // band 1220 x 510.5 at y = 1100.8.
        val effH = bw / 2.39f
        val k = ViewfinderMath.presentationScale(vw, vh, bw, effH, ViewfinderMath.ScaleMode.FIT)
        assertEquals(0.6354f, k, 1e-3f)
        val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, effH, k, 0)
        val e = cropExtent(m, bw, effH)
        assertEquals(1220f, e[0], 0.5f)
        assertEquals(510.5f, e[1], 0.5f)
        assertEquals(1100.8f, e[3], 0.5f)
    }

    @Test
    fun `view center is a fixed point for any rotation`() {
        for (r in listOf(0, 90, 180, 270)) {
            val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
            val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, r)
            val (cx, cy) = map(m, vw / 2f, vh / 2f)
            assertEquals("r=$r cx", vw / 2f, cx, 0.5f)
            assertEquals("r=$r cy", vh / 2f, cy, 0.5f)
        }
    }

    @Test
    fun `rotation preserves area - determinant equals a*d`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
        val a = k * bw / vw
        val d = k * bh / vh
        for (r in listOf(0, 90, 180, 270)) {
            val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, r)
            val det = abs(m[0] * m[4] - m[1] * m[3])
            assertEquals("r=$r", a * d, det, 0.5f)
        }
    }

    @Test
    fun `regression - old buffer-space entries are not produced`() {
        // Old bug: a = d = 1.883 written directly -> content ended at
        // x = 1100 leaving a 139px right bar. The correct FILL matrix must
        // cover [0..1220] horizontally.
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
        val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, bw, bh, k, 0)
        val (x0, _) = map(m, 0f, 0f)
        val (x1, _) = map(m, vw, 0f)
        assertTrue(x0 <= 0.5f)
        assertTrue(x1 >= vw - 0.5f)
    }

    // --- orientation-aware presentation (r=90: view dims swap for cover/fit) ---

    @Test
    fun `effectiveCrop stays buffer aligned - wide aspects cut a horizontal band`() {
        // 2.39 / 16:9 cut a centered height band; 4:3 limit on 4:3 content
        // returns full buffer (ratio not greater than limit).
        assertEquals(1920f to (1920f / 2.39f), ViewfinderMath.effectiveCrop(bw, bh, 2.39f))
        assertEquals(1920f to 1080f, ViewfinderMath.effectiveCrop(bw, bh, 16f / 9f))
        assertEquals(1920f to 1440f, ViewfinderMath.effectiveCrop(bw, bh, 4f / 3f))
        assertEquals(1920f to 1440f, ViewfinderMath.effectiveCrop(bw, bh, null))
    }

    @Test
    fun `image presentation ignores device rotation - chassis locked`() {
        // The viewfinder IMAGE never rotates (camera-body rule): production
        // calls presentationScale without the rotation arg, so orientation
        // cannot alter the band geometry. The rotating framing OUTLINE is an
        // overlay concern, not this math.
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, 2.39f)
        val k = ViewfinderMath.presentationScale(vw, vh, effW, effH, ViewfinderMath.ScaleMode.FIT)
        val kRotatedArg = ViewfinderMath.presentationScale(vw, vh, effW, effH, ViewfinderMath.ScaleMode.FIT, 90)
        // production uses the default (no swap):
        assertEquals(0.6354f, k, 1e-3f)
        // the swap overload still exists for callers that opt in, and differs:
        org.junit.Assert.assertNotEquals(k, kRotatedArg)
    }

    @Test
    fun `portrait and 180 are unchanged by the rotation-aware scale`() {
        // Regression: r=0 (and 180) must not swap — identical k to the
        // rotation-less overload used before.
        for (mode in ViewfinderMath.ScaleMode.entries) {
            val kNoRot = ViewfinderMath.presentationScale(vw, vh, bw, bh, mode, 0)
            val kDefault = ViewfinderMath.presentationScale(vw, vh, bw, bh, mode)
            assertEquals(kNoRot, kDefault, 1e-6f)
            val k180 = ViewfinderMath.presentationScale(vw, vh, bw, bh, mode, 180)
            assertEquals(kNoRot, k180, 1e-6f)
        }
    }

    @Test
    fun `square pixels preserved with the rotation overload too`() {
        // For callers that DO opt into swapped presentation (not production
        // today), the per-axis entries must still cancel the pre-stretch:
        // a*vw/bw == d*vh/bh == k, and |det| == a*d (no shear).
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, 2.39f)
        val k = ViewfinderMath.presentationScale(vw, vh, effW, effH, ViewfinderMath.ScaleMode.FIT, 90)
        val a = k * bw / vw
        val d = k * bh / vh
        assertEquals(k, a * vw / bw, 1e-3f)
        assertEquals(k, d * vh / bh, 1e-3f)
        val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, effW, effH, k, 90)
        val det = kotlin.math.abs(m[0] * m[4] - m[1] * m[3])
        assertEquals(a * d, det, 1e-2f)
    }

    // ─── presentationFor: representational default, immersive opt-in ───

    @Test
    fun `default presentation is FIT for every aspect mode`() {
        for (i in 0..4) {
            assertEquals(
                ViewfinderMath.ScaleMode.FIT,
                ViewfinderMath.presentationFor(aspectIndex = i)
            )
        }
    }

    @Test
    fun `effectiveCrop produces square dimensions for 1-1 aspect`() {
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, 1.0f)
        assertEquals(bh, effW, 1e-3f)
        assertEquals(bh, effH, 1e-3f)
    }

    @Test
    fun `autoRotation behavior across orientation modes`() {
        // CHASSIS_LOCKED: permanently 0°
        assertEquals(0, ViewfinderMath.autoRotation(90, 0, ViewfinderMath.OrientationMode.CHASSIS_LOCKED))
        assertEquals(0, ViewfinderMath.autoRotation(90, 90, ViewfinderMath.OrientationMode.CHASSIS_LOCKED))
        assertEquals(0, ViewfinderMath.autoRotation(90, 270, ViewfinderMath.OrientationMode.CHASSIS_LOCKED))

        // CINEMA_LANDSCAPE: permanently horizontal (270° for 90° back sensors across both landscape-left and landscape-right)
        assertEquals(270, ViewfinderMath.autoRotation(90, 0, ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE))
        assertEquals(270, ViewfinderMath.autoRotation(90, 90, ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE))
        assertEquals(270, ViewfinderMath.autoRotation(90, 270, ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE))
        // Front camera (270° sensor)
        assertEquals(90, ViewfinderMath.autoRotation(270, 90, ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE))
    }

    @Test
    fun `presentationScale and activeFramingRect with fillFraction from 0 to 1`() {
        val k0 = ViewfinderMath.presentationScale(vw, vh, bw, bh, 0f, 0)
        val kFit = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FIT, 0)
        assertEquals(kFit, k0, 1e-5f)

        val k1 = ViewfinderMath.presentationScale(vw, vh, bw, bh, 1f, 0)
        val kFill = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL, 0)
        assertEquals(kFill, k1, 1e-5f)

        val kHalf = ViewfinderMath.presentationScale(vw, vh, bw, bh, 0.5f, 0)
        assertEquals((kFit + kFill) / 2f, kHalf, 1e-4f)

        val r0 = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, k0, fillFraction = 0f)
        val rFit = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, kFit, scaleMode = ViewfinderMath.ScaleMode.FIT)
        for (i in 0..3) assertEquals(rFit[i], r0[i], 1e-4f)

        val r1 = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, k1, fillFraction = 1f)
        val rFill = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, k1, scaleMode = ViewfinderMath.ScaleMode.FILL)
        for (i in 0..3) assertEquals(rFill[i], r1[i], 1e-4f)

        val rHalf = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, kHalf, fillFraction = 0.5f)
        for (i in 0..3) assertEquals((rFit[i] + rFill[i]) / 2f, rHalf[i], 1e-3f)
    }

    @Test
    fun `activeFramingRect in native widescreen cinema landscape`() {
        val landW = 2712f
        val landH = 1220f
        val k = ViewfinderMath.presentationScale(landW, landH, bw, bh, ViewfinderMath.ScaleMode.FIT, 0)
        // In landscape FIT: k = min(2712/1920, 1220/1440) = min(1.4125, 0.8472) = 0.8472
        val rectFull = ViewfinderMath.activeFramingRect(
            viewW = landW, viewH = landH, bufW = bw, bufH = bh,
            aspectLimit = null, k = k, scaleMode = ViewfinderMath.ScaleMode.FIT,
            isLandscape = true, rotation = 0
        )
        // Centered horizontally with pillar lookaround bars:
        val expectedW = k * bw
        assertEquals(landH, rectFull[3] - rectFull[1], 0.5f)
        assertEquals(expectedW, rectFull[2] - rectFull[0], 0.5f)
        assertEquals((landW - expectedW) / 2f, rectFull[0], 0.5f)

        // 2.39:1 widescreen frame:
        val rect239 = ViewfinderMath.activeFramingRect(
            viewW = landW, viewH = landH, bufW = bw, bufH = bh,
            aspectLimit = 2.39f, k = k, scaleMode = ViewfinderMath.ScaleMode.FIT,
            isLandscape = true, rotation = 0
        )
        val effH239 = bw / 2.39f
        assertEquals(k * effH239, rect239[3] - rect239[1], 0.5f)
        assertEquals(expectedW, rect239[2] - rect239[0], 0.5f)
    }

    @Test
    fun `immersive fills across all aspect modes`() {
        for (i in 0..4) {
            assertEquals(
                ViewfinderMath.ScaleMode.FILL,
                ViewfinderMath.presentationFor(immersive = true, aspectIndex = i)
            )
        }
    }

    // ─── contentRect: the overlay guide's source of truth ───

    @Test
    fun `contentRect matches the transform's rendered extent`() {
        // The overlay derives guides from contentRect while the image is
        // rendered by contentTransform — the two must agree exactly or the
        // outline lands off the image edge.
        for (mode in ViewfinderMath.ScaleMode.entries) {
            val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, 2.39f)
            val k = ViewfinderMath.presentationScale(vw, vh, effW, effH, mode)
            val r = ViewfinderMath.contentRect(vw, vh, effW, effH, k)
            val m = ViewfinderMath.contentTransform(vw, vh, bw, bh, effW, effH, k, 0)
            val ext = cropExtent(m, effW, effH)
            assertEquals(ext[2], r[0], 0.5f) // left
            assertEquals(ext[3], r[1], 0.5f) // top
            assertEquals(ext[0], r[2] - r[0], 0.5f) // width
            assertEquals(ext[1], r[3] - r[1], 0.5f) // height
        }
    }

    @Test
    fun `contentRect FIT is centered with symmetric bars`() {
        // Portrait window 1220x2712, 4:3 buffer FIT -> image is 1220x915
        // centered vertically; bars are (2712-915)/2 = 898.5 each.
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, null)
        val k = ViewfinderMath.presentationScale(vw, vh, effW, effH, ViewfinderMath.ScaleMode.FIT)
        val r = ViewfinderMath.contentRect(vw, vh, effW, effH, k)
        assertEquals(0f, r[0], 1e-3f) // flush left
        assertEquals(vw, r[2] - r[0], 1e-3f) // full width
        assertEquals((vh - k * effH) / 2f, r[1], 1e-3f) // centered top
        assertEquals(r[1] + k * effH, r[3], 1e-3f) // bottom = top + height
        assertEquals(k * effW, r[2] - r[0], 1e-3f) // width = k*effW
    }

    @Test
    fun `contentRect FILL covers the window`() {
        // FILL on the tall window: k*effH >= vh (content taller than view).
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, null)
        val k = ViewfinderMath.presentationScale(vw, vh, effW, effH, ViewfinderMath.ScaleMode.FILL)
        val r = ViewfinderMath.contentRect(vw, vh, effW, effH, k)
        assertTrue(r[1] <= 0f && r[3] >= vh) // vertically covers
        assertEquals(k * effW, r[2] - r[0], 1e-3f) // width still k*effW
    }

    // ─── activeFramingRect: orientation-aware framing guides ───

    @Test
    fun `activeFramingRect in portrait matches contentRect`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FIT)
        val rPortrait = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, k, isLandscape = false)
        val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, 16f / 9f)
        val rExpected = ViewfinderMath.contentRect(vw, vh, effW, effH, k, 0)
        assertEquals(rExpected[0], rPortrait[0], 1e-3f)
        assertEquals(rExpected[1], rPortrait[1], 1e-3f)
        assertEquals(rExpected[2], rPortrait[2], 1e-3f)
        assertEquals(rExpected[3], rPortrait[3], 1e-3f)
    }

    @Test
    fun `activeFramingRect in landscape creates horizontal widescreen frame inside camera feed`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FIT)
        val rLandscape = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 16f / 9f, k, isLandscape = true)
        val imgH = k * bh // horizontal extent on landscape device
        val imgTop = (vh - imgH) / 2f
        val devH = imgH / (16f / 9f) // vertical extent on landscape device

        // Window Y spans [imgTop, imgTop + imgH]
        assertEquals(imgTop, rLandscape[1], 1e-3f)
        assertEquals(imgTop + imgH, rLandscape[3], 1e-3f)

        // Window X is centered with extent devH
        val expectedLeft = (vw - devH) / 2f
        assertEquals(expectedLeft, rLandscape[0], 1e-3f)
        assertEquals(expectedLeft + devH, rLandscape[2], 1e-3f)

        // Aspect ratio on landscape device (horizontal / vertical) is exactly 16:9
        val deviceW = rLandscape[3] - rLandscape[1]
        val deviceH = rLandscape[2] - rLandscape[0]
        assertEquals(16f / 9f, deviceW / deviceH, 1e-3f)
    }

    @Test
    fun `activeFramingRect in landscape 2_39 ratio matches on device`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FIT)
        val rLandscape = ViewfinderMath.activeFramingRect(vw, vh, bw, bh, 2.39f, k, isLandscape = true)
        val deviceW = rLandscape[3] - rLandscape[1]
        val deviceH = rLandscape[2] - rLandscape[0]
        assertEquals(2.39f, deviceW / deviceH, 1e-3f)
    }

    @Test
    fun `activeFramingRect with FILL mode fits within screen bounds in landscape`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
        // 16:9 on landscape screen (2712 x 1220)
        val r169 = ViewfinderMath.activeFramingRect(
            vw, vh, bw, bh, 16f / 9f, k,
            scaleMode = ViewfinderMath.ScaleMode.FILL, isLandscape = true
        )
        // Window X bounds are within [0, vw]
        assertTrue(r169[0] >= 0f && r169[2] <= vw)
        // Window Y bounds are within [0, vh]
        assertTrue(r169[1] >= 0f && r169[3] <= vh)
        val devW = r169[3] - r169[1]
        val devH = r169[2] - r169[0]
        assertEquals(16f / 9f, devW / devH, 1e-3f)
    }

    @Test
    fun `activeFramingRect with FILL mode fits within screen bounds in portrait`() {
        val k = ViewfinderMath.presentationScale(vw, vh, bw, bh, ViewfinderMath.ScaleMode.FILL)
        val r169 = ViewfinderMath.activeFramingRect(
            vw, vh, bw, bh, 16f / 9f, k,
            scaleMode = ViewfinderMath.ScaleMode.FILL, isLandscape = false
        )
        assertTrue(r169[0] >= 0f && r169[2] <= vw)
        assertTrue(r169[1] >= 0f && r169[3] <= vh)
        val w = r169[2] - r169[0]
        val h = r169[3] - r169[1]
        assertEquals(16f / 9f, w / h, 1e-3f)
    }
}
