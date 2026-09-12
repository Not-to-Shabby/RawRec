package dev.rawrec.app.codec

object RawSampleReader {

    fun readU16LE(
        payload: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): ShortArray {
        require(pixelStride >= 2) { "pixelStride $pixelStride unsupported" }
        val out = ShortArray(width * height)
        var o = 0
        for (y in 0 until height) {
            var rowStart = y * rowStride
            for (x in 0 until width) {
                val p = rowStart
                out[o++] = ((payload[p].toInt() and 0xFF) or
                    ((payload[p + 1].toInt() and 0xFF) shl 8)).toShort()
                rowStart += pixelStride
            }
        }
        return out
    }

    fun isContiguous(rowStride: Int, pixelStride: Int, width: Int): Boolean =
        rowStride == width * pixelStride && pixelStride == 2
}
