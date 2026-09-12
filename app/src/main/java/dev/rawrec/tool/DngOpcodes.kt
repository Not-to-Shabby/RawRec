package dev.rawrec.tool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary encoders for Adobe DNG 1.4 Specification Opcode Lists.
 *
 * All multi-byte data values within an opcode list are stored using BIG-ENDIAN
 * byte order, regardless of the byte order used for the rest of the DNG file.
 * (Adobe DNG Specification 1.4.0.0, section "Opcode List Format").
 */
object DngOpcodes {

    const val DNG_VERSION_1_3 = 0x01030000
    const val FLAG_OPTIONAL = 1

    /**
     * Builds Tag 51022 (OpcodeList3) containing a WarpRectilinear opcode (Opcode ID = 1).
     * Corrects geometric lens distortion (barrel / pincushion) in DaVinci Resolve & ACR.
     *
     * @param distortion 4-5 element array from LENS_DISTORTION [k1, k2, k3, k4, k5]
     * @param calibration 5 element array from LENS_INTRINSIC_CALIBRATION [fx, fy, cx, cy, s]
     * @param imageWidth image width in pixels
     * @param imageHeight image height in pixels
     */
    fun buildWarpRectilinearOpcodeList(
        distortion: DoubleArray,
        calibration: DoubleArray,
        imageWidth: Int,
        imageHeight: Int
    ): ByteArray? {
        if (distortion.size < 4 || calibration.size < 4 || imageWidth <= 0 || imageHeight <= 0) return null

        // Normalized optical center in [0, 1] range
        val cx = (calibration[2] / imageWidth.toDouble()).coerceIn(0.0, 1.0)
        val cy = (calibration[3] / imageHeight.toDouble()).coerceIn(0.0, 1.0)

        // Radial distortion polynomial coefficients: r_dist = r * (1 + k0*r^2 + k1*r^4 + k2*r^6 + k3*r^8)
        val k0 = distortion[0]
        val k1 = distortion[1]
        val k2 = distortion[2]
        val k3 = if (distortion.size >= 4) distortion[3] else 0.0

        // Opcode parameters size:
        // uint32: planes (1)
        // real64[4]: k0, k1, k2, k3 (4 * 8 = 32 bytes)
        // real64[2]: cx, cy (2 * 8 = 16 bytes)
        val paramSize = 4 + 32 + 16

        // Opcode header: 4 (id) + 4 (version) + 4 (flags) + 4 (paramSize) = 16 bytes
        // List header: 4 (count = 1)
        val totalSize = 4 + 16 + paramSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Opcode List Header: count of opcodes
        buf.putInt(1)

        // Opcode Header
        buf.putInt(1) // Opcode ID 1 = WarpRectilinear
        buf.putInt(DNG_VERSION_1_3)
        buf.putInt(FLAG_OPTIONAL)
        buf.putInt(paramSize)

        // Parameters
        buf.putInt(1) // planes = 1
        buf.putDouble(k0)
        buf.putDouble(k1)
        buf.putDouble(k2)
        buf.putDouble(k3)
        buf.putDouble(cx)
        buf.putDouble(cy)

        return buf.array()
    }

    /**
     * Builds Tag 51008 (OpcodeList1) containing a GainMap opcode (Opcode ID = 8).
     * Corrects optical lens vignetting / shading in DaVinci Resolve & ACR.
     */
    fun buildGainMapOpcodeList(
        rows: Int,
        cols: Int,
        gains: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): ByteArray? {
        if (rows < 2 || cols < 2 || gains.size < rows * cols * 4 || imageWidth <= 0 || imageHeight <= 0) return null

        val planes = 4 // R, Geven, Godd, B
        val mapPointsH = cols
        val mapPointsV = rows

        val mapSpacingH = 1.0 / (mapPointsH - 1)
        val mapSpacingV = 1.0 / (mapPointsV - 1)
        val mapOriginH = 0.0
        val mapOriginV = 0.0

        val gainFloatsCount = rows * cols * planes
        val paramSize = 16 + 24 + 32 + (gainFloatsCount * 4)

        val totalSize = 4 + 16 + paramSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buf.putInt(1) // 1 opcode

        buf.putInt(8) // Opcode ID 8 = GainMap
        buf.putInt(DNG_VERSION_1_3)
        buf.putInt(FLAG_OPTIONAL)
        buf.putInt(paramSize)

        // Bounding rect
        buf.putInt(0) // top
        buf.putInt(0) // left
        buf.putInt(imageHeight) // bottom
        buf.putInt(imageWidth) // right

        buf.putInt(0) // plane
        buf.putInt(planes) // planes
        buf.putInt(mapPointsH * planes) // rowPitch
        buf.putInt(planes) // colPitch
        buf.putInt(mapPointsH)
        buf.putInt(mapPointsV)

        buf.putDouble(mapSpacingH)
        buf.putDouble(mapSpacingV)
        buf.putDouble(mapOriginH)
        buf.putDouble(mapOriginV)

        for (i in 0 until gainFloatsCount) {
            buf.putFloat(gains[i])
        }

        return buf.array()
    }
}
