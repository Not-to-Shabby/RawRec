package dev.rawrec.app.container

import dev.rawrec.app.codec.FrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

private object XorCodec : FrameCodec {
    override val id = "XOR"
    override val compressed = true
    private val key = byteArrayOf(0x5A, 0x33)
    override fun encode(src: ByteArray): ByteArray {
        val out = src.copyOf()
        for (i in out.indices) out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }
    override fun decode(src: ByteArray, expectedBytes: Long) = encode(src)
}

class RvspCodecWiringTest {

    private fun header(codecId: String, packing: Int) = RvspHeader(
        width = 32,
        height = 16,
        bitDepth = 10,
        cfaPattern = Rvsp.CFA_BGGR,
        packing = packing,
        videoCodec = codecId,
        createdUnixUs = 999L
    )

    @Test
    fun `writer with injected codec stores encoded payload and header keeps codec id`() {
        val rng = Random(11)
        val frames = List(4) { ByteArray(512).also(rng::nextBytes) }

        val out = ByteArrayOutputStream()
        RvspWriter(out, header("XOR", Rvsp.PACKING_MIPI_PACKED), XorCodec).use { w ->
            frames.forEachIndexed { i, f -> w.writeFrame(f, i.toLong()) }
        }

        val bytes = out.toByteArray()
        val reader = RvspReader(ByteArrayInputStream(bytes))
        assertEquals("XOR", reader.header.videoCodec)
        assertEquals(Rvsp.PACKING_MIPI_PACKED, reader.header.packing)

        val stored = reader.frames()
        assertEquals(frames.size, stored.size)
        stored.forEachIndexed { i, r ->
            val decoded = XorCodec.decode(r.payload, frames[i].size.toLong())
            assertArrayEquals("frame $i mismatch after decode", frames[i], decoded)
        }
    }

    @Test
    fun `store codec passthrough keeps legacy behavior`() {
        val out = ByteArrayOutputStream()
        RvspWriter(out, header("STORE", Rvsp.PACKING_EXPANDED_LSB)).use { w ->
            w.writeFrame(byteArrayOf(1, 2, 3), 5L, 6L, 7)
        }
        val frames = RvspReader(ByteArrayInputStream(out.toByteArray())).frames()
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[0].payload)
    }

    @Test
    fun `compressed payload differs from raw but same length records parse`() {
        val rng = Random(99)
        val frame = ByteArray(256).also(rng::nextBytes)
        val out = ByteArrayOutputStream()
        RvspWriter(out, header("XOR", Rvsp.PACKING_EXPANDED_LSB), XorCodec).use {
            it.writeFrame(frame, 1L)
        }
        val all = RvspReader(ByteArrayInputStream(out.toByteArray())).readAll()
        assertEquals(1, all.size)
        val stored = all[0].payload
        org.junit.Assert.assertFalse(stored.contentEquals(frame))
        assertArrayEquals(frame, XorCodec.decode(stored, frame.size.toLong()))
    }
}
