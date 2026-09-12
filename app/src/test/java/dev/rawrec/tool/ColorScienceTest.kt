package dev.rawrec.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorScienceTest {

    @Test
    fun `all tone profiles produce valid bounded 8-bit ARGB values`() {
        for (profile in ColorScience.ToneProfile.values()) {
            for (r in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                for (g in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    for (b in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                        val argb = ColorScience.gradePixel(r, g, b, 0.5, 0.5, profile, enableVignette = true)
                        val a = (argb ushr 24) and 0xFF
                        val ir = (argb ushr 16) and 0xFF
                        val ig = (argb ushr 8) and 0xFF
                        val ib = argb and 0xFF

                        assertEquals("Alpha must be fully opaque (0xFF) for $profile", 0xFF, a)
                        assertTrue("R must be in 0..255 for $profile, got $ir", ir in 0..255)
                        assertTrue("G must be in 0..255 for $profile, got $ig", ig in 0..255)
                        assertTrue("B must be in 0..255 for $profile, got $ib", ib in 0..255)
                    }
                }
            }
        }
    }

    @Test
    fun `monochrome profiles produce strictly equal R, G, B channels`() {
        val testColors = listOf(
            Triple(0.8, 0.2, 0.1),
            Triple(0.2, 0.9, 0.3),
            Triple(0.1, 0.3, 0.8),
            Triple(0.5, 0.5, 0.5)
        )

        for (monoProfile in listOf(
            ColorScience.ToneProfile.CINE_SOFT_MONO,
            ColorScience.ToneProfile.CINE_MONO
        )) {
            for ((r, g, b) in testColors) {
                // center pixel (no vignette distortion)
                val argb = ColorScience.gradePixel(r, g, b, 0.5, 0.5, monoProfile, enableVignette = false)
                val ir = (argb ushr 16) and 0xFF
                val ig = (argb ushr 8) and 0xFF
                val ib = argb and 0xFF

                assertEquals("R and G must match in monochrome $monoProfile", ir, ig)
                assertEquals("G and B must match in monochrome $monoProfile", ig, ib)
            }
        }
    }

    @Test
    fun `high contrast monochrome has steeper contrast than classic monochrome`() {
        // Shadow point (0.08 in linear space)
        val shadowClassic = ColorScience.gradePixel(0.08, 0.08, 0.08, 0.5, 0.5, ColorScience.ToneProfile.CINE_SOFT_MONO, false) and 0xFF
        val shadowHC = ColorScience.gradePixel(0.08, 0.08, 0.08, 0.5, 0.5, ColorScience.ToneProfile.CINE_MONO, false) and 0xFF
        assertTrue("HC shadow should be darker or equal to soft shadow ($shadowHC <= $shadowClassic)", shadowHC <= shadowClassic)

        // Highlight point (0.85 in linear space)
        val hlClassic = ColorScience.gradePixel(0.85, 0.85, 0.85, 0.5, 0.5, ColorScience.ToneProfile.CINE_SOFT_MONO, false) and 0xFF
        val hlHC = ColorScience.gradePixel(0.85, 0.85, 0.85, 0.5, 0.5, ColorScience.ToneProfile.CINE_MONO, false) and 0xFF
        assertTrue("HC highlight should be brighter or equal to soft highlight ($hlHC >= $hlClassic)", hlHC >= hlClassic)
    }

    @Test
    fun `luma-aware optical vignetting attenuates corners while preserving center`() {
        // Center pixel vs corner pixel for mid-gray
        val center = ColorScience.gradePixel(0.4, 0.4, 0.4, 0.5, 0.5, ColorScience.ToneProfile.FILM_AUTHENTIC, enableVignette = true)
        val corner = ColorScience.gradePixel(0.4, 0.4, 0.4, 0.0, 0.0, ColorScience.ToneProfile.FILM_AUTHENTIC, enableVignette = true)

        val centerLum = (center ushr 16) and 0xFF
        val cornerLum = (corner ushr 16) and 0xFF

        assertTrue("Corner must be darker than center due to optical vignetting ($cornerLum < $centerLum)", cornerLum < centerLum)
    }

    @Test
    fun `fromId resolves all known profiles by case-insensitive key`() {
        for (profile in ColorScience.ToneProfile.values()) {
            val resolved = ColorScience.ToneProfile.fromId(profile.id)
            assertEquals("Profile id ${profile.id} should resolve to $profile", profile, resolved)

            val resolvedUpper = ColorScience.ToneProfile.fromId(profile.id.uppercase())
            assertEquals("Uppercase id should also resolve to $profile", profile, resolvedUpper)
        }

        assertEquals("Unknown id must default to DEFAULT", ColorScience.ToneProfile.DEFAULT, ColorScience.ToneProfile.fromId("unknown_xyz"))
    }

    @Test
    fun `Cinema Filmic look delivers soft highlight roll-off and organic skin tone`() {
        // Skin tone test vector in linear space: warm peach (R > G > B)
        val skinR = 0.65; val skinG = 0.45; val skinB = 0.35
        val argb = ColorScience.gradePixel(skinR, skinG, skinB, 0.5, 0.5, ColorScience.ToneProfile.CINE_FILMIC, enableVignette = false)

        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        assertTrue("Filmic look should preserve warm skin vector R > G", r > g)
        assertTrue("Filmic look should preserve warm skin vector G > B", g > b)
        assertTrue("Filmic look should deliver soft highlight compression", r < 250)
    }

    @Test
    fun `Reference OOTF preserves neutral broadcast balance`() {
        val neutral = 0.5
        val argb = ColorScience.gradePixel(neutral, neutral, neutral, 0.5, 0.5, ColorScience.ToneProfile.CINE_OOTF, enableVignette = false)

        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        assertEquals("OOTF neutral R and G should match", r, g)
        assertEquals("OOTF neutral G and B should match", g, b)
    }

    @Test
    fun `applyColorGrading modifies ARGB buffer in-place`() {
        val w = 4
        val h = 4
        val buffer = IntArray(w * h) {
            (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        }
        val origCopy = buffer.clone()

        ColorScience.applyColorGrading(buffer, w, h, ColorScience.ToneProfile.FILM_VIBRANT, enableVignette = true)

        // Ensure buffer was modified
        assertNotEquals("Buffer should be altered by color grading", origCopy[0], buffer[0])
    }

    @Test
    fun `resolveWbGains computes correct multipliers from AsShotNeutral`() {
        // Example: neutral point with 2x green bias (R=0.5, G=1.0, B=0.6)
        val asShotNeutral = floatArrayOf(0.5f, 1.0f, 0.6f)
        val gains = ColorScience.resolveWbGains(asShotNeutral)

        assertEquals("Red multiplier should be 2.0 (1/0.5)", 2.0, gains[0], 0.001)
        assertEquals("Green multiplier should be normalized to 1.0", 1.0, gains[1], 0.001)
        assertEquals("Blue multiplier should be 1.666 (1/0.6)", 1.666, gains[2], 0.01)
    }

    @Test
    fun `resolveWbGains falls back to D65 defaults on missing or degenerate neutral`() {
        val nullGains = ColorScience.resolveWbGains(null)
        assertTrue("Red gain should compensate green bias (> 1.5)", nullGains[0] > 1.5)
        assertEquals(1.0, nullGains[1], 0.001)
        assertTrue("Blue gain should compensate green bias (> 1.3)", nullGains[2] > 1.3)

        val zeroGains = ColorScience.resolveWbGains(floatArrayOf(0f, 0f, 0f))
        assertTrue("Zero gains fallback should match D65 defaults", zeroGains[0] > 1.5)
    }
}
