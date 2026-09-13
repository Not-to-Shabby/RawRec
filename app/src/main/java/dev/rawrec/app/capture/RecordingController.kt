package dev.rawrec.app.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.media.Image
import android.os.Process
import android.os.SystemClock
import android.util.Size
import dev.rawrec.app.audio.AudioRecorder
import dev.rawrec.app.codec.FrameCodec
import dev.rawrec.app.codec.MipiPacker
import dev.rawrec.app.codec.RawPackNative
import dev.rawrec.app.codec.RawSampleReader
import dev.rawrec.app.codec.StoreCodec
import dev.rawrec.app.codec.ZstdFrameCodec
import dev.rawrec.app.container.Rvsp
import dev.rawrec.app.container.RvspHeader
import dev.rawrec.app.container.RvspWriter
import dev.rawrec.app.profiles.SocOptimizer.ThermalStage as SocStage
import dev.rawrec.app.proxy.ProxyEncoder
import dev.rawrec.app.proxy.ProxyFrame
import dev.rawrec.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RecStats(
    val active: Boolean = false,
    val fileName: String = "",
    val framesCaptured: Long = 0,
    val framesWritten: Long = 0,
    val dropped: Long = 0,
    val avgFps: Double = 0.0,
    val bytesWritten: Long = 0,
    val rawBytesEstimate: Long = 0,
    val elapsedMs: Long = 0,
    val workers: Int = 0,
    val error: String? = null,
    val thermalStage: String = "NONE"
) {
    val ratio: Double
        get() = if (rawBytesEstimate > 0) bytesWritten.toDouble() / rawBytesEstimate else 0.0
}

data class StorageVolumeInfo(
    val description: String,
    val path: File,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long
)

class RecordingController(private val context: Context) {
    private val prefs = dev.rawrec.app.util.AppPreferences(context)

    private class QueuedImage(
        @Volatile var image: Image?,
        val tsNs: Long,
        val expNs: Long,
        val iso: Int
    )

    class AudioChunk(val pcm: ByteArray, val tsNs: Long)

    private class WorkItem(
        val payload: ByteArray,
        val tsNs: Long,
        val expNs: Long,
        val iso: Int,
        val needsEncode: Boolean
    )

    private data class ProcessedItem(
        val encoded: ByteArray,
        val tsNs: Long,
        val expNs: Long,
        val iso: Int
    )

    private val _stats = MutableStateFlow(RecStats())
    val stats: StateFlow<RecStats> = _stats

    private var engine: RawCaptureEngine? = null
    private var writerThread: Thread? = null
    private var extractorThread: Thread? = null
    @Volatile private var workerPool: ExecutorService? = null
    private var inbound: ArrayBlockingQueue<QueuedImage>? = null
    private var midQ: ArrayBlockingQueue<WorkItem> = ArrayBlockingQueue(MID_CAPACITY)
    private val payloadPool = ArrayBlockingQueue<ByteArray>(32)
    private var outbound: ArrayBlockingQueue<ProcessedItem>? = null
    private var audioQ: ArrayBlockingQueue<AudioChunk>? = null
    private var audio: AudioRecorder? = null
    private var proxyEncoder: ProxyEncoder? = null

    private var proxyY: java.nio.ByteBuffer? = null
    private var proxyU: java.nio.ByteBuffer? = null
    private var proxyV: java.nio.ByteBuffer? = null
    private var proxyW = 0
    private var proxyH = 0

    @Volatile private var stopping = false
    private val droppedInQ = AtomicLong()
    private val droppedMidQ = AtomicLong()
    private val droppedOutQ = AtomicLong()
    private val capturedCounter = AtomicLong()
    private val writtenCounter = AtomicLong()
    private val bytesCounter = AtomicLong()
    private val rawEstimateCounter = AtomicLong()
    @Volatile private var activeStartRealtime = 0L
    @Volatile private var lastFrameTimestamp = 0L
    @Volatile private var lastUiPublishMs = 0L
    private val statsLock = Any()
    private val startStopLock = Any()
    @Volatile private var isStarting = false

    @Volatile private var quadCodes: IntArray = intArrayOf(0, 1, 1, 2)
    @Volatile private var sessionWhiteLevel = 1023
    @Volatile private var proxyActive = false
    // Session context for thermal rescale (Tier 2): set at start(), cleared at stop().
    @Volatile private var lastStartedSize: Size? = null
    @Volatile private var activeCodec: FrameCodec? = null
    @Volatile private var activeWorkerGate = 3

    // Thermal governor: drives zstd-pool rescale on escalation (Tier 2) and
    // feeds RecStats.thermalStage. Session is NEVER touched by it.
    private val thermalGovernor = ThermalGovernor(context) { stage ->
        onThermalStageChanged(stage)
    }
    // ADPF hint session + powerkeeper notify (Tier 3).
    private val perfSession = PerfSession(context)
    @Volatile private var activeThermalStage = SocStage.NONE

    fun recordingsDir(): File {
        val custom = prefs.customStoragePath?.trim().orEmpty()
        if (custom.isNotEmpty()) {
            val customDir = File(custom)
            if (customDir.exists() || customDir.mkdirs() || customDir.canWrite()) {
                return customDir
            }
            AppLog.w(TAG, "Configured custom storage path $custom unavailable, falling back to internal storage")
        }
        val rootDir = File(android.os.Environment.getExternalStorageDirectory(), "RawRec")
        if (rootDir.exists() || rootDir.mkdirs() || rootDir.canWrite()) {
            return rootDir
        }
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
    }

    fun getStorageVolumes(): List<StorageVolumeInfo> {
        return runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            val volumes = sm?.storageVolumes.orEmpty()
            val list = mutableListOf<StorageVolumeInfo>()

            for (v in volumes) {
                val dir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    v.directory
                } else null

                val targetDir = if (dir != null) {
                    File(dir, "RawRec")
                } else if (v.isPrimary) {
                    File(android.os.Environment.getExternalStorageDirectory(), "RawRec")
                } else null

                if (targetDir != null) {
                    val desc = v.getDescription(context)
                    val isPrim = v.isPrimary
                    val isRem = v.isRemovable
                    val root = targetDir.parentFile ?: targetDir
                    val free = runCatching { root.freeSpace }.getOrDefault(0L)
                    val total = runCatching { root.totalSpace }.getOrDefault(0L)
                    list.add(StorageVolumeInfo(desc, targetDir, isPrim, isRem, free, total))
                }
            }
            if (list.isEmpty()) {
                val primary = File(android.os.Environment.getExternalStorageDirectory(), "RawRec")
                list.add(
                    StorageVolumeInfo(
                        "Internal Shared Storage",
                        primary,
                        isPrimary = true,
                        isRemovable = false,
                        freeBytes = primary.freeSpace,
                        totalBytes = primary.totalSpace
                    )
                )
            }
            list
        }.getOrElse {
            val primary = File(android.os.Environment.getExternalStorageDirectory(), "RawRec")
            listOf(
                StorageVolumeInfo(
                    "Internal Shared Storage",
                    primary,
                    isPrimary = true,
                    isRemovable = false,
                    freeBytes = primary.freeSpace,
                    totalBytes = primary.totalSpace
                )
            )
        }
    }

    fun listRecordings(): List<File> {
        val active = recordingsDir()
        val activeFiles = active.listFiles { f -> f.name.endsWith(".rvsp") || f.name.endsWith(".mp4") }?.toList() ?: emptyList()
        val primaryDir = File(android.os.Environment.getExternalStorageDirectory(), "RawRec")
        val primaryFiles = if (primaryDir.exists() && primaryDir != active) {
            primaryDir.listFiles { f -> f.name.endsWith(".rvsp") || f.name.endsWith(".mp4") }?.toList() ?: emptyList()
        } else emptyList()
        val legacyDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
        val legacy = if (legacyDir.exists() && legacyDir != active && legacyDir != primaryDir) {
            legacyDir.listFiles { f -> f.name.endsWith(".rvsp") || f.name.endsWith(".mp4") }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
        return (activeFiles + primaryFiles + legacy).distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    private fun deviceState(): String {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val sticky = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val tempTenths = sticky?.getIntExtra("temperature", Int.MIN_VALUE) ?: Int.MIN_VALUE
            val battPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            "batt=$battPct% battTemp=${if (tempTenths != Int.MIN_VALUE) "${tempTenths / 10.0}C" else "?"} " +
                "thermal=${pm.currentThermalStatus}"
        }.getOrElse { "state unavailable" }
    }

    private var previewEngine: PreviewEngine? = null
    private val previewLock = Any()
    @Volatile private var activePreviewSurface: android.view.Surface? = null
    @Volatile private var lastCameraId: String? = null
    @Volatile private var lastPhysicalCameraId: String? = null
    @Volatile private var lastControlState: dev.rawrec.app.control.CameraControlState? = null

    fun startPreview(
        cameraId: String,
        surface: android.view.Surface,
        controls: dev.rawrec.app.control.CameraControlState,
        physicalCameraId: String? = null
    ) {
        synchronized(previewLock) {
            activePreviewSurface = surface
            lastCameraId = cameraId
            lastPhysicalCameraId = physicalCameraId
            lastControlState = controls
            if (engine != null) return

            val pe = previewEngine
            if (pe != null && pe.isAliveAndMatching(cameraId, physicalCameraId, surface)) {
                pe.updateControls(controls)
                return
            }

            // Clean up old, dead, or surface-mismatched preview engine before creating fresh one
            previewEngine?.stop()
            previewEngine = null

            val newPe = PreviewEngine(
                context, cameraId, physicalCameraId, surface,
                onCameraLost = {
                    // External eviction (e.g. Google Assistant error 3): self-heal.
                    AppLog.w(TAG, "preview lost externally — auto-restarting")
                    synchronized(previewLock) {
                        if (previewEngine != null) previewEngine?.restart()
                    }
                }
            )
            previewEngine = newPe
            Thread({
                try {
                    kotlinx.coroutines.runBlocking { newPe.start(controls) }
                } catch (t: Throwable) {
                    AppLog.e(TAG, "preview start failed", t)
                    synchronized(previewLock) {
                        if (previewEngine == newPe) previewEngine = null
                    }
                }
            }, "rawrec-preview-thread").start()
        }
    }

    fun stopPreview() {
        synchronized(previewLock) {
            previewEngine?.stop()
            previewEngine = null
        }
    }

    fun updateControls(state: dev.rawrec.app.control.CameraControlState) {
        lastControlState = state
        engine?.updateControls(state)
        previewEngine?.updateControls(state)
    }

    fun start(
        cameraId: String,
        size: Size,
        autoExposure: Boolean = true,
        useZstd: Boolean = true,
        packMipi10: Boolean = true,
        mic: Boolean = false,
        proxy: Boolean = false,
        previewSurface: android.view.Surface? = null,
        controls: dev.rawrec.app.control.CameraControlState = dev.rawrec.app.control.CameraControlState(
            autoExposure = autoExposure
        ),
        uiRotation: Int = 0,
        physicalCameraId: String? = null
    ) {
        synchronized(startStopLock) {
            if (isStarting || engine != null || _stats.value.active) {
                AppLog.w(TAG, "start ignored: session active or starting")
                return
            }
            isStarting = true
        }

        try {
            // Latch a real AWB result from the preview session before tearing it
            // down — used to seed AsShotNeutral in the file header.
            val firstCaptureResult = previewEngine?.firstCaptureResult
            stopPreview()

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = if (physicalCameraId != null) {
            runCatching { cm.getCameraCharacteristics(physicalCameraId) }.getOrNull()
                ?: cm.getCameraCharacteristics(cameraId)
        } else {
            cm.getCameraCharacteristics(cameraId)
        }
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val cfa = (chars.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: 0).coerceIn(0, 3)
        val bitDepth = bitDepthFromWhiteLevel(whiteLevel)
        val canPack = packMipi10 && bitDepth == 10
        val nativePack = canPack && RawPackNative.loaded
        sessionWhiteLevel = whiteLevel

        // WYSIWYG framing crop: wide aspects store the same center band the
        // letterboxed viewfinder shows (software crop in the packer — never
        // SCALER_CROP_REGION, whose RAW buffer semantics are HAL-dependent).
        val isLandscape = uiRotation == 90 || uiRotation == 270
        val effectiveAspect = controls.framingAspect(isLandscape)
        val framingCrop = if (canPack) {
            MipiPacker.cropRect(effectiveAspect, size.width, size.height, isLandscape)
        } else null
        val effW = framingCrop?.get(2) ?: size.width
        val effH = framingCrop?.get(3) ?: size.height
        val effSize = Size(effW, effH)
        // Captured by the extractor thread; effectively final for the session.
        val activeFramingCrop: IntArray? = framingCrop

        // --- Sensor colorimetry (AOSP DngCreator semantics: direct copies) ---
        val calib = dev.rawrec.app.control.SensorCalibration
        val blackLevelQuad = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { p -> IntArray(4).also { p.copyTo(it, 0) } }
        val blackLevel = blackLevelQuad?.let {
            calib.blackLevelToChannelOrder(it, cfa)
        } ?: intArrayOf(0, 0, 0, 0)
        val colorMatrix = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { t ->
            // ColorSpaceTransform exposes getElement(column,row) as Rational;
            // flatten row-major, numerator/denominator separately.
            val nums = IntArray(9)
            val dens = IntArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val r = t.getElement(col, row)
                    nums[row * 3 + col] = r.numerator
                    dens[row * 3 + col] = r.denominator
                }
            }
            calib.colorTransformToFloats(nums, dens)
        } ?: calib.identityMatrix()
        val calibIlluminant1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
            ?: calib.DEFAULT_ILLUMINANT
        val calibIlluminant2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        val colorMatrix2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { t ->
            val nums = IntArray(9)
            val dens = IntArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val r = t.getElement(col, row)
                    nums[row * 3 + col] = r.numerator
                    dens[row * 3 + col] = r.denominator
                }
            }
            calib.colorTransformToFloats(nums, dens)
        }
        val forwardMatrix1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { t ->
            val nums = IntArray(9)
            val dens = IntArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val r = t.getElement(col, row)
                    nums[row * 3 + col] = r.numerator
                    dens[row * 3 + col] = r.denominator
                }
            }
            calib.colorTransformToFloats(nums, dens)
        }
        val forwardMatrix2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { t ->
            val nums = IntArray(9)
            val dens = IntArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val r = t.getElement(col, row)
                    nums[row * 3 + col] = r.numerator
                    dens[row * 3 + col] = r.denominator
                }
            }
            calib.colorTransformToFloats(nums, dens)
        }
        val noiseProfile = firstCaptureResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { pairs ->
            val arr = DoubleArray(pairs.size * 2)
            for (i in pairs.indices) {
                arr[i * 2] = pairs[i].first
                arr[i * 2 + 1] = pairs[i].second
            }
            arr
        }
        // AsShotNeutral: prefer the first result's neutral point / AWB gains;
        // fall back to the manual WB gains from the control state.
        val asShotNeutral = run {
            val firstResult = firstCaptureResult
            when {
                firstResult?.get(TotalCaptureResult.SENSOR_NEUTRAL_COLOR_POINT) != null -> {
                    firstResult.get(TotalCaptureResult.SENSOR_NEUTRAL_COLOR_POINT)!!
                        .map { it.toFloat() }.toFloatArray()
                }
                firstResult?.get(TotalCaptureResult.COLOR_CORRECTION_GAINS) != null -> {
                    val g = firstResult.get(TotalCaptureResult.COLOR_CORRECTION_GAINS)!!
                    calib.asShotNeutralFromGains(
                        floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
                    )
                }
                !controls.autoWhiteBalance ->
                    calib.asShotNeutralFromManualRgb(controls.effectiveRgbGains)
                else -> floatArrayOf(1f, 1f, 1f)
            }
        }
        // Timestamp source for A/V alignment (see AudioRecorder clock note)
        val tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN

        val dir = recordingsDir().apply { mkdirs() }
        val preStat = runCatching { android.os.StatFs(dir.absolutePath) }.getOrNull()
        if (preStat != null && preStat.totalBytes > 0L) {
            val usedRatio = (preStat.totalBytes - preStat.availableBytes).toDouble() / preStat.totalBytes
            if (usedRatio >= 0.95) {
                val pctStr = "%.1f".format(usedRatio * 100)
                AppLog.w(TAG, "Cannot start recording: storage is $pctStr% full (limit 95%)")
                publish { RecStats(error = "Storage 95% full — cannot record") }
                synchronized(startStopLock) { isStarting = false }
                return
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "rec_$stamp.rvsp")
        val startRealtime = SystemClock.elapsedRealtime()

        droppedInQ.set(0)
        droppedMidQ.set(0)
        droppedOutQ.set(0)
        capturedCounter.set(0)
        writtenCounter.set(0)
        bytesCounter.set(0)
        rawEstimateCounter.set(0)
        activeStartRealtime = startRealtime
        lastFrameTimestamp = 0L
        lastUiPublishMs = 0L
        stopping = false
        firstTsForFps = 0L

        // --- Phase 6: Multi-SoC Optimization & Dynamic Queue Bounding ---
        val isHighRes = size.width > 4096 || size.height > 3072
        val socTuning = dev.rawrec.app.profiles.SocOptimizer.current(isHighRes50Mp = isHighRes)
        AppLog.i(TAG, "SoC optimization: ${socTuning.family} (${socTuning.description}), " +
            "workers=${socTuning.recommendedCompressWorkers}, queues: in=${socTuning.recommendedQueueCapacities.inCapacity} " +
            "mid=${socTuning.recommendedQueueCapacities.midCapacity} out=${socTuning.recommendedQueueCapacities.outCapacity}")

        val inQ = ArrayBlockingQueue<QueuedImage>(socTuning.recommendedQueueCapacities.inCapacity)
        val outQ = ArrayBlockingQueue<ProcessedItem>(socTuning.recommendedQueueCapacities.outCapacity)
        inbound = inQ
        outbound = outQ

        val codec: FrameCodec = if (useZstd) ZstdFrameCodec(level = socTuning.zstdLevel, nbWorkers = INNER_ZSTD_WORKERS) else StoreCodec
        val packing = if (canPack) Rvsp.PACKING_MIPI_PACKED else Rvsp.PACKING_EXPANDED_LSB
        val compressWorkers = socTuning.recommendedCompressWorkers
        midQ = ArrayBlockingQueue(socTuning.recommendedQueueCapacities.midCapacity)
        payloadPool.clear()
        val packedFrameSize = if (canPack) ((effW + 3) / 4) * 5 * effH else 0
        if (packedFrameSize > 0) {
            val poolCapacity = socTuning.recommendedQueueCapacities.midCapacity + 3
            repeat(poolCapacity) {
                payloadPool.offer(ByteArray(packedFrameSize))
            }
        }
        lastStartedSize = size
        activeCodec = codec

        // Optical calibration for DNG opcodes (Phase 6)
        val lensCalibration = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val lensDistortion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            chars.get(CameraCharacteristics.LENS_DISTORTION)
        } else {
            chars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
        }
        val opticalBlack = chars.get(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS)?.map {
            intArrayOf(it.left, it.top, it.width(), it.height())
        }
        val lensShadingMap = firstCaptureResult?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val lensShadingJson = lensShadingMap?.let { lsm ->
            val gains = FloatArray(lsm.gainFactorCount)
            lsm.copyGainFactors(gains, 0)
            JSONObject().apply {
                put("rows", lsm.rowCount)
                put("cols", lsm.columnCount)
                put("gains", org.json.JSONArray(gains.toList()))
            }
        }

        val proxyActiveNow = proxy && canPack && nativePack
        if (proxyActiveNow) {
            proxyW = effW / PROXY_FACTOR
            proxyH = effH / PROXY_FACTOR
            proxyY = java.nio.ByteBuffer.allocateDirect(proxyW * proxyH)
            proxyU = java.nio.ByteBuffer.allocateDirect(proxyW * proxyH / 4)
            proxyV = java.nio.ByteBuffer.allocateDirect(proxyW * proxyH / 4)
            val quad = when (cfa) {
                0 -> intArrayOf(0, 1, 1, 2); 1 -> intArrayOf(1, 0, 2, 1)
                2 -> intArrayOf(1, 2, 0, 1); else -> intArrayOf(2, 1, 1, 0)
            }
            quadCodes = quad
            val proxyFps = kotlin.math.round(controls.targetFps).toInt().coerceAtLeast(1)
            proxyEncoder = ProxyEncoder(
                File(dir, "${file.nameWithoutExtension}_proxy.mp4"),
                proxyW, proxyH,
                fps = proxyFps
            ).also { it.start() }
            AppLog.i(TAG, "proxy encoder started ${proxyW}x${proxyH} @ ${proxyFps}fps")
        }
        proxyActive = proxyActiveNow

        if (mic) {
            audioQ = ArrayBlockingQueue(64)
            // Timestamp source REALTIME means sensor timestamps share the
            // elapsedRealtime (CLOCK_BOOTTIME) base — use the same clock for
            // audio so records are directly comparable (see DngCreator ctor).
            val audioClock: () -> Long =
                if (tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                    { android.os.SystemClock.elapsedRealtimeNanos() }
                } else {
                    { System.nanoTime() }
                }
            val rec = AudioRecorder(sampleRate = 48_000, channels = 2, clock = audioClock) { pcm, ts ->
                val q = audioQ ?: return@AudioRecorder
                while (!q.offer(AudioChunk(pcm, ts))) { q.poll() }
                if (proxyActive) {
                    proxyEncoder?.submitAudio(pcm, ts)
                }
            }
            try {
                rec.start()
                audio = rec
                AppLog.i(TAG, "audio recorder started: 48000Hz stereo pcm16")
            } catch (t: Throwable) {
                AppLog.e(TAG, "audio start failed", t)
                audioQ = null
            }
        }

        publish {
            RecStats(active = true, fileName = file.name, workers = compressWorkers)
        }
        AppLog.i(
            TAG, "start cam=$cameraId ${size.width}x${size.height} bitDepth=$bitDepth " +
                "codec=${codec.id} pack=$canPack(native=$nativePack) " +
                "outerWorkers=$compressWorkers innerZstd=$INNER_ZSTD_WORKERS " +
                "autoExp=$autoExposure" +
                    (framingCrop?.let { " crop=[${it[0]},${it[1]},${it[2]},${it[3]}]" } ?: "") +
                    " ${deviceState()} -> ${file.name}"
        )

        writerThread = Thread({
            dev.rawrec.app.profiles.SocOptimizer.applyThreadPriority(socTuning.threadPriority)
            runWriterLoop(
                file, outQ, effSize, whiteLevel, cfa, cameraId, startRealtime, codec, packing,
                audioMeta = if (audio != null) "48000/2/pcm16" else null,
                blackLevel = blackLevel,
                colorMatrix = colorMatrix,
                asShotNeutral = asShotNeutral,
                calibIlluminant1 = calibIlluminant1,
                tsSource = tsSource,
                framingAspect = effectiveAspect,
                framingCrop = framingCrop,
                targetFps = controls.targetFps,
                lensCalibration = lensCalibration,
                lensDistortion = lensDistortion,
                opticalBlack = opticalBlack,
                lensShadingJson = lensShadingJson,
                calibIlluminant2 = calibIlluminant2,
                colorMatrix2 = colorMatrix2,
                forwardMatrix1 = forwardMatrix1,
                forwardMatrix2 = forwardMatrix2,
                noiseProfile = noiseProfile
            )
        }, "rvsp-writer").apply { start() }

        val pool = Executors.newFixedThreadPool(compressWorkers) { r ->
            Thread(r, "rvsp-zstd").apply { isDaemon = true }
        }
        workerPool = pool
        activeWorkerGate = compressWorkers
        // ADPF: register the zstd worker TIDs once the threads exist. The
        // threads materialize lazily on first execute, so capture them via a
        // side list filled inside the thread body itself.
        val workerTids = java.util.Collections.synchronizedList(ArrayList<Int>())
        val framePeriodNs = (1_000_000_000.0 / controls.targetFps.coerceAtLeast(1.0)).toLong()
        repeat(compressWorkers) { workerIndex ->
            pool.execute {
                dev.rawrec.app.profiles.SocOptimizer.applyThreadPriority(socTuning.threadPriority)
                workerTids.add(Process.myTid())
                // First thread to come up starts the hint session with the
                // writer tid (we don't own it here; the engine's capture
                // thread registers itself separately via registerCaptureThread).
                if (workerTids.size == compressWorkers) {
                    perfSession.startHintSession(workerTids.toIntArray(), framePeriodNs)
                }
                val encoder = codec.openEncoder()
                try {
                    while (!stopping || midQ.isNotEmpty()) {
                        // Thermal degradation gate: extra workers sleep when thermal throttles
                        if (workerIndex >= activeWorkerGate && !stopping) {
                            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                            continue
                        }
                        val wi = midQ.poll(50, TimeUnit.MILLISECONDS) ?: continue
                        try {
                            perfSession.markWorkStart()
                            val rawPayload = wi.payload
                            var payload = rawPayload
                            if (wi.needsEncode) {
                                payload = encoder.encode(rawPayload)
                                payloadPool.offer(rawPayload)
                            }
                            val done = ProcessedItem(payload, wi.tsNs, wi.expNs, wi.iso)
                            while (!outQ.offer(done)) {
                                outQ.poll()
                                droppedOutQ.incrementAndGet()
                            }
                            perfSession.reportWorkDone()
                            publishProgress()
                        } catch (t: Throwable) {
                            AppLog.e(TAG, "compress failed", t)
                        }
                    }
                } finally {
                    runCatching { encoder.close() }
                }
            }
        }

        // Thermal governor starts only while a take is active (Tier 2).
        activeThermalStage = SocStage.NONE
        thermalGovernor.start()
        // HyperOS power governor: lift camera power limits during the take.
        val qualityLabel = "${size.width}x${size.height}@" + "%.0f".format(controls.targetFps)
        perfSession.notifyPowerkeeperRecordingStart(controls.targetFps, qualityLabel)
        // Foreground service: keeps camera DMA and background CPU alive under HyperOS / Android 15
        CaptureForegroundService.start(context)

        var extractCount = 0
        val extractor = Thread({
            dev.rawrec.app.profiles.SocOptimizer.applyThreadPriority(socTuning.threadPriority)
            while (!stopping || inQ.isNotEmpty()) {
                val qi = inQ.poll(50, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val img = qi.image ?: continue
                    val tsNs = qi.tsNs
                    val crop = activeFramingCrop
                    val targetBuf = if (packedFrameSize > 0) (payloadPool.poll() ?: ByteArray(packedFrameSize)) else null
                    val t0 = System.nanoTime()
                    val packed = extractFromImage(
                        img, nativePack || canPack, size, proxyActive, crop, targetBuf
                    )
                    val t1 = System.nanoTime()
                    if (proxyActive && proxyEncoder != null) {
                        val pBuf = proxyEncoder?.obtainBuffer()
                        if (pBuf != null) {
                            proxyY?.let { py -> py.position(0); py.get(pBuf.y); py.position(0) }
                            proxyU?.let { pu -> pu.position(0); pu.get(pBuf.u); pu.position(0) }
                            proxyV?.let { pv -> pv.position(0); pv.get(pBuf.v); pv.position(0) }
                            pBuf.ptsUs = tsNs / 1000
                            proxyEncoder?.submit(pBuf)
                        }
                    }
                    img.close()
                    qi.image = null
                    val work = WorkItem(packed, tsNs, qi.expNs, qi.iso, codec.compressed)
                    val t2 = System.nanoTime()
                    while (!midQ.offer(work)) {
                        val evicted = midQ.poll()
                        if (evicted != null && evicted.needsEncode) {
                            payloadPool.offer(evicted.payload)
                        }
                        droppedMidQ.incrementAndGet()
                    }
                    val t3 = System.nanoTime()
                    if (++extractCount % 30 == 0) {
                        val packMs = (t1 - t0) / 1_000_000.0
                        val midQMs = (t3 - t2) / 1_000_000.0
                        AppLog.i(TAG, "extractor: pack=%.1fms, midQ=%.1fms, inQ.size=%d, midQ.size=%d, pool=%d".format(packMs, midQMs, inQ.size, midQ.size, payloadPool.size))
                    }
                } catch (t: Throwable) {
                    AppLog.e(TAG, "extract failed", t)
                    runCatching { qi.image?.close() }
                    qi.image = null
                }
            }
        }, "rvsp-extract").apply { start() }
        extractorThread = extractor

        engine = RawCaptureEngine(
            context, cameraId, size.width, size.height,
            physicalCameraId = physicalCameraId,
            transferImageOwnership = true,
            maxImages = socTuning.recommendedQueueCapacities.maxImages
        ) { img, meta ->
            onFrameCaptured(inQ, img, meta, startRealtime)
        }

            Thread({
                try {
                    kotlinx.coroutines.runBlocking {
                        engine!!.start(controls = controls, previewSurface = previewSurface)
                    }
                    AppLog.i(TAG, "capture session started")
                } catch (t: Throwable) {
                    AppLog.e(TAG, "capture start failed", t)
                    stop()
                    publish {
                        RecStats(error = "start failed: ${t.message}", fileName = file.name)
                    }
                } finally {
                    synchronized(startStopLock) {
                        isStarting = false
                    }
                }
            }, "rawcap-starter").apply { start() }
        } catch (t: Throwable) {
            synchronized(startStopLock) {
                isStarting = false
            }
            throw t
        }
    }

    /**
     * Thermal escalation: adjust active worker gate without thread pool churn.
     * Higher-index worker threads park and sleep when the gate drops, instantly
     * reducing CPU core pressure and preventing Camera HAL starvation.
     * CRITICAL additionally surfaces via RecStats.error so the studio header shows it.
     */
    private fun onThermalStageChanged(stage: SocStage) {
        activeThermalStage = stage
        synchronized(statsLock) {
            _stats.value = _stats.value.copy(
                thermalStage = stage.name,
                error = if (stage == SocStage.CRITICAL)
                    "thermal critical — thermal throttling imminent" else _stats.value.error
            )
        }
        val isHighRes = lastStartedSize?.let { it.width > 4096 || it.height > 3072 } ?: false
        val tuning = dev.rawrec.app.profiles.SocOptimizer.tuningFor(
            isHighRes50Mp = isHighRes, thermalStage = stage
        )
        val target = tuning.recommendedCompressWorkers
        if (activeWorkerGate != target) {
            AppLog.w(TAG, "thermal $stage -> active zstd workers $activeWorkerGate -> $target")
            activeWorkerGate = target
        }
    }

    fun stop() {
        synchronized(startStopLock) {
            if (engine == null && writerThread == null) return
            AppLog.i(
                TAG, "stop requested: captured=${_stats.value.framesCaptured} " +
                    "written=${_stats.value.framesWritten} dropped=${totalDropped()}"
            )
            stopping = true
            runCatching { audio?.stop() }
            audio = null
            runCatching { extractorThread?.join(TimeUnit.SECONDS.toMillis(10)) }
            extractorThread = null
            runCatching { proxyEncoder?.finish() }
            proxyEncoder = null
            runCatching { engine?.stop() }
            engine = null

            // Teardown drain: guarantee all pending hardware gralloc buffers in inQ are closed
            val q = inbound
            if (q != null) {
                while (q.isNotEmpty()) {
                    runCatching { q.poll()?.image?.close() }
                }
            }

            workerPool?.shutdown()
            runCatching { workerPool?.awaitTermination(10, TimeUnit.SECONDS) }
            workerPool = null
            runCatching { writerThread?.join(TimeUnit.SECONDS.toMillis(15)) }
            writerThread = null
            inbound = null
            outbound = null
            thermalGovernor.stop()
            perfSession.endHintSession()
            perfSession.notifyPowerkeeperRecordingEnd()
            CaptureForegroundService.stop(context)
            activeThermalStage = SocStage.NONE
            lastStartedSize = null
            activeCodec = null
            payloadPool.clear()
            synchronized(statsLock) {
                _stats.value = _stats.value.copy(active = false, thermalStage = "NONE")
            }
            AppLog.i(TAG, "stopped: ${_stats.value.fileName} bytes=${_stats.value.bytesWritten} ${deviceState()}")

            val surf = activePreviewSurface
            val cam = lastCameraId
            val phys = lastPhysicalCameraId
            val ctrl = lastControlState
            if (surf != null && surf.isValid && cam != null && ctrl != null) {
                startPreview(cam, surf, ctrl, phys)
            }
        }
    }

    private fun extractFromImage(
        img: Image,
        doPack: Boolean,
        size: Size,
        useProxy: Boolean,
        crop: IntArray? = null,
        targetBuf: ByteArray? = null
    ): ByteArray {
        val plane = img.planes[0]
        val cropL = crop?.get(0) ?: 0
        val cropT = crop?.get(1) ?: 0
        val cropW = crop?.get(2) ?: size.width
        val cropH = crop?.get(3) ?: size.height
        return when {
            doPack && RawPackNative.loaded -> {
                val buf = plane.buffer
                buf.position(0)
                try {
                    if (crop != null) {
                        if (useProxy && proxyEncoder != null) {
                            RawPackNative.packMipi10ProxyCroppedDirect(
                                buf, plane.rowStride, plane.pixelStride,
                                cropL, cropT, cropW, cropH,
                                quadCodes, sessionWhiteLevel,
                                PROXY_FACTOR, proxyY!!, proxyU!!, proxyV!!
                            ) ?: (if (targetBuf != null && RawPackNative.packMipi10CroppedDirectInto(
                                buf, targetBuf, plane.rowStride, plane.pixelStride,
                                cropL, cropT, cropW, cropH
                            )) targetBuf else RawPackNative.packMipi10CroppedDirect(
                                buf, plane.rowStride, plane.pixelStride,
                                cropL, cropT, cropW, cropH
                            )) ?: RawPackNative.packMipi10(
                                toBytes(buf), plane.rowStride, plane.pixelStride,
                                size.width, size.height
                            ).let { full ->
                                // JVM last resort: pack full frame then cut the
                                // packed rows for the crop band.
                                MipiPacker.packCropped(
                                    RawSampleReader.readU16LE(
                                        toBytes(buf), plane.rowStride, plane.pixelStride,
                                        size.width, size.height
                                    ),
                                    size.width, crop
                                ) ?: full
                            }
                        } else {
                            if (targetBuf != null && RawPackNative.packMipi10CroppedDirectInto(
                                buf, targetBuf, plane.rowStride, plane.pixelStride,
                                cropL, cropT, cropW, cropH
                            )) targetBuf
                            else RawPackNative.packMipi10CroppedDirect(
                                buf, plane.rowStride, plane.pixelStride,
                                cropL, cropT, cropW, cropH
                            ) ?: MipiPacker.packCropped(
                                RawSampleReader.readU16LE(
                                    toBytes(buf), plane.rowStride, plane.pixelStride,
                                    size.width, size.height
                                ),
                                size.width, crop
                            )
                        }
                    } else if (useProxy && proxyEncoder != null) {
                        RawPackNative.packMipi10ProxyDirect(
                            buf, plane.rowStride, plane.pixelStride,
                            size.width, size.height, quadCodes, sessionWhiteLevel,
                            PROXY_FACTOR, proxyY!!, proxyU!!, proxyV!!
                        ) ?: (if (targetBuf != null && RawPackNative.packMipi10DirectInto(
                            buf, targetBuf, plane.rowStride, plane.pixelStride, size.width, size.height
                        )) targetBuf else RawPackNative.packMipi10Direct(
                            buf, plane.rowStride, plane.pixelStride,
                            size.width, size.height
                        )) ?: RawPackNative.packMipi10(
                            toBytes(buf), plane.rowStride, plane.pixelStride,
                            size.width, size.height
                        )
                    } else {
                        if (targetBuf != null && RawPackNative.packMipi10DirectInto(
                            buf, targetBuf, plane.rowStride, plane.pixelStride, size.width, size.height
                        )) targetBuf
                        else RawPackNative.packMipi10Direct(
                            buf, plane.rowStride, plane.pixelStride, size.width, size.height
                        ) ?: RawPackNative.packMipi10(
                            toBytes(buf), plane.rowStride, plane.pixelStride,
                            size.width, size.height
                        )
                    }
                } catch (e: IllegalStateException) {
                    RawPackNative.packMipi10(
                        toBytes(buf), plane.rowStride, plane.pixelStride,
                        size.width, size.height
                    )
                }
            }
            doPack -> {
                val samples = RawSampleReader.readU16LE(
                    toBytes(plane.buffer), plane.rowStride, plane.pixelStride,
                    size.width, size.height
                )
                if (crop != null) MipiPacker.packCropped(samples, size.width, crop)
                else MipiPacker.pack(samples)
            }
            else -> {
                // Expanded-LSB (no pack): cropped take falls back to full-frame
                // storage — crop requires MIPI packing (framingCrop is only
                // computed when canPack).
                val buf = plane.buffer
                buf.position(0)
                if (RawPackNative.loaded) {
                    RawPackNative.expandCopyDirect(
                        buf, plane.rowStride, plane.pixelStride, size.width, size.height
                    ) ?: leBytes(
                        RawSampleReader.readU16LE(
                            toBytes(buf), plane.rowStride, plane.pixelStride,
                            size.width, size.height
                        )
                    )
                } else {
                    leBytes(
                        RawSampleReader.readU16LE(
                            toBytes(buf), plane.rowStride, plane.pixelStride,
                            size.width, size.height
                        )
                    )
                }
            }
        }
    }

    private fun toBytes(buf: ByteBuffer): ByteArray {
        buf.position(0)
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private fun totalDropped(): Long =
        droppedInQ.get() + droppedMidQ.get() + droppedOutQ.get() + (engine?.stats?.droppedOnAcquire ?: 0)

    private fun onFrameCaptured(
        q: ArrayBlockingQueue<QueuedImage>,
        img: Image,
        meta: TotalCaptureResult?,
        startRealtime: Long
    ) {
        val tsNs = try {
            img.timestamp
        } catch (_: Exception) {
            runCatching { img.close() }
            return
        }
        if (stopping) {
            runCatching { img.close() }
            return
        }
        val expNs = meta?.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val iso = meta?.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: 0
        while (!q.offer(QueuedImage(img, tsNs, expNs, iso))) {
            val evicted = q.poll()
            runCatching { evicted?.image?.close() }
            droppedInQ.incrementAndGet()
        }
        capturedCounter.incrementAndGet()
        lastFrameTimestamp = tsNs
        maybePublishStats(SystemClock.elapsedRealtime())
    }

    private var firstTsForFps = 0L
    private fun avgFpsOf(ts: Long): Double {
        if (firstTsForFps == 0L) firstTsForFps = ts
        val count = capturedCounter.get()
        return if (ts > firstTsForFps) count * 1e9 / (ts - firstTsForFps) else 0.0
    }

    private fun maybePublishStats(now: Long, force: Boolean = false) {
        if (!force && now - lastUiPublishMs < 100L) return
        lastUiPublishMs = now
        synchronized(statsLock) {
            val s = _stats.value
            _stats.value = s.copy(
                framesCaptured = capturedCounter.get(),
                framesWritten = writtenCounter.get(),
                bytesWritten = bytesCounter.get(),
                rawBytesEstimate = rawEstimateCounter.get(),
                dropped = totalDropped(),
                avgFps = avgFpsOf(lastFrameTimestamp),
                elapsedMs = if (activeStartRealtime > 0) now - activeStartRealtime else s.elapsedMs,
                thermalStage = activeThermalStage.name
            )
        }
    }

    private fun publishProgress() {
        maybePublishStats(SystemClock.elapsedRealtime())
    }

    private fun leBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var o = 0
        for (s in samples) {
            val v = s.toInt()
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v shr 8) and 0xFF).toByte()
            o += 2
        }
        return out
    }

    private fun runWriterLoop(
        file: File,
        outQ: ArrayBlockingQueue<ProcessedItem>,
        size: Size,
        whiteLevel: Int,
        cfa: Int,
        cameraId: String,
        startRealtime: Long,
        codec: FrameCodec,
        packing: Int,
        audioMeta: String?,
        blackLevel: IntArray = intArrayOf(0, 0, 0, 0),
        colorMatrix: FloatArray = dev.rawrec.app.control.SensorCalibration.identityMatrix(),
        asShotNeutral: FloatArray = floatArrayOf(1f, 1f, 1f),
        calibIlluminant1: Int = dev.rawrec.app.control.SensorCalibration.DEFAULT_ILLUMINANT,
        tsSource: Int = 0,
        framingAspect: Float? = null,
        framingCrop: IntArray? = null,
        targetFps: Double = 30.0,
        lensCalibration: FloatArray? = null,
        lensDistortion: FloatArray? = null,
        opticalBlack: List<IntArray>? = null,
        lensShadingJson: JSONObject? = null,
        calibIlluminant2: Int? = null,
        colorMatrix2: FloatArray? = null,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        noiseProfile: DoubleArray? = null
    ) {
        var written = 0L
        var firstFrameTs: Long? = null
        var firstAudioTs: Long? = null
        val fpsMilli = Math.round(targetFps * 1000.0).toInt().coerceAtLeast(1)
        val nominalPeriodNs = if (fpsMilli > 0) (1_000_000_000_000L / fpsMilli) else 33_333_333L
        var lastFrameTsNs = 0L
        var gapCount = 0

        val initialMetaObj = JSONObject().apply {
            put("cameraId", cameraId)
            put("packedMipi10", packing == Rvsp.PACKING_MIPI_PACKED)
            put("calibIlluminant1", calibIlluminant1)
            calibIlluminant2?.let { put("calibIlluminant2", it) }
            colorMatrix2?.let { put("colorMatrix2", org.json.JSONArray(it.toList())) }
            forwardMatrix1?.let { put("forwardMatrix1", org.json.JSONArray(it.toList())) }
            forwardMatrix2?.let { put("forwardMatrix2", org.json.JSONArray(it.toList())) }
            noiseProfile?.let { put("noiseProfile", org.json.JSONArray(it.toList())) }
            put("timestampSource", tsSource)
            put("targetFps", targetFps)
            put("totalDropped", 0L)
            put("droppedGaps", 0)
            framingAspect?.let { put("framingAspect", it.toDouble()) }
            framingCrop?.let {
                put("cropRegion", org.json.JSONArray(it.toList()))
            }
            audioMeta?.let {
                val p = it.split("/")
                put("audioSampleRate", p.getOrNull(0)?.toIntOrNull() ?: 0)
                put("audioChannels", p.getOrNull(1)?.toIntOrNull() ?: 0)
                put("audioFormat", p.getOrNull(2) ?: "pcm16")
            }
            lensCalibration?.let { put("lensCalibration", org.json.JSONArray(it.toList())) }
            lensDistortion?.let { put("lensDistortion", org.json.JSONArray(it.toList())) }
            opticalBlack?.let { regions ->
                val arr = org.json.JSONArray()
                for (r in regions) arr.put(org.json.JSONArray(r.toList()))
                put("opticalBlack", arr)
            }
            lensShadingJson?.let { put("lensShading", it) }
        }
        val initialMetaRaw = initialMetaObj.toString()
        val rawBytesLen = initialMetaRaw.toByteArray(Charsets.UTF_8).size
        // Dynamically allocate padding: ensure padding is at least 16384 bytes,
        // or raw string size + 4096 bytes headroom, so padEnd never truncates and
        // final drop/gap counters patch accurately.
        val metaPaddingBytes = maxOf(16384, ((rawBytesLen + 4096 + 511) / 512) * 512)
        val initialMeta = initialMetaRaw.padEnd(metaPaddingBytes, ' ')

        try {
            FileOutputStream(file).use { fos ->
                CountingStream(fos).use { counting ->
                    val header = RvspHeader(
                        width = size.width,
                        height = size.height,
                        bitDepth = bitDepthFromWhiteLevel(whiteLevel),
                        cfaPattern = cfa,
                        packing = packing,
                        videoCodec = codec.id,
                        whiteLevel = whiteLevel,
                        blackLevel = blackLevel,
                        colorMatrix = colorMatrix,
                        asShotNeutral = asShotNeutral,
                        nominalFpsMilli = fpsMilli,
                        createdUnixUs = System.currentTimeMillis() * 1000L,
                        cameraModel = android.os.Build.MODEL.take(63),
                        lensId = cameraId.take(31),
                        metaJson = initialMeta
                    )
                    RvspWriter(counting, header, codec).use { w ->
                        val sampleCount = size.width * size.height
                        var lastLog = 0L
                        while (!stopping || outQ.isNotEmpty() || audioQ?.isNotEmpty() == true) {
                            while (true) {
                                val a = audioQ?.poll() ?: break
                                if (firstAudioTs == null) firstAudioTs = a.tsNs
                                w.writeAudioChunk(a.pcm, a.tsNs)
                            }
                            val item = outQ.poll(50, TimeUnit.MILLISECONDS)
                            if (item == null) {
                                if (stopping && outQ.isEmpty() && audioQ?.isEmpty() != false) break
                                continue
                            }
                            if (firstFrameTs == null) firstFrameTs = item.tsNs
                            if (lastFrameTsNs > 0L && item.tsNs > lastFrameTsNs) {
                                val deltaNs = item.tsNs - lastFrameTsNs
                                if (deltaNs > 1.5 * nominalPeriodNs) {
                                    gapCount++
                                }
                            }
                            lastFrameTsNs = item.tsNs
                            w.writeFrameRaw(item.encoded, item.tsNs, item.expNs, item.iso)
                            written++
                            writtenCounter.set(written)
                            bytesCounter.set(counting.count + Rvsp.HEADER_SIZE)
                            val rawFrameBytes = if (packing == Rvsp.PACKING_MIPI_PACKED)
                                MipiPacker.packedSize(sampleCount).toLong()
                            else sampleCount * 2L
                            rawEstimateCounter.set(written * rawFrameBytes)

                            val now = SystemClock.elapsedRealtime()
                            maybePublishStats(now)
                            if (now - lastLog >= 1000) {
                                lastLog = now
                                val s = _stats.value
                                AppLog.i(
                                    TAG,
                                    "writer: f=%d outQ=%d drop=[in=%d mid=%d out=%d acq=%d] %.1fMB ratio=%.0f%%"
                                        .format(
                                            written, outQ.size,
                                            droppedInQ.get(), droppedMidQ.get(), droppedOutQ.get(),
                                            engine?.stats?.droppedOnAcquire ?: 0,
                                            s.bytesWritten / 1e6, s.ratio * 100
                                        )
                                )
                                // Active streaming check: auto-stop if storage >= 95% full
                                runCatching {
                                    val stat = android.os.StatFs(file.parentFile?.absolutePath ?: recordingsDir().absolutePath)
                                    val total = stat.totalBytes
                                    val avail = stat.availableBytes
                                    if (total > 0L && (total - avail).toDouble() / total >= 0.95) {
                                        val usedPct = (total - avail).toDouble() / total * 100
                                        val pctStr = "%.1f".format(usedPct)
                                        AppLog.w(TAG, "Storage is $pctStr% full (limit 95%) — auto-stopping recording")
                                        Thread({ stop() }, "rvsp-storage-autostop").start()
                                        synchronized(statsLock) {
                                            _stats.value = _stats.value.copy(error = "Storage 95% full — auto-stopped")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "writer failed", t)
            synchronized(statsLock) {
                _stats.value = _stats.value.copy(error = "writer failed: ${t.message}")
            }
        } finally {
            synchronized(statsLock) {
                _stats.value = _stats.value.copy(
                    active = false,
                    framesWritten = written,
                    elapsedMs = SystemClock.elapsedRealtime() - startRealtime
                )
            }
            AppLog.i(
                TAG, "writer done: frames=%d avgFrameKB=%.0f"
                    .format(written, if (written > 0) _stats.value.bytesWritten / 1e3 / written else 0.0)
            )
            if (written > 0) {
                AppLog.i(
                    TAG,
                    "colorimetry: black=${blackLevel.toList()} illuminant=$calibIlluminant1 " +
                        "tsSource=$tsSource firstFrameTs=${firstFrameTs ?: "n/a"} " +
                        "firstAudioTs=${firstAudioTs ?: "n/a"}"
                )
            }
            try {
                val finalDropped = totalDropped()
                val finalMetaObj = JSONObject(initialMetaObj.toString()).apply {
                    put("totalDropped", finalDropped)
                    put("droppedGaps", gapCount)
                    put("framesCaptured", _stats.value.framesCaptured)
                    put("framesWritten", written)
                }
                val finalMetaRaw = finalMetaObj.toString()
                val finalBytes = finalMetaRaw.padEnd(metaPaddingBytes, ' ').toByteArray(Charsets.UTF_8)
                if (finalBytes.size == metaPaddingBytes && file.exists()) {
                    java.io.RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(Rvsp.HEADER_SIZE.toLong())
                        raf.write(finalBytes)
                    }
                    AppLog.i(TAG, "patched final metadata into ${file.name}: dropped=$finalDropped gaps=$gapCount frames=$written")
                } else {
                    AppLog.e(TAG, "metadata patch size mismatch: expected $metaPaddingBytes but got ${finalBytes.size}")
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "failed to patch final drop metadata into ${file.name}: ${t.message}")
            }
        }
    }

    private fun bitDepthFromWhiteLevel(whiteLevel: Int): Int = when {
        whiteLevel <= 255 -> 8
        whiteLevel <= 1023 -> 10
        whiteLevel <= 4095 -> 12
        whiteLevel <= 16383 -> 14
        else -> 16
    }

    private fun publish(s: () -> RecStats) {
        synchronized(statsLock) { _stats.value = s() }
    }

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            inner.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len); count += len
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "RawRec"
        private const val INBOUND_CAPACITY = 6
        private const val MID_CAPACITY = 16
        private const val OUTBOUND_CAPACITY = 32
        // Single-threaded Zstd per frame context (nbWorkers = 0) avoids multiplying
        // threads by workerPool count (preventing 20-thread CPU oversubscription).
        private const val INNER_ZSTD_WORKERS = 0
        private const val PROXY_FACTOR = 4
    }
}
