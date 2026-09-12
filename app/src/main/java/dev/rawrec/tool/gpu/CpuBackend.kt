package dev.rawrec.tool.gpu

import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.CubeLut
import dev.rawrec.tool.Rvtool
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CpuBackend : GpuBackend {
    override val type: BackendType = BackendType.CPU
    override val deviceName: String = "Host CPU (${Runtime.getRuntime().availableProcessors()} cores)"
    override val isAvailable: Boolean = true

    override fun processFrame(
        mipiPayload: ByteArray,
        width: Int,
        height: Int,
        cfa: Int,
        packing: Int,
        blackLevels: List<Int>,
        whiteLevel: Int,
        asShotNeutral: FloatArray?,
        applyCalibration: Boolean,
        profile: ColorScience.ToneProfile,
        enableVignette: Boolean,
        customLut: CubeLut?,
        downsample: Int
    ): IntArray {
        val n = width * height
        val samples = when (packing) {
            1 -> {
                val s = ShortArray(n)
                var si = 0
                var di = 0
                fun u(b: Byte) = b.toInt() and 0xFF
                while (si < n && di + 5 <= mipiPayload.size) {
                    val b4 = u(mipiPayload[di + 4])
                    s[si] = ((u(mipiPayload[di]) shl 2) or ((b4 shr 6) and 3)).toShort()
                    if (si + 1 < n) s[si + 1] = ((u(mipiPayload[di + 1]) shl 2) or ((b4 shr 4) and 3)).toShort()
                    if (si + 2 < n) s[si + 2] = ((u(mipiPayload[di + 2]) shl 2) or ((b4 shr 2) and 3)).toShort()
                    if (si + 3 < n) s[si + 3] = ((u(mipiPayload[di + 3]) shl 2) or (b4 and 3)).toShort()
                    si += 4
                    di += 5
                }
                s
            }
            else -> {
                val bb = ByteBuffer.wrap(mipiPayload).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(n) { bb.short }
            }
        }

        return Rvtool.quadRgb(
            samples = samples,
            width = width,
            height = height,
            cfa = cfa,
            blackLevels = blackLevels,
            whiteLevel = whiteLevel,
            asShotNeutral = asShotNeutral,
            applyCalibration = applyCalibration,
            profile = profile,
            enableVignette = enableVignette,
            customLut = customLut
        )
    }

    override fun release() {
        // No native resources on CPU
    }
}
