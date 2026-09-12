package dev.rawrec.app.codec

object ZstdNative {
    init {
        System.loadLibrary("rawrec")
    }

    external fun version(): Int
    external fun compress(src: ByteArray, level: Int, nbWorkers: Int): ByteArray?
    external fun decompress(src: ByteArray, maxOut: Long): ByteArray?

    external fun createCCtx(level: Int, nbWorkers: Int): Long
    external fun createCCtxEx(level: Int, nbWorkers: Int, windowLog: Int): Long
    external fun compressCtx(handle: Long, src: ByteArray): ByteArray?
    external fun freeCCtx(handle: Long)

    external fun decompressInto(src: ByteArray, dst: ByteArray): Int

    const val DEFAULT_LEVEL = 1
    const val DEFAULT_NB_WORKERS = 3
}
