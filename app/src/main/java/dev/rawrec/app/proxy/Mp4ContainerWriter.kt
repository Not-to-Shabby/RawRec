package dev.rawrec.app.proxy

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mp4ContainerWriter {

    fun makeBox(fourcc: String, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8 + payload.size)
        buf.put(fourcc.toByteArray(Charsets.US_ASCII))
        buf.put(payload)
        return buf.array()
    }

    fun makeHdlr(handlerType: String, name: String): ByteArray {
        val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(4 + 4 + 4 + 12 + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(0) // version & flags
        payload.putInt(0) // pre_defined
        payload.put(handlerType.toByteArray(Charsets.US_ASCII))
        repeat(3) { payload.putInt(0) } // 12 bytes reserved
        payload.put(nameBytes)
        return makeBox("hdlr", payload.array())
    }

    /**
     * Color Parameter Box (`colr`) with `nclx` payload signaling standard HDR/HLG/BT.2020 metadata.
     *
     * @param primaries 9 = ITU-R BT.2020, 1 = ITU-R BT.709
     * @param transfer 18 = ITU-R BT.2100 HLG (ARIB STD-B67), 16 = SMPTE ST 2084 (PQ), 1 = BT.709
     * @param matrix 9 = ITU-R BT.2020 non-constant luminance, 1 = BT.709
     * @param fullRange true = Full Range (0-255 / 0-1023), false = Limited Range
     */
    fun makeColrNclx(
        primaries: Short = 9,
        transfer: Short = 18,
        matrix: Short = 9,
        fullRange: Boolean = true
    ): ByteArray {
        val buf = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
        buf.put("nclx".toByteArray(Charsets.US_ASCII))
        buf.putShort(primaries)
        buf.putShort(transfer)
        buf.putShort(matrix)
        buf.put(if (fullRange) 0x80.toByte() else 0x00.toByte())
        return makeBox("colr", buf.array())
    }

    /**
     * File Type Box (`ftyp`) for ISO MP4 / QuickTime compatibility.
     */
    fun makeFtyp(
        majorBrand: String = "mp42",
        minorVersion: Int = 0,
        compatibleBrands: List<String> = listOf("isom", "mp42", "qt  ")
    ): ByteArray {
        val buf = ByteBuffer.allocate(8 + compatibleBrands.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(majorBrand.toByteArray(Charsets.US_ASCII))
        buf.putInt(minorVersion)
        for (b in compatibleBrands) {
            buf.put(b.toByteArray(Charsets.US_ASCII))
        }
        return makeBox("ftyp", buf.array())
    }

    fun buildMoovBox(
        w: Int,
        h: Int,
        fps: Int,
        videoOffsets: List<Long>,
        videoSizes: List<Int>,
        audioOffsets: List<Long> = emptyList(),
        audioSizes: List<Int> = emptyList(),
        audioSampleRate: Int = 48000,
        audioChannels: Int = 2,
        isHlg: Boolean = false,
        rotation: Int = 0
    ): ByteArray {
        val totalVideoFrames = videoSizes.size
        if (totalVideoFrames == 0) return ByteArray(0)
        val effectiveFps = if (fps > 0) fps else 30
        val timescale = 600
        val videoFrameDuration = timescale / effectiveFps
        val videoDurationInTimescale = totalVideoFrames * videoFrameDuration

        val bytesPerAudioSample = audioChannels * 2
        val audioSamplesPerChunk = audioSizes.map { it / bytesPerAudioSample }
        val totalAudioSamples = audioSamplesPerChunk.sum()
        val audioDurationInTimescale = if (totalAudioSamples > 0) {
            (totalAudioSamples.toLong() * timescale / audioSampleRate).toInt()
        } else 0

        val maxDuration = maxOf(videoDurationInTimescale, audioDurationInTimescale)
        val hasAudio = totalAudioSamples > 0 && audioOffsets.isNotEmpty()

        // mvhd box
        val mvhdBuf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        mvhdBuf.putInt(0) // version & flags
        mvhdBuf.putInt(0) // creation time
        mvhdBuf.putInt(0) // modification time
        mvhdBuf.putInt(timescale)
        mvhdBuf.putInt(maxDuration)
        mvhdBuf.putInt(0x00010000) // rate 1.0
        mvhdBuf.putShort(0x0100) // volume 1.0
        mvhdBuf.putShort(0) // reserved
        mvhdBuf.putLong(0) // reserved
        // matrix (identity)
        mvhdBuf.putInt(0x00010000); mvhdBuf.putInt(0); mvhdBuf.putInt(0)
        mvhdBuf.putInt(0); mvhdBuf.putInt(0x00010000); mvhdBuf.putInt(0)
        mvhdBuf.putInt(0); mvhdBuf.putInt(0); mvhdBuf.putInt(0x40000000)
        // pre_defined[6]
        repeat(6) { mvhdBuf.putInt(0) }
        mvhdBuf.putInt(if (hasAudio) 3 else 2) // next track id
        val mvhdBox = makeBox("mvhd", mvhdBuf.array())

        // --- Video Track (Track 1) ---
        val tkhdBuf = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
        tkhdBuf.putInt(0x0000000f) // track enabled, in movie, in preview
        tkhdBuf.putInt(0) // creation time
        tkhdBuf.putInt(0) // mod time
        tkhdBuf.putInt(1) // track id
        tkhdBuf.putInt(0) // reserved
        tkhdBuf.putInt(videoDurationInTimescale)
        tkhdBuf.putLong(0) // reserved
        tkhdBuf.putShort(0) // layer
        tkhdBuf.putShort(0) // alternate group
        tkhdBuf.putShort(0) // volume
        tkhdBuf.putShort(0) // reserved
        // matrix (rotation-aware)
        when ((rotation % 360 + 360) % 360) {
            90 -> {
                tkhdBuf.putInt(0); tkhdBuf.putInt(0x00010000); tkhdBuf.putInt(0)
                tkhdBuf.putInt(-0x00010000); tkhdBuf.putInt(0); tkhdBuf.putInt(0)
                tkhdBuf.putInt(h shl 16); tkhdBuf.putInt(0); tkhdBuf.putInt(0x40000000)
            }
            180 -> {
                tkhdBuf.putInt(-0x00010000); tkhdBuf.putInt(0); tkhdBuf.putInt(0)
                tkhdBuf.putInt(0); tkhdBuf.putInt(-0x00010000); tkhdBuf.putInt(0)
                tkhdBuf.putInt(w shl 16); tkhdBuf.putInt(h shl 16); tkhdBuf.putInt(0x40000000)
            }
            270 -> {
                tkhdBuf.putInt(0); tkhdBuf.putInt(-0x00010000); tkhdBuf.putInt(0)
                tkhdBuf.putInt(0x00010000); tkhdBuf.putInt(0); tkhdBuf.putInt(0)
                tkhdBuf.putInt(0); tkhdBuf.putInt(w shl 16); tkhdBuf.putInt(0x40000000)
            }
            else -> {
                tkhdBuf.putInt(0x00010000); tkhdBuf.putInt(0); tkhdBuf.putInt(0)
                tkhdBuf.putInt(0); tkhdBuf.putInt(0x00010000); tkhdBuf.putInt(0)
                tkhdBuf.putInt(0); tkhdBuf.putInt(0); tkhdBuf.putInt(0x40000000)
            }
        }
        tkhdBuf.putInt(w shl 16) // width (fixed point 16.16)
        tkhdBuf.putInt(h shl 16) // height
        val tkhdBox = makeBox("tkhd", tkhdBuf.array())

        val mdhdBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        mdhdBuf.putInt(0)
        mdhdBuf.putInt(0)
        mdhdBuf.putInt(0)
        mdhdBuf.putInt(timescale)
        mdhdBuf.putInt(videoDurationInTimescale)
        mdhdBuf.putInt(0x55c40000) // und language
        val mdhdBox = makeBox("mdhd", mdhdBuf.array())

        val hdlrVideoBox = makeHdlr("vide", "VideoHandler")

        val vmhdBuf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        vmhdBuf.putInt(1) // version & flags
        vmhdBuf.putShort(0) // graphics mode
        vmhdBuf.putShort(0); vmhdBuf.putShort(0); vmhdBuf.putShort(0) // opcolor
        val vmhdBox = makeBox("vmhd", vmhdBuf.array())

        val drefPayload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        drefPayload.putInt(0) // version & flags
        drefPayload.putInt(1) // entry count
        drefPayload.putInt(12) // size
        drefPayload.put("url ".toByteArray(Charsets.US_ASCII))
        drefPayload.putInt(1) // flags = 1 (self contained)
        val dinfBox = makeBox("dinf", makeBox("dref", drefPayload.array()))

        // stsd box (jpeg sample entry with optional colr HDR / HLG atom)
        val jpegEntry = ByteBuffer.allocate(78).order(ByteOrder.BIG_ENDIAN)
        jpegEntry.put(ByteArray(6)) // reserved
        jpegEntry.putShort(1) // data ref index
        jpegEntry.putShort(0) // pre_defined
        jpegEntry.putShort(0) // reserved
        repeat(3) { jpegEntry.putInt(0) }
        jpegEntry.putShort(w.toShort())
        jpegEntry.putShort(h.toShort())
        jpegEntry.putInt(0x00480000) // 72 dpi
        jpegEntry.putInt(0x00480000)
        jpegEntry.putInt(0) // data size
        jpegEntry.putShort(1) // frame count
        jpegEntry.put(ByteArray(32)) // compressor name
        jpegEntry.putShort(24) // depth
        jpegEntry.putShort((-1).toShort()) // pre_defined

        val colrAtom = if (isHlg) {
            makeColrNclx(primaries = 9, transfer = 18, matrix = 9, fullRange = true)
        } else {
            makeColrNclx(primaries = 1, transfer = 1, matrix = 1, fullRange = true)
        }

        val jpegPayload = concatByteArrays(listOf(jpegEntry.array(), colrAtom))
        val jpegBox = makeBox("jpeg", jpegPayload)
        val stsdPayload = ByteBuffer.allocate(8 + jpegBox.size).order(ByteOrder.BIG_ENDIAN)
        stsdPayload.putInt(0) // version & flags
        stsdPayload.putInt(1) // entry count
        stsdPayload.put(jpegBox)
        val stsdBox = makeBox("stsd", stsdPayload.array())

        val sttsBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        sttsBuf.putInt(0)
        sttsBuf.putInt(1) // 1 entry
        sttsBuf.putInt(totalVideoFrames)
        sttsBuf.putInt(videoFrameDuration)
        val sttsBox = makeBox("stts", sttsBuf.array())

        val stscBuf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        stscBuf.putInt(0)
        stscBuf.putInt(1) // 1 entry
        stscBuf.putInt(1) // first chunk
        stscBuf.putInt(1) // samples per chunk
        stscBuf.putInt(1) // sample desc index
        val stscBox = makeBox("stsc", stscBuf.array())

        val stszBuf = ByteBuffer.allocate(12 + totalVideoFrames * 4).order(ByteOrder.BIG_ENDIAN)
        stszBuf.putInt(0)
        stszBuf.putInt(0) // sample size (0 = variable)
        stszBuf.putInt(totalVideoFrames)
        for (s in videoSizes) { stszBuf.putInt(s) }
        val stszBox = makeBox("stsz", stszBuf.array())

        val videoNeeds64 = videoOffsets.any { it > 0x7FFFFFFFL }
        val videoChunkOffsetBox = if (videoNeeds64) {
            val co64Buf = ByteBuffer.allocate(8 + totalVideoFrames * 8).order(ByteOrder.BIG_ENDIAN)
            co64Buf.putInt(0) // version(0) + flags(0)
            co64Buf.putInt(totalVideoFrames)
            for (o in videoOffsets) { co64Buf.putLong(o) }
            makeBox("co64", co64Buf.array())
        } else {
            val stcoBuf = ByteBuffer.allocate(8 + totalVideoFrames * 4).order(ByteOrder.BIG_ENDIAN)
            stcoBuf.putInt(0)
            stcoBuf.putInt(totalVideoFrames)
            for (o in videoOffsets) { stcoBuf.putInt(o.toInt()) }
            makeBox("stco", stcoBuf.array())
        }

        val stblBox = makeBox("stbl", concatByteArrays(listOf(stsdBox, sttsBox, stscBox, stszBox, videoChunkOffsetBox)))
        val minfBox = makeBox("minf", concatByteArrays(listOf(vmhdBox, dinfBox, stblBox)))
        val mdiaBox = makeBox("mdia", concatByteArrays(listOf(mdhdBox, hdlrVideoBox, minfBox)))
        val trakVideoBox = makeBox("trak", concatByteArrays(listOf(tkhdBox, mdiaBox)))

        // --- Audio Track (Track 2) ---
        val trakAudioBox = if (hasAudio) {
            val tkhdAudioBuf = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
            tkhdAudioBuf.putInt(0x0000000f)
            tkhdAudioBuf.putInt(0)
            tkhdAudioBuf.putInt(0)
            tkhdAudioBuf.putInt(2) // track id 2
            tkhdAudioBuf.putInt(0)
            tkhdAudioBuf.putInt(audioDurationInTimescale)
            tkhdAudioBuf.putLong(0)
            tkhdAudioBuf.putShort(0)
            tkhdAudioBuf.putShort(0)
            tkhdAudioBuf.putShort(0x0100) // volume 1.0
            tkhdAudioBuf.putShort(0)
            // identity matrix
            tkhdAudioBuf.putInt(0x00010000); tkhdAudioBuf.putInt(0); tkhdAudioBuf.putInt(0)
            tkhdAudioBuf.putInt(0); tkhdAudioBuf.putInt(0x00010000); tkhdAudioBuf.putInt(0)
            tkhdAudioBuf.putInt(0); tkhdAudioBuf.putInt(0); tkhdAudioBuf.putInt(0x40000000)
            tkhdAudioBuf.putInt(0); tkhdAudioBuf.putInt(0) // width/height 0
            val tkhdAudioBox = makeBox("tkhd", tkhdAudioBuf.array())

            val mdhdAudioBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            mdhdAudioBuf.putInt(0)
            mdhdAudioBuf.putInt(0)
            mdhdAudioBuf.putInt(0)
            mdhdAudioBuf.putInt(audioSampleRate)
            mdhdAudioBuf.putInt(totalAudioSamples)
            mdhdAudioBuf.putInt(0x55c40000) // und language
            val mdhdAudioBox = makeBox("mdhd", mdhdAudioBuf.array())

            val hdlrAudioBox = makeHdlr("soun", "SoundHandler")

            val smhdBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            smhdBuf.putInt(0) // version & flags
            smhdBuf.putShort(0) // balance
            smhdBuf.putShort(0) // reserved
            val smhdBox = makeBox("smhd", smhdBuf.array())

            // sowt sample entry (16-bit LE PCM)
            val sowtEntry = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
            sowtEntry.put(ByteArray(6)) // reserved
            sowtEntry.putShort(1) // data ref index
            sowtEntry.putShort(0) // version
            sowtEntry.putShort(0) // revision
            sowtEntry.putInt(0) // vendor
            sowtEntry.putShort(audioChannels.toShort())
            sowtEntry.putShort(16) // sample size bits
            sowtEntry.putShort(0) // compression id
            sowtEntry.putShort(0) // packet size
            sowtEntry.putInt(audioSampleRate shl 16) // sample rate (fixed point 16.16)
            val sowtBox = makeBox("sowt", sowtEntry.array())

            val stsdAudioPayload = ByteBuffer.allocate(8 + sowtBox.size).order(ByteOrder.BIG_ENDIAN)
            stsdAudioPayload.putInt(0) // version & flags
            stsdAudioPayload.putInt(1) // entry count
            stsdAudioPayload.put(sowtBox)
            val stsdAudioBox = makeBox("stsd", stsdAudioPayload.array())

            val sttsAudioBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            sttsAudioBuf.putInt(0)
            sttsAudioBuf.putInt(1)
            sttsAudioBuf.putInt(totalAudioSamples)
            sttsAudioBuf.putInt(1) // 1 sample per timescale tick
            val sttsAudioBox = makeBox("stts", sttsAudioBuf.array())

            val stscAudioBuf = ByteBuffer.allocate(8 + audioSamplesPerChunk.size * 12).order(ByteOrder.BIG_ENDIAN)
            stscAudioBuf.putInt(0)
            stscAudioBuf.putInt(audioSamplesPerChunk.size)
            for (i in audioSamplesPerChunk.indices) {
                stscAudioBuf.putInt(i + 1) // first chunk
                stscAudioBuf.putInt(audioSamplesPerChunk[i]) // samples per chunk
                stscAudioBuf.putInt(1) // sample desc index
            }
            val stscAudioBox = makeBox("stsc", stscAudioBuf.array())

            // stsz: fixed bytesPerAudioSample per sample
            val stszAudioBuf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            stszAudioBuf.putInt(0)
            stszAudioBuf.putInt(bytesPerAudioSample) // sample size
            stszAudioBuf.putInt(totalAudioSamples) // sample count
            val stszAudioBox = makeBox("stsz", stszAudioBuf.array())

            val audioNeeds64 = audioOffsets.any { it > 0x7FFFFFFFL }
            val audioChunkOffsetBox = if (audioNeeds64) {
                val co64AudioBuf = ByteBuffer.allocate(8 + audioOffsets.size * 8).order(ByteOrder.BIG_ENDIAN)
                co64AudioBuf.putInt(0)
                co64AudioBuf.putInt(audioOffsets.size)
                for (o in audioOffsets) { co64AudioBuf.putLong(o) }
                makeBox("co64", co64AudioBuf.array())
            } else {
                val stcoAudioBuf = ByteBuffer.allocate(8 + audioOffsets.size * 4).order(ByteOrder.BIG_ENDIAN)
                stcoAudioBuf.putInt(0)
                stcoAudioBuf.putInt(audioOffsets.size)
                for (o in audioOffsets) { stcoAudioBuf.putInt(o.toInt()) }
                makeBox("stco", stcoAudioBuf.array())
            }

            val stblAudioBox = makeBox("stbl", concatByteArrays(listOf(stsdAudioBox, sttsAudioBox, stscAudioBox, stszAudioBox, audioChunkOffsetBox)))
            val minfAudioBox = makeBox("minf", concatByteArrays(listOf(smhdBox, dinfBox, stblAudioBox)))
            val mdiaAudioBox = makeBox("mdia", concatByteArrays(listOf(mdhdAudioBox, hdlrAudioBox, minfAudioBox)))
            makeBox("trak", concatByteArrays(listOf(tkhdAudioBox, mdiaAudioBox)))
        } else null

        val traks = if (trakAudioBox != null) listOf(mvhdBox, trakVideoBox, trakAudioBox) else listOf(mvhdBox, trakVideoBox)
        return makeBox("moov", concatByteArrays(traks))
    }

    fun patchMp4File(
        file: File,
        w: Int,
        h: Int,
        fps: Int,
        videoOffsets: List<Long>,
        videoSizes: List<Int>,
        mdatOffset: Long,
        audioOffsets: List<Long> = emptyList(),
        audioSizes: List<Int> = emptyList(),
        audioSampleRate: Int = 48000,
        audioChannels: Int = 2,
        isHlg: Boolean = false,
        rotation: Int = 0
    ) {
        val moovBox = buildMoovBox(
            w, h, fps, videoOffsets, videoSizes,
            audioOffsets, audioSizes, audioSampleRate, audioChannels,
            isHlg = isHlg,
            rotation = rotation
        )
        val raf = RandomAccessFile(file, "rw")
        val mdatSize = (file.length() - mdatOffset).toInt()
        raf.seek(mdatOffset)
        raf.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mdatSize).array())

        raf.seek(raf.length())
        raf.write(moovBox)
        raf.close()
    }

    private fun concatByteArrays(list: List<ByteArray>): ByteArray {
        val total = list.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (b in list) {
            System.arraycopy(b, 0, out, pos, b.size)
            pos += b.size
        }
        return out
    }
}
