package dev.rawrec.app.container

import dev.rawrec.app.codec.FrameCodec
import dev.rawrec.app.codec.StoreCodec
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.Flushable
import java.io.OutputStream
import java.nio.ByteBuffer

class RvspWriter(
    output: OutputStream,
    private val header: RvspHeader,
    private val codec: FrameCodec = StoreCodec
) : Closeable, Flushable {

    private val out = BufferedOutputStream(output, WRITE_BUFFER_BYTES)
    private var sequence = 0L

    var framesWritten: Long = 0L
        private set

    var audioChunksWritten: Long = 0L
        private set

    init {
        out.write(header.toBytes())
    }

    @Synchronized
    fun writeFrame(payload: ByteArray, timestampNs: Long, exposureNs: Long = 0L, iso: Int = 0) {
        val encoded = if (codec.compressed) codec.encode(payload) else payload
        writeRecord(Rvsp.FRAME_MAGIC, encoded, timestampNs, exposureNs, iso)
        framesWritten++
    }

    @Synchronized
    fun writeFrameRaw(payload: ByteArray, timestampNs: Long, exposureNs: Long = 0L, iso: Int = 0) {
        writeRecord(Rvsp.FRAME_MAGIC, payload, timestampNs, exposureNs, iso)
        framesWritten++
    }

    @Synchronized
    fun writeAudioChunk(payload: ByteArray, timestampNs: Long) {
        writeRecord(Rvsp.AUDIO_MAGIC, payload, timestampNs, 0L, 0)
        audioChunksWritten++
    }

    private fun writeRecord(
        magic: ByteArray,
        payload: ByteArray,
        timestampNs: Long,
        extra1: Long,
        extra2: Int
    ) {
        require(payload.size <= Rvsp.MAX_RECORD_PAYLOAD) {
            "record payload too large: ${payload.size}"
        }
        val head = ByteBuffer.allocate(Rvsp.RECORD_HEADER_BYTES).order(Rvsp.LE)
            .put(magic)
            .putInt(payload.size)
            .putLong(timestampNs)
            .putLong(extra1)
            .putInt(extra2)
            .putInt(sequence.toInt())
        sequence++
        out.write(head.array())
        out.write(payload)
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        out.close()
    }

    companion object {
        private const val WRITE_BUFFER_BYTES = 1 shl 20
    }
}
