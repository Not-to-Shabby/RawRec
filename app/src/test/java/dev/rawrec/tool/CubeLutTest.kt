package dev.rawrec.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CubeLutTest {

    @Test
    fun `parses standard 3D identity cube LUT and samples accurately`() {
        val cubeText = """
            # Standard 3D Identity Cube
            TITLE "Identity 3x3x3"
            LUT_3D_SIZE 3
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            
            # b=0
            0.0 0.0 0.0
            0.5 0.0 0.0
            1.0 0.0 0.0
            0.0 0.5 0.0
            0.5 0.5 0.0
            1.0 0.5 0.0
            0.0 1.0 0.0
            0.5 1.0 0.0
            1.0 1.0 0.0
            
            # b=0.5
            0.0 0.0 0.5
            0.5 0.0 0.5
            1.0 0.0 0.5
            0.0 0.5 0.5
            0.5 0.5 0.5
            1.0 0.5 0.5
            0.0 1.0 0.5
            0.5 1.0 0.5
            1.0 1.0 0.5
            
            # b=1.0
            0.0 0.0 1.0
            0.5 0.0 1.0
            1.0 0.0 1.0
            0.0 0.5 1.0
            0.5 0.5 1.0
            1.0 0.5 1.0
            0.0 1.0 1.0
            0.5 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLut.parse(ByteArrayInputStream(cubeText.toByteArray(Charsets.UTF_8)))
        assertEquals("Identity 3x3x3", lut.title)
        assertEquals(3, lut.size)
        assertTrue(lut.is3D)

        val out = DoubleArray(3)
        // Test corner
        lut.sample(0.0, 0.0, 0.0, out)
        assertEquals(0.0, out[0], 0.001)
        assertEquals(0.0, out[1], 0.001)
        assertEquals(0.0, out[2], 0.001)

        // Test trilinear interpolation at quarter-steps
        lut.sample(0.25, 0.75, 0.50, out)
        assertEquals(0.25, out[0], 0.01)
        assertEquals(0.75, out[1], 0.01)
        assertEquals(0.50, out[2], 0.01)
    }

    @Test
    fun `parses 1D inversion cube LUT`() {
        val cubeText = """
            TITLE "Invert 1D"
            LUT_1D_SIZE 2
            1.0 1.0 1.0
            0.0 0.0 0.0
        """.trimIndent()

        val lut = CubeLut.parse(ByteArrayInputStream(cubeText.toByteArray(Charsets.UTF_8)))
        assertEquals("Invert 1D", lut.title)
        assertEquals(2, lut.size)
        assertTrue(!lut.is3D)

        val out = DoubleArray(3)
        lut.sample(0.2, 0.7, 0.0, out)
        assertEquals(0.8, out[0], 0.01)
        assertEquals(0.3, out[1], 0.01)
        assertEquals(1.0, out[2], 0.01)
    }

    @Test
    fun `ColorScience gradePixel with custom 3D LUT applies trilinear mapping`() {
        // Red-boost LUT (maps G and B to 0)
        val cubeText = """
            TITLE "Red Only"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 0.0 0.0
            1.0 0.0 0.0
        """.trimIndent()

        val lut = CubeLut.parse(ByteArrayInputStream(cubeText.toByteArray(Charsets.UTF_8)))
        val argb = ColorScience.gradePixel(
            rNorm = 0.64, // sqrt(0.64) = 0.8
            gNorm = 0.50,
            bNorm = 0.50,
            uNorm = 0.5,
            vNorm = 0.5,
            profile = ColorScience.ToneProfile.CUSTOM_LUT,
            enableVignette = false,
            customLut = lut
        )

        val ir = (argb ushr 16) and 0xFF
        val ig = (argb ushr 8) and 0xFF
        val ib = argb and 0xFF

        assertTrue("Red channel should be strongly preserved ($ir > 180)", ir > 180)
        assertEquals("Green channel should be mapped to 0", 0, ig)
        assertEquals("Blue channel should be mapped to 0", 0, ib)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid or empty cube file missing size header`() {
        val badText = "TITLE \"Bad\"\n0.1 0.2 0.3"
        CubeLut.parse(ByteArrayInputStream(badText.toByteArray(Charsets.UTF_8)))
    }
}
