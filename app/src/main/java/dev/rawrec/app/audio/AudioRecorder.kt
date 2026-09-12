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
    }
}
