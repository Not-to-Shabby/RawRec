package dev.rawrec.tool

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clean-room photographic color science and cinema tone grading engine.
 *
 * Implements analytical cinema curves (filmic soft-skin curves,
 * standard optical transfer functions (OOTF), sigmoid microcontrast, highlight soft
 * roll-off, panchromatic spectral weighting, and luma-aware optical vignetting) without
 * proprietary firmware tables or trademarked dependencies.
 */
object ColorScience {

    enum class ToneProfile(val id: String, val displayName: String) {
        DEFAULT("default", "Raw Linear (Gamma 0.5)"),

        // ---- Cinema Look Flavors ----
        CINE_FILMIC("cine_filmic", "Cinema Filmic (Soft Skin & Roll-off)"),
        CINE_HLG("cine_hlg", "Rec.2100 HLG (Hybrid Log-Gamma HDR)"),
        CINE_OOTF("cine_ootf", "Reference OOTF (Broadcast Neutral)"),
        CINE_WARM("cine_warm", "Golden Amber (Warm Dusk)"),
        CINE_COOL("cine_cool", "Cobalt Cool (Teal Shadows)"),
        CINE_VINTAGE("cine_vintage", "Vintage 70s (Faded Film)"),
        CINE_BRIGHT("cine_bright", "Airy High-Key (Bright & Vivid)"),
        CINE_SOFT_MONO("cine_soft_mono", "Soft Tonal Monochrome"),
        CINE_MONO("cine_mono", "Cine Street Monochrome"),

        // ---- Classic Film Styles ----
        FILM_AUTHENTIC("authentic", "Film Authentic (Microcontrast)"),
        FILM_VIBRANT("vibrant", "Film Vibrant (Deep Gamut)"),
        VINTAGE_SEPIA("sepia", "Vintage Bronze / Sepia"),
        NORDIC_BLUE("nordic_blue", "Nordic Indigo / Cyan"),

        // ---- Custom 3D LUT ----
        CUSTOM_LUT("custom_lut", "Custom 3D LUT (.cube)");

        companion object {
            fun fromId(id: String): ToneProfile =
                values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }

    /**
     * Resolves per-channel white balance multipliers from AsShotNeutral (or default D65).
     *
     * AsShotNeutral is (1/R_gain, 1/G_gain, 1/B_gain). Inverting and normalizing to green
     * gives the RGB gains needed to neutralize the sensor's raw green sensitivity bias.
     */
    fun resolveWbGains(asShotNeutral: FloatArray?): DoubleArray {
        if (asShotNeutral == null || asShotNeutral.size < 3) {
            // Standard daylight D65 gains for phone Bayer sensors (neutralizes green bias)
            return doubleArrayOf(1.95, 1.0, 1.65)
        }
        val nR = asShotNeutral[0].toDouble()
        val nG = asShotNeutral[1].toDouble()
        val nB = asShotNeutral[2].toDouble()
        if (nR <= 0.001 || nG <= 0.001 || nB <= 0.001) {
            return doubleArrayOf(1.95, 1.0, 1.65)
        }
        val rG = 1.0 / nR
        val gG = 1.0 / nG
        val bG = 1.0 / nB
        return doubleArrayOf((rG / gG).coerceIn(0.5, 5.0), 1.0, (bG / gG).coerceIn(0.5, 5.0))
    }

    /**
     * Grades a single normalized (0.0..1.0) RGB quad and applies tone curves,
     * chromatic weighting, and luma-aware optical vignetting.
     *
     * @param rNorm Normalized red channel [0.0..1.0]
     * @param gNorm Normalized green channel [0.0..1.0]
     * @param bNorm Normalized blue channel [0.0..1.0]
     * @param uNorm Horizontal image position [0.0..1.0]
     * @param vNorm Vertical image position [0.0..1.0]
     * @param profile Target tonal and color profile
     * @param enableVignette Whether to apply luma-aware optical corner falloff
     * @param wbGains Optional white-balance gains [R, G, B] (default 1.0, 1.0, 1.0)
     * @param customLut Optional 3D/1D LUT for [ToneProfile.CUSTOM_LUT] mapping
     * @return Packed ARGB integer (0xFFRRGGBB)
     */
    fun gradePixel(
        rNorm: Double,
        gNorm: Double,
        bNorm: Double,
        uNorm: Double,
        vNorm: Double,
        profile: ToneProfile,
        enableVignette: Boolean = true,
        wbGains: DoubleArray? = null,
        customLut: CubeLut? = null
    ): Int {
        val gainR = wbGains?.getOrNull(0) ?: 1.0
        val gainG = wbGains?.getOrNull(1) ?: 1.0
        val gainB = wbGains?.getOrNull(2) ?: 1.0

        var r = (rNorm * gainR).coerceIn(0.0, 1.0)
        var g = (gNorm * gainG).coerceIn(0.0, 1.0)
        var b = (bNorm * gainB).coerceIn(0.0, 1.0)

        when (profile) {
            ToneProfile.DEFAULT -> {
                // Base square-root gamma response
                r = r.pow(0.5)
                g = g.pow(0.5)
                b = b.pow(0.5)
            }

            ToneProfile.CUSTOM_LUT -> {
                // Apply gamma 0.5 preview linearization first, then sample 3D LUT
                val rLin = r.pow(0.5)
                val gLin = g.pow(0.5)
                val bLin = b.pow(0.5)
                if (customLut != null) {
                    val lutOut = DoubleArray(3)
                    customLut.sample(rLin, gLin, bLin, lutOut)
                    r = lutOut[0]
                    g = lutOut[1]
                    b = lutOut[2]
                } else {
                    r = rLin
                    g = gLin
                    b = bLin
                }
            }

            // ---- 1. Cinema Filmic Look Flavor ----
            ToneProfile.CINE_FILMIC -> {
                // Soft sigmoid with gentle highlight roll-off and rich organic skin tones
                r = sigmoid(r, contrast = 1.32, pivot = 0.35)
                g = sigmoid(g, contrast = 1.32, pivot = 0.35)
                b = sigmoid(b, contrast = 1.32, pivot = 0.35)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val shadowFactor = (1.0 - lum).pow(2.0)

                // Subtle cool-cyan base in deep shadows + soft warm golden midtone push
                r = (r - 0.02 * shadowFactor + 0.03 * sin((lum * PI).coerceIn(0.0, PI))).coerceIn(0.0, 1.0)
                g = (g + 0.01 * sin((lum * PI).coerceIn(0.0, PI))).coerceIn(0.0, 1.0)
                b = (b + 0.03 * shadowFactor - 0.02 * sin((lum * PI).coerceIn(0.0, PI))).coerceIn(0.0, 1.0)

                // Natural chroma compression in deep shadows and bright highlights
                val satScale = 1.08 * (0.92 + 0.16 * sin((lum * PI).coerceIn(0.0, PI)))
                r = (lum + (r - lum) * satScale).coerceIn(0.0, 1.0)
                g = (lum + (g - lum) * satScale).coerceIn(0.0, 1.0)
                b = (lum + (b - lum) * satScale).coerceIn(0.0, 1.0)
            }

            // ---- 2. Rec.2100 HLG (Hybrid Log-Gamma HDR) ----
            ToneProfile.CINE_HLG -> {
                fun hlgOetf(e: Double): Double {
                    val a = 0.17883277
                    val b = 0.28466892
                    val c = 0.55991073
                    return if (e <= 1.0 / 12.0) {
                        sqrt(3.0 * e)
                    } else {
                        a * kotlin.math.ln(12.0 * e - b) + c
                    }.coerceIn(0.0, 1.0)
                }
                r = hlgOetf(r)
                g = hlgOetf(g)
                b = hlgOetf(b)
            }

            // ---- 3. Reference OOTF (Opto-Optical Transfer Function) ----
            ToneProfile.CINE_OOTF -> {
                // ITU-R BT.709/BT.2020 standard broadcast OOTF curve with linear toe and gentle knee
                fun ootf(x: Double): Double = if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099
                r = ootf(r).coerceIn(0.0, 1.0)
                g = ootf(g).coerceIn(0.0, 1.0)
                b = ootf(b).coerceIn(0.0, 1.0)
            }

            // ---- 3. Golden Amber / Warm (Warm Look Inspired) ----
            ToneProfile.CINE_WARM -> {
                r = sigmoid(r, contrast = 1.28, pivot = 0.33)
                g = sigmoid(g, contrast = 1.28, pivot = 0.33)
                b = sigmoid(b, contrast = 1.28, pivot = 0.33)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                // Warm amber highlights, soft tungsten tones
                r = (r * 1.08 + 0.02 * lum).coerceIn(0.0, 1.0)
                g = (g * 1.02 + 0.01 * lum).coerceIn(0.0, 1.0)
                b = (b * 0.90).coerceIn(0.0, 1.0)

                val satScale = 1.15
                r = (lum + (r - lum) * satScale).coerceIn(0.0, 1.0)
                g = (lum + (g - lum) * satScale).coerceIn(0.0, 1.0)
                b = (lum + (b - lum) * satScale).coerceIn(0.0, 1.0)
            }

            // ---- 4. Cobalt Cool (Cool Look Inspired) ----
            ToneProfile.CINE_COOL -> {
                r = sigmoid(r, contrast = 1.30, pivot = 0.36)
                g = sigmoid(g, contrast = 1.30, pivot = 0.36)
                b = sigmoid(b, contrast = 1.30, pivot = 0.36)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val shadow = (1.0 - lum).pow(1.5)

                // Push deep cyan/cobalt into shadows, clean neutral highlights
                r = (r * 0.92 - 0.03 * shadow).coerceIn(0.0, 1.0)
                g = (g * 0.98 + 0.01 * shadow).coerceIn(0.0, 1.0)
                b = (b * 1.14 + 0.06 * shadow).coerceIn(0.0, 1.0)
            }

            // ---- 5. Vintage 70s Film (Vintage Look Inspired) ----
            ToneProfile.CINE_VINTAGE -> {
                // Lifted milky black floor + soft shoulder roll-off
                val rS = sigmoid(r, contrast = 1.20, pivot = 0.36)
                val gS = sigmoid(g, contrast = 1.20, pivot = 0.36)
                val bS = sigmoid(b, contrast = 1.20, pivot = 0.36)

                // Lift blacks to 0.06, mute greens, warm yellow highlights
                r = (rS * 0.90 + 0.06).coerceIn(0.0, 1.0)
                g = (gS * 0.86 + 0.05).coerceIn(0.0, 1.0)
                b = (bS * 0.78 + 0.07).coerceIn(0.0, 1.0)
            }

            // ---- 6. Airy High-Key / Bright (Bright Look Inspired) ----
            ToneProfile.CINE_BRIGHT -> {
                // Open shadows, clean whites, vibrant pastel midtones
                r = sigmoid(r, contrast = 1.15, pivot = 0.28)
                g = sigmoid(g, contrast = 1.15, pivot = 0.28)
                b = sigmoid(b, contrast = 1.15, pivot = 0.28)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val satScale = 1.22
                r = (lum + (r - lum) * satScale).coerceIn(0.0, 1.0)
                g = (lum + (g - lum) * satScale).coerceIn(0.0, 1.0)
                b = (lum + (b - lum) * satScale).coerceIn(0.0, 1.0)
            }

            // ---- 7. Soft Tonal Monochrome (Soft Mono Inspired) ----
            ToneProfile.CINE_SOFT_MONO -> {
                val panchro = (0.299 * r + 0.587 * g + 0.114 * b).pow(0.5)
                val graded = sigmoid(panchro, contrast = 1.12, pivot = 0.38)
                r = graded; g = graded; b = graded
            }

            // ---- 8. Cine Street Monochrome (Mono Inspired) ----
            ToneProfile.CINE_MONO -> {
                val panchro = (0.35 * r + 0.55 * g + 0.10 * b).pow(0.5)
                val graded = sigmoid(panchro, contrast = 1.85, pivot = 0.38)
                r = graded; g = graded; b = graded
            }

            // ---- Classic Film Styles ----
            ToneProfile.FILM_AUTHENTIC -> {
                r = sigmoid(r, contrast = 1.55, pivot = 0.36)
                g = sigmoid(g, contrast = 1.55, pivot = 0.36)
                b = sigmoid(b, contrast = 1.55, pivot = 0.36)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val satScale = 0.94 + 0.12 * sin((lum * PI).coerceIn(0.0, PI))
                r = lum + (r - lum) * satScale
                g = lum + (g - lum) * satScale
                b = lum + (b - lum) * satScale
            }

            ToneProfile.FILM_VIBRANT -> {
                r = sigmoid(r, contrast = 1.25, pivot = 0.30)
                g = sigmoid(g, contrast = 1.25, pivot = 0.30)
                b = sigmoid(b, contrast = 1.25, pivot = 0.30)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val satScale = 1.24
                r = (lum + (r - lum) * satScale).coerceIn(0.0, 1.0)
                g = (lum + (g - lum) * satScale).coerceIn(0.0, 1.0)
                b = (lum + (b - lum) * satScale).coerceIn(0.0, 1.0)
            }

            ToneProfile.VINTAGE_SEPIA -> {
                val luma = (0.299 * r + 0.587 * g + 0.114 * b).pow(0.5)
                val graded = sigmoid(luma, contrast = 1.30, pivot = 0.36)
                r = (graded * 1.16 + 0.04).coerceIn(0.0, 1.0)
                g = (graded * 0.94 + 0.02).coerceIn(0.0, 1.0)
                b = (graded * 0.72).coerceIn(0.0, 1.0)
            }

            ToneProfile.NORDIC_BLUE -> {
                val luma = (0.299 * r + 0.587 * g + 0.114 * b).pow(0.5)
                val graded = sigmoid(luma, contrast = 1.30, pivot = 0.36)
                r = (graded * 0.85).coerceIn(0.0, 1.0)
                g = (graded * 0.96 + 0.01).coerceIn(0.0, 1.0)
                b = (graded * 1.18 + 0.03).coerceIn(0.0, 1.0)
            }
        }

        // Luma-aware optical vignetting (highlights resist falloff, shadows fall off smoothly)
        if (enableVignette && profile != ToneProfile.DEFAULT && profile != ToneProfile.CINE_OOTF) {
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val dx = (uNorm - 0.5) * 1.15
            val dy = (vNorm - 0.5)
            val dist = sqrt(dx * dx + dy * dy)
            val vignetteFalloff = smoothstep(0.35, 0.82, dist)

            // High luma resists darkening to keep bright skies natural
            val resistance = lum.pow(2.2)
            val factor = 1.0 - vignetteFalloff * 0.42 * (1.0 - resistance)

            r = (r * factor).coerceIn(0.0, 1.0)
            g = (g * factor).coerceIn(0.0, 1.0)
            b = (b * factor).coerceIn(0.0, 1.0)
        }

        val ir = (r * 255.0).toInt().coerceIn(0, 255)
        val ig = (g * 255.0).toInt().coerceIn(0, 255)
        val ib = (b * 255.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
    }

    /**
     * In-place color grading pass over an existing ARGB pixel buffer.
     */
    fun applyColorGrading(
        argb: IntArray,
        width: Int,
        height: Int,
        profile: ToneProfile,
        enableVignette: Boolean = true,
        wbGains: DoubleArray? = null,
        customLut: CubeLut? = null
    ) {
        if (profile == ToneProfile.DEFAULT && !enableVignette && wbGains == null) return
        for (y in 0 until height) {
            val vNorm = y.toDouble() / height
            for (x in 0 until width) {
                val idx = y * width + x
                val c = argb[idx]
                val rNorm = ((c shr 16) and 0xFF) / 255.0
                val gNorm = ((c shr 8) and 0xFF) / 255.0
                val bNorm = (c and 0xFF) / 255.0
                val uNorm = x.toDouble() / width
                argb[idx] = gradePixel(rNorm, gNorm, bNorm, uNorm, vNorm, profile, enableVignette, wbGains, customLut)
            }
        }
    }

    /**
     * Evaluates the 1D tone transfer response of a profile across [pointsCount] coordinates
     * for embedding as a DNG Tag 50981 (ProfileToneCurve) in exported CinemaDNG files.
     * Returns an array of (x, y) coordinate pairs in [0.0..1.0].
     */
    fun evaluateToneCurve(profile: ToneProfile, pointsCount: Int = 33): FloatArray {
        val out = FloatArray(pointsCount * 2)
        for (i in 0 until pointsCount) {
            val x = i.toDouble() / (pointsCount - 1)
            val y = when (profile) {
                ToneProfile.DEFAULT -> x
                ToneProfile.CINE_FILMIC -> sigmoid(x, contrast = 1.32, pivot = 0.35)
                ToneProfile.CINE_HLG -> if (x <= 1.0 / 12.0) sqrt(3.0 * x) else 0.17883277 * kotlin.math.ln(12.0 * x - 0.28466892) + 0.55991073
                ToneProfile.CINE_OOTF -> if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099
                ToneProfile.CINE_WARM -> sigmoid(x, contrast = 1.28, pivot = 0.33)
                ToneProfile.CINE_COOL -> sigmoid(x, contrast = 1.30, pivot = 0.36)
                ToneProfile.CINE_VINTAGE -> sigmoid(x, contrast = 1.20, pivot = 0.36) * 0.90 + 0.06
                ToneProfile.CINE_BRIGHT -> sigmoid(x, contrast = 1.15, pivot = 0.28)
                ToneProfile.CINE_SOFT_MONO -> sigmoid(x.pow(0.5), contrast = 1.12, pivot = 0.38)
                ToneProfile.CINE_MONO -> sigmoid(x.pow(0.5), contrast = 1.85, pivot = 0.38)
                ToneProfile.FILM_AUTHENTIC -> sigmoid(x, contrast = 1.55, pivot = 0.36)
                ToneProfile.FILM_VIBRANT -> sigmoid(x, contrast = 1.25, pivot = 0.30)
                ToneProfile.VINTAGE_SEPIA -> sigmoid(x.pow(0.5), contrast = 1.30, pivot = 0.36)
                ToneProfile.NORDIC_BLUE -> sigmoid(x.pow(0.5), contrast = 1.30, pivot = 0.36)
                ToneProfile.CUSTOM_LUT -> x
            }.coerceIn(0.0, 1.0)

            out[i * 2] = x.toFloat()
            out[i * 2 + 1] = y.toFloat()
        }
        return out
    }

    /**
     * Grades a single 16-bit raw sensor pixel sample value according to the chosen tone profile.
     */
    fun gradeRawSample(
        sample: Int,
        blackLevel: Int,
        whiteLevel: Int,
        profile: ToneProfile
    ): Short {
        if (profile == ToneProfile.DEFAULT) return sample.toShort()
        val black = blackLevel.coerceAtLeast(0)
        val span = (whiteLevel - black).coerceAtLeast(1).toDouble()
        val norm = ((sample - black) / span).coerceIn(0.0, 1.0)
        val graded = when (profile) {
            ToneProfile.DEFAULT -> norm
            ToneProfile.CINE_FILMIC -> sigmoid(norm, contrast = 1.32, pivot = 0.35)
            ToneProfile.CINE_HLG -> if (norm <= 1.0 / 12.0) sqrt(3.0 * norm) else 0.17883277 * kotlin.math.ln(12.0 * norm - 0.28466892) + 0.55991073
            ToneProfile.CINE_OOTF -> if (norm < 0.018) 4.5 * norm else 1.099 * norm.pow(0.45) - 0.099
            ToneProfile.CINE_WARM -> sigmoid(norm, contrast = 1.28, pivot = 0.33)
            ToneProfile.CINE_COOL -> sigmoid(norm, contrast = 1.30, pivot = 0.36)
            ToneProfile.CINE_VINTAGE -> sigmoid(norm, contrast = 1.20, pivot = 0.36) * 0.90 + 0.06
            ToneProfile.CINE_BRIGHT -> sigmoid(norm, contrast = 1.15, pivot = 0.28)
            ToneProfile.CINE_SOFT_MONO -> sigmoid(norm.pow(0.5), contrast = 1.12, pivot = 0.38)
            ToneProfile.CINE_MONO -> sigmoid(norm.pow(0.5), contrast = 1.85, pivot = 0.38)
            ToneProfile.FILM_AUTHENTIC -> sigmoid(norm, contrast = 1.55, pivot = 0.36)
            ToneProfile.FILM_VIBRANT -> sigmoid(norm, contrast = 1.25, pivot = 0.30)
            ToneProfile.VINTAGE_SEPIA -> sigmoid(norm.pow(0.5), contrast = 1.30, pivot = 0.36)
            ToneProfile.NORDIC_BLUE -> sigmoid(norm.pow(0.5), contrast = 1.30, pivot = 0.36)
            ToneProfile.CUSTOM_LUT -> norm
        }.coerceIn(0.0, 1.0)

        val outVal = (black + graded * span).toInt().coerceIn(0, whiteLevel)
        return outVal.toShort()
    }

    private fun sigmoid(x: Double, contrast: Double, pivot: Double): Double {
        val norm = x.coerceIn(0.0001, 0.9999)
        val p = pivot.coerceIn(0.01, 0.99)
        return (1.0 / (1.0 + ((p / (1.0 - p)) * ((1.0 - norm) / norm)).pow(contrast)))
            .coerceIn(0.0, 1.0)
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
