package dev.rawrec.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioRecorder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val clock: () -> Long = { System.nanoTime() },
    private val onChunk: (pcm: ByteArray, tsNs: Long) -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile var activeEncoding: Int = audioEncoding
        private set

    private val channelMask =
        if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    val isAvailable: Boolean
        get() = runCatching {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, activeEncoding)
            minBuf > 0
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        check(!running) { "already running" }
        var targetEncoding = audioEncoding
        var minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, targetEncoding)
        if (minBuf <= 0 && targetEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            // Fall back to 16-bit PCM if device HAL does not support float
            targetEncoding = AudioFormat.ENCODING_PCM_16BIT
            minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, targetEncoding)
        }
        val bufferCapacity = maxOf(minBuf, 16_384) * 4
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            channelMask, targetEncoding,
            bufferCapacity
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed for encoding=$targetEncoding")
        }
        activeEncoding = targetEncoding
        record = rec
        running = true
        rec.startRecording()

        val isFloat = activeEncoding == AudioFormat.ENCODING_PCM_FLOAT
        val chunkBytes = if (isFloat) CHUNK_BYTES * 2 else CHUNK_BYTES
        thread = Thread({
            try {
                if (isFloat) {
                    val floatBuf = FloatArray(chunkBytes / 4)
                    val byteBuf = java.nio.ByteBuffer.allocate(chunkBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    while (running) {
                        val readFloats = rec.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
                        if (readFloats <= 0) continue
                        byteBuf.clear()
                        for (i in 0 until readFloats) {
                            byteBuf.putFloat(floatBuf[i])
                        }
                        onChunk(byteBuf.array().copyOf(readFloats * 4), clock())
                    }
                } else {
                    val buf = ByteArray(chunkBytes)
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        onChunk(buf.copyOf(n), clock())
                    }
                }
            } catch (t: Throwable) {
                dev.rawrec.app.util.AppLog.e("AudioRecorder", "audio read loop error: ${t.message}", t)
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }, "rvsp-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(TimeUnit_MS)
        thread = null
        record = null
    }

    companion object {
        private const val CHUNK_BYTES = 16_384
        private const val TimeUnit_MS = 5_000L

        /**
         * Computes stereo (L, R) peak audio levels in dBFS [-60f .. +12f] from a 16-bit integer or 32-bit float PCM chunk.
         */
        fun computeStereoPeakDb(pcm: ByteArray, length: Int = pcm.size, isFloat: Boolean = false): FloatArray {
            if (isFloat) {
                var maxL = 0f
                var maxR = 0f
                val limit = (length / 8) * 8
                var i = 0
                while (i < limit) {
                    val bL = (pcm[i].toInt() and 0xFF) or
                            ((pcm[i + 1].toInt() and 0xFF) shl 8) or
                            ((pcm[i + 2].toInt() and 0xFF) shl 16) or
                            ((pcm[i + 3].toInt() and 0xFF) shl 24)
                    val bR = (pcm[i + 4].toInt() and 0xFF) or
                            ((pcm[i + 5].toInt() and 0xFF) shl 8) or
                            ((pcm[i + 6].toInt() and 0xFF) shl 16) or
                            ((pcm[i + 7].toInt() and 0xFF) shl 24)
                    val sL = kotlin.math.abs(java.lang.Float.intBitsToFloat(bL))
                    val sR = kotlin.math.abs(java.lang.Float.intBitsToFloat(bR))
                    if (sL > maxL) maxL = sL
                    if (sR > maxR) maxR = sR
                    i += 8
                }
                fun toFloatDb(peak: Float): Float {
                    if (peak <= 0.0009f) return -60f
                    val db = 20.0 * kotlin.math.log10(peak.toDouble())
                    return db.toFloat().coerceIn(-60f, 12f)
                }
                return floatArrayOf(toFloatDb(maxL), toFloatDb(maxR))
            } else {
                var maxL = 0
                var maxR = 0
                val limit = (length / 4) * 4
                var i = 0
                while (i < limit) {
                    val sL = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                    val sR = ((pcm[i + 3].toInt() shl 8) or (pcm[i + 2].toInt() and 0xFF)).toShort().toInt()
                    val absL = kotlin.math.abs(sL)
                    val absR = kotlin.math.abs(sR)
                    if (absL > maxL) maxL = absL
                    if (absR > maxR) maxR = absR
                    i += 4
                }
                fun toDb(peak: Int): Float {
                    if (peak <= 32) return -60f
                    val db = 20.0 * kotlin.math.log10(peak.toDouble() / 32768.0)
                    return db.toFloat().coerceIn(-60f, 0f)
                }
                return floatArrayOf(toDb(maxL), toDb(maxR))
            }
        }
    }
}
