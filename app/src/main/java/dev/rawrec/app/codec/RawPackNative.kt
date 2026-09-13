package dev.rawrec.app.codec

import java.nio.ByteBuffer

object RawPackNative {
    val loaded: Boolean = runCatching { System.loadLibrary("rawrec") }.isSuccess

    external fun packMipi10(
        src: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray

    external fun packMipi10Direct(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray?

    external fun packMipi10DirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Boolean

    /**
     * Pack the (left, top, width, height) sub-rect of a full-sensor buffer —
     * the WYSIWYG framing crop. Origin must be a multiple of 4 (Bayer parity).
     */
    external fun packMipi10CroppedDirect(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int
    ): ByteArray?

    external fun packMipi10CroppedDirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int
    ): Boolean

    external fun packMipi12DirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Boolean

    external fun packMipi12CroppedDirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int
    ): Boolean

    external fun packMipi14DirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Boolean

    external fun packMipi14CroppedDirectInto(
        src: ByteBuffer,
        dst: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int
    ): Boolean

    /** Cropped + 1/4 YUV downscale of the same band (proxy variant). */
    external fun packMipi10ProxyCroppedDirect(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        quadCodes: IntArray,
        whiteLevel: Int,
        factor: Int,
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer
    ): ByteArray?

    external fun expandCopyDirect(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray?

    external fun packMipi10ProxyDirect(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        quadCodes: IntArray,
        whiteLevel: Int,
        factor: Int,
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer
    ): ByteArray?
}
