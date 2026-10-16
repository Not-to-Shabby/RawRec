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
    private val codec: FrameCodec = StoreCodec,
    private val computeCrc: Boolean = (header.flags and Rvsp.FLAG_HAS_RECORD_CRC) != 0
) : Closeable, Flushable {

    private val out = BufferedOutputStream(output, WRITE_BUFFER_BYTES)
    private var sequence = 0L
    private var currentFileOffset = 0L
    val indexEntries = ArrayList<RvspIndexEntry>()

    var framesWritten: Long = 0L
        private set

    var audioChunksWritten: Long = 0L
        private set

    init {
        val headBytes = header.toBytes()
        out.write(headBytes)
        currentFileOffset = headBytes.size.toLong()
    }

    @Synchronized
    fun writeFrame(payload: ByteArray, timestampNs: Long, exposureNs: Long = 0L, iso: Int = 0) {
        val encoded = if (codec.compressed) codec.encode(payload) else payload
        writeFrameRaw(encoded, timestampNs, exposureNs, iso)
    }

    @Synchronized
    fun writeFrameRaw(payload: ByteArray, timestampNs: Long, exposureNs: Long = 0L, iso: Int = 0) {
        val crc = if (computeCrc) {
            val c = java.util.zip.CRC32()
            c.update(payload)
            c.value
        } else 0L
        writeRecord(Rvsp.FRAME_MAGIC, payload, timestampNs, exposureNs, iso, crc, streamType = 0)
        framesWritten++
    }

    @Synchronized
    fun writeAudioChunk(
        payload: ByteArray,
        timestampNs: Long,
        sampleRate: Int = 48000,
        trackId: Int = 0,
        channelCount: Int = 2,
        audioFormat: Int = Rvsp.AUDIO_FMT_PCM16
    ) {
        val crc = if (computeCrc) {
            val c = java.util.zip.CRC32()
            c.update(payload)
            c.value
        } else 0L
        val extra2 = (trackId and 0xFF) or ((channelCount and 0xFF) shl 8) or ((audioFormat and 0xFF) shl 16)
        writeRecord(Rvsp.AUDIO_MAGIC, payload, timestampNs, sampleRate.toLong(), extra2, crc, streamType = 1)
        audioChunksWritten++
    }

    private fun writeRecord(
        magic: ByteArray,
        payload: ByteArray,
        timestampNs: Long,
        extra1: Long,
        extra2: Int,
        crc32: Long = 0L,
        streamType: Byte = 0
    ) {
        require(payload.size <= Rvsp.MAX_RECORD_PAYLOAD) {
            "record payload too large: ${payload.size}"
        }
        val recordOffset = currentFileOffset
        if (magic.contentEquals(Rvsp.FRAME_MAGIC)) {
            indexEntries.add(
                RvspIndexEntry(
                    fileOffset = recordOffset,
                    payloadBytes = payload.size,
                    timestampNs = timestampNs,
                    exposureNs = extra1,
                    iso = extra2,
                    streamType = 0
                )
            )
        } else if (magic.contentEquals(Rvsp.AUDIO_MAGIC)) {
            indexEntries.add(
                RvspIndexEntry(
                    fileOffset = recordOffset,
                    payloadBytes = payload.size,
                    timestampNs = timestampNs,
                    exposureNs = 0L,
                    iso = 0,
                    streamType = 1
                )
            )
        }
        val head = ByteBuffer.allocate(Rvsp.RECORD_HEADER_BYTES).order(Rvsp.LE)
            .put(magic)
            .putInt(payload.size)
            .putLong(timestampNs)
        if (computeCrc) {
            head.putInt(extra1.toInt())
            head.putInt(crc32.toInt())
            head.putInt(extra2)
        } else {
            head.putLong(extra1)
            head.putInt(extra2)
        }
        head.putInt(sequence.toInt())
        sequence++
        out.write(head.array())
        out.write(payload)
        currentFileOffset += Rvsp.RECORD_HEADER_BYTES + payload.size
    }

    /**
     * Appends an IDXT Index Trailer after the last record and flushes to stream.
     * Returns the absolute byte offset where the index trailer starts.
     */
    @Synchronized
    fun writeIndexTrailer(): Long {
        if (indexEntries.isEmpty()) return 0L
        val trailerOffset = currentFileOffset
        val trailerBytes = RvspIndex.buildTrailer(indexEntries)
        out.write(trailerBytes)
        currentFileOffset += trailerBytes.size
        out.flush()
        return trailerOffset
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
