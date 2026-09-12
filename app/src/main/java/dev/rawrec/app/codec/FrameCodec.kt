package dev.rawrec.app.codec

interface FrameEncoder : AutoCloseable {
    fun encode(src: ByteArray): ByteArray
}

interface FrameDecoder : AutoCloseable {
    fun decodeInto(src: ByteArray, dst: ByteArray): Int
}

interface FrameCodec {
    val id: String
    val compressed: Boolean
    fun encode(src: ByteArray): ByteArray
    fun decode(src: ByteArray, expectedBytes: Long): ByteArray

    fun openEncoder(): FrameEncoder = object : FrameEncoder {
        override fun encode(src: ByteArray) = this@FrameCodec.encode(src)
        override fun close() {}
    }

    fun openDecoder(): FrameDecoder = object : FrameDecoder {
        override fun decodeInto(src: ByteArray, dst: ByteArray): Int {
            val dec = this@FrameCodec.decode(src, dst.size.toLong())
            System.arraycopy(dec, 0, dst, 0, minOf(dec.size, dst.size))
            return dec.size
        }
        override fun close() {}
    }
}

object StoreCodec : FrameCodec {
    override val id = "STORE"
    override val compressed = false
    override fun encode(src: ByteArray) = src
    override fun decode(src: ByteArray, expectedBytes: Long) = src
}

class ZstdFrameCodec(
    private val level: Int = ZstdNative.DEFAULT_LEVEL,
    private val nbWorkers: Int = ZstdNative.DEFAULT_NB_WORKERS,
    private val windowLog: Int = 0
) : FrameCodec {
    override val id = "ZSTD"
    override val compressed = true

    override fun encode(src: ByteArray): ByteArray =
        ZstdNative.compress(src, level, nbWorkers)
            ?: throw IllegalStateException("zstd compress failed")

    override fun decode(src: ByteArray, expectedBytes: Long): ByteArray =
        ZstdNative.decompress(src, expectedBytes)
            ?: throw IllegalStateException("zstd decompress failed")

    override fun openEncoder(): FrameEncoder {
        val handle = if (windowLog > 0) {
            ZstdNative.createCCtxEx(level, nbWorkers, windowLog)
        } else {
            ZstdNative.createCCtx(level, nbWorkers)
        }
        if (handle == 0L) return object : FrameEncoder {
            override fun encode(src: ByteArray) =
                ZstdNative.compress(src, level, 0)
                    ?: throw IllegalStateException("zstd compress failed")
            override fun close() {}
        }
        return object : FrameEncoder {
            override fun encode(src: ByteArray) =
                ZstdNative.compressCtx(handle, src)
                    ?: throw IllegalStateException("zstd compress failed")
            override fun close() = ZstdNative.freeCCtx(handle)
        }
    }

    override fun openDecoder(): FrameDecoder {
        return object : FrameDecoder {
            override fun decodeInto(src: ByteArray, dst: ByteArray): Int {
                val rc = ZstdNative.decompressInto(src, dst)
                if (rc < 0) throw IllegalStateException("zstd decompressInto failed")
                return rc
            }
            override fun close() {}
        }
    }
}

object FrameCodecs {
    fun byId(id: String): FrameCodec = when (id) {
        StoreCodec.id -> StoreCodec
        "ZSTD" -> ZstdFrameCodec()
        else -> throw IllegalArgumentException("unknown codec $id")
    }
}
