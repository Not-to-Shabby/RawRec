# RawRec — Plan of Record

Goal: rooted-Snapdragon Android app capturing unprocessed Bayer via Camera2 RAW_SENSOR,
storing a true-RAW master in an open, self-owned container, with cross-platform desktop
conversion to CinemaDNG for editors. Optional low-res HEVC proxy + DNG stills.

## Locked decisions
- Fresh project (no fork). Vendored patterns only (Camera2 coroutine helpers, DngCreator stills).
- Container: own open spec ("RVSP", see container-spec-draft.md), Apache-2.0 published.
  Payload codecs pluggable: store / LZ4 / zstd. PCM audio v1.
- Desktop: cross-platform C++17 CLI `rvtool` -> CinemaDNG folder -> DaVinci Resolve ingest.
- RAM staging buffer as shock absorber; watermark-based drop policy; never blocks capture thread.
- DeviceProfiles DB (gcam-style) keyed by MANUFACTURER_SOC: raw packing quirks, hidden modes,
  root override presets; every assumption validated against first real frame.

## Phases
0. ModeProbe capability report            [app/probe] DONE
1. CaptureEngine -> Writer MVP            DONE (zero-copy, worker pools, native pack)
2. Compressor benchmarks (ZSTDMT)         DONE: pack+zstd 96.5% retention @50% ratio;
                                          pack-only 0 drops; storage ceiling ~665MB/s
3. rvtool extract -> CinemaDNG            DONE: single-IFD spec compliance (OCTOPUS RAW Player + Resolve tested)
4. Proxy MP4 + audio capture + full UI    DONE: 48 kHz stereo PCM into RVSP + MP4 proxy with unit tests
5. Camera Controls & M3 Viewfinder        DONE:
   done — Material 3 cinema layout, manual ISO/shutter/focus/WB, chassis-locked
   viewfinder + own rotation engine, aspect/FIT presentation, camera self-heal;
   capture-quality pass done — sensor colorimetry into header+DNG (real
   black=64/D65/matrix on-device), timestamp-keyed result pairing, controls
   honored from frame 0 (manual-mode frame-duration pin, auto-mode fps range),
   REALTIME audio clock + tsSource metadata, record toggles hoisted (cinema
   button no longer hardcodes mic/proxy on; retention 50%→78% with all-on,
   ~29fps sustained); aspect WYSIWYG done — viewfinder aspect modes are shared
   control state and 2.39:1/16:9 takes store the same center band the
   letterboxed viewfinder shows (packer-level crop, 4096×1712/4096×2304;
   Full/4:3/Grid full-sensor; cropRegion in metaJson); representational
   viewfinder done — all modes FIT (Full/Grid letterbox the whole sensor frame,
   ~3x-zoom FILL cover-crop removed), preview buffer target cut to 960x720
   4:3-first (~0.69MP, ~4x always-on bandwidth cut; stock-HyperOS decompilation
   validated the never-switch-buffer approach); presentation option + selector
   done — aspect button shows active ratio label (Full, 2.39:1, 16:9, 4:3, 1:1),
   upright preview orientation compensates sensorOrientation (eliminating sideways
   feed and 3.77x zoom blow-up), Gridlines is dedicated scope toggle, resolution
   selector probes binned, high-res, and unbinned 50MP modes accessible on both
   Cinema top HUD pill and Settings, 1:1 square crop supported (3072x3072);
   live scopes done — ScopeAnalyzer taps downscaled 240x180 preview frames directly
   from TextureView at 16 FPS on Dispatchers.Default, feeding real-time
   HistogramScopeView and rendering live focus peaking (neon green edges on live
   color feed), 16-zone calibrated IRE false color heatmap, and crawling diagonal
   zebra stripes on highlights (>95% IRE) with zero impact on RAW recording.
6. Multi-SoC & Sensor Opcode Optimization DONE:
   done — SocFamily & SocOptimizer architecture detection across Qualcomm,
   MediaTek, Exynos, Tensor, and Unisoc; CPU cluster tuning and Linux thread
   priorities; 50MP unbinned dynamic queue sizing and memory bounding (caps peak
   RAM < 350MB); dynamic vendor camera tag engine (VendorTags) applying
   Qualcomm/Xiaomi performance and manual priority keys; sensor opcode extraction
   (lens distortion, calibration, optical black, shading map) into RVSP metaJson;
   DngOpcodes encoder in rvtool writing standard DNG OpcodeList1 (GainMap) and
   OpcodeList3 (WarpRectilinear) tags (51008/51022) for native lens correction in
   DaVinci Resolve and Adobe Camera Raw.
6.5. SoC & Sensor-Specific Optimization Suite DONE (2026-09-07):
   done — Tier 1: acquireNextImage FIFO lossless frame delivery replaces acquireLatestImage;
   capture HandlerThread priority elevated to URGENT_DISPLAY;
   Tier 2: ThermalGovernor listening to Android OnThermalStatusChangedListener, 10s predictive
   headroom forecasting (getThermalHeadroom), and Xiaomi HyperOS action_temp_state_change;
   pure SocOptimizer.tuningFor degradation ladder (MODERATE: workers-1, SEVERE: workers=2 inQ-2,
   CRITICAL: studio header badge + RecStats.error); zstd-pool-only rescale;
   Tier 3: ADPF PerformanceHintManager hint session registering pipeline TIDs, target frame
   period, per-frame reportActualWorkDuration work telemetry; HyperOS com.miui.powerkeeper
   record_start/record_end broadcasts lifting power throttling;
   Tier 4: native ARM64 NEON SIMD MIPI RAW10 bit-packing (pack_row / pack16_neon) vectorizing
   16 u16 samples per iteration; JNI null-checks and crop bounds safety; memcpy-accelerated
   expandCopyDirect; ZstdNative.createCCtxEx windowLog plumbing; 5-second fdatasync cadence;
   Tier 5: sensor FPS-range capability probing filtering FPS dial options; SENSOR_MAX_ANALOG_SENSITIVITY
   marking analog ISO ceiling; dual-illuminant colorimetry (colorMatrix2, calibIlluminant2,
   forwardMatrix1/2) and SENSOR_NOISE_PROFILE into metaJson and DNG tags 50722/50779/50969/50970/50974;
   Tier 6: CaptureForegroundService with foregroundServiceType="camera", ongoing notification,
   and partial wake lock, preventing Android 14/15 and HyperOS background camera cgroup freezing.
7. Desktop rvtool Studio GUI Suite        DONE (2026-09-12):
   v1 player — scrubber, per-frame ISO/exp/ts HUD + FrameInspector verdict,
   RGB/gray preview, scopes (peaking/false color/zebras/histogram), playback +
   WAV audio, exports (bmp/rgb/wav/extract);
   v2 Cinema studio deck — custom Deck Swing controls
   (button/toggle/slider/combo), structured take-inspector telemetry cards,
   RGB+luma histogram with IRE grids, clip % readout, frame stepping, LOOP
   repeat playback (auto-rewind at end), SMPTE timecode transport;
   grading suite — 16 clean-room ToneProfiles + .cube LUT import, OpenCL
   (default)/Vulkan/CPU engine selection, CinemaDNG tone-tag embedding
   (50936/50981) or baked grading, BT.2100 HLG MP4 export with colr.nclx
   and pure-Kotlin baseline JPEG encoding;
   Phase 7 pass: per-frame ISO & shutter exposure telemetry curve plots
   (CurvePlotPanel) synced with playhead slider cursor, synchronized 48kHz audio
   playback/pause/loop/scrub transport, and multi-take batch processing
   (CLI `rvtool batch` + GUI `BATCH...` dialog).
8. Spec + reference decoder published     DONE (2026-09-12):
   Formal docs/RVSP_SPEC.md publication-grade specification with exact offsets,
   colorimetry mapping, JSON schema, and CinemaDNG 1.4 TIFF tags;
   single-header C++17 library in include/rvsp.h with zero external dependencies;
   standalone Python 3 reference decoder in tools/rvsp_decode.py.
9. Endurance & Thermal Soak Test          DONE (2026-09-12):
   Full production load soak test on POCO F6 (12.5 MP 4096x3072 @ 30 FPS, Pack+Zstd):
   - Continuous 177s capture writing 36.37 GB (5,165 RAW frames).
   - 99.3% frame retention (5,165 written / 5,202 captured, only 25 drops in 3 mins).
   - Sustained UFS write bandwidth: 205.5 MB/s.
   - Thermal stability: battery temp rose from 32.2°C to 37.1°C (ΔT = +4.9°C), thermal status remained NONE (0).
   - 95% storage auto-stop safety mechanism autonomously halted the take at exactly 95.0% full to prevent OS disk exhaust.
   - Zero gralloc buffer leaks, preview re-engaged immediately.

## Stability & Quality Audit Fixes (Tracked)
1. ✅ **JNI Safety (`rawrec_jni.cpp`)**:
   - Added explicit `nullptr` checks on all `env->GetByteArrayElements` and `env->GetIntArrayElements` calls before dereferencing (`ZstdNative_compress`, `ZstdNative_decompress`, `ZstdNative_decompressInto`, `packMipi10ProxyDirect`, `packMipi10ProxyCroppedDirect`).
   - Added DirectByteBuffer capacity vs max row offset checks (`(cropTop + cropHeight) * rowStride <= capacity`) in `packMipi10CroppedDirectInto` and `packMipi10ProxyCroppedDirect` to eliminate gralloc memory read-past SIGSEGV risks.
2. ✅ **Proxy MP4 64-Bit Chunk Offsets (`Mp4ContainerWriter.kt`)**:
   - Replaced fixed 32-bit `stco` with standard ISO 14496-12 64-bit `co64` chunk offset box for both video and audio tracks when chunk offsets exceed 2 GB (`0x7FFFFFFFL`), preventing index truncation on long takes.
3. ✅ **Capture Teardown Cleanup & Buffer Leak (`RecordingController.kt`)**:
   - Wrapped all thread joins (`extractorThread.join()`, `writerThread.join()`, `workerPool.awaitTermination()`) in `runCatching` to guarantee complete teardown sequence even if interrupted.
   - Added an explicit `inQ` gralloc buffer drain loop in `stop()`, polling and closing all unhandled `Image` objects (`img.close()`) to eliminate Android hardware buffer locks and camera re-acquisition errors.
4. ✅ **Live Scopes Pipeline (`CinemaScopes.kt`, `MainActivity.kt`)**:
   - Implemented preview-pixel tap to extract live luma/RGB statistics, feeding `HistogramScopeView` and real-time peaking/zebra/false-color overlays.
5. **RVSP Container Deserialization (`Rvsp.kt`, `RvspReader.kt`)**:
   - Implement preview-pixel tap to extract live luma/RGB statistics, feeding `HistogramScopeView` and real-time peaking/zebra/false-color overlays.
5. **RVSP Container Deserialization (`Rvsp.kt`, `RvspReader.kt`)**:
   - Parse and retain header `flags` at offset 10 in `RvspHeader.fromBytes()` (currently hardcoded to 0).
   - Cache records in `RvspReader` so `frames()` and `audioChunks()` can both be called on the same stream without exhausting un-seekable input.
6. **CLI Tooling Audio Metadata (`Rvtool.kt`)**:
   - In `rvtool wav`, parse sample rate, channels, and PCM format from `metaJson` instead of hardcoding 48kHz stereo 16-bit.
   - Slice audio output when `rvtool extract` is called with `--start` and `--count` frame ranges so partial exports remain audio-synced.
7. **UI Pipeline Validation (`MainActivity.kt`)**:
   - Add validation/warning preventing Zstd compression without MIPI packing (chokes CPU and drops ~50% of frames).
112	   - Fix first-launch permission flow so viewfinder preview starts reliably when permissions are granted before `previewSurface` is initialized.
113	8. **Zstd Concurrency, BufferQueue Silencing & Zero-Allocation Pipeline (`rawrec_jni.cpp`, `RecordingController.kt`, `RawCaptureEngine.kt`)**:
114	   - Diagnosed and resolved the freeze hypothesis (*"When using zstd compression it seem some scenario the viewfinder froze and making the footage not recording..."*).
115	   - **BufferQueue edge-trigger silencing fix**: Replaced single `acquireNextImage()` with an internal `while (true)` drain loop in `RawCaptureEngine.kt` so burst frames never silence `OnImageAvailableListener`, and expanded `maxImages >= inCapacity + 3`.
116	   - **2-way sliced row-parallel packing**: Added persistent `SliceWorker` thread with `THREAD_PRIORITY_URGENT_DISPLAY` in `rawrec_jni.cpp`, cutting MIPI RAW10 packing latency from 38ms to 2.4–5.4ms per frame.
117	   - **Zero-allocation buffer pool**: Pre-allocated 15 recycled `ByteArray` frames (`payloadPool`) in `RecordingController.kt` paired with `packMipi10DirectInto`/`packMipi10CroppedDirectInto`, eliminating 860 MB/s heap churn and 30ms ART GC freezes.
118	   - **Auto-Disable Viewfinder**: Added user setting (Never/5s/10s/30s/60s) with pitch-black OLED telemetry HUD and tap-to-wake, cutting GPU and display power draw during takes.
119	   - **Verification**: Verified on POCO F6 with 0 dropped frames in both 16:9 (414 frames, 4.33 GB) and 2.39:1 (400 frames, 3.17 GB) at locked 30.0 FPS.
120	
121	## Non-goals (current)
- MCRAW write compatibility
- Main10 HDR encode path (deferred)
- FUSE mounter (post-v1)
