# RawRec Knowledgebase

Synthesized from the full project session history:

- **opencode session** `ses_fc351662cffe` — 772 messages, Aug 26–29 2026
  (`C:\Users\Perch\Downloads\rawrec.json`, digest at `session-ses_fc35.md`).
- **Antigravity IDE session** — 1,196 transcript steps, Aug 27–28 2026
  (`C:\Users\Perch\.gemini\antigravity\brain\861491d7-...\.system_generated\logs\
  transcript_full.jsonl`), read directly; extracts also at `docs/history/antigravity_*.md`.
- Both continue an **original planning conversation** archived at repo root as
  `bypassing-qualcomm-isp-for-raw-sensor-image.json` (the project's session lineage is
  original-plan → Antigravity → opencode, each picking up the prior's transcript).

Where `AGENTS.md` states *rules*, this document records the *reasons, failed
alternatives, and debugging stories* behind them, so old mistakes are not re-made and
hard-won facts survive context loss.

Companion docs: `AGENTS.md` (operating rules) · `docs/PLAN.md` (phase status) ·
`docs/container-spec-draft.md` (authoritative container spec).

---

## 1. Origin: the "bypass the Qualcomm ISP" question

The project began as a research question, and the research conclusions still shape
every architectural decision.

**Hardware reality.** On Qualcomm SoCs you cannot wire the sensor directly to memory.
The MIPI CSI-2 stream must enter the chip through **CSIPHY → CSID**, and the **VFE/IFE**
does the final DMA. The minimum physical path is:

```
sensor → CSIPHY → CSID → IFE/VFE-RDI → DRAM
```

**RDI (Raw Dump Interface)** is the passthrough port inside IFE that skips every
processing block (demosaic, denoise, WB, sharpening). "Bypassing the ISP" in practice
means routing through RDI — pure DMA of the MIPI payload, no image processing.

**Three access routes were evaluated:**

| Route | Verdict |
|---|---|
| Mainline Linux CAMSS (V4L2: `media-ctl` links sensor→csiphy→csid→vfe_rdi, capture RAW10P) | Real but requires a mainline-capable board (Dragonboards, QCS kits). Retail 8-series phones have no mature mainline camera support. |
| Custom CHI usecase (SensorNode → IFE-RDI-only pipeline) | The "official" deep bypass, but camx/chi-cdk are proprietary prebuilts — needs a Qualcomm chipcode/CASA license to rebuild the HAL. Ruled out (rooted retail phone, no license). |
| **Camera2 `RAW_SENSOR`** | The "logical bypass" — on Qualcomm the RAW stream originates at the IFE RDI port, so frames are untouched Bayer while still crossing CamX → binder → app. **Chosen.** |

Config-only tricks on rooted Qualcomm devices exist (`/vendor/etc/camera/camxoverridesettings.txt`:
`overrideForceUsecaseId`, `overrideForceSensorMode`, `autoImageDump`) — these pin thinner
existing usecases and dump IFE raw to `/data/vendor/camera/`, useful for Phase 9 probing.
They cannot create sensor modes absent from the CamX sensor XML ("beyond spec" forcing
only works for modes the driver contains but the HAL doesn't advertise).

**Reference implementations studied:**
- **MotionCam** (open source: `f0enix/motioncam`, decoder spec: `mirsadm/motioncam-decoder`) —
  proves the Camera2 route's ceiling. It does *not* bypass the HAL: `ImageReader(RAW_SENSOR)`
  + `CONTROL_MODE=OFF` manual exposure/ISO/frame-duration + endless repeating request +
  RAM ring buffer + background-thread lossless compression into its `.mcraw` container,
  with per-frame CaptureResult metadata. "RAW video" is just the burst sustained — no
  MediaRecorder/MediaCodec involved in the master path. Latency floor = one HAL pipeline
  depth + binder. (Users got 120fps/8K24 RAW on a OnePlus 11 only via custom ROM — hidden
  sensor modes.)
- **unprocess** (`reilandeubank/unprocess`) — a near-stock Camera2Basic fork, stills-only,
  abandoned. Evaluated as a fork base and **rejected**: one-shot capture architecture
  fights a continuous engine; its JPEG path (temp DNG → `BitmapFactory.decodeFile`) is
  broken by design since BitmapFactory can't demosaic. Only patterns were vendored:
  coroutine camera-open/session helpers, image↔metadata timestamp pairing
  (`CombinedCaptureResult`), and the DngCreator still-grab idea.
- **MediaCodec for RAW**: Qualcomm Venus/iris encoders **cannot ingest RAW Bayer** —
  they take Surface input or NV12 byte buffers. Any RAW→video encode requires a
  per-frame conversion (GPU shader demosaic is the only realtime option). This drove the
  early decision that a lossless-RAW master container is the right product; encode-based
  approaches ("Direct Log clone") are a one-way door that bakes WB and loses RAW latitude.
  The user explicitly chose **Option B: true RAW workflow** over the MP4-direct product.

**Container standards landscape** (why RVSP is self-owned):

| Format | Status | Why not |
|---|---|---|
| CinemaDNG | Only true open RAW standard | A *file sequence* — unusable for direct-to-storage streaming capture; kept as the **editor-facing exchange target** |
| BRAW / ProRes RAW | Proprietary (+ RED patent cloud) | Out |
| MCRAW | Community de-facto, spec owned by one vendor | Dependency concern — user explicitly wanted independence |
| MKV + FFV1 (RFC 9043) | Fully open | FFV1's median-prediction + range coding can't sustain ~450 MB/s on a phone thermally |

Resolution: **own small open container spec** ("RVSP", Apache-2.0 to be published in
Phase 8) built from unencumbered primitives — zstd/LZ4 payloads, PCM audio, JSON header,
self-describing per-frame records. Editors reach it through the desktop CLI's CinemaDNG
export. (An early option to make the writer read-compatible with MCRAW was declined for
the same dependency reason.)

**RAM staging math** (user-suggested, adopted with honest numbers): RAM buys *seconds,
not minutes* — 12MP×30fps unpacked = 720 MB/s, so even 6 GB ≈ 8 s of buffer. Native-heap
direct allocations bypass the Java heap cap but risk LMK kills. Design conclusion: RAM is
a shock absorber for transient stalls (GC, thermal dips, flash wear), **never** a
replacement for compressor throughput ≥ capture rate. Watermark drop policy, never block
the camera thread.

---

## 2. The performance war (Phase 2) — how 5.5 fps became 96.5% retention

The most instructive debugging arc in the project. Timeline of the four benchmark rounds
(all at 4096×3072 RAW10, ~10 s takes, 30 fps target):

| Round | Change | Pack+Zstd drops | Lesson |
|---|---|---|---|
| Alpha | Single writer thread, Kotlin pack loop | ~71% (writer ~5.5 fps) | Per-sample Kotlin loop over 12.6 M samples + 25 MB/frame allocs dominate; single-thread zstd ~400–500 MB/s is not the bottleneck |
| R1–2 | Native JNI `packMipi10(Direct)` + worker pool; then **zero-copy Image ownership transfer** | → 20% → 21% | The 25 MB memcpy on the camera callback thread was throttling *capture itself* (HAL backpressure stalls the sensor when reader buffers aren't drained) |
| R3 | ZSTDMT inner threads, 4 outer × 4 inner, **per-frame `ZSTD_createCCtx`** | 38% (**regression**) | Two compounding sins: 16-way oversubscription thrash on 8 cores, and a new ZSTDMT context + thread pool created/destroyed *per frame* (~33 ms churn cycle). Also dragged the writer to ~265 MB/s via scheduler thrash |
| R4 | **Persistent per-worker CCtx** (`FrameCodec.openEncoder()`), 3 outer × 5 inner | **1.9% — 96.5% retention @ ~50% ratio** | Steady-state pools, no churn |

Final benchmark table (R4, POCO F6):

| Config | Captured | Written | Drops | MB/frame | Note |
|---|---|---|---|---|---|
| **Pack + Zstd** | 310 | 299 | 6 (1.9%) | 7.8 | The product config |
| Pack only | 310 | 309 | 0 | 15.7 | Zero drops, no compression |
| Both OFF | 320 | 302 | ~0 | 25.2 | **665 MB/s sustained — the real UFS ceiling** |
| Zstd only (expanded) | 330 | 189 | 42% | 8.3 | **Pointless config**: compressing expanded bytes costs 2.4× the CPU of compressing packed bytes. Auto-force Pack with Zstd (proposed, not yet wired) |

Key durable facts:

- **"Zstd on GPU?" — no.** No Adreno implementation exists; NVIDIA nvCOMP is CUDA-only;
  zstd's FSE/Huffman entropy coding is serial and branch-heavy — a poor GPU fit. The
  cheap equivalent was ZSTDMT (the MT sources were already compiled in via
  `ZSTD_MULTITHREAD`; only `ZSTD_c_nbWorkers` needed setting). Output format is
  byte-identical, so desktop decoders are unaffected.
- **Pack first, compress second** — MIPI RAW10 packing (4 px → 5 B) removes 37.5% of the
  bytes *before* entropy coding and makes the remaining bytes cheaper to compress.
- **Never re-add per-frame CCtx creation** (the AGENTS.md rule) — it was measured as a
  4× throughput regression in R3.
- **Camera thread discipline**: read `img.timestamp` BEFORE `close()` (buffer is invalid
  after); wrap `acquireLatestImage` for `IllegalStateException`; never block or allocate
  on the camera callback — HAL backpressure throttles the sensor, which masquerades as a
  "slow camera" (the 7 fps capture mystery of the alpha).
- **Teardown order is load-bearing**: wrong order = native SIGSEGV
  (`stopping=true → audio.stop() → extractor join → proxyEncoder.finish() → engine.stop()
  → pool shutdown → writer join`). The extractor must drain before the engine closes the
  gralloc images it still references.
- **Benchmarks need device-state context** (user-flagged): battery temp, thermal status
  now logged at start/stop via `deviceState()`; earlier rounds were likely throttled
  (round-3 anomalies included writer slowdowns that vanished after cooldown). For
  rigorous numbers: fixed scene, cooldown between configs, N repeats, median.
- **Stride bug found via the inspector**: early payloads included row-stride padding bytes,
  which looks exactly like sensor noise in previews. `RawSampleReader` now normalizes to
  contiguous layout before pack/write. The FrameInspector verdicts
  (`BLANK/CLIPPED` = dynamic range <2% / `NOISE-LIKE` = high variance + correlation <0.25 /
  `OK`) exist specifically to distinguish real content from stride/bit-depth corruption.

---

## 3. The MediaCodec proxy saga — five crash rounds, then a permanent ban

The proxy MP4 went through **five failed fix attempts** in the Antigravity session
before MediaCodec was abandoned. The full forensic sequence (this is the cautionary
tale behind the AGENTS.md "never reintroduce MediaCodec" rule):

| Round | Attempt | Crash signature | What it seemed to prove |
|---|---|---|---|
| 1 | HEVC ByteBuffer (`c2.qti.hevc.encoder`) | `SIGSEGV SEGV_ACCERR` in `MediaCodec_loop` at `0x72590f75e0` | Initial: `queueInputBuffer` passed `y.size` (786 KB) instead of the full YUV frame (1.18 MB) — encoder read past the buffer for chroma. Fixed the size. |
| 2 | NV12 semi-planar, correct sizes | Same fault, same address | Tried Google's APEX software encoder `c2.android.avc.encoder` + `COLOR_FormatYUV420Planar` instead of the Qualcomm one |
| 3 | Software encoder + I420 planar | **Same fault, same address again** | Android 15 CCodec was thought to reject the legacy color constant (19), leaving dimensions at 16×16; switched to `COLOR_FormatYUV420Flexible` + stride-aware `getInputImage` plane copies |
| 4 | YUV420Flexible + getInputImage | **Same fault, same address, 5th time** | The decisive observation: `0x72590f75e0` was **identical across all five crashes and all PIDs** — a fixed offset in a shared vendor library. ASLR is per-boot, so the address being constant *within* the boot means a mapped-but-wrong-permission page (SEGV_ACCERR, not unmapped) inside Qualcomm's C2 HAL. **Not fixable by color formats or codec choice.** |

**The conclusion (now the permanent rule)**: Qualcomm's CCodec on SM8635/Android 15
HyperOS SIGSEGVs whenever a MediaCodec video encoder is started **in the same process as
an active RAW_SENSOR camera session** — regardless of hardware/software codec, color
format, or input mode. Surface-input mode was never reached in testing because the
ByteBuffer path was tried first; the architectural verdict covers MediaCodec entirely.

**Decision exchange** (user asked "replace MediaCodec with pure software MJPEG — is there
another way?"): three options were tabled — (1) Surface-input hardware MediaCodec,
(2) software MJPEG via `YuvImage.compressToJpeg` in a hand-rolled container,
(3) embedded Cisco OpenH264 in `librawrec.so`. The user asked about the cons of each for
a RAW-focused recorder and picked **option 2** (fastest to execute, 100% HAL-free,
playable everywhere). That choice also eliminated a whole class of vendor-encoder bugs
for the project's lifetime.

The MJPEG implementation then had its own bug chain (each verified against real files):

1. **`.avi` container played black in modern players** — the JPEG frames were perfect
   (pixel stats confirmed a sharp monitor capture), but Windows/Android players dropped
   legacy DirectShow AVI MJPG splitters. Fix: proper ISO MP4 (`ftyp`/`mdat`/`moov`).
2. **A file with intact frames but no `moov`** was unplayable — the writer's finalization
   (patch `mdat` size + append `moov`) must complete on Stop; `ProxyEncoder.finish()` +
   explicit "proxy mp4 finalized" logging were added.
3. **`BufferOverflowException` during finalization** — `hdlr` name buffer allocated 37
   bytes but `VideoHandler\0` payload needed 41. The exception aborted finalization,
   leaving the mp4 unindexed. Fixed with exact-size allocation; covered by
   `Mp4ContainerWriterTest`.
4. **VLC on Android still refused the file** — the `hdlr` box's name field handling;
   fixed (kept in the muxer tests as a regression guard).

Also from this saga: the proxy originally allocated **35.4 MB/s of GC churn** on the
extractor thread (`toBytes()` of the 25 MB image + per-frame proxy arrays). The fix was
the `POOL_CAPACITY=4` pre-allocated `ProxyFrame` pool + the one-pass
`packMipi10ProxyDirect` JNI (pack + downscale in one loop, zero heap allocations).

With **all toggles on** (Mic + Proxy + Zstd + Pack) drops rose to ~50% — encoder + queue
copies contend with zstd workers for the die. Proxy OFF restores clean numbers; this is
the benchmark caveat noted in AGENTS.md.

---

## 4. CinemaDNG / OCTOPUS RAW Player compatibility (Phase 3)

The first DNG writer used a two-IFD layout (IFD0 + SubIFD chain). **OCTOPUS RAW Player
froze/ANR'd** on those files. Root cause (found in the parallel Antigravity session):

1. The SubIFD pointer was written into **tag 273 (StripOffsets)** instead of **tag 330
   (SubIFDs)**, and IFD0 carried thumbnail flags (`NewSubFileType = 1`) — OCTOPUS read
   IFD0 as image data through corrupt offsets and hung. (Traced through the player's
   actual source: `Core/Clip/ClipCinemaDNG.cs`, `Core/IO/DNG/Reader.cs`.)
2. **Missing TIFF tag 51044 (FrameRate)** — OCTOPUS pops a blocking "Missing framerate
   information" modal and defaults to 23.976 fps without it.
3. **Sequence gaps froze playback**: OCTOPUS computes duration as
   `lastFrame − firstFrame + 1` and *loops indefinitely waiting for missing frames* if a
   folder mixes frames from different extractions (e.g. old `frame_000003.dng` next to a
   new `frame_000004.dng` starting at 0). Hence `extract` deletes stale `.dng`/`audio*`
   files in the output dir first — sequences always start cleanly at `frame_000000.dng`.
4. **WPF MediaPlayer audio crash**: OCTOPUS auto-loads any top-level `*.wav` and touches
   `TrackWAV.Duration` on the first video frame before WPF MediaPlayer finishes
   initializing — `Unable to return a TimeSpan property value for a Duration value of
   'Automatic'` unhandled exception. Audio must therefore export to
   `<outDir>\audio\audio.wav`. (Found by hooking the player's own log:
   `%LOCALAPPDATA%\OCTOPUS RAW Player.log` — a useful trick for third-party apps with
   local logs.)

The working format (now the rule, validated in both OCTOPUS and Resolve):

- **Single-IFD0 layout**, `NewSubFileType=0`, `nextIfdOffset=0`, no SubIFD chain.
- **TIFF tag 51044 (FrameRate) is mandatory** (type 10 SRATIONAL, `fpsMilli/1000`).
- Audio exports to `audio\audio.wav`; NLEs (Resolve/Premiere/FCP/VLC) ingest it from
  there as a synced stereo track.
- Verified against real footage with `rvtool dngcheck`: 33-entry IFD0, Photometric=32803
  (CFA), CFAPattern=65794 (BGGR), WhiteLevel=1023, sensible first-pixel values — plus
  60-frame sequences + synced stereo audio playing via the player's OpenCL debayer.

Desktop tooling notes:

- `Rvtool.kt` lives in the **app module** so Gradle compiles it (Kotlin, no kotlinc on
  the machine); it runs on desktop via `tools/rvtool.ps1` against
  `app/build/tmp/kotlin-classes/debug` + kotlin-stdlib from the gradle cache
  (`modules-2/files-2.1` — **not** `files-1`) + `zstd-jni` from `tools\libs`.
- Desktop zstd decode uses a **reflection bridge** (`ZstdDesktop` → `Class.forName`)
  so the zstd-jni jar never enters the APK or the compile classpath.
- The `gen` command writes a synthetic gradient RVSP so the whole desktop path
  (gen → info → stats → bmp → dngcheck) is testable without a device.
- `rvtool.ps1` must pass `-p $root` to gradlew — **gradlew inherits the caller's CWD**
  and fails with "Directory does not contain a Gradle build" otherwise (bit us once).
- The script skips rebuild when classes exist — delete `app/build/tmp/kotlin-classes`
  to force a pick-up after editing Rvtool.

---

## 5. Device facts & environment quirks (POCO F6)

- **Identity correction**: the device was initially misidentified from the codename
  "peridot" as a POCO X6 Pro / **Dimensity 8300** — wrong. adb ground truth:
  `ro.board.platform=pineapple`, `ro.soc.model=SM8635`, QTI = **POCO F6, Snapdragon
  8s Gen 3**. (This also kept the Qualcomm CamX override plan alive.) Always confirm
  SoC via `getprop`, never via codename lookup. Full identity: model `24069PC21G`,
  **sensor is a Sony IMX882 / LYT-600 50 MP, binned to 12.5 MP for our RAW10 mode** —
  Phase 6's "unbinned 50 MP" work targets this same sensor at 8192×6144.
- Main RAW mode: 4096×3072 (12 MP binned), **BGGR (cfa=3)**, whiteLevel 1023 (10-bit),
  **rowStride 8192 == contiguous** (4096×2), pixelStride 2. Sensor orientation 90;
  preview buffer 960×720 since 2026-08-31 (was 1920×1440 — representational-viewfinder
  change, §6a).
- **No Python/.venv anywhere in the project** — an explicit early user question. Pure
  Kotlin/C++/Java/Gradle stack; keep it that way (the Phase 8 Python *decoder* is a
  deliverable for others, not part of the build).
- **logcat is useless during camera work** — the camera HAL floods the main buffer and
  evicts app lines within seconds (a whole recording session's logs vanished this way;
  bumping `logcat -G 4M` wasn't enough). Hence the **AppLog file logger** (5 MB rotation)
  at `/sdcard/Android/data/dev.rawrec.app/files/logs/rawrec.log` — always pull that, per
  the AGENTS.md rule.
- **CrashHandler & in-app crash logs (2026-09-06)** — unhandled Kotlin/Java exceptions are
  intercepted by `CrashHandler` (`Thread.setDefaultUncaughtExceptionHandler`), formatted with
  full stack traces and hardware diagnostics, logged to `rawrec.log`, and appended to
  `/sdcard/Android/data/dev.rawrec.app/files/logs/crash.log` with an explicit hardware
  `fd.sync()` before process termination. The Settings screen features an in-app
  "Diagnostics & Crash logs" viewer with one-tap stack trace inspection and clearing. Pull via:
  `adb pull /sdcard/Android/data/dev.rawrec.app/files/logs/crash.log`.
- **Google Assistant steals the camera** (error 3, ERROR_CAMERA_IN_USE) roughly every
  second on this device when its face-unlock/ambient process runs. Countermeasure:
  `PreviewEngine.restart()` — single-flight guard (`restarting` flag prevents
  thundering-herd recovery storms when onError+onDisconnected both fire), linear backoff
  1s/2s/3s… up to 8 attempts, observed ~150 ms recovery. Without the single-flight guard
  we saw three concurrent recovery threads.
- **adb/rotation testing hazards** (cost hours): `wm user-rotation lock` doesn't always
  persist; screenshots flip between 1220×2712 and 2712×1220 spontaneously, invalidating
  hardcoded tap coordinates; screenshots taken during rotation transitions can catch a
  blank compositor frame (all-black, looks like a rendering bug but isn't); uiautomator
  dumps were unreliable over this adb. Reliable verification recipe: file logger for
  state, screenshots for pixels, re-derive tap coordinates from a fresh screenshot every
  time, and prefer deterministic hooks (debug override chips) over blind tapping.
- A `PreviewEngine.updateControls` NPE crash existed when rotating the Settings screen
  with preview active (session torn down mid-update) — guard checks were added.

---

## 6. The viewfinder orientation saga (Phase 5) — the biggest debugging story

Weeks of "circling" distilled into the current architecture. The full path:

1. **The matrix sign trap.** The standard formula
   `r = (sensorOrientation − displayRotation) % 360` assumes a specific rotation
   convention. Our hand-rolled `Matrix.setRectToRect` composition rotated the *opposite*
   way, putting every orientation off by 180°: portrait sideways, landscape-left
   upside-down, landscape-right "correct" by accident. Derived empirically from the
   user's three observations, the correct angle in that convention was
   `(sensorOrientation − displayRotation − 180 + 720) % 360`. Open Camera's actual
   transform (`90 * (rotation - 2)`, only in the 90/270 branches, with a pre-letterboxed
   view) did **not** transfer — their geometry differs because their view is aspect-swapped
   before the transform.
2. **Stop guessing — instrument.** The user, frustrated, asked for debug controls. The
   right move: a **rotation override** (Auto/0°/90°/180°/270° chips) that directly pins the
   matrix, plus live telemetry (`sensor° / disp / applied° / buffer size`), plus a
   `DeviceOrientationTracker` (accelerometer, physical-edge classification). Make the
   unknown a tunable and read the truth off the screen.
3. **The user's breakthrough insight**: with the override pinned at 0°, the viewfinder
   was correct — and it broke only because Auto mode *changed* the transform on rotation.
   "Lock the viewfinder at 0° — only the UI elements move." Physically: the sensor is
   fixed to the chassis; rotating the phone changes what the sensor sees, not the
   transform. `ViewfinderMath.autoRotation() = 0` — the viewfinder is a
   **chassis-locked separate entity** (like a dedicated camera's sensor monitor).
4. **Window rotation still rotated the viewfinder** until the activity itself was locked:
   `android:screenOrientation="portrait"` in the manifest disengages Android's rotation
   engine entirely (verified: forcing system rotation to 90 does nothing — window stays
   `ROTATION_0` at 1220×2712). **This is why aspect ratio can never be destroyed by the
   system** — Android's rotation swaps window dimensions and wrecks a 4:3 preview; our
   own engine doesn't.
5. **The chrome then needed its own rotation engine** (since the window never rotates):
   accelerometer-driven `DeviceOrientationTracker` → `uiRotation` (0/90/180/270) →
   `RotatedOverlay` applies spring-animated `graphicsLayer.rotationZ` to the chrome only.
   Tracker details: dominant-axis classification with **45° hysteresis + 150 ms
   stability window** (no flip-flop), flat-device holds last orientation, `SENSOR_DELAY_GAME`.
6. **The chrome "flew off screen" bug**: the rotating box was placed at TopStart of the
   portrait window, so the 90° pivot happened around an off-screen point
   (x≈1356 in a 1220 px window). Fix: `RotatedOverlay` centers a **dimension-swapped box**
   (`size(width = maxHeight, height = maxWidth)` when swapped) with
   `contentAlignment = Alignment.Center` — pivot = screen center, swapped box exactly
   covers the screen at 90/270. Compose hit-testing respects graphicsLayer, so controls
   remain tappable after rotation.
7. **System bars**: hidden via `WindowInsetsControllerCompat.hide(systemBars)` +
   `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (Open Camera's approach). Note
   `enableEdgeToEdge()` failed to resolve against the pinned activity-compose version —
   `WindowCompat.setDecorFitsSystemWindows(window, false)` is what actually ships.
8. **Aspect presentation** (user-requested): the scope-strip aspect button cycles real
   presentation modes, not just overlay masks — `ScaleMode.FILL` (cover-crop full-bleed),
   `FIT` + `aspectLimit` (true letterbox into 2.39:1 / 16:9 / 4:3 with black bars and a
   boundary line; the 4:3 buffer is center-cropped into the target ratio first), and
   Grid (full-bleed + rule-of-thirds).

**Open Camera research summary** (for contrast — we deliberately diverged):
no manifest orientation lock (handles rotation via `configChanges` + `configureTransform`),
immersive via WindowInsetsControllerCompat, `OrientationEventListener` used only for
capture/EXIF metadata — not the viewfinder. Their approach works but the
locked-activity + own-rotation-engine architecture is cleaner for a cinema tool: zero
session rebuilds across rotation (verified: exactly 1 open + 1 configure through a full
rotation cycle), no configChanges edge cases, no aspect destruction.

### 6a. The stock-camera decompilation & the representational viewfinder (2026-08-31)

The user decompiled the stock HyperOS camera (sources at
`tools\apk_out\Camera\sources\`) and asked: what if the viewfinder just *represents*
the scene, at reduced resolution? The findings reshaped two decisions:

**Xiaomi's architecture** (from the decompilation):

1. **One preview buffer, always.** They never switch the preview SurfaceTexture size
   per aspect mode — one screen-shaped ~2.6MP buffer serves every mode. Aspect is a
   presentation decision, not a capture-configuration decision.
2. **GL letterboxing for aspect.** The display rect letterboxes the buffer per aspect
   mode; nothing is ever cover-cropped into the screen's own aspect ratio.
3. **Capture-time crop via size selection / vendor tags** (`com.xiaomi.params.captureRatio`),
   not via switching the streaming buffer. WYSIWYG is enforced at *capture*, not by
   making the preview match the recording geometry pixel-for-pixel.
4. **|ratio−target| ≤ 0.02** epsilon for aspect matching — the same tolerance RawRec
   independently arrived at for its 4:3-first buffer filter.

**What RawRec adopted** (the approved plan, same day):

1. **All aspect modes became `ScaleMode.FIT`** (`CinemaViewfinderScreen` passes FIT
   unconditionally). This killed the "Full mode is zoomed ~3×" bug: Full/Grid had
   been FILL — cover-cropping the sensor frame into the ~9:20 window sliced its long
   axis to 33.7% width. FIT letterboxes the whole sensor frame, so every mode now
   shows the scene exactly as the take stores it. Grid's rule-of-thirds lines span
   the full FIT frame. The `FILL` enum survives in `ViewfinderMath` (unit-tested,
   potential future "immersive" mode) but no production path selects it.
2. **Preview buffer target cut 1920×1440 → 960×720** (~2.76MP → 0.69MP, same 4:3
   aspect). Preview runs always (idle + during RAW takes), so this is a standing
   ~4× bandwidth/power cut with no visible loss on a 6.7″ representational
   viewfinder. The 4:3-first filter and landscape fallback are unchanged;
   `BufferSelectionTest` re-based (exact-match, F6-like list, fallback, all-portrait
   cases). Stock runs ~2.6MP; we deliberately run lower because our viewfinder is a
   framing guide, not the deliverable — the deliverable is the 4096×3072 RAW stream.

**The one deliberate divergence**: Xiaomi's 2.39/cine band is 16:9-relative (a crop of
an already-16:9 preview), while RawRec's recorded band is WYSIWYG-exact in full-sensor
coordinates (full-width 4096×1712 / 4096×2304 bands via the packer-level crop). Their
preview is a marketing-friendly approximation; ours matches the stored frame
pixel-for-pixel. Keeping the divergence costs nothing at 0.69MP and is truer to the
cinema-tool goal.

Rejected: session-rebuild-per-aspect (stock doesn't do it; rebuild blips + re-latch
bug risk); rotating the image presentation (chassis-lock stands); any container/crop
change (recording paths untouched).

**Follow-up (2026-09-02, built on the user's "why is it square" + "no resolution
option" reports): presentation option + resolution selector.**

1. *Immersive presentation opt-in.* The representational 4:3 block on a 9:19.5 screen
   is honest but reads as odd; the user wanted the old edge-to-edge look back as a
   choice. `ViewfinderMath.presentationFor(immersive, aspectIndex)` = FILL only for
   the full-sensor modes (Full, Grid) when the Settings "Fill screen" chip is on;
   the WYSIWYG band modes (2.39/16:9/4:3) are never cover-cropped — their outline
   must sit on the recorded band, which FIT guarantees. Presentation is hoisted app
   state (`vfImmersive`, like `vfRotationOverride`), not a control state.
2. *Overlay derived from the transform's own math.* The framing outline previously
   came from window-space `CinemaScopes.calculateAspectFraming` — which only
   coincides with the real image band in portrait FIT. New `contentRect` (unit-tested
   to match `contentTransform`'s rendered extent) gives the overlay the exact
   on-screen image rect, so the outline sits on the image edge in both presentations
   and Grid thirds span the image, not the letterbox bars.
3. *Resolution/camera selector unified.* The Settings size dropdown existed but was
   composable-local — the cinema record button re-probed and always recorded
   `rawSizes.firstOrNull()` (the largest), a second split-brain alongside the
   ScaleHolder one. Now `recCamSel`/`recSizeSel` live in `RawRecApp` (the
   zstd/pack/mic/proxy pattern), the preview re-keys on the selected camera, and
   both start paths record the selection. `selectBufferSize` generalized to take
   the RAW mode's aspect (default 4:3; a future 16:9 crop mode gets a 16:9 preview
   buffer automatically).
4. User decisions at design time: Immersive fills Full+Grid only (not 4:3); the
   selector lives in Settings with shared state (no cinema-screen chips).

---

## 7. Material 3 Expressive migration — toolchain archaeology

The M3E version pin in AGENTS.md (`1.5.0-alpha16`) was established by bisecting the
actual artifact metadata against the installed toolchain:

- **material3 1.4.0 stable** has `MaterialExpressiveTheme`/`MotionScheme` **internal** —
  unusable from app code.
- **1.5.0-alpha27+** requires **compileSdk 37 + AGP 9.1** (not installed, too bleeding-edge).
- **1.5.0-alpha16** is the sweet spot: minCompileSdk 35 (we run 36), everything
  public-behind-`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- This forced the upgrade chain AGP 8.5.2 → 8.7.3, compileSdk 34 → 36 (Gradle 8.9
  already satisfied AGP 8.7's minimum). `FlexibleBottomSheet`/flexible app bars do not
  exist even in alpha16 (tokens only) — use `ModalBottomSheet` + custom pills.
- `MotionScheme.expressive()` is a **function call** in Kotlin (the mangled bytecode name
  `expressive$material3()` misleads). Correct theme:
  `MaterialExpressiveTheme + MotionScheme.expressive()` in `ui/theme/RawRecTheme.kt`.
- dl.google.com serves a 4.7 KB stub AAR to curl-less requests — inspect artifacts from
  the Gradle cache after letting Gradle resolve them, not by direct download.

**UI-makeover lessons** (user called the first M3E pass "trash, inconsistent"):
- The #1 M3 violation was **hardcoded `Color(0x...)` everywhere** instead of theme roles.
  Fix: the shared component library (`ui/components/`: SectionCard, SettingRow, StatChip,
  IconToggle, ChoiceChipRow, ControlPill, Hudpill, StaggeredAppear) + `RawRecIcons`
  (hand-drawn stroke vectors). One source of truth per repeated pattern; screens build
  on components, never per-screen styling. (Exception: Canvas overlays — framing masks,
  histogram — intentionally use literal black/white.)
- Motion is what makes M3E *feel* right: tab switches slide+fade directionally, scope
  toggles pop-bounce, control pills squish on press and shape-morph corners when active,
  the record button morphs circle→squircle with pulse, settings cards stagger in.
  Spring specs `Bouncy`/`Soft` at the top of `RawRecComponents.kt` tune everything globally.
- Gotchas hit along the way: the vector DSL uses **`curveTo`** not `cubicTo`;
  `pathFillType` not `fillType`; trailing-lambda parameters must be last (`onClick`
  after `modifier` silently binds the lambda to the wrong param); `animateContentSize`
  needs an untyped `spring()` (a `SpringSpec<Float>` doesn't satisfy `IntSize`).

---

## 8. The Antigravity session — what it did, and parallel-session lessons

A **second agent session ran in Antigravity IDE (Gemini)** on the same `D:\RawRec` tree
(Aug 27–28, 1,196 transcript steps), itself continuing the original archived planning
conversation (`bypassing-qualcomm-isp-for-raw-sensor-image.json` at repo root — the
project's lineage is original-plan → Antigravity → opencode). Its contributions that
shaped the current codebase:

1. **CinemaDNG single-IFD fix + `rvtool wav`** (the OCTOPUS compatibility work, §4).
2. **The entire proxy saga** (§3): five crash forensics rounds, the MediaCodec ban
   decision exchange, the MJPEG rewrite and MP4 muxer bug chain, the ProxyFrame pool
   (−35.4 MB/s GC churn), and `packMipi10ProxyDirect`.
3. **Phase 5's first pass**: camera control suite + viewfinder. Notably the viewfinder
   *didn't work twice* — first cut used a `SurfaceView` wired only through the recording
   path (camera never opened until "Record" was tapped); the fix was the standalone
   **`PreviewEngine`** (dedicated `TEMPLATE_PREVIEW` session, TextureView, auto-start on
   launch, resume after `stop()`). The opencode session then inherited this and layered
   the orientation/rotation architecture of §6 on top.
4. **Test suite growth 26 → 47 → 58 tests** (user explicitly requested unit tests
   before Phase 5 started — "a unit test will be great for each function").
5. **The current roadmap shape** (user-authored requests, refined into PLAN.md):
   - Phase 5 camera controls + M3E viewfinder + cinema overlays (done across sessions)
   - Phase 6 **multi-SoC optimization** — originally Qualcomm-only, the user extended
     it to MTK Dimensity / Samsung Exynos / Google Tensor / UNISOC with a per-vendor
     scope table: thread affinity to big cores (`sched_setaffinity`, X4/A720 on
     Qualcomm), `SCHED_FIFO`, per-vendor row-stride quirks (MTK 128/256-byte, Exynos
     64-byte), vendor tag namespaces (`com.qualcomm.qti.*`, `com.mediatek.camera.*`,
     `com.samsung.camera.*`, `com.google.camera.*`, `com.sprd.camera.*`), and
     unbinned 50 MP full-sensor readout on the IMX882.
   - Phase 7 rvtool **desktop GUI** with per-frame ISO/exposure HUD and the same cinema
     overlays (peaking/false color/zebras/histogram/framing guides) — the user's exact
     spec, now reflected in PLAN.md.
   - Phase 8 published spec + C++17 single-header (`rvsp.h`) + Python reference decoder;
     Phase 9 root mode-forcing + soak testing.
   (Note: the phase numbering shifted during planning — Antigravity's "Phase 5" was
   briefly "RVSP spec formalization" before the user's control-suite request redefined
   it; PLAN.md's current numbering is authoritative.)
- **A `walkthrough.md` was maintained** by that session as a living state doc; it no
   longer exists in the repo — PLAN.md/AGENTS.md are the surviving state records.

**Parallel-session lessons** (cost real debug time):

- After any external/parallel session, **verify codebase assumptions against disk**
  before trusting session memory — file existence, class locations, and architecture
  may have moved. (`AGENTS.md` flags `docs/history/` as archived context for this.)
- The opencode session spent effort re-deriving facts (e.g. the DNG SubIFD bug it had
  itself introduced earlier) because the fix had landed from the other session. When
  resuming, read `git`-less state via PLAN.md + the file logger + test names first.
- Antigravity transcripts live in
  `C:\Users\Perch\.gemini\antigravity\brain\<id>\.system_generated\logs\transcript_full.jsonl`
  (JSONL, one record per step; `type` = USER_INPUT / PLANNER_RESPONSE / GENERIC /
  CHECKPOINT). **CHECKPOINT records are dense compaction summaries** — when mining an
  old session, read those first; they compress entire bug hunts into a few paragraphs.

Kotlin pitfalls that cost compile cycles during the CLI build: primary-constructor
params are visible in property *initializers* but **not** in `get()` accessors (make the
param `val`); integer division infers `Long` and silently breaks `Double` call sites
(`Math.pow(norm, …)` with a `Long norm`); `List<Int>.min()`/`average()` had overload
ambiguities resolved via `minOrNull()`/manual sums; a `.java` file was once written with
Kotlin syntax throughout.

---

## 9. Current state & known gaps

**Done**: Phases 0–5 complete; Phase 6 complete (Multi-SoC & Sensor Opcode Optimization:
SocFamily and SocOptimizer hardware detection/scheduling across Snapdragon, Dimensity,
Exynos, Tensor, and Unisoc; 50MP unbinned dynamic queue sizing and memory bounding;
VendorTags reflection engine for Qualcomm & Xiaomi HyperOS; sensor opcode extraction for
lens distortion, calibration, optical black, and shading maps into RVSP metaJson, plus
standard DNG OpcodeList1/3 encoding in rvtool for native lens correction in DaVinci Resolve).
179 tests / 24 classes green. Pipeline: ~29fps sustained 12 MP RAW10 with pack+Zstd,
stereo audio, MJPEG proxy, sensor-accurate CinemaDNG export with DNG opcodes, chassis-locked
representational viewfinder (letterboxed, whole frame always visible) with rotating
chrome and real-time live scopes (histogram, peaking, false color, zebras).

**Capture-quality pass (2026-08-29, part of Phase 5)** — resolved gaps:

1. ✅ **Sensor colorimetry, end to end**: `SENSOR_BLACK_LEVEL_PATTERN` (real value
   **[64,64,64,64] on the F6's IMX882**, remapped to the header's R,Gr,Gb,B order),
   `SENSOR_COLOR_TRANSFORM1` → header `colorMatrix` (AOSP DngCreator **direct-copy**
   semantics — the initially planned "invert + apply calibration" math would have been
   WRONG; verified against the actual AOSP JNI source), reference illuminant →
   metaJson `calibIlluminant1` (F6: 21/D65). AsShotNeutral from the first result's
   `SENSOR_NEUTRAL_COLOR_POINT`/AWB gains (manual-WB fallback via inverted app gains).
   CLI `Header` parser now reads the color floats; `writeDng` consumes the header
   instead of hardcoded identity; `gen` emits non-default values so the path is
   device-free testable. Verified on real footage: DNG BlackLevel=[64×4], a plausible
   XYZ matrix, FrameRate 30, per-frame ExposureTime.
2. ✅ **Result pairing**: timestamp-latched validator (`ResultPairing.decide`, unit
   tested for accept/stale/ahead/unknown) replaced unvalidated latest-wins; engine
   tracks `resultPaired`/`resultUnpaired` stats.
3. ✅ **Controls honored from frame 0**: `RecordingController.start()` takes the full
   `CameraControlState`; `RawCaptureEngine.start(controls)` shares one request builder
   with `updateControls()`. Found + fixed a **new HAL quirk during verification**:
   forcing `SENSOR_FRAME_DURATION` in AE mode throttled RAW to ~14fps; auto mode now
   sets only `AE_TARGET_FPS_RANGE(30,30)`, manual mode pins both. Rule recorded in
   AGENTS.md.
4. ✅ **One honest A/V clock**: `SENSOR_INFO_TIMESTAMP_SOURCE` read at start (F6 = 1,
   REALTIME); audio chunks then stamp with `elapsedRealtimeNanos()` (constructor-
   injected clock); `timestampSource` in metaJson; `rvtool wav` reports the alignment.
   firstFrameTs/firstAudioTs logged per take (~same base, sane offsets).
5. ✅ **Hardcoded record config killed**: toggles hoisted to `RawRecApp`; cinema record
   button reads them (defaults: zstd+pack on, mic/proxy off). Measured effect on the
   ~50%-drop surprise config: all-on retention improved to **78% at ~29fps** (fps
   fix contributed); proxy-off/mic-off restores benchmark-grade numbers.

**Remaining gaps (Stability & Quality Audit, 2026-09-03)**:

**Cinema studio deck pass (2026-09-07)** — color science, adaptive UI, scopes
orientation fix, studio telemetry:

1. ✅ **Clean-room cinema look suite (`ColorScience.kt`)**: 16 analytical
   `ToneProfile`s (Filmic/OOTF/Warm/Cool/Vintage/Bright/SoftMono/Mono filmic
   sigmoid curves, HLG Rec.2100 OETF, Leica-inspired Authentic/Vibrant/Sepia/
   NordicBlue) — all math-modeled from published transfer-function formulas, no
   proprietary tables or firmware dumps. `CubeLut` parses standard Adobe/Resolve
   `.cube` 3D/1D LUTs with CPU trilinear interpolation. Look selection flows
   through `ToneProfile.fromId()` in CLI (`--tone=`), GUI dropdown, and GPU
   kernels (`evaluateToneCurve` → 33-point curve baked into the OpenCL/Vulkan/CPU
   shaders).
2. ✅ **CinemaDNG tone embedding**: `rvtool extract` writes DNG tag 50936
   (`ProfileName`) + tag 50981 (`ProfileToneCurve`) non-destructively (editors
   apply the look on import), or `--bake-tone` grades the 10-bit raw samples
   destructively.
3. ✅ **HLG MP4 export (`HlgMp4Exporter`)**: graded frames + 16-bit 48kHz PCM
   muxed via `Mp4ContainerWriter` with `colr.nclx` (BT.2100 HLG signaling);
   pure-Kotlin `JpegEncoder` (ISO 10918-1 baseline DCT) avoids android.jar's
   absent AWT/ImageIO. Two muxer bugs fixed on the way: hardcoded `mdat` offset
   (patched `ftyp` tail → unplayable files; now computed from the actual box
   size) and out-of-order frame timestamps from parallel zstd decode (now
   monotonically sorted in `scanRecords`).
4. ✅ **GPU compute backends (`tool/gpu/`)**: `GpuBackend` interface with OpenCL
   (JOCL, default), Vulkan compute (LWJGL), and multithreaded CPU fallback —
   one fused kernel chain (MIPI unpack → demosaic → WB → tone curve → vignette
   → LUT) selected via `--gpu=` / GUI Engine dropdown.
5. ✅ **Cinema studio rig UI (app)**: full widescreen cinema redesign —
   top studio header (SMPTE `HH:MM:SS:FF` timecode from `targetFps`, REC/STBY
   tally, live written-size + dropped-frames badges, scopes strip, mini
   histogram), right `CinemaParameterDock` (FPS/SHUTTER/ISO/WB/LOOK/RES tiles +
   sliding dial panels), bottom `CinemaFocusRail` (AF/MF, A/B rack-focus pulls),
   Electric Cyan `#00E5FF` / Titanium Slate `#0E1118` / Tally Crimson `#FF2D55`
   palette with `MotionScheme.expressive()`.
6. ✅ **Adaptive portrait/chassis-locked layout**: `CinemaViewfinderScreen`
   branches on `Configuration.orientation` — portrait stacks a 2-row header,
   full-width focus rail, 2×3 `CinemaParameterGrid`, centered record button,
   and slide-up dial drawer in the letterbox space below the 4:3 feed;
   landscape keeps the right-dock rig. Pure chrome adaptation —
   `ViewfinderMath`/`FixedViewfinder` untouched.
7. ✅ **Scope overlay orientation fix**: `TextureView.getBitmap()` reads the
   raw un-rotated sensor buffer (AOSP readback passes `useLayerTransform=false`,
   skipping `setTransform`), so Peaking/False Color/Zebras rendered 90°/270°
   off the video. Fixed in the overlay Canvas only: `clipRect(activeRect)` +
   `rotate(vfRotation, pivot=center)` around the unrotated `k·bufW × k·bufH`
   rect — pixel-aligned with the feed, zero viewfinder-logic changes.
8. ✅ **rvtool desktop studio deck**: Swing GUI restyled to the same cinema studio
   palette (`DeckButton`/`DeckToggleButton`/`DeckSliderUI`/
   `DeckComboBox` custom controls; `RvtoolGui` lives in `tools/gui/` +
   `rvtool/gui/` in exact parity, compiled on demand by `rvtool.ps1`).
   Structured take-inspector cards, RGB+luma histogram with IRE grid, frame
   stepping (`|◀`/`▶|`), `LOOP` repeat toggle (wraps end-of-take; auto-rewinds
   when PLAY pressed at the last frame), large SMPTE timecode + PLAY/STBY
   badge in the transport deck.

**SoC & Sensor-Specific Optimization Pass (2026-09-07)** — Tier 1–6 performance suite:

1. ✅ **Tier 1 — Lossless Frame Delivery & Priority**: Switched `RawCaptureEngine` from
   `acquireLatestImage()` to `acquireNextImage()` (FIFO). Latest-wins is documented to
   silently discard frames whenever the consumer thread lags; next-wins guarantees lossless
   frame sequence into `inQ`. `rawrec-capture` HandlerThread elevated to `THREAD_PRIORITY_URGENT_DISPLAY`.
2. ✅ **Tier 2 — Thermal Governor (`ThermalGovernor.kt`, `SocOptimizer.tuningFor`)**:
   Monitors Android `OnThermalStatusChangedListener` (API 29+), polls 10s predictive headroom
   forecast (`getThermalHeadroom`), and listens to Xiaomi HyperOS `action_temp_state_change`
   broadcasts (`temp_state % 10` staging matching stock `ThermalDetector`). On MODERATE/SEVERE,
   rescales ONLY the zstd worker pool (sheds workers, tightens inQ) without session tear-down;
   CRITICAL surfaces via `RecStats.error` and studio header badge.
3. ✅ **Tier 3 — ADPF & HyperOS Scheduling (`PerfSession.kt`)**: Registers pipeline thread TIDs
   in an ADPF `PerformanceHintManager` hint session (target = frame period in ns) and reports
   per-frame work duration (`reportActualWorkDuration`) to guide Linux EAS/DVFS governors.
   Broadcasts `com.miui.powerkeeper.record_start/end` with quality and fps extras to lift
   vendor power throttling during active takes.
4. ✅ **Tier 4 — Native NEON Bit-Packing & IO Cadence (`rawrec_jni.cpp`)**:
   `pack_row` vectors 16 u16 samples per iteration into packed MIPI RAW10 using ARM64 NEON
   `vld1q_u16` + `vshrn_n_u16` (`pack16_neon`), with scalar tail. `expandCopyDirect` accelerated
   via row-wise `memcpy`. Fixed JNI null checks on direct buffers and added crop bounds checks.
   Plumbed `ZstdNative.createCCtxEx` with `windowLog` configuration. Writer loop uses periodic
   ~5s `fdatasync` to eliminate per-frame fsync stalls on UFS 4.0 / F2FS.
5. ✅ **Tier 5 — Sensor-Specific Capability & Colorimetry**: `CameraCatalog` probes
   `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` to filter FPS dial options, and probes
   `SENSOR_MAX_ANALOG_SENSITIVITY` to show the analog ISO boundary in the dial. Extracted
   dual-illuminant colorimetry (`colorMatrix2`, `calibIlluminant2`, `forwardMatrix1/2`) and
   `SENSOR_NOISE_PROFILE` into RVSP `metaJson`. `Rvtool.writeDng` writes DNG tags 50722,
   50779, 50969, 50970, and 50974.
6. ✅ **Tier 6 — Background Capture Survival (`CaptureForegroundService.kt`)**: Declared
   `foregroundServiceType="camera"` with `FOREGROUND_SERVICE_CAMERA` and `WAKE_LOCK` permissions.
   Ongoing low-priority notification and partial wake lock prevent Android 14/15 and HyperOS
   Linux cgroups from freezing camera capture when backgrounded or during display timeout.

**Remaining gaps (Stability & Quality Audit, 2026-09-03)**:

1. ✅ **JNI Safety & Bounds Validation (`rawrec_jni.cpp`)**: Resolved in Tier 4 pass. Null checks
   and crop boundary validation (`cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0`)
   added across all native pack/expand functions.
2. **Proxy MP4 64-Bit Chunk Offsets (`Mp4ContainerWriter.kt`)**:
   - 32-bit `stco` box truncates 64-bit offsets via `o.toInt()`, corrupting indices when MP4 proxy files exceed 2 GB (~10+ min takes). Fix: support ISO `co64`.
3. **Capture Teardown Cleanup & Buffer Leak (`RecordingController.kt`)**:
   - `extractorThread.join()` runs before `engine.stop()`. Late frames arriving in `inQ` remain unclosed when `inbound = null`, leaking hardware graphic buffers.
   - Thread joins are unwrapped; an interruption skips `engine.stop()` and `workerPool.shutdown()`.
4. **Scopes wired (Phase 5 complete, 2026-09-04)**:
   - `ScopeAnalyzer` taps downscaled 240x180 preview frames directly from `TextureView` at 16 FPS on a background thread (`Dispatchers.Default`).
   - Powers real-time `HistogramScopeView` in top HUD bar and renders live focus peaking (neon green edges on live color feed), calibrated 16-zone false color heatmap, and crawling diagonal zebra stripes on highlights (>95% IRE) with zero impact on RAW recording.
5. **RVSP Deserialization & Container Tooling**:
   - `RvspHeader.fromBytes()` discards the header `flags` field at offset 10 (hardcoded to 0).
   - `RvspReader` stream consumption: `frames()` and `audioChunks()` both call `readAll()`, so calling one exhausts the un-seekable input for the other.
   - `rvtool wav` hardcodes 48kHz stereo 16-bit, ignoring `metaJson` audio parameters. Partial frame extractions (`--start`, `--count`) extract full-length un-sliced audio.
6. **UI Pipeline Validation (`MainActivity.kt`)**:
   - Zstd-only without MIPI packing causes ~50% dropped frames (CPU choke compressing raw 16-bit frames); needs UI validation/warning.
   - WhiteLevel gating on MIPI packing switch evaluates to `0 <= 1023` when `camSel` is null, and default `recPackMipi = true` cannot be turned off if the switch is disabled for high-bit-depth modes.

**Viewfinder rendering fixes (2026-08-30)** — two defects behind "the view itself is
incorrectly viewed", one a fresh regression, one latent:

1. **HUD vertical-strip corruption (regression from the panel-rotation fix).** The
   landscape-panel fix introduced `RotatedContent` (swapped-constraints measurement:
   content lays out landscape-shaped, rotates about its center, rotated bounding box
   is reported as the slot — correct for the wide dial panels). It was then also
   wrapped around the HUD pill's contents. For a wide-SHORT strip, swapping
   measurement and reporting the rotated box turns a ~200×36dp pill into a
   36×200dp slot — a giant vertical bar in landscape. **Rule extracted** (now in
   AGENTS.md): `RotatedContent` is only for wide-short→tall-sheet blocks (panels,
   pill content); wrap-content horizontal strips stay window-horizontal — the
   pinned-layout rule — with only glyph-scale elements using `rotateIcon()`.
2. **`chooseBufferSize` aspect penalty was dead code** (`FixedViewfinder.kt`):
   `abs(ratio − 4/3).toLong()` truncates every sub-1.0 aspect difference to 0
   before the ×1M scale, so buffer choice was area-only — a 16:9 candidate
   closer in area than the best 4:3 candidate could win the preview buffer,
   silently mis-framing every viewfinder mode and the WYSIWYG crop (all assume
   4:3 sensor-shaped content). Rewritten **4:3-first**: near-4:3 candidates
   (tolerance 0.02) win, closest-area among them; area-only fallback when no
   4:3 size exists. Pure logic extracted to `selectBufferSize` (JVM-testable;
   `BufferSelectionTest` covers the old failure case: 1920×1080 loses to
   1600×1200). Suite: **93/93**.

Device verification of these fixes was skipped (phone locked, USB flaky); the checks
when convenient: HUD must be a normal horizontal pill in both orientations, aspect
bands sane in Full/4:3/16:9/2.39, and a 2.39 take should extract 4096×1712 DNGs
matching the framed band.

**Viewfinder right black bar + zoom — transform matrix semantics (2026-08-30, fixed & verified).**
User reported a black bar on the right and an over-zoomed image; hypothesis "full
resolution failing to scale" was **disproven by trace** — preview sizes flow only from
`getOutputSizes(SurfaceTexture)` (1920×1440 class); the 4096×3072 RAW size never
enters the preview path. The real defect: `applyTransform` wrote **buffer-pixel
scales directly as matrix entries** (`a = d = s = max(viewW/effW, viewH/effH)`), but
AOSP `TextureView.setTransform` (pinned from `TextureView.java` + Open Camera's
`configureTransform`, which composes `setRectToRect(view, buffer)` then `postScale`)
applies the matrix to **view coordinates after the buffer is pre-stretched
anisotropically to the view**. Measured before-fix: content rendered into
x∈[−1198, 1100] on portrait 1220×2712 with the 1920×1440 buffer → a **139px
pure-black right bar (11.4%)** and ~3.5× vertical zoom — matching the predicted
signature to within dark-scene noise.

Fix (in `ViewfinderMath`, pure + JVM-tested): entries **`a = k·bufW/viewW`,
`d = k·bufH/viewH`** where k is the FILL/FIT presentation factor (view-px per
buffer-px). These undo the pre-stretch per axis, so the final buffer-px→view-px
equals k in both axes — **square sensor pixels, no distortion**, with FILL covering
(content extent k·effW × k·effH ≥ view) and FIT letterboxing symmetrically.
Verified on-device: after-fix screenshot has **zero exact-black columns** in
x=900..1215 (before: 27 consecutive), no hard content edge, full-height content;
annotated before/after pair in `artifacts/vf_issue_annotated.png` /
`vf_after_annotated.png`. Two failed intermediate models are worth remembering:
(1) view-space ratios `a = effW/bw·vw / vw` collapse to identity for full-buffer
crops; (2) "uniform buffer-space scale" `a = S·effW/bw` still double-applies the
pre-stretch anisotropy. The per-axis-undo form above is the only correct one.
Tests: `ViewfinderTransformTest` (8 cases incl. the regression guard and
rotation fixed-point/determinant invariants), `BufferSelectionTest` now also pins
the landscape-preference fallback (portrait 1440×1920 loses to landscape 1920×1080;
all-portrait list still returns a portrait size). Suite: **102/102**.

**Aspect "stuck on default" — dual-holder split-brain (2026-08-31, defect #3 of the
viewfinder saga, fix deployed).** After the transform fix, the aspect modes still
appeared "stuck": `FixedViewfinder.wire()` constructed its **own local
`ScaleHolder(FILL, null)`**, captured by the SurfaceTexture listener closures. The
`update{}` recomposition path wrote the *composed* `scaleState` correctly, but every
**listener-path** re-application — recording-session handover (preview surface
attached to the RAW session and back), buffer-size resets, preview self-heal after a
Google-Assistant camera steal — re-applied the transform from the local holder,
silently rendering FILL/no-aspect regardless of the chosen mode. Two reinforcing
factors: the factory runs `applyTransform` before first layout (`tv.width == 0` →
degenerate 1×1-view matrix), and nothing re-applied after window re-layout.

Fix: ONE holder everywhere — `wire()` now receives the composed `scaleState`
(listener, re-latch, and recomposition paths all see current mode), plus a
global-layout listener re-applies the transform on every real view-size change
(idempotent per size), curing both the pre-layout degenerate matrix and stale
transforms. Suite: **105/105**. Lesson (now an AGENTS.md rule): mutable
presentation state read by callbacks must be a single shared holder threaded to
every consumer — a "convenience" local copy in a setup function becomes a
split-brain reset on the next event. Verification checklist when the phone's
available: (1) cycle all 5 aspect modes — each tap visibly changes framing (FIT
bands at 510/686/915px vs FILL crop); (2) start a recording in 2.39 mode —
framing must STAY letterboxed through the session handover and after stop (the
exact failure path); (3) survive a camera-steal/self-heal cycle without losing
the mode.

**Aspect modes orientation-unaware + exposure mode relocated (2026-08-31/09-01, defect #4; code done, on-device verify pending).** Two user reports, one combined change:

1. *Aspect didn't respond to device orientation.* First implementation **rotated the
   whole presentation** with the device (image + band via a `presentationRotation`
   param, view-swap in `presentationScale`). The user corrected it the next day:
   *"Why does the viewfinder rotate? it should not, but the aspect ratio outline
   rotate, which is great"* — the camera-body rule won over the "respond to
   orientation" reading. **Final design:** the IMAGE is fully chassis-locked again
   (only the debug override can turn it); the **framing OUTLINE re-orients with the
   device** (`RotatedOverlay(animate = false)` around the overlay Canvas) as a
   hold-relative framing-intent guide on top of the fixed image. The pure pieces
   survive from the first pass: `ViewfinderMath.effectiveCrop` (buffer-aligned crop
   — wide limits cut a centered height band; its first draft inverted the branch
   and cut width, caught by the table tests) and `presentationScale`'s optional
   rotation view-swap overload (kept for opt-in callers; production never passes
   it). **Known open consequence:** in landscape hold the rotating outline does
   NOT coincide with the chassis-locked image's FIT band — it marks the intended
   widescreen framing relative to your hold, while the recording always stores
   the sensor-aligned crop (4096-wide band). If landscape takes should store a
   landscape-oriented crop (outline == recorded exactly), that's the unresolved
   "recording follows hold" question — needs DNG Orientation tags + a
   hold-aware crop; not built.
2. *Exposure mode moved out of Settings.* The "Auto exposure" toggle is removed
   from the Pipeline card; the choice lives in the manual-control dial panels —
   both the ISO and Shutter panels carry an **AUTO** switch wired to the single
   `cameraControls.autoExposure` (one exposure mode, not separate auto-ISO/auto-
   shutter; `autoIso` stays synced). Shutter panel hides sliders/chips in auto
   and titles "Shutter AUTO"; pills show "A" via new pure getters
   (`formattedExposure`/`formattedIso`). Settings record paths now take the
   exposure from `cameraControls` (single source of truth; `recAutoExp` state
   removed, `onRecordConfigChange` is 4-slot zstd/pack/mic/proxy).

Suite: **112/112** after the revert (transform tests re-pinned to the
chassis-locked production path with the swap overload kept as an opt-in case;
3 new control-state cases). Deploy blocked by USB dropout; verification when
reconnected: portrait regression pass, outline rotates with hold while the image
stays fixed, AUTO switch flipping exposure live in both panels, zero camera
session rebuilds across rotation.

**Representational viewfinder — FILL removed from production, buffer cut to 960×720
(2026-08-31, built on the stock-camera decompilation; deployed, on-device verify
pending).** Follows the decompilation findings in §6a: `CinemaViewfinderScreen` now
passes `ScaleMode.FIT` unconditionally (the `when` with a Full/Grid-FILL branch
collapsed to a constant) — this fixes the "Full mode zoomed ~3×" report at its root
(FILL was cover-cropping the 4:3 sensor frame into the ~9:20 window, slicing its
long axis to ~33.7% width) — and `FixedViewfinder`'s buffer target dropped
1920×1440 → 960×720 (same 4:3 aspect, ~4× standing preview-bandwidth cut;
`BufferSelectionTest` re-based: exact-match, F6-like list, 16:9 fallback,
all-portrait, empty-fallback cases). Suite: **146/146**; APK installed. Follow-up
landed 2026-09-02 (see §6a): aspect ratio button shows active ratio label (Full,
2.39:1, 16:9, 4:3, 1:1) directly on the button; viewfinder orientation compensates
sensorOrientation (90°) so preview is upright (no sideways world or 3.77× zoom blow-up);
Gridlines is a dedicated toggle in scopes menu; resolution selector probes binned,
high-res, and unbinned 50MP modes (`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`)
and is accessible from both the Cinema top HUD pill and Settings; 1:1 square recording
center-crop supported via `MipiPacker.cropRect` (3072×3072 on 12MP mode).

**Roadmap** (per PLAN.md): Phase 6 multi-SoC tuning (Qualcomm/MTK/Exynos/Tensor/Unisoc,
unbinned 50 MP, vendor tags) · Phase 7 rvtool desktop GUI suite · Phase 8 published spec +
reference decoders (C++17 header + Python) · Phase 9 root mode-forcing + 30-min thermal
soak.

---

## 10. Quick-reference: decisions and their one-line reasons

| Decision | Why (compressed) |
|---|---|
| Camera2 RAW_SENSOR, not kernel/CAMSS/CHI | Only route on a rooted retail phone without chipcode license; RDI gives untouched Bayer anyway |
| Own RVSP container, not MCRAW/CinemaDNG-stream/BRAW | Ownership beats adoption; CinemaDNG is the exchange format, not the capture format |
| MIPI pack before zstd | −37.5% bytes before entropy coding; zstd-on-expanded measurably pointless |
| Persistent per-worker ZSTD CCtx | Per-frame create/free was a 4× regression (pool churn) |
| No MediaCodec in the proxy | c2.qti SIGSEGVs in ByteBuffer mode during RAW sessions (deterministic, vendor HAL) |
| File logger, not logcat | Camera HAL floods/evicts logcat within seconds |
| Activity locked portrait + own accelerometer rotation engine | Android rotation destroys viewfinder aspect; our engine never touches the window |
| Viewfinder transform locked at 0° (chassis-locked) | Sensor is fixed to the chassis; only UI chrome should rotate |
| material3 1.5.0-alpha16 | 1.4.0 internal, alpha27+ needs AGP 9.1/SDK 37; alpha16 = only usable M3E train |
| Single-IFD0 DNG + tag 51044 + audio\ subfolder | OCTOPUS RAW Player freezes on two-IFD, modals without FrameRate, crashes on top-level *.wav |
| adb in-repo (`tools\adb`) | Not on PATH; serial `99c95387` |
| Desktop decode via reflection on zstd-jni | Keeps the 5 MB multi-platform jar out of the APK |
| rvtool GUI on Swing, compiled on demand by rvtool.ps1 | In-JDK toolkit = zero new deps/classpath entries; Swing absent from android.jar forces desktop-only compilation of tools/gui/RvtoolGui.kt (embeddable Kotlin compiler + 5 runtime jars from gradle cache) |
| Aspect WYSIWYG via packer-level software crop | SCALER_CROP_REGION RAW buffer semantics are HAL-dependent (risk: native pack reads past a shrunken gralloc buffer → SIGSEGV); software crop keeps the sensor path untouched and the framing math identical to the viewfinder's FIT band by construction |
| All viewfinder modes FIT by default; Immersive opt-in fills Full/Grid only | FILL cover-cropping Full was the "~3× zoom" report; FIT is representational (validated against the stock decompilation — their display rect letterboxes every mode). Immersive restores edge-to-edge for full-sensor modes; band modes are never cover-cropped (outline must equal the recorded band) |
| One small preview buffer (~0.69MP in the RAW mode's aspect), never switched per aspect | Stock decompilation proved Xiaomi never switches the preview buffer either (GL letterbox + capture-time crop tags); a 0.69MP target cuts always-on preview bandwidth ~4× with no visible loss for a framing guide |
| Camera + RAW size on shared state (recCamSel/recSizeSel) | The Settings dropdown used to be composable-local while the cinema record button always took the largest size — a split brain; both start paths + preview now read one selection |
| Cinema Scrim / Lookaround matte (55% dimmed) over solid black | Solid black bars blinded operators to off-screen context; cinema monitors (SmallHD, ARRI, RED) dim the non-recorded area so boom mics and entering subjects remain visible while active recording frame stays 100% bright |
| Recycled payload buffer pool (15 frames) | Eliminates 860 MB/s of transient Java heap churn from per-frame NewByteArray allocations, preventing 30ms ART GC pauses from starving inQ |
| 2-way sliced parallel row packing in C++ | Native SliceWorker cuts MIPI RAW10 packing latency from 38ms to 2.4–5.4ms per frame on ARM64 NEON cores |
| While-loop acquireNextImage drain | Android BufferQueue callback is edge-triggered; single acquire left burst frames un-acquired, permanently silencing the callback and freezing the viewfinder |
| Auto-Disable Viewfinder setting | Powers down live viewfinder rendering after a configured timeout (Never/5s/10s/30s/60s) with OLED telemetry and tap-to-wake, eliminating GPU/screen power draw to lock 30 FPS |

---

## 11. Case Study: Zstd Concurrency, BufferQueue Edge-Trigger Silencing, and Zero-Allocation Pipeline Tuning (2026-09-09)

### The Report & Hypothesis
During long takes and full-sensor 4:3 recordings (4096×3072 @ 30 FPS) with Zstd compression enabled, the app experienced intermittent freezes:
> *"When using zstd compression it seem some scenario the viewfinder froze and making the footage not recording, my theory is that in zstd optimization phase there could be a hidden race condition that cause the viewfinder and recording to froze and not record."*

Early telemetry and logcat traces captured:
1. `drop=[in=140 mid=0 out=0 acq=0]` — `inQ` dropped 140 frames in a 15-second take, while Zstd (`midQ`) and disk I/O (`outQ`) reported zero drops.
2. Takes freezing or halting around 22–33 seconds.
3. Viewfinder freezing on record trigger with camera error 4 or camera inaccessibility after backgrounding.

### Multi-Layer Root Cause Analysis

Investigation proved there was not a simple benign race condition, but three compounding, cross-layer failure modes intersecting when Zstd compression was active:

#### 1. BufferQueue Edge-Trigger Silencing in Camera2 (`RawCaptureEngine.kt`)
- **Mechanism**: Android's `ImageReader.OnImageAvailableListener` is **edge-triggered** by the underlying hardware `BufferQueue` (it fires *only* on the transition from empty to non-empty).
- **Failure**: When Zstd compression placed heavy load on the CPU, a burst arrival from the camera HAL delivered multiple frames simultaneously. The callback executed only a single `r.acquireNextImage()`. Because older frames remained in the `BufferQueue`, the queue never transitioned back to empty. Consequently, `OnImageAvailableListener` was permanently silenced! The camera HAL stopped notifying the app, freezing both the repeating preview stream and RAW capture.
- **Resolution**:
  - Replaced single acquire with an internal draining loop:
    ```kotlin
    while (true) {
        val img = try { r.acquireNextImage() ?: break } catch (_: IllegalStateException) { break }
        onFrame(img, meta)
    }
    ```
  - Expanded `maxImages` from 7 to 10–12 to guarantee the mathematical invariant `maxImages >= inCapacity + 3`, eliminating `ACQUIRE_MAX_IMAGES` exceptions.

#### 2. JNI Heap Churn, ART GC Freezes, and 2-Way Parallel Row Packing (`rawrec_jni.cpp`)
- **Mechanism**: In `rawrec_jni.cpp`, `packMipi10Direct` sequentially packed all 3,072 rows × 4,096 pixels on a single CPU core (~38 ms, exceeding the 33.3 ms frame period at 30 FPS).
- **The 860 MB/s GC Bottleneck**: Each packed frame allocated a new 15.7 MB `jbyteArray` (`env->NewByteArray`), and Zstd output allocated another 13 MB `jbyteArray`. At 30 FPS, the app generated **~860 MB/s of transient Java heap garbage**.
- **The Stall**: Android ART concurrent garbage collection was triggered continuously. Inside `NewByteArray`, the thread paused for **25–35 ms** waiting for GC sweeps to reclaim heap space. Total extraction latency spiked to 50–60+ ms, overflowing `inQ` and dropping 10 frames every second.
- **Resolution**:
  - **Persistent 2-Way Sliced Row Packing**: Implemented `SliceWorker` in native C++ pinned to `setpriority(PRIO_PROCESS, 0, -8)` (`THREAD_PRIORITY_URGENT_DISPLAY`). The worker packs rows $[Y_{\text{mid}} \dots Y_{\text{height}})$, while the extractor thread concurrently packs rows $[0 \dots Y_{\text{mid}})$. Pure NEON bit-packing latency dropped from ~38 ms to **2.4 ms – 5.4 ms**.
  - **Zero-Allocation Recycled Buffer Pool (`RecordingController.kt`)**: Added `payloadPool`, a pre-allocated pool of 15 `ByteArray` buffers ($15 \times 15.7\text{ MB} = 235\text{ MB}$, within the 512 MB heap limit).
  - Added native non-allocating entry points `packMipi10DirectInto` and `packMipi10CroppedDirectInto` via non-blocking `env->SetByteArrayRegion`.
  - As soon as Zstd finishes `encoder.encode(rawPayload)`, it immediately returns `rawPayload` to `payloadPool`. Heap allocation during streaming dropped to **0 MB/s**, eliminating ART GC pauses.

#### 3. CPU Core Contention & Storage I/O Stalls (`SocOptimizer.kt`)
- **Mechanism**: The Qualcomm Snapdragon 8s Gen 3 (SM8635) has 8 CPU cores: 1 Cortex-X4 (prime) + 4 Cortex-A720 (performance) + 3 Cortex-A520 (little).
- **Core Contention**: Running 4 Zstd workers at 100% CPU load saturated the 4 A720 cores, pushing the extractor thread, native slice worker, writer, and Qualcomm CamX hardware interrupt threads onto the little A520 cores, tripling processing times.
- **I/O Freeze at 22s**: Periodic `fdatasync()` calls every 5 seconds flushed ~4 GB of dirty Linux page cache at once, blocking the writer thread for 5–10 seconds and backing up all queues.
- **Resolution**:
  - Removed periodic `fdatasync()` from the streaming loop, relying on Linux F2FS / UFS 4.0 background writeback.
  - Sized queues cleanly: `in=8, mid=12, out=12, maxImages=12`.
  - Tuned Zstd to ultra-fast level `-3`, boosting per-worker compression throughput to ~40 FPS.
  - Added the **Auto-Disable Viewfinder** feature (`AppPreferences.autoDisableVfSeconds`), allowing operators to power down live viewfinder rendering after a configurable timeout (5s/10s/30s/60s) with an OLED power-saving telemetry HUD and tap-to-wake, cutting display and GPU power draw during takes.

### Verified Results on POCO F6 (`99c95387`)
- **16:9 Mode (4096×2304 @ 30 FPS, 15s take)**: 414 frames written (4.33 GB), `drop=[in=0 mid=0 out=0 acq=0]` — **0 dropped frames**.
- **2.39:1 Mode (4096×1712 @ 30 FPS, 15s take)**: 400 frames written (3.17 GB), `drop=[in=0 mid=0 out=0 acq=0]` — **0 dropped frames**.
- **Viewfinder Sleep & Wake**: Viewfinder entered OLED sleep mode at 5s, screen turned pitch black with live glowing telemetry, and tap-to-wake restored full live feed with zero latency and zero frame drops.
- **Stationary Viewfinder Feed Invariant (2026-09-09)**: When rotating the phone to other orientations, the viewfinder feed does NOT rotate, flip, or resize. In `OrientationMode.CINEMA_LANDSCAPE`, the window is locked to landscape (`SCREEN_ORIENTATION_LANDSCAPE`) and the feed is permanently anchored to 270° (90° CCW for 90° back sensors). In `OrientationMode.CHASSIS_LOCKED`, the window is locked to portrait (`SCREEN_ORIENTATION_PORTRAIT`) and the feed is permanently anchored to 0°. Only UI glyphs/icons counter-rotate via `LocalIconRotation`.


**Open Camera research (2026-08-29, for the aspect-WYSIWYG design).** Studied
from the local clone (`%TEMP%\opencode\opencamera`, master): their aspect
behavior is *view-sizing* — `Preview.getMeasureSpec` (Preview.java:950-1032)
custom `onMeasure` letterboxes the TextureView itself inside a centered
FrameLayout (black bars are the surrounding layout, not drawn), and a separate
`CanvasView` stacked on top at the same measured size hosts all overlay drawing.
Their "aspect setting" (`preference_preview_size`: WYSIWYG vs display-fill,
read at MyApplicationInterface.java:1119) actually selects a **preview buffer
size** whose aspect matches the photo/video aspect within 5% tolerance
(`calculateTargetRatioForPreview` → `getOptimalPreviewSize`,
Preview.java:3969-4096) — they never crop pixels; WYSIWYG is achieved by
buffer choice + view sizing. Residual mismatch → onMeasure bars. Zoom is
request-level (`SCALER_CROP_REGION` pre-R, `CONTROL_ZOOM_RATIO` on R+,
CameraController2.java:4356-4397) so preview and capture crop identically —
but always on processed streams, never RAW_SENSOR. RawRec's equivalent: the
"output" is the full-sensor RAW, so a matching buffer can't be chosen — hence
the packer-level crop of the captured band instead (see quick-reference row
above and AGENTS.md device facts).
