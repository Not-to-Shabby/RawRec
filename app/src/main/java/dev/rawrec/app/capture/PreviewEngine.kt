package dev.rawrec.app.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.rawrec.app.control.CameraControlState
import dev.rawrec.app.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PreviewEngine(
    private val context: Context,
    val cameraId: String,
    val physicalCameraId: String? = null,
    private val previewSurface: Surface,
    private val onCameraLost: (() -> Unit)? = null
) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("rawrec-preview").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession
    private var lastControls: CameraControlState? = null
    @Volatile private var isClosed = false
    @Volatile private var closedLatch: java.util.concurrent.CountDownLatch? = null

    /**
     * Checks if this preview engine is still alive, open, and bound to the
     * exact specified camera ID, optional physical sub-camera ID, and active Surface.
     */
    fun isAliveAndMatching(targetCameraId: String, targetPhysicalId: String? = null, targetSurface: Surface): Boolean =
        !isClosed &&
        !restarting &&
        cameraId == targetCameraId &&
        physicalCameraId == targetPhysicalId &&
        previewSurface == targetSurface &&
        previewSurface.isValid &&
        ::camera.isInitialized &&
        ::session.isInitialized

    /**
     * First completed result from the preview stream, latched once so the
     * recorder can seed AsShotNeutral from real AWB gains at header time
     * (before its own recording session delivers any frames).
     */
    @Volatile var firstCaptureResult: android.hardware.camera2.TotalCaptureResult? = null
        private set

    @SuppressLint("MissingPermission")
    suspend fun start(controls: CameraControlState) {
        check(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) { "CAMERA permission required" }

        camera = openCamera()
        AppLog.d(TAG, "preview camera $cameraId opened")
        session = createSession(camera, listOf(previewSurface))
        AppLog.d(TAG, "preview session configured on surface")

        updateControls(controls)
    }

    fun updateControls(state: CameraControlState) {
        if (!::session.isInitialized || !::camera.isInitialized || !previewSurface.isValid) return
        lastControls = state
        val chars = cm.getCameraCharacteristics(cameraId)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)

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

            val frameDurationNs = (1_000_000_000.0 / state.targetFps).toLong()
            set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

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
        }

        runCatching {
            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        if (firstCaptureResult == null) firstCaptureResult = result
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        AppLog.w(TAG, "preview capture error (frame dropped)")
                    }
                },
                handler
            )
        }
    }

    fun stop() {
        isClosed = true
        restarting = false
        val latch = java.util.concurrent.CountDownLatch(1)
        closedLatch = latch
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        runCatching { camera.close() }
        // Wait for camera HAL to confirm hardware release before quitting thread
        // and letting next engine open the camera.
        runCatching { latch.await(350, java.util.concurrent.TimeUnit.MILLISECONDS) }
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                isClosed = false
                if (cont.isActive) cont.resume(device)
            }

            override fun onClosed(device: CameraDevice) {
                AppLog.d(TAG, "preview camera $cameraId onClosed confirmed")
                isClosed = true
                closedLatch?.countDown()
            }

            override fun onDisconnected(device: CameraDevice) {
                AppLog.w(TAG, "preview camera $cameraId disconnected")
                isClosed = true
                device.close()
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("camera disconnected"))
                } else {
                    onCameraLost?.invoke()
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                AppLog.e(TAG, "preview camera $cameraId error $error")
                isClosed = true
                device.close()
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("camera error $error"))
                } else {
                    onCameraLost?.invoke()
                }
            }
        }, handler)
    }

    /** Restart after external eviction (Google Assistant steals camera: error 3). Single-flight. */
    @Volatile private var restarting = false
    fun restart() {
        if (restarting || isClosed) return
        restarting = true
        val controls = lastControls
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        runCatching { camera.close() }
        Thread({
            var attempt = 0
            while (attempt < 8 && !isClosed) {
                try {
                    kotlinx.coroutines.runBlocking {
                        camera = openCamera()
                        session = createSession(camera, listOf(previewSurface))
                    }
                    if (controls != null) updateControls(controls)
                    AppLog.i(TAG, "preview recovered after ${attempt + 1} attempt(s)")
                    restarting = false
                    return@Thread
                } catch (t: Throwable) {
                    attempt++
                    AppLog.w(TAG, "preview recovery attempt $attempt failed: ${t.message}")
                    try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) { restarting = false; return@Thread }
                }
            }
            AppLog.e(TAG, "preview recovery gave up after $attempt attempts")
            isClosed = true
            restarting = false
        }, "rawrec-preview-recover").start()
    }

    private suspend fun createSession(
        device: CameraDevice,
        outputs: List<Surface>
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cont.isActive) cont.resume(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("preview session configuration failed"))
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
        private const val TAG = "RawRec-Preview"
    }
}
