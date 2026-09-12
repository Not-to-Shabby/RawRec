package dev.rawrec.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.rawrec.tool.Rvtool
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-latency audio playback engine for `.rvsp` takes with embedded 48kHz stereo PCM audio.
 *
 * Synchronizes hardware AudioTrack streaming with the video transport deck,
 * supporting play, pause, seek, loop rewind, volume adjustments, and mute.
 */
class RvspAudioPlayer(
    private val file: File,
    private val audioRecords: List<Rvtool.AudioRec>
) {

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val isPlaying = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    private val seekRequested = AtomicBoolean(false)
    private val targetSeekTsNs = AtomicLong(0L)
    private val baseAudioTsNs = AtomicLong(0L)
    private val startHeadPosition = AtomicLong(0L)

    private var volume: Float = 1.0f
    private var isMuted: Boolean = false

    val hasAudio: Boolean
        get() = audioRecords.isNotEmpty()

    init {
        if (hasAudio) {
            initAudioTrack()
        }
    }

    private fun initAudioTrack() {
        runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                48000,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, 48000 * 4 / 2) // 500ms internal hardware buffer

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.setVolume(if (isMuted) 0f else volume)
            audioTrack = track
        }
    }

    /**
     * Exact hardware audio playback time currently being output by the speaker.
     */
    fun getHardwareAudioTimeNs(): Long? {
        if (!hasAudio || !isPlaying.get()) return null
        val track = audioTrack ?: return null
        val baseTs = baseAudioTsNs.get()
        if (baseTs <= 0L) return null
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val startHead = startHeadPosition.get()
        val playedFrames = (head - startHead).coerceAtLeast(0L)
        val playedNs = (playedFrames * 1_000_000_000L) / 48000L
        return baseTs + playedNs
    }

    /**
     * Starts or resumes synchronized audio streaming from [startTsNs].
     */
    @Synchronized
    fun play(startTsNs: Long) {
        if (!hasAudio || isReleased.get()) return

        targetSeekTsNs.set(startTsNs)
        seekRequested.set(true)
        isPlaying.set(true)

        runCatching {
            audioTrack?.play()
        }

        if (playbackThread == null || playbackThread?.isAlive == false) {
            playbackThread = Thread({ streamAudioLoop() }, "RvspAudioPlayer-Stream").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Pauses audio playback and flushes hardware buffers so audio immediately cuts.
     */
    @Synchronized
    fun pause() {
        if (!hasAudio || isReleased.get()) return
        isPlaying.set(false)
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    /**
     * Seeks audio playhead to [targetTsNs].
     */
    @Synchronized
    fun seekTo(targetTsNs: Long) {
        if (!hasAudio || isReleased.get()) return
        targetSeekTsNs.set(targetTsNs)
        seekRequested.set(true)
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            if (isPlaying.get()) {
                audioTrack?.play()
            }
        }
    }

    /**
     * Sets playback volume (0.0f to 1.0f).
     */
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        if (!isMuted) {
            audioTrack?.setVolume(volume)
        }
    }

    /**
     * Mutes or unmutes audio output.
     */
    fun setMute(muted: Boolean) {
        isMuted = muted
        audioTrack?.setVolume(if (isMuted) 0f else volume)
    }

    fun toggleMute(): Boolean {
        setMute(!isMuted)
        return isMuted
    }

    /**
     * Audio streaming loop running in background daemon thread.
     */
    private fun streamAudioLoop() {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "r")
            var currentIdx = 0

            while (!isReleased.get()) {
                if (!isPlaying.get()) {
                    Thread.sleep(10)
                    continue
                }

                if (seekRequested.compareAndSet(true, false)) {
                    val targetTs = targetSeekTsNs.get()
                    var idx = audioRecords.binarySearch { it.timestampNs.compareTo(targetTs) }
                    if (idx < 0) idx = -(idx + 1) - 1
                    currentIdx = idx.coerceIn(0, audioRecords.size - 1)
                    val rec = audioRecords[currentIdx]
                    baseAudioTsNs.set(rec.timestampNs)
                    val track = audioTrack
                    startHeadPosition.set((track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL)
                }

                if (currentIdx < audioRecords.size) {
                    val rec = audioRecords[currentIdx]
                    val pcmBytes = Rvtool.readPayload(raf, rec.offset, rec.size)
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                    currentIdx++
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: Throwable) {
            // Player closed or interrupted
        } finally {
            runCatching { raf?.close() }
        }
    }

    /**
     * Releases hardware AudioTrack and terminates threads.
     */
    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            isPlaying.set(false)
            playbackThread?.interrupt()
            playbackThread = null
            runCatching {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }
        }
    }
}
