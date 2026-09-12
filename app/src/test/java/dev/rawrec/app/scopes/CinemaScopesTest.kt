package dev.rawrec.app.scopes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaScopesTest {

    @Test
    fun `focus peaking highlights sharp high-contrast edges`() {
        val w = 8
        val h = 8
        val luma = ByteArray(w * h) { 50.toByte() }

        // Create a sharp vertical edge in the center column
        for (y in 0 until h) {
            luma[y * w + 4] = 200.toByte()
        }

        val highlightColor = 0xFF00FF00.toInt() // Neon Green
        val peaked = CinemaScopes.applyFocusPeaking(luma, w, h, threshold = 50, highlightArgb = highlightColor)

        // Edge pixel (x=4, y=4) should have high laplacian and be highlighted
        assertEquals(highlightColor, peaked[4 * w + 4])

        // Uniform background pixel (x=1, y=1) should remain grayscale
        assertNotEquals(highlightColor, peaked[1 * w + 1])
    }

    @Test
    fun `false color maps exposure zones to standard colors`() {
        assertEquals("Crushed black must be purple", 0xFF800080.toInt(), CinemaScopes.mapLumaToFalseColor(2))
        assertEquals("18% gray must be green", 0xFF00FF00.toInt(), CinemaScopes.mapLumaToFalseColor(105))
        assertEquals("Skin tone must be pink", 0xFFFFC0CB.toInt(), CinemaScopes.mapLumaToFalseColor(145))
        assertEquals("Blown highlight must be red", 0xFFFF0000.toInt(), CinemaScopes.mapLumaToFalseColor(254))
    }

    @Test
    fun `zebras stripes apply only to highlights above threshold`() {
        val w = 16
        val h = 16
        val luma = ByteArray(w * h)
        // Set left half to normal exposure (100) and right half to blown highlight (250)
        for (y in 0 until h) {
            for (x in 0 until w) {
                luma[y * w + x] = if (x < 8) 100.toByte() else 250.toByte()
            }
        }

        val zebras = CinemaScopes.applyZebras(luma, w, h, thresholdIre = 95, stripeWidth = 4)

        // Left pixel should be normal grayscale
        val leftPixel = zebras[5 * w + 2]
        val leftLuma = leftPixel and 0xFF
        assertEquals(100, leftLuma)

        // Right pixels should be either black or white zebra stripes
        val rightPixel = zebras[5 * w + 12]
        assertTrue(rightPixel == 0xFF000000.toInt() || rightPixel == 0xFFFFFFFF.toInt())
    }

    @Test
    fun `computeHistogram populates 256 bins and detects clipping`() {
        val pixels = IntArray(100)
        // 50 black pixels (clipping)
        for (i in 0 until 50) {
            pixels[i] = 0xFF000000.toInt()
        }
        // 50 white pixels (clipping)
        for (i in 50 until 100) {
            pixels[i] = 0xFFFFFFFF.toInt()
        }

        val hist = CinemaScopes.computeHistogram(pixels)
        assertEquals(100, hist.totalSamples)
        assertEquals(50, hist.lumaBins[0])
        assertEquals(50, hist.lumaBins[255])
        assertEquals(50.0, hist.shadowClippedPercent, 0.01)
        assertEquals(50.0, hist.highlightClippedPercent, 0.01)
    }

    @Test
    fun `aspect framing calculates letterbox and pillarbox correctly`() {
        // 2.39:1 on a 1920x1080 (16:9 = 1.777) screen -> Letterbox (top/bottom bars)
        val letterbox = CinemaScopes.calculateAspectFraming(1920f, 1080f, 2.39f)
        assertEquals(0f, letterbox.left, 0.01f)
        assertEquals(1920f, letterbox.right, 0.01f)
        assertTrue("Top margin should be > 0", letterbox.top > 0f)
        assertTrue("Bottom margin should be < 1080", letterbox.bottom < 1080f)

        // 4:3 on a 1920x1080 screen -> Pillarbox (left/right bars)
        val pillarbox = CinemaScopes.calculateAspectFraming(1920f, 1080f, 4f / 3f)
        assertEquals(0f, pillarbox.top, 0.01f)
        assertEquals(1080f, pillarbox.bottom, 0.01f)
        assertTrue("Left margin should be > 0", pillarbox.left > 0f)
        assertTrue("Right margin should be < 1920", pillarbox.right < 1920f)
    }

    @Test
    fun `argbToLuma converts standard primary colors to correct luma`() {
        // Pure White: 0xFFFFFFFF -> Luma 255
        // Pure Black: 0xFF000000 -> Luma 0
        // Pure Green: 0xFF00FF00 -> Luma ~183 (BT.709 green weight)
        val pixels = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF00FF00.toInt()
        )
        val luma = CinemaScopes.argbToLuma(pixels, 3, 1)
        assertEquals(255.toByte(), luma[0])
        assertEquals(0.toByte(), luma[1])
        assertEquals(182.toByte(), luma[2]) // (183 * 255) >> 8 = 182
    }

    @Test
    fun `applyFocusPeakingTransparent highlights sharp edges and keeps background transparent`() {
        val w = 8
        val h = 8
        val luma = ByteArray(w * h) { 30.toByte() }

        // Create high-contrast edge down column 4
        for (y in 0 until h) {
            luma[y * w + 4] = 220.toByte()
        }

        val highlight = 0xFF00FF00.toInt() // Neon green
        val out = CinemaScopes.applyFocusPeakingTransparent(luma, w, h, threshold = 40, highlightArgb = highlight)

        // Edge pixel (x=4, y=4) must be highlighted green
        assertEquals(highlight, out[4 * w + 4])

        // Uniform non-edge pixel (x=1, y=1) must be 0 (0x00000000 transparent)
        assertEquals(0, out[1 * w + 1])
    }

    @Test
    fun `applyZebrasTransparent renders stripes on highlights and leaves normal exposure transparent`() {
        val w = 16
        val h = 16
        val luma = ByteArray(w * h)
        // Left half = normal exposure (120), right half = highlight (250)
        for (y in 0 until h) {
            for (x in 0 until w) {
                luma[y * w + x] = if (x < 8) 120.toByte() else 250.toByte()
            }
        }

        val zebras = CinemaScopes.applyZebrasTransparent(luma, w, h, thresholdIre = 95, stripeWidth = 4)

        // Normal exposure pixel (x=2, y=5) must be 0 (transparent)
        assertEquals(0, zebras[5 * w + 2])

        // Blown highlight pixel (x=12, y=5) must be black or white stripe
        val stripePixel = zebras[5 * w + 12]
        assertTrue(stripePixel == 0xFF000000.toInt() || stripePixel == 0xFFFFFFFF.toInt())
    }
}
