package dev.rawrec.tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngOpcodeTest {

    @Test
    fun `buildWarpRectilinearOpcodeList encodes valid DNG 1_3 big-endian opcode`() {
        val distortion = doubleArrayOf(-0.02, 0.005, -0.001, 0.0)
        val calibration = doubleArrayOf(3000.0, 3000.0, 2048.0, 1536.0, 0.0)
        val w = 4096
        val h = 3072

        val bytes = DngOpcodes.buildWarpRectilinearOpcodeList(distortion, calibration, w, h)
        assertNotNull(bytes)
        val buf = ByteBuffer.wrap(bytes!!).order(ByteOrder.BIG_ENDIAN)

        // List Header: count of opcodes
        assertEquals(1, buf.int)

        // Opcode Header:
        // ID 1 = WarpRectilinear
        assertEquals(1, buf.int)
        // DNG Version 1.3 = 0x01030000
        assertEquals(0x01030000, buf.int)
        // Flags (1 = optional)
        assertEquals(1, buf.int)
        // Param size = 4 (planes) + 32 (4 * real64) + 16 (2 * real64) = 52
        assertEquals(52, buf.int)

        // Parameters
        assertEquals(1, buf.int) // planes
        assertEquals(-0.02, buf.double, 1e-6) // k0
        assertEquals(0.005, buf.double, 1e-6) // k1
        assertEquals(-0.001, buf.double, 1e-6) // k2
        assertEquals(0.0, buf.double, 1e-6) // k3
        assertEquals(2048.0 / 4096.0, buf.double, 1e-6) // cx
        assertEquals(1536.0 / 3072.0, buf.double, 1e-6) // cy
    }

    @Test
    fun `buildWarpRectilinearOpcodeList returns null on invalid inputs`() {
        assertNull(DngOpcodes.buildWarpRectilinearOpcodeList(doubleArrayOf(), doubleArrayOf(), 4096, 3072))
        assertNull(DngOpcodes.buildWarpRectilinearOpcodeList(doubleArrayOf(1.0, 2.0, 3.0, 4.0), doubleArrayOf(), 0, 0))
    }

    @Test
    fun `buildGainMapOpcodeList encodes valid DNG GainMap opcode`() {
        val rows = 2
        val cols = 2
        val gains = floatArrayOf(
            1.0f, 1.0f, 1.0f, 1.0f,
            1.1f, 1.1f, 1.1f, 1.1f,
            1.2f, 1.2f, 1.2f, 1.2f,
            1.3f, 1.3f, 1.3f, 1.3f
        )
        val w = 4096
        val h = 3072

        val bytes = DngOpcodes.buildGainMapOpcodeList(rows, cols, gains, w, h)
        assertNotNull(bytes)
        val buf = ByteBuffer.wrap(bytes!!).order(ByteOrder.BIG_ENDIAN)

        assertEquals(1, buf.int) // count
        assertEquals(8, buf.int) // Opcode ID 8 = GainMap
        assertEquals(0x01030000, buf.int) // Version
        assertEquals(1, buf.int) // Flags

        // Param size
        val paramSize = buf.int
        assertEquals(16 + 24 + 32 + (16 * 4), paramSize)

        // Bounding rect
        assertEquals(0, buf.int) // top
        assertEquals(0, buf.int) // left
        assertEquals(h, buf.int) // bottom
        assertEquals(w, buf.int) // right
    }

    @Test
    fun `Rvtool JSON extraction helpers extract nested and array data without dependencies`() {
        val json = """
        {
          "cameraId": "0",
          "lensDistortion": [-0.025, 0.004, -0.001, 0.0],
          "lensCalibration": [3100.5, 3100.5, 2048.0, 1536.0, 0.0],
          "lensShading": {
            "rows": 4,
            "cols": 6,
            "gains": [1.0, 1.1, 1.2]
          }
        }
        """.trimIndent()

        val dist = Rvtool.extractJsonDoubleArray(json, "lensDistortion")
        assertEquals(4, dist.size)
        assertEquals(-0.025, dist[0], 1e-4)
        assertEquals(0.004, dist[1], 1e-4)

        val calib = Rvtool.extractJsonDoubleArray(json, "lensCalibration")
        assertEquals(5, calib.size)
        assertEquals(3100.5, calib[0], 1e-4)

        val shadingObj = Rvtool.extractJsonObject(json, "lensShading")
        assertEquals(4, Rvtool.extractJsonInt(shadingObj, "rows"))
        assertEquals(6, Rvtool.extractJsonInt(shadingObj, "cols"))

        val gains = Rvtool.extractJsonFloatArray(shadingObj, "gains")
        assertEquals(3, gains.size)
        assertEquals(1.1f, gains[1], 1e-4f)
    }
}
