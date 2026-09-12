# RVTool — RawRec Desktop Studio & CLI Suite

Desktop companion toolkit for **RawRec** RAW video containers (`.rvsp`). Includes a cross-platform command-line utility and a Swing-based desktop player/scrubber with real-time video scopes and CinemaDNG exporter.

---

## Directory Layout

```
rvtool/
├── rvtool.ps1              # Primary launcher (CLI & GUI)
├── README.md               # Documentation & command reference
├── gui/
│   └── RvtoolGui.kt        # Swing Desktop Studio GUI application
└── libs/
    └── zstd-jni-1.5.6-4.jar # Native Zstandard decompression library
```

---

## Prerequisites

- **OS**: Windows 10/11 x64 with PowerShell 5.1+
- **Java**: JDK 17+ on PATH
- **Gradle**: Local Gradle cache populated with Kotlin standard library (run `.\gradlew.bat :app:assembleDebug` once)

---

## Quick Start

Run the launcher from anywhere inside or outside the repository:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' <command> [arguments...]
```

*(Note: `.\tools\rvtool.ps1` is also maintained as a forwarder for backward compatibility).*

---

## Commands

### 1. Studio GUI (`gui`)
Launches the Swing desktop player, timeline scrubber, and scope analyzer:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' gui [clip.rvsp]
```

- **Features**: Real-time playhead scrubber, synchronized 48 kHz PCM audio playback, per-frame ISO/shutter/timestamp HUD, and professional video scopes (focus peaking, false color exposure, zebra stripes, and RGB histogram).

### 2. Container Info (`info`)
Displays container geometry, bit depth, sensor calibration, project frame rate, and frame drop telemetry:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' info input.rvsp
```

### 3. CinemaDNG Export (`extract`)
Extracts a standard CinemaDNG uncompressed RAW frame sequence and synchronized `audio/audio.wav`:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' extract input.rvsp <outDir> [startFrame] [frameCount] [profile] [bakeTone] [lutPath]
```

- **A/V Sync Frame Drop Compensation**: Enabled by default. Automatically detects any missing frames from capture dropouts and duplicates the preceding frame across gaps, ensuring video length exactly matches audio length with zero drift in DaVinci Resolve or Premiere Pro.
- Pass `--no-compensate` to extract only physically captured frames without duplicate padding.
- Audio is extracted to `<outDir>\audio\audio.wav` for direct NLE timeline import.

### 4. Audio Extraction (`wav`)
Extracts 48 kHz stereo 16-bit PCM audio track to a standard RIFF WAV file:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' wav input.rvsp [output.wav]
```

### 5. Demosaiced RGB Snapshot (`rgb`)
Exports a demosaiced 24-bit RGB bitmap frame with optional tone curve or 3D `.cube` LUT:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' rgb input.rvsp [frameIndex] [output.bmp] [profile] [lutPath]
```

Supported profiles: `default`, `cine_filmic`, `cine_hlg`, `cine_ootf`, `cine_warm`, `cine_cool`, `cine_vintage`, `cine_bright`, `cine_mono`, `custom_lut` (legacy alias: `cine_venice`).

### 6. Raw Bayer Snapshot (`bmp`)
Exports raw 16-bit un-demosaiced Bayer CFA bitmap:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' bmp input.rvsp [frameIndex] [output.bmp]
```

### 7. Pixel Statistics (`stats`)
Prints dynamic range, clipping, and per-channel minimum/maximum/average levels:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' stats input.rvsp [frameIndex]
```

### 8. Synthetic Generator (`gen`)
Generates a 5-frame synthetic gradient RVSP clip for self-tests without requiring a physical camera device:

```powershell
powershell -NoProfile -File 'D:\RawRec\rvtool\rvtool.ps1' gen test.rvsp
```
