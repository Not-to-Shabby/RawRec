package dev.rawrec.tool.gpu

import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.CubeLut

enum class BackendType(val id: String, val displayName: String) {
    OPENCL("opencl", "OpenCL (Default GPU)"),
    VULKAN("vulkan", "Vulkan Compute"),
    OPENGL("opengl", "OpenGL ES (Android GPU)"),
    CPU("cpu", "CPU (Pure Kotlin)")
}

/**
 * Unified hardware acceleration abstraction for raw video decoding, debayering,
 * tone curves, and 3D LUT application.
 */
interface GpuBackend {
    val type: BackendType
    val deviceName: String
    val isAvailable: Boolean

    fun processFrame(
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
        customLut: CubeLut? = null,
        downsample: Int = 1
    ): IntArray

    fun release()
}
