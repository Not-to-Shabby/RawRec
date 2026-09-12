package dev.rawrec.app.capture

import android.os.PowerManager
import dev.rawrec.app.profiles.SocOptimizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure mapping tests for the ThermalGovernor's stage converters.
 * The Android listener wiring itself is device-verified via rawrec.log.
 * Runs on plain JVM: android.jar stubs PowerManager constants with real
 * values? No — android.jar stubs throw at runtime, BUT static final int
 * constants are inlined at compile time, so reading
 * PowerManager.THERMAL_STATUS_* is JVM-safe.
 */
class ThermalGovernorTest {

    @Test
    fun `android thermal status maps to ladder stages`() {
        assertEquals(SocOptimizer.ThermalStage.NONE, ThermalGovernor.stageFromAndroidStatus(0))
        assertEquals(SocOptimizer.ThermalStage.LIGHT, ThermalGovernor.stageFromAndroidStatus(1))
        assertEquals(SocOptimizer.ThermalStage.MODERATE, ThermalGovernor.stageFromAndroidStatus(2))
        assertEquals(SocOptimizer.ThermalStage.SEVERE, ThermalGovernor.stageFromAndroidStatus(3))
        assertEquals(SocOptimizer.ThermalStage.CRITICAL, ThermalGovernor.stageFromAndroidStatus(4))
        // EMERGENCY also maps to CRITICAL
        assertEquals(SocOptimizer.ThermalStage.CRITICAL, ThermalGovernor.stageFromAndroidStatus(5))
        // Unknown values degrade safely to NONE
        assertEquals(SocOptimizer.ThermalStage.NONE, ThermalGovernor.stageFromAndroidStatus(-1))
    }

    @Test
    fun `android status constants align with mapping table`() {
        // Guard against mapping drift if the platform constants ever change.
        assertEquals(SocOptimizer.ThermalStage.MODERATE,
            ThermalGovernor.stageFromAndroidStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(SocOptimizer.ThermalStage.SEVERE,
            ThermalGovernor.stageFromAndroidStatus(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(SocOptimizer.ThermalStage.CRITICAL,
            ThermalGovernor.stageFromAndroidStatus(PowerManager.THERMAL_STATUS_CRITICAL))
    }

    @Test
    fun `xiaomi temp_state maps to ladder stages with modulo`() {
        assertEquals(SocOptimizer.ThermalStage.NONE, ThermalGovernor.stageFromXiaomiTempState(0))
        assertEquals(SocOptimizer.ThermalStage.NONE, ThermalGovernor.stageFromXiaomiTempState(1))
        assertEquals(SocOptimizer.ThermalStage.LIGHT, ThermalGovernor.stageFromXiaomiTempState(2))
        assertEquals(SocOptimizer.ThermalStage.MODERATE, ThermalGovernor.stageFromXiaomiTempState(3))
        assertEquals(SocOptimizer.ThermalStage.SEVERE, ThermalGovernor.stageFromXiaomiTempState(4))
        assertEquals(SocOptimizer.ThermalStage.CRITICAL, ThermalGovernor.stageFromXiaomiTempState(5))
        // temp_state arrives as base*10 + stage in Xiaomi builds (stage = extra % 10)
        assertEquals(SocOptimizer.ThermalStage.MODERATE, ThermalGovernor.stageFromXiaomiTempState(13))
        assertEquals(SocOptimizer.ThermalStage.SEVERE, ThermalGovernor.stageFromXiaomiTempState(24))
    }
}
