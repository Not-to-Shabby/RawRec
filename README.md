# RawRec
**Professional Mobile RAW Sensor Video Recorder & Cinema Studio Suite**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_14%2B_%7C_Windows_%7C_Linux-green.svg)](#)
[![Format](https://img.shields.io/badge/Container-RVSP_v1.0-orange.svg)](docs/RVSP_SPEC.md)

> 🤖 **AI Disclaimer**: 100% of this codebase was created by a clanker.

RawRec captures untouched, uncompressed 10-bit / 12-bit / 14-bit Bayer frames directly from mobile camera sensors via Android Camera2 `RAW_SENSOR`, completely bypassing manufacturer computational photography and ISP post-processing (noise reduction, edge sharpening, and tone-mapping).

Frames and synchronized 48 kHz linear PCM or 32-bit float audio are streamed in real time into the open, append-only **RawRec Video Sensor Payload (`.rvsp`)** container, supported by an on-device Studio Gallery, desktop playback tools (`rvtool`), a single-header C++17 library, a standalone Python reference decoder, and 1:1 export into **Adobe CinemaDNG 1.4** and **BT.2100 HLG MP4**.

---

## Key Capabilities

* **Hardware ISP Bypass (Raw Sensor Capture)**: Direct Camera2 DMA streaming of unbinned (up to 50MP on IMX882) and binned 10-bit, 12-bit, and 14-bit Bayer pixel arrays.
* **Open RVSP Container Format (`.rvsp`)**:
  * **Dual-Mode Seeking (`IDXT` Index Trailer)**: Instant sub-millisecond (< 2 ms) file opening and timeline scrubbing for 50+ GB takes, with automatic fallback to sequential torn-tail scanning for crash resilience.
  * **Per-Record Integrity Checksums**: Hardware-accelerated 32-bit CRC32 checksums on every video and audio record, detecting silent flash storage bitflips.
  * **Crash-Tolerant Append Streaming**: Header written at $t=0$; abrupt battery disconnects or Android LMK kills never corrupt prior frames.
* **Cinema Audio Architecture**:
  * **32-Bit Float Audio Capture**: $1,528\text{ dB}$ of unclipped dynamic range via `AudioFormat.ENCODING_PCM_FLOAT`, eliminating digital clipping on loud sound spikes.
  * **Real-Time Stereo VU / Peak Meter**: Ballistic PPM metering with color-coded dBFS readouts (-60 to +12 dBFS).
  * **Broadcast Wave Format (BWF)**: Exports standard 32-bit float RIFF WAV (`0x0003` `WAVE_FORMAT_IEEE_FLOAT`) and 16-bit integer PCM WAV with SMPTE timecode.
* **High-Performance Native Pipeline (`librawrec.so`)**:
  * **Vectorized NEON Bit-Packing**: 2-way sliced row-parallel NEON packing for MIPI RAW10, RAW12, and RAW14 (2–5 ms per frame).
  * **Hardware Cache Exploitation**: L1 hardware cache prefetching (`PRFM`), private L2-bounded Zstandard dictionary window (`windowLog = 17` / 128 KB), and shared L3 buffer recycling.
  * **Scene-Adaptive Entropy Governor**: Closed-loop monitor dynamically scaling Zstd levels (`-3` $\rightarrow$ `-6` $\rightarrow$ `-9` / store) to guarantee 0 dropped frames under high-ISO noise or complex scenes.
  * **Linux Kernel Syscalls**: Direct page cache eviction via `posix_fadvise(POSIX_FADV_DONTNEED)` and CPU core affinity pinning via `sched_setaffinity`.
  * **16 KB Page Alignment**: Verified LOAD segments for Android 15+ kernels.
* **Material Design 3 Expressive UI & Scopes**:
  * **Clean-Feed Pillarbox Architecture**: Zero UI obstruction over the 4:3 camera image in landscape.
  * **Left-Thumb Pillarbox**: Vertical follow-focus wheel with barrel scale ticks, Cyan A & Amber B witness pins, one-tap `RACK A➔B` pull trigger, and vertical assist toggles (`Peak`, `False`, `Zebra`, `Hist`, `Grid`, `Aspect`, `Mirror`).
  * **Right-Thumb Pillarbox**: Compact 146dp exposure dock with optical lens switcher (`RawRecIcons.LensIris`), `FPS`/`SHUT`/`ISO`/`WB`/`LOOK`/`GATE` matrix, and isolated right-thumb record trigger.
  * **Active Recording Perimeter Tally**: 2px pulsing red outline drawn directly around the active recording frame.
  * **Viewfinder Pipelines**: Dual backend support (OpenGL ES 3.0 with live 3D `.cube` LUT shader vs. TextureView with CPU scopes).
  * **Front Camera**: Upright orientation with dynamic `Mirror` / `True` selfie toggle.
* **On-Device Studio Gallery**:
  * Integrated review deck with synchronized 48 kHz PCM and 32-bit float audio playback.
  * Live color look grading (Filmic, Rec.709, HLG HDR, Linear, Warm, Cool, Vintage, B&W Mono).
  * In-app GPU-accelerated BT.2100 HLG MP4 export and single-IFD Adobe CinemaDNG 1.4 sequence export.
* **Desktop Studio Deck (`rvtool`)**:
  * Cross-platform Swing GUI and CLI with nanosecond hardware-paced playback and SMPTE `HH:MM:SS:FF` timecode.
  * Synchronized 48 kHz stereo audio monitoring and timeline playback.
  * Per-frame ISO and shutter exposure time telemetry curve sparklines (`CurvePlotPanel`).
  * 16 clean-room analytical `ToneProfile` curves and `.cube` 3D LUT import.
  * Multi-backend compute acceleration (OpenCL, Vulkan, multi-threaded CPU).
  * Batch export processing for entire folders of `.rvsp` takes into CinemaDNG sequences or HLG MP4s.
* **Open Interoperability**:
  * **Adobe CinemaDNG 1.4**: Exports single-IFD0 sequences with embedded colorimetry and Adobe opcodes (`OpcodeList1` GainMap and `OpcodeList3` WarpRectilinear) for native lens correction in DaVinci Resolve.
  * **Single-Header C++17 Library**: Portable, zero-dependency [`include/rvsp.h`](include/rvsp.h) with `IDXT` index seeking and MIPI unpacking.
  * **Standalone Python 3 Reference Decoder**: Pure Python [`tools/rvsp_decode.py`](tools/rvsp_decode.py) for ML pipelines and color science research.

---

## Architecture Overview

```
[ Camera Sensor ] ---> Camera2 RAW_SENSOR Stream (Bayer DMA)
                              |
                              v
                        [ inQ Buffer ]
                              |
                              v
        [ "rvsp-extract" Sliced NEON Worker Thread ]
           (MIPI RAW10 Packing + Zero-Allocation Pool)
                              |
                              v
                       [ midQ WorkItem ]
                              |
                              v
               [ Multi-Threaded Zstd Pool ]
             (Persistent CCtx Single-Shot Block)
                              |
                              v
                      [ outQ Processed ]
                              |
                              v
         [ "rvsp-writer" Direct UFS Stream Thread ]
             (Append-Only Interleaved .rvsp File)
```

---

## Building from Source

### Prerequisites
- Android SDK with Platform-Tools 35+
- Android NDK (r27+ recommended)
- JDK 17 or higher
- CMake 3.22.1+

### Build Android APK
```bash
# Build Debug APK
./gradlew :app:assembleDebug

# Run Unit Tests (194 tests across 26 classes)
./gradlew :app:testDebugUnitTest

# Install on connected Android device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Desktop Studio Deck & CLI (`rvtool`)

The `rvtool` suite runs on any desktop JVM without Android dependencies.

### Launch Studio Deck GUI
```powershell
powershell -File tools/rvtool.ps1 gui take.rvsp
```
Features real-time playback, audio transport synchronization, SMPTE timecode, ISO/exposure telemetry curve plots, live scopes, look grading, and single-click CinemaDNG / MP4 exports.

### Command-Line Interface (CLI)
```powershell
# Display container telemetry, geometry, and drop statistics
powershell -File tools/rvtool.ps1 info take.rvsp

# Extract take into a single-IFD0 CinemaDNG frame sequence with audio
powershell -File tools/rvtool.ps1 extract take.rvsp output_dir/

# Export BT.2100 HLG MP4 with normalized stereo audio
powershell -File tools/rvtool.ps1 mp4 take.rvsp take_hlg.mp4

# Batch process an entire directory of takes
powershell -File tools/rvtool.ps1 batch input_folder/ output_folder/ dng

# Extract linear PCM or 32-bit float audio track to standard RIFF WAV
powershell -File tools/rvtool.ps1 wav take.rvsp audio.wav

# Validate Adobe DNG tags on extracted frame
powershell -File tools/rvtool.ps1 dngcheck output_dir/frame_000000.dng
```

---

## Standalone Reference Decoders

### Single-Header C++17 Library
Include [`include/rvsp.h`](include/rvsp.h) directly in your project:
```cpp
#include "rvsp.h"

rvsp::Reader reader;
if (reader.open("take.rvsp")) {
    const auto& header = reader.header();
    std::cout << "Take: " << header.width << "x" << header.height << " @ " << header.fps() << " fps\n";

    for (const auto& frame : reader.frames()) {
        std::vector<uint8_t> payload;
        reader.readPayload(frame, payload);

        std::vector<uint16_t> bayerPixels(header.width * header.height);
        rvsp::Reader::unpackMipi10(payload.data(), payload.size(), bayerPixels.data(), header.width, header.height);
    }
}
```

### Standalone Python 3 Decoder
```bash
# Print take metadata and JSON tags
python tools/rvsp_decode.py info take.rvsp

# List all frames with sequence numbers, timestamps, exposure, and ISO
python tools/rvsp_decode.py list-frames take.rvsp

# Extract embedded audio into WAV
python tools/rvsp_decode.py extract-wav take.rvsp take.wav

# Dump single raw unpacked 16-bit Bayer frame
python tools/rvsp_decode.py dump-frame take.rvsp --frame=0 --out=frame0.raw
```

---

## Container Specification

For the complete technical specification of the `.rvsp` container format, byte offsets, and JSON schemas, refer to [`docs/RVSP_SPEC.md`](docs/RVSP_SPEC.md).

---

## License

RawRec is open-source software licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for complete details.

---

## AI Disclaimer

100% of this codebase was created by a clanker.
