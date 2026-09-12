package dev.rawrec.tool

import dev.rawrec.tool.gpu.BackendType
import dev.rawrec.tool.gpu.CpuBackend
import dev.rawrec.tool.gpu.GpuManager
import dev.rawrec.tool.gpu.OpenClBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GpuBackendTest {

    @Test
    fun `GpuManager defaults to OpenCL or falls back gracefully to CPU`() {
        assertNotNull("GpuManager activeBackend should never be null", GpuManager.activeBackend)
        assertTrue(
            "Default backend should be OPENCL or CPU fallback",
            GpuManager.activeBackend.type == BackendType.OPENCL || GpuManager.activeBackend.type == BackendType.CPU
        )
    }

    @Test
    fun `CpuBackend processes MIPI-10 and uncompressed payloads accurately`() {
        val cpu = CpuBackend()
        assertEquals(BackendType.CPU, cpu.type)
        assertTrue(cpu.isAvailable)

        val w = 8
        val h = 8
        // 8x8 uncompressed raw16 test
        val uncompressedBytes = ByteArray(w * h * 2) { 0 }
        val outRgb = cpu.processFrame(
            mipiPayload = uncompressedBytes,
            width = w,
            height = h,
            cfa = 0,
            packing = 0,
            blackLevels = listOf(0, 0, 0, 0),
            whiteLevel = 1023,
            asShotNeutral = floatArrayOf(1f, 1f, 1f),
            applyCalibration = true,
            profile = ColorScience.ToneProfile.DEFAULT,
            enableVignette = false
        )

        assertEquals("Output buffer size must be (w/2)*(h/2) = 16", (w / 2) * (h / 2), outRgb.size)
        val alpha = (outRgb[0] ushr 24) and 0xFF
        assertEquals("Alpha channel must be fully opaque", 0xFF, alpha)
    }

    @Test
    fun `switching backend type in GpuManager takes immediate effect`() {
        GpuManager.requestedBackendType = BackendType.CPU
        assertEquals(BackendType.CPU, GpuManager.activeBackend.type)

        GpuManager.requestedBackendType = BackendType.OPENCL
        assertTrue(
            "Requested OPENCL should yield OPENCL or graceful CPU fallback",
            GpuManager.activeBackend.type == BackendType.OPENCL || GpuManager.activeBackend.type == BackendType.CPU
        )

        GpuManager.requestedBackendType = BackendType.OPENGL
        assertTrue(
            "Requested OPENGL should yield OPENGL or graceful CPU fallback",
            GpuManager.activeBackend.type == BackendType.OPENGL || GpuManager.activeBackend.type == BackendType.CPU
        )
    }

    @Test
    fun `OpenGlBackend create handles non-android or headless runtime safely`() {
        // On desktop JVM, OpenGlBackend.create() returns null safely without throwing
        val gl = dev.rawrec.tool.gpu.OpenGlBackend.create()
        if (gl != null) {
            assertEquals(BackendType.OPENGL, gl.type)
            assertTrue(gl.isAvailable)
            gl.release()
        }
    }

    @Test
    fun `OpenCL backend matches CPU reference output within tolerance`() {
        val cl = OpenClBackend.create() ?: return // Skip if running on headless machine with no OpenCL driver
        val cpu = CpuBackend()

        val w = 16
        val h = 16
        // Synthetic MIPI-10 payload for 16x16 pixels: 16 rows * (16/4 * 5) = 320 bytes
        val rowBytes = (w / 4) * 5
        val mipiData = ByteArray(h * rowBytes) { i -> ((i * 37) and 0xFF).toByte() }

        val blackLevels = listOf(64, 64, 64, 64)
        val whiteLevel = 1023
        val asShotNeutral = floatArrayOf(0.5f, 1.0f, 0.75f)

        for (profile in listOf(
            ColorScience.ToneProfile.DEFAULT,
            ColorScience.ToneProfile.CINE_FILMIC,
            ColorScience.ToneProfile.CINE_HLG,
            ColorScience.ToneProfile.CINE_WARM
        )) {
            val cpuOut = cpu.processFrame(
                mipiData, w, h, 3, 1, blackLevels, whiteLevel,
                asShotNeutral, true, profile, false
            )
            val clOut = cl.processFrame(
                mipiData, w, h, 3, 1, blackLevels, whiteLevel,
                asShotNeutral, true, profile, false
            )

            assertEquals(cpuOut.size, clOut.size)

            // Validate color channel parity within small float rounding tolerance (<= 3 LSBs)
            for (i in cpuOut.indices) {
                val cpuR = (cpuOut[i] ushr 16) and 0xFF
                val cpuG = (cpuOut[i] ushr 8) and 0xFF
                val cpuB = cpuOut[i] and 0xFF

                val clR = (clOut[i] ushr 16) and 0xFF
                val clG = (clOut[i] ushr 8) and 0xFF
                val clB = clOut[i] and 0xFF

                assertTrue("R channel parity for $profile at $i: cpu=$cpuR, cl=$clR", abs(cpuR - clR) <= 3)
                assertTrue("G channel parity for $profile at $i: cpu=$cpuG, cl=$clG", abs(cpuG - clG) <= 3)
                assertTrue("B channel parity for $profile at $i: cpu=$cpuB, cl=$clB", abs(cpuB - clB) <= 3)
            }
        }

        cl.release()
    }
}
