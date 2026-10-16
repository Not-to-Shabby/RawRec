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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.drawscope.scale
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
import dev.rawrec.app.ui.components.CinemaVerticalFocusWheel
import dev.rawrec.app.ui.components.CinemaParameterDock
import dev.rawrec.app.ui.components.CinemaParameterGrid
import dev.rawrec.app.ui.components.rotateIcon
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckTimecodeStyle
import androidx.compose.ui.platform.LocalContext

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
    micEnabled: Boolean = false,
    useZstd: Boolean = true,
    freeStorageBytes: Long = 0L,
    mirrorFront: Boolean = true,
    onMirrorFrontChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activePanel by remember { mutableStateOf(ActivePanel.NONE) }
    var controlsExpanded by remember { mutableStateOf(false) }
    var scopesExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    var peakingActive by remember { mutableStateOf(false) }
    var falseColorActive by remember { mutableStateOf(false) }
    var zebrasActive by remember { mutableStateOf(false) }
    var histogramActive by remember { mutableStateOf(true) }
    var gridActive by remember { mutableStateOf(false) }
    var vfBufW by remember { mutableStateOf(0) }
    var vfBufH by remember { mutableStateOf(0) }
    var vfRotation by remember { mutableStateOf(0) }
    val initialLookName = when (controls.toneProfile) {
        "cine_hlg" -> "HLG HDR"
        "cine_ootf" -> "Rec.709"
        "cine_warm" -> "Warm"
        "cine_cool" -> "Cool"
        "cine_vintage" -> "Vintage"
        "cine_bright" -> "Bright"
        "cine_mono" -> "B&W Mono"
        "default" -> "Linear"
        else -> "Filmic"
    }
    var selectedLook by remember(controls.toneProfile) { mutableStateOf(initialLookName) }

    // Battery Percentage Telemetry
    var batteryPct by remember { mutableIntStateOf(100) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val cap = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                if (cap in 1..100) batteryPct = cap
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    // Standby Stereo Audio Peak Monitor (dBFS) when mic is enabled and not recording
    var standbyAudioLeftDb by remember { mutableStateOf(-60f) }
    var standbyAudioRightDb by remember { mutableStateOf(-60f) }
    DisposableEffect(micEnabled, stats.active) {
        var monitorRec: dev.rawrec.app.audio.AudioRecorder? = null
        if (micEnabled && !stats.active) {
            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                runCatching {
                    val rec = dev.rawrec.app.audio.AudioRecorder(sampleRate = 48_000, channels = 2) { pcm, _ ->
                        val peaks = dev.rawrec.app.audio.AudioRecorder.computeStereoPeakDb(pcm)
                        standbyAudioLeftDb = peaks[0]
                        standbyAudioRightDb = peaks[1]
                    }
                    rec.start()
                    monitorRec = rec
                }
            }
        } else if (!micEnabled) {
            standbyAudioLeftDb = -60f
            standbyAudioRightDb = -60f
        }
        onDispose {
            runCatching { monitorRec?.stop() }
        }
    }
    val effectiveAudioLeftDb = if (stats.active) stats.audioPeakLeftDb else standbyAudioLeftDb
    val effectiveAudioRightDb = if (stats.active) stats.audioPeakRightDb else standbyAudioRightDb

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
    val isFrontCamera = selectedCamera?.facing == "front" || selectedCamera?.lensRole == dev.rawrec.app.probe.LensRole.FRONT
    // Rotation always follows the front sensor mounting; only the left-right flip is optional.
    val mirrorActive = isFrontCamera && mirrorFront
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
                                    if (mirrorActive) {
                                        scale(scaleX = -1f, scaleY = 1f, pivot = Offset(w / 2f, h / 2f)) {
                                            drawImage(
                                                image = overlayImg,
                                                dstOffset = IntOffset(overlayLeft.roundToInt(), overlayTop.roundToInt()),
                                                dstSize = IntSize(overlayW.roundToInt(), overlayH.roundToInt()),
                                                filterQuality = FilterQuality.Low
                                            )
                                        }
                                    } else {
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

                    // 2px Red Tally Perimeter Outline around the exact active camera frame when recording
                    if (stats.active) {
                        drawRect(
                            color = Color(0xFFFF2D55).copy(alpha = tallyAlpha),
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(6f)
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
            val r = ViewfinderMath.effectiveRotation(sensorOrientation, 0, rotationOverride, orientationMode, isFrontCamera)
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
                isFrontCamera = mirrorActive,
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
                isFrontCamera = isFrontCamera,
                mirrorFront = mirrorFront,
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

        val shutterLabel = if (controls.autoExposure) "AUTO" else if (controls.useShutterAngle) ShutterCalculator.formatShutterAngle(controls.shutterAngle) else ShutterCalculator.formatExposureFraction(controls.exposureNs)
        val isoLabel = if (controls.autoExposure) "AUTO" else controls.formattedIso
        val wbLabel = if (controls.autoWhiteBalance) "AUTO" else "${controls.whiteBalanceKelvin}K"
        val fpsLabel = "%.0f".format(if (stats.active) stats.avgFps else controls.targetFps)
        val resLabel = selectedRawSize?.let { CameraCatalog.formatCinemaGate(it, controls.aspectIndex) } ?: "GATE"
        val bitDepthNum = when (selectedCamera?.whiteLevel ?: 1023) {
            in 0..1023 -> 10
            in 1024..4095 -> 12
            else -> 14
        }
        val formatBadgeText = "$resLabel · ${bitDepthNum}-bit RAW"
        // Full sensor-mode detail: pixel dimensions and megapixels (e.g. "4096×3072 · 12.6MP")
        val resDetailText = selectedRawSize?.let {
            "${it.width}×${it.height} · ${String.format(java.util.Locale.US, "%.1f", it.width.toLong() * it.height / 1_000_000.0)}MP"
        }
        val remainingBytes = (freeStorageBytes - (if (stats.active) stats.bytesWritten else 0L)).coerceAtLeast(0L)
        val remainingGB = remainingBytes.toDouble() / (1L shl 30)
        val estimatedBytesPerSec = run {
            val sz = selectedRawSize
            val w = sz?.width ?: 4096
            val h = sz?.height ?: 3072
            val crop = dev.rawrec.app.codec.MipiPacker.cropRect(controls.framingAspect(false), w, h)
            val cw = crop?.get(2) ?: w
            val ch = crop?.get(3) ?: h
            val rawFrameBytes = (cw.toLong() * ch.toLong() * bitDepthNum) / 8L
            val ratio = if (useZstd) 0.52 else 1.0
            (rawFrameBytes * controls.targetFps * ratio).toLong().coerceAtLeast(10_000_000L)
        }
        val remainingMins = (remainingBytes / estimatedBytesPerSec) / 60L
        val storageBadgeText = "%.0fGB · ~%dm".format(remainingGB, remainingMins)

        val lensShortLabel = when (selectedCamera?.lensRole) {
            dev.rawrec.app.probe.LensRole.ULTRAWIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm UW" else "UW"
            dev.rawrec.app.probe.LensRole.WIDE -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm Wide" else "Wide"
            dev.rawrec.app.probe.LensRole.TELEPHOTO -> if (selectedCamera.focalLength35mmEq > 0f) "${selectedCamera.focalLength35mmEq.roundToInt()}mm Tele" else "Tele"
            dev.rawrec.app.probe.LensRole.FRONT -> "21mm Front"
            else -> "cam${selectedCamera?.id ?: 0}"
        }
        val onCycleLens: () -> Unit = {
            if (!stats.active && availableCameras.isNotEmpty()) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val currentIdx = availableCameras.indexOfFirst { it.id == selectedCamera?.id && it.physicalId == selectedCamera?.physicalId }
                val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % availableCameras.size else 0
                onCameraSelected?.invoke(availableCameras[nextIdx])
            }
        }

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

        val mapLookToProfileId: (String) -> String = { look ->
            when (look.lowercase()) {
                "hlg hdr", "hlg" -> "cine_hlg"
                "rec.709", "ootf" -> "cine_ootf"
                "warm" -> "cine_warm"
                "cool" -> "cine_cool"
                "vintage" -> "cine_vintage"
                "bright" -> "cine_bright"
                "b&w mono", "mono" -> "cine_mono"
                "linear", "natural" -> "default"
                else -> "cine_filmic"
            }
        }

        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                configuration.screenWidthDp < configuration.screenHeightDp

        // ─── Top Cinema Status Strip ───
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
                    // Left: M3 Expressive Timecode & Tally Stadium Capsule
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (stats.active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        text = if (stats.active) "REC" else "STBY",
                                        style = DeckLabelStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = if (stats.active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                text = if (stats.active) formatTimecode(stats.elapsedMs, controls.targetFps) else "00:00:00:00",
                                style = DeckTimecodeStyle,
                                color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            if (stats.active) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
                                    }
                                }
                            }
                        }
                    }

                    // Right: Unified M3 Expressive Telemetry & Navigation Capsule
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val effectiveHistogram = if (histogramActive) (liveHistogram ?: histogram) else null
                            if (effectiveHistogram != null) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.size(width = 72.dp, height = 36.dp)
                                ) {
                                    HistogramScopeView(
                                        histogram = effectiveHistogram,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp).fillMaxSize()
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = onOpenGallery,
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    RawRecIcons.Movie,
                                    contentDescription = "Gallery",
                                    modifier = Modifier.size(17.dp).rotateIcon()
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onOpenSettings,
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    RawRecIcons.Settings,
                                    contentDescription = "Settings",
                                    modifier = Modifier.size(17.dp).rotateIcon()
                                )
                            }
                        }
                    }
                }

                // Row 2: Centered M3 Expressive Scopes Floating Toolbar
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconToggle(RawRecIcons.Peaks, "Peaking", peakingActive, label = "Peak", compact = true) { peakingActive = !peakingActive }
                            IconToggle(RawRecIcons.FalseColor, "False color", falseColorActive, label = "False", compact = true) { falseColorActive = !falseColorActive }
                            IconToggle(RawRecIcons.Zebra, "Zebras", zebrasActive, label = "Zebra", compact = true) { zebrasActive = !zebrasActive }
                            IconToggle(RawRecIcons.Histogram, "Histogram", histogramActive, label = "Hist", compact = true) { histogramActive = !histogramActive }
                            IconToggle(RawRecIcons.Grid, "Grid", gridActive, label = "Grid", compact = true) { gridActive = !gridActive }
                            IconToggle(
                                icon = RawRecIcons.Aspect,
                                contentDescription = "Framing",
                                active = controls.aspectIndex > 0,
                                label = ASPECT_OPTIONS[controls.aspectIndex],
                                compact = true
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onControlsChanged(controls.copy(aspectIndex = (controls.aspectIndex + 1) % ASPECT_OPTIONS.size))
                            }
                            if (isFrontCamera && onMirrorFrontChange != null) {
                                IconToggle(
                                    icon = RawRecIcons.Mirror,
                                    contentDescription = "Mirror front camera",
                                    active = mirrorFront,
                                    label = if (mirrorFront) "Mirror" else "True",
                                    compact = true
                                ) { onMirrorFrontChange(!mirrorFront) }
                            }
                            if (availableCameras.size > 1) {
                                IconToggle(
                                    icon = RawRecIcons.LensIris,
                                    contentDescription = "Camera Lens",
                                    active = true,
                                    label = lensShortLabel.substringBefore(" "),
                                    compact = true
                                ) { onCycleLens() }
                            }
                        }
                    }
                }

                // Row 3: Slim telemetry strip (format, audio, storage, battery)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = resLabel,
                            style = DeckLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StereoAudioVuMeter(
                            enabled = micEnabled,
                            leftDb = effectiveAudioLeftDb,
                            rightDb = effectiveAudioRightDb
                        )
                        Text(
                            text = storageBadgeText,
                            style = DeckLabelStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$batteryPct%",
                            style = DeckLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = if (batteryPct <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            // ─── Landscape Consolidated Top Status Strip (Edge-to-Edge, Zero 4:3 Obstruction) ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Capsule: Tally + SMPTE Timecode + Cinema Format Badge + Live Luma Histogram
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (stats.active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = if (stats.active) "REC" else "STBY",
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = if (stats.active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            text = if (stats.active) formatTimecode(stats.elapsedMs, controls.targetFps) else "00:00:00:00",
                            style = DeckTimecodeStyle.copy(fontSize = 16.sp),
                            color = if (stats.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = if (stats.active) {
                                    "${formatSize(stats.bytesWritten)} · ${stats.dropped} DROP"
                                } else {
                                    formatBadgeText
                                },
                                style = DeckLabelStyle,
                                fontWeight = FontWeight.SemiBold,
                                color = if (stats.active && stats.dropped > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        val effectiveHistogram = if (histogramActive) (liveHistogram ?: histogram) else null
                        if (effectiveHistogram != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(width = 64.dp, height = 26.dp)
                            ) {
                                HistogramScopeView(
                                    histogram = effectiveHistogram,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxSize()
                                )
                            }
                        }
                    }
                }

                // Right Capsule: Stereo Audio dBFS Meter + Storage/Time Remaining + Battery % + Gallery + Settings
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Live Stereo Audio Level Meter (dBFS)
                        StereoAudioVuMeter(
                            enabled = micEnabled,
                            leftDb = effectiveAudioLeftDb,
                            rightDb = effectiveAudioRightDb
                        )

                        // 2. Remaining Storage & Estimated Recording Time
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = RawRecIcons.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = storageBadgeText,
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 3. Battery Percentage
                        Surface(
                            shape = CircleShape,
                            color = if (batteryPct <= 15) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, if (batteryPct <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = RawRecIcons.Battery,
                                    contentDescription = null,
                                    tint = if (batteryPct <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "$batteryPct%",
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = if (batteryPct <= 15) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 4. Studio Gallery & Settings
                        FilledTonalIconButton(
                            onClick = onOpenGallery,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                RawRecIcons.Movie,
                                contentDescription = "Gallery",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onOpenSettings,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                RawRecIcons.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
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
                // Slide-up Dial Panel Drawer (M3 Expressive extraLarge Sheet)
                AnimatedVisibility(
                    visible = activePanel != ActivePanel.NONE,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(bottom = 4.dp)
                    ) {
                        Box(
                            Modifier
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(14.dp)
                        ) {
                            ActiveDialContent(
                                activePanel = activePanel,
                                controls = controls,
                                selectedLook = selectedLook,
                                selectedRawSize = selectedRawSize,
                                availableRawSizes = availableRawSizes,
                                supportedFps = supportedFps,
                                maxAnalogIso = maxAnalogIso,
                                onControlsChanged = onControlsChanged,
                                onLookSelected = { look ->
                                    selectedLook = look
                                    onControlsChanged(controls.copy(toneProfile = mapLookToProfileId(look)))
                                },
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
                    focusPointA = controls.focusPointA,
                    focusPointB = controls.focusPointB,
                    rackDurationMs = controls.rackDurationMs,
                    isFixedFocus = selectedCamera?.isFixedFocus == true,
                    onFocusChanged = { d, af ->
                        onControlsChanged(controls.copy(focusDiopters = d, autoFocus = af))
                    },
                    onSetPointA = { pA ->
                        onControlsChanged(controls.copy(focusPointA = pA))
                    },
                    onSetPointB = { pB ->
                        onControlsChanged(controls.copy(focusPointB = pB))
                    },
                    onSetRackDuration = { dur ->
                        onControlsChanged(controls.copy(rackDurationMs = dur))
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
                    resDetail = resDetailText,
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

                // Record row: trigger exactly centered, gallery shortcut pinned to the left edge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(
                        onClick = onOpenGallery,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 28.dp)
                            .size(48.dp)
                    ) {
                        Icon(
                            RawRecIcons.Movie,
                            contentDescription = "Quick Gallery Review",
                            modifier = Modifier.size(20.dp).rotateIcon()
                        )
                    }

                    RecordButtonExpressive(
                        recording = stats.active,
                        onToggle = onRecordToggle,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        } else {
            // ─── Landscape "Clean Feed + Dual-Thumb Edge" Pillarbox Architecture ───
            // 1. Left Pillarbox (Left Thumb: Vertical Assist Column + Vertical Follow-Focus Wheel)
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp, top = 48.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Outer Left Column: Monitoring & Framing Assist Toggles
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 5.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconToggle(RawRecIcons.Peaks, "Peaking", peakingActive, label = "Peak") { peakingActive = !peakingActive }
                        IconToggle(RawRecIcons.FalseColor, "False color", falseColorActive, label = "False") { falseColorActive = !falseColorActive }
                        IconToggle(RawRecIcons.Zebra, "Zebras", zebrasActive, label = "Zebra") { zebrasActive = !zebrasActive }
                        IconToggle(RawRecIcons.Histogram, "Histogram", histogramActive, label = "Hist") { histogramActive = !histogramActive }
                        IconToggle(RawRecIcons.Grid, "Grid", gridActive, label = "Grid") { gridActive = !gridActive }
                        IconToggle(
                            icon = RawRecIcons.Aspect,
                            contentDescription = "Framing",
                            active = controls.aspectIndex > 0,
                            label = ASPECT_OPTIONS[controls.aspectIndex]
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onControlsChanged(controls.copy(aspectIndex = (controls.aspectIndex + 1) % ASPECT_OPTIONS.size))
                        }
                        if (isFrontCamera && onMirrorFrontChange != null) {
                            IconToggle(
                                icon = RawRecIcons.Mirror,
                                contentDescription = "Mirror front camera",
                                active = mirrorFront,
                                label = if (mirrorFront) "Mirror" else "True"
                            ) { onMirrorFrontChange(!mirrorFront) }
                        }
                    }
                }

                // Inner Left Column: Vertical Left-Thumb Follow-Focus Wheel & A-B Rack Puller
                CinemaVerticalFocusWheel(
                    currentDiopters = controls.focusDiopters,
                    autoFocus = controls.autoFocus,
                    focusPointA = controls.focusPointA,
                    focusPointB = controls.focusPointB,
                    rackDurationMs = controls.rackDurationMs,
                    isFixedFocus = selectedCamera?.isFixedFocus == true,
                    onFocusChanged = { d, af ->
                        onControlsChanged(controls.copy(focusDiopters = d, autoFocus = af))
                    },
                    onSetPointA = { pA ->
                        onControlsChanged(controls.copy(focusPointA = pA))
                    },
                    onSetPointB = { pB ->
                        onControlsChanged(controls.copy(focusPointB = pB))
                    },
                    onSetRackDuration = { dur ->
                        onControlsChanged(controls.copy(rackDurationMs = dur))
                    }
                )
            }

            // 2. Right Pillarbox (Right Thumb: Optical Lens Switcher, Compact 188dp Exposure Matrix, Isolated Record Trigger)
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp, top = 48.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Floating Dial Panel (M3 Expressive extraLarge Sheet — only visible when a parameter tile is active)
                AnimatedVisibility(
                    visible = activePanel != ActivePanel.NONE,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .width(296.dp)
                            .heightIn(max = 320.dp)
                            .padding(4.dp)
                    ) {
                        Box(
                            Modifier
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(14.dp)
                        ) {
                            ActiveDialContent(
                                activePanel = activePanel,
                                controls = controls,
                                selectedLook = selectedLook,
                                selectedRawSize = selectedRawSize,
                                availableRawSizes = availableRawSizes,
                                supportedFps = supportedFps,
                                maxAnalogIso = maxAnalogIso,
                                onControlsChanged = onControlsChanged,
                                onLookSelected = { look ->
                                    selectedLook = look
                                    onControlsChanged(controls.copy(toneProfile = mapLookToProfileId(look)))
                                },
                                onRawSizeSelected = onRawSizeSelected,
                                onDismiss = { activePanel = ActivePanel.NONE }
                            )
                        }
                    }
                }

                // Right Pillarbox Dock (188dp wide — zero overlap on the 4:3 frame)
                CinemaParameterDock(
                    fpsValue = fpsLabel,
                    shutterValue = shutterLabel,
                    isoValue = isoLabel,
                    wbValue = wbLabel,
                    lookValue = selectedLook,
                    resValue = resLabel,
                    resDetail = resDetailText,
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
                    lensLabel = if (availableCameras.size > 1) lensShortLabel else null,
                    onLensSwitch = if (availableCameras.size > 1) onCycleLens else null,
                    onTileClick = onTileClick,
                    onRecordToggle = onRecordToggle
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
                                .background(MaterialTheme.colorScheme.error.copy(alpha = tallyAlpha), CircleShape)
                        )
                        Text(
                            "REC",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            formatTimecode(stats.elapsedMs, controls.targetFps),
                            style = DeckTimecodeStyle.copy(fontSize = 32.sp),
                            color = MaterialTheme.colorScheme.onSurface
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("•", color = MaterialTheme.colorScheme.outline)
                        val drops = stats.dropped
                        Text(
                            if (drops == 0L) "0 DROP" else "$drops DROP",
                            style = dev.rawrec.app.ui.theme.TelemetryStyle.copy(
                                fontSize = 15.sp,
                                fontWeight = if (drops > 0) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (drops == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        if (stats.thermalStage != "NONE") {
                            Text("•", color = MaterialTheme.colorScheme.outline)
                            Text(
                                "TH: ${stats.thermalStage}",
                                style = dev.rawrec.app.ui.theme.TelemetryStyle.copy(fontSize = 15.sp),
                                color = MaterialTheme.colorScheme.tertiary
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
                        color = MaterialTheme.colorScheme.outline
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
    val looks = listOf("Filmic", "Rec.709", "HLG HDR", "Linear", "Warm", "Cool", "Vintage", "B&W Mono")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialHeader("Color Look / Monitoring ($currentLook)")
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
        val currentLabel = selectedSize?.let { CameraCatalog.formatCinemaGate(it) } ?: "GATE"
        DialHeader("Cinema Sensor Gate ($currentLabel)")
        ChoiceChipRow(
            options = availableSizes.map { CameraCatalog.formatResolution(it, availableSizes) },
            selected = { label -> selectedSize != null && CameraCatalog.formatResolution(selectedSize, availableSizes) == label },
            onSelect = { label ->
                availableSizes.firstOrNull { CameraCatalog.formatResolution(it, availableSizes) == label }?.let(onSizeSelected)
            }
        )
    }
}

/**
 * Compact dual-channel (L/R) Stereo Audio VU / Peak Meter in dBFS (-60dB .. 0dB)
 * with green/cyan normal range, amber (-12..-3dB) headroom warning, and crimson (>-3dB) clip segments.
 */
@Composable
private fun StereoAudioVuMeter(
    enabled: Boolean,
    leftDb: Float,
    rightDb: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val warnColor = MaterialTheme.colorScheme.tertiary
    val clipColor = MaterialTheme.colorScheme.error
    val dimTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (enabled) RawRecIcons.Mic else RawRecIcons.VolumeMute,
                contentDescription = "Audio Meter",
                tint = if (enabled) primaryColor else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(11.dp)
            )
            if (enabled) {
                Canvas(modifier = Modifier.size(width = 48.dp, height = 12.dp)) {
                    val segments = 10
                    val gap = 1.5f
                    val segW = (size.width - gap * (segments - 1)) / segments
                    val barH = (size.height - 2f) / 2f

                    val normL = ((leftDb.coerceIn(-60f, 0f) + 60f) / 60f)
                    val normR = ((rightDb.coerceIn(-60f, 0f) + 60f) / 60f)

                    for (s in 0 until segments) {
                        val thresh = (s + 1).toFloat() / segments
                        val segColor = when {
                            s >= 9 -> clipColor
                            s >= 7 -> warnColor
                            else -> primaryColor
                        }
                        val x = s * (segW + gap)
                        drawRect(
                            color = if (normL >= thresh) segColor else dimTrackColor,
                            topLeft = Offset(x, 0f),
                            size = Size(segW, barH)
                        )
                        drawRect(
                            color = if (normR >= thresh) segColor else dimTrackColor,
                            topLeft = Offset(x, barH + 2f),
                            size = Size(segW, barH)
                        )
                    }
                }
                val peakDb = maxOf(leftDb, rightDb).toInt()
                Text(
                    text = if (peakDb <= -59) "-∞dB" else "${peakDb}dB",
                    style = DeckLabelStyle.copy(fontSize = 8.sp),
                    color = when {
                        peakDb >= -3 -> clipColor
                        peakDb >= -12 -> warnColor
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            } else {
                Text(
                    text = "MIC OFF",
                    style = DeckLabelStyle.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
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
                Switch(
                    checked = autoChecked,
                    onCheckedChange = onAutoChange,
                    thumbContent = if (autoChecked) {
                        {
                            Icon(
                                imageVector = RawRecIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Returns a function that snaps a raw slider value to the nearest stop when
 * within [snapFraction] of the range, and ticks the haptic motor on each new
 * stop the thumb lands on.
 */
@Composable
private fun rememberStopSnapper(
    stops: List<Float>,
    range: ClosedFloatingPointRange<Float>,
    snapFraction: Float = 0.015f
): (Float) -> Float {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val lastStop = remember { mutableStateOf<Float?>(null) }
    val window = (range.endInclusive - range.start) * snapFraction
    return { v ->
        val near = stops.minByOrNull { kotlin.math.abs(it - v) }
        if (near != null && kotlin.math.abs(near - v) <= window) {
            if (lastStop.value != near) {
                lastStop.value = near
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
            near
        } else {
            lastStop.value = null
            v
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
            val snapIso = rememberStopSnapper(
                listOf(50f, 100f, 200f, 400f, 800f, 1600f, 3200f, 6400f), 50f..6400f
            )
            Slider(
                value = iso.toFloat(),
                onValueChange = { onIsoChanged(snapIso(it).toInt(), false) },
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
                val snapAngle = rememberStopSnapper(
                    listOf(45f, 90f, 144f, 172.8f, 180f, 270f, 360f), 10f..360f
                )
                Slider(
                    value = shutterAngle.toFloat(),
                    onValueChange = { onShutterChanged(exposureNs, snapAngle(it).toDouble(), true) },
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
            val snapKelvin = rememberStopSnapper(
                listOf(3200f, 4000f, 5000f, 5600f, 6500f, 7500f), 2000f..10000f
            )
            Slider(
                value = kelvin.toFloat(),
                onValueChange = { onWbChanged(snapKelvin(it).toInt(), tint, false) },
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
