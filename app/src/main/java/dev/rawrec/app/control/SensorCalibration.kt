package dev.rawrec.app.control

/**
 * Pure colorimetry helpers mapping Camera2 sensor calibration onto the RVSP
 * header's canonical channel order (R, Gr, Gb, B) and computing DNG values.
 *
 * No android.* imports: everything here is unit-testable on the plain JVM,
 * mirroring the repo convention that camera-dependent code stays in the
 * caller while the math stays pure.
 *
 * Reference behavior: AOSP DngCreator (core/jni/android_hardware_camera2_
 * DngCreator.cpp) writes sensor calibration as DIRECT copies:
 *   SENSOR_COLOR_TRANSFORM1   -> DNG tag 50721 ColorMatrix1
 *   SENSOR_NEUTRAL_COLOR_POINT-> DNG tag 50728 AsShotNeutral
 *   SENSOR_BLACK_LEVEL_PATTERN-> DNG tag 50714 BlackLevel
 * It does NOT invert or recombine matrices — this module must not either.
 */
object SensorCalibration {

    const val DEFAULT_ILLUMINANT = 21 // D65, TIFF A0 (21) standard light source

    /**
     * Quadrant channel names in row-column scan order (TL, TR, BL, BR),
     * per SENSOR_INFO_COLOR_FILTER_ARRANGEMENT.
     */
    fun cfaQuadrantNames(cfaPattern: Int): Array<String> = when (cfaPattern) {
        0 -> arrayOf("R", "Gr", "Gb", "B")   // RGGB
        1 -> arrayOf("Gr", "R", "B", "Gb")   // GRBG
        2 -> arrayOf("Gb", "B", "R", "Gr")   // GBRG
        else -> arrayOf("B", "Gb", "Gr", "R") // BGGR
    }

    /**
     * Remap a 2x2 row-column-scan order value array (as delivered by
     * SENSOR_BLACK_LEVEL_PATTERN) into the RVSP header's canonical
     * R, Gr, Gb, B channel order.
     */
    fun blackLevelToChannelOrder(
        rowColValues: IntArray,
        cfaPattern: Int
    ): IntArray {
        require(rowColValues.size == 4) { "expected 4 quadrant values" }
        val names = cfaQuadrantNames(cfaPattern)
        // First occurrence of each channel in scan order picks the quadrant;
        // for Gb/Gr the naming follows CFA convention (Gr shares the row
        // with R, Gb shares the row with B).
        val out = IntArray(4)
        for (i in names.indices) {
            val idx = when (names[i]) {
                "R" -> 0; "Gr" -> 1; "Gb" -> 2; else -> 3
            }
            out[idx] = rowColValues[i]
        }
        return out
    }

    /**
     * DNG AsShotNeutral from a CaptureResult's COLOR_CORRECTION_GAINS
     * (R, Gr, Gb, B ordering as reported by the camera HAL).
     *
     * Per the DNG 1.6 spec, AsShotNeutral is the camera-RGB value that maps
     * to neutral in XYZ — i.e. the per-channel inverse of the white-balance
     * gains. AOSP derives it from SENSOR_NEUTRAL_COLOR_POINT; when that key
     * is absent, 1/gains is the standard fallback used by camera apps.
     * The two green gains are averaged into the single G entry.
     */
    fun asShotNeutralFromGains(gainsRggb: FloatArray): FloatArray {
        require(gainsRggb.size == 4) { "expected R,Gr,Gb,B gains" }
        val gAvg = (gainsRggb[1] + gainsRggb[2]) / 2f
        fun inv(v: Float): Float = if (v > 0f) 1f / v else 1f
        return floatArrayOf(inv(gainsRggb[0]), inv(gAvg), inv(gainsRggb[3]))
    }

    /**
     * AsShotNeutral when only the app's manual RGB gains are known (no
     * CaptureResult). [gainsRgb] is (R, G, B) as applied to the image.
     */
    fun asShotNeutralFromManualRgb(gainsRgb: FloatArray): FloatArray {
        require(gainsRgb.size == 3) { "expected R,G,B gains" }
        return floatArrayOf(
            if (gainsRgb[0] > 0f) 1f / gainsRgb[0] else 1f,
            if (gainsRgb[1] > 0f) 1f / gainsRgb[1] else 1f,
            if (gainsRgb[2] > 0f) 1f / gainsRgb[2] else 1f
        )
    }

    /**
     * Extract the 3x3 row-major float matrix from a Camera2
     * ColorSpaceTransform's Rational[9], with double precision for the
     * division.
     */
    fun colorTransformToFloats(rationalNums: IntArray, rationalDens: IntArray): FloatArray {
        require(rationalNums.size == 9 && rationalDens.size == 9) {
            "expected 3x3 rationals"
        }
        val out = FloatArray(9)
        for (i in 0 until 9) {
            val d = rationalDens[i].toDouble()
            out[i] = if (d != 0.0) (rationalNums[i].toDouble() / d).toFloat() else 0f
        }
        return out
    }

    /**
     * The 3x3 ColorMatrix1 to store when the sensor does not report one:
     * identity. Old files extracted with identity stay valid.
     */
    fun identityMatrix(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun isIdentity(m: FloatArray, epsilon: Float = 1e-6f): Boolean {
        if (m.size != 9) return false
        for (i in 0 until 9) {
            val expected = if (i % 4 == 0) 1f else 0f
            if (kotlin.math.abs(m[i] - expected) > epsilon) return false
        }
        return true
    }
}
