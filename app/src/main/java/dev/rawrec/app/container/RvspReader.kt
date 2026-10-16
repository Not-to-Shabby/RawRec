package dev.rawrec.app.container

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class RvspReader(input: InputStream, private val verifyCrc: Boolean = false) {

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
            if (verifyCrc && meta.crc32 != 0L) {
                val c = java.util.zip.CRC32()
                c.update(payload)
                if (c.value != meta.crc32) {
                    corruptTail = true
                    return null
                }
            }
            recordsRead++
            val hasCrc = (header.flags and Rvsp.FLAG_HAS_RECORD_CRC) != 0
            return when {
                magic.contentEquals(RsvpFrameTag) -> RvspRecord.Video(
                    timestampNs = meta.timestampNs,
                    sequence = meta.sequence.toLong() and 0xFFFFFFFFL,
                    payload = payload,
                    exposureNs = meta.extraLong,
                    iso = if (hasCrc) (meta.extraInt and 0xFFFF) else meta.extraInt,
                    crc32 = meta.crc32
                )
                else -> {
                    val sr = if (hasCrc) meta.extraLong.toInt() else 48000
                    val tId = if (hasCrc) (meta.extraInt and 0xFF) else 0
                    val ch = if (hasCrc) ((meta.extraInt ushr 8) and 0xFF) else 2
                    val fmt = if (hasCrc) ((meta.extraInt ushr 16) and 0xFF) else Rvsp.AUDIO_FMT_PCM16
                    RvspRecord.Audio(
                        timestampNs = meta.timestampNs,
                        sequence = meta.sequence.toLong() and 0xFFFFFFFFL,
                        payload = payload,
                        sampleRate = sr,
                        trackId = tId,
                        channelCount = ch,
                        audioFormat = fmt,
                        crc32 = meta.crc32
                    )
                }
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
        val crc32: Long,
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
        val timestampNs = buf.long
        val hasCrc = (header.flags and Rvsp.FLAG_HAS_RECORD_CRC) != 0
        val extraLong: Long
        val extraInt: Int
        val crc32: Long
        if (hasCrc) {
            extraLong = buf.int.toLong() and 0xFFFFFFFFL
            crc32 = buf.int.toLong() and 0xFFFFFFFFL
            extraInt = buf.int
        } else {
            extraLong = buf.long
            extraInt = buf.int
            crc32 = 0L
        }
        val sequence = buf.int
        return RecordMeta(
            payloadBytes = payloadBytes,
            timestampNs = timestampNs,
            extraLong = extraLong,
            extraInt = extraInt,
            crc32 = crc32,
            sequence = sequence
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
