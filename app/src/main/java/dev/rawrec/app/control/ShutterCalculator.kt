package dev.rawrec.app.control

import kotlin.math.roundToInt
import kotlin.math.roundToLong

object ShutterCalculator {

    val STANDARD_ANGLES = listOf(45.0, 90.0, 144.0, 172.8, 180.0, 270.0, 360.0)

    val STANDARD_FRACTIONS_DENOMINATORS = listOf(
        24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 240, 250, 500, 1000, 2000, 4000, 8000
    )

    fun shutterAngleToExposureNs(angleDegrees: Double, fps: Double): Long {
        require(angleDegrees > 0.0) { "Angle must be positive: $angleDegrees" }
        require(fps > 0.0) { "FPS must be positive: $fps" }
        val seconds = angleDegrees / (360.0 * fps)
        return (seconds * 1_000_000_000.0).roundToLong().coerceAtLeast(1_000L)
    }

    fun exposureNsToShutterAngle(exposureNs: Long, fps: Double): Double {
        require(exposureNs > 0) { "Exposure must be positive: $exposureNs" }
        require(fps > 0.0) { "FPS must be positive: $fps" }
        val seconds = exposureNs / 1_000_000_000.0
        return (seconds * fps * 360.0).coerceIn(1.0, 360.0)
    }

    fun fractionToExposureNs(denominator: Int): Long {
        require(denominator > 0) { "Denominator must be positive: $denominator" }
        return (1_000_000_000.0 / denominator).roundToLong()
    }

    fun formatExposureFraction(exposureNs: Long): String {
        if (exposureNs <= 0) return "0s"
        val seconds = exposureNs / 1_000_000_000.0
        if (seconds >= 1.0) {
            return if (seconds == seconds.roundToLong().toDouble()) {
                "${seconds.roundToLong()}s"
            } else {
                "%.1fs".format(seconds)
            }
        }
        val denom = (1.0 / seconds).roundToInt()
        return "1/${denom}s"
    }

    fun formatShutterAngle(angleDegrees: Double): String {
        val rounded = (angleDegrees * 10.0).roundToInt() / 10.0
        return if (rounded == rounded.roundToInt().toDouble()) {
            "${rounded.roundToInt()}°"
        } else {
            "%.1f°".format(rounded)
        }
    }
}
