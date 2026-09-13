@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.rawrec.app.ui

import android.content.res.Configuration
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import dev.rawrec.app.capture.RecStats
import dev.rawrec.app.control.CameraControlState
import dev.rawrec.app.control.ColorTemperature
import dev.rawrec.app.control.FocusCalculator
import dev.rawrec.app.control.ShutterCalculator
import dev.rawrec.app.probe.CameraCatalog
import dev.rawrec.app.scopes.HistogramData
import dev.rawrec.app.scopes.ScopeAnalyzer
import dev.rawrec.app.ui.components.ChoiceChipRow
import dev.rawrec.app.ui.components.ControlPill
import dev.rawrec.app.ui.components.Hudpill
import dev.rawrec.app.ui.components.IconToggle
import dev.rawrec.app.ui.components.RawRecIcons
import dev.rawrec.app.ui.components.RecordButtonExpressive
import dev.rawrec.app.ui.components.CinemaFocusRail
import dev.rawrec.app.ui.components.CinemaParameterDock
import dev.rawrec.app.ui.components.CinemaParameterGrid
import dev.rawrec.app.ui.components.rotateIcon
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckTimecodeStyle

private enum class ActivePanel {
    NONE, ISO, SHUTTER, WB, FOCUS, FPS, LOOK, RES
}

private val ASPECT_OPTIONS = listOf("Full", "2.39:1", "16:9", "4:3", "1:1")

@Composable
fun CinemaViewfinderScreen(
    stats: RecStats,
    controls: CameraControlState,
    histogram: HistogramData?,
    onControlsChanged: (CameraControlState) -> Unit,
    onRecordToggle: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onPreviewSurfaceAvailable: ((Surface?) -> Unit)? = null,
    supportedPreviewSizes: List<android.util.Size> = emptyList(),
    sensorOrientation: Int = 90,
    rotationOverride: Int? = null,
    uiRotation: Int = 0,
    orientationMode: ViewfinderMath.OrientationMode = ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE,
    immersive: Boolean = false,
    fillFraction: Float = if (immersive) 1f else 0f,
    stretchMode: Boolean = false,
    autoDisableVfSeconds: Int = 0,
    rawAspect: Double = 4.0 / 3.0,
    selectedRawSize: android.util.Size? = null,
    availableRawSizes: List<android.util.Size> = emptyList(),
    supportedFps: List<Double> = listOf(24.0, 30.0),
    maxAnalogIso: Int = 0,
    selectedCamera: dev.rawrec.app.probe.CamInfo? = null,
    availableCameras: List<dev.rawrec.app.probe.CamInfo> = emptyList(),
    onCameraSelected: ((dev.rawrec.app.probe.CamInfo) -> Unit)? = null,
    onRawSizeSelected: ((android.util.Size) -> Unit)? = null,
    onTelemetry: ((Int, Int, Int, Int, Int) -> Unit)? = null,
    vfBackend: dev.rawrec.app.util.AppPreferences.ViewfinderBackend = dev.rawrec.app.util.AppPreferences.ViewfinderBackend.TEXTURE_VIEW,
    activeLut: dev.rawrec.tool.CubeLut? = null,
    modifier: Modifier = Modifier
) {
    var activePanel by remember { mutableStateOf(ActivePanel.NONE) }
    // Manual-controls pills collapsed behind the tune button by default.
    var controlsExpanded by remember { mutableStateOf(false) }
    // Scope toggles collapsed behind the scopes button by default.
    var scopesExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Icon-only rotation: layout stays pinned to portrait positions; the
    // glyphs rotate via RawRecUi.LocalIconRotation (provided in MainActivity).

    var peakingActive by remember { mutableStateOf(false) }
    var falseColorActive by remember { mutableStateOf(false) }
    var zebrasActive by remember { mutableStateOf(false) }
    var histogramActive by remember { mutableStateOf(true) }
    var gridActive by remember { mutableStateOf(false) }
    // Preview buffer dims and applied rotation angle — reported by onStateChanged
    var vfBufW by remember { mutableStateOf(0) }
    var vfBufH by remember { mutableStateOf(0) }
    var vfRotation by remember { mutableStateOf(0) }
    var selectedLook by remember { mutableStateOf("Filmic") }

    // Live Scope Analyzer (15-20 FPS background analyzer for Histogram, Peaking, False Color, Zebras)
    val scopeAnalyzer = remember { ScopeAnalyzer() }
    DisposableEffect(Unit) {
        onDispose { scopeAnalyzer.release() }
    }
    val liveHistogram by scopeAnalyzer.histogram.collectAsState()
    val liveOverlay by scopeAnalyzer.overlay.collectAsState()

    val tallyAlpha by rememberInfiniteTransition(label = "tally").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "glow"
    )

    var isVfSleeping by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableLongStateOf(0L) }

    // Auto-disable viewfinder timer during active recording to preserve 30 FPS and battery
    LaunchedEffect(stats.active, autoDisableVfSeconds) {
        if (stats.active) {
            lastInteractionMs = android.os.SystemClock.elapsedRealtime()
            if (autoDisableVfSeconds > 0) {
                while (stats.active) {
                    kotlinx.coroutines.delay(500)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!isVfSleeping && now - lastInteractionMs >= autoDisableVfSeconds * 1000L) {
                        isVfSleeping = true
                    }
                }
            }
        } else {
            isVfSleeping = false
            lastInteractionMs = 0L
        }
    }

    val rootModifier = if (vfBackend == dev.rawrec.app.util.AppPreferences.ViewfinderBackend.OPENGL_ES) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxSize().background(Color.Black)
    }
    Box(modifier = rootModifier) {

        // Fixed-orientation viewfinder: feed never rotates, only UI chrome does.
        // Aspect mode controls real letterboxing/masking, not just overlay lines.
        val aspectRatioLimit = when (controls.aspectIndex) {
            1 -> 2.39f
            2 -> 16f / 9f
            3 -> 4f / 3f
            4 -> 1.0f
            else -> null
        }
        val scaleMode = when {
            stretchMode -> ViewfinderMath.ScaleMode.STRETCH
            fillFraction >= 0.5f -> ViewfinderMath.ScaleMode.FILL
            else -> ViewfinderMath.ScaleMode.FIT
        }
        val overlayContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val line = Color(0xDDFFFFFF)
                val scrim = Color.Black.copy(alpha = 0.55f)

                if (vfBufW > 0 && vfBufH > 0) {
                    val bw = vfBufW.toFloat()
                    val bh = vfBufH.toFloat()
                    val isLandscape = w > h || uiRotation == 90 || uiRotation == 270

                    val activeRect: FloatArray
                    var overlayW: Float
                    var overlayH: Float
                    var overlayLeft: Float
                    var overlayTop: Float
                    if (stretchMode) {
                        val rotated = vfRotation == 90 || vfRotation == 270
                        val scales = ViewfinderMath.stretchAxisScales(
                            w, h, bw, bh, fillFraction, vfRotation
                        )
                        val (bandW, bandH) = ViewfinderMath.effectiveCrop(bw, bh, aspectRatioLimit)
                        val bandScreenW = if (rotated) bandH * scales[1] else bandW * scales[0]
                        val bandScreenH = if (rotated) bandW * scales[0] else bandH * scales[1]
                        activeRect = floatArrayOf(
                            (w - bandScreenW) / 2f,
                            (h - bandScreenH) / 2f,
                            (w + bandScreenW) / 2f,
                            (h + bandScreenH) / 2f
                        )
                        overlayW = bw * scales[0]
                        overlayH = bh * scales[1]
                        overlayLeft = (w - overlayW) / 2f
                        overlayTop = (h - overlayH) / 2f
                    } else {
                        val k = ViewfinderMath.presentationScale(w, h, bw, bh, fillFraction, vfRotation)
                        activeRect = ViewfinderMath.activeFramingRect(
                            viewW = w,
                            viewH = h,
                            bufW = bw,
                            bufH = bh,
                            aspectLimit = aspectRatioLimit,
                            k = k,
                            fillFraction = fillFraction,
                            isLandscape = isLandscape,
                            rotation = vfRotation
                        )
                        overlayW = k * bw
                        overlayH = k * bh
                        overlayLeft = (w - overlayW) / 2f
                        overlayTop = (h - overlayH) / 2f
                    }

                    val left = activeRect[0]
                    val top = activeRect[1]
                    val right = activeRect[2]
                    val bottom = activeRect[3]

                    // Draw live scope overlays (False Color, Zebras, Focus Peaking) matching viewfinder orientation (TextureView mode only)
                    if (vfBackend == dev.rawrec.app.util.AppPreferences.ViewfinderBackend.TEXTURE_VIEW) {
                        val overlayImg = liveOverlay
                        if (overlayImg != null) {
                            clipRect(left = left, top = top, right = right, bottom = bottom) {
                                rotate(degrees = vfRotation.toFloat(), pivot = Offset(w / 2f, h / 2f)) {
                                    drawImage(
                                        image = overlayImg,
                                        dstOffset = IntOffset(overlayLeft.roundToInt(), overlayTop.roundToInt()),
                                        dstSize = IntSize(overlayW.roundToInt(), overlayH.roundToInt()),
                                        filterQuality = FilterQuality.Low
                                    )
                                }
                            }
                        }
                    }

                    // For framed aspect ratios (2.39:1, 16:9, 1:1), draw the Cinema Lookaround Scrim (55% dimmed)
                    if (controls.aspectIndex in 1..4 && aspectRatioLimit != null) {
                        if (top > 0f) drawRect(scrim, Offset(0f, 0f), Size(w, top))
                        if (bottom < h) drawRect(scrim, Offset(0f, bottom), Size(w, h - bottom))
                        if (left > 0f) drawRect(scrim, Offset(0f, top), Size(left, bottom - top))
                        if (right < w) drawRect(scrim, Offset(right, top), Size(w - right, bottom - top))

                        drawRect(
                            line,
                            Offset(left, top),
                            Size(right - left, bottom - top),
                            style = Stroke(2f)
                        )
                    }

                    // Rule-of-thirds gridlines strictly within the active recording window
                    if (gridActive) {
                        val grid = Color(0x66FFFFFF)
                        val iw = right - left
                        val ih = bottom - top
                        drawLine(grid, Offset(left + iw / 3f, top), Offset(left + iw / 3f, bottom), strokeWidth = 1.5f)
                        drawLine(grid, Offset(left + 2f * iw / 3f, top), Offset(left + 2f * iw / 3f, bottom), strokeWidth = 1.5f)
                        drawLine(grid, Offset(left, top + ih / 3f), Offset(right, top + ih / 3f), strokeWidth = 1.5f)
                        drawLine(grid, Offset(left, top + 2f * ih / 3f), Offset(right, top + 2f * ih / 3f), strokeWidth = 1.5f)
                    }

                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    drawLine(Color(0x88FFFFFF), Offset(cx - 16f, cy), Offset(cx + 16f, cy), strokeWidth = 2f)
                    drawLine(Color(0x88FFFFFF), Offset(cx, cy - 16f), Offset(cx, cy + 16f), strokeWidth = 2f)
                }
            }
        }

        if (vfBackend == dev.rawrec.app.util.AppPreferences.ViewfinderBackend.OPENGL_ES) {
            val glBufSize = remember(supportedPreviewSizes, rawAspect) {
                chooseBufferSize(supportedPreviewSizes, rawAspect)
            }
            val r = ViewfinderMath.effectiveRotation(sensorOrientation, 0, rotationOverride, orientationMode)
            LaunchedEffect(glBufSize, r) {
                vfBufW = glBufSize.width
                vfBufH = glBufSize.height
                vfRotation = r
            }
            dev.rawrec.app.ui.gl.GlViewfinder(
                modifier = Modifier.fillMaxSize(),
                bufferWidth = glBufSize.width,
                bufferHeight = glBufSize.height,
                rawAspect = rawAspect,
                rotation = r,
                fillFraction = fillFraction,
                stretchMode = stretchMode,
                activeLut = activeLut,
                peakingActive = peakingActive,
                falseColorActive = falseColorActive,
                zebrasActive = zebrasActive,
                histogramActive = histogramActive,
                onPixelsRead = { pixels ->
                    scopeAnalyzer.onPixelsAvailable(pixels, histogramActive)
                },
                onPreviewSurfaceAvailable = { surface ->
                    onPreviewSurfaceAvailable?.invoke(surface)
                },
                overlay = overlayContent
            )
        } else {
            FixedViewfinder(
                modifier = Modifier.fillMaxSize(),
                supportedPreviewSizes = supportedPreviewSizes,
                sensorOrientation = sensorOrientation,
                scaleMode = scaleMode,
                fillFraction = fillFraction,
                aspectLimit = null, // Viewfinder displays full sensor frame so lookaround area is visible
                rotationOverride = rotationOverride,
                uiRotation = uiRotation,
                orientationMode = orientationMode,
                rawAspect = rawAspect,
                onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                onStateChanged = { sensorO, dispR, angle, bufW, bufH ->
                    vfBufW = bufW; vfBufH = bufH
                    vfRotation = angle
                    onTelemetry?.invoke(sensorO, dispR, angle, bufW, bufH)
                },
                onFrameUpdated = { tv ->
                    if (!isVfSleeping) {
                        scopeAnalyzer.onFrameAvailable(
                            tv = tv,
                            histogramActive = histogramActive,
                            peakingActive = peakingActive,
                            falseColorActive = falseColorActive,
                            zebrasActive = zebrasActive
                        )
                    }
                },
                overlay = overlayContent
            )
        }

        // ─── Fixed chrome: HUD, scope rail, dial panel, deck stay at their
        // portrait layout positions in every orientation; only icon glyphs
        // rotate (RawRecUi.LocalIconRotation). ───

        // Recording tally border
        if (stats.active) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.error.copy(alpha = tallyAlpha))
            )
        }

        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                configuration.screenWidthDp < configuration.screenHeightDp

        // ─── Top Cinema Header (Broadcast status, Scopes strip, Mini-Hist, Settings) ───
        if (isPortrait) {
            // Adaptive 2-row layout for portrait / chassis-locked mode
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Recording tally dot, Timecode, STBY/REC badge, Size & Drops
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                        )
                        Text(
                            text = if (stats.active) formatTimecode(stats.elapsedMs, controls.targetFps) else "00:00:00:00",
                            style = DeckTimecodeStyle,
                            color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (stats.active) "REC" else "STBY",
                            style = DeckLabelStyle,
                            color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        if (stats.active) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatSize(stats.bytesWritten),
                                        style = DeckLabelStyle,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (stats.dropped == 0L) "0 DROP" else "${stats.dropped} DROP",
                                        style = DeckLabelStyle,
                                        color = if (stats.dropped == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        fontWeight = if (stats.dropped > 0L) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (stats.thermalStage != "NONE") {
                                        Text(
                                            text = stats.thermalStage,
                                            style = DeckLabelStyle,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: Mini Histogram & Settings Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val effectiveHistogram = if (histogramActive) (liveHistogram ?: histogram) else null
                        if (effectiveHistogram != null) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                                modifier = Modifier.size(width = 68.dp, height = 30.dp)
                            ) {
                                HistogramScopeView(
                                    histogram = effectiveHistogram,
                                    modifier = Modifier.padding(2.dp).fillMaxSize()
                                )
                            }
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { onOpenGallery() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    RawRecIcons.Movie,
                                    contentDescription = "Gallery",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { onOpenSettings() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    RawRecIcons.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Row 2: Centered Scopes Quick Strip
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconToggle(RawRecIcons.Peaks, "Peaking", peakingActive) { peakingActive = !peakingActive }
                            IconToggle(RawRecIcons.FalseColor, "False color", falseColorActive) { falseColorActive = !falseColorActive }
                            IconToggle(RawRecIcons.Zebra, "Zebras", zebrasActive) { zebrasActive = !zebrasActive }
                            IconToggle(RawRecIcons.Histogram, "Histogram", histogramActive) { histogramActive = !histogramActive }
                            IconToggle(RawRecIcons.Grid, "Grid", gridActive) { gridActive = !gridActive }
                            IconToggle(
                                icon = RawRecIcons.Aspect,
                                contentDescription = "Framing",
                                active = controls.aspectIndex > 0,
                                label = ASPECT_OPTIONS[controls.aspectIndex]
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onControlsChanged(controls.copy(aspectIndex = (controls.aspectIndex + 1) % ASPECT_OPTIONS.size))
                            }
                            if (availableCameras.size > 1) {
                                val lensShortLabel = when (selectedCamera?.lensRole) {
                                    dev.rawrec.app.probe.LensRole.ULTRAWIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "UW"
                                    dev.rawrec.app.probe.LensRole.WIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "W"
                                    dev.rawrec.app.probe.LensRole.TELEPHOTO -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "TELE"
                                    dev.rawrec.app.probe.LensRole.FRONT -> "Front"
                                    else -> "cam${selectedCamera?.id ?: 0}"
                                }
                                IconToggle(
                                    icon = RawRecIcons.Camera,
                                    contentDescription = "Camera Lens",
                                    active = true,
                                    label = lensShortLabel
                                ) {
                                    if (!stats.active && availableCameras.isNotEmpty()) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val currentIdx = availableCameras.indexOfFirst { it.id == selectedCamera?.id && it.physicalId == selectedCamera?.physicalId }
                                        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableCameras.size else 0
                                        onCameraSelected?.invoke(availableCameras[nextIdx])
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Widescreen Landscape single-row header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Recording tally dot, Timecode, STBY/REC badge, Size & Drops
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = if (stats.active) formatTimecode(stats.elapsedMs, controls.targetFps) else "00:00:00:00",
                        style = DeckTimecodeStyle,
                        color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (stats.active) "REC" else "STBY",
                        style = DeckLabelStyle,
                        color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    if (stats.active) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatSize(stats.bytesWritten),
                                    style = DeckLabelStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (stats.dropped == 0L) "0 DROP" else "${stats.dropped} DROP",
                                    style = DeckLabelStyle,
                                    color = if (stats.dropped == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    fontWeight = if (stats.dropped > 0L) FontWeight.Bold else FontWeight.Normal
                                )
                                if (stats.thermalStage != "NONE") {
                                    Text(
                                        text = stats.thermalStage,
                                        style = DeckLabelStyle,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Center: Compact Scopes Quick Strip
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconToggle(RawRecIcons.Peaks, "Peaking", peakingActive) { peakingActive = !peakingActive }
                        IconToggle(RawRecIcons.FalseColor, "False color", falseColorActive) { falseColorActive = !falseColorActive }
                        IconToggle(RawRecIcons.Zebra, "Zebras", zebrasActive) { zebrasActive = !zebrasActive }
                        IconToggle(RawRecIcons.Histogram, "Histogram", histogramActive) { histogramActive = !histogramActive }
                        IconToggle(RawRecIcons.Grid, "Grid", gridActive) { gridActive = !gridActive }
                        IconToggle(
                            icon = RawRecIcons.Aspect,
                            contentDescription = "Framing",
                            active = controls.aspectIndex > 0,
                            label = ASPECT_OPTIONS[controls.aspectIndex]
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onControlsChanged(controls.copy(aspectIndex = (controls.aspectIndex + 1) % ASPECT_OPTIONS.size))
                        }
                        if (availableCameras.size > 1) {
                            val lensShortLabel = when (selectedCamera?.lensRole) {
                                dev.rawrec.app.probe.LensRole.ULTRAWIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "UW"
                                dev.rawrec.app.probe.LensRole.WIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "W"
                                dev.rawrec.app.probe.LensRole.TELEPHOTO -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm" else "TELE"
                                dev.rawrec.app.probe.LensRole.FRONT -> "Front"
                                else -> "cam${selectedCamera?.id ?: 0}"
                            }
                            IconToggle(
                                icon = RawRecIcons.Camera,
                                contentDescription = "Camera Lens",
                                active = true,
                                label = lensShortLabel
                            ) {
                                if (!stats.active && availableCameras.isNotEmpty()) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val currentIdx = availableCameras.indexOfFirst { it.id == selectedCamera?.id && it.physicalId == selectedCamera?.physicalId }
                                    val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableCameras.size else 0
                                    onCameraSelected?.invoke(availableCameras[nextIdx])
                                }
                            }
                        }
                    }
                }

                // Right: Mini Histogram & Settings Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val effectiveHistogram = if (histogramActive) (liveHistogram ?: histogram) else null
                    if (effectiveHistogram != null) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            modifier = Modifier.size(width = 84.dp, height = 34.dp)
                        ) {
                            HistogramScopeView(
                                histogram = effectiveHistogram,
                                modifier = Modifier.padding(3.dp).fillMaxSize()
                            )
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { onOpenGallery() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                RawRecIcons.Movie,
                                contentDescription = "Gallery",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { onOpenSettings() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                RawRecIcons.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        val shutterLabel = if (controls.autoExposure) "AUTO" else if (controls.useShutterAngle) ShutterCalculator.formatShutterAngle(controls.shutterAngle) else ShutterCalculator.formatExposureFraction(controls.exposureNs)
        val isoLabel = if (controls.autoExposure) "AUTO" else controls.formattedIso
        val wbLabel = if (controls.autoWhiteBalance) "AUTO" else "${controls.whiteBalanceKelvin}K"
        val fpsLabel = "%.0f".format(if (stats.active) stats.avgFps else controls.targetFps)
        val resLabel = selectedRawSize?.let { CameraCatalog.formatShortResolution(it) } ?: "RES"

        val onTileClick: (String) -> Unit = { tile ->
            activePanel = when (tile) {
                "FPS" -> if (activePanel == ActivePanel.FPS) ActivePanel.NONE else ActivePanel.FPS
                "SHUTTER" -> if (activePanel == ActivePanel.SHUTTER) ActivePanel.NONE else ActivePanel.SHUTTER
                "ISO" -> if (activePanel == ActivePanel.ISO) ActivePanel.NONE else ActivePanel.ISO
                "WB" -> if (activePanel == ActivePanel.WB) ActivePanel.NONE else ActivePanel.WB
                "LOOK" -> if (activePanel == ActivePanel.LOOK) ActivePanel.NONE else ActivePanel.LOOK
                "RES" -> if (activePanel == ActivePanel.RES) ActivePanel.NONE else ActivePanel.RES
                else -> ActivePanel.NONE
            }
        }

        if (isPortrait) {
            // ─── Portrait Bottom Studio Deck (Focus Rail, Parameters Grid, Record Button, Dial Drawer) ───
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Slide-up Dial Panel Drawer
                AnimatedVisibility(
                    visible = activePanel != ActivePanel.NONE,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    ) {
                        Box(Modifier.padding(12.dp)) {
                            ActiveDialContent(
                                activePanel = activePanel,
                                controls = controls,
                                selectedLook = selectedLook,
                                selectedRawSize = selectedRawSize,
                                availableRawSizes = availableRawSizes,
                                supportedFps = supportedFps,
                                maxAnalogIso = maxAnalogIso,
                                onControlsChanged = onControlsChanged,
                                onLookSelected = { selectedLook = it },
                                onRawSizeSelected = onRawSizeSelected,
                                onDismiss = { activePanel = ActivePanel.NONE }
                            )
                        }
                    }
                }

                // Cinema Focus Rail (Full width in portrait)
                CinemaFocusRail(
                    currentDiopters = controls.focusDiopters,
                    autoFocus = controls.autoFocus,
                    onFocusChanged = { d, af ->
                        onControlsChanged(controls.copy(focusDiopters = d, autoFocus = af))
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Cinema Parameter Matrix (2x3 Grid in portrait)
                CinemaParameterGrid(
                    fpsValue = fpsLabel,
                    shutterValue = shutterLabel,
                    isoValue = isoLabel,
                    wbValue = wbLabel,
                    lookValue = selectedLook,
                    resValue = resLabel,
                    activeTile = when (activePanel) {
                        ActivePanel.FPS -> "FPS"
                        ActivePanel.SHUTTER -> "SHUTTER"
                        ActivePanel.ISO -> "ISO"
                        ActivePanel.WB -> "WB"
                        ActivePanel.LOOK -> "LOOK"
                        ActivePanel.RES -> "RES"
                        else -> null
                    },
                    autoExposure = controls.autoExposure,
                    autoWb = controls.autoWhiteBalance,
                    onTileClick = onTileClick,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(2.dp))

                // Master Record Button Row with Quick Gallery Review
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { onOpenGallery() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                RawRecIcons.Movie,
                                contentDescription = "Quick Gallery Review",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(28.dp))

                    RecordButtonExpressive(
                        recording = stats.active,
                        onToggle = onRecordToggle
                    )

                    Spacer(Modifier.width(72.dp))
                }
            }
        } else {
            // ─── Landscape Studio Deck Layout ───
            // Right-side Cinema Parameter Matrix Dock & Floating Dial Panel
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, top = 48.dp, bottom = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Floating Dial Panel (slides in to the left of the dock)
                AnimatedVisibility(
                    visible = activePanel != ActivePanel.NONE,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .width(320.dp)
                            .padding(4.dp)
                    ) {
                        Box(Modifier.padding(12.dp)) {
                            ActiveDialContent(
                                activePanel = activePanel,
                                controls = controls,
                                selectedLook = selectedLook,
                                selectedRawSize = selectedRawSize,
                                availableRawSizes = availableRawSizes,
                                supportedFps = supportedFps,
                                maxAnalogIso = maxAnalogIso,
                                onControlsChanged = onControlsChanged,
                                onLookSelected = { selectedLook = it },
                                onRawSizeSelected = onRawSizeSelected,
                                onDismiss = { activePanel = ActivePanel.NONE }
                            )
                        }
                    }
                }

                // The Dock
                CinemaParameterDock(
                    fpsValue = fpsLabel,
                    shutterValue = shutterLabel,
                    isoValue = isoLabel,
                    wbValue = wbLabel,
                    lookValue = selectedLook,
                    resValue = resLabel,
                    activeTile = when (activePanel) {
                        ActivePanel.FPS -> "FPS"
                        ActivePanel.SHUTTER -> "SHUTTER"
                        ActivePanel.ISO -> "ISO"
                        ActivePanel.WB -> "WB"
                        ActivePanel.LOOK -> "LOOK"
                        ActivePanel.RES -> "RES"
                        else -> null
                    },
                    autoExposure = controls.autoExposure,
                    autoWb = controls.autoWhiteBalance,
                    recording = stats.active,
                    onTileClick = onTileClick,
                    onRecordToggle = onRecordToggle
                )
            }

            // Bottom Cinema Focus Rail with A-B Rack Focus Puller
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 240.dp, bottom = 12.dp)
            ) {
                CinemaFocusRail(
                    currentDiopters = controls.focusDiopters,
                    autoFocus = controls.autoFocus,
                    onFocusChanged = { d, af ->
                        onControlsChanged(controls.copy(focusDiopters = d, autoFocus = af))
                    }
                )
            }
        }

        // ─── OLED Pitch-Black Telemetry Overlay (Viewfinder Sleep Mode) ───
        if (isVfSleeping && stats.active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        lastInteractionMs = android.os.SystemClock.elapsedRealtime()
                        isVfSleeping = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    // Top row: Glowing Tally + REC + Timecode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(Color(0xFFFF2D55).copy(alpha = tallyAlpha), CircleShape)
                        )
                        Text(
                            "REC",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFFF2D55),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            formatTimecode(stats.elapsedMs, controls.targetFps),
                            style = DeckTimecodeStyle.copy(fontSize = 32.sp),
                            color = Color.White
                        )
                    }

                    // Middle telemetry row: file size + dropped frames + thermal
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatSize(stats.bytesWritten),
                            style = dev.rawrec.app.ui.theme.TelemetryStyle.copy(fontSize = 15.sp),
                            color = Color(0xCCFFFFFF)
                        )
                        Text("•", color = Color(0x55FFFFFF))
                        val drops = stats.dropped
                        Text(
                            if (drops == 0L) "0 DROP" else "$drops DROP",
                            style = dev.rawrec.app.ui.theme.TelemetryStyle.copy(
                                fontSize = 15.sp,
                                fontWeight = if (drops > 0) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (drops == 0L) Color(0xFF00E5FF) else Color(0xFFFF2D55)
                        )
                        if (stats.thermalStage != "NONE") {
                            Text("•", color = Color(0x55FFFFFF))
                            Text(
                                "TH: ${stats.thermalStage}",
                                style = dev.rawrec.app.ui.theme.TelemetryStyle.copy(fontSize = 15.sp),
                                color = Color(0xFFFFB300)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Minimal master record stop button so operator can stop directly from sleep
                    RecordButtonExpressive(
                        recording = true,
                        onToggle = onRecordToggle
                    )

                    Spacer(Modifier.height(12.dp))

                    // Subtle pulsing tap-to-wake hint
                    Text(
                        "TAP ANYWHERE TO WAKE VIEWFINDER",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = Color(0x88FFFFFF)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDialContent(
    activePanel: ActivePanel,
    controls: CameraControlState,
    selectedLook: String,
    selectedRawSize: android.util.Size?,
    availableRawSizes: List<android.util.Size>,
    supportedFps: List<Double>,
    maxAnalogIso: Int,
    onControlsChanged: (CameraControlState) -> Unit,
    onLookSelected: (String) -> Unit,
    onRawSizeSelected: ((android.util.Size) -> Unit)?,
    onDismiss: () -> Unit
) {
    when (activePanel) {
        ActivePanel.ISO -> IsoDialPanel(
            iso = controls.iso,
            autoExposure = controls.autoExposure,
            maxAnalogIso = maxAnalogIso,
            onIsoChanged = { iso, auto ->
                onControlsChanged(controls.copy(iso = iso, autoIso = auto, autoExposure = auto))
            }
        )
        ActivePanel.SHUTTER -> ShutterDialPanel(
            exposureNs = controls.exposureNs,
            shutterAngle = controls.shutterAngle,
            useAngle = controls.useShutterAngle,
            fps = controls.targetFps,
            autoExposure = controls.autoExposure,
            onShutterChanged = { ns, angle, useA ->
                onControlsChanged(
                    controls.copy(
                        exposureNs = ns,
                        shutterAngle = angle,
                        useShutterAngle = useA,
                        autoExposure = false
                    )
                )
            },
            onAutoExposureChange = { auto ->
                onControlsChanged(controls.copy(autoExposure = auto, autoIso = auto))
            }
        )
        ActivePanel.WB -> WbDialPanel(
            kelvin = controls.whiteBalanceKelvin,
            tint = controls.whiteBalanceTint,
            autoWb = controls.autoWhiteBalance,
            onWbChanged = { k, tint, auto ->
                onControlsChanged(
                    controls.copy(
                        whiteBalanceKelvin = k,
                        whiteBalanceTint = tint,
                        autoWhiteBalance = auto
                    )
                )
            }
        )
        ActivePanel.FOCUS -> FocusDialPanel(
            diopters = controls.focusDiopters,
            autoFocus = controls.autoFocus,
            onFocusChanged = { d, af ->
                onControlsChanged(controls.copy(focusDiopters = d, autoFocus = af))
            }
        )
        ActivePanel.FPS -> FpsDialPanel(
            currentFps = controls.targetFps,
            supportedFps = supportedFps,
            onFpsChanged = { fps ->
                onControlsChanged(controls.copy(targetFps = fps))
                onDismiss()
            }
        )
        ActivePanel.LOOK -> LookDialPanel(
            currentLook = selectedLook,
            onLookSelected = { look ->
                onLookSelected(look)
                onDismiss()
            }
        )
        ActivePanel.RES -> ResDialPanel(
            selectedSize = selectedRawSize,
            availableSizes = availableRawSizes,
            onSizeSelected = { s ->
                onRawSizeSelected?.invoke(s)
                onDismiss()
            }
        )
        ActivePanel.NONE -> {}
    }
}

@Composable
private fun LookDialPanel(
    currentLook: String,
    onLookSelected: (String) -> Unit
) {
    val looks = listOf("Filmic", "OOTF", "Warm", "Cool", "Vintage", "Bright", "Mono", "Natural")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialHeader("Look / Tone Profile ($currentLook)")
        ChoiceChipRow(
            options = looks,
            selected = { it.equals(currentLook, ignoreCase = true) },
            onSelect = onLookSelected
        )
    }
}

@Composable
private fun ResDialPanel(
    selectedSize: android.util.Size?,
    availableSizes: List<android.util.Size>,
    onSizeSelected: (android.util.Size) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val currentLabel = selectedSize?.let { CameraCatalog.formatShortResolution(it) } ?: "RES"
        DialHeader("RAW Sensor Mode ($currentLabel)")
        ChoiceChipRow(
            options = availableSizes.map { CameraCatalog.formatResolution(it, availableSizes) },
            selected = { label -> selectedSize != null && CameraCatalog.formatResolution(selectedSize, availableSizes) == label },
            onSelect = { label ->
                availableSizes.firstOrNull { CameraCatalog.formatResolution(it, availableSizes) == label }?.let(onSizeSelected)
            }
        )
    }
}

@Composable
fun HistogramScopeView(histogram: HistogramData, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxBin = histogram.lumaBins.maxOrNull()?.coerceAtLeast(1) ?: 1
        for (i in 0 until 256 step 4) {
            val x = (i / 256f) * w
            val binH = (histogram.lumaBins[i].toFloat() / maxBin) * h
            drawLine(Color(0x99FFFFFF), Offset(x, h), Offset(x, h - binH), strokeWidth = 2f)
        }
    }
}

@Composable
private fun DialHeader(
    title: String,
    autoLabel: String? = null,
    autoChecked: Boolean? = null,
    onAutoChange: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (autoLabel != null && autoChecked != null && onAutoChange != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    autoLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (autoChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = autoChecked, onCheckedChange = onAutoChange)
            }
        }
    }
}

@Composable
private fun IsoDialPanel(
    iso: Int,
    autoExposure: Boolean,
    maxAnalogIso: Int = 0,
    onIsoChanged: (Int, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // AUTO here is the exposure mode (one mode drives both ISO and
        // shutter — the ISO panel's switch and the Shutter panel's switch
        // toggle the same autoExposure).
        val analogHint = if (maxAnalogIso > 0) " (Analog ≤ $maxAnalogIso)" else ""
        DialHeader("ISO $iso$analogHint", "AUTO", autoExposure) { onIsoChanged(iso, it) }
        if (!autoExposure) {
            Slider(
                value = iso.toFloat(),
                onValueChange = { onIsoChanged(it.toInt(), false) },
                valueRange = 50f..6400f
            )
            ChoiceChipRow(
                options = listOf("50", "100", "200", "400", "800", "1600", "3200", "6400"),
                selected = { it == iso.toString() },
                onSelect = { onIsoChanged(it.toInt(), false) }
            )
        }
    }
}

@Composable
private fun ShutterDialPanel(
    exposureNs: Long,
    shutterAngle: Double,
    useAngle: Boolean,
    fps: Double,
    autoExposure: Boolean,
    onShutterChanged: (Long, Double, Boolean) -> Unit,
    onAutoExposureChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val titleText = if (autoExposure) {
            "Shutter AUTO"
        } else if (useAngle) {
            "Shutter ${ShutterCalculator.formatShutterAngle(shutterAngle)} (${ShutterCalculator.formatExposureFraction(ShutterCalculator.shutterAngleToExposureNs(shutterAngle, fps))})"
        } else {
            "Shutter ${ShutterCalculator.formatExposureFraction(exposureNs)}"
        }
        DialHeader(titleText, "AUTO", autoExposure, onAutoExposureChange)
        if (!autoExposure) {
            ChoiceChipRow(
                options = listOf("Angle", "Time"),
                selected = { (it == "Angle") == useAngle },
                onSelect = { onShutterChanged(exposureNs, shutterAngle, it == "Angle") }
            )
            if (useAngle) {
                Slider(
                    value = shutterAngle.toFloat(),
                    onValueChange = { onShutterChanged(exposureNs, it.toDouble(), true) },
                    valueRange = 10f..360f
                )
                ChoiceChipRow(
                    options = listOf("45°", "90°", "144°", "172.8°", "180°", "270°", "360°"),
                    selected = { it == "${shutterAngle.toInt()}°" },
                    onSelect = {
                        onShutterChanged(exposureNs, it.dropLast(1).toDouble(), true)
                    }
                )
            } else {
                ChoiceChipRow(
                    options = listOf("1/24", "1/30", "1/48", "1/50", "1/60", "1/96", "1/120", "1/240"),
                    selected = { exposureNs == ShutterCalculator.fractionToExposureNs(it.drop(2).toInt()) },
                    onSelect = {
                        onShutterChanged(
                            ShutterCalculator.fractionToExposureNs(it.drop(2).toInt()),
                            shutterAngle,
                            false
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun WbDialPanel(
    kelvin: Int,
    tint: Int,
    autoWb: Boolean,
    onWbChanged: (Int, Int, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialHeader(
            "White Balance ${kelvin}K",
            "AWB",
            autoWb
        ) { onWbChanged(kelvin, tint, it) }
        if (!autoWb) {
            Slider(
                value = kelvin.toFloat(),
                onValueChange = { onWbChanged(it.toInt(), tint, false) },
                valueRange = 2000f..10000f
            )
            ChoiceChipRow(
                options = listOf("3200K", "4000K", "5000K", "5600K", "6500K", "7500K"),
                selected = { it == "${kelvin}K" },
                onSelect = { onWbChanged(it.dropLast(1).toInt(), tint, false) }
            )
        }
    }
}

@Composable
private fun FocusDialPanel(
    diopters: Float,
    autoFocus: Boolean,
    onFocusChanged: (Float, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialHeader(
            "Focus ${FocusCalculator.formatFocusDistance(diopters)}",
            "AF",
            autoFocus
        ) { onFocusChanged(diopters, it) }
        if (!autoFocus) {
            Slider(
                value = diopters,
                onValueChange = { onFocusChanged(it, false) },
                valueRange = 0.0f..15.0f
            )
            ChoiceChipRow(
                options = listOf("∞", "2 m", "1 m", "50 cm", "20 cm", "10 cm"),
                selected = {
                    (it == "∞" && diopters <= 0.05f) ||
                        (it == "2 m" && diopters in 0.45f..0.55f) ||
                        (it == "1 m" && diopters in 0.9f..1.1f) ||
                        (it == "50 cm" && diopters in 1.9f..2.1f) ||
                        (it == "20 cm" && diopters in 4.8f..5.2f) ||
                        (it == "10 cm" && diopters >= 9.5f)
                },
                onSelect = {
                    val d = when (it) {
                        "∞" -> 0.0f
                        "2 m" -> 0.5f
                        "1 m" -> 1.0f
                        "50 cm" -> 2.0f
                        "20 cm" -> 5.0f
                        else -> 10.0f
                    }
                    onFocusChanged(d, false)
                }
            )
        }
    }
}

@Composable
private fun FpsDialPanel(
    currentFps: Double,
    supportedFps: List<Double> = emptyList(),
    onFpsChanged: (Double) -> Unit
) {
    val displayFps = when {
        kotlin.math.abs(currentFps - 23.976) < 0.01 -> "23.98"
        kotlin.math.abs(currentFps - 29.97) < 0.01 -> "29.97"
        currentFps == currentFps.toLong().toDouble() -> currentFps.toLong().toString()
        else -> "%.2f".format(currentFps)
    }
    val allOptions = listOf("23.98", "24", "25", "29.97", "30", "48", "60")
    val maxCap = (supportedFps.maxOrNull() ?: 60.0) + 0.5
    val options = allOptions.filter { opt ->
        val v = when (opt) {
            "23.98" -> 23.976
            "29.97" -> 29.97
            else -> opt.toDoubleOrNull() ?: 30.0
        }
        v <= maxCap
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialHeader("Project Frame Rate ($displayFps fps)")
        ChoiceChipRow(
            options = options,
            selected = {
                when (it) {
                    "23.98" -> kotlin.math.abs(currentFps - 23.976) < 0.01
                    "29.97" -> kotlin.math.abs(currentFps - 29.97) < 0.01
                    else -> currentFps == it.toDouble()
                }
            },
            onSelect = {
                val fps = when (it) {
                    "23.98" -> 23.976
                    "29.97" -> 29.97
                    else -> it.toDouble()
                }
                onFpsChanged(fps)
            }
        )
    }
}

private fun formatTimecode(ms: Long, fps: Double): String {
    val targetFps = if (fps > 0) fps else 30.0
    val totalSec = (ms / 1000).toInt()
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    val frames = (((ms % 1000) / 1000.0) * targetFps).toInt().coerceIn(0, (targetFps - 1).toInt())
    return "%02d:%02d:%02d:%02d".format(hrs, mins, secs, frames)
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L * 1024L -> "%.0f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
}
