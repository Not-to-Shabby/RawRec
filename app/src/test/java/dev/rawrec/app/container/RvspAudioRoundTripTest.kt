package dev.rawrec.app.container

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RvspAudioRoundTripTest {

    private fun testHeader() = RvspHeader(
        width = 1920,
        height = 1080,
        bitDepth = 10,
        cfaPattern = Rvsp.CFA_RGGB,
        packing = Rvsp.PACKING_EXPANDED_LSB,
        whiteLevel = 1023,
        blackLevel = intArrayOf(64, 64, 64, 64),
        asShotNeutral = floatArrayOf(0.5f, 1.0f, 0.7f),
        nominalFpsMilli = 30000,
        createdUnixUs = 1700000000000000L,
        cameraModel = "Test Phone",
        lensId = "rear-main",
        audioCodec = Rvsp.AUDIO_PCM16
    )

    @Test
    fun `interleaved video and audio records roundtrip correctly`() {
        val out = ByteArrayOutputStream()
        val videoFrames = List(5) { i -> ByteArray(2000) { (it + i).toByte() } }
        val audioChunks = List(5) { i -> ByteArray(6400) { ((it + i * 3) and 0xFF).toByte() } }

        RvspWriter(out, testHeader()).use { writer ->
            for (i in 0 until 5) {
                writer.writeFrame(
                    payload = videoFrames[i],
                    timestampNs = 1_000_000_000L * i,
                    exposureNs = 10_000_000L,
                    iso = 100
                )
                writer.writeAudioChunk(
                    payload = audioChunks[i],
                    timestampNs = 1_000_000_000L * i + 500_000
                )
            }
        }

        val bytes = out.toByteArray()
        val reader = RvspReader(ByteArrayInputStream(bytes))
        val records = reader.readAll()
        assertEquals(10, records.size)

        var vCount = 0
        var aCount = 0
        for (r in records) {
            when (r) {
                is RvspRecord.Video -> {
                    assertArrayEquals("Video payload mismatch", videoFrames[vCount], r.payload)
                    vCount++
                }
                is RvspRecord.Audio -> {
                    assertArrayEquals("Audio payload mismatch", audioChunks[aCount], r.payload)
                    aCount++
                }
            }
        }
        assertEquals(5, vCount)
        assertEquals(5, aCount)
    }
}
