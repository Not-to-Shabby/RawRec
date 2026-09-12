package dev.rawrec.app.control

data class CameraControlState(
    val iso: Int = 100,
    val autoIso: Boolean = false,
    val exposureNs: Long = 20_000_000L, // 1/50s
    val autoExposure: Boolean = false,
    val shutterAngle: Double = 180.0,
    val useShutterAngle: Boolean = false,
    val focusDiopters: Float = 0.0f, // infinity
    val autoFocus: Boolean = true,
    val whiteBalanceKelvin: Int = 5600, // Daylight
    val whiteBalanceTint: Int = 0,
    val autoWhiteBalance: Boolean = true,
    val targetFps: Double = 30.0,
    /**
     * Viewfinder framing mode: 0 Full, 1 Widescreen (2.39:1), 2 16:9, 3 4:3, 4 1:1.
     * Shared state like ISO/shutter — the recording honors it (WYSIWYG:
     * wide aspects store the same center band the letterboxed viewfinder
     * shows; 1:1 stores a square center crop; Full/4:3 store the full sensor).
     */
    val aspectIndex: Int = 0
) {
    /** Recording framing ratio for the aspect mode, or null for full-sensor. */
    fun framingAspect(isLandscape: Boolean = false): Float? = when (aspectIndex) {
        1 -> 2.39f
        2 -> 16f / 9f
        3 -> null // 4:3 native sensor frame (no crop)
        4 -> 1.0f
        else -> null
    }

    val framingAspect: Float? get() = framingAspect(false)
    val effectiveExposureNs: Long get() =
        if (useShutterAngle) ShutterCalculator.shutterAngleToExposureNs(shutterAngle, targetFps)
        else exposureNs

    val effectiveRgbGains: FloatArray get() =
        ColorTemperature.kelvinToRgbGains(whiteBalanceKelvin, whiteBalanceTint)

    val formattedExposure: String get() =
        if (autoExposure) "A" else if (useShutterAngle) ShutterCalculator.formatShutterAngle(shutterAngle)
        else ShutterCalculator.formatExposureFraction(exposureNs)

    val formattedIso: String get() =
        if (autoExposure) "A" else iso.toString()

    val formattedFocus: String get() =
        if (autoFocus) "AF" else FocusCalculator.formatFocusDistance(focusDiopters)

    val formattedWhiteBalance: String get() =
        if (autoWhiteBalance) "AWB" else ColorTemperature.formatKelvin(whiteBalanceKelvin)

    val formattedFps: String get() = when {
        kotlin.math.abs(targetFps - 23.976) < 0.01 -> "23.98"
        kotlin.math.abs(targetFps - 29.97) < 0.01 -> "29.97"
        targetFps == targetFps.toLong().toDouble() -> targetFps.toLong().toString()
        else -> "%.2f".format(targetFps)
    }
}
