package dev.rawrec.app.control

import kotlin.math.ln
import kotlin.math.pow

object ColorTemperature {

    val PRESET_KELVINS = listOf(
        2800 to "Incandescent",
        3200 to "Tungsten",
        4000 to "Fluorescent",
        5600 to "Daylight",
        6500 to "Cloudy",
        7500 to "Shade"
    )

    fun kelvinToRgbGains(kelvin: Int, tint: Int = 0): FloatArray {
        val k = kelvin.coerceIn(2000, 12000).toDouble() / 100.0

        // Red calculation
        val r = if (k <= 66.0) 255.0 else {
            329.698727446 * (k - 60.0).pow(-0.1332047592)
        }

        // Green calculation
        val g = if (k <= 66.0) {
            99.4708025861 * ln(k) - 161.1195681661
        } else {
            288.1221695283 * (k - 60.0).pow(-0.0755148492)
        }

        // Blue calculation
        val b = if (k >= 66.0) 255.0 else if (k <= 19.0) 0.0 else {
            138.5177312231 * ln(k - 10.0) - 305.0447927307
        }

        val rClamped = r.coerceIn(0.0, 255.0)
        var gClamped = g.coerceIn(0.0, 255.0)
        val bClamped = b.coerceIn(0.0, 255.0)

        // Apply green/magenta tint offset (-100 to +100)
        if (tint != 0) {
            val tintFactor = 1.0 + (tint.coerceIn(-100, 100).toDouble() / 200.0)
            gClamped = (gClamped * tintFactor).coerceIn(1.0, 255.0)
        }

        // Return normalized as-shot neutral vector (g = 1.0)
        val gNorm = gClamped.coerceAtLeast(1.0)
        val rGain = (rClamped / gNorm).toFloat()
        val gGain = 1.0f
        val bGain = (bClamped / gNorm).toFloat()

        return floatArrayOf(rGain, gGain, bGain)
    }

    fun formatKelvin(kelvin: Int): String = "${kelvin}K"
}
