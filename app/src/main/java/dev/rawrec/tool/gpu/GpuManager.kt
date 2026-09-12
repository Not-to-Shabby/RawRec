package dev.rawrec.tool.gpu

import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.CubeLut

object GpuManager {

    private val cpuBackend: CpuBackend by lazy { CpuBackend() }
    private val openClBackend: OpenClBackend? by lazy { OpenClBackend.create() }
    private val vulkanBackend: VulkanBackend? by lazy { VulkanBackend.create() }
    private val openGlBackend: OpenGlBackend? by lazy { OpenGlBackend.create() }

    private fun isAndroidRuntime(): Boolean = runCatching {
        Class.forName("android.os.Build")
        true
    }.getOrDefault(false)

    @Volatile
    var requestedBackendType: BackendType = if (isAndroidRuntime()) BackendType.OPENGL else BackendType.OPENCL

    /**
     * Resolves the active backend according to user request, prioritizing
     * hardware acceleration (OpenGL ES on Android, OpenCL on Desktop) with
     * automatic, seamless fallback to CPU.
     */
    val activeBackend: GpuBackend
        get() = when (requestedBackendType) {
            BackendType.OPENGL -> openGlBackend ?: cpuBackend
            BackendType.OPENCL -> openClBackend ?: cpuBackend
            BackendType.VULKAN -> vulkanBackend ?: cpuBackend
            BackendType.CPU -> cpuBackend
        }

    val availableBackends: List<GpuBackend>
        get() = buildList {
            openGlBackend?.let { add(it) }
            openClBackend?.let { add(it) }
            vulkanBackend?.let { add(it) }
            add(cpuBackend)
        }

    fun isBackendAvailable(type: BackendType): Boolean = when (type) {
        BackendType.OPENGL -> openGlBackend != null
        BackendType.OPENCL -> openClBackend != null
        BackendType.VULKAN -> vulkanBackend != null
        BackendType.CPU -> true
    }

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
    ): IntArray {
        return activeBackend.processFrame(
            mipiPayload, width, height, cfa, packing,
            blackLevels, whiteLevel, asShotNeutral, applyCalibration,
            profile, enableVignette, customLut, downsample
        )
    }
}
