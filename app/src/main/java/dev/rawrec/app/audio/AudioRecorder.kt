package dev.rawrec.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioRecorder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val clock: () -> Long = { System.nanoTime() },
    private val onChunk: (pcm: ByteArray, tsNs: Long) -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val channelMask =
        if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    val isAvailable: Boolean
        get() = runCatching {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            minBuf > 0
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        check(!running) { "already running" }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            channelMask, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 16_384) * 4
        )
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        record = rec
        running = true
        rec.startRecording()

        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    onChunk(buf.copyOf(n), clock())
                }
            } catch (_: Throwable) {
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
         * Computes stereo (L, R) peak audio levels in dBFS [-60f .. 0f] from a 16-bit LE PCM chunk.
         */
        fun computeStereoPeakDb(pcm: ByteArray, length: Int = pcm.size): FloatArray {
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
