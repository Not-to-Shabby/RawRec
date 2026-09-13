package dev.rawrec.app.scopes

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-allocation background analyzer for live viewfinder scopes.
 *
 * Taps downscaled 240x180 frames from TextureView at 15-20 FPS on a background
 * thread with double-buffered memory. Powers the real-time Histogram, Focus Peaking,
 * False Color, and Zebra stripes with zero impact on the 30 fps RAW capture pipeline.
 */
class ScopeAnalyzer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        const val WIDTH = 240
        const val HEIGHT = 180
        private const val SAMPLE_INTERVAL_MS = 60L // ~16 FPS
    }

    // Ping-pong bitmaps between UI thread (capture) and worker thread (analysis)
    private var captureBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private var analysisBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    // Pre-allocated reusable arrays (zero GC allocations during playback)
    private val pixels = IntArray(WIDTH * HEIGHT)
    private val luma = ByteArray(WIDTH * HEIGHT)
    private val overlayPixels = IntArray(WIDTH * HEIGHT)
    private val tempBuffer = IntArray(WIDTH * HEIGHT)
    private val resultBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    private val isProcessing = AtomicBoolean(false)
    private var lastSampleTimeMs = 0L
    private var animPhase = 0
    private var activeJob: Job? = null

    // Exposed flows for Compose UI
    private val _histogram = MutableStateFlow<HistogramData?>(null)
    val histogram: StateFlow<HistogramData?> = _histogram.asStateFlow()

    private val _overlay = MutableStateFlow<ImageBitmap?>(null)
    val overlay: StateFlow<ImageBitmap?> = _overlay.asStateFlow()

    /**
     * Called from TextureView.SurfaceTextureListener.onSurfaceTextureUpdated
     * on the UI thread.
     */
    fun onFrameAvailable(
        tv: TextureView,
        histogramActive: Boolean,
        peakingActive: Boolean,
        falseColorActive: Boolean,
        zebrasActive: Boolean
    ) {
        // If no scopes are enabled, skip entirely with zero CPU overhead
        if (!histogramActive && !peakingActive && !falseColorActive && !zebrasActive) {
            if (_histogram.value != null) _histogram.value = null
            if (_overlay.value != null) _overlay.value = null
            return
        }

        val hasOverlay = peakingActive || falseColorActive || zebrasActive
        // When only histogram is active, 250ms (4 FPS) is plenty and avoids GPU readback contention.
        // Full overlay scopes sample at 100ms (10 FPS).
        val interval = if (hasOverlay) 100L else 250L

        val now = System.currentTimeMillis()
        if (now - lastSampleTimeMs < interval) return
        if (isProcessing.get()) return // Drop frame if previous analysis is still running

        lastSampleTimeMs = now

        // Fast downscaled capture from TextureView on UI thread (< 0.5ms)
        tv.getBitmap(captureBitmap)

        // Swap ping-pong buffers
        val tmp = captureBitmap
        captureBitmap = analysisBitmap
        analysisBitmap = tmp

        isProcessing.set(true)
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                processFrame(histogramActive, peakingActive, falseColorActive, zebrasActive)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /**
     * Called from GlViewfinderRenderer with GPU-readback ARGB pixels to compute live histogram.
     */
    fun onPixelsAvailable(
        srcPixels: IntArray,
        histogramActive: Boolean
    ) {
        if (!histogramActive) {
            if (_histogram.value != null) _histogram.value = null
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSampleTimeMs < 100L) return
        if (isProcessing.get()) return

        lastSampleTimeMs = now
        System.arraycopy(srcPixels, 0, pixels, 0, minOf(srcPixels.size, pixels.size))

        isProcessing.set(true)
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val hist = CinemaScopes.computeHistogram(pixels, sampleStep = 1)
                _histogram.value = hist
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun processFrame(
        histogramActive: Boolean,
        peakingActive: Boolean,
        falseColorActive: Boolean,
        zebrasActive: Boolean
    ) {
        // Extract ARGB pixels from analysis bitmap
        analysisBitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

        // 1. Histogram Scope
        if (histogramActive) {
            val hist = CinemaScopes.computeHistogram(pixels, sampleStep = 1)
            _histogram.value = hist
        } else {
            if (_histogram.value != null) _histogram.value = null
        }

        // 2. Video Overlay Scopes (Peaking, False Color, Zebras)
        val hasOverlayScope = peakingActive || falseColorActive || zebrasActive
        if (!hasOverlayScope) {
            if (_overlay.value != null) _overlay.value = null
            return
        }

        // Compute BT.709 Luminance bytes
        CinemaScopes.argbToLuma(pixels, WIDTH, HEIGHT, luma)

        // Base layer: False Color heatmap OR transparent
        if (falseColorActive) {
            CinemaScopes.applyFalseColor(luma, WIDTH, HEIGHT, overlayPixels)
        } else {
            overlayPixels.fill(0) // 0x00000000 transparent background
        }

        // Mid layer: Zebras on blown highlights
        if (zebrasActive) {
            animPhase = (animPhase + 1) % 16
            CinemaScopes.applyZebrasTransparent(
                luma = luma,
                width = WIDTH,
                height = HEIGHT,
                thresholdIre = 95,
                stripeWidth = 6,
                animPhase = animPhase,
                outBuffer = tempBuffer
            )
            for (i in 0 until WIDTH * HEIGHT) {
                val z = tempBuffer[i]
                if (z != 0) {
                    overlayPixels[i] = z
                }
            }
        }

        // Top layer: Focus Peaking neon green edge highlights
        if (peakingActive) {
            CinemaScopes.applyFocusPeakingTransparent(
                luma = luma,
                width = WIDTH,
                height = HEIGHT,
                threshold = 32,
                highlightArgb = 0xFF00FF00.toInt(),
                outBuffer = tempBuffer
            )
            for (i in 0 until WIDTH * HEIGHT) {
                val p = tempBuffer[i]
                if (p != 0) {
                    overlayPixels[i] = p
                }
            }
        }

        // Write combined overlay pixels to result bitmap and publish ImageBitmap
        resultBitmap.setPixels(overlayPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        _overlay.value = resultBitmap.asImageBitmap()
    }

    fun release() {
        activeJob?.cancel()
        _histogram.value = null
        _overlay.value = null
    }
}
