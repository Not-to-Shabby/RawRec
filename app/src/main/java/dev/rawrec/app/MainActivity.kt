@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.rawrec.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rawrec.app.capture.RecStats
import dev.rawrec.app.capture.RecordingController
import dev.rawrec.app.codec.FrameCodecs
import dev.rawrec.app.codec.MipiPacker
import dev.rawrec.app.codec.RawSampleReader
import dev.rawrec.app.inspect.FrameInspector
import dev.rawrec.app.inspect.GrayPreview
import dev.rawrec.app.probe.CamInfo
import dev.rawrec.app.probe.CameraCatalog
import dev.rawrec.app.probe.ModeProbe
import dev.rawrec.app.util.CrashHandler
import dev.rawrec.app.ui.components.RawRecIcons
import dev.rawrec.app.ui.components.SectionCard
import dev.rawrec.app.ui.components.SettingRow
import dev.rawrec.app.ui.components.StaggeredAppear
import dev.rawrec.app.ui.components.StatChip
import dev.rawrec.app.ui.theme.RawRecTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Hide system bars entirely (Open Camera's edge-to-edge immersive approach)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(
            androidx.core.view.WindowInsetsCompat.Type.systemBars()
        )
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val logsDir = java.io.File(getExternalFilesDir(null) ?: filesDir, "logs")
        dev.rawrec.app.util.AppLog.init(logsDir)
        dev.rawrec.app.util.CrashHandler.init(logsDir)
        setContent {
            RawRecTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RawRecApp()
                }
            }
        }
    }
}

@Composable
fun RawRecApp() {
    val ctx = LocalContext.current
    val prefs = remember { dev.rawrec.app.util.AppPreferences(ctx.applicationContext) }
    val controller = remember { RecordingController(ctx.applicationContext) }
    val stats by controller.stats.collectAsState()
    var cameraControls by remember { mutableStateOf(prefs.loadCameraControlState()) }
    // Record-config toggles hoisted from SettingsScreen so the cinema record
    // button honors them too, now persisted to AppPreferences.
    var recUseZstd by remember { mutableStateOf(prefs.useZstd) }
    var recPackMipi by remember { mutableStateOf(prefs.packMipi) }
    var recMicOn by remember { mutableStateOf(prefs.micOn) }
    var recProxyOn by remember { mutableStateOf(prefs.proxyOn) }
    var previewSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    var galleryInitialFile by remember { mutableStateOf<File?>(null) }
    var galleryInitialExportFile by remember { mutableStateOf<File?>(null) }
    // Camera + RAW size selection — SHARED by preview and BOTH start paths
    var recCamSel by remember { mutableStateOf<CamInfo?>(null) }
    var recSizeSel by remember { mutableStateOf<Size?>(null) }
    var allCams by remember { mutableStateOf<List<CamInfo>>(emptyList()) }
    var vfRotationOverride by remember { mutableStateOf<Int?>(null) }
    var vfOrientationMode by remember { mutableStateOf(prefs.orientationMode) }
    var autoDisableVfSeconds by remember { mutableStateOf(prefs.autoDisableVfSeconds) }
    // Viewfinder presentation: 0f = 0% Accurate (FIT), 1f = 100% Fill screen (FILL).
    var vfFillFraction by remember { mutableStateOf(prefs.fillFraction) }
    var vfImmersive by remember { mutableStateOf(prefs.fillFraction >= 0.5f) }
    // Stretch: anamorphic presentation — whole frame fills the screen, no crop (non-square pixels).
    var vfStretch by remember { mutableStateOf(prefs.presentationStretch) }
    // Live viewfinder telemetry: sensor, display rotation, applied angle, bufW, bufH
    var vfTelemetry by remember { mutableStateOf("viewfinder: idle") }
    // Live device-orientation debug state
    var deviceOrientation by remember { mutableStateOf("unknown") }
    var displayRotationName by remember { mutableStateOf("unknown") }
    var displayRotationDeg by remember { mutableStateOf(0) }
    // Our OWN rotation engine: ui rotation driven by accelerometer (activity locked to portrait)
    var uiRotation by remember { mutableStateOf(0) }
    // Viewfinder rendering backend and live 3D LUT
    var vfBackend by remember { mutableStateOf(prefs.vfBackend) }
    var activeLut by remember { mutableStateOf<dev.rawrec.tool.CubeLut?>(null) }

    LaunchedEffect(prefs.activeLutPath) {
        val path = prefs.activeLutPath
        activeLut = if (path != null) {
            val f = java.io.File(path)
            if (f.exists()) runCatching { dev.rawrec.tool.CubeLut.parse(f) }.getOrNull() else null
        } else null
    }

    // Dynamic Activity requestedOrientation based on selected OrientationMode (fixed locked orientations, never spinning with gyro)
    LaunchedEffect(vfOrientationMode) {
        val activity = ctx as? android.app.Activity ?: return@LaunchedEffect
        val target = when (vfOrientationMode) {
            dev.rawrec.app.ui.ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            dev.rawrec.app.ui.ViewfinderMath.OrientationMode.CHASSIS_LOCKED ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }

    // Keep screen awake at all times while camera is in foreground to prevent display timeout / lock
    val currentView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val window = (ctx as? android.app.Activity)?.window
        currentView.keepScreenOn = true
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            currentView.keepScreenOn = false
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Lifecycle observer: cleanly stop the camera preview when the app is backgrounded
    // (Home button / App switch) unless recording is active (which is protected by foreground service).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, stats.active) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    if (!stats.active) {
                        controller.stopPreview()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val cam = recCamSel
                    val surf = previewSurface
                    if (surf != null && surf.isValid && cam != null && !stats.active) {
                        if (ContextCompat.checkSelfPermission(
                                ctx, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            controller.startPreview(cam.id, surf, cameraControls)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Physical orientation (which edge is up) — accelerometer-driven, with hysteresis
    DisposableEffect(Unit) {
        val tracker = dev.rawrec.app.util.DeviceOrientationTracker(ctx) { orientation, angle ->
            if (orientation != deviceOrientation) {
                deviceOrientation = orientation
                uiRotation = angle
                dev.rawrec.app.util.AppLog.i(
                    "Orientation", "device orientation: $orientation -> ui=$angle°"
                )
            }
        }
        tracker.start()
        onDispose { tracker.stop() }
    }

    // Display rotation (locked or sensor) — window-driven; configChanges absorbs
    // rotation events, so poll while the app is visible for the debug readout.
    fun refreshDisplayRotation() {
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE)
            as android.view.WindowManager
        val rot = wm.defaultDisplay.rotation
        displayRotationDeg = when (rot) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        displayRotationName = dev.rawrec.app.util.rotationName(rot)
    }
    LaunchedEffect(Unit) {
        while (true) {
            refreshDisplayRotation()
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(Unit) {
        val cams = runCatching { CameraCatalog.load(ctx) }.getOrElse { emptyList() }
        allCams = cams
        val rawCams = cams.filter { it.hasRaw && it.rawSizes.isNotEmpty() }
        val savedCamId = prefs.cameraId
        val savedSize = prefs.rawSize
        val matchedCam = rawCams.firstOrNull { it.id == savedCamId } ?: rawCams.firstOrNull()
        val matchedSize = if (matchedCam != null && savedSize != null && matchedCam.rawSizes.contains(savedSize)) {
            savedSize
        } else {
            matchedCam?.rawSizes?.firstOrNull()
        }
        recCamSel = matchedCam
        recSizeSel = matchedSize
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true && previewSurface != null && !stats.active) {
            val cams = runCatching { CameraCatalog.load(ctx) }.getOrElse { emptyList() }
            val cam = cams.firstOrNull { it.hasRaw && it.rawSizes.isNotEmpty() }
            if (cam != null) {
                controller.startPreview(cam.id, previewSurface!!, cameraControls)
            }
        }
    }

    LaunchedEffect(Unit) {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.READ_MEDIA_VIDEO)
            need.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val ungranted = need.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }

        // On Android 11+ check All Files Access for /sdcard/RawRec/
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    ).apply {
                        data = android.net.Uri.parse("package:${ctx.packageName}")
                    }
                    ctx.startActivity(intent)
                }
            }
        }
    }

    // ADB Remote Control for automated/device control without consuming tokens on touch coordinates
    val isDebug = true
    if (isDebug) {
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val cmd = intent.getStringExtra("cmd") ?: return
                    dev.rawrec.app.util.AppLog.i("AdbControl", "Received adb command: $cmd")
                    when (cmd) {
                        "record_toggle" -> {
                            if (stats.active) {
                                Thread { controller.stop() }.start()
                            } else {
                                val cam = recCamSel ?: runCatching { CameraCatalog.load(ctx) }
                                    .getOrNull()?.firstOrNull { it.hasRaw && it.rawSizes.isNotEmpty() }
                                val size = recSizeSel ?: cam?.rawSizes?.firstOrNull()
                                if (cam != null && size != null) {
                                    Thread {
                                        controller.start(
                                            cameraId = cam.id,
                                            size = size,
                                            physicalCameraId = cam.physicalId,
                                            autoExposure = cameraControls.autoExposure,
                                            useZstd = recUseZstd,
                                            packMipi10 = recPackMipi,
                                            mic = recMicOn,
                                            proxy = recProxyOn,
                                            previewSurface = previewSurface,
                                            controls = cameraControls,
                                            uiRotation = uiRotation
                                        )
                                    }.start()
                                }
                            }
                        }
                        "record_start" -> {
                            if (!stats.active) {
                                val cam = recCamSel ?: runCatching { CameraCatalog.load(ctx) }
                                    .getOrNull()?.firstOrNull { it.hasRaw && it.rawSizes.isNotEmpty() }
                                val size = recSizeSel ?: cam?.rawSizes?.firstOrNull()
                                dev.rawrec.app.util.AppLog.i("RawRecApp", "record_start: cam=${cam?.displayName}, size=$size, rawSizes=${cam?.rawSizes}")
                                if (cam != null && size != null) {
                                    Thread {
                                        controller.start(
                                            cameraId = cam.id,
                                            size = size,
                                            physicalCameraId = cam.physicalId,
                                            autoExposure = cameraControls.autoExposure,
                                            useZstd = recUseZstd,
                                            packMipi10 = recPackMipi,
                                            mic = recMicOn,
                                            proxy = recProxyOn,
                                            previewSurface = previewSurface,
                                            controls = cameraControls,
                                            uiRotation = uiRotation
                                        )
                                    }.start()
                                }
                            }
                        }
                        "record_stop" -> {
                            if (stats.active) {
                                Thread { controller.stop() }.start()
                            }
                        }
                        "open_settings" -> settingsOpen = true
                        "close_settings" -> settingsOpen = false
                        "open_gallery" -> {
                            galleryInitialFile = null
                            galleryOpen = true
                        }
                        "close_gallery" -> {
                            galleryOpen = false
                            galleryInitialFile = null
                            galleryInitialExportFile = null
                        }
                        "play_take" -> {
                            val takes = controller.listRecordings()
                            if (takes.isNotEmpty()) {
                                galleryInitialFile = takes.first()
                                galleryOpen = true
                            }
                        }
                        "play_raw" -> {
                            val takes = controller.listRecordings()
                            val rawTake = takes.firstOrNull { it.name.endsWith(".rvsp", ignoreCase = true) }
                            if (rawTake != null) {
                                galleryInitialFile = rawTake
                                galleryOpen = true
                            }
                        }
                        "export_take" -> {
                            val takes = controller.listRecordings()
                            val rawTake = takes.firstOrNull { it.name.endsWith(".rvsp", ignoreCase = true) }
                            if (rawTake != null) {
                                galleryInitialExportFile = rawTake
                                galleryOpen = true
                            }
                        }
                        "set_aspect" -> {
                            val idx = intent.getIntExtra("val", 0).coerceIn(0, 4)
                            cameraControls = cameraControls.copy(aspectIndex = idx)
                            controller.updateControls(cameraControls)
                            prefs.saveCameraControlState(cameraControls)
                        }
                        "set_fill_fraction" -> {
                            val frac = intent.getFloatExtra("val", 0f).coerceIn(0f, 1f)
                            vfFillFraction = frac
                            vfImmersive = frac >= 0.5f
                            prefs.fillFraction = frac
                        }
                        "set_stretch" -> {
                            val on = intent.getIntExtra("val", 0) != 0
                            vfStretch = on
                            prefs.presentationStretch = on
                        }
                        "set_rotation_override" -> {
                            val deg = if (intent.hasExtra("val")) intent.getIntExtra("val", 0) else null
                            vfRotationOverride = deg
                        }
                        "set_orientation_mode" -> {
                            val modeStr = intent.getStringExtra("val") ?: ""
                            val mode = dev.rawrec.app.ui.ViewfinderMath.OrientationMode.values()
                                .firstOrNull { it.name.equals(modeStr, ignoreCase = true) || it.label.contains(modeStr, ignoreCase = true) }
                            if (mode != null) {
                                vfOrientationMode = mode
                                prefs.orientationMode = mode
                            }
                        }
                        "set_iso" -> {
                            val iso = intent.getIntExtra("val", 100)
                            cameraControls = cameraControls.copy(iso = iso, autoIso = false, autoExposure = false)
                            controller.updateControls(cameraControls)
                            prefs.saveCameraControlState(cameraControls)
                        }
                        "set_auto_disable_vf" -> {
                            val secs = intent.getIntExtra("val", 0).coerceAtLeast(0)
                            autoDisableVfSeconds = secs
                            prefs.autoDisableVfSeconds = secs
                        }
                        "set_storage" -> {
                            val path = intent.getStringExtra("path")
                            prefs.customStoragePath = if (path.isNullOrBlank() || path == "default" || path == "internal") null else path
                        }
                        "set_vf_backend" -> {
                            val backendStr = intent.getStringExtra("val") ?: ""
                            val backend = if (backendStr.contains("gles", ignoreCase = true) || backendStr.contains("gl", ignoreCase = true)) {
                                dev.rawrec.app.util.AppPreferences.ViewfinderBackend.OPENGL_ES
                            } else {
                                dev.rawrec.app.util.AppPreferences.ViewfinderBackend.TEXTURE_VIEW
                            }
                            vfBackend = backend
                            prefs.vfBackend = backend
                        }
                        "set_vf_lut" -> {
                            val path = intent.getStringExtra("path")
                            val newPath = if (path.isNullOrBlank() || path == "none" || path == "clear") null else path
                            prefs.activeLutPath = newPath
                            activeLut = if (newPath != null) {
                                val f = java.io.File(newPath)
                                if (f.exists()) runCatching { dev.rawrec.tool.CubeLut.parse(f) }.getOrNull() else null
                            } else null
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter("dev.rawrec.CONTROL")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            onDispose {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    // Cinema is the whole app now — the preview always runs (recording takes
    // over the surface; stop() resumes preview automatically). Re-keys on the
    // selected camera so a Settings camera switch restarts the preview.
    LaunchedEffect(previewSurface, recCamSel) {
        val cam = recCamSel
        if (previewSurface != null && cam != null && !stats.active) {
            if (ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                controller.startPreview(cam.id, previewSurface!!, cameraControls, cam.physicalId)
            }
        }
    }

    // Relative icon rotation: in Cinema Landscape, the entire studio layout and all UI elements
    // are locked to horizontal landscape (zero rotation). In Chassis Locked (portrait monitor view),
    // glyphs rotate via accelerometer to follow the operator.
    val relativeUiRotation = if (vfOrientationMode == dev.rawrec.app.ui.ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE) {
        0f
    } else {
        ((uiRotation - displayRotationDeg + 360) % 360).toFloat()
    }
    val effectiveUiRotation = if (vfOrientationMode == dev.rawrec.app.ui.ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE) 0 else uiRotation
    androidx.compose.runtime.CompositionLocalProvider(
        dev.rawrec.app.ui.components.RawRecUi.LocalIconRotation provides relativeUiRotation
    ) {
        Box(Modifier.fillMaxSize()) {
            dev.rawrec.app.ui.CinemaViewfinderScreen(
                stats = stats,
                controls = cameraControls,
                histogram = null,
                supportedPreviewSizes = recCamSel?.previewSizes.orEmpty(),
                sensorOrientation = recCamSel?.sensorOrientation ?: 90,
                rotationOverride = vfRotationOverride,
                uiRotation = effectiveUiRotation,
                orientationMode = vfOrientationMode,
                immersive = vfImmersive,
                fillFraction = vfFillFraction,
                stretchMode = vfStretch,
                autoDisableVfSeconds = autoDisableVfSeconds,
                vfBackend = vfBackend,
                activeLut = activeLut,
                rawAspect = recSizeSel?.let { it.width.toDouble() / it.height } ?: (4.0 / 3.0),
                selectedRawSize = recSizeSel,
                availableRawSizes = recCamSel?.rawSizes.orEmpty(),
                supportedFps = recCamSel?.supportedFps ?: listOf(24.0, 30.0),
                maxAnalogIso = recCamSel?.maxAnalogIso ?: 0,
                selectedCamera = recCamSel,
                availableCameras = allCams,
                onCameraSelected = { cam ->
                    recCamSel = cam
                    val newSize = cam.rawSizes.firstOrNull()
                    recSizeSel = newSize
                    prefs.cameraId = cam.id
                    prefs.rawSize = newSize
                },
                onRawSizeSelected = {
                    recSizeSel = it
                    prefs.rawSize = it
                },
                onTelemetry = { sensor, disp, angle, bufW, bufH ->
                    vfTelemetry = "sensor=$sensor° disp=$disp applied=$angle° buf=${bufW}x$bufH"
                },
                onControlsChanged = { updated ->
                    cameraControls = updated
                    controller.updateControls(updated)
                    prefs.saveCameraControlState(updated)
                },
                onPreviewSurfaceAvailable = { surface ->
                    previewSurface = surface
                },
                onOpenSettings = { settingsOpen = true },
                onOpenGallery = { galleryOpen = true },
                onRecordToggle = {
                    if (stats.active) {
                        Thread { controller.stop() }.start()
                    } else {
                        // Selected camera + size (shared state); fall back to
                        // the default first-hasRaw camera only if the initial
                        // catalog load failed.
                        val cam = recCamSel ?: runCatching { CameraCatalog.load(ctx) }
                            .getOrNull()?.firstOrNull { it.hasRaw && it.rawSizes.isNotEmpty() }
                        val size = recSizeSel ?: cam?.rawSizes?.firstOrNull()
                        dev.rawrec.app.util.AppLog.i("RawRecApp", "onRecordToggle: cam=${cam?.displayName}, size=$size, rawSizes=${cam?.rawSizes}")
                        if (cam != null && size != null) {
                            Thread {
                                controller.start(
                                    cameraId = cam.id,
                                    size = size,
                                    autoExposure = cameraControls.autoExposure,
                                    useZstd = recUseZstd,
                                    packMipi10 = recPackMipi,
                                    mic = recMicOn,
                                    proxy = recProxyOn,
                                    previewSurface = previewSurface,
                                    controls = cameraControls,
                                    uiRotation = uiRotation
                                )
                            }.start()
                        }
                    }
                }
            )

            // Settings — full-screen overlay opened from the deck's gear button
            if (settingsOpen) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Settings",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = { settingsOpen = false }) { Text("Close") }
                            }
                        }
                        SettingsScreen(
                            controller, stats,
                            autoExposure = cameraControls.autoExposure,
                            recUseZstd = recUseZstd,
                            recPackMipi = recPackMipi,
                            recMicOn = recMicOn,
                            recProxyOn = recProxyOn,
                            onRecordConfigChange = { zstd, pack, mic, proxy ->
                                recUseZstd = zstd; recPackMipi = pack
                                recMicOn = mic; recProxyOn = proxy
                                prefs.useZstd = zstd; prefs.packMipi = pack
                                prefs.micOn = mic; prefs.proxyOn = proxy
                            },
                            vfRotationOverride = vfRotationOverride,
                            onVfRotationOverride = { vfRotationOverride = it },
                            vfOrientationMode = vfOrientationMode,
                            onOrientationModeChange = {
                                vfOrientationMode = it
                                prefs.orientationMode = it
                            },
                            vfFillFraction = vfFillFraction,
                            onVfFillFractionChange = {
                                vfFillFraction = it
                                vfImmersive = it >= 0.5f
                                prefs.fillFraction = it
                            },
                            vfImmersive = vfImmersive,
                            onVfPresentationChange = {
                                vfImmersive = it
                                vfFillFraction = if (it) 1f else 0f
                                prefs.fillFraction = vfFillFraction
                            },
                            vfStretch = vfStretch,
                            onStretchChange = {
                                vfStretch = it
                                prefs.presentationStretch = it
                            },
                            autoDisableVfSeconds = autoDisableVfSeconds,
                            onAutoDisableVfSecondsChange = {
                                autoDisableVfSeconds = it
                                prefs.autoDisableVfSeconds = it
                            },
                            recCamSel = recCamSel,
                            recSizeSel = recSizeSel,
                            onSourceChange = { cam, size ->
                                recCamSel = cam; recSizeSel = size
                                prefs.cameraId = cam?.id
                                prefs.rawSize = size
                            },
                            vfBackend = vfBackend,
                            onVfBackendChange = {
                                vfBackend = it
                                prefs.vfBackend = it
                            },
                            activeLutPath = prefs.activeLutPath,
                            onActiveLutPathChange = {
                                prefs.activeLutPath = it
                            },
                            vfTelemetry = vfTelemetry,
                            deviceOrientation = deviceOrientation,
                            displayRotationName = displayRotationName,
                            uiRotation = uiRotation
                        )
                    }
                }
            }

            // Studio Gallery — full-screen overlay opened from the viewfinder's gallery button
            if (galleryOpen) {
                dev.rawrec.app.ui.gallery.GalleryScreen(
                    controller = controller,
                    initialFile = galleryInitialFile,
                    initialExportFile = galleryInitialExportFile,
                    onClose = {
                        galleryOpen = false
                        galleryInitialFile = null
                        galleryInitialExportFile = null
                    }
                )
            }
        }
    }
}

@Composable
private fun ProbeCard() {
    val ctx = LocalContext.current
    var lines by remember {
        mutableStateOf(listOf("tap Run to enumerate camera capabilities"))
    }

    SectionCard(title = "Sensor probe", icon = RawRecIcons.Probe) {
        Button(onClick = {
            lines = runCatching {
                val report = ModeProbe().run(ctx)
                val file = ModeProbe().save(ctx, report)
                listOf("Report saved: ${file.name}") + report
            }.getOrElse { listOf("probe failed: $it") }
        }) { Text("Run probe") }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashLogCard() {
    var hasCrashes by remember { mutableStateOf(CrashHandler.hasCrashLogs()) }
    var showDialog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    SectionCard(title = "Diagnostics & Crash logs", icon = RawRecIcons.Probe) {
        if (hasCrashes) {
            val file = CrashHandler.crashFile()
            val sizeKb = (file?.length() ?: 0L) / 1024L
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Crash log detected (${sizeKb} KB)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "A fatal crash was captured and saved to crash.log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        logText = CrashHandler.readCrashLog()
                        showDialog = true
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("View crash trace")
                }
                OutlinedButton(onClick = {
                    CrashHandler.clearCrashLogs()
                    hasCrashes = CrashHandler.hasCrashLogs()
                }) {
                    Text("Clear log")
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "● System healthy (No crashes recorded)",
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Uncaught exceptions and fatal crashes are saved to crash.log automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Fatal Crash Report") },
            text = {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    val scroll = rememberScrollState()
                    Text(
                        text = logText.ifBlank { "No log content" },
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp).verticalScroll(scroll)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showDialog = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    CrashHandler.clearCrashLogs()
                    hasCrashes = CrashHandler.hasCrashLogs()
                    showDialog = false
                }) {
                    Text("Delete log")
                }
            }
        )
    }
}

@Composable
private fun SettingsScreen(
    controller: RecordingController,
    stats: RecStats,
    autoExposure: Boolean = true,
    recUseZstd: Boolean = true,
    recPackMipi: Boolean = true,
    recMicOn: Boolean = false,
    recProxyOn: Boolean = false,
    onRecordConfigChange: (Boolean, Boolean, Boolean, Boolean) -> Unit = {_,_,_,_ ->},
    recCamSel: CamInfo? = null,
    recSizeSel: Size? = null,
    onSourceChange: (CamInfo?, Size?) -> Unit = { _, _ -> },
    vfRotationOverride: Int? = null,
    onVfRotationOverride: (Int?) -> Unit = {},
    vfOrientationMode: dev.rawrec.app.ui.ViewfinderMath.OrientationMode = dev.rawrec.app.ui.ViewfinderMath.OrientationMode.CINEMA_LANDSCAPE,
    onOrientationModeChange: (dev.rawrec.app.ui.ViewfinderMath.OrientationMode) -> Unit = {},
    vfFillFraction: Float = 0f,
    onVfFillFractionChange: (Float) -> Unit = {},
    vfImmersive: Boolean = false,
    onVfPresentationChange: (Boolean) -> Unit = {},
    vfStretch: Boolean = false,
    onStretchChange: (Boolean) -> Unit = {},
    autoDisableVfSeconds: Int = 0,
    onAutoDisableVfSecondsChange: (Int) -> Unit = {},
    vfBackend: dev.rawrec.app.util.AppPreferences.ViewfinderBackend = dev.rawrec.app.util.AppPreferences.ViewfinderBackend.TEXTURE_VIEW,
    onVfBackendChange: (dev.rawrec.app.util.AppPreferences.ViewfinderBackend) -> Unit = {},
    activeLutPath: String? = null,
    onActiveLutPathChange: (String?) -> Unit = {},
    vfTelemetry: String = "viewfinder: idle",
    deviceOrientation: String = "unknown",
    displayRotationName: String = "unknown",
    uiRotation: Int = 0
) {
    val ctx = LocalContext.current
    val prefs = remember { dev.rawrec.app.util.AppPreferences(ctx) }

    var cams by remember { mutableStateOf<List<CamInfo>>(emptyList()) }
    var camMenuOpen by remember { mutableStateOf(false) }
    var sizeMenuOpen by remember { mutableStateOf(false) }
    var wantStart by remember { mutableStateOf(false) }
    var inspectFile by remember { mutableStateOf<File?>(null) }
    val camSel = recCamSel
    val sizeSel = recSizeSel

    LaunchedEffect(Unit) {
        cams = runCatching { CameraCatalog.load(ctx) }.getOrElse { emptyList() }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (controller.stats.value.active) controller.stop()
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true && wantStart) {
            wantStart = false
	            camSel?.let { c -> sizeSel?.let { s ->
	                Thread {
	                    controller.start(
	                        c.id, s,
	                        physicalCameraId = c.physicalId,
	                        autoExposure = autoExposure,
	                        useZstd = recUseZstd,
	                        packMipi10 = recPackMipi,
	                        mic = recMicOn,
	                        proxy = recProxyOn,
	                        uiRotation = uiRotation
	                    )
	                }.start()
	            } }
	        }
	    }

	    LazyColumn(
	        Modifier.fillMaxSize().padding(16.dp),
	        verticalArrangement = Arrangement.spacedBy(12.dp)
	    ) {
	        item {
	            Text("Settings", style = MaterialTheme.typography.headlineSmall)
	        }

	        item {
	            StaggeredAppear(index = 1) {
	                SectionCard(title = "Source", icon = RawRecIcons.Camera) {
	                    Text(
	                        "Camera Lens:",
	                        style = MaterialTheme.typography.labelMedium,
	                        color = MaterialTheme.colorScheme.onSurface
	                    )
	                    if (cams.isNotEmpty()) {
	                        dev.rawrec.app.ui.components.ChoiceChipRow(
	                            options = cams.map { it.displayName },
	                            selected = { opt ->
	                                cams.firstOrNull { it.displayName == opt }?.let {
	                                    it.id == camSel?.id && it.physicalId == camSel?.physicalId
	                                } == true
	                            },
	                            onSelect = { label ->
	                                cams.firstOrNull { it.displayName == label }?.let { selected ->
	                                    onSourceChange(selected, selected.rawSizes.firstOrNull())
	                                }
	                            }
	                        )
	                    }
	                    Text(
	                        camSel?.let { c ->
	                            "${c.facing.replaceFirstChar { it.uppercase() }} lens · ${c.hardwareLevel} hardware level" +
	                                (if (c.hasRaw) " · ${c.rawSizes.size} RAW mode(s)" else " · noRAW (Preview only)")
	                        } ?: "No camera selected",
	                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
	                        color = MaterialTheme.colorScheme.primary
	                    )

	                    Spacer(Modifier.height(4.dp))
	                    Text(
	                        "RAW Sensor Mode:",
	                        style = MaterialTheme.typography.labelMedium,
	                        color = MaterialTheme.colorScheme.onSurface
	                    )
	                    val sizes = camSel?.rawSizes.orEmpty()
	                    if (sizes.isNotEmpty()) {
	                        dev.rawrec.app.ui.components.ChoiceChipRow(
	                            options = sizes.map { CameraCatalog.formatShortResolution(it) },
	                            selected = { opt ->
	                                sizeSel?.let { CameraCatalog.formatShortResolution(it) } == opt
	                            },
	                            onSelect = { label ->
	                                sizes.firstOrNull { CameraCatalog.formatShortResolution(it) == label }?.let { s ->
	                                    onSourceChange(camSel, s)
	                                }
	                            }
	                        )
	                    } else {
	                        Text(
	                            "This physical sensor does not advertise RAW_SENSOR output on this device firmware.",
	                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
	                            color = MaterialTheme.colorScheme.error
	                        )
	                    }
	                }
	            }
	        }

        item {
            StaggeredAppear(index = 2) {
                SectionCard(title = "Pipeline", icon = RawRecIcons.Layers) {
                    // These drive BOTH start paths (Settings button + cinema
                    // record button) — state lives in RawRecApp. Exposure mode
                    // lives in the manual controls (cameraControls.autoExposure).
                    SettingSwitch("Zstd compression", recUseZstd, !stats.active) {
                        onRecordConfigChange(it, recPackMipi, recMicOn, recProxyOn)
                    }
                    SettingSwitch(
                        "MIPI-10 packing",
                        recPackMipi,
                        !stats.active && (camSel?.whiteLevel ?: 0) <= 1023
                    ) { onRecordConfigChange(recUseZstd, it, recMicOn, recProxyOn) }
                    SettingSwitch("Microphone", recMicOn, !stats.active) {
                        onRecordConfigChange(recUseZstd, recPackMipi, it, recProxyOn)
                    }
                    SettingSwitch(
                        "Proxy video",
                        recProxyOn,
                        !stats.active && recPackMipi
                    ) { onRecordConfigChange(recUseZstd, recPackMipi, recMicOn, it) }
                }
            }
        }

        item {
            StaggeredAppear(index = 3) {
                SectionCard(title = "Storage & Destination", icon = RawRecIcons.Folder) {
                    var customPath by remember { mutableStateOf(prefs.customStoragePath) }
                    val volumes = remember { controller.getStorageVolumes() }
                    val activeDir = controller.recordingsDir()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Active Target: ${activeDir.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val freeGB = activeDir.freeSpace.toDouble() / (1 shl 30)
                        val totalGB = activeDir.totalSpace.toDouble() / (1 shl 30)
                        Text(
                            "Free Space: %.1f GB / %.1f GB".format(freeGB, totalGB),
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            "Detected Storage Volumes (Tap to Select):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        volumes.forEach { vol ->
                            val isSelected = (customPath == null && vol.isPrimary) || (customPath == vol.path.absolutePath)
                            val volFreeGB = vol.freeBytes.toDouble() / (1 shl 30)
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(!stats.active) {
                                        val newPath = if (vol.isPrimary) null else vol.path.absolutePath
                                        prefs.customStoragePath = newPath
                                        customPath = newPath
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (vol.isRemovable) "💾 ${vol.description} (External USB-C)" else "📱 ${vol.description} (Internal)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            vol.path.absolutePath,
                                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        "%.1f GB free".format(volFreeGB),
                                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Text(
                            if (customPath != null)
                                "Writing directly to external target volume via high-speed POSIX I/O. If disconnected, recordings auto-fallback to internal storage."
                            else
                                "Default internal storage selected (/sdcard/RawRec/).",
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            StaggeredAppear(index = 4) {
	                dev.rawrec.app.ui.components.SectionCard(
	                    title = "Viewfinder & Orientation",
	                    icon = RawRecIcons.Probe
	                ) {
	                    Text(
	                        "Viewfinder Pipeline Engine:",
	                        style = MaterialTheme.typography.labelMedium,
	                        color = MaterialTheme.colorScheme.onSurface
	                    )
	                    dev.rawrec.app.ui.components.ChoiceChipRow(
	                        options = dev.rawrec.app.util.AppPreferences.ViewfinderBackend.values().map { it.label },
	                        selected = { it == vfBackend.label },
	                        onSelect = { label ->
	                            val b = dev.rawrec.app.util.AppPreferences.ViewfinderBackend.values().firstOrNull { it.label == label }
	                            if (b != null) onVfBackendChange(b)
	                        }
	                    )
	                    Text(
	                        vfBackend.description,
	                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
	                        color = MaterialTheme.colorScheme.primary
	                    )

	                    Spacer(Modifier.height(6.dp))
	                    Text(
	                        "Orientation Architecture:",
	                        style = MaterialTheme.typography.labelMedium,
	                        color = MaterialTheme.colorScheme.onSurface
	                    )
                    dev.rawrec.app.ui.components.ChoiceChipRow(
                        options = dev.rawrec.app.ui.ViewfinderMath.OrientationMode.values().map { it.label },
                        selected = { it == vfOrientationMode.label },
                        onSelect = { label ->
                            val selected = dev.rawrec.app.ui.ViewfinderMath.OrientationMode.values().firstOrNull { it.label == label }
                            if (selected != null) onOrientationModeChange(selected)
                        }
                    )
                    Text(
                        vfOrientationMode.description,
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Presentation Framing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${(vfFillFraction * 100).toInt()}% " + when {
                                vfFillFraction <= 0.05f -> "(Accurate)"
                                vfFillFraction >= 0.95f -> "(Fill screen)"
                                else -> "(Hybrid)"
                            },
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = vfFillFraction,
                        onValueChange = onVfFillFractionChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "0% Accurate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "100% Fill screen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    dev.rawrec.app.ui.components.ChoiceChipRow(
                        options = listOf("0% Accurate", "50% Hybrid", "100% Fill", "Stretch"),
                        selected = { opt ->
                            when (opt) {
                                "0% Accurate" -> !vfStretch && vfFillFraction <= 0.05f
                                "50% Hybrid" -> !vfStretch && vfFillFraction in 0.45f..0.55f
                                "100% Fill" -> !vfStretch && vfFillFraction >= 0.95f
                                "Stretch" -> vfStretch
                                else -> false
                            }
                        },
                        onSelect = { label ->
                            when (label) {
                                "0% Accurate" -> {
                                    onStretchChange(false)
                                    onVfFillFractionChange(0f)
                                }
                                "50% Hybrid" -> {
                                    onStretchChange(false)
                                    onVfFillFractionChange(0.5f)
                                }
                                "100% Fill" -> {
                                    onStretchChange(false)
                                    onVfFillFractionChange(1f)
                                }
                                "Stretch" -> onStretchChange(true)
                            }
                        }
                    )
                    Text(
                        if (vfStretch)
                            "Anamorphic Stretch: whole frame fills the screen — zero crop, non-square pixels."
                        else
                            "Uniform scale (square pixels): high fill crops the frame edges.",
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                    dev.rawrec.app.ui.components.ChoiceChipRow(
                        options = dev.rawrec.app.ui.ViewfinderMath.OVERRIDE_OPTIONS
                            .map { dev.rawrec.app.ui.ViewfinderMath.formatOverride(it) },
                        selected = { it == dev.rawrec.app.ui.ViewfinderMath.formatOverride(vfRotationOverride) },
                        onSelect = { label ->
                            onVfRotationOverride(
                                dev.rawrec.app.ui.ViewfinderMath.OVERRIDE_OPTIONS.firstOrNull {
                                    dev.rawrec.app.ui.ViewfinderMath.formatOverride(it) == label
                                }
                            )
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Auto-Disable Viewfinder During Recording:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val autoDisableOptions = listOf(
                        0 to "Never",
                        5 to "5s",
                        10 to "10s",
                        30 to "30s",
                        60 to "60s"
                    )
                    dev.rawrec.app.ui.components.ChoiceChipRow(
                        options = autoDisableOptions.map { it.second },
                        selected = { opt ->
                            autoDisableOptions.firstOrNull { it.second == opt }?.first == autoDisableVfSeconds
                        },
                        onSelect = { label ->
                            autoDisableOptions.firstOrNull { it.second == label }?.let {
                                onAutoDisableVfSecondsChange(it.first)
                            }
                        }
                    )
                    Text(
                        if (autoDisableVfSeconds == 0)
                            "Live viewfinder stays active continuously throughout the take."
                        else
                            "Powers off live viewfinder rendering after $autoDisableVfSeconds seconds of recording to eliminate GPU/screen power draw and maintain consistent 30 FPS. Tap screen to wake.",
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "device orientation: $deviceOrientation",
                                style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "display rotation: $displayRotationName",
                                style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                vfTelemetry,
                                style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            StaggeredAppear(index = 4) {
                StatsCard(stats)
            }
        }

        item {
            StaggeredAppear(index = 4) {
                if (!stats.active) {
                    Button(
                        enabled = camSel != null && sizeSel != null,
                        onClick = {
                            val need = buildList {
                                add(Manifest.permission.CAMERA)
                                if (recMicOn) add(Manifest.permission.RECORD_AUDIO)
                            }.filter {
                                ContextCompat.checkSelfPermission(ctx, it) !=
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (need.isEmpty()) {
                                camSel?.let { c -> sizeSel?.let { s ->
                                    Thread {
                                        controller.start(
                                            c.id, s, autoExposure, recUseZstd,
                                            recPackMipi, recMicOn, recProxyOn,
                                            uiRotation = uiRotation
                                        )
                                    }.start()
                                } }
                            } else {
                                wantStart = true
                                permission.launch(need.toTypedArray())
                            }
                        },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start recording") }
                } else {
                    Button(
                        onClick = { Thread { controller.stop() }.start() },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("Stop recording") }
                }
            }
        }

        item {
            Text(
                "Recordings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(controller.listRecordings()) { f ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { inspectFile = f },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (f.name.endsWith(".mp4")) RawRecIcons.Movie else RawRecIcons.Record,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            f.name,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        "%.1f MB".format(f.length() / 1e6),
                        style = dev.rawrec.app.ui.theme.TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            StaggeredAppear(index = 6) {
                ProbeCard()
            }
        }

        item {
            StaggeredAppear(index = 7) {
                CrashLogCard()
            }
        }
    }

    inspectFile?.let { file ->
        InspectSheet(file = file, onDismiss = { inspectFile = null })
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        label = label,
        checked = checked,
        enabled = enabled,
        onCheckedChange = if (enabled) onChange else null
    )
}

@Composable
private fun StatsCard(s: RecStats) {
    SectionCard(title = "Capture status", icon = RawRecIcons.Grid) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChip(
                label = "fps",
                value = "%.1f".format(if (s.active) s.avgFps else 0.0),
                alert = s.active && s.avgFps < 25.0
            )
            StatChip(label = "captured", value = "${s.framesCaptured}")
            StatChip(label = "written", value = "${s.framesWritten}")
            StatChip(label = "dropped", value = "${s.dropped}", alert = s.dropped > 0)
        }
        Text(
            "%.1f MB · %.0f%% of raw · %ds".format(
                s.bytesWritten / 1e6, s.ratio * 100, s.elapsedMs / 1000
            ),
            style = dev.rawrec.app.ui.theme.TelemetryStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        s.error?.let {
            Text(
                "ERROR: $it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private data class InspectResult(
    val header: dev.rawrec.app.container.RvspHeader,
    val frameIndex: Int,
    val argb: IntArray,
    val diag: dev.rawrec.app.inspect.FrameDiagnostics
)

private fun loadInspect(file: File, wantIndex: Int): InspectResult {
    java.io.FileInputStream(file).use { fis ->
        val reader = dev.rawrec.app.container.RvspReader(fis)
        val h = reader.header
        var idx = 0
        var chosen: dev.rawrec.app.container.RvspRecord.Video? = null
        while (true) {
            val r = reader.nextRecord() ?: break
            if (r is dev.rawrec.app.container.RvspRecord.Video) {
                if (idx == wantIndex) { chosen = r; break }
                idx++
            }
        }
        val rec = chosen ?: error("file has fewer than ${wantIndex + 1} frames")
        val w = h.width; val hh = h.height; val n = w * hh
        val packed = h.packing == dev.rawrec.app.container.Rvsp.PACKING_MIPI_PACKED
        val expectedRaw = if (packed) MipiPacker.packedSize(n).toLong() else n * 2L
        val raw = FrameCodecs.byId(h.videoCodec).decode(rec.payload, expectedRaw)
        val samples = if (packed) MipiPacker.unpack(raw, n)
        else RawSampleReader.readU16LE(raw, w * 2, 2, w, hh)

        val diag = FrameInspector.diagnose(samples, w, hh, h.cfaPattern, h.whiteLevel)
        val blackAvg = h.blackLevel.average().toInt()
        val argb = GrayPreview.argb(samples, w, hh, h.whiteLevel, blackAvg)
        return InspectResult(h, idx, argb, diag)
    }
}

@Composable
private fun InspectSheet(file: File, onDismiss: () -> Unit) {
    var result by remember(file) { mutableStateOf<InspectResult?>(null) }
    var err by remember(file) { mutableStateOf<String?>(null) }
    var index by remember(file) { androidx.compose.runtime.mutableIntStateOf(2) }

    LaunchedEffect(file, index) {
        result = null; err = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { loadInspect(file, index) }
                .onSuccess { result = it }
                .onFailure { err = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(onClick = { index += 1 }) { Text("Next frame") }
        },
        title = {
            Text("Inspect: ${file.name}", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = result
                when {
                    err != null -> Text(
                        "failed: $err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    r == null -> Text(
                        "decoding frame $index…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {
                        val bmp = remember(r.argb) {
                            android.graphics.Bitmap.createBitmap(
                                GrayPreview.OUT_W, GrayPreview.OUT_H,
                                android.graphics.Bitmap.Config.ARGB_8888
                            ).apply {
                                setPixels(
                                    r.argb, 0, GrayPreview.OUT_W,
                                    0, 0, GrayPreview.OUT_W, GrayPreview.OUT_H
                                )
                            }.asImageBitmap()
                        }
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "frame preview",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${r.diag.verdict} · frame ${r.frameIndex} · " +
                                "${r.header.width}×${r.header.height} ${r.header.videoCodec}",
                            color = when {
                                r.diag.verdict.startsWith("OK") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            r.diag.detail,
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        r.diag.channels.forEach { c ->
                            Text(
                                "%s mean %.0f · std %.0f · [%d..%d]".format(
                                    c.name, c.mean, c.stdDev, c.min, c.max
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    )
}
