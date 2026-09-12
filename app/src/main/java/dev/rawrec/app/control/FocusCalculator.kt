package dev.rawrec.app.control

import kotlin.math.roundToInt

object FocusCalculator {

    fun dioptersToMeters(diopters: Float): Float {
        if (diopters <= 0.001f) return Float.POSITIVE_INFINITY
        return 1.0f / diopters
    }

    fun metersToDiopters(meters: Float): Float {
        if (meters.isInfinite() || meters <= 0.0f) return 0.0f
        return 1.0f / meters
    }

    fun formatFocusDistance(diopters: Float): String {
        if (diopters <= 0.05f) return "∞"
        val meters = dioptersToMeters(diopters)
        return if (meters >= 1.0f) {
            "%.1fm".format(meters)
        } else {
            val cm = (meters * 100.0f).roundToInt()
            "${cm}cm"
        }
    }

    fun hyperfocalDistanceMeters(
        focalLengthMm: Float,
        fNumber: Float,
        cocMm: Float = 0.015f
    ): Float {
        require(fNumber > 0f) { "fNumber must be > 0" }
        require(cocMm > 0f) { "cocMm must be > 0" }
        val hMm = (focalLengthMm * focalLengthMm) / (fNumber * cocMm) + focalLengthMm
        return hMm / 1000.0f
    }
}
