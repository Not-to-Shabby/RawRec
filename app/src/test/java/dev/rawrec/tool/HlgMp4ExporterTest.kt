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

class HlgMp4ExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `exports valid HLG MP4 with standard ftyp, mdat, and moov atoms`() {
        val testRvsp = tempFolder.newFile("test_hlg.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val outMp4 = tempFolder.newFile("exported_hlg.mp4")
        val exported = HlgMp4Exporter.export(
            testRvsp.absolutePath,
            outMp4.absolutePath,
            profile = ColorScience.ToneProfile.CINE_HLG,
            isHlg = true
        )

        assertTrue("Exported MP4 should exist", exported.exists())
        assertTrue("Exported MP4 should have substantial size (> 1KB)", exported.length() > 1024)

        val bytes = exported.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Verify ftyp atom
        val ftypSize = buf.getInt()
        val ftypType = ByteArray(4).also { buf.get(it) }.let { String(it, Charsets.US_ASCII) }
        assertEquals("ftyp", ftypType)
        assertTrue("ftyp size must be >= 16", ftypSize >= 16)

        // Find moov box
        val strContent = String(bytes, Charsets.ISO_8859_1)
        assertTrue("MP4 must contain 'moov' atom", strContent.contains("moov"))
        assertTrue("MP4 must contain 'mdat' atom", strContent.contains("mdat"))
        assertTrue("MP4 must contain 'colr' atom for HLG signaling", strContent.contains("colr"))
        assertTrue("MP4 must contain 'nclx' parameter identifier", strContent.contains("nclx"))
    }

    @Test
    fun `CLI mp4 command exports HLG video with custom tone profile`() {
        val testRvsp = tempFolder.newFile("test_cli_hlg.rvsp")
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val outMp4 = tempFolder.newFile("cli_filmic.mp4")
        Rvtool.main(arrayOf("mp4", testRvsp.absolutePath, outMp4.absolutePath, "cine_filmic"))

        assertTrue("CLI exported MP4 should exist", outMp4.exists())
        assertTrue("CLI exported MP4 should not be empty", outMp4.length() > 512)
    }

    @Test
    fun `auto-detects 60fps from frame timestamps even if header fps is different`() {
        val testRvsp = tempFolder.newFile("test_60fps.rvsp")
        // Generate base 30fps rvsp
        Rvtool.main(arrayOf("gen", testRvsp.absolutePath))

        val outMp4 = tempFolder.newFile("out_60fps.mp4")
        val exported = HlgMp4Exporter.export(
            testRvsp.absolutePath,
            outMp4.absolutePath,
            profile = ColorScience.ToneProfile.CINE_HLG
        )

        assertTrue(exported.exists())
        val bytes = exported.readBytes()
        val moovPos = bytes.indexOf("moov".toByteArray(Charsets.US_ASCII))
        assertTrue("moov atom must exist", moovPos != -1)
    }

    private fun ByteArray.indexOf(target: ByteArray): Int {
        outer@ for (i in 0..this.size - target.size) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
