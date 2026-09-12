package dev.rawrec.app.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationTest {

    // --- black level remap: CFA spatial order -> canonical R,Gr,Gb,B ---

    @Test
    fun `black level remap BGGR keeps R in slot 0`() {
        // BGGR scan order: B, Gb, Gr, R  -> canonical [R, Gr, Gb, B]
        val scan = intArrayOf(65, 64, 64, 66) // B=65, Gb=64, Gr=64, R=66
        val out = SensorCalibration.blackLevelToChannelOrder(scan, 3)
        assertArrayEquals(intArrayOf(66, 64, 64, 65), out)
    }

    @Test
    fun `black level remap RGGB is identity`() {
        val scan = intArrayOf(66, 64, 64, 65) // R=66, Gr, Gb, B=65
        val out = SensorCalibration.blackLevelToChannelOrder(scan, 0)
        assertArrayEquals(intArrayOf(66, 64, 64, 65), out)
    }

    @Test
    fun `black level remap handles all four CFA patterns`() {
        // Same physical quad values with distinct numbers to track movement.
        // Scan order is always TL,TR,BL,BR; canonical is R,Gr,Gb,B.
        val tl = 10; val tr = 20; val bl = 30; val br = 40
        val scan = intArrayOf(tl, tr, bl, br)
        // RGGB: TL=R, TR=Gr, BL=Gb, BR=B -> [10,20,30,40]
        assertArrayEquals(
            intArrayOf(10, 20, 30, 40),
            SensorCalibration.blackLevelToChannelOrder(scan, 0)
        )
        // GRBG: TL=Gr, TR=R, BL=B, BR=Gb -> [20,10,40,30]
        assertArrayEquals(
            intArrayOf(20, 10, 40, 30),
            SensorCalibration.blackLevelToChannelOrder(scan, 1)
        )
        // GBRG: TL=Gb, TR=B, BL=R, BR=Gr -> [30,40,10,20]
        assertArrayEquals(
            intArrayOf(30, 40, 10, 20),
            SensorCalibration.blackLevelToChannelOrder(scan, 2)
        )
        // BGGR: TL=B, TR=Gb, BL=Gr, BR=R -> [40,30,20,10]
        assertArrayEquals(
            intArrayOf(40, 30, 20, 10),
            SensorCalibration.blackLevelToChannelOrder(scan, 3)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `black level remap rejects wrong size`() {
        SensorCalibration.blackLevelToChannelOrder(intArrayOf(1, 2, 3), 0)
    }

    @Test
    fun `cfa quadrant names match spec tables`() {
        assertEquals(listOf("B", "Gb", "Gr", "R"), SensorCalibration.cfaQuadrantNames(3).toList())
        assertEquals(listOf("R", "Gr", "Gb", "B"), SensorCalibration.cfaQuadrantNames(0).toList())
    }

    // --- AsShotNeutral from gains ---

    @Test
    fun `as shot neutral inverts gains and averages greens`() {
        // R=2, Gr=1, Gb=1.5, B=0.5 -> neutral [0.5, 1/1.25=0.8, 2.0]
        val out = SensorCalibration.asShotNeutralFromGains(floatArrayOf(2f, 1f, 1.5f, 0.5f))
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
        assertEquals(2.0f, out[2], 1e-6f)
    }

    @Test
    fun `as shot neutral treats unity gains as unity neutral`() {
        val out = SensorCalibration.asShotNeutralFromGains(floatArrayOf(1f, 1f, 1f, 1f))
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(1f, out[2], 1e-6f)
    }

    @Test
    fun `as shot neutral handles zero gain defensively`() {
        val out = SensorCalibration.asShotNeutralFromGains(floatArrayOf(0f, 1f, 1f, 1f))
        assertEquals(1f, out[0], 1e-6f) // degenerate gain -> neutral 1, not Infinity
    }

    @Test
    fun `manual rgb gains invert to neutral`() {
        val out = SensorCalibration.asShotNeutralFromManualRgb(floatArrayOf(4f, 1f, 2f))
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
    }

    // --- ColorSpaceTransform rationals -> floats ---

    @Test
    fun `color transform rationals convert with double precision`() {
        // 1/3, 1/6, ... exercise the rational division path
        val nums = intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
        val dens = intArrayOf(3, 3, 3, 6, 1, 6, 3, 3, 3)
        val out = SensorCalibration.colorTransformToFloats(nums, dens)
        assertEquals(1f / 3f, out[0], 1e-6f)
        assertEquals(1f / 6f, out[3], 1e-6f)
        assertEquals(1f, out[4], 1e-6f)
    }

    @Test
    fun `zero denominator yields zero not NaN`() {
        val out = SensorCalibration.colorTransformToFloats(
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1)
        )
        assertEquals(0f, out[0], 1e-9f)
        assertFalse(out[0].isNaN())
    }

    // --- identity fallbacks ---

    @Test
    fun `identity matrix helpers behave`() {
        val id = SensorCalibration.identityMatrix()
        assertTrue(SensorCalibration.isIdentity(id))
        assertFalse(SensorCalibration.isIdentity(floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse(SensorCalibration.isIdentity(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))) // wrong size
    }

    @Test
    fun `default illuminant is D65`() {
        assertEquals(21, SensorCalibration.DEFAULT_ILLUMINANT)
    }
}
