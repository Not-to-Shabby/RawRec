package dev.rawrec.app.scopes

import kotlin.math.abs
import kotlin.math.roundToInt

data class HistogramData(
    val redBins: IntArray,
    val greenBins: IntArray,
    val blueBins: IntArray,
    val lumaBins: IntArray,
    val totalSamples: Int,
    val shadowClippedPercent: Double,
    val highlightClippedPercent: Double
)

data class FramingRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object CinemaScopes {

    // --- 0. Fast ARGB to Rec.709 Luminance ---
    fun argbToLuma(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        outLuma: ByteArray? = null
    ): ByteArray {
        val count = width * height
        val out = outLuma ?: ByteArray(count)
        for (i in 0 until count) {
            val p = argbPixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // ITU-R BT.709 Luma: 0.2126 R + 0.7152 G + 0.0722 B ≈ (54*R + 183*G + 19*B) >> 8
            val y = ((54 * r + 183 * g + 19 * b) shr 8).coerceIn(0, 255)
            out[i] = y.toByte()
        }
        return out
    }

    // --- 1. Focus Peaking (3x3 Laplacian edge filter) ---
    fun applyFocusPeaking(
        luma: ByteArray,
        width: Int,
        height: Int,
        threshold: Int = 30,
        highlightArgb: Int = 0xFF00FF00.toInt()
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val p = yOffset + x
                val center = luma[p].toInt() and 0xFF
                // Grayscale background
                val grayArgb = (0xFF shl 24) or (center shl 16) or (center shl 8) or center

                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    out[p] = grayArgb
                    continue
                }

                // 3x3 Laplacian: 4*center - top - bottom - left - right
                val top = luma[p - width].toInt() and 0xFF
                val bottom = luma[p + width].toInt() and 0xFF
                val left = luma[p - 1].toInt() and 0xFF
                val right = luma[p + 1].toInt() and 0xFF
                val laplacian = abs(4 * center - top - bottom - left - right)

                out[p] = if (laplacian >= threshold) highlightArgb else grayArgb
            }
        }
        return out
    }

    /**
     * Transparent focus peaking overlay: non-edge pixels are 0x00000000 (fully transparent),
     * while sharp in-focus edges are highlighted in [highlightArgb] (neon green by default).
     * Overlays cleanly on top of the live color camera preview without muting colors.
     */
    fun applyFocusPeakingTransparent(
        luma: ByteArray,
        width: Int,
        height: Int,
        threshold: Int = 30,
        highlightArgb: Int = 0xFF00FF00.toInt(),
        outBuffer: IntArray? = null
    ): IntArray {
        val count = width * height
        val out = outBuffer ?: IntArray(count)
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val p = yOffset + x
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    out[p] = 0 // transparent border
                    continue
                }

                val center = luma[p].toInt() and 0xFF
                val top = luma[p - width].toInt() and 0xFF
                val bottom = luma[p + width].toInt() and 0xFF
                val left = luma[p - 1].toInt() and 0xFF
                val right = luma[p + 1].toInt() and 0xFF
                val laplacian = abs(4 * center - top - bottom - left - right)

                out[p] = if (laplacian >= threshold) highlightArgb else 0
            }
        }
        return out
    }

    // --- 2. 16-Zone Calibrated False Color Map ---
    fun mapLumaToFalseColor(lumaByte: Int): Int {
        val v = lumaByte.coerceIn(0, 255)
        return when {
            v <= 5 -> 0xFF800080.toInt()   // Purple: Crushed blacks (0-2% IRE)
            v <= 25 -> 0xFF0000FF.toInt()  // Blue: Shadows (2-10% IRE)
            v <= 50 -> 0xFF00FFFF.toInt()  // Cyan: Low midtones (10-20% IRE)
            v in 97..115 -> 0xFF00FF00.toInt() // Green: 18% Middle Gray (38-45% IRE)
            v in 132..158 -> 0xFFFFC0CB.toInt() // Pink: Caucasian/Light skin tones (52-62% IRE)
            v in 159..178 -> 0xFFFFFF99.toInt() // Light Yellow: High midtones (62-70% IRE)
            v in 179..216 -> 0xFFFFA500.toInt() // Orange: Near highlight (70-85% IRE)
            v in 217..250 -> 0xFFFFFF00.toInt() // Yellow: Hot highlights (85-98% IRE)
            v >= 251 -> 0xFFFF0000.toInt() // Red: Clipping (> 98% IRE)
            else -> {
                // Neutral midtone gray for unclassified zones
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    fun applyFalseColor(
        luma: ByteArray,
        width: Int,
        height: Int,
        outBuffer: IntArray? = null
    ): IntArray {
        val count = width * height
        val out = outBuffer ?: IntArray(count)
        for (i in 0 until count) {
            val v = luma[i].toInt() and 0xFF
            out[i] = mapLumaToFalseColor(v)
        }
        return out
    }

    // --- 3. Zebra Stripes Highlight Overlay ---
    fun applyZebras(
        luma: ByteArray,
        width: Int,
        height: Int,
        thresholdIre: Int = 95,
        stripeWidth: Int = 8,
        animPhase: Int = 0,
        outBuffer: IntArray? = null
    ): IntArray {
        val count = width * height
        val out = outBuffer ?: IntArray(count)
        val thresholdByte = (thresholdIre * 255.0 / 100.0).roundToInt()
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val p = yOffset + x
                val v = luma[p].toInt() and 0xFF
                if (v >= thresholdByte) {
                    val isStripe = ((x + y + animPhase) / stripeWidth) % 2 == 0
                    out[p] = if (isStripe) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                } else {
                    out[p] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        return out
    }

    /**
     * Transparent zebra stripes overlay: pixels below [thresholdIre] are 0x00000000 (transparent),
     * while clipped highlights show alternating black and white crawling diagonal stripes.
     */
    fun applyZebrasTransparent(
        luma: ByteArray,
        width: Int,
        height: Int,
        thresholdIre: Int = 95,
        stripeWidth: Int = 8,
        animPhase: Int = 0,
        outBuffer: IntArray? = null
    ): IntArray {
        val count = width * height
        val out = outBuffer ?: IntArray(count)
        val thresholdByte = (thresholdIre * 255.0 / 100.0).roundToInt()
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val p = yOffset + x
                val v = luma[p].toInt() and 0xFF
                if (v >= thresholdByte) {
                    val isStripe = ((x + y + animPhase) / stripeWidth) % 2 == 0
                    out[p] = if (isStripe) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                } else {
                    out[p] = 0 // transparent
                }
            }
        }
        return out
    }

    // --- 4. 256-Bin RGB & Luminance Histogram ---
    fun computeHistogram(argbPixels: IntArray, sampleStep: Int = 1): HistogramData {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        val luma = IntArray(256)

        var total = 0
        var shadowClipped = 0
        var highlightClipped = 0

        val step = sampleStep.coerceAtLeast(1)
        for (i in argbPixels.indices step step) {
            val pixel = argbPixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // ITU-R BT.709 Luma: 0.2126 R + 0.7152 G + 0.0722 B
            val y = ((54 * r + 183 * g + 19 * b) shr 8).coerceIn(0, 255)

            red[r]++
            green[g]++
            blue[b]++
            luma[y]++
            total++

            if (y <= 5) shadowClipped++
            if (y >= 250) highlightClipped++
        }

        val totalSafe = total.coerceAtLeast(1).toDouble()
        return HistogramData(
            redBins = red,
            greenBins = green,
            blueBins = blue,
            lumaBins = luma,
            totalSamples = total,
            shadowClippedPercent = (shadowClipped / totalSafe) * 100.0,
            highlightClippedPercent = (highlightClipped / totalSafe) * 100.0
        )
    }

    // --- 5. Aspect Ratio & Framing Guides ---
    fun calculateAspectFraming(
        viewWidth: Float,
        viewHeight: Float,
        targetAspect: Float
    ): FramingRect {
        require(viewWidth > 0f && viewHeight > 0f) { "View dimensions must be positive" }
        require(targetAspect > 0f) { "Aspect ratio must be positive" }

        val currentAspect = viewWidth / viewHeight
        return if (currentAspect > targetAspect) {
            // View is wider than target -> pillarbox (bars on left/right)
            val activeWidth = viewHeight * targetAspect
            val margin = (viewWidth - activeWidth) / 2.0f
            FramingRect(left = margin, top = 0f, right = viewWidth - margin, bottom = viewHeight)
        } else {
            // View is taller than target -> letterbox (bars on top/bottom)
            val activeHeight = viewWidth / targetAspect
            val margin = (viewHeight - activeHeight) / 2.0f
            FramingRect(left = 0f, top = margin, right = viewWidth, bottom = viewHeight - margin)
        }
    }
}
