package dev.rawrec.app.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraControlsTest {

    @Test
    fun `shutter 180 degrees at 30 fps yields 1 over 60s`() {
        val ns = ShutterCalculator.shutterAngleToExposureNs(180.0, 30.0)
        assertEquals(16_666_667L, ns)
        assertEquals("1/60s", ShutterCalculator.formatExposureFraction(ns))

        val angle = ShutterCalculator.exposureNsToShutterAngle(ns, 30.0)
        assertTrue(abs(angle - 180.0) < 0.1)
    }

    @Test
    fun `shutter 180 degrees at 24 fps yields 1 over 48s`() {
        val ns = ShutterCalculator.shutterAngleToExposureNs(180.0, 24.0)
        assertEquals(20_833_333L, ns)
        assertEquals("1/48s", ShutterCalculator.formatExposureFraction(ns))
    }

    @Test
    fun `shutter fractions formatting`() {
        assertEquals("1/50s", ShutterCalculator.formatExposureFraction(20_000_000L))
        assertEquals("1/120s", ShutterCalculator.formatExposureFraction(8_333_333L))
        assertEquals("1/1000s", ShutterCalculator.formatExposureFraction(1_000_000L))
        assertEquals("1s", ShutterCalculator.formatExposureFraction(1_000_000_000L))
        assertEquals("180°", ShutterCalculator.formatShutterAngle(180.0))
        assertEquals("172.8°", ShutterCalculator.formatShutterAngle(172.8))
    }

    @Test
    fun `color temperature gains scale properly from warm to cool`() {
        val warm = ColorTemperature.kelvinToRgbGains(2800) // Tungsten
        assertTrue("Warm 2800K should have R > B", warm[0] > warm[2])

        val cool = ColorTemperature.kelvinToRgbGains(7500) // Shade
        assertTrue("Cool 7500K should have B > R", cool[2] > cool[0])

        val daylight = ColorTemperature.kelvinToRgbGains(5600)
        assertEquals(1.0f, daylight[1], 0.001f) // G is normalized to 1.0
    }

    @Test
    fun `focus calculator converts between diopters and meters`() {
        assertEquals(Float.POSITIVE_INFINITY, FocusCalculator.dioptersToMeters(0.0f))
        assertEquals("∞", FocusCalculator.formatFocusDistance(0.0f))

        assertEquals(1.0f, FocusCalculator.dioptersToMeters(1.0f), 0.001f)
        assertEquals("1.0m", FocusCalculator.formatFocusDistance(1.0f))

        assertEquals(0.1f, FocusCalculator.dioptersToMeters(10.0f), 0.001f)
        assertEquals("10cm", FocusCalculator.formatFocusDistance(10.0f))

        val hyperfocal = FocusCalculator.hyperfocalDistanceMeters(focalLengthMm = 24.0f, fNumber = 1.8f, cocMm = 0.015f)
        assertTrue("Hyperfocal distance should be around 21m", hyperfocal > 20.0f && hyperfocal < 23.0f)
    }

    @Test
    fun `camera control state manages manual exposure and auto modes`() {
        val state = CameraControlState(
            iso = 200,
            autoIso = false,
            exposureNs = 20_000_000L,
            useShutterAngle = true,
            shutterAngle = 180.0,
            targetFps = 25.0
        )

        // 180 degrees at 25 fps = 1/50s = 20_000_000 ns
        assertEquals(20_000_000L, state.effectiveExposureNs)
        assertEquals("180°", state.formattedExposure)
        assertEquals("AF", state.formattedFocus)
        assertEquals("AWB", state.formattedWhiteBalance)
    }

    @Test
    fun `exposure pills show A in auto mode`() {
        val auto = CameraControlState(autoExposure = true, iso = 800)
        assertEquals("A", auto.formattedExposure)
        assertEquals("A", auto.formattedIso)
    }

    @Test
    fun `exposure pills show values in manual mode`() {
        val manual = CameraControlState(
            autoExposure = false, iso = 800,
            exposureNs = 20_000_000L
        )
        assertEquals("1/50s", manual.formattedExposure)
        assertEquals("800", manual.formattedIso)
    }

    @Test
    fun `manual shutter angle still formats in manual mode`() {
        val angle = CameraControlState(
            autoExposure = false, useShutterAngle = true, shutterAngle = 180.0
        )
        assertEquals("180°", angle.formattedExposure)
    }

    @Test
    fun `aspect ratios produce correct framingAspect`() {
        assertEquals(null, CameraControlState(aspectIndex = 0).framingAspect)
        assertEquals(2.39f, CameraControlState(aspectIndex = 1).framingAspect)
        assertEquals(16f / 9f, CameraControlState(aspectIndex = 2).framingAspect)
        assertEquals(null, CameraControlState(aspectIndex = 3).framingAspect)
        assertEquals(1.0f, CameraControlState(aspectIndex = 4).framingAspect)
    }

    @Test
    fun `formattedFps formats integer and fractional frame rates cleanly`() {
        assertEquals("24", CameraControlState(targetFps = 24.0).formattedFps)
        assertEquals("30", CameraControlState(targetFps = 30.0).formattedFps)
        assertEquals("60", CameraControlState(targetFps = 60.0).formattedFps)
        assertEquals("23.98", CameraControlState(targetFps = 23.976).formattedFps)
        assertEquals("29.97", CameraControlState(targetFps = 29.97).formattedFps)
    }
}
