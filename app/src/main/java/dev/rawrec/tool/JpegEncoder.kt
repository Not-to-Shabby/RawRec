package dev.rawrec.tool

import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin baseline JPEG (JFIF) encoder.
 *
 * Implements ISO/IEC 10918-1 baseline sequential DCT compression with standard
 * luminance and chrominance quantization tables, zigzag reordering, and standard
 * Huffman tables.
 *
 * Zero Android/AWT/ImageIO dependencies: 100% portable and unit-testable on any JVM.
 */
object JpegEncoder {

    // Standard Luminance Quantization Table (ITU-T T.81 / K.1)
    private val STD_LUMA_Q = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    // Standard Chrominance Quantization Table (ITU-T T.81 / K.2)
    private val STD_CHROMA_Q = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99
    )

    // Standard ZigZag Order
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    )

    // Standard Huffman Table Definitions (Luminance DC, Luminance AC, Chrominance DC, Chrominance AC)
    private val DC_LUMA_BITS = byteArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_LUMA_VALS = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val DC_CHROMA_BITS = byteArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val DC_CHROMA_VALS = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val AC_LUMA_BITS = byteArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d.toByte())
    private val AC_LUMA_VALS = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa
    ).map { it.toByte() }.toByteArray()

    private val AC_CHROMA_BITS = byteArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77.toByte())
    private val AC_CHROMA_VALS = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
        0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
        0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
        0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
        0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
        0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa
    ).map { it.toByte() }.toByteArray()

    // Precomputed Cosine Basis Matrix for 8x8 DCT
    private val C = Array(8) { u ->
        DoubleArray(8) { x ->
            val alpha = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            alpha * 0.5 * cos(((2.0 * x + 1.0) * u * Math.PI) / 16.0)
        }
    }

    /**
     * Compresses an ARGB image buffer to standard baseline JPEG format.
     */
    fun encode(argb: IntArray, width: Int, height: Int, quality: Int = 90): ByteArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - 2 * q

        val qLuma = IntArray(64) { i -> ((STD_LUMA_Q[i] * scale + 50) / 100).coerceIn(1, 255) }
        val qChroma = IntArray(64) { i -> ((STD_CHROMA_Q[i] * scale + 50) / 100).coerceIn(1, 255) }

        val out = ByteArrayOutputStream(width * height / 2 + 1024)

        fun writeMarker(m: Int) {
            out.write(0xFF)
            out.write(m)
        }

        // SOI
        writeMarker(0xD8)

        // APP0 (JFIF)
        writeMarker(0xE0)
        out.write(0x00); out.write(0x10) // length = 16
        out.write("JFIF\u0000".toByteArray(Charsets.US_ASCII))
        out.write(0x01); out.write(0x01) // version 1.1
        out.write(0x00) // units (none)
        out.write(0x00); out.write(0x01) // Xdensity = 1
        out.write(0x00); out.write(0x01) // Ydensity = 1
        out.write(0x00); out.write(0x00) // thumbnail 0x0

        // DQT
        writeMarker(0xDB)
        out.write(0x00); out.write(0x84) // 2 + 65*2 = 132
        out.write(0x00) // Table 0 (Luma)
        for (i in 0..63) out.write(qLuma[ZIGZAG[i]])
        out.write(0x01) // Table 1 (Chroma)
        for (i in 0..63) out.write(qChroma[ZIGZAG[i]])

        // SOF0 (Baseline DCT)
        writeMarker(0xC0)
        out.write(0x00); out.write(0x11) // length = 17
        out.write(0x08) // 8 bits per sample
        out.write((height shr 8) and 0xFF); out.write(height and 0xFF)
        out.write((width shr 8) and 0xFF); out.write(width and 0xFF)
        out.write(0x03) // 3 components (Y, Cb, Cr)

        // Component 1: Y (1x1 sampling)
        out.write(0x01); out.write(0x11); out.write(0x00)
        // Component 2: Cb (1x1 sampling)
        out.write(0x02); out.write(0x11); out.write(0x01)
        // Component 3: Cr (1x1 sampling)
        out.write(0x03); out.write(0x11); out.write(0x01)

        // DHT (Huffman Tables)
        fun writeDHT(tableClass: Int, tableId: Int, bits: ByteArray, values: ByteArray) {
            writeMarker(0xC4)
            val len = 2 + 1 + 16 + values.size
            out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
            out.write(((tableClass shl 4) or tableId) and 0xFF)
            out.write(bits)
            out.write(values)
        }

        writeDHT(0, 0, DC_LUMA_BITS, DC_LUMA_VALS)
        writeDHT(1, 0, AC_LUMA_BITS, AC_LUMA_VALS)
        writeDHT(0, 1, DC_CHROMA_BITS, DC_CHROMA_VALS)
        writeDHT(1, 1, AC_CHROMA_BITS, AC_CHROMA_VALS)

        // SOS (Start of Scan)
        writeMarker(0xDA)
        out.write(0x00); out.write(0x0C) // length = 12
        out.write(0x03) // 3 components
        out.write(0x01); out.write(0x00) // Y uses DC0, AC0
        out.write(0x02); out.write(0x11) // Cb uses DC1, AC1
        out.write(0x03); out.write(0x11) // Cr uses DC1, AC1
        out.write(0x00); out.write(0x3F); out.write(0x00) // spectral select 0..63

        // Build Huffman trees for fast bit writing
        val dcLumaTree = buildHuffmanTree(DC_LUMA_BITS, DC_LUMA_VALS)
        val acLumaTree = buildHuffmanTree(AC_LUMA_BITS, AC_LUMA_VALS)
        val dcChromaTree = buildHuffmanTree(DC_CHROMA_BITS, DC_CHROMA_VALS)
        val acChromaTree = buildHuffmanTree(AC_CHROMA_BITS, AC_CHROMA_VALS)

        val bitWriter = BitWriter(out)
        var lastDcY = 0
        var lastDcCb = 0
        var lastDcCr = 0

        val blockY = DoubleArray(64)
        val blockCb = DoubleArray(64)
        val blockCr = DoubleArray(64)

        val dctCoeffs = IntArray(64)

        val mcuW = (width + 7) / 8
        val mcuH = (height + 7) / 8

        for (by in 0 until mcuH) {
            for (bx in 0 until mcuW) {
                // Extract 8x8 block with RGB -> YCbCr conversion
                for (dy in 0..7) {
                    val yPos = (by * 8 + dy).coerceAtMost(height - 1)
                    for (dx in 0..7) {
                        val xPos = (bx * 8 + dx).coerceAtMost(width - 1)
                        val p = argb[yPos * width + xPos]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF

                        // ITU-R BT.601 color transform with level shift -128
                        val yVal = 0.299 * r + 0.587 * g + 0.114 * b - 128.0
                        val cbVal = -0.168736 * r - 0.331264 * g + 0.5 * b
                        val crVal = 0.5 * r - 0.418688 * g - 0.081312 * b

                        val idx = dy * 8 + dx
                        blockY[idx] = yVal
                        blockCb[idx] = cbVal
                        blockCr[idx] = crVal
                    }
                }

                // Compress Y Block
                forwardDCT(blockY, dctCoeffs, qLuma)
                lastDcY = encodeBlock(bitWriter, dctCoeffs, lastDcY, dcLumaTree, acLumaTree)

                // Compress Cb Block
                forwardDCT(blockCb, dctCoeffs, qChroma)
                lastDcCb = encodeBlock(bitWriter, dctCoeffs, lastDcCb, dcChromaTree, acChromaTree)

                // Compress Cr Block
                forwardDCT(blockCr, dctCoeffs, qChroma)
                lastDcCr = encodeBlock(bitWriter, dctCoeffs, lastDcCr, dcChromaTree, acChromaTree)
            }
        }

        bitWriter.flush()

        // EOI
        writeMarker(0xD9)

        return out.toByteArray()
    }

    private fun forwardDCT(input: DoubleArray, outQuant: IntArray, qTable: IntArray) {
        val temp = DoubleArray(64)

        // 1D DCT on rows
        for (i in 0..7) {
            val row = i * 8
            for (u in 0..7) {
                var sum = 0.0
                for (x in 0..7) {
                    sum += input[row + x] * C[u][x]
                }
                temp[row + u] = sum
            }
        }

        // 1D DCT on cols + Quantization
        for (v in 0..7) {
            for (u in 0..7) {
                var sum = 0.0
                for (y in 0..7) {
                    sum += temp[y * 8 + u] * C[v][y]
                }
                val qVal = qTable[v * 8 + u]
                outQuant[v * 8 + u] = (sum / qVal).roundToInt()
            }
        }
    }

    private fun encodeBlock(
        bw: BitWriter,
        coeffs: IntArray,
        lastDC: Int,
        dcTree: Map<Int, Pair<Int, Int>>,
        acTree: Map<Int, Pair<Int, Int>>
    ): Int {
        // DC Encoding
        val dc = coeffs[0]
        val diff = dc - lastDC
        val (dcLen, dcBits) = getCategoryAndBits(diff)
        val dcCode = dcTree[dcLen] ?: Pair(0, 0)
        bw.writeBits(dcCode.first, dcCode.second)
        if (dcLen > 0) bw.writeBits(dcBits, dcLen)

        // AC Encoding (Zigzag reordered)
        var zeroCount = 0
        for (k in 1..63) {
            val ac = coeffs[ZIGZAG[k]]
            if (ac == 0) {
                zeroCount++
            } else {
                while (zeroCount >= 16) {
                    // ZRL (16 zeros)
                    val zrl = acTree[0xF0] ?: Pair(0, 0)
                    bw.writeBits(zrl.first, zrl.second)
                    zeroCount -= 16
                }
                val (acLen, acBits) = getCategoryAndBits(ac)
                val symbol = (zeroCount shl 4) or acLen
                val code = acTree[symbol] ?: Pair(0, 0)
                bw.writeBits(code.first, code.second)
                bw.writeBits(acBits, acLen)
                zeroCount = 0
            }
        }
        // EOB
        if (zeroCount > 0) {
            val eob = acTree[0x00] ?: Pair(0, 0)
            bw.writeBits(eob.first, eob.second)
        }
        return dc
    }

    private fun getCategoryAndBits(v: Int): Pair<Int, Int> {
        if (v == 0) return Pair(0, 0)
        val absV = if (v < 0) -v else v
        var cat = 0
        var temp = absV
        while (temp > 0) {
            cat++
            temp = temp shr 1
        }
        val bits = if (v < 0) v + (1 shl cat) - 1 else v
        return Pair(cat, bits)
    }

    private fun buildHuffmanTree(bits: ByteArray, values: ByteArray): Map<Int, Pair<Int, Int>> {
        val out = HashMap<Int, Pair<Int, Int>>()
        var code = 0
        var valIdx = 0
        for (len in 1..16) {
            val count = bits[len - 1].toInt() and 0xFF
            for (i in 0 until count) {
                val symbol = values[valIdx++].toInt() and 0xFF
                out[symbol] = Pair(code, len)
                code++
            }
            code = code shl 1
        }
        return out
    }

    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var buffer = 0
        private var bitsLeft = 8

        fun writeBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                val bit = (value shr i) and 1
                buffer = (buffer shl 1) or bit
                bitsLeft--
                if (bitsLeft == 0) {
                    out.write(buffer)
                    if (buffer == 0xFF) out.write(0x00) // Byte stuffing
                    buffer = 0
                    bitsLeft = 8
                }
            }
        }

        fun flush() {
            if (bitsLeft < 8) {
                buffer = buffer shl bitsLeft
                out.write(buffer)
                if (buffer == 0xFF) out.write(0x00)
                buffer = 0
                bitsLeft = 8
            }
        }
    }
}
