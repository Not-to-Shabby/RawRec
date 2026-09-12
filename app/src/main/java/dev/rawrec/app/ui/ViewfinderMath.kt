package dev.rawrec.app.ui

/**
 * Pure viewfinder rotation math — single source of truth shared by the
 * transform, the debug override UI, and the telemetry readout.
 */
object ViewfinderMath {

    /** How the buffer maps into the view. STRETCH = anamorphic fill (whole frame visible, non-square pixels). */
    enum class ScaleMode { FILL, FIT, STRETCH }

    /** Orientation behavior for camera viewport & window. */
    enum class OrientationMode(val label: String, val description: String) {
        CINEMA_LANDSCAPE("Cinema Landscape", "Locked horizontal widescreen cinema mode"),
        CHASSIS_LOCKED("Chassis Locked (0°)", "Fixed 0° monitor view with rotating UI chrome")
    }

    /**
     * Auto rotation math based on selected [OrientationMode]:
     * - CINEMA_LANDSCAPE: in a landscape window (SENSOR_LANDSCAPE), display rotation is 90° or 270°.
     *   Camera2 upright rotation is (sensorOrientation - displayRotation + 360) % 360.
     *   On a 90° sensor (IMX882 / IMX355), normal landscape yields 0° and reverse landscape yields 180°.
     * - CHASSIS_LOCKED: permanently 0° (matching sensor chassis monitor view).
     */
    fun autoRotation(
        sensorOrientation: Int,
        rotationDeg: Int = 0,
        mode: OrientationMode = OrientationMode.CINEMA_LANDSCAPE
    ): Int = when (mode) {
        OrientationMode.CINEMA_LANDSCAPE -> {
            // In a landscape activity window (SCREEN_ORIENTATION_SENSOR_LANDSCAPE), Android's WindowManager
            // automatically rotates the entire window 180° when flipping between ROTATION_90 and ROTATION_270.
            // The upright relative transform to compensate the hardware sensor orientation (90° on back cameras)
            // is (360 - sensorOrientation) % 360 = 270° (90° CCW).
            (360 - (sensorOrientation % 360)) % 360
        }
        OrientationMode.CHASSIS_LOCKED -> 0
    }

    /** Final angle: manual override wins over orientation mode default. */
    fun effectiveRotation(
        sensorOrientation: Int,
        rotationDeg: Int = 0,
        override: Int? = null,
        mode: OrientationMode = OrientationMode.CINEMA_LANDSCAPE
    ): Int = override ?: autoRotation(sensorOrientation, rotationDeg, mode)

    val OVERRIDE_OPTIONS = listOf<Int?>(null, 0, 90, 180, 270)

    fun formatOverride(o: Int?): String = o?.let { "$it°" } ?: "Locked"

    /**
     * Aspect-limited content crop in BUFFER space (no rotation swap): the
     * visible band must equal the recorded band (WYSIWYG — the framing crop
     * `MipiPacker.cropRect` cuts along the sensor's long axis). Wide limits
     * (2.39, 16:9) cut a centered horizontal band; a null/narrow limit
     * returns the full buffer.
     */
    fun effectiveCrop(
        bufW: Float,
        bufH: Float,
        aspectLimit: Float?
    ): Pair<Float, Float> {
        if (aspectLimit == null || aspectLimit <= 0f) return bufW to bufH
        // Limit wider than the content -> cut a centered HEIGHT band (the
        // WYSIWYG framing crop cuts rows, never columns). Limit narrower ->
        // keep full height, cap the width.
        return if (aspectLimit > bufW / bufH) {
            bufW to bufW / aspectLimit
        } else {
            bufH * aspectLimit to bufH
        }
    }

    /**
     * Presentation-mode selection (REPRESENTATIONAL default, IMMERSIVE opt-in).
     *
     * Default (immersive = false): FIT — the viewfinder letterboxes the sensor frame.
     * Immersive (immersive = true): FILL — preview fills the screen edge-to-edge
     * across all aspect ratio modes without collapsing back to a 4:3 box.
     */
    fun presentationFor(immersive: Boolean, aspectIndex: Int = 0): ScaleMode =
        if (immersive) ScaleMode.FILL
        else ScaleMode.FIT

    fun presentationFor(aspectIndex: Int): ScaleMode = presentationFor(false, aspectIndex)

    /**
     * On-screen rect of the framed active recording window (view coordinates).
     *
     * In FIT mode (Accurate):
     *  - In portrait: centered within the view with width effW*k and height effH*k.
     *  - In landscape: the chassis-locked camera feed is rendered at 0° (so sensor height
     *    bufH spans along the device's horizontal axis k*bufH, and sensor width bufW
     *    spans along the device's vertical axis k*bufW).
     *    The widescreen landscape frame stays bounded inside this camera feed.
     *
     * In FILL mode (Fill screen):
     *  - The camera feed covers the entire view. The active framing rect fits
     *    within the screen dimensions and displays the chosen aspect ratio cleanly.
     */
    fun activeFramingRect(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        aspectLimit: Float?,
        k: Float,
        scaleMode: ScaleMode = ScaleMode.FIT,
        isLandscape: Boolean = viewW > viewH,
        rotation: Int = 0
    ): FloatArray {
        if (aspectLimit == null || aspectLimit <= 0f) {
            return if (scaleMode == ScaleMode.FILL) {
                floatArrayOf(0f, 0f, viewW, viewH)
            } else {
                val (effW, effH) = effectiveCrop(bufW, bufH, null)
                contentRect(viewW, viewH, effW, effH, k, rotation)
            }
        }

        if (scaleMode == ScaleMode.FILL) {
            return if (viewW >= viewH) {
                val screenAspect = viewW / viewH
                if (aspectLimit > screenAspect) {
                    val devH = viewW / aspectLimit
                    val top = (viewH - devH) / 2f
                    floatArrayOf(0f, top, viewW, top + devH)
                } else {
                    val devW = viewH * aspectLimit
                    val left = (viewW - devW) / 2f
                    floatArrayOf(left, 0f, left + devW, viewH)
                }
            } else if (isLandscape) {
                val screenAspect = viewH / viewW
                if (aspectLimit > screenAspect) {
                    val devH = viewH / aspectLimit
                    val left = (viewW - devH) / 2f
                    floatArrayOf(left, 0f, left + devH, viewH)
                } else {
                    val devW = viewW * aspectLimit
                    val top = (viewH - devW) / 2f
                    floatArrayOf(0f, top, viewW, top + devW)
                }
            } else {
                val devH = viewW / aspectLimit
                val top = (viewH - devH) / 2f
                floatArrayOf(0f, top, viewW, top + devH)
            }
        }

        // In native landscape window (viewW >= viewH, e.g. Cinema Landscape):
        if (viewW >= viewH) {
            val (effW, effH) = effectiveCrop(bufW, bufH, null)
            val full = contentRect(viewW, viewH, effW, effH, k, rotation)
            val imgW = full[2] - full[0]
            val imgH = full[3] - full[1]
            val imgAspect = imgW / imgH

            return if (aspectLimit >= imgAspect) {
                // Target is wider than on-screen content (e.g. 16:9, 2.39:1): letterbox top and bottom
                val boxW = imgW
                val boxH = boxW / aspectLimit
                val left = (viewW - boxW) / 2f
                val top = (viewH - boxH) / 2f
                floatArrayOf(left, top, left + boxW, top + boxH)
            } else {
                // Target is narrower than on-screen content (e.g. 1:1): pillarbox left and right
                val boxH = imgH
                val boxW = boxH * aspectLimit
                val left = (viewW - boxW) / 2f
                val top = (viewH - boxH) / 2f
                floatArrayOf(left, top, left + boxW, top + boxH)
            }
        }

        // In portrait window (viewW < viewH) held horizontally in chassis-locked mode (rotation 0):
        if (viewW < viewH && isLandscape && rotation == 0) {
            val imgH = k * bufH
            val imgTop = (viewH - imgH) / 2f
            val devH = imgH / aspectLimit
            val left = (viewW - devH) / 2f
            return floatArrayOf(left, imgTop, left + devH, imgTop + imgH)
        }

        val (effW, effH) = effectiveCrop(bufW, bufH, aspectLimit)
        return contentRect(viewW, viewH, effW, effH, k, rotation)
    }

    /**
     * Overload for continuous fillFraction: 0f = 0% Accurate (FIT), 1f = 100% Fill screen (FILL).
     * Smoothly interpolates the on-screen framing rect between Accurate and Fill screen.
     */
    fun activeFramingRect(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        aspectLimit: Float?,
        k: Float,
        fillFraction: Float,
        isLandscape: Boolean = viewW > viewH,
        rotation: Int = 0
    ): FloatArray {
        val frac = fillFraction.coerceIn(0f, 1f)
        if (frac <= 0.001f) {
            return activeFramingRect(viewW, viewH, bufW, bufH, aspectLimit, k, ScaleMode.FIT, isLandscape, rotation)
        }
        if (frac >= 0.999f) {
            return activeFramingRect(viewW, viewH, bufW, bufH, aspectLimit, k, ScaleMode.FILL, isLandscape, rotation)
        }
        val kFit = presentationScale(viewW, viewH, bufW, bufH, ScaleMode.FIT, rotation)
        val kFill = presentationScale(viewW, viewH, bufW, bufH, ScaleMode.FILL, rotation)
        val rFit = activeFramingRect(viewW, viewH, bufW, bufH, aspectLimit, kFit, ScaleMode.FIT, isLandscape, rotation)
        val rFill = activeFramingRect(viewW, viewH, bufW, bufH, aspectLimit, kFill, ScaleMode.FILL, isLandscape, rotation)
        return floatArrayOf(
            rFit[0] + frac * (rFill[0] - rFit[0]),
            rFit[1] + frac * (rFill[1] - rFit[1]),
            rFit[2] + frac * (rFill[2] - rFit[2]),
            rFit[3] + frac * (rFill[3] - rFit[3])
        )
    }

    /**
     * On-screen rect of the presented image (view coordinates): centered,
     * k*effW wide, k*effH tall — the exact band the transform renders.
     * Pure counterpart of [contentTransform] for the overlay guides: the
     * framing outline and Grid thirds must span THIS rect, not the window.
     */
    fun contentRect(
        viewW: Float,
        viewH: Float,
        effW: Float,
        effH: Float,
        k: Float,
        rotation: Int = 0
    ): FloatArray {
        val (renderedW, renderedH) = if (rotation == 90 || rotation == 270) {
            effH to effW
        } else {
            effW to effH
        }
        val w = k * renderedW
        val h = k * renderedH
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        // {left, top, right, bottom}
        return floatArrayOf(left, top, left + w, top + h)
    }

    /**
     * FILL/FIT presentation factor k, in view-px-per-buffer-px: how large the
     * effW x effH content crop renders on screen. FILL covers (k = max),
     * FIT fits (k = min). Square-pixel guarantee: the same k applies through
     * both axes of [contentTransform], cancelling the view's anisotropic
     * default stretch exactly.
     *
     * [rotation] 90/270 swaps the VIEW dimensions the rotated content box
     * must cover/fit (on-screen the box is k*effH wide x k*effW tall) — the
     * aspect crop itself stays buffer-aligned (see [effectiveCrop]).
     */
    fun presentationScale(
        viewW: Float,
        viewH: Float,
        effW: Float,
        effH: Float,
        mode: ScaleMode,
        rotation: Int = 0
    ): Float = presentationScale(viewW, viewH, effW, effH, if (mode == ScaleMode.FILL) 1f else 0f, rotation)

    /**
     * Overload for continuous fillFraction: 0f = 0% Accurate (FIT), 1f = 100% Fill screen (FILL).
     * Smoothly scales the preview between accurate fit and screen-filling crop.
     */
    fun presentationScale(
        viewW: Float,
        viewH: Float,
        effW: Float,
        effH: Float,
        fillFraction: Float,
        rotation: Int = 0
    ): Float {
        val (coverW, coverH) = if (rotation == 90 || rotation == 270) {
            effH to effW
        } else {
            effW to effH
        }
        val fit = minOf(viewW / coverW, viewH / coverH)
        val fill = maxOf(viewW / coverW, viewH / coverH)
        val frac = fillFraction.coerceIn(0f, 1f)
        return fit + frac * (fill - fit)
    }

    /**
     * Per-axis view scales (view px per BUFFER px) for the anamorphic stretch
     * interpolation, indexed by BUFFER axis: [sx] scales the buffer-x extent,
     * [sy] the buffer-y extent. At [stretchFrac] 0 both equal the FIT scale
     * (square pixels, whole content letterboxed); at 1 the content fills the
     * view edge-to-edge (rendered width → viewW, rendered height → viewH).
     * The whole content is visible at every fraction — no crop ever.
     *
     * Rotation matters: at 90/270 the buffer-x extent renders along SCREEN-Y
     * and buffer-y along SCREEN-X, so the view-dimension targets swap axes.
     * Returns [sx, sy].
     */
    fun stretchAxisScales(
        viewW: Float,
        viewH: Float,
        effW: Float,
        effH: Float,
        stretchFrac: Float,
        rotation: Int = 0
    ): FloatArray {
        val frac = stretchFrac.coerceIn(0f, 1f)
        val sx: Float
        val sy: Float
        if (rotation == 90 || rotation == 270) {
            // Rendered on screen: width = effH (buffer-y), height = effW (buffer-x).
            val fit = minOf(viewW / effH, viewH / effW)
            sx = fit + frac * (viewH / effW - fit)
            sy = fit + frac * (viewW / effH - fit)
        } else {
            // Rendered on screen: width = effW (buffer-x), height = effH (buffer-y).
            val fit = minOf(viewW / effW, viewH / effH)
            sx = fit + frac * (viewW / effW - fit)
            sy = fit + frac * (viewH / effH - fit)
        }
        return floatArrayOf(sx, sy)
    }

    /**
     * Anamorphic (STRETCH) transform: renders the effW x effH content crop
     * filling the view with NO crop — whole frame always visible, pixels may
     * be non-square. Entries: a = kx*bufW/viewW, d = ky*bufH/viewH with per-
     * axis scales from [stretchAxisScales] at full stretch (eff dims swapped
     * for 90/270), translation to center, rotation about the content center.
     * Identity when effW x effH equals the full buffer and rotation is 0.
     */
    fun contentTransformStretch(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        effW: Float,
        effH: Float,
        rotation: Int
    ): FloatArray = contentTransformStretchFrac(viewW, viewH, bufW, bufH, effW, effH, 1f, rotation)

    /**
     * Stretch-with-fraction: per-axis scales interpolate from the FIT (square-
     * pixel, letterboxed) scale toward the full anamorphic fill as [stretchFrac]
     * goes 0f → 1f. At 0 the transform equals the uniform FIT transform; at 1
     * it equals [contentTransformStretch]. The WHOLE frame is visible at every
     * position — no crop ever, only progressive anamorphic deformation.
     */
    fun contentTransformStretchFrac(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        effW: Float,
        effH: Float,
        stretchFrac: Float,
        rotation: Int
    ): FloatArray {
        val scales = stretchAxisScales(viewW, viewH, effW, effH, stretchFrac, rotation)
        val a = scales[0] * bufW / viewW
        val d = scales[1] * bufH / viewH
        val cx = viewW / 2f
        val cy = viewH / 2f

        val rad = Math.toRadians(rotation.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        val m00 = a * cos;     val m01 = -d * sin
        val m10 = a * sin;     val m11 = d * cos
        val tx = cx - (m00 * cx + m01 * cy)
        val ty = cy - (m10 * cx + m11 * cy)
        return floatArrayOf(
            m00, m01, tx,
            m10, m11, ty,
            0f, 0f, 1f
        )
    }

    /**
     * Framing rect for STRETCH presentation: the whole view IS the full frame,
     * so the aspect band letterboxes/pillarboxes inside the view rect itself.
     * Wide limits (2.39, 16:9) cut a centered height band; narrower (1:1)
     * pillarboxes left/right; null = whole view.
     */
    fun activeFramingRectStretch(viewW: Float, viewH: Float, aspectLimit: Float?): FloatArray {
        if (aspectLimit == null || aspectLimit <= 0f) {
            return floatArrayOf(0f, 0f, viewW, viewH)
        }
        val screenAspect = viewW / viewH
        return if (aspectLimit >= screenAspect) {
            // Target wider than view: full width, centered height band
            val boxH = viewW / aspectLimit
            val top = (viewH - boxH) / 2f
            floatArrayOf(0f, top, viewW, top + boxH)
        } else {
            // Target narrower than view: full height, centered width band
            val boxW = viewH * aspectLimit
            val left = (viewW - boxW) / 2f
            floatArrayOf(left, 0f, left + boxW, viewH)
        }
    }

    /**
     * Stretch-with-fraction framing rect: the on-screen rect of the effW x effH
     * content under [contentTransformStretchFrac] — computed from the same
     * [stretchAxisScales] so the framing outline always sits exactly on the
     * presented content at any stretch fraction and rotation.
     */
    fun activeFramingRectStretchFrac(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        effW: Float,
        effH: Float,
        stretchFrac: Float,
        rotation: Int = 0
    ): FloatArray {
        val scales = stretchAxisScales(viewW, viewH, effW, effH, stretchFrac, rotation)
        val w: Float
        val h: Float
        if (rotation == 90 || rotation == 270) {
            // Buffer-x renders along screen-Y, buffer-y along screen-X.
            w = effH * scales[1]
            h = effW * scales[0]
        } else {
            w = effW * scales[0]
            h = effH * scales[1]
        }
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        return floatArrayOf(left, top, left + w, top + h)
    }

    /**
     * Builds the TextureView content transform as a row-major 3x3 (Android
     * Matrix.setValues order: {a,b,tx, c,d,ty, 0,0,1}).
     *
     * CONVENTION (pinned from AOSP TextureView.java + Open Camera's
     * configureTransform, quoted in docs/knowledgebase.md): `setTransform`'s
     * matrix maps VIEW coordinates — the buffer is PRE-STRETCHED
     * (anisotropically) to fill the view, and our matrix then transforms
     * that stretched content. To present the effW x effH crop at uniform
     * scale [k] (view-px per buffer-px), the entries must be
     *   a = k * bw / viewW,   d = k * bh / viewH
     * i.e. they UNDO the pre-stretch and re-apply k per axis, so the final
     * view-px-per-buffer-px equals k in both axes (square sensor pixels).
     * Content then renders at k*effW x k*effH view px, translated to center
     * and rotated by [rotation] about the content center.
     *
     * Regression note: the old code wrote buffer-pixel scales directly
     * (a = s, d = s), rendering a 139px black bar on the right with a
     * ~3.5x zoom on portrait 1220x2712 / 1920x1440 (measured 2026-08-30).
     */
    fun contentTransform(
        viewW: Float,
        viewH: Float,
        bufW: Float,
        bufH: Float,
        effW: Float,
        effH: Float,
        k: Float,
        rotation: Int
    ): FloatArray {
        val a = k * bufW / viewW
        val d = k * bufH / viewH
        val cx = viewW / 2f
        val cy = viewH / 2f

        val rad = Math.toRadians(rotation.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        // Composed: T(center) * R(rot) * S(a,d) * T(-center)
        val m00 = a * cos;     val m01 = -d * sin
        val m10 = a * sin;     val m11 = d * cos
        val tx = cx - (m00 * cx + m01 * cy)
        val ty = cy - (m10 * cx + m11 * cy)
        return floatArrayOf(
            m00, m01, tx,
            m10, m11, ty,
            0f, 0f, 1f
        )
    }
}
