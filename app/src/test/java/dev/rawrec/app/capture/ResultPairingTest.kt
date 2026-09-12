package dev.rawrec.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultPairingTest {

    private val framePeriod = 33_333_333L // 30 fps

    @Test
    fun `exact match accepts`() {
        assertEquals(
            ResultPairing.ACCEPT,
            ResultPairing.decide(1_000_000_000L, 1_000_000_000L, framePeriod)
        )
    }

    @Test
    fun `within half frame period accepts`() {
        assertEquals(
            ResultPairing.ACCEPT,
            ResultPairing.decide(1_000_000_000L, 1_000_000_000L + 16_000_000L, framePeriod)
        )
        assertEquals(
            ResultPairing.ACCEPT,
            ResultPairing.decide(1_000_000_000L, 1_000_000_000L - 16_000_000L, framePeriod)
        )
    }

    @Test
    fun `boundary exactly half period accepts`() {
        assertEquals(
            ResultPairing.ACCEPT,
            ResultPairing.decide(0L, framePeriod / 2, framePeriod)
        )
    }

    @Test
    fun `result older than image is stale`() {
        // result belongs to a previous frame dropped by acquireLatestImage
        assertEquals(
            ResultPairing.RESULT_STALE,
            ResultPairing.decide(1_000_000_000L, 1_000_000_000L - 34_000_000L, framePeriod)
        )
    }

    @Test
    fun `result newer than image stays latched for next image`() {
        // image is late; result already belongs to the following frame
        assertEquals(
            ResultPairing.RESULT_AHEAD,
            ResultPairing.decide(1_000_000_000L, 1_000_000_000L + 34_000_000L, framePeriod)
        )
    }

    @Test
    fun `missing sensor timestamp falls back to legacy pairing`() {
        assertEquals(
            ResultPairing.UNKNOWN,
            ResultPairing.decide(1_000_000_000L, null, framePeriod)
        )
    }
}
