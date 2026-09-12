package dev.rawrec.tool

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Rvtool {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("rvtool: info|stats|bmp|rgb|extract|mp4|wav|dngcheck|gui <file.rvsp> [...] [--gpu=opencl|vulkan|cpu]")
            return
        }

        // Configure GPU acceleration backend from command line flags
        val gpuArg = args.firstOrNull { it.startsWith("--gpu=") }?.substringAfter("--gpu=")?.lowercase()
        if (gpuArg != null) {
            dev.rawrec.tool.gpu.GpuManager.requestedBackendType = when (gpuArg) {
                "opencl", "cl" -> dev.rawrec.tool.gpu.BackendType.OPENCL
                "vulkan", "vk" -> dev.rawrec.tool.gpu.BackendType.VULKAN
                "cpu" -> dev.rawrec.tool.gpu.BackendType.CPU
                else -> dev.rawrec.tool.gpu.BackendType.OPENCL
            }
        }

        val file = args.getOrElse(1) { "" }
        when (args[0]) {
            "gui" -> runCatching {
                // GUI lives in the desktop-JVM source set (Swing is absent
                // from android.jar); dispatch by reflection so this file
                // stays android-clean.
                Class.forName("dev.rawrec.tool.RvtoolGui")
                    .getMethod("main", Array<String>::class.java)
                    .invoke(null, args.drop(1).toTypedArray() as Any)
            }.getOrElse { t ->
                println("gui unavailable: ${t.cause?.message ?: t.message}")
                println("build unit-test classes first: gradlew :app:testDebugUnitTest")
            }
            "gen" -> gen(file)
            "info" -> info(file)
            "stats" -> stats(file, args.getOrElse(2) { "3" }.toIntOrNull() ?: 3)
            "bmp" -> bmp(
                file,
                args.getOrElse(2) { "3" }.toIntOrNull() ?: 3,
                args.getOrElse(3) { "frame.bmp" }
            )
            "rgb" -> {
                val opt4 = args.getOrElse(4) { "default" }
                val opt5 = args.getOrElse(5) { "" }
                val (prof, lut) = if (opt4.endsWith(".cube", ignoreCase = true)) {
                    "custom_lut" to opt4
                } else if (opt5.endsWith(".cube", ignoreCase = true)) {
                    opt4 to opt5
                } else {
                    opt4 to null
                }
                rgb(
                    file,
                    args.getOrElse(2) { "3" }.toIntOrNull() ?: 3,
                    args.getOrElse(3) { "frame_rgb.bmp" },
                    prof,
                    lutPath = lut
                )
            }
            "extract" -> {
                val outDir = args.getOrElse(2) { "." }
                val start = args.getOrElse(3) { "0" }.toIntOrNull() ?: 0
                val count = args.getOrElse(4) { "-1" }.toIntOrNull() ?: -1
                val opt5 = args.getOrElse(5) { "default" }
                val opt6 = args.getOrElse(6) { "false" }
                val opt7 = args.getOrElse(7) { "" }
                val (prof, lut) = if (opt5.endsWith(".cube", ignoreCase = true)) {
                    "custom_lut" to opt5
                } else if (opt7.endsWith(".cube", ignoreCase = true)) {
                    opt5 to opt7
                } else {
                    opt5 to null
                }
                val bake = opt6.equals("true", ignoreCase = true) || opt6.equals("bake", ignoreCase = true)
                val compensate = !args.contains("--no-compensate")
                extract(
                    file,
                    outDir,
                    start,
                    count,
                    profileName = prof,
                    bakeTone = bake,
                    lutPath = lut,
                    compensateDrops = compensate
                )
            }
            "wav" -> wav(
                file,
                args.getOrElse(2) {
                    if (file.endsWith(".rvsp", ignoreCase = true)) {
                        file.substring(0, file.length - 5) + ".wav"
                    } else {
                        "audio.wav"
                    }
                }
            )
            "mp4" -> {
                val outMp4 = args.getOrElse(2) {
                    if (file.endsWith(".rvsp", ignoreCase = true)) {
                        file.substring(0, file.length - 5) + "_hlg.mp4"
                    } else {
                        "take_hlg.mp4"
                    }
                }
                val opt3 = args.getOrElse(3) { "cine_hlg" }
                val opt4 = args.getOrElse(4) { "" }
                val (prof, lut) = if (opt3.endsWith(".cube", ignoreCase = true)) {
                    "custom_lut" to opt3
                } else if (opt4.endsWith(".cube", ignoreCase = true)) {
                    opt3 to opt4
                } else {
                    opt3 to null
                }
                val lutObj = lut?.let { p ->
                    val f = File(p)
                    if (f.exists()) CubeLut.parse(f) else null
                }
                val profile = if (lutObj != null) ColorScience.ToneProfile.CUSTOM_LUT else ColorScience.ToneProfile.fromId(prof)
                println("exporting HLG MP4 video ($profile)...")
                val f = HlgMp4Exporter.export(
                    file,
                    outMp4,
                    profile = profile,
                    customLut = lutObj,
                    isHlg = true
                ) { cur, tot ->
                    if (cur % 30 == 0 || cur == tot) println("  $cur/$tot frames...")
                }
                println("exported HLG MP4 -> ${f.absolutePath} (${f.length() / 1024} KB)")
            }
            "batch" -> {
                val inDir = args.getOrElse(1) { "." }
                val outDir = args.getOrElse(2) { "./batch_export" }
                val format = args.getOrElse(3) { "dng" }.lowercase()
                val opt4 = args.getOrElse(4) { "default" }
                val opt5 = args.getOrElse(5) { "false" }
                val opt6 = args.getOrElse(6) { "" }
                val (prof, lut) = if (opt4.endsWith(".cube", ignoreCase = true)) {
                    "custom_lut" to opt4
                } else if (opt6.endsWith(".cube", ignoreCase = true)) {
                    opt4 to opt6
                } else {
                    opt4 to null
                }
                val bake = opt5.equals("true", ignoreCase = true) || opt5.equals("bake", ignoreCase = true)
                println("starting batch processing: in=$inDir out=$outDir format=$format...")
                val count = batchProcess(inDir, outDir, format, prof, bake, lut) { cur, tot, file ->
                    println("[$cur/$tot] processing $file...")
                }
                println("batch complete: processed $count takes.")
            }
            "dngcheck" -> dngCheck(args.getOrElse(1) { "" })
            else -> println("unknown command ${args[0]}")
        }
    }

    class Header(private val buf: ByteBuffer) {
        val width = buf.getInt(36)
        val height = buf.getInt(40)
        val bitDepth = buf.get(44).toInt()
        val cfa = buf.get(45).toInt()
        val packing = buf.get(46).toInt()
        val videoCodec = fixed(12, 16)
        val audioCodec = fixed(28, 8)
        val whiteLevel = buf.getInt(48)
        val fpsMilli = buf.getInt(116)
        val cameraModel = fixed(128, 64)

        /** Row-major 3x3 XYZ->sensor color matrix (header offset 68). */
        val colorMatrix: FloatArray = FloatArray(9) {
            buf.getFloat(68 + it * 4)
        }

        /** AsShotNeutral RGB (header offset 104). */
        val asShotNeutral: FloatArray = FloatArray(3) {
            buf.getFloat(104 + it * 4)
        }

        fun blackLevel(i: Int) = buf.getInt(52 + i * 4)

        val blackLevels get() = (0..3).map { blackLevel(it) }

        private fun fixed(off: Int, len: Int): String {
            buf.position(off)
            val b = ByteArray(len)
            buf.get(b)
            val end = b.indexOf(0).let { if (it < 0) len else it }
            return String(b, 0, end, Charsets.US_ASCII)
        }
    }

    /** Indexed frame record; tsNs/iso are the per-frame record-header fields. */
    class FrameRec(val offset: Long, val size: Int, val expNs: Long, val tsNs: Long, val iso: Int)
    class AudioRec(val offset: Long, val size: Int, val timestampNs: Long)

    fun openHeader(path: String): Pair<Header, RandomAccessFile> {
        val raf = RandomAccessFile(path, "r")
        try {
            val hb = ByteArray(512)
            raf.readFully(hb)
            require(String(hb, 0, 4, Charsets.US_ASCII) == "RVSP") { "$path is not RVSP" }
            val h = Header(ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN))
            return h to raf
        } catch (t: Throwable) {
            raf.close(); throw t
        }
    }

    fun metaSize(raf: RandomAccessFile): Int {
        raf.seek(232)
        val b = ByteArray(4); raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun metaJsonRaw(raf: RandomAccessFile): String {
        return runCatching {
            val size = metaSize(raf)
            if (size <= 0 || size > 256 * 1024) return ""
            raf.seek(512L)
            val raw = ByteArray(size).also { raf.readFully(it) }
            String(raw, Charsets.UTF_8).trim()
        }.getOrDefault("")
    }

    /** metaJson is a plain JSON object at offset 512; parse it without org.json. */
    fun metaJson(raf: RandomAccessFile): Map<String, String> {
        return runCatching {
            val size = metaSize(raf)
            if (size <= 0 || size > 256 * 1024) return emptyMap()
            raf.seek(512L)
            val raw = ByteArray(size).also { raf.readFully(it) }
            val json = String(raw, Charsets.UTF_8)
            val out = mutableMapOf<String, String>()
            out["_raw"] = json
            // Minimal flat-object scanner: "key":value pairs.
            val re = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(\"[^\"]*\"|[-0-9A-Za-z_.]+)")
            for (m in re.findAll(json)) {
                var v = m.groupValues[2]
                if (v.startsWith("\"")) v = v.removeSurrounding("\"")
                out[m.groupValues[1]] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun extractJsonDoubleArray(json: String, key: String): DoubleArray {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return DoubleArray(0)
        val start = json.indexOf('[', idx)
        if (start < 0) return DoubleArray(0)
        val end = json.indexOf(']', start)
        if (end < 0) return DoubleArray(0)
        val content = json.substring(start + 1, end).trim()
        if (content.isEmpty()) return DoubleArray(0)
        return content.split(',').mapNotNull { it.trim().toDoubleOrNull() }.toDoubleArray()
    }

    fun extractJsonFloatArray(json: String, key: String): FloatArray {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return FloatArray(0)
        val start = json.indexOf('[', idx)
        if (start < 0) return FloatArray(0)
        val end = json.indexOf(']', start)
        if (end < 0) return FloatArray(0)
        val content = json.substring(start + 1, end).trim()
        if (content.isEmpty()) return FloatArray(0)
        return content.split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }

    fun extractJsonObject(json: String, key: String): String {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return ""
        val start = json.indexOf('{', idx)
        if (start < 0) return ""
        var depth = 0
        for (i in start until json.length) {
            if (json[i] == '{') depth++
            else if (json[i] == '}') {
                depth--
                if (depth == 0) return json.substring(start, i + 1)
            }
        }
        return ""
    }

    fun extractJsonInt(json: String, key: String): Int {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return 0
        val colon = json.indexOf(':', idx)
        if (colon < 0) return 0
        val sb = StringBuilder()
        var i = colon + 1
        while (i < json.length && (json[i].isWhitespace() || json[i] == '"')) i++
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) {
            sb.append(json[i])
            i++
        }
        return sb.toString().toIntOrNull() ?: 0
    }

    fun scanRecords(raf: RandomAccessFile): Pair<List<FrameRec>, List<AudioRec>> {
        val frames = mutableListOf<FrameRec>()
        val audio = mutableListOf<AudioRec>()
        raf.seek(512L + metaSize(raf))
        val head = ByteArray(32)
        while (true) {
            val off = raf.filePointer
            if (raf.read(head, 0, 32) < 32) break
            val isFrame = head[0] == 0x46.toByte() && head[1] == 0x52.toByte() &&
                head[2] == 0x4D.toByte() && head[3] == 0x00.toByte()
            val isAudio = head[0] == 0x41.toByte() && head[1] == 0x55.toByte() &&
                head[2] == 0x44.toByte() && head[3] == 0x00.toByte()
            if (!isFrame && !isAudio) break
            val payload = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            if (payload < 0 || payload > 256 shl 20) break
            val tsNs = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(8)
            val expNs = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(16)
            val iso = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            if (isFrame) frames += FrameRec(off, payload, expNs, tsNs, iso)
            else if (isAudio) audio += AudioRec(off, payload, tsNs)
            raf.seek(off + 32L + payload)
        }
        // Multi-threaded compression workers in the capture pipeline write out-of-order
        // chunks as each finishes; sort monotonically by timestamp so all playback,
        // scrubbing, DNG extraction, and video exports progress in true chronological order.
        if (frames.size > 1) {
            frames.sortBy { it.tsNs }
        }
        if (audio.size > 1) {
            audio.sortBy { it.timestampNs }
        }
        return frames to audio
    }

    fun scanFrames(raf: RandomAccessFile): List<FrameRec> = scanRecords(raf).first
    fun scanAudio(raf: RandomAccessFile): List<AudioRec> = scanRecords(raf).second

    fun readPayload(raf: RandomAccessFile, off: Long, size: Int): ByteArray {
        raf.seek(off + 32)
        return ByteArray(size).also { raf.readFully(it) }
    }

    fun readPayload(raf: RandomAccessFile, f: FrameRec): ByteArray =
        readPayload(raf, f.offset, f.size)

    private fun u(b: Byte) = b.toInt() and 0xFF

    private object ZstdDesktop {
        val available: Boolean by lazy {
            runCatching { Class.forName("com.github.luben.zstd.Zstd") }.isSuccess
        }

        fun decompress(src: ByteArray, maxOut: Long): ByteArray {
            val cls = Class.forName("com.github.luben.zstd.Zstd")
            val m = cls.getMethod(
                "decompress",
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            return m.invoke(null, src, maxOut.toInt()) as ByteArray
        }
    }

    fun decodedPayload(h: Header, payload: ByteArray): ByteArray {
        if (h.videoCodec != "ZSTD") return payload
        val expectedRaw = if (h.packing == 1) (h.width * h.height / 4 * 5).toLong()
        else h.width.toLong() * h.height * 2
        if (ZstdDesktop.available) {
            return ZstdDesktop.decompress(payload, expectedRaw)
        }
        // Fallback for Android runtime where librawrec.so (ZstdNative) is loaded
        return runCatching {
            val codec = dev.rawrec.app.codec.FrameCodecs.byId(h.videoCodec)
            codec.decode(payload, expectedRaw)
        }.getOrElse {
            error("Zstandard decompression unavailable: neither zstd-jni nor native librawrec.so is loaded: ${it.message}")
        }
    }

    fun samplesOf(h: Header, payload: ByteArray): ShortArray {
        val n = h.width * h.height
        return when (h.packing) {
            1 -> {
                val s = ShortArray(n)
                var si = 0; var di = 0
                while (si < n && di + 5 <= payload.size) {
                    val b4 = u(payload[di + 4])
                    s[si] = ((u(payload[di]) shl 2) or ((b4 shr 6) and 3)).toShort()
                    if (si + 1 < n) s[si + 1] = ((u(payload[di + 1]) shl 2) or ((b4 shr 4) and 3)).toShort()
                    if (si + 2 < n) s[si + 2] = ((u(payload[di + 2]) shl 2) or ((b4 shr 2) and 3)).toShort()
                    if (si + 3 < n) s[si + 3] = ((u(payload[di + 3]) shl 2) or (b4 and 3)).toShort()
                    si += 4; di += 5
                }
                s
            }
            else -> {
                val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(n) { bb.short }
            }
        }
    }

    fun info(path: String) {
        val (h, raf) = openHeader(path)
        raf.use {
            val frames = scanFrames(raf)
            val meta = metaJson(raf)
            println("camera      : ${h.cameraModel}")
            println("geometry    : ${h.width}x${h.height} bitDepth=${h.bitDepth} cfa=${h.cfa}")
            println("packing     : ${h.packing} (0=expanded-lsb, 1=mipi10)")
            println("codec       : ${h.videoCodec} audio=${h.audioCodec}")
            println("levels      : white=${h.whiteLevel} black=${h.blackLevels}")
            println("nominalFps  : ${h.fpsMilli / 1000.0}")
            println("video frames: ${frames.size}")
            val dropped = meta["totalDropped"]?.toLongOrNull() ?: 0L
            val gaps = meta["droppedGaps"]?.toIntOrNull() ?: 0
            if (dropped > 0L || gaps > 0) {
                println("dropped     : $dropped frames ($gaps gap(s))")
            }
        }
    }

    fun stats(path: String, frameIdx: Int) {
        val (h, raf) = openHeader(path)
        raf.use {
            val frames = scanFrames(raf)
            if (frameIdx >= frames.size) { println("only ${frames.size} frames"); return }
            val samples = samplesOf(h, decodedPayload(h, readPayload(raf, frames[frameIdx])))
            val names = when (h.cfa) {
                0 -> listOf("R", "Gr", "Gb", "B"); 1 -> listOf("Gr", "R", "B", "Gb")
                2 -> listOf("Gb", "B", "R", "Gr"); else -> listOf("B", "Gb", "Gr", "R")
            }
            val sums = DoubleArray(4); val sq = DoubleArray(4); val cnt = LongArray(4)
            var mn = Int.MAX_VALUE; var mx = Int.MIN_VALUE
            for (y in 0 until h.height) for (x in 0 until h.width) {
                val v = samples[y * h.width + x].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
                val i = ((y and 1) shl 1) or (x and 1)
                sums[i] += v; sq[i] += v.toDouble() * v; cnt[i]++
            }
            println("frame $frameIdx overall [$mn .. $mx]")
            names.forEachIndexed { i, nm ->
                val mean = sums[i] / cnt[i]
                val std = kotlin.math.sqrt((sq[i] / cnt[i] - mean * mean).coerceAtLeast(0.0))
                println("  %s: mean %.1f std %.1f".format(nm, mean, std))
            }
            val black = (h.blackLevels.minOrNull() ?: 0).coerceAtLeast(0)
            val range = (h.whiteLevel - black).coerceAtLeast(1)
            val dyn = mx - mn
            println(
                "verdict: " + when {
                    dyn < range * 0.02 -> "BLANK/CLIPPED (dyn $dyn of $range)"
                    else -> "has dynamic range (%.1f%% of full scale)".format(dyn * 100.0 / range)
                }
            )
        }
    }

    fun bmp(path: String, frameIdx: Int, outPath: String) {
        val (h, raf) = openHeader(path)
        raf.use {
            val frames = scanFrames(raf)
            if (frameIdx >= frames.size) { println("only ${frames.size} frames"); return }
            val samples = samplesOf(h, decodedPayload(h, readPayload(raf, frames[frameIdx])))
            val w = h.width / 2; val hh = h.height / 2
            val black = (h.blackLevels.sum().toDouble() / h.blackLevels.size).toInt().coerceAtLeast(0)
            val span = (h.whiteLevel - black).coerceAtLeast(1)
            val px = IntArray(w * hh)
            for (y in 0 until hh) for (x in 0 until w) {
                var acc = 0L
                for (dy in 0..1) for (dx in 0..1) {
                    acc += samples[(y * 2 + dy) * h.width + (x * 2 + dx)].toInt() and 0xFFFF
                }
                val v = (((acc / 4.0) - black) / span).coerceIn(0.0, 1.0)
                px[y * w + x] = (Math.pow(v, 0.5) * 255.0).toInt().coerceIn(0, 255)
            }
            writeBmpGray(outPath, w, hh, px)
            println("wrote $outPath (${w}x$hh grayscale)")
        }
    }

    private fun writeBmpGray(path: String, w: Int, h: Int, gray: IntArray) {
        FileOutputStream(path).use { o ->
            val rowBytes = w * 3
            val pad = ByteArray((4 - rowBytes % 4) % 4)
            val dataSize = (rowBytes + pad.size) * h
            fun le(v: Int, n: Int): ByteArray = ByteArray(n) { i -> (v shr (8 * i)).toByte() }
            o.write(byteArrayOf(0x42, 0x4D))
            o.write(le(54 + dataSize, 4)); o.write(ByteArray(4)); o.write(le(54, 4))
            o.write(le(40, 4)); o.write(le(w, 4)); o.write(le(h, 4))
            o.write(le(1, 2)); o.write(le(24, 2)); o.write(le(0, 4)); o.write(le(dataSize, 4))
            o.write(le(2835, 4)); o.write(le(2835, 4)); o.write(le(0, 4)); o.write(le(0, 4))
            for (y in h - 1 downTo 0) {
                for (x in 0 until w) {
                    val c = gray[y * w + x].toByte()
                    o.write(byteArrayOf(c, c, c))
                }
                o.write(pad)
            }
        }
    }

    /**
     * Half-res quad-RGB demosaic: one output pixel per 2x2 Bayer quad,
     * per-channel black/white normalized with gamma 0.5. Pure — the GUI
     * preview and the rgb command share it.
     */
    /**
     * Half-res quad-RGB demosaic: one output pixel per 2x2 Bayer quad,
     * with optional white-balance calibration and color grading. Pure — the GUI
     * preview and the rgb command share it.
     */
    fun quadRgb(
        samples: ShortArray,
        width: Int,
        height: Int,
        cfa: Int,
        blackLevels: List<Int>,
        whiteLevel: Int,
        asShotNeutral: FloatArray? = null,
        applyCalibration: Boolean = true,
        profile: ColorScience.ToneProfile = ColorScience.ToneProfile.DEFAULT,
        enableVignette: Boolean = true,
        customLut: CubeLut? = null
    ): IntArray {
        val names = cfaNames(cfa)
        val w = width / 2; val hh = height / 2
        val blackOf = { ch: Int -> blackLevels[ch].coerceAtLeast(0) }
        val spanOf = { ch: Int -> (whiteLevel - blackOf(ch)).coerceAtLeast(1).toDouble() }
        val wbGains = if (applyCalibration) ColorScience.resolveWbGains(asShotNeutral) else doubleArrayOf(1.0, 1.0, 1.0)
        val px = IntArray(w * hh)
        for (y in 0 until hh) {
            val vNorm = y.toDouble() / hh
            for (x in 0 until w) {
                val uNorm = x.toDouble() / w
                val tl = y * 2 * width + x * 2
                val v = IntArray(4) { samples[tl + (it / 2) * width + (it % 2)].toInt() and 0xFFFF }
                var r = 0.0; var g = 0.0; var b = 0.0
                for (q in 0..3) {
                    val norm = ((v[q] - blackOf(q)) / spanOf(q)).coerceIn(0.0, 1.0)
                    when (names[q]) {
                        "R" -> r += norm; "B" -> b += norm
                        else -> g += norm
                    }
                }
                g /= 2.0
                px[y * w + x] = ColorScience.gradePixel(r, g, b, uNorm, vNorm, profile, enableVignette, wbGains, customLut)
            }
        }
        return px
    }

    /**
     * Half-res 8-bit luma (for the CinemaScopes pure functions): quad
     * average, black/white normalized, gamma 0.5 — the same look as the
     * grayscale preview.
     */
    fun luma8Of(
        samples: ShortArray,
        width: Int,
        height: Int,
        blackLevel: Int,
        whiteLevel: Int
    ): ByteArray {
        val w = width / 2; val hh = height / 2
        val lo = blackLevel.coerceAtLeast(0)
        val span = (whiteLevel - lo).coerceAtLeast(1)
        val out = ByteArray(w * hh)
        for (y in 0 until hh) for (x in 0 until w) {
            var acc = 0L
            for (dy in 0..1) for (dx in 0..1) {
                acc += samples[(y * 2 + dy) * width + (x * 2 + dx)].toInt() and 0xFFFF
            }
            val norm = ((acc / 4.0 - lo) / span).coerceIn(0.0, 1.0)
            out[y * w + x] = (Math.pow(norm, 0.5) * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    fun rgb(
        path: String,
        frameIdx: Int,
        outPath: String,
        profileName: String = "default",
        applyCalibration: Boolean = true,
        lutPath: String? = null
    ) {
        val (h, raf) = openHeader(path)
        raf.use {
            val frames = scanFrames(raf)
            if (frameIdx >= frames.size) { println("only ${frames.size} frames"); return }
            val payload = decodedPayload(h, readPayload(raf, frames[frameIdx]))
            val lut = lutPath?.let { p ->
                val f = File(p)
                if (f.exists()) CubeLut.parse(f) else null
            }
            val profile = if (lut != null) ColorScience.ToneProfile.CUSTOM_LUT else ColorScience.ToneProfile.fromId(profileName)
            val px = dev.rawrec.tool.gpu.GpuManager.processFrame(
                mipiPayload = payload,
                width = h.width,
                height = h.height,
                cfa = h.cfa,
                packing = h.packing,
                blackLevels = h.blackLevels,
                whiteLevel = h.whiteLevel,
                asShotNeutral = h.asShotNeutral,
                applyCalibration = applyCalibration,
                profile = profile,
                enableVignette = true,
                customLut = lut
            )
            writeBmpColor(outPath, h.width / 2, h.height / 2, px)
            val calibLabel = if (applyCalibration) "applied WB" else "sensor raw"
            val lutLabel = if (lut != null) " (LUT: ${lut.title})" else ""
            val backendLabel = dev.rawrec.tool.gpu.GpuManager.activeBackend.deviceName
            println("wrote $outPath (${h.width / 2}x${h.height / 2} true color, profile=${profile.displayName}$lutLabel, color=$calibLabel, backend=$backendLabel)")
        }
    }

    private fun cfaNames(pattern: Int): List<String> = when (pattern) {
        0 -> listOf("R", "Gr", "Gb", "B")
        1 -> listOf("Gr", "R", "B", "Gb")
        2 -> listOf("Gb", "B", "R", "Gr")
        else -> listOf("B", "Gb", "Gr", "R")
    }

    private fun writeBmpColor(path: String, w: Int, h: Int, argbLike: IntArray) {
        FileOutputStream(path).use { o ->
            val rowBytes = w * 3
            val pad = ByteArray((4 - rowBytes % 4) % 4)
            val dataSize = (rowBytes + pad.size) * h
            fun le(v: Int, n: Int): ByteArray = ByteArray(n) { i -> (v shr (8 * i)).toByte() }
            o.write(byteArrayOf(0x42, 0x4D))
            o.write(le(54 + dataSize, 4)); o.write(ByteArray(4)); o.write(le(54, 4))
            o.write(le(40, 4)); o.write(le(w, 4)); o.write(le(h, 4))
            o.write(le(1, 2)); o.write(le(24, 2)); o.write(le(0, 4)); o.write(le(dataSize, 4))
            o.write(le(2835, 4)); o.write(le(2835, 4)); o.write(le(0, 4)); o.write(le(0, 4))
            for (y in h - 1 downTo 0) {
                for (x in 0 until w) {
                    val p = argbLike[y * w + x]
                    o.write(byteArrayOf(
                        (p and 0xFF).toByte(),
                        ((p shr 8) and 0xFF).toByte(),
                        ((p shr 16) and 0xFF).toByte()
                    ))
                }
                o.write(pad)
            }
        }
    }

    private class TiffEntry(val tag: Int, val type: Int, val data: ByteArray) {
        val count: Int = when (type) {
            2 -> data.size
            3, 8 -> data.size / 2
            4, 9 -> data.size / 4
            5, 10 -> data.size / 8
            else -> data.size
        }
        val inline: Boolean get() = data.size <= 4
    }

    private fun tShort(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun tLong(v: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0..3) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun tLongs(v: IntArray) = ByteArray(v.size * 4) { i -> tLong(v[i / 4])[i % 4] }
    private fun tShorts(v: IntArray) = ByteArray(v.size * 2) { i -> tShort(v[i / 2])[i % 2] }
    private fun tRational(num: Long, den: Long) =
        tLong(num.toInt()) + tLong(den.toInt())

    private fun tRationals(nums: LongArray, den: Long) =
        nums.flatMap { tRational(it, den).toList() }.toByteArray()

    private fun tSRationalsFromFloats(f: FloatArray, scale: Long = 10_000L): ByteArray =
        f.flatMap { v ->
            // Round-trip through the scaled integer; signed rationals keep
            // the sign (the old form masked negatives into unsigned garbage).
            val n = Math.round(v * scale).toLong()
            tRational(n, scale).toList()
        }.toByteArray()

    private fun tDouble(v: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        val b = ByteArray(8)
        for (i in 0..7) b[i] = ((bits shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun tDoubles(v: DoubleArray): ByteArray =
        v.flatMap { tDouble(it).toList() }.toByteArray()

    private fun writeIfd(
        out: FileOutputStream,
        entries: List<TiffEntry>,
        nextIfdOffset: Int,
        baseOffset: Int
    ) {
        val sorted = entries.sortedBy { it.tag }
        out.write(tShort(sorted.size))
        var extCursor = baseOffset + 2 + 12 * sorted.size + 4
        for (e in sorted) {
            out.write(tShort(e.tag))
            out.write(tShort(e.type))
            out.write(tLong(e.count))
            if (e.inline) {
                val v = e.data + ByteArray(4 - e.data.size)
                out.write(v)
            } else {
                out.write(tLong(extCursor))
                extCursor += e.data.size + (e.data.size and 1)
            }
        }
        out.write(tLong(nextIfdOffset))
        for (e in sorted) {
            if (!e.inline) {
                out.write(e.data)
                if (e.data.size and 1 == 1) out.write(0)
            }
        }
    }

    private fun cfaQuadrantNames(pattern: Int): List<String> = when (pattern) {
        0 -> listOf("R", "Gr", "Gb", "B")
        1 -> listOf("Gr", "R", "B", "Gb")
        2 -> listOf("Gb", "B", "R", "Gr")
        else -> listOf("B", "Gb", "Gr", "R")
    }

    private fun cfaCodeQuad(pattern: Int): IntArray = when (pattern) {
        0 -> intArrayOf(0, 1, 1, 2)
        1 -> intArrayOf(1, 0, 2, 1)
        2 -> intArrayOf(1, 2, 0, 1)
        else -> intArrayOf(2, 1, 1, 0)
    }

    fun extract(
        path: String,
        outDir: String,
        startFrame: Int = 0,
        count: Int = -1,
        profileName: String = "default",
        bakeTone: Boolean = false,
        lutPath: String? = null,
        compensateDrops: Boolean = true
    ) {
        val (h, raf) = openHeader(path)
        raf.use {
            val meta = metaJson(raf)
            val (frames, audio) = scanRecords(raf)
            val last = if (count < 0) frames.size else minOf(startFrame + count, frames.size)
            if (startFrame >= frames.size) { println("only ${frames.size} frames"); return }
            val dir = File(outDir)
            dir.mkdirs()
            // Clean out old dng/wav files in outDir to prevent non-contiguous sequence gaps
            dir.listFiles { f -> f.name.endsWith(".dng", ignoreCase = true) || f.name.startsWith("audio", ignoreCase = true) }
                ?.forEach { it.delete() }

            val quadNames = cfaNames(h.cfa)
            val chanIdx = mapOf("R" to 0, "Gr" to 1, "Gb" to 2, "B" to 3)
            val blackQuad = quadNames.map { h.blackLevels.getOrElse(chanIdx[it] ?: 0) { 0 } }
            val model = h.cameraModel.ifBlank { "RawRec device" }
            val lut = lutPath?.let { p ->
                val f = File(p)
                if (f.exists()) CubeLut.parse(f) else null
            }
            val profile = if (lut != null) ColorScience.ToneProfile.CUSTOM_LUT else ColorScience.ToneProfile.fromId(profileName)
            val framePeriodNs = if (h.fpsMilli > 0) (1_000_000_000_000L / h.fpsMilli) else 33_333_333L
            var writtenCount = 0
            var compensatedCount = 0
            var prevPixels: ByteArray? = null
            var prevExpNs = 0L
            var lastTsNs = -1L

            for (idx in startFrame until last) {
                val rec = frames[idx]

                // If frames were dropped, duplicate the previous frame to keep A/V sync
                if (compensateDrops && lastTsNs > 0L && rec.tsNs > lastTsNs && prevPixels != null) {
                    val deltaNs = rec.tsNs - lastTsNs
                    if (deltaNs > 1.5 * framePeriodNs) {
                        val missing = (Math.round(deltaNs.toDouble() / framePeriodNs).toInt() - 1).coerceIn(1, 300)
                        for (gapStep in 0 until missing) {
                            val file = File(dir, "frame_%06d.dng".format(writtenCount))
                            writeDng(file, h, prevPixels, model, blackQuad, prevExpNs, meta, profile, bakeTone)
                            writtenCount++
                            compensatedCount++
                        }
                    }
                }

                val samples = samplesOf(h, decodedPayload(h, readPayload(raf, rec)))
                if (bakeTone && profile != ColorScience.ToneProfile.DEFAULT) {
                    val blackOf = { ch: Int -> blackQuad.getOrElse(ch) { 0 } }
                    for (i in samples.indices) {
                        val q = (i / h.width % 2) * 2 + (i % h.width % 2)
                        val black = blackOf(q)
                        samples[i] = ColorScience.gradeRawSample(samples[i].toInt() and 0xFFFF, black, h.whiteLevel, profile)
                    }
                }
                val pixels = ByteArray(h.width * h.height * 2)
                val pb = pixels.asByteBuffer()
                for (s in samples) { pb.putShort(s) }
                pb.rewind()
                val file = File(dir, "frame_%06d.dng".format(writtenCount))
                writeDng(file, h, pixels, model, blackQuad, rec.expNs, meta, profile, bakeTone)
                writtenCount++
                prevPixels = pixels
                prevExpNs = rec.expNs
                lastTsNs = rec.tsNs
                if (writtenCount % 30 == 0) println("  $writtenCount frames...")
            }
            val bakeInfo = if (bakeTone) " (baked tone)" else if (profile != ColorScience.ToneProfile.DEFAULT) " (DNG ProfileToneCurve: ${profile.displayName})" else ""
            val compInfo = if (compensatedCount > 0) " (compensated $compensatedCount dropped frame(s) for A/V sync)" else ""
            println("extracted $writtenCount DNG frames ($startFrame..${last - 1}) into $outDir$bakeInfo$compInfo")

            if (audio.isNotEmpty()) {
                val audioDir = File(dir, "audio").apply { mkdirs() }
                val audioFile = File(audioDir, "audio.wav")
                wav(path, audioFile.absolutePath)
            }
        }
    }

    fun batchProcess(
        inDir: String,
        outDir: String,
        format: String = "dng",
        profileName: String = "default",
        bakeTone: Boolean = false,
        lutPath: String? = null,
        onProgress: ((current: Int, total: Int, takeName: String) -> Unit)? = null
    ): Int {
        val srcDir = File(inDir)
        val files = if (srcDir.isDirectory) {
            srcDir.listFiles { f -> f.name.endsWith(".rvsp", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()
        } else if (srcDir.isFile && srcDir.name.endsWith(".rvsp", ignoreCase = true)) {
            listOf(srcDir)
        } else emptyList()

        if (files.isEmpty()) {
            println("no .rvsp takes found in $inDir")
            return 0
        }

        val dstDir = File(outDir).apply { mkdirs() }
        files.forEachIndexed { i, f ->
            onProgress?.invoke(i + 1, files.size, f.name)
            val baseName = f.nameWithoutExtension
            if (format.equals("mp4", ignoreCase = true)) {
                val outFile = File(dstDir, "${baseName}_hlg.mp4").absolutePath
                val lutObj = lutPath?.let { p -> File(p).takeIf { it.exists() }?.let { CubeLut.parse(it) } }
                val profile = if (lutObj != null) ColorScience.ToneProfile.CUSTOM_LUT else ColorScience.ToneProfile.fromId(profileName)
                HlgMp4Exporter.export(
                    f.absolutePath,
                    outFile,
                    profile = profile,
                    customLut = lutObj,
                    isHlg = true
                )
            } else {
                val takeOutDir = File(dstDir, baseName).absolutePath
                extract(
                    f.absolutePath,
                    takeOutDir,
                    startFrame = 0,
                    count = -1,
                    profileName = profileName,
                    bakeTone = bakeTone,
                    lutPath = lutPath
                )
            }
        }
        return files.size
    }

    fun wav(path: String, outPath: String) {
        val (h, raf) = openHeader(path)
        raf.use {
            val meta = metaJson(raf)
            val audio = scanAudio(raf)
            if (audio.isEmpty()) {
                println("no audio records found in $path")
                return
            }
            val tsSource = meta["timestampSource"]?.toIntOrNull()
            if (tsSource != null) {
                // 1 = REALTIME: both frame and audio records share the
                // elapsedRealtime base, so their raw timestamps are directly
                // comparable. 0/absent = UNKNOWN base; treat audio/frame
                // offsets as unaligned (legacy files).
                println(
                    "audio clock: timestampSource=$tsSource" +
                        if (tsSource == 1) " (REALTIME — same base as frames, no offset needed)"
                        else " (unknown base — legacy file, raw offsets unaligned)"
                )
            }
            val sampleRate = 48000
            val channels = 2
            val bitsPerSample = 16
            val totalBytes = audio.sumOf { it.size.toLong() }
            val outFile = File(outPath)
            outFile.parentFile?.mkdirs()

            FileOutputStream(outFile).use { out ->
                val totalDataLen = totalBytes + 36
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val blockAlign = channels * bitsPerSample / 8

                out.write("RIFF".toByteArray(Charsets.US_ASCII))
                out.write(tLong(totalDataLen.toInt()))
                out.write("WAVE".toByteArray(Charsets.US_ASCII))
                out.write("fmt ".toByteArray(Charsets.US_ASCII))
                out.write(tLong(16))
                out.write(tShort(1))
                out.write(tShort(channels))
                out.write(tLong(sampleRate))
                out.write(tLong(byteRate))
                out.write(tShort(blockAlign))
                out.write(tShort(bitsPerSample))
                out.write("data".toByteArray(Charsets.US_ASCII))
                out.write(tLong(totalBytes.toInt()))

                for (rec in audio) {
                    val pcm = readPayload(raf, rec.offset, rec.size)
                    out.write(pcm)
                }
            }
            val durationSec = totalBytes.toDouble() / (sampleRate * channels * 2)
            println("extracted ${audio.size} audio chunks ($totalBytes bytes, %.2f s) -> $outPath".format(durationSec))
        }
    }

    private fun ByteArray.asByteBuffer(): java.nio.ByteBuffer =
        java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    private fun writeDng(
        file: File,
        h: Header,
        pixels: ByteArray,
        model: String,
        blackQuad: List<Int>,
        exposure_ns: Long,
        meta: Map<String, String> = emptyMap(),
        profile: ColorScience.ToneProfile = ColorScience.ToneProfile.DEFAULT,
        bakeTone: Boolean = false
    ) {
        val w = h.width; val hh = h.height
        val stripLen = w.toLong() * hh.toLong() * 2L

        // Colorimetry comes from the RVSP header (populated from
        // CameraCharacteristics at record time); identity/unity values keep
        // older files extracting unchanged.
        val colorMatrix = h.colorMatrix
        val asShotNeutral = h.asShotNeutral
        val illuminant = meta["calibIlluminant1"]?.toIntOrNull() ?: 21 // D65

        val entries = mutableListOf(
            TiffEntry(254, 4, tLong(0)),
            TiffEntry(256, 4, tLong(w)),
            TiffEntry(257, 4, tLong(hh)),
            TiffEntry(258, 3, tShorts(intArrayOf(16))),
            TiffEntry(259, 3, tShort(1)),
            TiffEntry(262, 3, tShort(32803)),
            TiffEntry(271, 2, "RawRec".toByteArray(Charsets.US_ASCII) + 0),
            TiffEntry(272, 2, model.toByteArray(Charsets.US_ASCII) + 0),
            TiffEntry(273, 4, tLong(0)),
            TiffEntry(274, 3, tShort(1)),
            TiffEntry(277, 3, tShort(1)),
            TiffEntry(278, 4, tLong(hh)),
            TiffEntry(279, 4, tLong(stripLen.toInt())),
            TiffEntry(282, 5, tRational(72, 1)),
            TiffEntry(283, 5, tRational(72, 1)),
            TiffEntry(284, 3, tShort(1)),
            TiffEntry(296, 3, tShort(2)),
            TiffEntry(305, 2, "RawRec rvtool".toByteArray(Charsets.US_ASCII) + 0),
            TiffEntry(339, 3, tShorts(intArrayOf(1))),
            TiffEntry(33421, 3, tShorts(intArrayOf(2, 2))),
            TiffEntry(33422, 1, tBytes(cfaCodeQuad(h.cfa))),
            TiffEntry(33434, 5, tRational(Math.max(1L, exposure_ns), 1_000_000_000L)),
            TiffEntry(50706, 1, byteArrayOf(1, 4, 0, 0)),
            TiffEntry(50707, 1, byteArrayOf(1, 1, 0, 0)),
            TiffEntry(50708, 2, ("dev.rawrec:$model").toByteArray(Charsets.US_ASCII) + 0),
            TiffEntry(50710, 1, tBytes(intArrayOf(0, 1, 2))),
            TiffEntry(50711, 3, tShort(1)),
            TiffEntry(50713, 3, tShorts(intArrayOf(2, 2))),
            TiffEntry(50714, 3, tShorts(blackQuad.toIntArray())),
            TiffEntry(50717, 4, tLong(h.whiteLevel)),
            TiffEntry(50721, 10, tSRationalsFromFloats(colorMatrix)),
            TiffEntry(50728, 5, tSRationalsFromFloats(asShotNeutral, scale = 1000L)),
            TiffEntry(50778, 3, tShort(illuminant)),
            TiffEntry(51044, 10, tRational(if (h.fpsMilli > 0) h.fpsMilli.toLong() else 30000L, 1000L))
        )

        // Tier 5: Dual-illuminant colorimetry and noise model
        meta["calibIlluminant2"]?.toIntOrNull()?.let { ill2 ->
            entries.add(TiffEntry(50779, 3, tShort(ill2)))
        }

        // Phase 6: DNG OpcodeLists (Hardware Sensor Calibration)
        val rawJson = meta["_raw"].orEmpty()
        if (rawJson.isNotEmpty()) {
            runCatching {
                val json = org.json.JSONObject(rawJson)
                if (json.has("colorMatrix2")) {
                    val arr = json.getJSONArray("colorMatrix2")
                    val floats = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    entries.add(TiffEntry(50722, 10, tSRationalsFromFloats(floats)))
                }
                if (json.has("forwardMatrix1")) {
                    val arr = json.getJSONArray("forwardMatrix1")
                    val floats = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    entries.add(TiffEntry(50969, 10, tSRationalsFromFloats(floats)))
                }
                if (json.has("forwardMatrix2")) {
                    val arr = json.getJSONArray("forwardMatrix2")
                    val floats = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    entries.add(TiffEntry(50970, 10, tSRationalsFromFloats(floats)))
                }
                if (json.has("noiseProfile")) {
                    val arr = json.getJSONArray("noiseProfile")
                    val doubles = DoubleArray(arr.length()) { arr.getDouble(it) }
                    entries.add(TiffEntry(50974, 12, tDoubles(doubles)))
                }
            }
            // OpcodeList3: WarpRectilinear (Geometric lens distortion correction)
            val dist = extractJsonDoubleArray(rawJson, "lensDistortion")
            val calib = extractJsonDoubleArray(rawJson, "lensCalibration")
            if (dist.size >= 4 && calib.size >= 4) {
                val opcode3 = DngOpcodes.buildWarpRectilinearOpcodeList(dist, calib, w, hh)
                if (opcode3 != null) {
                    entries.add(TiffEntry(51022, 7, opcode3))
                }
            }

            // OpcodeList1: GainMap (Lens vignetting and shading correction)
            val shadingObj = extractJsonObject(rawJson, "lensShading")
            if (shadingObj.isNotEmpty()) {
                val rows = extractJsonInt(shadingObj, "rows")
                val cols = extractJsonInt(shadingObj, "cols")
                val gains = extractJsonFloatArray(shadingObj, "gains")
                if (rows >= 2 && cols >= 2 && gains.isNotEmpty()) {
                    val opcode1 = DngOpcodes.buildGainMapOpcodeList(rows, cols, gains, w, hh)
                    if (opcode1 != null) {
                        entries.add(TiffEntry(51008, 7, opcode1))
                    }
                }
            }
        }

        // If tone profile is specified and not baked, embed standard DNG Camera Profile tags
        if (!bakeTone && profile != ColorScience.ToneProfile.DEFAULT) {
            val profNameBytes = (profile.displayName + "\u0000").toByteArray(Charsets.US_ASCII)
            entries.add(TiffEntry(50936, 2, profNameBytes))

            val curvePoints = ColorScience.evaluateToneCurve(profile, pointsCount = 33)
            val curveBytes = tSRationalsFromFloats(curvePoints, scale = 10_000L)
            entries.add(TiffEntry(50981, 10, curveBytes))
        } else if (bakeTone && profile != ColorScience.ToneProfile.DEFAULT) {
            val profNameBytes = (profile.displayName + " (Baked)\u0000").toByteArray(Charsets.US_ASCII)
            entries.add(TiffEntry(50936, 2, profNameBytes))
        }

        val ifdSize = 2 + 12 * entries.size + 4
        val nonInlineLen = entries.filter { !it.inline }.sumOf { it.data.size + (it.data.size and 1) }
        var stripOff = 8 + ifdSize + nonInlineLen
        if (stripOff and 3 != 0) stripOff += 4 - (stripOff and 3)

        val patchedEntries = entries.map {
            if (it.tag == 273) TiffEntry(273, 4, tLong(stripOff)) else it
        }

        FileOutputStream(file).use { o ->
            o.write(byteArrayOf(0x49, 0x49)); o.write(tShort(42)); o.write(tLong(8))
            writeIfd(o, patchedEntries, 0, 8)
            var pad = stripOff - (8 + ifdSize + nonInlineLen)
            while (pad-- > 0) o.write(0)
            o.write(pixels)
        }
    }

    private fun tBytes(v: IntArray): ByteArray = ByteArray(v.size) { v[it].toByte() }

    fun dngCheck(path: String) {
        RandomAccessFile(path, "r").use { raf ->
            val hb = ByteArray(8); raf.readFully(hb)
            require(hb[0] == 0x49.toByte() && hb[1] == 0x49.toByte()) { "not little-endian TIFF" }
            val bb = java.nio.ByteBuffer.wrap(hb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.position(2)
            val magic = bb.short; val ifdOff0 = bb.int
            require(magic == 42.toShort()) { "bad magic" }

            fun walk(off: Int, label: String): Pair<Long, Long> {
                raf.seek(off.toLong())
                val cnt = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                val names = mapOf(
                    254 to "NewSubFileType", 256 to "ImageWidth", 257 to "ImageLength",
                    258 to "BitsPerSample", 259 to "Compression", 262 to "Photometric",
                    271 to "Make", 272 to "Model", 273 to "StripOffsets",
                    279 to "StripByteCounts", 33421 to "CFARepeatDim", 33422 to "CFAPattern",
                    50706 to "DNGVersion", 50714 to "BlackLevel", 50717 to "WhiteLevel",
                    50728 to "AsShotNeutral", 50721 to "ColorMatrix1", 50778 to "CalibIllim1",
                    51008 to "OpcodeList1", 51009 to "OpcodeList2", 51022 to "OpcodeList3",
                    51044 to "FrameRate"
                )
                println("$label entries=$cnt @ $off")
                var stripOff = -1L; var stripLen = -1L
                repeat(cnt) {
                    val e = ByteArray(12); raf.readFully(e)
                    val eb = java.nio.ByteBuffer.wrap(e).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val tag = eb.short.toInt() and 0xFFFF
                    val typ = eb.short.toInt() and 0xFFFF
                    eb.int
                    var value = eb.int
                    if (typ == 3) value = value and 0xFFFF
                    when (tag) {
                        273 -> { stripOff = value.toLong(); println("  StripOffsets -> $value") }
                        279 -> { stripLen = value.toLong(); println("  StripByteCounts -> $value") }
                        else -> println("  ${names[tag] ?: "tag$tag"} = $value")
                    }
                }
                return stripOff to stripLen
            }

            val (stripOff, stripLen) = walk(ifdOff0, "IFD0(CFA RAW)")

            if (stripOff > 0 && stripLen > 0) {
                raf.seek(stripOff)
                val px = ByteArray(8); raf.readFully(px)
                val pb = java.nio.ByteBuffer.wrap(px).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                print("first4pixels:")
                repeat(4) { print(" ${pb.short.toInt() and 0xFFFF}") }
                println()
                println("strip: offset=$stripOff len=$stripLen fileSize=${raf.length()}")
            }
        }
    }

    fun gen(path: String) {
        val width = 64; val height = 16; val frameCount = 5
        FileOutputStream(path).use { out ->
            val hb = ByteArray(512)
            fun put(off: Int, v: ByteArray) = System.arraycopy(v, 0, hb, off, v.size)
            fun le(v: Int, n: Int): ByteArray = ByteArray(n) { i -> (v shr (8 * i)).toByte() }
            fun le32f(off: Int, v: Float) {
                val bits = java.lang.Float.floatToIntBits(v)
                put(off, le(bits, 4))
            }
            put(0, "RVSP".toByteArray())
            put(4, le(0, 2)); put(6, le(1, 2)); put(8, le(512, 2)); put(10, le(0, 2))
            put(12, "STORE".toByteArray()); put(28, "PCM16".toByteArray())
            put(36, le(width, 4)); put(40, le(height, 4))
            hb[44] = 10; hb[45] = 0; hb[46] = 1
            put(48, le(1023, 4))
            (0..3).forEach { put(52 + it * 4, le(64, 4)) }
            // Non-identity colorimetry so the whole header->DNG path is
            // verifiable without a device (self-test values only).
            val matrix = floatArrayOf(
                1.05f, -0.05f, 0.0f,
                -0.03f, 1.03f, 0.0f,
                0.0f, -0.02f, 1.04f
            )
            matrix.forEachIndexed { i, v -> le32f(68 + i * 4, v) }
            val neutral = floatArrayOf(0.5f, 1.0f, 0.75f)
            neutral.forEachIndexed { i, v -> le32f(104 + i * 4, v) }
            put(116, le(30000, 4))
            put(128, "SELFTEST".toByteArray())
            val meta = """{"cameraId":"selftest","calibIlluminant1":17}"""
            val metaBytes = meta.toByteArray(Charsets.UTF_8)
            put(232, le(metaBytes.size, 4))
            out.write(hb)
            out.write(metaBytes)

            repeat(frameCount) { f ->
                val packed = ByteArray(width * height / 4 * 5)
                var si = 0; var di = 0
                while (si < width * height) {
                    val x = si % width; val y = si / width
                    fun px(k: Int): Int =
                        (((x + k) % width * 15 + f * 20 xor (y * 13)) and 0x3FF)
                    val p0 = px(0); val p1 = px(1); val p2 = px(2); val p3 = px(3)
                    packed[di] = (p0 shr 2).toByte()
                    packed[di + 1] = (p1 shr 2).toByte()
                    packed[di + 2] = (p2 shr 2).toByte()
                    packed[di + 3] = (p3 shr 2).toByte()
                    packed[di + 4] = (((p0 and 3) shl 6) or ((p1 and 3) shl 4) or
                        ((p2 and 3) shl 2) or (p3 and 3)).toByte()
                    si += 4; di += 5
                }
                val head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                head.put(byteArrayOf(0x46, 0x52, 0x4D, 0))
                head.putInt(packed.size)
                head.putLong(f * 33_000_000L)
                head.putLong(8_000_000L)
                head.putInt(100)
                head.putInt(f)
                out.write(head.array())
                out.write(packed)

                val audioBytes = ByteArray(6400)
                val audioHead = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                audioHead.put(byteArrayOf(0x41, 0x55, 0x44, 0))
                audioHead.putInt(audioBytes.size)
                audioHead.putLong(f * 33_000_000L)
                audioHead.putLong(0L)
                audioHead.putInt(0)
                audioHead.putInt(f)
                out.write(audioHead.array())
                out.write(audioBytes)
            }
        }
        println("generated $path (${width}x${height} x$frameCount mipi10 STORE)")
    }
}
