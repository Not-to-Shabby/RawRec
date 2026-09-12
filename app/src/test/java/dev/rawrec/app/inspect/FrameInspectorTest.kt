package dev.rawrec.app.inspect

import dev.rawrec.app.container.Rvsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameInspectorTest {

    @Test
    fun `cfaNames maps all Bayer patterns correctly`() {
        assertEquals(listOf("R", "Gr", "Gb", "B"), FrameInspector.cfaNames(Rvsp.CFA_RGGB))
        assertEquals(listOf("Gr", "R", "B", "Gb"), FrameInspector.cfaNames(Rvsp.CFA_GRBG))
        assertEquals(listOf("Gb", "B", "R", "Gr"), FrameInspector.cfaNames(Rvsp.CFA_GBRG))
        assertEquals(listOf("B", "Gb", "Gr", "R"), FrameInspector.cfaNames(Rvsp.CFA_BGGR))
    }

    @Test
    fun `diagnose handles uniform frame as blank or clipped`() {
        val w = 64
        val h = 64
        val samples = ShortArray(w * h) { 64.toShort() }
        val diag = FrameInspector.diagnose(samples, w, h, Rvsp.CFA_RGGB, whiteLevel = 1023)

        assertEquals(64, diag.overallMin)
        assertEquals(64, diag.overallMax)
        assertEquals(4, diag.channels.size)
        assertEquals("BLANK/CLIPPED", diag.verdict)
    }

    @Test
    fun `diagnose computes channel statistics and correlation on smooth gradient`() {
        val w = 64
        val h = 64
        val samples = ShortArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                samples[y * w + x] = (100 + (x + y) * 5).coerceAtMost(1023).toShort()
            }
        }

        val diag = FrameInspector.diagnose(samples, w, h, Rvsp.CFA_BGGR, whiteLevel = 1023)
        assertTrue(diag.overallMin >= 100)
        assertTrue(diag.overallMax <= 1023)
        assertTrue("Smooth gradient should have high correlation", diag.neighborCorrelation > 0.8)
        assertEquals("OK", diag.verdict)
    }
}
