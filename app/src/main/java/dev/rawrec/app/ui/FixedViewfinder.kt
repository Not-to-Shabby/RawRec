package dev.rawrec.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Chassis-locked viewfinder: a SEPARATE ENTITY from the rotating UI.
 *
 * The activity window (and all UI chrome: HUD, pills, nav bar) rotates with
 * device orientation. This viewfinder does NOT: the preview container is
 * counter-rotated by -(window rotation), so on the physical screen the image
 * stays exactly where it was — fixed to the phone chassis, like a dedicated
 * camera's sensor monitor. Rotating the device changes what the sensor sees
 * (the world rotates), but the viewfinder presentation never moves.
 *
 * Scale modes (user-selectable):
 *  - FILL: cover-crop — image fills the whole view (cinema full-bleed).
 *  - FIT:  letterbox — whole image visible, black bars top/bottom or sides.
 *          With [aspectLimit], the image is first constrained to that ratio
 *          (sensor 4:3 crop inside e.g. 2.39:1), then letterboxed into view.
 *
 * - Producer buffer fixed (supported-size chosen); session NEVER rebuilt.
 * - Transform independent of display rotation (sensor-locked, 0° default).
 * - Buffer-reset trap handled on every size change.
 * - [rotationOverride] + [onStateChanged] serve the Settings debug card.
 */
@Composable
fun FixedViewfinder(
    modifier: Modifier = Modifier,
    supportedPreviewSizes: List<android.util.Size> = emptyList(),
    sensorOrientation: Int = 90,
    scaleMode: ViewfinderMath.ScaleMode = ViewfinderMath.ScaleMode.FILL,
    fillFraction: Float = if (scaleMode == ViewfinderMath.ScaleMode.FILL) 1f else 0f,
    aspectLimit: Float? = null,
    rotationOverride: Int? = null,
    uiRotation: Int = 0,
    orientationMode: ViewfinderMath.OrientationMode = ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE,
    rawAspect: Double = 4.0 / 3.0,
    onPreviewSurfaceAvailable: ((Surface?) -> Unit)? = null,
    onStateChanged: ((Int, Int, Int, Int, Int) -> Unit)? = null,
    onFrameUpdated: ((TextureView) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val wm = remember(context) {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    val buf = remember(supportedPreviewSizes, rawAspect) {
        chooseBufferSize(supportedPreviewSizes, rawAspect)
    }
    // Remember scale mode for the transform lambda (holder pattern, like rotation)
    val scaleState = remember { ScaleHolder(scaleMode, aspectLimit, fillFraction) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    wire(tv, buf.width, buf.height, sensorOrientation, wm,
                        uiRotation, orientationMode, scaleState, onPreviewSurfaceAvailable, onStateChanged, onFrameUpdated)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { tv ->
                (tv.tag as? Holder)?.let { h ->
                    h.override = rotationOverride
                    h.uiRotation = uiRotation
                    h.mode = orientationMode
                    h.onState = onStateChanged
                    h.onFrameUpdated = onFrameUpdated
                }
                scaleState.mode = scaleMode
                scaleState.aspect = aspectLimit
                scaleState.fillFraction = fillFraction
                applyTransform(tv, buf.width, buf.height, sensorOrientation,
                    wm, rotationOverride, uiRotation, scaleState, orientationMode, onStateChanged)
            }
        )
        overlay()
    }
}

private class Holder(
    @Volatile var override: Int?,
    @Volatile var uiRotation: Int,
    @Volatile var mode: ViewfinderMath.OrientationMode,
    @Volatile var onState: ((Int, Int, Int, Int, Int) -> Unit)?,
    @Volatile var onFrameUpdated: ((TextureView) -> Unit)?
)

private class ScaleHolder(
    @Volatile var mode: ViewfinderMath.ScaleMode,
    @Volatile var aspect: Float?,
    @Volatile var fillFraction: Float = 0f
)

private fun wire(
    tv: TextureView,
    bufW: Int,
    bufH: Int,
    sensorOrientation: Int,
    wm: WindowManager,
    uiRotation: Int,
    mode: ViewfinderMath.OrientationMode,
    scale: ScaleHolder,
    cb: ((Surface?) -> Unit)?,
    onState: ((Int, Int, Int, Int, Int) -> Unit)?,
    onFrameUpdated: ((TextureView) -> Unit)?
) {
    val holder = Holder(null, uiRotation, mode, onState, onFrameUpdated)
    tv.tag = holder

    fun reapply() {
        applyTransform(tv, bufW, bufH, sensorOrientation, wm, holder.override, holder.uiRotation, scale, holder.mode, holder.onState)
    }

    // Layout-driven application: the factory runs before first layout
    // (tv.width == 0 -> degenerate 1x1 view transform), and nothing else
    // re-fires after a window re-layout. Re-apply whenever the view has
    // real dimensions and its size changed. Idempotent per size.
    tv.viewTreeObserver.addOnGlobalLayoutListener(
        object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            private var lastW = -1
            private var lastH = -1
            override fun onGlobalLayout() {
                if (tv.width > 0 && tv.height > 0 &&
                    (tv.width != lastW || tv.height != lastH)
                ) {
                    lastW = tv.width
                    lastH = tv.height
                    reapply()
                }
            }
        }
    )

    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            st.setDefaultBufferSize(bufW, bufH)
            reapply()
            cb?.invoke(Surface(st))
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            // TextureView resets buffer size internally before this callback.
            st.setDefaultBufferSize(bufW, bufH)
            reapply()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            cb?.invoke(null)
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            holder.onFrameUpdated?.invoke(tv)
        }
    }
    tv.surfaceTexture?.let { st ->
        st.setDefaultBufferSize(bufW, bufH)
        reapply()
        cb?.invoke(Surface(st))
    }
}

private fun applyTransform(
    tv: TextureView,
    bufW: Int,
    bufH: Int,
    sensorOrientation: Int,
    wm: WindowManager,
    override: Int?,
    uiRotation: Int,
    scale: ScaleHolder,
    mode: ViewfinderMath.OrientationMode = ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE,
    onState: ((Int, Int, Int, Int, Int) -> Unit)?
) {
    val viewW = tv.width.coerceAtLeast(1).toFloat()
    val viewH = tv.height.coerceAtLeast(1).toFloat()
    val bw = bufW.toFloat()
    val bh = bufH.toFloat()

    val displayRotation = wm.defaultDisplay.rotation
    // The viewfinder is a stationary cinema monitor: feed rotation is anchored and fixed to the chassis,
    // never spinning or twisting when the device rotates into other angles.
    val r = ViewfinderMath.effectiveRotation(sensorOrientation, 0, override, mode)

    // Aspect-limited crop in BUFFER space (no rotation swap): the visible
    // band must equal the recorded WYSIWYG band.
    val (effW, effH) = ViewfinderMath.effectiveCrop(bw, bh, scale.aspect)

    val values = if (scale.mode == ViewfinderMath.ScaleMode.STRETCH) {
        // Anamorphic fill: slider (stretchFrac) interpolates whole-frame fit →
        // edge-to-edge anamorphic. No crop at any position.
        ViewfinderMath.contentTransformStretchFrac(
            viewW, viewH, bw, bh, effW, effH, scale.fillFraction, r
        )
    } else {
        // View-space transform (see ViewfinderMath.contentTransform for the
        // pinned TextureView convention): a = k*bw/viewW, d = k*bh/viewH with
        // k the presentation factor based on fillFraction — the anisotropic default
        // pre-stretch is undone and buffer pixels stay square.
        val k = ViewfinderMath.presentationScale(viewW, viewH, effW, effH, scale.fillFraction, r)
        ViewfinderMath.contentTransform(
            viewW, viewH, bw, bh, effW, effH, k, r
        )
    }
    val m = Matrix()
    m.setValues(values)
    tv.setTransform(m)
    onState?.invoke(sensorOrientation, displayRotation, r, bufW, bufH)
}

/**
 * Preview buffer selection — RAW-ASPECT-FIRST: the buffer must match the
 * selected RAW mode's shape (4:3 for the F6's binned mode) because every
 * viewfinder presentation mode and the WYSIWYG recorded crop assume
 * sensor-aspect content. Near-[rawAspect] candidates (|ratio−target| ≤ 0.02)
 * win, closest in area to the [rawAspect]-shaped ~0.69MP target; only when no
 * near-target size exists does area-closeness decide overall.
 *
 * (The previous version computed an aspect penalty but truncated
 * `abs(ratio − 4/3).toLong()` to 0 for every sub-1.0 difference — the
 * penalty never discriminated, so a 16:9 candidate closer in area won.)
 */
internal fun chooseBufferSize(
    supported: List<android.util.Size>,
    rawAspect: Double = 4.0 / 3.0
): android.util.Size {
    if (supported.isEmpty()) {
        val w = 960
        val h = (w / rawAspect).toInt().coerceAtLeast(1)
        return android.util.Size(w, h)
    }
    val best = selectBufferSize(supported.map { it.width to it.height }, rawAspect)
    return android.util.Size(best.first, best.second)
}

/**
 * Pure selection logic (JVM-testable): RAW-ASPECT-FIRST. The preview buffer
 * must match the selected RAW mode's shape ([rawAspect], 4:3 on the F6's
 * binned mode) because every viewfinder presentation mode and the WYSIWYG
 * recorded crop assume sensor-aspect content. Near-[rawAspect] candidates
 * (|ratio−target| ≤ 0.02) win, closest in area to the ~0.69MP
 * [rawAspect]-shaped target among them; only when no near-target size exists
 * does area-closeness decide overall.
 *
 * (The previous version truncated its aspect penalty — `abs(ratio −
 * 4/3).toLong()` is 0 for every sub-1.0 difference — so the penalty never
 * discriminated and a 16:9 candidate closer in area could win the buffer.)
 */
fun selectBufferSize(
    supported: List<Pair<Int, Int>>,
    rawAspect: Double = 4.0 / 3.0
): Pair<Int, Int> {
    if (supported.isEmpty()) {
        val w = 960
        return w to (w / rawAspect).toInt().coerceAtLeast(1)
    }
    // ~0.69MP target in the RAW mode's own shape (960×720 for 4:3).
    val targetArea = 691_200.0
    val targetW = kotlin.math.sqrt(targetArea * rawAspect)
    val targetH = targetW / rawAspect
    fun aspectOf(s: Pair<Int, Int>) = s.first.toDouble() / s.second

    val nearTarget = supported.filter {
        kotlin.math.abs(aspectOf(it) - rawAspect) <= 0.02
    }
    if (nearTarget.isNotEmpty()) {
        return nearTarget.minByOrNull {
            kotlin.math.abs(
                it.first.toLong() * it.second - (targetW * targetH).toLong()
            )
        } ?: (960 to 720)
    }
    // No near-target size: prefer LANDSCAPE (w >= h) candidates — the sensor
    // presents landscape and a portrait buffer (e.g. 1440x1920) mis-frames
    // the chassis-locked viewfinder. Only an all-portrait list keeps one.
    val landscape = supported.filter { it.first >= it.second }
    val pool = if (landscape.isNotEmpty()) landscape else supported
    return pool.minByOrNull {
        val areaDiff = kotlin.math.abs(it.first.toLong() * it.second - (targetW * targetH).toLong())
        val aspectDiff = kotlin.math.abs(aspectOf(it) - rawAspect)
        areaDiff + kotlin.math.round(aspectDiff * 1_000_000)
    } ?: (960 to 720)
}
