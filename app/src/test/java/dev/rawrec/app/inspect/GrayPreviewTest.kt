package dev.rawrec.app.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayPreviewTest {

    @Test
    fun `argb generates array matching requested dimensions`() {
        val w = 64
        val h = 48
        val outW = 32
        val outH = 24
        val samples = ShortArray(w * h) { 200.toShort() }

        val argb = GrayPreview.argb(
            samples, w, h,
            whiteLevel = 1023,
            blackLevel = 64,
            outW = outW,
            outH = outH
        )

        assertEquals(outW * outH, argb.size)
        // Alpha channel must be 0xFF
        for (pixel in argb) {
            val alpha = (pixel ushr 24) and 0xFF
            assertEquals(0xFF, alpha)
        }
    }

    @Test
    fun `argb scales black level to black and white level to white`() {
        val w = 4
        val h = 4
        val outW = 2
        val outH = 2

        val blackSamples = ShortArray(w * h) { 64.toShort() }
        val blackArgb = GrayPreview.argb(blackSamples, w, h, whiteLevel = 1023, blackLevel = 64, outW = outW, outH = outH)
        val blackLuma = blackArgb[0] and 0xFF
        assertEquals(0, blackLuma)

        val whiteSamples = ShortArray(w * h) { 1023.toShort() }
        val whiteArgb = GrayPreview.argb(whiteSamples, w, h, whiteLevel = 1023, blackLevel = 64, outW = outW, outH = outH)
        val whiteLuma = whiteArgb[0] and 0xFF
        assertEquals(255, whiteLuma)
    }
}
