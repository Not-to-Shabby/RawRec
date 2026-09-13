package dev.rawrec.app.container

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Rvsp {
    const val HEADER_SIZE = 512
    val FILE_MAGIC = byteArrayOf(0x52, 0x56, 0x53, 0x50)
    val FRAME_MAGIC = byteArrayOf(0x46, 0x52, 0x4D, 0x00)
    val AUDIO_MAGIC = byteArrayOf(0x41, 0x55, 0x44, 0x00)

    const val VERSION_MAJOR = 0
    const val VERSION_MINOR = 1

    const val FLAG_HAS_INDEX = 1

    const val RECORD_HEADER_BYTES = 32
    const val MAX_RECORD_PAYLOAD = 256 * 1024 * 1024
    const val MAX_META_JSON_BYTES = 64 * 1024

    const val CODEC_STORE = "STORE"
    const val CODEC_LZ4 = "LZ4"
    const val CODEC_ZSTD = "ZSTD"

    const val AUDIO_NONE = "NONE"
    const val AUDIO_PCM16 = "PCM16"
    const val AUDIO_PCM24 = "PCM24"

    const val PACKING_EXPANDED_LSB = 0
    const val PACKING_MIPI_PACKED = 1
    const val PACKING_EXPANDED_MSB = 2
    const val PACKING_MIPI_RAW12 = 3
    const val PACKING_MIPI_RAW14 = 4

    const val CFA_RGGB = 0
    const val CFA_GRBG = 1
    const val CFA_GBRG = 2
    const val CFA_BGGR = 3

    internal val LE: ByteOrder = ByteOrder.LITTLE_ENDIAN

    internal fun fixedString(value: String, maxBytes: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(maxBytes)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, maxBytes))
        return out
    }

    internal fun readFixedString(buf: ByteBuffer, length: Int): String {
        val raw = ByteArray(length)
        buf.get(raw)
        val end = raw.indexOf(0).let { if (it < 0) length else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }
}

data class RvspHeader(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val cfaPattern: Int,
    val packing: Int,
    val videoCodec: String = Rvsp.CODEC_STORE,
    val audioCodec: String = Rvsp.AUDIO_NONE,
    val whiteLevel: Int = 1023,
    val blackLevel: IntArray = intArrayOf(0, 0, 0, 0),
    val colorMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val asShotNeutral: FloatArray = floatArrayOf(1f, 1f, 1f),
    val nominalFpsMilli: Int = 30_000,
    val createdUnixUs: Long,
    val cameraModel: String = "",
    val lensId: String = "",
    val indexOffset: Long = 0L,
    val flags: Int = 0,
    val metaJson: String = ""
) {
    init {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(bitDepth in 8..16) { "unsupported bit depth $bitDepth" }
        require(cfaPattern in 0..3) { "invalid cfa pattern $cfaPattern" }
        require(packing in 0..2) { "invalid packing $packing" }
        require(blackLevel.size == 4) { "blackLevel must have 4 entries" }
        require(colorMatrix.size == 9) { "colorMatrix must be 3x3 row-major" }
        require(asShotNeutral.size == 3) { "asShotNeutral must have 3 entries" }
        require(cameraModel.toByteArray(Charsets.US_ASCII).size <= 63) { "cameraModel too long" }
        require(lensId.toByteArray(Charsets.US_ASCII).size <= 31) { "lensId too long" }
        require(metaJson.toByteArray(Charsets.UTF_8).size <= Rvsp.MAX_META_JSON_BYTES) {
            "metaJson too large"
        }
    }

    fun toBytes(): ByteArray {
        val jsonBytes = metaJson.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(Rvsp.HEADER_SIZE + jsonBytes.size).order(Rvsp.LE)
        buf.put(Rvsp.FILE_MAGIC)
        buf.putShort(Rvsp.VERSION_MAJOR.toShort())
        buf.putShort(Rvsp.VERSION_MINOR.toShort())
        buf.putShort(Rvsp.HEADER_SIZE.toShort())
        buf.putShort(flags.toShort())
        buf.put(Rvsp.fixedString(videoCodec, 16))
        buf.put(Rvsp.fixedString(audioCodec, 8))
        buf.putInt(width)
        buf.putInt(height)
        buf.put(bitDepth.toByte())
        buf.put(cfaPattern.toByte())
        buf.put(packing.toByte())
        buf.put(0)
        buf.putInt(whiteLevel)
        blackLevel.forEach { buf.putInt(it) }
        colorMatrix.forEach { buf.putFloat(it) }
        asShotNeutral.forEach { buf.putFloat(it) }
        buf.putInt(nominalFpsMilli)
        buf.putLong(createdUnixUs)
        buf.put(Rvsp.fixedString(cameraModel, 64))
        buf.put(Rvsp.fixedString(lensId, 32))
        buf.putLong(indexOffset)
        buf.putInt(jsonBytes.size)
        check(buf.position() <= Rvsp.HEADER_SIZE)
        buf.position(Rvsp.HEADER_SIZE)
        buf.put(jsonBytes)
        return buf.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): RvspHeader {
            require(bytes.size >= Rvsp.HEADER_SIZE) { "header truncated (${bytes.size} bytes)" }
            val buf = ByteBuffer.wrap(bytes).order(Rvsp.LE)
            val magic = ByteArray(4).also { buf.get(it) }
            require(magic.contentEquals(Rvsp.FILE_MAGIC)) { "bad magic, not an RVSP file" }
            val major = buf.short.toInt()
            require(major == Rvsp.VERSION_MAJOR) { "unsupported version $major.x" }
            buf.short
            buf.short
            buf.short
            val videoCodec = Rvsp.readFixedString(buf, 16)
            val audioCodec = Rvsp.readFixedString(buf, 8)
            val width = buf.int
            val height = buf.int
            val bitDepth = buf.get().toInt()
            val cfa = buf.get().toInt()
            val packing = buf.get().toInt()
            buf.get()
            val whiteLevel = buf.int
            val blackLevel = IntArray(4) { buf.int }
            val matrix = FloatArray(9) { buf.float }
            val neutral = FloatArray(3) { buf.float }
            val fpsMilli = buf.int
            val createdUs = buf.long
            val model = Rvsp.readFixedString(buf, 64)
            val lens = Rvsp.readFixedString(buf, 32)
            val indexOffset = buf.long
            val metaSize = buf.int
            require(metaSize >= 0 && metaSize <= Rvsp.MAX_META_JSON_BYTES) {
                "corrupt metaJsonSize $metaSize"
            }
            val json = if (metaSize > 0) {
                require(bytes.size >= Rvsp.HEADER_SIZE + metaSize) { "metaJson truncated" }
                String(bytes, Rvsp.HEADER_SIZE, metaSize, Charsets.UTF_8)
            } else ""
            return RvspHeader(
                width = width,
                height = height,
                bitDepth = bitDepth,
                cfaPattern = cfa,
                packing = packing,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                whiteLevel = whiteLevel,
                blackLevel = blackLevel,
                colorMatrix = matrix,
                asShotNeutral = neutral,
                nominalFpsMilli = fpsMilli,
                createdUnixUs = createdUs,
                cameraModel = model,
                lensId = lens,
                indexOffset = indexOffset,
                flags = 0,
                metaJson = json
            )
        }
    }
}

sealed class RvspRecord {
    abstract val timestampNs: Long
    abstract val sequence: Long
    abstract val payload: ByteArray

    data class Video(
        override val timestampNs: Long,
        override val sequence: Long,
        override val payload: ByteArray,
        val exposureNs: Long,
        val iso: Int
    ) : RvspRecord()

    data class Audio(
        override val timestampNs: Long,
        override val sequence: Long,
        override val payload: ByteArray
    ) : RvspRecord()
}
