package dev.rawrec.app.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4ContainerWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class Box(val fourcc: String, val size: Int, val payload: ByteArray)

    private fun parseBoxes(data: ByteArray): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val buf = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN)
            val size = buf.int
            val fourccBytes = ByteArray(4)
            buf.get(fourccBytes)
            val fourcc = String(fourccBytes, Charsets.US_ASCII)
            assertTrue("Box size must be at least 8: $size for $fourcc", size >= 8)
            val payloadSize = size - 8
            val payload = ByteArray(payloadSize)
            System.arraycopy(data, pos + 8, payload, 0, payloadSize)
            boxes.add(Box(fourcc, size, payload))
            pos += size
        }
        return boxes
    }

    @Test
    fun `empty frames returns empty moov`() {
        val moov = Mp4ContainerWriter.buildMoovBox(1024, 768, 30, emptyList(), emptyList())
        assertEquals(0, moov.size)
    }

    @Test
    fun `single frame generates complete moov hierarchy without overflow`() {
        val offsets = listOf(28L)
        val sizes = listOf(50000)
        val moovBytes = Mp4ContainerWriter.buildMoovBox(1024, 768, 30, offsets, sizes)
        assertTrue(moovBytes.isNotEmpty())

        val boxes = parseBoxes(moovBytes)
        assertEquals(1, boxes.size)
        val moov = boxes[0]
        assertEquals("moov", moov.fourcc)

        val subBoxes = parseBoxes(moov.payload)
        val subFourccs = subBoxes.map { it.fourcc }
        assertTrue("moov must contain mvhd", subFourccs.contains("mvhd"))
        assertTrue("moov must contain trak", subFourccs.contains("trak"))

        val trak = subBoxes.first { it.fourcc == "trak" }
        val trakSub = parseBoxes(trak.payload)
        val trakFourccs = trakSub.map { it.fourcc }
        assertTrue("trak must contain tkhd", trakFourccs.contains("tkhd"))
        assertTrue("trak must contain mdia", trakFourccs.contains("mdia"))

        val mdia = trakSub.first { it.fourcc == "mdia" }
        val mdiaSub = parseBoxes(mdia.payload)
        val mdiaFourccs = mdiaSub.map { it.fourcc }
        assertTrue("mdia must contain mdhd", mdiaFourccs.contains("mdhd"))
        assertTrue("mdia must contain hdlr", mdiaFourccs.contains("hdlr"))
        assertTrue("mdia must contain minf", mdiaFourccs.contains("minf"))
    }

    @Test
    fun `audio and video dual track moov contains both traks with sowt audio`() {
        val vOffsets = listOf(28L, 50000L)
        val vSizes = listOf(40000, 42000)
        val aOffsets = listOf(92000L, 98400L)
        val aSizes = listOf(6400, 6400) // 1600 stereo samples each

        val moovBytes = Mp4ContainerWriter.buildMoovBox(
            1024, 768, 30,
            vOffsets, vSizes,
            aOffsets, aSizes,
            48000, 2
        )
        val moov = parseBoxes(moovBytes).first()
        val traks = parseBoxes(moov.payload).filter { it.fourcc == "trak" }
        assertEquals("Must have 2 traks (video and audio)", 2, traks.size)

        // Verify Audio Trak
        val audioTrak = traks[1]
        val audioMdia = parseBoxes(audioTrak.payload).first { it.fourcc == "mdia" }
        val audioMinf = parseBoxes(audioMdia.payload).first { it.fourcc == "minf" }
        val audioMinfBoxes = parseBoxes(audioMinf.payload)
        assertTrue("Audio minf must contain smhd", audioMinfBoxes.any { it.fourcc == "smhd" })

        val audioStbl = audioMinfBoxes.first { it.fourcc == "stbl" }
        val audioStblBoxes = parseBoxes(audioStbl.payload)
        val audioStsd = audioStblBoxes.first { it.fourcc == "stsd" }
        val audioStsdBoxes = parseBoxes(audioStsd.payload.copyOfRange(8, audioStsd.payload.size))
        assertTrue("Audio stsd must contain sowt PCM codec", audioStsdBoxes.any { it.fourcc == "sowt" })
    }

    @Test
    fun `sample table stsz and stco match frame counts and offsets`() {
        val frameCount = 150
        val offsets = (0 until frameCount).map { 28L + it * 60000L }
        val sizes = (0 until frameCount).map { 55000 + (it % 1000) }

        val moovBytes = Mp4ContainerWriter.buildMoovBox(1920, 1080, 60, offsets, sizes)
        val moov = parseBoxes(moovBytes).first()
        val trak = parseBoxes(moov.payload).first { it.fourcc == "trak" }
        val mdia = parseBoxes(trak.payload).first { it.fourcc == "mdia" }
        val minf = parseBoxes(mdia.payload).first { it.fourcc == "minf" }
        val stbl = parseBoxes(minf.payload).first { it.fourcc == "stbl" }
        val stblSub = parseBoxes(stbl.payload)

        val stsz = stblSub.first { it.fourcc == "stsz" }
        val stszBuf = ByteBuffer.wrap(stsz.payload).order(ByteOrder.BIG_ENDIAN)
        stszBuf.getInt() // version
        assertEquals("Sample size should be 0 for variable size", 0, stszBuf.getInt())
        assertEquals(frameCount, stszBuf.getInt())
        for (s in sizes) {
            assertEquals(s, stszBuf.getInt())
        }

        val stco = stblSub.first { it.fourcc == "stco" }
        val stcoBuf = ByteBuffer.wrap(stco.payload).order(ByteOrder.BIG_ENDIAN)
        stcoBuf.getInt() // version
        assertEquals(frameCount, stcoBuf.getInt())
        for (o in offsets) {
            assertEquals(o.toInt(), stcoBuf.getInt())
        }
    }

    @Test
    fun `patchMp4File patches mdat size and appends valid moov with audio`() {
        val testFile = tempFolder.newFile("test_av_proxy.mp4")
        val ftypPayload = "mp42\u0000\u0000\u0000\u0000mp42isomqt  ".toByteArray(Charsets.ISO_8859_1)
        val ftypBox = Mp4ContainerWriter.makeBox("ftyp", ftypPayload)

        val fos = FileOutputStream(testFile)
        val dos = java.io.DataOutputStream(fos)
        dos.write(ftypBox)

        val mdatOffset = ftypBox.size.toLong()
        dos.writeInt(0) // placeholder for mdat size
        dos.write("mdat".toByteArray(Charsets.US_ASCII))

        val vOffsets = mutableListOf<Long>()
        val vSizes = mutableListOf<Int>()
        val aOffsets = mutableListOf<Long>()
        val aSizes = mutableListOf<Int>()
        var curPos = mdatOffset + 8

        for (i in 0 until 10) {
            val dummyJpeg = ByteArray(1000 + i * 50) { 0xFF.toByte() }
            vOffsets.add(curPos)
            vSizes.add(dummyJpeg.size)
            dos.write(dummyJpeg)
            curPos += dummyJpeg.size

            val dummyAudio = ByteArray(6400) { 0 }
            aOffsets.add(curPos)
            aSizes.add(dummyAudio.size)
            dos.write(dummyAudio)
            curPos += dummyAudio.size
        }
        dos.flush()
        dos.close()

        val rawMdatSize = (testFile.length() - mdatOffset).toInt()
        Mp4ContainerWriter.patchMp4File(
            testFile, 1024, 768, 30,
            vOffsets, vSizes, mdatOffset,
            aOffsets, aSizes, 48000, 2
        )

        val raf = RandomAccessFile(testFile, "r")
        raf.seek(mdatOffset)
        val patchedMdatSize = raf.readInt()
        assertEquals(rawMdatSize, patchedMdatSize)

        val allBytes = testFile.readBytes()
        val allBoxes = parseBoxes(allBytes)
        val fourccs = allBoxes.map { it.fourcc }
        assertEquals(listOf("ftyp", "mdat", "moov"), fourccs)
    }
}
