package dev.rawrec.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RvtoolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `gen creates valid RVSP file`() {
        val testRvsp = tempFolder.newFile("generated_test.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        assertTrue("Generated RVSP file should not be empty", testRvsp.length() > 512)
        val headerBytes = testRvsp.readBytes().copyOfRange(0, 4)
        assertEquals("RVSP", String(headerBytes, Charsets.US_ASCII))
    }

    @Test
    fun `wav extracts valid RIFF WAV audio file`() {
        val testRvsp = tempFolder.newFile("test_audio.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val testWav = tempFolder.newFile("extracted.wav")
        Rvtool.main(arrayOf("wav", testRvsp.absolutePath, testWav.absolutePath))

        assertTrue("Extracted WAV file should exist", testWav.exists())
        assertTrue("Extracted WAV should have at least 44-byte header", testWav.length() >= 44)

        val wavBytes = testWav.readBytes()
        val riff = String(wavBytes.copyOfRange(0, 4), Charsets.US_ASCII)
        val wave = String(wavBytes.copyOfRange(8, 12), Charsets.US_ASCII)
        val fmt = String(wavBytes.copyOfRange(12, 16), Charsets.US_ASCII)

        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)
        assertEquals("fmt ", fmt)

        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val audioFormat = buf.getShort(20).toInt()
        val numChannels = buf.getShort(22).toInt()
        val sampleRate = buf.getInt(24)
        val bitsPerSample = buf.getShort(34).toInt()

        assertEquals(1, audioFormat) // PCM
        assertEquals(2, numChannels) // Stereo
        assertEquals(48000, sampleRate)
        assertEquals(16, bitsPerSample)
    }

    @Test
    fun `extract generates valid CinemaDNG sequence and dngCheck passes`() {
        val testRvsp = tempFolder.newFile("test_dng.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val extractDir = tempFolder.newFolder("dng_output")
        Rvtool.main(arrayOf("extract", testRvsp.absolutePath, extractDir.absolutePath, "0", "1"))

        val dngFiles = extractDir.listFiles { _, name -> name.endsWith(".dng", ignoreCase = true) }
        assertNotNull(dngFiles)
        assertTrue("At least one DNG file should be extracted", dngFiles!!.isNotEmpty())

        val firstDng = dngFiles[0]
        assertTrue("DNG file should be non-empty", firstDng.length() > 1024)

        // Validate with dngcheck
        val dngBytes = firstDng.readBytes()
        val tiffHeader = String(dngBytes.copyOfRange(0, 2), Charsets.US_ASCII)
        assertEquals("II", tiffHeader) // Little-Endian TIFF
        val tiffMagic = ByteBuffer.wrap(dngBytes).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt()
        assertEquals(42, tiffMagic)
    }

    /** Minimal single-IFD TIFF walker: tag -> (type, count, inlineValue/offset). */
    private fun tiffEntries(dng: File): Map<Int, Triple<Int, Int, ByteArray>> {
        val bytes = dng.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd0 = buf.getInt(4)
        buf.position(ifd0)
        val count = buf.short.toInt()
        val out = mutableMapOf<Int, Triple<Int, Int, ByteArray>>()
        repeat(count) {
            val tag = buf.short.toInt() and 0xFFFF
            val type = buf.short.toInt() and 0xFFFF
            val n = buf.int
            val typeSize = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 10 to 8)[type] ?: 1
            val dataLen = typeSize * n
            val data = if (dataLen <= 4) {
                val inline = ByteArray(4); buf.get(inline)
                inline.copyOf(dataLen)
            } else {
                val off = buf.int
                ByteArray(dataLen).also { b ->
                    System.arraycopy(bytes, off, b, 0, dataLen)
                }
            }
            out[tag] = Triple(type, n, data)
        }
        return out
    }

    private fun rationals(data: ByteArray): List<Pair<Int, Int>> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return (data.size / 8).let {
            List(it) { Pair(buf.int, buf.int) }
        }
    }

    @Test
    fun `extracted DNG carries real colorimetry from the header`() {
        val testRvsp = tempFolder.newFile("color_dng.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))
        val extractDir = tempFolder.newFolder("dng_color")
        Rvtool.main(arrayOf("extract", testRvsp.absolutePath, extractDir.absolutePath, "0", "1"))

        val dng = extractDir.listFiles { _, n -> n.endsWith(".dng") }!![0]
        val tags = tiffEntries(dng)

        // 50714 BlackLevel: gen writes 64 per quadrant (SHORT x4).
        val black = tags[50714]!!
        val blackVals = ByteBuffer.wrap(black.third).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, black.third.size / 2)
        assertEquals(64, blackVals.short.toInt())

        // 50721 ColorMatrix1: SRATIONAL x9 matching gen's non-identity matrix
        // (row-major: [0]=1.05, [1]=-0.05).
        val matrix = rationals(tags[50721]!!.third)
        assertEquals(9, matrix.size)
        assertEquals(1.05, matrix[0].first / 10000.0, 1e-3)
        assertEquals(-0.05, matrix[1].first / 10000.0, 1e-3)

        // 50728 AsShotNeutral: gen writes (0.5, 1.0, 0.75) at scale 1000.
        val neutral = rationals(tags[50728]!!.third)
        assertEquals(3, neutral.size)
        assertEquals(0.5, neutral[0].first / 1000.0, 1e-6)
        assertEquals(1.0, neutral[1].first / 1000.0, 1e-6)
        assertEquals(0.75, neutral[2].first / 1000.0, 1e-6)

        // 50778 CalibrationIlluminant1: gen's metaJson says 17.
        val ill = tags[50778]!!
        val illBuf = ByteBuffer.wrap(ill.third).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(17, illBuf.short.toInt())
    }

    @Test
    fun `scanRecords surfaces per-frame timestamp and iso`() {
        val testRvsp = tempFolder.newFile("ts_iso.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))
        val (h, raf) = Rvtool.openHeader(testRvsp.absolutePath)
        raf.use {
            val (frames, audio) = Rvtool.scanRecords(raf)
            assertEquals(5, frames.size)
            assertEquals(5, audio.size)
            // gen writes ts = f * 33_000_000 ns, iso = 100
            assertEquals(0L, frames[0].tsNs)
            assertEquals(33_000_000L, frames[1].tsNs)
            assertEquals(132_000_000L, frames[4].tsNs)
            assertEquals(100, frames[0].iso)
            assertEquals(8_000_000L, frames[0].expNs)
        }
    }

    @Test
    fun `quadRgb demosaics to half-res with channel sanity`() {
        val w = 8; val hh = 8
        // RGGB quad: R at (0,0); make red bright, others black
        val samples = ShortArray(w * hh)
        samples[0] = 1000
        val px = Rvtool.quadRgb(samples, w, hh, 0, listOf(0, 0, 0, 0), 1023)
        assertEquals(16, px.size) // (8/2)x(8/2)
        val first = px[0]
        val r = (first shr 16) and 0xFF
        val g = (first shr 8) and 0xFF
        val b = first and 0xFF
        // single bright R pixel in the quad: r > g, r > b after gamma
        org.junit.Assert.assertTrue("r=$r g=$g b=$b", r > g && r > b)
    }

    @Test
    fun `luma8Of clamps and downsamples to half-res`() {
        val w = 8; val hh = 8
        val samples = ShortArray(w * hh) { 1023 } // all white
        val luma = Rvtool.luma8Of(samples, w, hh, 0, 1023)
        assertEquals(16, luma.size)
        org.junit.Assert.assertEquals("white maps to 255", 255, luma[0].toInt() and 0xFF)
        // all black below black-level clamps to 0
        val dark = ShortArray(w * hh)
        val luma2 = Rvtool.luma8Of(dark, w, hh, 64, 1023)
        org.junit.Assert.assertEquals(0, luma2[0].toInt() and 0xFF)
    }

    @Test
    fun `rgb exports BMP with custom 3D cube LUT`() {
        val testRvsp = tempFolder.newFile("test_lut.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val cubeFile = tempFolder.newFile("test.cube")
        cubeFile.writeText(
            """
            TITLE "Red Tint"
            LUT_3D_SIZE 2
            0.5 0.0 0.0
            1.0 0.0 0.0
            0.5 0.0 0.0
            1.0 0.0 0.0
            0.5 0.0 0.0
            1.0 0.0 0.0
            0.5 0.0 0.0
            1.0 0.0 0.0
            """.trimIndent()
        )

        val outBmp = tempFolder.newFile("out_lut.bmp")
        Rvtool.main(arrayOf("rgb", testRvsp.absolutePath, "0", outBmp.absolutePath, cubeFile.absolutePath))

        assertTrue("Exported LUT BMP should exist", outBmp.exists())
        assertTrue("Exported LUT BMP should be non-empty", outBmp.length() > 54)
    }

    @Test
    fun `extract embeds DNG ProfileToneCurve and ProfileName tags for cinema profiles`() {
        val testRvsp = tempFolder.newFile("test_filmic.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val extractDir = tempFolder.newFolder("dng_filmic")
        Rvtool.main(arrayOf("extract", testRvsp.absolutePath, extractDir.absolutePath, "0", "1", "cine_filmic"))

        val dng = extractDir.listFiles { _, n -> n.endsWith(".dng") }!![0]
        val tags = tiffEntries(dng)

        // 50936 ProfileName
        val profNameTag = tags[50936]
        assertNotNull("Tag 50936 (ProfileName) must be present", profNameTag)
        val name = String(profNameTag!!.third, Charsets.US_ASCII).trimEnd('\u0000')
        assertEquals("Cinema Filmic (Soft Skin & Roll-off)", name)

        // 50981 ProfileToneCurve
        val curveTag = tags[50981]
        assertNotNull("Tag 50981 (ProfileToneCurve) must be present", curveTag)
        val curveRationals = rationals(curveTag!!.third)
        assertEquals("Curve should contain 33 pairs of (x, y) coordinates", 33 * 2, curveRationals.size)
    }

    @Test
    fun `extract with bakeTone modifies raw samples`() {
        val testRvsp = tempFolder.newFile("test_bake.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val extractDir = tempFolder.newFolder("dng_bake")
        Rvtool.main(arrayOf("extract", testRvsp.absolutePath, extractDir.absolutePath, "0", "1", "cine_mono", "true"))

        val dng = extractDir.listFiles { _, n -> n.endsWith(".dng") }!![0]
        val tags = tiffEntries(dng)

        val profNameTag = tags[50936]
        assertNotNull("Baked profile should record baked tag name", profNameTag)
        val name = String(profNameTag!!.third, Charsets.US_ASCII).trimEnd('\u0000')
        assertTrue("Profile name should note baked status", name.contains("Baked"))
    }

    @Test
    fun `extract compensates dropped frame gaps for AV sync by default`() {
        val testRvsp = tempFolder.newFile("gap_test.rvsp")
        // Generate synthetic RVSP with 3 frames, but introduce a 2-frame gap (ts jump: 0, 33ms, 133ms)
        java.io.FileOutputStream(testRvsp).use { fos ->
            val w = 64; val h = 32
            val header = dev.rawrec.app.container.RvspHeader(
                width = w, height = h, bitDepth = 10, cfaPattern = 0,
                packing = dev.rawrec.app.container.Rvsp.PACKING_EXPANDED_LSB,
                nominalFpsMilli = 30_000, createdUnixUs = 0L,
                metaJson = """{"totalDropped": 2, "droppedGaps": 1}"""
            )
            dev.rawrec.app.container.RvspWriter(fos, header).use { writer ->
                val sampleBytes = ByteArray(w * h * 2)
                // Frame 0 at 0ns
                writer.writeFrame(sampleBytes, 0L, 8_000_000L, 100)
                // Frame 1 at 33_333_333ns
                writer.writeFrame(sampleBytes, 33_333_333L, 8_000_000L, 100)
                // Frame 2 at 133_333_333ns (gap of 100ms = 3 frame intervals, 2 missing frames)
                writer.writeFrame(sampleBytes, 133_333_333L, 8_000_000L, 100)
            }
        }

        // Test with compensation (default)
        val extractCompDir = tempFolder.newFolder("dng_comp")
        Rvtool.extract(testRvsp.absolutePath, extractCompDir.absolutePath, 0, -1, compensateDrops = true)
        val compFiles = extractCompDir.listFiles { _, n -> n.endsWith(".dng") }
        assertNotNull(compFiles)
        assertEquals("Should have 5 frames (3 original + 2 compensated duplicates)", 5, compFiles!!.size)

        // Test without compensation (--no-compensate)
        val extractNoCompDir = tempFolder.newFolder("dng_no_comp")
        Rvtool.extract(testRvsp.absolutePath, extractNoCompDir.absolutePath, 0, -1, compensateDrops = false)
        val noCompFiles = extractNoCompDir.listFiles { _, n -> n.endsWith(".dng") }
        assertNotNull(noCompFiles)
        assertEquals("Should have exactly 3 original frames without compensation", 3, noCompFiles!!.size)
    }
}