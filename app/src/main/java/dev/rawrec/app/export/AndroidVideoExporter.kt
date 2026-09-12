package dev.rawrec.app.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.CubeLut
import dev.rawrec.tool.Rvtool
import dev.rawrec.tool.gpu.GpuManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance hardware video exporter for Android.
 *
 * Uses Android's native hardware MediaCodec (video/avc / H.264) and MediaMuxer to export
 * `.rvsp` takes into universally compatible MP4 files at 60+ FPS with multiplexed stereo audio.
 */
object AndroidVideoExporter {

    private class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int
    )

    fun export(
        rvspPath: String,
        outMp4Path: String,
        profile: ColorScience.ToneProfile = ColorScience.ToneProfile.CINE_FILMIC,
        customLut: CubeLut? = null,
        applyWb: Boolean = true,
        exportDownsample: Int = 2, // 1 = full sensor half-res (e.g. 2048x1536), 2 = 1080p class (e.g. 1024x768)
        bitrateBps: Int = 20_000_000,
        rotationDegrees: Int = 0,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File {
        val (h, raf) = Rvtool.openHeader(rvspPath)
        raf.use {
            val (frames, audio) = Rvtool.scanRecords(raf)
            require(frames.isNotEmpty()) { "No video frames found in $rvspPath" }

            val outFile = File(outMp4Path)
            outFile.parentFile?.mkdirs()

            val ds = if (exportDownsample >= 2) 2 else 1
            // Ensure even dimensions for hardware H.264 encoding
            val encW = ((h.width / 2) / ds) and -2
            val encH = ((h.height / 2) / ds) and -2

            val effectiveFps = if (frames.size > 1 && frames.last().tsNs > frames.first().tsNs) {
                val durationSec = (frames.last().tsNs - frames.first().tsNs) / 1e9
                val measured = Math.round((frames.size - 1) / durationSec).toInt()
                if (measured in 1..240) measured else if (h.fpsMilli > 0) Math.round(h.fpsMilli / 1000.0).toInt() else 30
            } else if (h.fpsMilli > 0) {
                Math.round(h.fpsMilli / 1000.0).toInt()
            } else 30

            val tempFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}.tmp_mp4")
            if (tempFile.exists()) tempFile.delete()

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val cleanRotation = when ((rotationDegrees % 360 + 360) % 360) {
                90 -> 90
                180 -> 180
                270 -> 270
                else -> 0
            }
            muxer.setOrientationHint(cleanRotation)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false
            val pendingSamples = ArrayList<PendingSample>()

            // 1. Configure Hardware Video Encoder (H.264 / AVC)
            @Suppress("DEPRECATION")
            val videoFormat = MediaFormat.createVideoFormat("video/avc", encW, encH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe
            }
            val videoEncoder = MediaCodec.createEncoderByType("video/avc")
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoEncoder.start()

            // 2. Configure Hardware Audio Encoder (AAC) if audio is present
            val hasAudio = audio.isNotEmpty()
            var audioEncoder: MediaCodec? = null
            if (hasAudio) {
                val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
                }
                audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
                    configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }

            fun checkStartMuxer() {
                if (!muxerStarted && videoTrackIndex >= 0 && (!hasAudio || audioTrackIndex >= 0)) {
                    muxer.start()
                    muxerStarted = true
                    for (sample in pendingSamples) {
                        val track = if (sample.isVideo) videoTrackIndex else audioTrackIndex
                        val buf = ByteBuffer.wrap(sample.data, sample.offset, sample.size)
                        val info = MediaCodec.BufferInfo().apply {
                            set(sample.offset, sample.size, sample.presentationTimeUs, sample.flags)
                        }
                        muxer.writeSampleData(track, buf, info)
                    }
                    pendingSamples.clear()
                }
            }

            fun handleSample(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) return
                if (muxerStarted) {
                    val track = if (isVideo) videoTrackIndex else audioTrackIndex
                    if (track >= 0) {
                        muxer.writeSampleData(track, buf, info)
                    }
                } else {
                    val bytes = ByteArray(info.size)
                    val origPos = buf.position()
                    buf.position(info.offset)
                    buf.get(bytes)
                    buf.position(origPos)
                    pendingSamples.add(
                        PendingSample(
                            isVideo = isVideo,
                            data = bytes,
                            offset = 0,
                            size = info.size,
                            presentationTimeUs = info.presentationTimeUs,
                            flags = info.flags
                        )
                    )
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val nv12Buf = ByteArray(encW * encH * 3 / 2)
            val expectedRaw = if (h.packing == 1) (h.width * h.height / 4 * 5) else (h.width * h.height * 2)
            val rawBuf = ByteArray(expectedRaw)
            val decoder = runCatching {
                dev.rawrec.app.codec.FrameCodecs.byId(h.videoCodec).openDecoder()
            }.getOrNull()

            try {
                // Encode Audio Chunk 0 first to register audio track in muxer early
                val aEnc = audioEncoder
                if (hasAudio && aEnc != null && audio.isNotEmpty()) {
                    val pcm0 = Rvtool.readPayload(raf, audio[0].offset, audio[0].size)
                    val aIn0 = aEnc.dequeueInputBuffer(100_000L)
                    if (aIn0 >= 0) {
                        aEnc.getInputBuffer(aIn0)?.apply {
                            clear()
                            put(pcm0)
                        }
                        aEnc.queueInputBuffer(aIn0, 0, pcm0.size, 0L, if (audio.size == 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                    drainAudio(aEnc, bufferInfo, ::checkStartMuxer, ::handleSample) { fmt ->
                        audioTrackIndex = muxer.addTrack(fmt)
                        checkStartMuxer()
                    }
                }

                // Encode Video Frames
                for (idx in frames.indices) {
                    val fr = frames[idx]
                    val rawPayload = Rvtool.readPayload(raf, fr)
                    val decoded = if (decoder != null && h.videoCodec == "ZSTD") {
                        val rc = decoder.decodeInto(rawPayload, rawBuf)
                        if (rc > 0) rawBuf else Rvtool.decodedPayload(h, rawPayload)
                    } else {
                        Rvtool.decodedPayload(h, rawPayload)
                    }

                    val argb = GpuManager.processFrame(
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
                        customLut = customLut,
                        downsample = ds
                    )

                    argbToNv12(argb, encW, encH, nv12Buf)

                    // Feed to Video Encoder
                    val inputBufIdx = videoEncoder.dequeueInputBuffer(100_000L)
                    if (inputBufIdx >= 0) {
                        val inputBuffer = videoEncoder.getInputBuffer(inputBufIdx)
                        inputBuffer?.clear()
                        inputBuffer?.put(nv12Buf)
                        val ptsUs = (idx.toLong() * 1_000_000L) / effectiveFps
                        val flags = if (idx == frames.size - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        videoEncoder.queueInputBuffer(inputBufIdx, 0, nv12Buf.size, ptsUs, flags)
                    }

                    // Drain Video Encoder
                    drainVideo(videoEncoder, bufferInfo, ::checkStartMuxer, ::handleSample) { fmt ->
                        videoTrackIndex = muxer.addTrack(fmt)
                        checkStartMuxer()
                    }

                    onProgress(idx + 1, frames.size)
                }

                // Drain remaining video frames
                var videoEos = false
                while (!videoEos) {
                    val outIdx = videoEncoder.dequeueOutputBuffer(bufferInfo, 100_000L)
                    if (outIdx >= 0) {
                        videoEncoder.getOutputBuffer(outIdx)?.let { outBuf ->
                            handleSample(true, outBuf, bufferInfo)
                        }
                        videoEncoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoEos = true
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                            checkStartMuxer()
                        }
                    } else {
                        break
                    }
                }

                // Encode & Mux Remaining Audio Chunks (from chunk 1 to end)
                if (hasAudio && aEnc != null) {
                    var audioPtsUs = if (audio.isNotEmpty()) (audio[0].size.toLong() * 1_000_000L) / (48000L * 4L) else 0L
                    for (aIdx in 1 until audio.size) {
                        val aRec = audio[aIdx]
                        val pcm = Rvtool.readPayload(raf, aRec.offset, aRec.size)
                        val inIdx = aEnc.dequeueInputBuffer(100_000L)
                        if (inIdx >= 0) {
                            aEnc.getInputBuffer(inIdx)?.apply {
                                clear()
                                put(pcm)
                            }
                            val isLast = aIdx == audio.size - 1
                            aEnc.queueInputBuffer(
                                inIdx, 0, pcm.size, audioPtsUs,
                                if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            audioPtsUs += (pcm.size.toLong() * 1_000_000L) / (48000L * 4L)
                        }

                        drainAudio(aEnc, bufferInfo, ::checkStartMuxer, ::handleSample) { fmt ->
                            if (audioTrackIndex < 0) {
                                audioTrackIndex = muxer.addTrack(fmt)
                                checkStartMuxer()
                            }
                        }
                    }

                    var audioEos = false
                    while (!audioEos) {
                        val outIdx = aEnc.dequeueOutputBuffer(bufferInfo, 100_000L)
                        if (outIdx >= 0) {
                            aEnc.getOutputBuffer(outIdx)?.let { outBuf ->
                                handleSample(false, outBuf, bufferInfo)
                            }
                            aEnc.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioEos = true
                            }
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (audioTrackIndex < 0) {
                                audioTrackIndex = muxer.addTrack(aEnc.outputFormat)
                                checkStartMuxer()
                            }
                        } else {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                dev.rawrec.app.util.AppLog.e("AndroidVideoExporter", "Export error: ${t.message}", t)
                throw t
            } finally {
                runCatching { decoder?.close() }
                runCatching {
                    videoEncoder.stop()
                    videoEncoder.release()
                }
                runCatching {
                    audioEncoder?.stop()
                    audioEncoder?.release()
                }
                runCatching {
                    if (muxerStarted) {
                        muxer.stop()
                    }
                    muxer.release()
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (outFile.exists()) outFile.delete()
                tempFile.renameTo(outFile)
            }
            return outFile
        }
    }

    private inline fun drainVideo(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        crossinline checkMuxer: () -> Unit,
        crossinline handleSample: (Boolean, ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        crossinline onFormatChanged: (MediaFormat) -> Unit
    ) {
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, 0L)
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormatChanged(encoder.outputFormat)
                checkMuxer()
            } else if (status >= 0) {
                encoder.getOutputBuffer(status)?.let { outBuf ->
                    handleSample(true, outBuf, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else {
                break
            }
        }
    }

    private inline fun drainAudio(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        crossinline checkMuxer: () -> Unit,
        crossinline handleSample: (Boolean, ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        crossinline onFormatChanged: (MediaFormat) -> Unit
    ) {
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, 0L)
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormatChanged(encoder.outputFormat)
                checkMuxer()
            } else if (status >= 0) {
                encoder.getOutputBuffer(status)?.let { outBuf ->
                    handleSample(false, outBuf, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else {
                break
            }
        }
    }

    /**
     * Fast, vectorized integer RGB -> NV12 (YUV420SemiPlanar) converter.
     */
    private fun argbToNv12(argb: IntArray, width: Int, height: Int, nv12: ByteArray) {
        val ySize = width * height
        var uvIndex = ySize
        var argbIndex = 0

        for (j in 0 until height) {
            val isEvenRow = (j and 1) == 0
            for (i in 0 until width) {
                val c = argb[argbIndex++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Fast integer BT.601 formula
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[j * width + i] = y.coerceIn(0, 255).toByte()

                if (isEvenRow && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                    nv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
