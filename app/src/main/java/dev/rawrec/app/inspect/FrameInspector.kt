package dev.rawrec.app.inspect

import dev.rawrec.app.container.Rvsp
import kotlin.math.sqrt

data class ChannelStats(
    val name: String,
    val min: Int,
    val max: Int,
    val mean: Double,
    val stdDev: Double
)

data class FrameDiagnostics(
    val channels: List<ChannelStats>,
    val overallMin: Int,
    val overallMax: Int,
    val neighborCorrelation: Double,
    val verdict: String,
    val detail: String
)

object FrameInspector {

    fun cfaNames(pattern: Int): List<String> = when (pattern) {
        Rvsp.CFA_RGGB -> listOf("R", "Gr", "Gb", "B")
        Rvsp.CFA_GRBG -> listOf("Gr", "R", "B", "Gb")
        Rvsp.CFA_GBRG -> listOf("Gb", "B", "R", "Gr")
        else -> listOf("B", "Gb", "Gr", "R")
    }

    private fun channelIndex(pattern: Int, x: Int, y: Int): Int {
        val row = (y and 1) shl 1
        return row or (x and 1)
    }

    fun diagnose(
        samples: ShortArray,
        width: Int,
        height: Int,
        cfaPattern: Int,
        whiteLevel: Int
    ): FrameDiagnostics {
        val sums = DoubleArray(4)
        val sumSq = DoubleArray(4)
        val mins = IntArray(4) { Int.MAX_VALUE }
        val maxs = IntArray(4)
        val counts = LongArray(4)

        var oMin = Int.MAX_VALUE
        var oMax = Int.MIN_VALUE
        for (y in 0 until height) {
            var base = y * width
            for (x in 0 until width) {
                val v = samples[base + x].toInt() and 0xFFFF
                if (v < oMin) oMin = v
                if (v > oMax) oMax = v
                val idx = channelIndex(cfaPattern, x, y)
                sums[idx] += v
                sumSq[idx] += v.toDouble() * v
                counts[idx]++
                if (v < mins[idx]) mins[idx] = v
                if (v > maxs[idx]) maxs[idx] = v
            }
            base += 0
        }

        val names = cfaNames(cfaPattern)
        val channels = names.mapIndexed { i, name ->
            val n = counts[i].coerceAtLeast(1).toDouble()
            val mean = sums[i] / n
            ChannelStats(
                name = name,
                min = mins[i],
                max = maxs[i],
                mean = mean,
                stdDev = sqrt((sumSq[i] / n - mean * mean).coerceAtLeast(0.0))
            )
        }

        val corr = neighborCorrelation(samples, width, height, cfaPattern)

        val blackEstimate = channels.minOf { it.mean }.coerceAtLeast(0.0)
        val range = (whiteLevel - blackEstimate).coerceAtLeast(1.0)
        val dynRange = (oMax - oMin).toDouble()
        val meanStd = channels.map { it.stdDev }.average()

        return when {
            dynRange < range * 0.02 || meanStd < range * 0.004 ->
                FrameDiagnostics(
                    channels, oMin, oMax, corr, "BLANK/CLIPPED",
                    "dynamic range %.0f of %.0f (%.2f%%): flat image, check exposure/lens cap"
                        .format(dynRange, range, dynRange / range * 100)
                )
            corr < 0.25 && meanStd > range * 0.05 ->
                FrameDiagnostics(
                    channels, oMin, oMax, corr, "NOISE-LIKE",
                    "correlation %.2f with variance %.0f: uncorrelated data, suspect stride/bit-depth/CSI issue"
                        .format(corr, meanStd)
                )
            else ->
                FrameDiagnostics(
                    channels, oMin, oMax, corr, "OK",
                    "structured content: correlation %.2f, variance %.0f".format(corr, meanStd)
                )
        }
    }

    private fun lumaAt(
        samples: ShortArray, width: Int, cfaPattern: Int, x: Int, y: Int
    ): Double {
        val v = samples[y * width + x].toInt() and 0xFFFF
        return if ((x + y) % 2 == 0) v.toDouble()
        else (v + (samples[y * width + (x xor 1)].toInt() and 0xFFFF)) / 2.0
    }

    private fun neighborCorrelation(
        samples: ShortArray,
        width: Int,
        height: Int,
        cfaPattern: Int
    ): Double {
        val gridW = 64
        val gridH = 48
        val stepX = (width / gridW).coerceAtLeast(2)
        val stepY = (height / gridH).coerceAtLeast(1)
        var nPairs = 0L
        var sumProd = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var y = 0
        while (y < gridH) {
            var x = 0
            while (x < gridW - 1) {
                val px = x * stepX
                val py = y * stepY
                if (px + stepX < width && py < height) {
                    val a = lumaAt(samples, width, cfaPattern, px, py)
                    val b = lumaAt(samples, width, cfaPattern, px + 1, py)
                    sumProd += a * b
                    sumA += a * a
                    sumB += b * b
                    nPairs++
                }
                x++
            }
            y++
        }
        return if (nPairs > 0 && sumA > 0 && sumB > 0) sumProd / sqrt(sumA * sumB) else 0.0
    }
}
