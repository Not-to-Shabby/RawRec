package dev.rawrec.app.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class MipiPackerTest {

    private fun random10Bit(n: Int, seed: Long): ShortArray {
        val rng = Random(seed)
        return ShortArray(n) { (rng.nextInt(1024)).toShort() }
    }

    @Test
    fun `packed size math`() {
        assertEquals(5, MipiPacker.packedSize(1))
        assertEquals(5, MipiPacker.packedSize(4))
        assertEquals(10, MipiPacker.packedSize(5))
        assertEquals(10, MipiPacker.packedSize(8))
    }

    @Test
    fun `pack then unpack is identity for aligned count`() {
        val n = 1024
        val samples = random10Bit(n, 7)
        val packed = MipiPacker.pack(samples)
        assertEquals(MipiPacker.packedSize(n), packed.size)
        assertArrayEquals(samples, MipiPacker.unpack(packed, n))
    }

    @Test
    fun `pack then unpack handles remainder pixels`() {
        for (n in listOf(1, 2, 3, 5, 6, 7, 9, 13)) {
            val samples = random10Bit(n, n.toLong())
            val packed = MipiPacker.pack(samples)
            assertEquals(MipiPacker.packedSize(n), packed.size)
            val restored = MipiPacker.unpack(packed, n)
            assertArrayEquals("n=$n", samples, restored)
        }
    }

    @Test
    fun `packed output uses 37_5 percent less space`() {
        val n = 4056 * 3040
        val samples = random10Bit(64, 3)
        val packed = MipiPacker.pack(samples)
        assertEquals(samples.size * 2 * 0.625, packed.size.toDouble(), 0.001)
        assertEquals(n / 4 * 5, MipiPacker.packedSize(n))
    }

    @Test
    fun `values are masked to 10 bits`() {
        val dirty = shortArrayOf(-1, 1024, 4095, Short.MAX_VALUE)
        val restored = MipiPacker.unpack(MipiPacker.pack(dirty), dirty.size)
        assertArrayEquals(
            shortArrayOf(1023.toShort(), 0, 1023.toShort(), 1023.toShort()),
            restored
        )
    }

    // --- cropRect: WYSIWYG framing crop ---

    @Test
    fun `cropRect is null for full, 4-3 and grid modes`() {
        // aspects equal-or-narrower than the sensor record the full frame
        assertEquals(null, MipiPacker.cropRect(null, 4096, 3072))
        assertEquals(null, MipiPacker.cropRect(4f / 3f, 4096, 3072))
        // same aspect as source -> null
        assertEquals(null, MipiPacker.cropRect(4096f / 3072f, 4096, 3072))
    }

    @Test
    fun `cropRect 16-9 on 12MP sensor is centered full-width band`() {
        // 4096 / (16/9) = 2304 exactly (multiple of 4); centered in 3072.
        val crop = MipiPacker.cropRect(16f / 9f, 4096, 3072)
        assertArrayEquals(intArrayOf(0, 384, 4096, 2304), crop)
    }

    @Test
    fun `cropRect 2_39 on 12MP sensor rounds to multiple of 4 and recenters`() {
        // 4096 / 2.39 = 1713.8 -> rounds down to 1712; centered in 3072:
        // (3072-1712)/2 = 680 exactly.
        val crop = MipiPacker.cropRect(2.39f, 4096, 3072)
        assertArrayEquals(intArrayOf(0, 680, 4096, 1712), crop)
    }

    @Test
    fun `cropRect origin and dims stay multiples of 4`() {
        for (aspect in listOf(2.39f, 16f / 9f, 2.0f, 1.9f, 3.0f)) {
            val c = MipiPacker.cropRect(aspect, 4096, 3072) ?: continue
            assertEquals("aspect=$aspect l", 0, c[0] % 4)
            assertEquals("aspect=$aspect t", 0, c[1] % 4)
            assertEquals("aspect=$aspect w", 0, c[2] % 4)
            assertEquals("aspect=$aspect h", 0, c[3] % 4)
        }
    }

    @Test
    fun `cropRect handles non-divisible centered rounding`() {
        // height 3074: (3074-1712)/2 = 681 -> snapped down to 680, band 1712
        val crop = MipiPacker.cropRect(2.39f, 4096, 3074)
        assertArrayEquals(intArrayOf(0, 680, 4096, 1712), crop)
    }

    @Test
    fun `cropRect handles 1-1 square crop`() {
        // On 4096x3072 sensor, 1:1 square crop cuts width: 3072x3072, left = 512
        val crop = MipiPacker.cropRect(1.0f, 4096, 3072)
        assertArrayEquals(intArrayOf(512, 0, 3072, 3072), crop)
    }

    @Test
    fun `cropRect landscape 16-9 cuts height and keeps full width`() {
        // True 16:9 widescreen: full width 4096, centered height 2304.
        val crop = MipiPacker.cropRect(16f / 9f, 4096, 3072, isLandscape = true)
        assertArrayEquals(intArrayOf(0, 384, 4096, 2304), crop)
    }

    @Test
    fun `cropRect landscape 2_39 cuts height and keeps full width`() {
        // True 2.39:1 widescreen: full width 4096, centered height 1712.
        val crop = MipiPacker.cropRect(2.39f, 4096, 3072, isLandscape = true)
        assertArrayEquals(intArrayOf(0, 680, 4096, 1712), crop)
    }

    @Test
    fun `cropRect landscape 4-3 matches native sensor aspect`() {
        // 4:3 on 4096x3072 matches source aspect -> null (full sensor)
        val crop = MipiPacker.cropRect(4f / 3f, 4096, 3072, isLandscape = true)
        org.junit.Assert.assertNull(crop)
    }

    @Test
    fun `cropRect landscape 1-1 square crop is 3072x3072`() {
        val crop = MipiPacker.cropRect(1.0f, 4096, 3072, isLandscape = true)
        assertArrayEquals(intArrayOf(512, 0, 3072, 3072), crop)
    }

    @Test
    fun `cropRect landscape origins and dims stay multiples of 4`() {
        for (aspect in listOf(2.39f, 16f / 9f, 4f / 3f, 1.0f, 1.85f, 2.0f)) {
            val c = MipiPacker.cropRect(aspect, 4096, 3072, isLandscape = true) ?: continue
            assertEquals("aspect=$aspect l", 0, c[0] % 4)
            assertEquals("aspect=$aspect t", 0, c[1] % 4)
            assertEquals("aspect=$aspect w", 0, c[2] % 4)
            assertEquals("aspect=$aspect h", 0, c[3] % 4)
        }
    }

    @Test
    fun `cropRect rejects degenerate aspects`() {
        assertEquals(null, MipiPacker.cropRect(0f, 4096, 3072))
        assertEquals(null, MipiPacker.cropRect(-1f, 4096, 3072))
        // an aspect so wide the band rounds down to nothing (4096/2000 = 2 -> 0)
        assertEquals(null, MipiPacker.cropRect(2000f, 4096, 3072))
    }

    // --- packCropped: JVM fallback path ---

    @Test
    fun `packCropped stores the same pixels as a direct pack of the band`() {
        val w = 64; val h = 32
        val samples = random10Bit(w * h, 11)
        // crop a centered 64x16 band (top = 8)
        val crop = intArrayOf(0, 8, 64, 16)
        val cropped = MipiPacker.packCropped(samples, w, crop)
        val direct = MipiPacker.pack(
            samples.copyOfRange(8 * w, 8 * w + 16 * w) // rows 8..23
        )
        assertArrayEquals(direct, cropped)
    }

    @Test
    fun `packCropped round-trips through unpack`() {
        val w = 64; val h = 32
        val samples = random10Bit(w * h, 13)
        val crop = intArrayOf(0, 8, 64, 16)
        val packed = MipiPacker.packCropped(samples, w, crop)
        assertEquals(MipiPacker.packedSize(64 * 16), packed.size)
        val restored = MipiPacker.unpack(packed, 64 * 16)
        // row 3 of the band = sensor row 11
        assertArrayEquals(
            samples.copyOfRange(11 * w, 11 * w + w),
            restored.copyOfRange(3 * w, 3 * w + w)
        )
    }
}
