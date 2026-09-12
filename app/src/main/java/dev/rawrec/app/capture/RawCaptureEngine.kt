package dev.rawrec.app.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import dev.rawrec.app.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FrameStats(
    var count: Long = 0,
    var droppedOnAcquire: Long = 0,
    var avgFps: Double = 0.0,
    var resultPaired: Long = 0,
    var resultUnpaired: Long = 0
)

/**
 * Decides whether a TotalCaptureResult belongs to the Image being dequeued,
 * by comparing the result's sensor timestamp with the image's.
 *
 * Contract (unit-tested in ResultPairingTest):
 *  - ACCEPT when the timestamps match within half a frame period.
 *  - RESULT_STALE when the latched result is older than the image: discard
 *    it, the record carries the spec's 0 = unknown sentinel.
 *  - RESULT_AHEAD when the latched result is newer: keep it latched for the
 *    next image (it belongs to a frame not delivered yet).
 *  - UNKNOWN when the HAL does not report SENSOR_TIMESTAMP: fall back to
 *    the legacy unvalidated latest-wins behavior.
 */
object ResultPairing {
    const val UNKNOWN = 0
    const val ACCEPT = 1
    const val RESULT_STALE = 2
    const val RESULT_AHEAD = 3

    fun decide(imageTs: Long, resultTs: Long?, framePeriodNs: Long): Int {
        if (resultTs == null) return UNKNOWN
        val delta = resultTs - imageTs
        return when {
            kotlin.math.abs(delta) <= framePeriodNs / 2 -> ACCEPT
            delta < 0 -> RESULT_STALE
            else -> RESULT_AHEAD
        }
    }
}

class RawCaptureEngine(
    private val context: Context,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val physicalCameraId: String? = null,
    val transferImageOwnership: Boolean = false,
    val maxImages: Int = MAX_IMAGES,
    private val onFrame: (image: Image, metadata: TotalCaptureResult?) -> Unit
) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("rawrec-capture").apply {
        start()
        // Callback delivery delay is a first-order drop cause on a contended
        // SoC — the camera HAL shares the prime core with our zstd workers.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
    }
    private val handler = Handler(thread.looper)

    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession
    private lateinit var reader: ImageReader

    val stats = FrameStats()
    private var firstTimestampNs = 0L

    @Volatile
    private var activePreviewSurface: Surface? = null

    /** Latest unconsumed capture result, latched for the next image. */
    @Volatile
    private var latestResult: TotalCaptureResult? = null

    @Volatile private var targetFps: Double = 30.0

    @SuppressLint("MissingPermission")
    suspend fun start(
        controls: dev.rawrec.app.control.CameraControlState,
        previewSurface: Surface? = null
    ) {
        check(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) { "CAMERA permission required" }

        activePreviewSurface = previewSurface
        targetFps = controls.targetFps
        val chars = cm.getCameraCharacteristics(cameraId)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        reader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, maxImages)
        var loggedFirst = false
        reader.setOnImageAvailableListener({ r ->
            // OnImageAvailableListener is edge-triggered: it only fires when the
            // queue transitions to non-empty. We must drain all available images
            // in a loop until acquireNextImage returns null; otherwise if frames arrive
            // in a burst during high CPU load, unacquired frames remain in the queue
            // and the listener stops firing, permanently stalling the camera HAL.
            while (true) {
                val img = try {
                    r.acquireNextImage() ?: break
                } catch (_: IllegalStateException) {
                    stats.droppedOnAcquire++
                    break
                }

                var meta: TotalCaptureResult? = null
                val latched = latestResult
                val resultTs = latched?.get(TotalCaptureResult.SENSOR_TIMESTAMP)
                val framePeriodNs = if (targetFps > 0.0) (1_000_000_000.0 / targetFps).toLong() else 0L
                when (ResultPairing.decide(img.timestamp, resultTs, framePeriodNs)) {
                    ResultPairing.ACCEPT, ResultPairing.UNKNOWN -> {
                        meta = latched
                        latestResult = null
                        stats.resultPaired++
                    }
                    ResultPairing.RESULT_STALE -> {
                        latestResult = null
                        stats.resultUnpaired++
                    }
                    ResultPairing.RESULT_AHEAD -> {
                        // keep latched; this image's metadata is unknown
                        stats.resultUnpaired++
                    }
                }
                if (firstTimestampNs == 0L) firstTimestampNs = img.timestamp
                stats.count++
                stats.avgFps = if (img.timestamp > firstTimestampNs) {
                    stats.count * 1e9 / (img.timestamp - firstTimestampNs)
                } else 0.0
                if (!loggedFirst) {
                    loggedFirst = true
                    AppLog.i(TAG, "first frame: ${width}x$height ts=${img.timestamp} " +
                        "rowStride=${img.planes[0].rowStride} pixelStride=${img.planes[0].pixelStride}")
                }
                try {
                    onFrame(img, meta)
                } finally {
                    if (!transferImageOwnership) img.close()
                }
            }
        }, handler)

        camera = openCamera()
        AppLog.d(TAG, "camera $cameraId opened")
        val outputs = mutableListOf<Surface>(reader.surface)
        if (previewSurface != null && previewSurface.isValid) {
            outputs.add(previewSurface)
        }
        session = createSession(camera, outputs, physicalCameraId)
        AppLog.d(TAG, "session configured (${width}x$height RAW_SENSOR + preview=${previewSurface != null} phys=$physicalCameraId)")

        // One builder shared by start() and updateControls(): the recording
        // session honors the user's controls from frame 0, and the two paths
        // can no longer drift apart.
        val builder = buildRequest(controls, exposureRange, isoRange)
        targetFps = controls.targetFps
        session.setRepeatingRequest(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    latestResult = result
                }
            },
            handler
        )
    }

    /**
     * Single source of truth for the recording request. Manual mode sets
     * AE/CONTROL off with the explicit exposure/ISO; auto mode lets the HAL's
     * 3A run. Frame duration follows the project FPS in both modes.
     */
    private fun buildRequest(
        state: dev.rawrec.app.control.CameraControlState,
        exposureRange: Range<Long>?,
        isoRange: Range<Int>?
    ): CaptureRequest.Builder {
        targetFps = state.targetFps
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(reader.surface)
            activePreviewSurface?.let { ps ->
                if (ps.isValid) addTarget(ps)
            }

            if (state.autoExposure) {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                val expNs = state.effectiveExposureNs
                exposureRange?.let { r ->
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs.coerceIn(r.lower, r.upper))
                }
                isoRange?.let { r ->
                    set(CaptureRequest.SENSOR_SENSITIVITY, state.iso.coerceIn(r.lower, r.upper))
                }
            }

            // Manual mode: pin frame duration to the project FPS. Auto mode:
            // set only the AE fps-range target — the F6 HAL throttles RAW
            // readout when SENSOR_FRAME_DURATION is forced alongside AE
            // (measured ~14fps vs 30fps on rec_20260829_211158).
            val frameDurationNs = (1_000_000_000.0 / state.targetFps).toLong()
            val targetFpsInt = kotlin.math.round(state.targetFps).toInt().coerceAtLeast(1)
            if (!state.autoExposure) {
                set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(targetFpsInt, targetFpsInt)
                )
            } else {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFpsInt, targetFpsInt))
            }

            if (state.autoFocus) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDiopters)
            }

            if (state.autoWhiteBalance) {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                val gains = state.effectiveRgbGains
                set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[1], gains[2])
                )
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val chars = cm.getCameraCharacteristics(cameraId)
                val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val maxSizes = maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
                if (maxSizes.any { it.width == width && it.height == height }) {
                    set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                }
            }

            // Apply vendor-specific camera tags (Qualcomm / Xiaomi HyperOS / MediaTek)
            VendorTags.applyOptimizations(this, cm.getCameraCharacteristics(cameraId))
        }
    }

    fun updateControls(state: dev.rawrec.app.control.CameraControlState) {
        if (!::session.isInitialized || !::camera.isInitialized) return
        val chars = cm.getCameraCharacteristics(cameraId)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val builder = buildRequest(state, exposureRange, isoRange)

        runCatching {
            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        latestResult = result
                    }
                },
                handler
            )
        }
    }

    @Volatile private var closedLatch: java.util.concurrent.CountDownLatch? = null

    fun stop() {
        val latch = java.util.concurrent.CountDownLatch(1)
        closedLatch = latch
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        runCatching { camera.close() }
        runCatching { latch.await(350, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { reader.close() }
        thread.quitSafely()
    }

    private class CameraOpenException(val errorCode: Int, message: String) : Exception(message)

    @SuppressLint("MissingPermission")
    private suspend fun openCameraOnce(): CameraDevice = suspendCancellableCoroutine { cont ->
        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (cont.isActive) cont.resume(device)
            }

            override fun onClosed(device: CameraDevice) {
                AppLog.d(TAG, "capture camera $cameraId onClosed confirmed")
                closedLatch?.countDown()
            }

            override fun onDisconnected(device: CameraDevice) {
                AppLog.w(TAG, "camera $cameraId disconnected")
                device.close()
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                AppLog.e(TAG, "camera $cameraId error code=$error")
                device.close()
                if (cont.isActive) {
                    cont.resumeWithException(CameraOpenException(error, "camera error $error"))
                }
            }
        }, handler)
    }

    private suspend fun openCamera(): CameraDevice {
        var attempts = 0
        while (true) {
            attempts++
            try {
                return openCameraOnce()
            } catch (e: CameraOpenException) {
                // Error 1 = ERROR_CAMERA_IN_USE, Error 2 = ERROR_MAX_CAMERAS_IN_USE,
                // Error 4 = ERROR_CAMERA_DEVICE (temporary driver busy during session transition).
                val isHandoverRetryable = e.errorCode == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        e.errorCode == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                        e.errorCode == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                if (isHandoverRetryable && attempts <= 3) {
                    AppLog.w(TAG, "camera $cameraId error ${e.errorCode} during handover, retrying ($attempts/3)...")
                    kotlinx.coroutines.delay(80L * attempts)
                } else {
                    throw IllegalStateException("camera error ${e.errorCode}", e)
                }
            }
        }
    }

    private suspend fun createSession(
        device: CameraDevice,
        outputs: List<Surface>,
        physicalCameraId: String? = null
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cont.isActive) cont.resume(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("session configuration failed"))
                }
            }
        }

        if (physicalCameraId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val outputConfigs = outputs.map { surface ->
                android.hardware.camera2.params.OutputConfiguration(surface).apply {
                    setPhysicalCameraId(physicalCameraId)
                }
            }
            val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                java.util.concurrent.Executor { command -> handler.post(command) },
                callback
            )
            device.createCaptureSession(sessionConfig)
        } else {
            device.createCaptureSession(outputs, callback, handler)
        }
    }

    companion object {
        private const val TAG = "RawRec"
        const val MAX_IMAGES = 5
    }
}
