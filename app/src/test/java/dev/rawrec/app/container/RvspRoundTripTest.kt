package dev.rawrec.app.container

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class RvspRoundTripTest {

    private fun testHeader(metaJson: String = "") = RvspHeader(
        width = 4056,
        height = 3040,
        bitDepth = 10,
        cfaPattern = Rvsp.CFA_RGGB,
        packing = Rvsp.PACKING_EXPANDED_LSB,
        whiteLevel = 1023,
        blackLevel = intArrayOf(64, 64, 64, 66),
        asShotNeutral = floatArrayOf(0.5123f, 1f, 0.7311f),
        nominalFpsMilli = 29_970,
        createdUnixUs = 1_724_000_000_000_000L,
        cameraModel = "Pixel Test",
        lensId = "front-24mm",
        metaJson = metaJson
    )

    private fun writeFrames(count: Int): Pair<ByteArray, List<ByteArray>> {
        val rng = Random(42)
        val payloads = List(count) { ByteArray(1024 + it * 7).also(rng::nextBytes) }
        val out = ByteArrayOutputStream()
        RvspWriter(out, testHeader()).use { w ->
            payloads.forEachIndexed { i, p ->
                w.writeFrame(
                    payload = p,
                    timestampNs = 1_000_000_000L * i + 123456,
                    exposureNs = 8_000_000L + i,
                    iso = 100 + i
                )
            }
        }
        return out.toByteArray() to payloads
    }

    @Test
    fun `roundtrip preserves every frame byte-exactly`() {
        val (bytes, payloads) = writeFrames(20)
        assertEquals(Rvsp.HEADER_SIZE.toLong(), bytes.size - totalRecordBytes(payloads).toLong())

        val records = RvspReader(ByteArrayInputStream(bytes)).readAll()
        assertEquals(payloads.size, records.size)
        records.forEachIndexed { i, r ->
            r as RvspRecord.Video
            assertArrayEquals("frame $i payload mismatch", payloads[i], r.payload)
            assertEquals(1_000_000_000L * i + 123456, r.timestampNs)
            assertEquals(8_000_000L + i, r.exposureNs)
            assertEquals(100 + i, r.iso)
            assertEquals(i.toLong(), r.sequence)
        }
    }

    @Test
    fun `header fields survive roundtrip`() {
        val bytes = writeFrames(3).first
        val h = RvspReader(ByteArrayInputStream(bytes)).header

        assertEquals(4056, h.width)
        assertEquals(3040, h.height)
        assertEquals(10, h.bitDepth)
        assertEquals(Rvsp.CFA_RGGB, h.cfaPattern)
        assertEquals(Rvsp.PACKING_EXPANDED_LSB, h.packing)
        assertEquals(Rvsp.CODEC_STORE, h.videoCodec)
        assertEquals(Rvsp.AUDIO_NONE, h.audioCodec)
        assertEquals(1023, h.whiteLevel)
        assertArrayEquals(intArrayOf(64, 64, 64, 66), h.blackLevel)
        assertEquals(0.5123f, h.asShotNeutral[0], 1e-6f)
        assertEquals(29_970, h.nominalFpsMilli)
        assertEquals(1_724_000_000_000_000L, h.createdUnixUs)
        assertEquals("Pixel Test", h.cameraModel)
        assertEquals("front-24mm", h.lensId)
    }

    @Test
    fun `24 fps and fractional frame rates are preserved in header`() {
        for (fpsMilli in listOf(24_000, 23_976, 25_000, 48_000, 60_000)) {
            val h = testHeader().copy(nominalFpsMilli = fpsMilli)
            val bytes = h.toBytes()
            val restored = RvspHeader.fromBytes(bytes)
            assertEquals("fpsMilli=$fpsMilli", fpsMilli, restored.nominalFpsMilli)
        }
    }

    @Test
    fun `meta json blob is preserved`() {
        val meta = """{"sensor":"imx999","stride":4072}"""
        val out = ByteArrayOutputStream()
        RvspWriter(out, testHeader(metaJson = meta)).use { it.writeFrame(ByteArray(16), 1L) }
        val parsed = RvspReader(ByteArrayInputStream(out.toByteArray())).header.metaJson
        assertEquals(meta, parsed)
    }

    @Test
    fun `audio chunks interleave and separate cleanly`() {
        val out = ByteArrayOutputStream()
        RvspWriter(out, testHeader()).use { w ->
            repeat(4) {
                w.writeFrame(ByteArray(64), it * 33_000_000L, 8_000_000L, 200)
                w.writeAudioChunk(ByteArray(256), it * 23_000_000L)
            }
        }
        val all = RvspReader(ByteArrayInputStream(out.toByteArray())).readAll()
        assertEquals(8, all.size)

        val frames = all.filterIsInstance<RvspRecord.Video>()
        val audio = all.filterIsInstance<RvspRecord.Audio>()
        assertEquals(4, frames.size)
        assertEquals(4, audio.size)
        assertEquals(
            (0L until 8L).toList(),
            all.map { it.sequence }
        )
    }

    @Test
    fun `torn tail drops only incomplete record`() {
        val (full, _) = writeFrames(5)
        for (cut in 1..31) {
            val torn = full.copyOfRange(0, full.size - cut)
            val reader = RvspReader(ByteArrayInputStream(torn))
            val records = reader.readAll()
            assertTrue("cut=$cut expected <=4 records", records.size <= 4)
            assertTrue(
                "cut=$cut should report truncated tail",
                reader.truncated || records.size == 5
            )
            assertFalse(reader.corruptTail)
        }
    }

    @Test
    fun `corrupt magic stops scan and reports corruption`() {
        val bytes = writeFrames(3).first
        val poisoned = bytes.copyOf()
        val secondRecordOffset =
            Rvsp.HEADER_SIZE + Rvsp.RECORD_HEADER_BYTES + 1024
        System.arraycopy(byteArrayOf(0x00, 0x01, 0x02, 0x03), 0, poisoned, secondRecordOffset, 4)
        val reader = RvspReader(ByteArrayInputStream(poisoned))

        val records = reader.readAll()
        assertEquals(1, records.size)
        assertTrue(reader.corruptTail)
    }

    @Test
    fun `empty writer produces parseable empty file`() {
        val out = ByteArrayOutputStream()
        RvspWriter(out, testHeader()).close()
        val records = RvspReader(ByteArrayInputStream(out.toByteArray())).readAll()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `large payload survives roundtrip`() {
        val bigFrame = ByteArray(1 shl 20) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        RvspWriter(out, testHeader()).use { it.writeFrame(bigFrame, 42L, 10_000_000L, 400) }
        val frames = RvspReader(ByteArrayInputStream(out.toByteArray())).frames()
        assertEquals(1, frames.size)
        assertArrayEquals(bigFrame, frames[0].payload)
    }

    /**
     * WYSIWYG framing crop: a 2.39:1 take of a 4096x3072 sensor stores
     * 4096x1712 frames — header dims and payload size must match exactly
     * (the writer loop computes both from the effective size).
     */
    @Test
    fun `cropped 2_39 take stores 4096x1712 with matching payload`() {
        val crop = dev.rawrec.app.codec.MipiPacker.cropRect(2.39f, 4096, 3072)!!
        assertArrayEquals(intArrayOf(0, 680, 4096, 1712), crop)
        val header = RvspHeader(
            width = crop[2],
            height = crop[3],
            bitDepth = 10,
            cfaPattern = Rvsp.CFA_BGGR,
            packing = Rvsp.PACKING_MIPI_PACKED,
            whiteLevel = 1023,
            createdUnixUs = 1_724_000_000_000_000L
        )
        val packed = ByteArray(
            dev.rawrec.app.codec.MipiPacker.packedSize(crop[2] * crop[3])
        )
        val out = ByteArrayOutputStream()
        RvspWriter(out, header).use {
            it.writeFrameRaw(packed, 1_000_000L, 10_000_000L, 100)
        }
        val reader = RvspReader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(4096, reader.header.width)
        assertEquals(1712, reader.header.height)
        val frame = reader.frames()[0]
        assertEquals(packed.size, frame.payload.size)
    }

    private fun totalRecordBytes(payloads: List<ByteArray>): Int =
        payloads.sumOf { Rvsp.RECORD_HEADER_BYTES + it.size }
}
