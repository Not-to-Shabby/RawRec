package dev.rawrec.app.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawSampleReaderTest {

    @Test
    fun `isContiguous returns true only for exact width * 2 and pixelStride 2`() {
        assertTrue(RawSampleReader.isContiguous(rowStride = 2048, pixelStride = 2, width = 1024))
        assertFalse(RawSampleReader.isContiguous(rowStride = 2050, pixelStride = 2, width = 1024))
        assertFalse(RawSampleReader.isContiguous(rowStride = 4096, pixelStride = 4, width = 1024))
    }

    @Test
    fun `readU16LE reads contiguous buffer correctly`() {
        val w = 4
        val h = 2
        val rowStride = w * 2
        val pixelStride = 2

        val buf = ByteBuffer.allocate(rowStride * h).order(ByteOrder.LITTLE_ENDIAN)
        val expected = ShortArray(w * h)
        for (i in 0 until w * h) {
            val v = (100 + i * 15).toShort()
            expected[i] = v
            buf.putShort(v)
        }

        val result = RawSampleReader.readU16LE(buf.array(), rowStride, pixelStride, w, h)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `readU16LE correctly skips row padding`() {
        val w = 3
        val h = 2
        val rowStride = 16 // padded from 6 bytes to 16 bytes
        val pixelStride = 2

        val payload = ByteArray(rowStride * h)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Row 0
        buf.position(0)
        buf.putShort(500)
        buf.putShort(600)
        buf.putShort(700)

        // Row 1 (at offset 16)
        buf.position(rowStride)
        buf.putShort(800)
        buf.putShort(900)
        buf.putShort(1000)

        val result = RawSampleReader.readU16LE(payload, rowStride, pixelStride, w, h)
        val expected = shortArrayOf(500, 600, 700, 800, 900, 1000)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `readU16LE throws when pixelStride is less than 2`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSampleReader.readU16LE(ByteArray(16), rowStride = 8, pixelStride = 1, width = 4, height = 2)
        }
    }
}
