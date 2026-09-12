package dev.rawrec.app.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameCodecsTest {

    @Test
    fun `store codec is identity and uncompressed`() {
        val codec = FrameCodecs.byId("STORE")
        assertEquals("STORE", codec.id)
        assertFalse(codec.compressed)

        val src = ByteArray(1024) { (it and 0xFF).toByte() }
        val enc = codec.encode(src)
        assertArrayEquals(src, enc)

        val dec = codec.decode(enc, src.size.toLong())
        assertArrayEquals(src, dec)

        codec.openEncoder().use { encoder ->
            val encoded = encoder.encode(src)
            assertArrayEquals(src, encoded)
        }
    }

    @Test
    fun `unknown codec throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodecs.byId("INVALID_CODEC_XYZ")
        }
    }

    @Test
    fun `zstd codec instance properties`() {
        val codec = FrameCodecs.byId("ZSTD")
        assertEquals("ZSTD", codec.id)
        assertTrue(codec.compressed)
    }
}
