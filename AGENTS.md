# AGENTS.md — RawRec

RAW sensor video recorder for Android (Camera2 `RAW_SENSOR` → own "RVSP" container → CinemaDNG export). Kotlin/Compose app + native C++ (zstd, MIPI packers) + desktop CLI (`rvtool`, pure Kotlin run on desktop JVM).

Project root: `D:\RawRec`.

## Commands

```powershell
# Build + unit tests (ALWAYS pass -p — agents run from arbitrary CWDs)
& 'D:\RawRec\gradlew.bat' -p 'D:\RawRec' :app:assembleDebug :app:testDebugUnitTest --console=plain -q

# Run a single test class
& 'D:\RawRec\gradlew.bat' -p 'D:\RawRec' :app:testDebugUnitTest --tests 'dev.rawrec.app.codec.MipiPackerTest' --console=plain -q

# Deploy to device (adb lives in-repo, not on PATH)
& 'D:\RawRec\tools\adb\adb.exe' install -r 'D:\RawRec\app\build\outputs\apk\debug\app-debug.apk'
& 'D:\RawRec\tools\adb\adb.exe' shell am force-stop dev.rawrec.app
& 'D:\RawRec\tools\adb\adb.exe' shell am start -n dev.rawrec.app/.MainActivity

# Pull device logs — use the FILE logger, NOT logcat (camera HAL floods the logcat
# buffer and evicts our lines within seconds)
& 'D:\RawRec\tools\adb\adb.exe' pull /sdcard/Android/data/dev.rawrec.app/files/logs/rawrec.log
# Fatal crash traces: CrashHandler writes persistent traces to crash.log
& 'D:\RawRec\tools\adb\adb.exe' pull /sdcard/Android/data/dev.rawrec.app/files/logs/crash.log
# Native abort traces only: logcat -d -b crash

# ADB Remote Control (Debug builds only — zero-token automation, no shell input tap/swipe):
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "record_toggle"
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_aspect" --ei val 2      # 0=Full, 1=2.39:1, 2=16:9, 3=4:3, 4=1:1
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_fill_fraction" --ef val 0.5  # 0.0=Accurate, 1.0=Fill screen
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_stretch" --ei val 1    # 1=anamorphic Stretch (whole frame, no crop), 0=off
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_rotation_override" --ei val 0 # 0, 90, 180, 270 (omit val to clear)
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "open_settings"           # or "close_settings"
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_orientation_mode" --es val "CINEMA_LANDSCAPE" # or "CHASSIS_LOCKED"
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_iso" --ei val 400
& 'D:\RawRec\tools\adb\adb.exe' shell am broadcast -a dev.rawrec.CONTROL --es cmd "set_auto_disable_vf" --ei val 10 # 0=Never, 5, 10, 30, 60s auto-disable during recording

# Desktop CLI (from any CWD; builds tool classes if stale, resolves kotlin-stdlib
# from the gradle cache and zstd-jni from tools\libs)
powershell -NoProfile -File 'D:\RawRec\tools\rvtool.ps1' <gen|info|stats|bmp|rgb|extract|batch|dngcheck|wav|mp4|gui> ...
#   rgb/extract/mp4 take --tone=<id> (default/cine_filmic/cine_hlg/...), --lut=<cube>,
#   extract adds --bake-tone (grade raw pixels) vs default DNG tags 50936/50981;
#   batch <inDir> <outDir> [dng|mp4] [profile] [bake] exports all takes in a directory;
#   all decode paths take --gpu=<opencl|vulkan|cpu> (OpenCL default);
#   mp4 = BT.2100 HLG export (colr.nclx) with 16-bit PCM audio
#   gui = Cinema studio deck (Phase 7): SMPTE timecode transport,
#   LOOP repeat playback, frame stepping, synchronized 48kHz audio transport,
#   ISO & exposure time telemetry curve plots (CurvePlotPanel), scopes (peaking/
#   false color/zebras/RGB+luma histogram with IRE grids), Engine + Look + LUT
#   selectors, exports (BMP/RGB/WAV/DNG sequence/HLG MP4) and batch dialog.
```

Test suite: 192 tests / 25 classes, all pure JVM, ~3s. Keep them green before deploying.

## Toolchain pins (do not bump casually)

- AGP 8.7.3, compileSdk 36, Kotlin 2.0.20, Gradle 8.9, NDK 27.0.12077973, CMake 3.22.1 (SDK at `D:\Android_SDK`, pinned in `local.properties`)
- **material3 `1.5.0-alpha16` — deliberate.** 1.4.0-stable has `MaterialExpressiveTheme`/`MotionScheme` internal (unusable); ≥alpha19 requires AGP 9.1 + compileSdk 37 (not installed). alpha16 is the only usable M3 Expressive release. M3E APIs need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- zstd 1.5.6 vendored in `app/src/main/cpp/third_party/zstd/` (built with `ZSTD_MULTITHREAD`); zstd-jni jar vendored in `tools\libs\` for desktop decode only.
- `librawrec.so` must stay **16 KB page aligned** (`-Wl,-z,max-page-size=16384` in CMakeLists; verify LOAD segments = `0x4000` with llvm-readelf).

## Architecture

Capture pipeline (in `capture/RecordingController.kt`) — order and ownership matter:

```
camera callback (while-loop drain) → inQ[8] (Image ownership TRANSFERS, maxImages=12 bounds this)
  → "rvsp-extract" thread: native 2-way parallel SliceWorker packMipi10DirectInto onto recycled payloadPool[15] buffer, closes Image immediately
  → midQ[12] → zstd pool (4 workers × ZSTD -3, PERSISTENT CCtx; recycles raw buffer to payloadPool on finish)
  → outQ[12] → "rvsp-writer" (also drains audioQ[64] PCM chunks)
```

- **Teardown order in `stop()` is load-bearing** (wrong order = native SIGSEGV):
  `stopping=true → audio.stop() → extractor join → proxyEncoder.finish() → engine.stop() → pool shutdown → writer join → thermal.stop() → perfSession.end() → fgs.stop()`.
- Never re-add per-frame `ZSTD_createCCtx` calls (pool churn caused a 4× throughput regression).
- Camera thread must never block or allocate: read `img.timestamp` BEFORE `close()`; **`acquireNextImage` (FIFO) in an internal while-loop drain is used for lossless video recording** (single acquire left burst frames in BufferQueue that permanently silenced the edge-triggered callback, freezing the viewfinder; `acquireLatestImage` is deprecated). `maxImages >= inCapacity + 3` must hold.
- **Zero-Allocation Buffer Pool (`payloadPool[15]`)**: 15 pre-allocated 15.7 MB `ByteArray` buffers circulating between extractor, Zstd workers, and payloadPool. Eliminates ~860 MB/s of transient Java heap allocations and eliminates 30ms ART GC freezes.
- **2-Way Sliced Row-Parallel NEON Packing (`rawrec_jni.cpp`)**: `SliceWorker` thread (`THREAD_PRIORITY_URGENT_DISPLAY`) packs bottom half of rows while caller packs top half across two performance cores via `pack_row` vectors (`pack16_neon`). Drops MIPI RAW10 packing latency from 38ms to 2.4–5.4ms per frame.
- **Auto-Disable Viewfinder Option (`AppPreferences.autoDisableVfSeconds`)**: Setting (Never/5s/10s/30s/60s) to blank the live viewfinder during recording to an OLED pitch-black telemetry HUD with tap-to-wake, cutting GPU and display power draw to guarantee locked 30 FPS.
- **SoC & Thermal Governor (`ThermalGovernor`, `SocOptimizer.tuningFor`)**: registers Android `OnThermalStatusChangedListener`, polls 10s headroom forecast (`getThermalHeadroom`), and listens to Xiaomi `action_temp_state_change` broadcasts. On MODERATE/SEVERE escalation, rescales ONLY the zstd pool (never the camera session); CRITICAL surfaces via `RecStats.error`.
- **ADPF & Scheduling (`PerfSession`)**: registers pipeline thread TIDs in an ADPF `PerformanceHintManager` hint session with target = frame period; reports per-frame work duration to steer Linux EAS/DVFS governors. Broadcasts `record_start`/`record_end` to `com.miui.powerkeeper` to lift camera power limits under HyperOS.
- **Android 14/15 Process Survival (`CaptureForegroundService`)**: active recordings run inside a foreground service with `foregroundServiceType="camera"`, an ongoing notification, and a partial wake lock, preventing HyperOS and Android 15 from freezing backgrounded camera cgroups.
- **Do NOT set `SENSOR_FRAME_DURATION` in auto/AE mode** — the F6 HAL throttles RAW readout to ~14fps when frame duration is forced alongside AE (measured 2026-08-29; auto mode uses `AE_TARGET_FPS_RANGE(30,30)` only; manual mode pins both). The recording request builder is shared by start()/updateControls() in `RawCaptureEngine.buildRequest()` — keep it that way.
- Frame↔metadata pairing is timestamp-latched (`RawCaptureEngine.ResultPairing`): accept when |resultTs−imgTs| ≤ ½ frame period, stale results are dropped (0=unknown sentinel downstream), future results stay latched for the next image. Don't revert to unvalidated latest-wins.
- Sensor colorimetry (blackLevel from `SENSOR_BLACK_LEVEL_PATTERN` remapped to header's R,Gr,Gb,B order; ColorMatrix1 = direct copy of `SENSOR_COLOR_TRANSFORM1`, AOSP DngCreator semantics — never invert/recombine; illuminant in metaJson `calibIlluminant1`) flows characteristics → `RvspHeader` → `writeDng` tags 50714/50721/50728/50778. **Never hardcode identity ColorMatrix/AsShotNeutral in `writeDng`** — it consumes the header now.
- Never reintroduce a **MediaCodec encoder** for the proxy: Qualcomm CCodec (`c2.qti.*`) on SM8635/Android 15 SIGSEGVs deterministically in ByteBuffer mode during RAW sessions (fixed fault addr in vendor HAL, unfixable from app space). The proxy is pure-software MJPEG via `YuvImage.compressToJpeg()` + a hand-rolled ISO MP4 muxer (`proxy/Mp4ContainerWriter.kt`).

## UI conventions

- Material 3 Expressive: `MaterialExpressiveTheme` + `MotionScheme.expressive()` in `ui/theme/RawRecTheme.kt`.
- Reuse shared components (`ui/components/`): `SectionCard`, `SettingRow`, `StatChip`, `IconToggle`, `ChoiceChipRow`, `ControlPill`, `Hudpill`, `CinemaParameterTile`/`CinemaParameterDock`/`CinemaParameterGrid`, `CinemaFocusRail`, plus `RawRecIcons` (hand-drawn stroke icons). Do not invent per-screen styling variants.
- Theme roles only in interactive UI — no hardcoded hex colors. Exception: Canvas overlays (framing masks, histogram) intentionally use literal black/white.
- **`RotatedContent` is for wide-short→tall-sheet blocks only (dial panels, pill content)**: it measures content with swapped constraints in landscape and reports the ROTATED bounding box as the slot. Never wrap wrap-content horizontal strips (HUD pill, chip rows) — the reported slot flips to tall-narrow and the Surface becomes a vertical bar. Wide strips stay window-horizontal per the pinned-layout rule; only glyph-scale elements rotate via `Modifier.rotateIcon()`.
- **Cinema screen layout is orientation-adaptive** (`CinemaViewfinderScreen` branches on `Configuration.orientation`): landscape = single-row studio header + right `CinemaParameterDock` (220dp) + sliding dial panel to its left + bottom focus rail inset `end=240dp`; portrait (Chassis Locked) = 2-row header, and a lower deck with full-width focus rail + 2×3 `CinemaParameterGrid` + centered record button, dial panels slide UP as a full-width drawer. Never use one fixed-width layout for both orientations.
- **Studio header telemetry**: SMPTE timecode `HH:MM:SS:FF` from `elapsedMs` × `targetFps` (`formatTimecode`); during REC also a size badge (`formatSize(stats.bytesWritten)`) and dropped-frames badge (`N DROP` — cyan at 0, bold crimson when >0).
- **Scope overlays (Peaking/False Color/Zebras) MUST be drawn inside `clipRect(activeRect) { rotate(vfRotation, pivot=center) }`** around the unrotated `k·bufW × k·bufH` rect. `TextureView.getBitmap()` (the `ScopeAnalyzer` tap) returns the RAW UN-ROTATED buffer — AOSP readback runs with `useLayerTransform=false` and skips `setTransform` — so a flat `drawImage` renders overlays 90°/270° off the feed (fixed 2026-09-07). The fix lives in the draw scope only; `ViewfinderMath`/`FixedViewfinder` are not involved.
- Cinema screen stays edge-to-edge; the floating bottom `NavigationBar` (MainActivity) is drawn OVER it and remains visible/tappable during recording by design.
- **rvtool desktop GUI** (`RvtoolGui.kt`, Swing) mirrors the cinema studio deck palette (Titanium Slate `#0E1118`, Electric Cyan `#00E5FF`, Tally Crimson `#FF2D55`) via custom `DeckButton`/`DeckToggleButton`/`DeckSliderUI`/`DeckComboBox` controls. It exists in TWO synced copies — `tools/gui/` and `rvtool/gui/` (launcher picks whichever exists; keep them identical). Playback defaults LOOP on (`isRepeat`); PLAY pressed at the last frame auto-rewinds to 0.
- **Cinema look/tone suite is CLEAN-ROOM math** (`ColorScience.kt`): 16 `ToneProfile`s modeled from published transfer functions (filmic sigmoids, Rec.2100 HLG OETF, OOTF) — no proprietary curves, firmware tables, or trademarked LUTs. Looks flow CLI (`--tone=`), GUI dropdown, `.cube` LUT import, GPU kernels, DNG tags 50936/50981 (`--bake-tone` for destructive), and HLG MP4 export (`colr.nclx`). GPU backends: OpenCL (default) / Vulkan / CPU via `tool/gpu/GpuManager` (`--gpu=`).

## Container format gotchas (`docs/container-spec-draft.md` is authoritative)

- 512-byte header; **metaJson blob lives at file offset 512** (not after the metaJsonSize field at 232).
- metaJson keys written by the recorder: `cameraId`, `packedMipi10`, `calibIlluminant1`, `timestampSource` (0=unknown, 1=REALTIME — audio and frame records share the elapsedRealtime base when 1), `framingAspect` + `cropRegion` (WYSIWYG crop in full-sensor coords; absent = full frame), plus `audioSampleRate`/`audioChannels`/`audioFormat` when mic was on.
- Payload order: **pack first (MIPI RAW10, 4px→5B), compress second** — zstd input is the packed bytes.
- CinemaDNG output (`Rvtool.extract`): **single-IFD0** layout (NewSubFileType=0, no SubIFD chain — OCTOPUS RAW Player freezes on the two-IFD form), TIFF tag **51044 (FrameRate)** is mandatory, and audio must go to `<outDir>\audio\audio.wav` — OCTOPUS scans `*.wav` top-level and its WPF MediaPlayer path crashes on them.
- Header color fields: `blackLevel` is stored in canonical **R,Gr,Gb,B** channel order (remapped from the sensor's 2×2 row-column-scan order at record time — `SensorCalibration.blackLevelToChannelOrder`); `colorMatrix` row-major 3×3 at header offset 68; `asShotNeutral` at offset 104. `rvtool gen` writes non-default values so the header→DNG colorimetry path is device-free testable.

## Device facts (test device)

- POCO F6 (`peridot`, **SM8635 Snapdragon 8s Gen 3** — not Dimensity; adb serial `99c95387`), Android 15/HyperOS.
- Main raw mode: 4096×3072 (12MP binned), BGGR (cfa=3), whiteLevel 1023 (10-bit), rowStride 8192 == contiguous, pixelStride 2.
- **Preview buffer selection is RAW-ASPECT-FIRST, target ~0.69MP in the selected RAW mode's shape** (`FixedViewfinder.selectBufferSize(supported, rawAspect)` — default 4:3 = 960×720 for the F6's binned mode; the cinema screen passes the selected RAW size's aspect so future non-4:3 modes get a matching buffer): near-target-aspect SurfaceTexture sizes (|ratio−rawAspect| ≤ 0.02) win, closest in area to the ~0.69MP target among them; area-closeness only decides when no near-target size exists, and then only among LANDSCAPE (w ≥ h) candidates (portrait buffers mis-present the chassis-locked viewfinder). (The old chooser's aspect penalty was dead code: `abs(ratio−4/3).toLong()` truncates to 0.) Never switch the preview buffer per aspect mode or rebuild the session for framing — a stock-HyperOS camera decompilation (`tools\apk_out\Camera\sources\`, 2026-08-31) confirmed Xiaomi also keeps ONE screen-shaped buffer always and letters via GL + crop tags; RawRec keeps one small buffer instead (a deliberate ~4× bandwidth/power cut vs the old 1920×1440 target; sufficient for a representational 6.7″ viewfinder). All viewfinder presentation modes and the WYSIWYG recorded crop assume sensor-aspect content.
- **Camera + RAW size selection is SHARED state** (`recCamSel`/`recSizeSel` in `RawRecApp`, written via Settings' Source dropdowns, read by the preview `LaunchedEffect` and BOTH start paths): the cinema record button and Settings' start button record the *selected* size, not silently the largest. The 2026-09-02 fix unified the old split brain — the Settings dropdown was composable-local while the cinema path re-probed and always took `rawSizes.firstOrNull()`. Camera switch resets the size to that camera's largest RAW mode.
- **TextureView `setTransform` convention (pinned from AOSP TextureView.java + Open Camera's configureTransform)**: the matrix maps VIEW coordinates — the buffer is pre-stretched ANISOTROPICALLY to fill the view first. Correct entries to present an effW×effH crop at scale k (view-px per buffer-px): **`a = k·bw/viewW`, `d = k·bh/viewH`** where `bw/bh` are swapped to `bufH/bufW` when rotation is 90° or 270° (they undo the anisotropic pre-stretch per axis; final buffer-px→view-px is k in BOTH axes = square sensor pixels), translation to center, rotation about the content center. **Never write buffer-pixel scales (a=d=s) directly** — that rendered content into x∈[−1198,1100] on portrait 1220×2712/1920×1440, a 139px pure-black right bar + ~3.5× zoom (measured 2026-08-30). Pure builder: `ViewfinderMath.contentTransform`/`presentationScale`, tested in `ViewfinderTransformTest`.
- **FixedViewfinder: ALL transform paths must share the composed `scaleState` holder** — `wire()` must never construct a local `ScaleHolder`. A local holder made every surface-event re-application (recording-session handover, buffer-size resets, preview self-heal) render FILL/no-aspect, silently resetting the chosen framing mode ("aspect stuck on default", 2026-08-31). The factory also runs before first layout (`tv.width==0` → degenerate 1×1 transform), so the transform is re-applied on every view-size change via the global-layout listener.
- **Viewfinder orientation & OrientationMode (Cinema Landscape / Chassis Locked)**: On F6 (sensorOrientation 90°), in `OrientationMode.CINEMA_LANDSCAPE`, window is locked to landscape (`SCREEN_ORIENTATION_LANDSCAPE`) and the viewfinder feed is permanently anchored at `270°` (90° CCW), with ALL UI elements (icons, dials, text) locked permanently to horizontal (zero rotation). Rotating the phone does NOT rotate the viewfinder feed or UI elements. In `OrientationMode.CHASSIS_LOCKED`, window is locked to portrait (`SCREEN_ORIENTATION_PORTRAIT`) and rotation stays 0° (matching sensor chassis), with UI glyphs counter-rotating via `LocalIconRotation`. The framing OUTLINE and letterbox scrim match the WYSIWYG recorded crop.
- **Viewfinder aspect modes & Cinema Lookaround Scrim**: 0 Full (100% bright, full sensor), 1 Widescreen (2.39:1 center band), 2 16:9 (16:9 center band), 3 4:3 (native sensor frame), 4 1:1 (square center crop). To prevent operator blindness from solid black bars, the non-recorded area is rendered as a **Cinema Scrim (55% dimmed semi-transparent matte)**, matching SmallHD/ARRI/RED field monitors so off-frame subjects, actors, and boom mics remain visible ("lookaround"). The active recording window is 100% full brightness, enclosed by a 2px white border (`Color(0xDDFFFFFF)`). The Aspect button displays the active ratio label directly on the button. Gridlines is a dedicated toggle in the scopes bar that overlays rule-of-thirds over the active recording frame.
- **Presentation options (Settings "Presentation Framing")**: **Stretch chip** switches to anamorphic mode (`ScaleMode.STRETCH`) where the slider (`fillFraction` = `stretchFrac`) drives `stretchAxisScales` — per-BUFFER-axis scales interpolating from the square-pixel FIT scale (frac=0, letterboxed) to full edge-to-edge anamorphic fill (frac=1). The whole frame stays visible at every position (no crop ever, only progressive deformation); at 90/270 feed rotation buffer-x renders along screen-Y so the axis targets swap. Transform via `contentTransformStretchFrac`, overlay/outline via `activeFramingRectStretchFrac` (same scales — outline always sits exactly on the presented content). Without Stretch, the slider is the old UNIFORM zoom between FIT and FILL (square pixels, crop past 0% unavoidable). Persisted as `AppPreferences.presentationStretch`; ADB `set_stretch` --ei 0/1.
- **Framing crop (WYSIWYG)**: viewfinder aspect modes 1 (2.39:1), 2 (16:9), and 4 (1:1) store **center-cropped** frames (4096×1712, 4096×2304, and 3072×3072 on the 12MP mode) via the **packer-level crop** (`MipiPacker.cropRect` + native `packMipi10CroppedDirect`/`packMipi10ProxyCroppedDirect`); Full / 4:3 store the full sensor. Crop origin/dims are always multiples of 4 (Bayer parity + MIPI groups). **Never use `SCALER_CROP_REGION` for RAW on this HAL** — delivered-buffer semantics are HAL-dependent and a smaller delivered buffer would make the fixed-size native pack read past the gralloc buffer (SIGSEGV). Software crop also keeps the sensor capture path untouched.
- **Resolution probing**: `CameraCatalog` probes standard `RAW_SENSOR`, `getHighResolutionOutputSizes`, and Android 12+ `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` (50MP unbinned on IMX882) so both binned and unbinned modes are available. The resolution is switchable both from the top HUD pill on the Cinema screen and the Settings Source card.
- Benchmarks on file: capture locks ~30fps; UFS sustained write ceiling ~665 MB/s; Pack+Zstd ≈ 96.5% frame retention at ~50% ratio; Pack-only = 0 drops.

## Testing quirks

- Unit tests run on plain JVM: `org.json:json` is a test-only dependency so `JSONObject` works (android.jar stubs throw). Native lib is NOT loadable in JVM tests — `RawPackNative.loaded` is false there; any code under test must have a JVM fallback path.
- `Rvtool.kt` lives in the app module so Gradle compiles it; it runs on desktop via `tools/rvtool.ps1` using `app/build/tmp/kotlin-classes/debug` + kotlin-stdlib from `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\...`. After editing it, rebuild `:app:assembleDebug` before the script picks up changes (script skips build if classes exist — delete `app/build/tmp/kotlin-classes` to force).
- **`RvtoolGui.kt` lives in `tools/gui/`** (NOT in app sources) — Swing is absent from android.jar, so no android source set can compile it. `rvtool.ps1` compiles it on demand with the embeddable Kotlin compiler from the gradle cache (recipe in the script — needs compiler + script-runtime + coroutines-1.9.0 + trove4j + annotations jars on the COMPILER classpath, else NoClassDefFoundError), output to `app/build/tmp/kotlin-classes/rvtool-gui` (stamp-cached). `Rvtool.main("gui")` dispatches by reflection. After editing the GUI, just re-run the script.
- FrameRec now carries `tsNs`/`iso` (record-header offsets 8/24); Rvtool's scan/decode helpers (`openHeader`, `scanRecords`, `readPayload`, `decodedPayload`, `quadRgb`, `luma8Of`, `extract`) are public — GUI and CLI share them.

## Misc

- **`material-3` skill installed** (`.opencode/skills/material-3/`, from github.com/hamen/material-3-skill): MD3/M3E guidance, component catalog, theming refs, and an MD3 compliance-audit procedure. Load it (skill tool) before non-trivial Compose UI work; its token/role rules match this repo's conventions. Note its web/@material/web sections are irrelevant here.
- `bypassing-qualcomm-isp-for-raw-sensor-image.json` (repo root) and `docs/history/` are archived session transcripts — context only, ignore during normal work.
- `tools/recordings/` holds pulled test footage and prior CinemaDNG extractions; large, don't read wholesale.
- Camera id 0 (`hasRaw`) is auto-selected everywhere; `CameraCatalog.load()` is cheap enough to call inline.
