package dev.rawrec.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferSelectionTest {

    // selectBufferSize is RAW-ASPECT-FIRST: the preview buffer must match the
    // selected RAW mode's shape (default 4:3, the F6's binned mode) because
    // all viewfinder modes + the WYSIWYG recorded crop assume sensor-shaped
    // content (target ~0.69MP in the RAW mode's own aspect). Regression test
    // for the old truncated-aspect-penalty bug where a 16:9 candidate closer
    // in AREA won the buffer.

    @Test
    fun `exact 960x720 is chosen when present`() {
        assertEquals(
            960 to 720,
            selectBufferSize(listOf(1280 to 720, 960 to 720, 1920 to 1080))
        )
    }

    @Test
    fun `4-3 candidate beats 16-9 candidate closer in area`() {
        // 1920x1080 area = 2.07MP vs target 2.76MP (diff 691k)
        // 1600x1200 area = 1.92MP (diff 845k) — the OLD code picked 1920x1080
        // because its aspect penalty truncated to 0; 4:3-first must win.
        assertEquals(
            1600 to 1200,
            selectBufferSize(listOf(1920 to 1080, 1600 to 1200))
        )
    }

    @Test
    fun `prefers near-4-3 over exact-area 16-9`() {
        // 2048x1536 (4:3, 3.1MP) vs 1664x1248 (4:3, 2.07MP) vs 1920x1080:
        // both 4:3 candidates beat the 16:9 one regardless of area.
        assertEquals(
            1664 to 1248,
            selectBufferSize(listOf(1664 to 1248, 1920 to 1080))
        )
    }

    @Test
    fun `falls back to closest area when no 4-3 candidates exist`() {
        // Only 16:9 / 18.5:9 shapes available. Target area 0.69MP:
        // 1280x720 = 0.92MP (diff 226k) beats 1920x1080 = 2.07MP (diff 1.38M)
        // and 2400x1080 = 2.59MP (diff 1.90M) — closest area wins in the
        // fallback pool.
        assertEquals(
            1280 to 720,
            selectBufferSize(listOf(1280 to 720, 1920 to 1080, 2400 to 1080))
        )
    }

    @Test
    fun `empty list falls back to 960x720`() {
        assertEquals(960 to 720, selectBufferSize(emptyList()))
    }

    @Test
    fun `within the 4-3 pool area decides`() {
        // Target 0.69MP: 1600x1200 (1.92MP, diff 1.23M) vs 2048x1536
        // (3.15MP, diff 2.46M) — 1600x1200 is closer to the reduced target.
        assertEquals(
            1600 to 1200,
            selectBufferSize(listOf(1600 to 1200, 2048 to 1536))
        )
    }

    @Test
    fun `f6-like size list picks the 960x720 target`() {
        assertEquals(
            960 to 720,
            selectBufferSize(
                listOf(1920 to 1440, 1280 to 960, 960 to 720, 800 to 600, 1920 to 1080)
            )
        )
    }

    @Test
    fun `no 4-3 candidates prefers landscape over portrait`() {
        // 1440x1920 (portrait, areaDiff=0) vs 1920x1080 (landscape):
        // the sensor presents landscape — portrait buffers mis-frame the
        // chassis-locked viewfinder. Landscape must win.
        assertEquals(
            1920 to 1080,
            selectBufferSize(listOf(1440 to 1920, 1920 to 1080))
        )
    }

    @Test
    fun `all-portrait list still returns a portrait candidate`() {
        // Nothing better exists — keep the portrait size closest to the
        // 0.69MP target rather than crash: 1080x1920 (2.07MP) beats 1440x1920
        // (2.76MP).
        assertEquals(
            1080 to 1920,
            selectBufferSize(listOf(1080 to 1920, 1440 to 1920))
        )
    }

    // ─── rawAspect: the buffer follows the selected RAW mode's shape ───

    @Test
    fun `16-9 raw mode picks a 16-9 buffer over 4-3`() {
        // A 16:9 RAW mode (e.g. a crop mode) must get a 16:9 preview buffer —
        // the 4:3 candidates are no longer near-target. Among the near-16:9
        // pool, 1280x720 is closest to the ~0.69MP target.
        assertEquals(
            1280 to 720,
            selectBufferSize(
                listOf(960 to 720, 1280 to 960, 1920 to 1080, 1280 to 720),
                rawAspect = 16.0 / 9.0
            )
        )
    }

    @Test
    fun `16-9 raw mode prefers the near-target size closest in area`() {
        // Among near-16:9 candidates, the one closest to the 0.69MP 16:9
        // target (~1138x640) wins: 1280x720 (0.92MP) vs 1920x1080 (2.07MP).
        assertEquals(
            1280 to 720,
            selectBufferSize(
                listOf(1920 to 1080, 1280 to 720),
                rawAspect = 16.0 / 9.0
            )
        )
    }

    @Test
    fun `default rawAspect is 4-3 - previous behavior unchanged`() {
        // No explicit rawAspect -> same as passing 4:3 (F6 binned mode).
        val list = listOf(960 to 720, 1280 to 960, 1920 to 1080)
        assertEquals(
            selectBufferSize(list, rawAspect = 4.0 / 3.0),
            selectBufferSize(list)
        )
    }

    @Test
    fun `empty list with 16-9 raw mode returns a 16-9 fallback`() {
        assertEquals(
            960 to 540,
            selectBufferSize(emptyList(), rawAspect = 16.0 / 9.0)
        )
    }
}
