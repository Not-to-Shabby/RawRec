package dev.rawrec.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class EntropyGovernorTest {

    @Test
    fun `governor starts at NORMAL stage with nominal level`() {
        val gov = EntropyGovernor(midCapacity = 16, outCapacity = 32, nominalLevel = -3)
        assertEquals(EntropyStage.NORMAL, gov.currentStage)
        assertEquals(-3, gov.activeLevel)
    }

    @Test
    fun `governor escalates immediately to PRESSURE when midQ reaches threshold`() {
        var notifiedStage: EntropyStage? = null
        var notifiedLevel: Int? = null
        val gov = EntropyGovernor(
            midCapacity = 16,
            outCapacity = 32,
            totalPoolBuffers = 15,
            nominalLevel = -3
        ) { stage, level ->
            notifiedStage = stage
            notifiedLevel = level
        }

        // midQ = 7 / 16 (43.7% >= 38%) -> triggers PRESSURE
        val stage = gov.evaluate(midQSize = 7, outQSize = 2, poolRemaining = 12)
        assertEquals(EntropyStage.PRESSURE, stage)
        assertEquals(EntropyStage.PRESSURE, gov.currentStage)
        assertEquals(-6, gov.activeLevel) // -3 + (-3)
        assertEquals(EntropyStage.PRESSURE, notifiedStage)
        assertEquals(-6, notifiedLevel)
    }

    @Test
    fun `governor escalates immediately to CRITICAL when buffer pool is depleted`() {
        val gov = EntropyGovernor(midCapacity = 16, outCapacity = 32, totalPoolBuffers = 15, nominalLevel = -3)

        // poolRemaining = 1 (depleted <= 2) -> triggers CRITICAL
        val stage = gov.evaluate(midQSize = 2, outQSize = 2, poolRemaining = 1)
        assertEquals(EntropyStage.CRITICAL, stage)
        assertEquals(-9, gov.activeLevel) // -3 + (-6)
    }

    @Test
    fun `governor applies damped de-escalation hysteresis`() {
        val gov = EntropyGovernor(midCapacity = 16, outCapacity = 32, totalPoolBuffers = 15, nominalLevel = -3)

        // 1. Escalate to PRESSURE
        gov.evaluate(midQSize = 8, outQSize = 2, poolRemaining = 10)
        assertEquals(EntropyStage.PRESSURE, gov.currentStage)

        // 2. Clear queues to quiet (midQ = 1, pool = 14)
        // Requires 6 consecutive quiet ticks before returning to NORMAL
        for (tick in 1..5) {
            val s = gov.evaluate(midQSize = 1, outQSize = 1, poolRemaining = 14)
            assertEquals("Tick $tick should remain in PRESSURE", EntropyStage.PRESSURE, s)
        }

        // 6th tick de-escalates back to NORMAL
        val finalStage = gov.evaluate(midQSize = 1, outQSize = 1, poolRemaining = 14)
        assertEquals(EntropyStage.NORMAL, finalStage)
        assertEquals(-3, gov.activeLevel)
    }
}
