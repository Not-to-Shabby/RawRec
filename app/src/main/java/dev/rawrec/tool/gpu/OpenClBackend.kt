package dev.rawrec.tool.gpu

import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.CubeLut
import org.jocl.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance OpenCL 1.2+ GPU acceleration backend for RawRec.
 *
 * Executes parallel MIPI-10 bit unpacking, Bayer demosaicing, per-channel white balance,
 * analytical cinema tone curves, and luma-aware optical vignetting in a single fused GPU kernel.
 */
class OpenClBackend private constructor(
    override val deviceName: String,
    private val context: cl_context,
    private val commandQueue: cl_command_queue,
    private val program: cl_program,
    private val kernel: cl_kernel
) : GpuBackend {

    override val type: BackendType = BackendType.OPENCL
    override val isAvailable: Boolean = true

    private var inputMem: cl_mem? = null
    private var inputMemCapacity = 0L

    private var outputMem: cl_mem? = null
    private var outputMemCapacity = 0L

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
        val outW = width / 2
        val outH = height / 2
        val totalOutputPixels = outW * outH
        val outBytes = (totalOutputPixels * 4).toLong()
        val inBytes = mipiPayload.size.toLong()

        // 1. Ensure input device buffer
        if (inputMem == null || inBytes > inputMemCapacity) {
            inputMem?.let { CL.clReleaseMemObject(it) }
            inputMemCapacity = inBytes
            inputMem = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY, inputMemCapacity, null, null)
        }

        // 2. Ensure output device buffer
        if (outputMem == null || outBytes > outputMemCapacity) {
            outputMem?.let { CL.clReleaseMemObject(it) }
            outputMemCapacity = outBytes
            outputMem = CL.clCreateBuffer(context, CL.CL_MEM_WRITE_ONLY, outputMemCapacity, null, null)
        }

        val inMemObj = inputMem ?: error("Failed to create OpenCL input buffer")
        val outMemObj = outputMem ?: error("Failed to create OpenCL output buffer")

        // 3. Upload input payload to GPU (blocking write for safe JVM heap array pointer)
        CL.clEnqueueWriteBuffer(
            commandQueue,
            inMemObj,
            CL.CL_TRUE,
            0,
            inBytes,
            Pointer.to(mipiPayload),
            0,
            null,
            null
        )

        // 4. Compute White Balance gains
        val wb = if (applyCalibration) ColorScience.resolveWbGains(asShotNeutral) else doubleArrayOf(1.0, 1.0, 1.0)
        val wbGainsArr = floatArrayOf(wb[0].toFloat(), wb[1].toFloat(), wb[2].toFloat(), 1.0f)

        // Map black levels
        val blackArr = intArrayOf(
            blackLevels.getOrElse(0) { 0 },
            blackLevels.getOrElse(1) { 0 },
            blackLevels.getOrElse(2) { 0 },
            blackLevels.getOrElse(3) { 0 }
        )

        val profileIndex = when (profile) {
            ColorScience.ToneProfile.DEFAULT -> 0
            ColorScience.ToneProfile.CINE_FILMIC -> 1
            ColorScience.ToneProfile.CINE_HLG -> 2
            ColorScience.ToneProfile.CINE_OOTF -> 3
            ColorScience.ToneProfile.CINE_WARM -> 4
            ColorScience.ToneProfile.CINE_COOL -> 5
            ColorScience.ToneProfile.CINE_VINTAGE -> 6
            ColorScience.ToneProfile.CINE_BRIGHT -> 7
            ColorScience.ToneProfile.CINE_SOFT_MONO -> 8
            ColorScience.ToneProfile.CINE_MONO -> 9
            ColorScience.ToneProfile.FILM_AUTHENTIC -> 10
            ColorScience.ToneProfile.FILM_VIBRANT -> 11
            ColorScience.ToneProfile.VINTAGE_SEPIA -> 12
            ColorScience.ToneProfile.NORDIC_BLUE -> 13
            ColorScience.ToneProfile.CUSTOM_LUT -> 0 // Fallback to linear if custom LUT
        }

        // 5. Bind kernel arguments
        CL.clSetKernelArg(kernel, 0, Sizeof.cl_mem.toLong(), Pointer.to(inMemObj))
        CL.clSetKernelArg(kernel, 1, Sizeof.cl_mem.toLong(), Pointer.to(outMemObj))
        CL.clSetKernelArg(kernel, 2, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(width)))
        CL.clSetKernelArg(kernel, 3, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(height)))
        CL.clSetKernelArg(kernel, 4, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(cfa)))
        CL.clSetKernelArg(kernel, 5, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(packing)))
        CL.clSetKernelArg(kernel, 6, (Sizeof.cl_int * 4).toLong(), Pointer.to(blackArr))
        CL.clSetKernelArg(kernel, 7, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(whiteLevel)))
        CL.clSetKernelArg(kernel, 8, (Sizeof.cl_float * 4).toLong(), Pointer.to(wbGainsArr))
        CL.clSetKernelArg(kernel, 9, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(profileIndex)))
        CL.clSetKernelArg(kernel, 10, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(if (enableVignette) 1 else 0)))

        // 6. Launch 2D workgroup (one work-item per 2x2 output pixel)
        val globalWorkSize = longArrayOf(outW.toLong(), outH.toLong())
        CL.clEnqueueNDRangeKernel(commandQueue, kernel, 2, null, globalWorkSize, null, 0, null, null)

        // 7. Read back ARGB pixels
        val resultArgb = IntArray(totalOutputPixels)
        CL.clEnqueueReadBuffer(
            commandQueue,
            outMemObj,
            CL.CL_TRUE,
            0,
            outBytes,
            Pointer.to(resultArgb),
            0,
            null,
            null
        )

        // If custom LUT was supplied, apply it over the linearized image
        if (profile == ColorScience.ToneProfile.CUSTOM_LUT && customLut != null) {
            ColorScience.applyColorGrading(resultArgb, outW, outH, profile, enableVignette, null, customLut)
        }

        return resultArgb
    }

    override fun release() {
        inputMem?.let { CL.clReleaseMemObject(it) }
        outputMem?.let { CL.clReleaseMemObject(it) }
        CL.clReleaseKernel(kernel)
        CL.clReleaseProgram(program)
        CL.clReleaseCommandQueue(commandQueue)
        CL.clReleaseContext(context)
    }

    companion object {
        private val KERNEL_SOURCE = """
            inline ushort unpackMipi(const __global uchar* mipi, int width, int row, int col) {
                int rowStride = (width / 4) * 5;
                int group = col / 4;
                int rem = col % 4;
                int off = row * rowStride + group * 5;
                uchar b = mipi[off + rem];
                uchar b4 = mipi[off + 4];
                int shift = 6 - rem * 2;
                return (ushort)(((ushort)b << 2) | ((ushort)((b4 >> shift) & 3)));
            }

            inline ushort unpackRaw16(const __global ushort* raw, int width, int row, int col) {
                return raw[row * width + col];
            }

            inline float sigmoid(float x, float contrast, float pivot) {
                float norm = clamp(x, 0.0001f, 0.9999f);
                float p = clamp(pivot, 0.01f, 0.99f);
                return clamp(1.0f / (1.0f + pow((p / (1.0f - p)) * ((1.0f - norm) / norm), contrast)), 0.0f, 1.0f);
            }

            __kernel void process_frame(
                __global const uchar* inputPayload,
                __global uint* argbOut,
                const int width,
                const int height,
                const int cfa,
                const int packing,
                const int4 blackLevels,
                const int whiteLevel,
                const float4 wbGains,
                const int profile,
                const int enableVignette
            ) {
                int x = get_global_id(0);
                int y = get_global_id(1);
                int outW = width / 2;
                int outH = height / 2;
                if (x >= outW || y >= outH) return;

                int r0 = y * 2;
                int c0 = x * 2;

                ushort v0, v1, v2, v3;
                if (packing == 1) {
                    v0 = unpackMipi(inputPayload, width, r0, c0);
                    v1 = unpackMipi(inputPayload, width, r0, c0 + 1);
                    v2 = unpackMipi(inputPayload, width, r0 + 1, c0);
                    v3 = unpackMipi(inputPayload, width, r0 + 1, c0 + 1);
                } else {
                    const __global ushort* raw16 = (const __global ushort*)inputPayload;
                    v0 = unpackRaw16(raw16, width, r0, c0);
                    v1 = unpackRaw16(raw16, width, r0, c0 + 1);
                    v2 = unpackRaw16(raw16, width, r0 + 1, c0);
                    v3 = unpackRaw16(raw16, width, r0 + 1, c0 + 1);
                }

                int4 chMap;
                if (cfa == 0) chMap = (int4)(0, 1, 2, 3);
                else if (cfa == 1) chMap = (int4)(1, 0, 3, 2);
                else if (cfa == 2) chMap = (int4)(2, 3, 0, 1);
                else chMap = (int4)(3, 2, 1, 0);

                int b0 = ((int*)&blackLevels)[chMap.x];
                int b1 = ((int*)&blackLevels)[chMap.y];
                int b2 = ((int*)&blackLevels)[chMap.z];
                int b3 = ((int*)&blackLevels)[chMap.w];

                float s0 = clamp((float)(v0 - b0) / (float)(whiteLevel - b0), 0.0f, 1.0f);
                float s1 = clamp((float)(v1 - b1) / (float)(whiteLevel - b1), 0.0f, 1.0f);
                float s2 = clamp((float)(v2 - b2) / (float)(whiteLevel - b2), 0.0f, 1.0f);
                float s3 = clamp((float)(v3 - b3) / (float)(whiteLevel - b3), 0.0f, 1.0f);

                float r, g, b;
                if (cfa == 0) { r = s0; g = 0.5f * (s1 + s2); b = s3; }
                else if (cfa == 1) { r = s1; g = 0.5f * (s0 + s3); b = s2; }
                else if (cfa == 2) { r = s2; g = 0.5f * (s0 + s3); b = s1; }
                else { r = s3; g = 0.5f * (s1 + s2); b = s0; }

                r = clamp(r * wbGains.x, 0.0f, 1.0f);
                g = clamp(g * wbGains.y, 0.0f, 1.0f);
                b = clamp(b * wbGains.z, 0.0f, 1.0f);

                // Tone Profile Evaluation
                if (profile == 0) { // DEFAULT
                    r = pow(r, 0.5f);
                    g = pow(g, 0.5f);
                    b = pow(b, 0.5f);
                } else if (profile == 1) { // CINE_FILMIC
                    r = sigmoid(r, 1.32f, 0.35f);
                    g = sigmoid(g, 1.32f, 0.35f);
                    b = sigmoid(b, 1.32f, 0.35f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    float shadow = (1.0f - lum) * (1.0f - lum);
                    float s = sin(clamp(lum * 3.14159265f, 0.0f, 3.14159265f));
                    r = clamp(r - 0.02f * shadow + 0.03f * s, 0.0f, 1.0f);
                    g = clamp(g + 0.01f * s, 0.0f, 1.0f);
                    b = clamp(b + 0.03f * shadow - 0.02f * s, 0.0f, 1.0f);
                    float satScale = 1.08f * (0.92f + 0.16f * s);
                    r = clamp(lum + (r - lum) * satScale, 0.0f, 1.0f);
                    g = clamp(lum + (g - lum) * satScale, 0.0f, 1.0f);
                    b = clamp(lum + (b - lum) * satScale, 0.0f, 1.0f);
                } else if (profile == 2) { // CINE_HLG
                    r = (r <= 1.0f / 12.0f) ? sqrt(3.0f * r) : 0.17883277f * log(12.0f * r - 0.28466892f) + 0.55991073f;
                    g = (g <= 1.0f / 12.0f) ? sqrt(3.0f * g) : 0.17883277f * log(12.0f * g - 0.28466892f) + 0.55991073f;
                    b = (b <= 1.0f / 12.0f) ? sqrt(3.0f * b) : 0.17883277f * log(12.0f * b - 0.28466892f) + 0.55991073f;
                } else if (profile == 3) { // CINE_OOTF
                    r = (r < 0.018f) ? 4.5f * r : 1.099f * pow(r, 0.45f) - 0.099f;
                    g = (g < 0.018f) ? 4.5f * g : 1.099f * pow(g, 0.45f) - 0.099f;
                    b = (b < 0.018f) ? 4.5f * b : 1.099f * pow(b, 0.45f) - 0.099f;
                } else if (profile == 4) { // CINE_WARM
                    r = sigmoid(r, 1.28f, 0.33f);
                    g = sigmoid(g, 1.28f, 0.33f);
                    b = sigmoid(b, 1.28f, 0.33f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    r = clamp(r * 1.08f + 0.02f * lum, 0.0f, 1.0f);
                    g = clamp(g * 1.02f + 0.01f * lum, 0.0f, 1.0f);
                    b = clamp(b * 0.90f, 0.0f, 1.0f);
                    r = clamp(lum + (r - lum) * 1.15f, 0.0f, 1.0f);
                    g = clamp(lum + (g - lum) * 1.15f, 0.0f, 1.0f);
                    b = clamp(lum + (b - lum) * 1.15f, 0.0f, 1.0f);
                } else if (profile == 5) { // CINE_COOL
                    r = sigmoid(r, 1.30f, 0.36f);
                    g = sigmoid(g, 1.30f, 0.36f);
                    b = sigmoid(b, 1.30f, 0.36f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    float shadow = pow(1.0f - lum, 1.5f);
                    r = clamp(r * 0.92f - 0.03f * shadow, 0.0f, 1.0f);
                    g = clamp(g * 0.98f + 0.01f * shadow, 0.0f, 1.0f);
                    b = clamp(b * 1.14f + 0.06f * shadow, 0.0f, 1.0f);
                } else if (profile == 6) { // CINE_VINTAGE
                    float rS = sigmoid(r, 1.20f, 0.36f);
                    float gS = sigmoid(g, 1.20f, 0.36f);
                    float bS = sigmoid(b, 1.20f, 0.36f);
                    r = clamp(rS * 0.90f + 0.06f, 0.0f, 1.0f);
                    g = clamp(gS * 0.86f + 0.05f, 0.0f, 1.0f);
                    b = clamp(bS * 0.78f + 0.07f, 0.0f, 1.0f);
                } else if (profile == 7) { // CINE_BRIGHT
                    r = sigmoid(r, 1.15f, 0.28f);
                    g = sigmoid(g, 1.15f, 0.28f);
                    b = sigmoid(b, 1.15f, 0.28f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    r = clamp(lum + (r - lum) * 1.22f, 0.0f, 1.0f);
                    g = clamp(lum + (g - lum) * 1.22f, 0.0f, 1.0f);
                    b = clamp(lum + (b - lum) * 1.22f, 0.0f, 1.0f);
                } else if (profile == 8) { // CINE_SOFT_MONO
                    float panchro = pow(0.299f * r + 0.587f * g + 0.114f * b, 0.5f);
                    float graded = sigmoid(panchro, 1.12f, 0.38f);
                    r = graded; g = graded; b = graded;
                } else if (profile == 9) { // CINE_MONO
                    float panchro = pow(0.35f * r + 0.55f * g + 0.10f * b, 0.5f);
                    float graded = sigmoid(panchro, 1.85f, 0.38f);
                    r = graded; g = graded; b = graded;
                } else if (profile == 10) { // FILM_AUTHENTIC
                    r = sigmoid(r, 1.55f, 0.36f);
                    g = sigmoid(g, 1.55f, 0.36f);
                    b = sigmoid(b, 1.55f, 0.36f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    float s = sin(clamp(lum * 3.14159265f, 0.0f, 3.14159265f));
                    float satScale = 0.94f + 0.12f * s;
                    r = clamp(lum + (r - lum) * satScale, 0.0f, 1.0f);
                    g = clamp(lum + (g - lum) * satScale, 0.0f, 1.0f);
                    b = clamp(lum + (b - lum) * satScale, 0.0f, 1.0f);
                } else if (profile == 11) { // FILM_VIBRANT
                    r = sigmoid(r, 1.25f, 0.30f);
                    g = sigmoid(g, 1.25f, 0.30f);
                    b = sigmoid(b, 1.25f, 0.30f);
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    r = clamp(lum + (r - lum) * 1.24f, 0.0f, 1.0f);
                    g = clamp(lum + (g - lum) * 1.24f, 0.0f, 1.0f);
                    b = clamp(lum + (b - lum) * 1.24f, 0.0f, 1.0f);
                } else if (profile == 12) { // VINTAGE_SEPIA
                    float luma = pow(0.299f * r + 0.587f * g + 0.114f * b, 0.5f);
                    float graded = sigmoid(luma, 1.30f, 0.36f);
                    r = clamp(graded * 1.16f + 0.04f, 0.0f, 1.0f);
                    g = clamp(graded * 0.94f + 0.02f, 0.0f, 1.0f);
                    b = clamp(graded * 0.72f, 0.0f, 1.0f);
                } else if (profile == 13) { // NORDIC_BLUE
                    float luma = pow(0.299f * r + 0.587f * g + 0.114f * b, 0.5f);
                    float graded = sigmoid(luma, 1.30f, 0.36f);
                    r = clamp(graded * 0.85f, 0.0f, 1.0f);
                    g = clamp(graded * 0.96f + 0.01f, 0.0f, 1.0f);
                    b = clamp(graded * 1.18f + 0.03f, 0.0f, 1.0f);
                }

                // Optical Vignetting
                if (enableVignette && profile != 0 && profile != 3) {
                    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    float uNorm = (float)x / (float)outW;
                    float vNorm = (float)y / (float)outH;
                    float dx = (uNorm - 0.5f) * 1.15f;
                    float dy = (vNorm - 0.5f);
                    float dist = sqrt(dx * dx + dy * dy);
                    float t = clamp((dist - 0.35f) / (0.82f - 0.35f), 0.0f, 1.0f);
                    float falloff = t * t * (3.0f - 2.0f * t);
                    float factor = 1.0f - falloff * 0.42f * (1.0f - pow(lum, 2.2f));
                    r = clamp(r * factor, 0.0f, 1.0f);
                    g = clamp(g * factor, 0.0f, 1.0f);
                    b = clamp(b * factor, 0.0f, 1.0f);
                }

                uint ir = (uint)(clamp(r, 0.0f, 1.0f) * 255.0f);
                uint ig = (uint)(clamp(g, 0.0f, 1.0f) * 255.0f);
                uint ib = (uint)(clamp(b, 0.0f, 1.0f) * 255.0f);
                argbOut[y * outW + x] = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
            }
        """.trimIndent()

        /**
         * Initializes and returns an OpenCL backend instance if available on the host machine.
         */
        fun create(): OpenClBackend? = runCatching {
            CL.setExceptionsEnabled(true)
            val numPlatforms = IntArray(1)
            CL.clGetPlatformIDs(0, null, numPlatforms)
            if (numPlatforms[0] == 0) return null

            val platforms = arrayOfNulls<cl_platform_id>(numPlatforms[0])
            CL.clGetPlatformIDs(platforms.size, platforms, null)

            // Prefer discrete GPU platform (NVIDIA / AMD) over integrated
            var selectedPlatform: cl_platform_id? = null
            var selectedDevice: cl_device_id? = null
            var selectedDevName = ""

            for (p in platforms) {
                if (p == null) continue
                val numDevices = IntArray(1)
                val ret = CL.clGetDeviceIDs(p, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevices)
                if (ret == CL.CL_SUCCESS && numDevices[0] > 0) {
                    val devices = arrayOfNulls<cl_device_id>(numDevices[0])
                    CL.clGetDeviceIDs(p, CL.CL_DEVICE_TYPE_GPU, devices.size, devices, null)
                    for (d in devices) {
                        if (d != null) {
                            val size = LongArray(1)
                            CL.clGetDeviceInfo(d, CL.CL_DEVICE_NAME, 0, null, size)
                            val nameBytes = ByteArray(size[0].toInt())
                            CL.clGetDeviceInfo(d, CL.CL_DEVICE_NAME, nameBytes.size.toLong(), Pointer.to(nameBytes), null)
                            val devName = String(nameBytes).trim().trimEnd(0.toChar())

                            selectedPlatform = p
                            selectedDevice = d
                            selectedDevName = devName
                            if (devName.contains("NVIDIA", ignoreCase = true) || devName.contains("Radeon", ignoreCase = true) || devName.contains("RTX", ignoreCase = true)) {
                                break
                            }
                        }
                    }
                }
                if (selectedDevice != null && (selectedDevName.contains("NVIDIA", ignoreCase = true) || selectedDevName.contains("RTX", ignoreCase = true))) {
                    break
                }
            }

            if (selectedPlatform == null || selectedDevice == null) return null

            val props = cl_context_properties()
            props.addProperty(CL.CL_CONTEXT_PLATFORM.toLong(), selectedPlatform)
            val context = CL.clCreateContext(props, 1, arrayOf(selectedDevice), null, null, null)
            val commandQueue = CL.clCreateCommandQueue(context, selectedDevice, 0, null)

            val program = CL.clCreateProgramWithSource(context, 1, arrayOf(KERNEL_SOURCE), null, null)
            CL.clBuildProgram(program, 0, null, "-cl-fast-relaxed-math", null, null)
            val kernel = CL.clCreateKernel(program, "process_frame", null)

            OpenClBackend(
                deviceName = selectedDevName,
                context = context,
                commandQueue = commandQueue,
                program = program,
                kernel = kernel
            )
        }.getOrNull()
    }
}
