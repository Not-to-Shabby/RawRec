package dev.rawrec.app.container

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class RvspReader(input: InputStream) {

    val header: RvspHeader
    var truncated: Boolean = false
        private set
    var corruptTail: Boolean = false
        private set
    var recordsRead: Long = 0L
        private set

    private val input = BufferedInputStream(input, READ_BUFFER_BYTES)
    private val scratch = ByteArray(Rvsp.RECORD_HEADER_BYTES - 4)

    init {
        val fixed = ByteArray(Rvsp.HEADER_SIZE)
        if (!readFully(fixed, Rvsp.HEADER_SIZE)) {
            throw IOException("file shorter than RVSP header")
        }
        val metaSize = ByteBuffer.wrap(fixed).order(Rvsp.LE).getInt(META_JSON_SIZE_OFFSET)
        require(metaSize >= 0 && metaSize <= Rvsp.MAX_META_JSON_BYTES) {
            "corrupt metaJsonSize $metaSize"
        }
        val complete = if (metaSize > 0) {
            val jsonBytes = ByteArray(metaSize)
            if (!readFully(jsonBytes, metaSize)) {
                throw IOException("metaJson truncated")
            }
            fixed + jsonBytes
        } else {
            fixed
        }
        header = RvspHeader.fromBytes(complete)
    }

    fun readAll(): List<RvspRecord> {
        val records = mutableListOf<RvspRecord>()
        while (true) {
            val record = nextRecord() ?: break
            records += record
        }
        return records
    }

    fun frames(): List<RvspRecord.Video> = readAll().filterIsInstance<RvspRecord.Video>()

    fun audioChunks(): List<RvspRecord.Audio> = readAll().filterIsInstance<RvspRecord.Audio>()

    fun nextRecord(): RvspRecord? {
        val magic = ByteArray(4)
        if (!readFully(magic, 4)) return null

        if (magic.contentEquals(Rvsp.FRAME_MAGIC) || magic.contentEquals(Rvsp.AUDIO_MAGIC)) {
            val meta = readRecordMeta() ?: return null
            val payload = readPayload(meta.payloadBytes) ?: return null
            recordsRead++
            return when {
                magic.contentEquals(RsvpFrameTag) -> RvspRecord.Video(
                    timestampNs = meta.timestampNs,
                    sequence = meta.sequence.toLong() and 0xFFFFFFFFL,
                    payload = payload,
                    exposureNs = meta.extraLong,
                    iso = meta.extraInt
                )
                else -> RvspRecord.Audio(
                    timestampNs = meta.timestampNs,
                    sequence = meta.sequence.toLong() and 0xFFFFFFFFL,
                    payload = payload
                )
            }
        }
        corruptTail = true
        return null
    }

    private class RecordMeta(
        val payloadBytes: Int,
        val timestampNs: Long,
        val extraLong: Long,
        val extraInt: Int,
        val sequence: Int
    )

    private fun readRecordMeta(): RecordMeta? {
        if (!readFully(scratch, scratch.size)) {
            truncated = true
            return null
        }
        val buf = ByteBuffer.wrap(scratch).order(Rvsp.LE)
        val payloadBytes = buf.int
        if (payloadBytes < 0 || payloadBytes > Rvsp.MAX_RECORD_PAYLOAD) {
            corruptTail = true
            return null
        }
        return RecordMeta(
            payloadBytes = payloadBytes,
            timestampNs = buf.long,
            extraLong = buf.long,
            extraInt = buf.int,
            sequence = buf.int
        )
    }

    private fun readPayload(size: Int): ByteArray? {
        val payload = ByteArray(size)
        if (!readFully(payload, size)) {
            truncated = true
            return null
        }
        return payload
    }

    private fun readFully(target: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val n = input.read(target, offset, length - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    companion object {
        private const val META_JSON_SIZE_OFFSET = 232
        private val RsvpFrameTag = byteArrayOf(0x46, 0x52, 0x4D, 0x00)
        private const val READ_BUFFER_BYTES = 1 shl 20
    }
}
