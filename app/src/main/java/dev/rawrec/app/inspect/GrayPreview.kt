package dev.rawrec.app.inspect

import dev.rawrec.app.container.Rvsp
import kotlin.math.roundToInt

object GrayPreview {

    const val OUT_W = 192
    const val OUT_H = 144

    fun argb(
        samples: ShortArray,
        width: Int,
        height: Int,
        whiteLevel: Int,
        blackLevel: Int,
        gamma: Double = 0.5,
        outW: Int = OUT_W,
        outH: Int = OUT_H
    ): IntArray {
        val out = IntArray(outW * outH)
        val lo = blackLevel.coerceAtLeast(0)
        val span = (whiteLevel - lo).coerceAtLeast(1)
        val invGamma = 1.0 / gamma
        for (gy in 0 until outH) {
            val sy0 = gy * height / outH
            val sy1 = ((gy + 1) * height / outH).coerceAtLeast(sy0 + 1)
            for (gx in 0 until outW) {
                val sx0 = gx * width / outW
                val sx1 = ((gx + 1) * width / outW).coerceAtLeast(sx0 + 1)
                var acc = 0L
                var n = 0L
                var sy = sy0
                while (sy < sy1) {
                    var sx = sx0
                    while (sx < sx1) {
                        acc += samples[sy * width + sx].toInt() and 0xFFFF
                        n++
                        sx++
                    }
                    sy++
                }
                val norm = ((acc.toDouble() / n.coerceAtLeast(1)) - lo) / span
                val g = Math.pow(norm.coerceIn(0.0, 1.0), invGamma)
                val v = (g * 255.0).roundToInt().coerceIn(0, 255)
                out[gy * outW + gx] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return out
    }
}
