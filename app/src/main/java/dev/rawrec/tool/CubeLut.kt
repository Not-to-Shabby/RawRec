package dev.rawrec.tool

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.floor

/**
 * Standard 3D / 1D `.cube` LUT (Look-Up Table) parser and fast trilinear interpolator.
 *
 * Fully compatible with industry-standard Adobe / DaVinci Resolve `.cube` specifications:
 * - 3D LUTs (`LUT_3D_SIZE N`, standard sizes 17x17x17, 33x33x33, 65x65x65)
 * - 1D LUTs (`LUT_1D_SIZE N`)
 * - `TITLE`, `DOMAIN_MIN`, and `DOMAIN_MAX` attributes
 * - Comments (`#`) and arbitrary whitespace formatting
 *
 * Implements high-throughput trilinear interpolation for smooth 60+ FPS preview in Swing.
 */
class CubeLut(
    val title: String,
    val size: Int,
    val is3D: Boolean,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    private val table: FloatArray
) {
    private val domainSpan = FloatArray(3) { i -> (domainMax[i] - domainMin[i]).coerceAtLeast(1e-6f) }
    private val sizeMinus1 = (size - 1).toFloat()

    /**
     * Fast trilinear sampling of an RGB color through the 3D LUT.
     *
     * @param r Red input in [0.0..1.0]
     * @param g Green input in [0.0..1.0]
     * @param b Blue input in [0.0..1.0]
     * @param out DoubleArray of size >= 3 to store mapped (r, g, b)
     */
    fun sample(r: Double, g: Double, b: Double, out: DoubleArray) {
        if (!is3D) {
            sample1D(r, g, b, out)
            return
        }

        // Map normalized input to LUT domain
        val nr = ((r.toFloat() - domainMin[0]) / domainSpan[0]).coerceIn(0f, 1f) * sizeMinus1
        val ng = ((g.toFloat() - domainMin[1]) / domainSpan[1]).coerceIn(0f, 1f) * sizeMinus1
        val nb = ((b.toFloat() - domainMin[2]) / domainSpan[2]).coerceIn(0f, 1f) * sizeMinus1

        val r0 = floor(nr).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val dr = (nr - r0).toDouble()

        val g0 = floor(ng).toInt().coerceIn(0, size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val dg = (ng - g0).toDouble()

        val b0 = floor(nb).toInt().coerceIn(0, size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val db = (nb - b0).toDouble()

        // 3D LUT index formula: index = (b * size * size + g * size + r) * 3
        fun lutOffset(ri: Int, gi: Int, bi: Int): Int = (bi * size * size + gi * size + ri) * 3

        val o000 = lutOffset(r0, g0, b0)
        val o100 = lutOffset(r1, g0, b0)
        val o010 = lutOffset(r0, g1, b0)
        val o110 = lutOffset(r1, g1, b0)
        val o001 = lutOffset(r0, g0, b1)
        val o101 = lutOffset(r1, g0, b1)
        val o011 = lutOffset(r0, g1, b1)
        val o111 = lutOffset(r1, g1, b1)

        val invDr = 1.0 - dr
        val invDg = 1.0 - dg
        val invDb = 1.0 - db

        for (c in 0..2) {
            // Lerp along R axis (4 pairs)
            val c000 = table[o000 + c] * invDr + table[o100 + c] * dr
            val c010 = table[o010 + c] * invDr + table[o110 + c] * dr
            val c001 = table[o001 + c] * invDr + table[o101 + c] * dr
            val c011 = table[o011 + c] * invDr + table[o111 + c] * dr

            // Lerp along G axis (2 pairs)
            val c00 = c000 * invDg + c010 * dg
            val c01 = c001 * invDg + c011 * dg

            // Lerp along B axis (final value)
            out[c] = (c00 * invDb + c01 * db).coerceIn(0.0, 1.0)
        }
    }

    private fun sample1D(r: Double, g: Double, b: Double, out: DoubleArray) {
        val inCh = doubleArrayOf(r, g, b)
        for (c in 0..2) {
            val nc = ((inCh[c].toFloat() - domainMin[c]) / domainSpan[c]).coerceIn(0f, 1f) * sizeMinus1
            val i0 = floor(nc).toInt().coerceIn(0, size - 1)
            val i1 = (i0 + 1).coerceAtMost(size - 1)
            val d = (nc - i0).toDouble()

            val o0 = i0 * 3 + c
            val o1 = i1 * 3 + c
            out[c] = (table[o0] * (1.0 - d) + table[o1] * d).coerceIn(0.0, 1.0)
        }
    }

    companion object {

        /**
         * Parses a `.cube` Look-Up Table file from an [InputStream].
         */
        fun parse(stream: InputStream, defaultTitle: String = "Custom LUT"): CubeLut {
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            var title = defaultTitle
            var size = 0
            var is3D = true
            val domainMin = floatArrayOf(0.0f, 0.0f, 0.0f)
            val domainMax = floatArrayOf(1.0f, 1.0f, 1.0f)
            val values = ArrayList<Float>(33 * 33 * 33 * 3)

            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine

                val tokens = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) return@forEachLine

                when (tokens[0].uppercase()) {
                    "TITLE" -> {
                        title = tokens.drop(1).joinToString(" ").removeSurrounding("\"")
                    }
                    "LUT_3D_SIZE" -> {
                        size = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                        is3D = true
                    }
                    "LUT_1D_SIZE" -> {
                        size = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                        is3D = false
                    }
                    "DOMAIN_MIN" -> {
                        if (tokens.size >= 4) {
                            domainMin[0] = tokens[1].toFloatOrNull() ?: 0f
                            domainMin[1] = tokens[2].toFloatOrNull() ?: 0f
                            domainMin[2] = tokens[3].toFloatOrNull() ?: 0f
                        }
                    }
                    "DOMAIN_MAX" -> {
                        if (tokens.size >= 4) {
                            domainMax[0] = tokens[1].toFloatOrNull() ?: 1f
                            domainMax[1] = tokens[2].toFloatOrNull() ?: 1f
                            domainMax[2] = tokens[3].toFloatOrNull() ?: 1f
                        }
                    }
                    else -> {
                        // Data line (R G B)
                        if (tokens.size >= 3) {
                            val r = tokens[0].toFloatOrNull()
                            val g = tokens[1].toFloatOrNull()
                            val b = tokens[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(r)
                                values.add(g)
                                values.add(b)
                            }
                        }
                    }
                }
            }

            require(size > 1) { "Invalid or missing LUT_3D_SIZE / LUT_1D_SIZE in cube file" }
            val expectedEntries = if (is3D) size * size * size * 3 else size * 3
            require(values.size >= expectedEntries) {
                "Incomplete LUT data: expected $expectedEntries floats for size $size, got ${values.size}"
            }

            return CubeLut(
                title = title.ifBlank { defaultTitle },
                size = size,
                is3D = is3D,
                domainMin = domainMin,
                domainMax = domainMax,
                table = values.toFloatArray()
            )
        }

        fun parse(file: File): CubeLut = file.inputStream().use { parse(it, file.nameWithoutExtension) }
    }
}
