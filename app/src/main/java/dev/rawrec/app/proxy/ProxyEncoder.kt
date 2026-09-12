package dev.rawrec.app.proxy

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class ProxyFrame(
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val width: Int,
    val height: Int,
    var ptsUs: Long = 0L
)

class ProxyEncoder(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    @Suppress("unused") private val bitrateMbps: Int = 12,
    private val audioSampleRate: Int = 48000,
    private val audioChannels: Int = 2
) {
    private sealed class Msg {
        class Video(val frame: ProxyFrame, val eos: Boolean) : Msg()
        class Audio(val pcm: ByteArray, val tsNs: Long) : Msg()
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val pool = ArrayBlockingQueue<ProxyFrame>(POOL_CAPACITY).apply {
        repeat(POOL_CAPACITY) {
            add(ProxyFrame(ByteArray(width * height), ByteArray(width * height / 4), ByteArray(width * height / 4), width, height))
        }
    }
    private val queue = ArrayBlockingQueue<Msg>(QUEUE_CAPACITY)
    var framesEncoded: Long = 0
        private set
    var audioChunksEncoded: Long = 0
        private set
    var error: String? = null
        private set

    fun obtainBuffer(): ProxyFrame? = if (running) pool.poll() else null

    fun start() {
        check(!running)
        running = true
        thread = Thread({ runLoop() }, "rvsp-proxy").apply { start() }
    }

    fun submit(frame: ProxyFrame): Boolean {
        if (!running) {
            pool.offer(frame)
            return false
        }
        val offered = queue.offer(Msg.Video(frame, eos = false))
        if (!offered) {
            pool.offer(frame)
        }
        return offered
    }

    fun submitAudio(pcm: ByteArray, tsNs: Long): Boolean {
        if (!running) return false
        return queue.offer(Msg.Audio(pcm, tsNs))
    }

    fun finish() {
        if (!running) return
        running = false
        runCatching { queue.offer(Msg.Video(PLACEHOLDER, eos = true), 3, TimeUnit.SECONDS) }
        thread?.join(10_000)
        thread = null
    }

    private fun runLoop() {
        val nv21 = ByteArray(width * height * 3 / 2)
        val jpegQuality = 85
        val rect = Rect(0, 0, width, height)
        val frameOffsets = ArrayList<Long>(1000)
        val frameSizes = ArrayList<Int>(1000)
        val audioOffsets = ArrayList<Long>(1000)
        val audioSizes = ArrayList<Int>(1000)
        val jpegStream = ByteArrayOutputStream(width * height / 4)

        try {
            val fos = FileOutputStream(file)
            val bos = BufferedOutputStream(fos, 256 * 1024)
            val dos = DataOutputStream(bos)

            // Write standard ftyp box (mp42, isom, qt)
            val ftypPayload = "mp42\u0000\u0000\u0000\u0000mp42isomqt  ".toByteArray(Charsets.ISO_8859_1)
            val ftypBox = Mp4ContainerWriter.makeBox("ftyp", ftypPayload)
            dos.write(ftypBox)

            // Write mdat box placeholder
            val mdatOffset = ftypBox.size.toLong()
            dos.writeInt(0) // placeholder for mdat size
            dos.write("mdat".toByteArray(Charsets.US_ASCII))

            var currentFilePos = mdatOffset + 8

            while (true) {
                val msg = queue.poll(50, TimeUnit.MILLISECONDS)
                if (msg == null) {
                    if (!running && queue.isEmpty()) break
                    continue
                }

                when (msg) {
                    is Msg.Video -> {
                        if (msg.eos) break

                        val f = msg.frame
                        System.arraycopy(f.y, 0, nv21, 0, f.y.size)
                        val uvOffset = f.y.size
                        val uvLen = f.u.size
                        for (i in 0 until uvLen) {
                            nv21[uvOffset + i * 2] = f.v[i]
                            nv21[uvOffset + i * 2 + 1] = f.u[i]
                        }
                        pool.offer(f)

                        jpegStream.reset()
                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        yuvImage.compressToJpeg(rect, jpegQuality, jpegStream)
                        val jpegBytes = jpegStream.toByteArray()

                        frameOffsets.add(currentFilePos)
                        frameSizes.add(jpegBytes.size)
                        dos.write(jpegBytes)
                        currentFilePos += jpegBytes.size
                        framesEncoded++
                    }
                    is Msg.Audio -> {
                        audioOffsets.add(currentFilePos)
                        audioSizes.add(msg.pcm.size)
                        dos.write(msg.pcm)
                        currentFilePos += msg.pcm.size
                        audioChunksEncoded++
                    }
                }
            }

            dos.flush()
            bos.flush()
            fos.flush()
            dos.close()

            Mp4ContainerWriter.patchMp4File(
                file, width, height, fps,
                frameOffsets, frameSizes, mdatOffset,
                audioOffsets, audioSizes, audioSampleRate, audioChannels
            )
            dev.rawrec.app.util.AppLog.i(
                "ProxyEncoder",
                "proxy mp4 finalized: ${file.name} ($framesEncoded video frames, $audioChunksEncoded audio chunks, ${file.length()} bytes)"
            )
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            dev.rawrec.app.util.AppLog.e("ProxyEncoder", "proxy error", t)
        } finally {
            running = false
        }
    }

    companion object {
        private const val POOL_CAPACITY = 4
        private const val QUEUE_CAPACITY = 64
        private val PLACEHOLDER = ProxyFrame(ByteArray(0), ByteArray(0), ByteArray(0), 0, 0, 0)
    }
}
