# RawRec
**Professional Mobile RAW Sensor Video Recorder & Cinema Studio Suite**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_14%2B_%7C_Windows_%7C_Linux-green.svg)](#)
[![Format](https://img.shields.io/badge/Container-RVSP_v1.0-orange.svg)](docs/RVSP_SPEC.md)

> 🤖 **AI Disclaimer**: 100% of this codebase was created by a clanker.

RawRec captures untouched, uncompressed 10-bit / 12-bit / 14-bit Bayer frames directly from mobile camera sensors via Android Camera2 `RAW_SENSOR`, completely bypassing manufacturer computational photography and ISP post-processing (noise reduction, edge sharpening, and tone-mapping).

Frames and synchronized 48 kHz linear PCM audio are streamed in real time into the open, append-only **RawRec Video Sensor Payload (`.rvsp`)** container, supported by desktop studio playback tools (`rvtool`), a single-header C++17 library, a standalone Python reference decoder, and 1:1 export into **Adobe CinemaDNG 1.4** and **BT.2100 HLG MP4**.

---

## Key Capabilities

* **Hardware ISP Bypass (Raw Sensor Capture)**: Direct Camera2 DMA streaming of unbinned and binned 10-bit/12-bit Bayer pixel arrays.
* **Open RVSP Container Format**: Crash-resilient, append-only binary container designed for high-throughput streaming with zero disk seeks. Battery disconnects or system interrupts never corrupt prior frames.
* **High-Performance Native Pipeline (`librawrec.so`)**:
  * 2-way sliced row-parallel NEON MIPI CSI-2 RAW10 bit-packing (4 pixels into 5 bytes in 2–5 ms).
  * Real-time multi-threaded Zstandard (`ZSTD`) frame compression, cutting storage bandwidth by ~55%.
  * Zero-allocation pre-allocated buffer pool (`payloadPool`), eliminating Android ART garbage collection freezes.
  * Verified **16 KB page alignment** for modern Android 15+ kernels.
* **Pro Viewfinder & Scopes**:
  * **Cinema Lookaround Scrim**: 55% dimmed matte keeping off-screen boom mics and subjects visible outside the active recording window.
  * **Stock Aspect Ratios**: Full, Widescreen 2.39:1, 16:9, 4:3, and 1:1 with WYSIWYG sensor-level cropping.
  * **Continuous Presentation Slider**: Smooth interpolation from 0% (Accurate optical fit) to 100% (Fill screen).
  * **Live Cinema Scopes**: Background 16 FPS zero-allocation pixel tap providing calibrated IRE RGB+Luma histogram, neon green focus peaking, 16-zone false color heatmap, and diagonal crawling zebras.
* **Desktop Studio Deck (`rvtool`)**:
  * Cross-platform Swing GUI and CLI with nanosecond hardware-paced playback and SMPTE `HH:MM:SS:FF` timecode.
  * Synchronized 48 kHz stereo audio monitoring and timeline playback.
  * Per-frame ISO and shutter exposure time telemetry curve sparklines (`CurvePlotPanel`).
  * 16 clean-room analytical `ToneProfile` curves (Filmic, Rec.2100 HLG, Vintage, Monochrome, etc.) and `.cube` 3D LUT import.
  * Multi-backend compute acceleration (OpenCL, Vulkan, multi-threaded CPU).
  * Batch export processing for entire folders of `.rvsp` takes into CinemaDNG sequences or HLG MP4s.
* **Open Interoperability**:
  * **Adobe CinemaDNG 1.4**: Exports single-IFD0 sequences with embedded colorimetry and Adobe opcodes (`OpcodeList1` GainMap and `OpcodeList3` WarpRectilinear) for native lens correction in DaVinci Resolve.
  * **Single-Header C++17 Library**: Portable, zero-dependency [`include/rvsp.h`](include/rvsp.h) for custom NLE/OFX integrations.
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

# Run Unit Tests (181 tests across 24 classes)
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

# Export BT.2100 HLG MP4 with 16-bit stereo PCM audio
powershell -File tools/rvtool.ps1 mp4 take.rvsp take_hlg.mp4

# Batch process an entire directory of takes
powershell -File tools/rvtool.ps1 batch input_folder/ output_folder/ dng

# Extract linear PCM audio track to standard RIFF WAV
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
