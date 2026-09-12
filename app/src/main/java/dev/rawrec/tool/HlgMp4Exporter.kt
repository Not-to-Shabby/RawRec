package dev.rawrec.tool

import dev.rawrec.app.proxy.Mp4ContainerWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exports `.rvsp` takes into standalone ISO MP4 video containers with
 * standard ITU-R BT.2100 Hybrid Log-Gamma (HLG) HDR signaling (`colr` box with BT.2020 primaries),
 * optional tone profiles/LUTs, and multiplexed 16-bit 48kHz stereo PCM audio.
 *
 * Uses pure Kotlin baseline JPEG encoding to remain 100% Android and desktop JVM compliant
 * without java.awt.image / ImageIO dependencies.
 */
object HlgMp4Exporter {

    fun export(
        rvspPath: String,
        outMp4Path: String,
        profile: ColorScience.ToneProfile = ColorScience.ToneProfile.CINE_HLG,
        customLut: CubeLut? = null,
        applyWb: Boolean = true,
        jpegQuality: Int = 92,
        isHlg: Boolean = true,
        rotation: Int = 0,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File {
        val (h, raf) = Rvtool.openHeader(rvspPath)
        raf.use {
            val (frames, audio) = Rvtool.scanRecords(raf)
            require(frames.isNotEmpty()) { "No video frames found in $rvspPath" }

            val outFile = File(outMp4Path)
            outFile.parentFile?.mkdirs()

            val w = h.width / 2
            val hh = h.height / 2
            val effectiveFps = if (frames.size > 1 && frames.last().tsNs > frames.first().tsNs) {
                val durationSec = (frames.last().tsNs - frames.first().tsNs) / 1e9
                val measured = Math.round((frames.size - 1) / durationSec).toInt()
                if (measured in 1..240) measured else if (h.fpsMilli > 0) Math.round(h.fpsMilli / 1000.0).toInt() else 30
            } else if (h.fpsMilli > 0) {
                Math.round(h.fpsMilli / 1000.0).toInt()
            } else 30

            val videoOffsets = ArrayList<Long>(frames.size)
            val videoSizes = ArrayList<Int>(frames.size)
            val audioOffsets = ArrayList<Long>(audio.size)
            val audioSizes = ArrayList<Int>(audio.size)

            val tempFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}.tmp_mp4")
            if (tempFile.exists()) tempFile.delete()

            val ftypBox = Mp4ContainerWriter.makeFtyp("mp42", 0, listOf("isom", "mp42", "qt  "))
            val ftypSize = ftypBox.size.toLong()

            val outStream = FileOutputStream(tempFile)
            outStream.use { out ->
                // 1. Write ftyp box
                out.write(ftypBox)

                // 2. Start mdat box with 8-byte placeholder (4-byte size + 4-byte 'mdat')
                val mdatOffset = ftypSize
                val mdatHeaderPlaceholder = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                mdatHeaderPlaceholder.putInt(0) // placeholder length
                mdatHeaderPlaceholder.put("mdat".toByteArray(Charsets.US_ASCII))
                out.write(mdatHeaderPlaceholder.array())

                var currentMdatOffset = 8L // offset relative to mdat payload

                // 3. Write video frames
                for (idx in frames.indices) {
                    val fr = frames[idx]
                    val payload = Rvtool.readPayload(raf, fr)
                    val decoded = Rvtool.decodedPayload(h, payload)

                    val argb = dev.rawrec.tool.gpu.GpuManager.processFrame(
                        mipiPayload = decoded,
                        width = h.width,
                        height = h.height,
                        cfa = h.cfa,
                        packing = h.packing,
                        blackLevels = h.blackLevels,
                        whiteLevel = h.whiteLevel,
                        asShotNeutral = h.asShotNeutral,
                        applyCalibration = applyWb,
                        profile = profile,
                        enableVignette = false,
                        customLut = customLut
                    )

                    val jpegBytes = JpegEncoder.encode(argb, w, hh, quality = jpegQuality)
                    out.write(jpegBytes)

                    videoOffsets.add(mdatOffset + currentMdatOffset)
                    videoSizes.add(jpegBytes.size)
                    currentMdatOffset += jpegBytes.size

                    onProgress(idx + 1, frames.size)
                }

                // 4. Write audio chunks (if present)
                for (rec in audio) {
                    val pcm = Rvtool.readPayload(raf, rec.offset, rec.size)
                    out.write(pcm)
                    audioOffsets.add(mdatOffset + currentMdatOffset)
                    audioSizes.add(pcm.size)
                    currentMdatOffset += pcm.size
                }
            }

            // 5. Finalize mdat size and write moov box
            Mp4ContainerWriter.patchMp4File(
                tempFile,
                w,
                hh,
                effectiveFps,
                videoOffsets,
                videoSizes,
                mdatOffset = ftypSize,
                audioOffsets = audioOffsets,
                audioSizes = audioSizes,
                audioSampleRate = 48000,
                audioChannels = 2,
                isHlg = isHlg,
                rotation = rotation
            )

            if (outFile.exists()) outFile.delete()
            tempFile.renameTo(outFile)

            return outFile
        }
    }
}
