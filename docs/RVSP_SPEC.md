# RVSP Container Specification v1.0
**RawRec Video Sensor Payload (.rvsp)**
*Author: RawRec Project*
*Status: Formal Specification*
*Target Revision: 1.0 (Compatible with draft v0.1)*

---

## 1. Overview & Architecture

The **RawRec Video Sensor Payload (`.rvsp`)** format is a lightweight, append-only, crash-tolerant binary container engineered specifically for high-throughput mobile RAW camera sensor video recording (e.g. Android Camera2 `RAW_SENSOR` streams).

### Design Objectives
1. **Zero Recording Overhead**: Sequential, direct append streaming with zero seek operations during capture.
2. **Crash-Resilience & Torn-Tail Recovery**: The container is valid and recoverable at any point in time; abrupt battery disconnects, Android process terminations (LMK), or thermal halts do not corrupt prior recorded frames.
3. **Hardware ISP Bypass Preservation**: Encapsulates pristine 10-bit/12-bit/14-bit/16-bit Bayer pixel data directly from the camera sensor interface with exact sensor colorimetry, optical calibration, and timing metadata.
4. **NLE & CinemaDNG Interoperability**: Seamless 1:1 mathematical translation into single-IFD Adobe CinemaDNG 1.4 frame sequences with synchronized linear PCM audio.

### File Layout Diagram
```
+-----------------------------------------------------------------------+
| 512-Byte Fixed Binary Header (Magic: "RVSP", Geometry, Colorimetry)  |
+-----------------------------------------------------------------------+
| Variable-Length UTF-8 JSON Metadata Block (Offset 512..512+metaSize) |
+-----------------------------------------------------------------------+
| Record 0: 32-Byte Header ("FRM\0") + Compressed/Packed Payload       |
+-----------------------------------------------------------------------+
| Record 1: 32-Byte Header ("AUD\0") + Interleaved Linear PCM Chunk     |
+-----------------------------------------------------------------------+
| Record 2: 32-Byte Header ("FRM\0") + Compressed/Packed Payload       |
+-----------------------------------------------------------------------+
| ...                                                                   |
+-----------------------------------------------------------------------+
| Optional Index Trailer (Offset in Header.indexOffset)                 |
+-----------------------------------------------------------------------+
```

### Endianness Convention
All multi-byte numeric primitives (16-bit, 32-bit, 64-bit unsigned integers, signed integers, and IEEE 754 32-bit single-precision floating point numbers) are stored strictly in **Little-Endian (LE)** byte order.

---

## 2. Fixed Binary Header (512 Bytes)

The file begins with an immutable 512-byte header block starting at byte offset `0`.

| Offset | Size (Bytes) | Data Type | Field Name | Description / Valid Values |
|---|---|---|---|---|
| `0` | 4 | `char[4]` | `magic` | Container identifier magic bytes: ASCII `"RVSP"` (`0x52 0x56 0x53 0x50`). |
| `4` | 2 | `uint16` | `versionMajor` | Format major version number (currently `0`). |
| `6` | 2 | `uint16` | `versionMinor` | Format minor version number (currently `1`). |
| `8` | 2 | `uint16` | `headerSize` | Size of binary header in bytes (always `512`). |
| `10` | 2 | `uint16` | `flags` | Container flags bitfield:<br>• `bit 0`: `hasIndex` (`1` if index trailer exists at `indexOffset`, `0` otherwise). |
| `12` | 16 | `char[16]` | `videoCodec` | Video payload compression codec (NUL-terminated ASCII):<br>• `"STORE\0"`: Uncompressed packed raw Bayer samples.<br>• `"ZSTD\0"`: Zstandard compressed frame payloads.<br>• `"LZ4\0"`: LZ4 compressed frame payloads. |
| `28` | 8 | `char[8]` | `audioCodec` | Audio payload format (NUL-terminated ASCII):<br>• `"NONE\0"`: No audio tracks embedded.<br>• `"PCM16\0"`: 16-bit signed little-endian PCM.<br>• `"PCM24\0"`: 24-bit signed little-endian PCM. |
| `36` | 4 | `uint32` | `width` | Active sensor frame width in pixels (e.g. `4096`). |
| `40` | 4 | `uint32` | `height` | Active sensor frame height in pixels (e.g. `3072`). |
| `44` | 1 | `uint8` | `bitDepth` | Native sensor ADC bit depth (e.g. `10`, `12`, `14`, `16`). |
| `45` | 1 | `uint8` | `cfaPattern` | Color Filter Array Bayer pattern:<br>• `0`: `RGGB`<br>• `1`: `GRBG`<br>• `2`: `GBRG`<br>• `3`: `BGGR` |
| `46` | 1 | `uint8` | `packing` | Bit-packing layout format:<br>• `0`: `EXPANDED_LSB` (16-bit LE per sample, right-aligned)<br>• `1`: `MIPI_PACKED` (MIPI CSI-2 RAW10, 4 pixels in 5 bytes)<br>• `2`: `EXPANDED_MSB` (16-bit LE per sample, left-aligned)<br>• `3`: `MIPI_RAW12` (MIPI CSI-2 RAW12, 2 pixels in 3 bytes)<br>• `4`: `MIPI_RAW14` (MIPI CSI-2 RAW14, 4 pixels in 7 bytes) |
| `47` | 1 | `uint8` | `reserved` | Reserved byte (set to `0x00`). |
| `48` | 4 | `uint32` | `whiteLevel` | Digital sensor saturation / clipping point (e.g. `1023` for 10-bit). |
| `52` | 16 | `int32[4]` | `blackLevel` | Black level subtraction offsets in canonical **R, Gr, Gb, B** channel order (e.g. `[64, 64, 64, 64]`). |
| `68` | 36 | `float32[9]` | `colorMatrix1` | Row-major 3×3 color matrix mapping CIE 1931 XYZ illuminant D65 to sensor RGB (AOSP `ColorSpaceTransform` direct copy). |
| `104` | 12 | `float32[3]` | `asShotNeutral` | Normalized AsShotNeutral reciprocal white balance gains `[R, G, B]`. |
| `116` | 4 | `uint32` | `nominalFpsMilli` | Target recording frame rate in millihertz (`fps * 1000`, e.g. `30000` = 30.000 fps, `23976` = 23.976 fps). |
| `120` | 8 | `uint64` | `createdUnixUs` | Recording start epoch timestamp in microseconds. |
| `128` | 64 | `char[64]` | `cameraModel` | Camera device / manufacturer identifier (UTF-8, NUL-padded). |
| `192` | 32 | `char[32]` | `lensId` | Optical lens assembly identifier or physical camera ID. |
| `224` | 8 | `uint64` | `indexOffset` | Byte offset to optional container index trailer (`0` if absent). |
| `232` | 4 | `uint32` | `metaJsonSize` | Byte length of the UTF-8 `metaJson` block stored at file offset 512. |
| `236` | 276 | `uint8[276]` | `headerPadding` | Reserved zero-padding up to byte offset `512`. |

---

## 3. Metadata JSON Block (`metaJson`)

Directly following the fixed header at byte offset **`512`**, a UTF-8 encoded JSON payload of size `metaJsonSize` is stored.

### JSON Schema Keys

```json
{
  "cameraId": "0",
  "packedMipi10": true,
  "calibIlluminant1": 21,
  "timestampSource": 1,
  "totalDropped": 0,
  "droppedGaps": 0,
  "framesCaptured": 414,
  "framesWritten": 414,
  "audioSampleRate": 48000,
  "audioChannels": 2,
  "audioFormat": "pcm16",
  "framingAspect": 2.39,
  "cropRegion": [0, 680, 4096, 1712],
  "lensCalibration": [4096.0, 3072.0, 2048.0, 1536.0, 0.0],
  "lensDistortion": [0.012, -0.004, 0.001, 0.0, 0.0],
  "opticalBlack": [[0, 0, 4096, 16]],
  "lensShading": {
    "rows": 12,
    "cols": 16,
    "gains": [1.0, 1.02, "..."]
  }
}
```

#### Field Specifications:
* **`timestampSource`** (`integer`):
  * `0`: `UNKNOWN` (legacy monotonic clock; audio and video clocks cannot be assumed aligned).
  * `1`: `REALTIME` (camera sensor and audio recorder both sample `CLOCK_BOOTTIME` / `elapsedRealtimeNanos()`; timestamps are directly comparable).
* **`totalDropped`** (`integer`): Count of dropped video frames during the take.
* **`droppedGaps`** (`integer`): Count of discontinuous frame drop occurrences.
* **`framingAspect`** (`number`, optional): Selected aspect ratio (e.g. `2.39`, `1.7778`, `1.0`). If absent, full sensor was framed.
* **`cropRegion`** (`[left, top, width, height]`): WYSIWYG crop coordinates in sensor space.
* **`lensDistortion`** & **`lensCalibration`**: Radial/tangential distortion parameters translated into Adobe DNG `OpcodeList3` (`WarpRectilinear`).
* **`lensShading`**: Sensor lens falloff calibration grid translated into Adobe DNG `OpcodeList1` (`GainMap`).

---

## 4. Record Structure (Interleaved Audio & Video)

Following the `metaJson` block (at byte offset `512 + metaJsonSize`), all video frames and audio chunks are stored sequentially as 32-byte header records followed immediately by their raw payload data.

### 32-Byte Record Header
| Offset | Size (Bytes) | Data Type | Field Name | Description |
|---|---|---|---|---|
| `0` | 4 | `char[4]` | `magic` | Stream record identifier magic:<br>• `"FRM\0"` (`0x46 0x52 0x4D 0x00`): Video Frame.<br>• `"AUD\0"` (`0x41 0x55 0x44 0x00`): Audio PCM Chunk. |
| `4` | 4 | `uint32` | `payloadBytes` | Length of immediate payload in bytes. |
| `8` | 8 | `uint64` | `timestampNs` | Presentation timestamp in nanoseconds.<br>• Video: Sensor start of exposure.<br>• Audio: Buffer acquisition start. |
| `16` | 8 | `uint64` | `exposureNs` | Video exposure duration in nanoseconds (0 for audio). |
| `24` | 4 | `uint32` | `iso` | Sensor ISO sensitivity amplification (0 for audio). |
| `28` | 4 | `uint32` | `sequence` | Monotonically increasing sequence number per stream. |
| `32` | `payloadBytes` | `bytes` | `payload` | Frame or audio payload. |

---

## 5. Bit-Packing Layouts

### MIPI CSI-2 RAW10 (`PACKING_MIPI_PACKED = 1`)
Encodes four 10-bit pixel samples ($P_0, P_1, P_2, P_3$) into 5 consecutive bytes ($B_0, B_1, B_2, B_3, B_4$), yielding a 20% bandwidth reduction over 16-bit unpacked representation:

$$\begin{aligned}
B_0 &= P_0[9:2] \\
B_1 &= P_1[9:2] \\
B_2 &= P_2[9:2] \\
B_3 &= P_3[9:2] \\
B_4 &= (P_0[1:0] \ll 6) \mid (P_1[1:0] \ll 4) \mid (P_2[1:0] \ll 2) \mid (P_3[1:0])
\end{aligned}$$

#### Unpacking Equation:
$$\begin{aligned}
P_0 &= (B_0 \ll 2) \mid ((B_4 \gg 6) \ \& \ 0x03) \\
P_1 &= (B_1 \ll 2) \mid ((B_4 \gg 4) \ \& \ 0x03) \\
P_2 &= (B_2 \ll 2) \mid ((B_4 \gg 2) \ \& \ 0x03) \\
P_3 &= (B_3 \ll 2) \mid (B_4 \ \& \ 0x03)
\end{aligned}$$

*Total Frame Packed Bytes*: $\frac{\text{width} \times \text{height}}{4} \times 5$.

---

## 6. Compression Specifications

* **Single-Frame Block Scope**: When `videoCodec == "ZSTD"`, each video frame payload is an independent Zstandard frame compressed with single-shot block execution.
* **Order of Execution**: **Pack First, Compress Second**. The raw sensor frame is bit-packed via MIPI RAW10 *before* entering Zstandard compression.
* **Decompression Sizing**: Readers should probe uncompressed length via `ZSTD_getFrameContentSize()`. If unknown, calculate nominal unpacked size:
  $$\text{expectedBytes} = \begin{cases} \frac{\text{width} \times \text{height}}{4} \times 5 & \text{if packing} = 1 \\ \text{width} \times \text{height} \times 2 & \text{otherwise} \end{cases}$$

---

## 7. CinemaDNG 1.4 TIFF Tag Mapping

When exporting or transcode-streaming RVSP takes into Adobe CinemaDNG format, fields map into a single IFD0 TIFF structure:

| TIFF Tag ID | Tag Name | Source in RVSP | Format / Notes |
|---|---|---|---|
| `254` | `NewSubFileType` | `0` | Primary full-resolution image (single-IFD0). |
| `256` | `ImageWidth` | `Header.width` | Sensor active width. |
| `257` | `ImageLength` | `Header.height` | Sensor active height. |
| `258` | `BitsPerSample` | `16` | Unpacked sample depth. |
| `259` | `Compression` | `1` | Uncompressed sensor raw. |
| `262` | `PhotometricInterpretation` | `32803` | Color Filter Array (CFA). |
| `271` | `Make` | `Header.cameraModel` | First token of camera model (or "RawRec"). |
| `272` | `Model` | `Header.cameraModel` | Full camera model string. |
| `273` | `StripOffsets` | Calculated | Byte offset to uncompressed raw pixel strip. |
| `279` | `StripByteCounts` | `width * height * 2` | Total raw buffer length in bytes. |
| `33421` | `CFARepeatPatternDim` | `[2, 2]` | 2×2 Bayer repeat unit. |
| `33422` | `CFAPattern` | `Header.cfaPattern` | Transcoded to TIFF CFA byte pattern. |
| `33434` | `ExposureTime` | `RecordHeader.exposureNs` | Rational: `exposureNs / 1,000,000,000`. |
| `34855` | `PhotographicSensitivity` | `RecordHeader.iso` | Integer ISO value. |
| `50706` | `DNGVersion` | `[1, 4, 0, 0]` | Adobe DNG 1.4.0.0. |
| `50714` | `BlackLevel` | `Header.blackLevel` | Per-channel black levels mapped to CFA pattern. |
| `50717` | `WhiteLevel` | `Header.whiteLevel` | Saturation clipping ceiling (e.g. `1023`). |
| `50721` | `ColorMatrix1` | `Header.colorMatrix1` | 3×3 row-major XYZ to camera matrix (SRationals). |
| `50728` | `AsShotNeutral` | `Header.asShotNeutral` | 3 rationals `[R, G, B]`. |
| `50778` | `CalibrationIlluminant1` | `metaJson["calibIlluminant1"]` | Standard EXIF LightSource ID (21 = D65). |
| `50936` | `ProfileName` | Look Profile | Optional color look name (e.g. "Cinema Filmic"). |
| `50981` | `ProfileToneCurve` | Tone curve evaluation | 33-point spline curve (SRationals). |
| `51008` | `OpcodeList1` | `metaJson["lensShading"]` | Adobe DNG 1.4 GainMap lens falloff opcode. |
| `51022` | `OpcodeList3` | `metaJson["lensDistortion"]` | Adobe DNG 1.4 WarpRectilinear geometric opcode. |
| `51044` | `FrameRate` | `Header.nominalFpsMilli` | Signed Rational `nominalFpsMilli / 1000`. |

---

## 8. Crash Recovery & Resilience Rules

1. **Torn-Tail Scanning**: If recording halts abruptly (e.g. battery pulled, kernel panic), the container parser scans records sequentially from byte `512 + metaJsonSize`.
2. **Magic Validation**: When parsing a record:
   - Verify `magic == "FRM\0"` or `magic == "AUD\0"`.
   - Verify `payloadBytes > 0` and `offset + 32 + payloadBytes <= fileSize`.
   - If a record header is truncated, non-matching, or exceeds `fileSize`, the parser ceases scanning and marks the previous valid record as the definitive end of the take. All prior recorded audio and video frames remain 100% intact and valid.
