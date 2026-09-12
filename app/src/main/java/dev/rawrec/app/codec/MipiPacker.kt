package dev.rawrec.app.codec

object MipiPacker {

    const val BITS = 10
    private const val MASK = (1 shl BITS) - 1
    private const val GROUP = 4

    fun packedSize(sampleCount: Int): Int = (sampleCount + GROUP - 1) / GROUP * 5

    /**
     * Largest centered crop of [aspect] (w/h) inside width x height, for
     * WYSIWYG framing: this rect in sensor space is exactly the band the
     * letterboxed viewfinder shows.
     *
     * In portrait ([isLandscape] = false): wide aspects keep full width and cut
     * a centered height band along the sensor's long axis.
     * In landscape ([isLandscape] = true): sensor height is the device's
     * horizontal axis and sensor width is the device's vertical axis, so
     * widescreen aspects keep full sensor height and cut a centered width band.
     *
     * Constraints baked in:
     *  - dimensions rounded down to multiples of 4 (MIPI groups + proxy /4
     *    YUV + Bayer parity: the origin must also stay a multiple of 4 so
     *    CFA quadrant alignment is preserved after cropping);
     *  - recentered after rounding;
     *  - null when [aspect] is null or matches source aspect (Full /
     *    Grid record the full sensor untouched).
     */
    fun cropRect(
        aspect: Float?,
        width: Int,
        height: Int,
        isLandscape: Boolean = false
    ): IntArray? {
        if (aspect == null || aspect <= 0f || width <= 0 || height <= 0) return null

        val sourceAspect = width.toFloat() / height.toFloat()
        if (kotlin.math.abs(aspect - sourceAspect) < 0.01f) return null

        return if (aspect > sourceAspect) {
            // Wider than source: full width, centered height band.
            var h = (width / aspect).toInt()
            h -= h % 4
            if (h <= 0 || h >= height) return null
            var top = (height - h) / 2
            top -= top % 4
            if (top + h > height) top = height - h
            intArrayOf(0, top, width, h)
        } else {
            // Narrower than source (e.g. 1:1 square): full height, centered width band.
            var w = (height * aspect).toInt()
            w -= w % 4
            if (w <= 0 || w >= width) return null
            var left = (width - w) / 2
            left -= left % 4
            if (left + w > width) left = width - w
            intArrayOf(left, 0, w, height)
        }
    }

    fun pack(samples: ShortArray, sampleCount: Int = samples.size): ByteArray {
        require(sampleCount >= 0 && sampleCount <= samples.size)
        val out = ByteArray(packedSize(sampleCount))
        var si = 0
        var di = 0
        while (si < sampleCount) {
            val p0 = samples[si].toInt() and MASK
            val p1 = if (si + 1 < sampleCount) samples[si + 1].toInt() and MASK else 0
            val p2 = if (si + 2 < sampleCount) samples[si + 2].toInt() and MASK else 0
            val p3 = if (si + 3 < sampleCount) samples[si + 3].toInt() and MASK else 0
            out[di] = ((p0 shr 2) and 0xFF).toByte()
            out[di + 1] = ((p1 shr 2) and 0xFF).toByte()
            out[di + 2] = ((p2 shr 2) and 0xFF).toByte()
            out[di + 3] = ((p3 shr 2) and 0xFF).toByte()
            out[di + 4] = (((p0 and 0x3) shl 6) or ((p1 and 0x3) shl 4) or
                ((p2 and 0x3) shl 2) or (p3 and 0x3)).toByte()
            si += GROUP
            di += 5
        }
        return out
    }

    fun unpack(packed: ByteArray, sampleCount: Int): ShortArray {
        require(sampleCount >= 0)
        require(packed.size >= packedSize(sampleCount)) { "packed buffer too small" }
        val out = ShortArray(sampleCount)
        var si = 0
        var di = 0
        while (si < sampleCount) {
            val b0 = packed[di].toInt() and 0xFF
            val b1 = packed[di + 1].toInt() and 0xFF
            val b2 = packed[di + 2].toInt() and 0xFF
            val b3 = packed[di + 3].toInt() and 0xFF
            val b4 = packed[di + 4].toInt() and 0xFF
            out[si] = ((b0 shl 2) or ((b4 shr 6) and 0x3)).toShort()
            if (si + 1 < sampleCount) out[si + 1] = ((b1 shl 2) or ((b4 shr 4) and 0x3)).toShort()
            if (si + 2 < sampleCount) out[si + 2] = ((b2 shl 2) or ((b4 shr 2) and 0x3)).toShort()
            if (si + 3 < sampleCount) out[si + 3] = ((b3 shl 2) or (b4 and 0x3)).toShort()
            si += GROUP
            di += 5
        }
        return out
    }

    /**
     * Pack a centered sub-rect of a full-sensor sample array — JVM fallback
     * counterpart of the native cropped packers. [crop] is (left, top, width,
     * height) from [cropRect] in full-sensor coordinates.
     */
    fun packCropped(
        samples: ShortArray,
        fullWidth: Int,
        crop: IntArray
    ): ByteArray {
        val left = crop[0]; val top = crop[1]; val w = crop[2]; val h = crop[3]
        val out = ByteArray(packedSize(w * h))
        var di = 0
        for (y in 0 until h) {
            val row = (top + y) * fullWidth + left
            for (g in 0 until w step GROUP) {
                fun p(k: Int): Int =
                    if (g + k < w) samples[row + g + k].toInt() and MASK else 0
                val p0 = p(0); val p1 = p(1); val p2 = p(2); val p3 = p(3)
                out[di] = ((p0 shr 2) and 0xFF).toByte()
                out[di + 1] = ((p1 shr 2) and 0xFF).toByte()
                out[di + 2] = ((p2 shr 2) and 0xFF).toByte()
                out[di + 3] = ((p3 shr 2) and 0xFF).toByte()
                out[di + 4] = (((p0 and 0x3) shl 6) or ((p1 and 0x3) shl 4) or
                    ((p2 and 0x3) shl 2) or (p3 and 0x3)).toByte()
                di += 5
            }
        }
        return out
    }
}
