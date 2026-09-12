# RVSP container draft spec v0.1

Single-file, append-only, crash-tolerant RAW video container.
All integers little-endian. All offsets relative to file start unless noted.

## File layout
```
[Header]              fixed size 512 bytes, magic "RVSP"
[FrameRecord]...      interleaved video frames and audio chunks
[Index]               optional trailer, offset recorded in Header.indexOffset
```

## Header (512 bytes)
| off | type       | field             | notes |
|-----|------------|-------------------|-------|
| 0   | char[4]    | magic             | "RVSP" |
| 4   | u16        | versionMajor      | 0 |
| 6   | u16        | versionMinor      | 1 |
| 8   | u16        | headerSize        | 512 |
| 10  | u16        | flags             | bit0: hasIndex |
| 12  | char[16]   | codecVideo        | "STORE" / "LZ4" / "ZSTD" |
| 28  | char[8]    | codecAudio        | "NONE" / "PCM16" / "PCM24" |
| 36  | u32        | width             | sensor active width |
| 40  | u32        | height            | sensor active height |
| 44  | u8         | bitDepth          | 10/12/16 |
| 45  | u8         | cfaPattern        | 0 RGGB 1 GRBG 2 GBRG 3 BGGR |
| 46  | u8         | packing           | 0 EXPANDED_LSB 1 MIPI_PACKED 2 EXPANDED_MSB |
| 47  | u8         | reserved          | |
| 48  | u32        | whiteLevel        | |
| 52  | i32[4]     | blackLevel        | R Gr Gb B |
| 68  | f32[9]     | colorMatrix1      | row-major XYZ->cam |
| 104 | f32[3]     | asShotNeutral     | RGB gains |
| 116 | u32        | nominalFpsMilli   | fps * 1000 |
| 120 | u64        | createdUnixUs     | |
| 128 | char[64]   | cameraModel       | Build.MODEL utf-8 NUL-padded |
| 192 | char[32]   | lensId            | |
| 224 | u64        | indexOffset       | 0 if absent |
| 232 | u32        | metaJsonSize      | extra JSON blob stored at offset headerSize |
| 236 | -          | reserved          | zeros up to headerSize |
| ... | bytes      | metaJson          | begins exactly at offset headerSize (=512) |

## FrameRecord
| off | type  | field          |
|-----|-------|----------------|
| 0   | char[4]| magic "FRM\0" |
| 4   | u32   | payloadBytes   |
| 8   | u64   | timestampNs    | sensor monotonic
| 16  | u64   | exposureNs     | 0 = unknown
| 24  | u32   | iso            | 0 = unknown
| 28  | u32   | sequence       | monotonic per stream
| 32  | bytes | payload        |

## AudioChunkRecord
Same shape, magic "AUD\0"; payload = signed LE interleaved PCM.
v0.1 writer convention: 48000 Hz, 2 channels (stereo), 16-bit ("pcm16").
Authoritative values live in metaJson keys `audioSampleRate`, `audioChannels`,
`audioFormat`; absence of these keys means no audio was recorded.

## Recovery rule
A reader scans records sequentially by magic+size fields; any torn tail write is
truncated at the last fully valid record. Index is optional acceleration only.

## Codec notes
- STORE: payload = packed/expanded raw rows exactly as declared by packing field.
- ZSTD/LZ4: whole-frame single-shot compression of the raw payload declared by the
  packing field (compress AFTER any packing transform). No dictionary; decoder MUST
  use ZSTD_getFrameContentSize / known frame size from header geometry for output sizing.

## Packing layouts
- PACKING_EXPANDED_LSB (0): one u16 LE sample per pixel, values right-aligned in [0, 2^bitDepth).
- PACKING_MIPI_PACKED (1): MIPI CSI-2 RAW10 groups — 4 pixels P0..P3 into 5 bytes:
  `b0=P0[9:2] b1=P1[9:2] b2=P2[9:2] b3=P3[9:2] b4=P0[1:0]<<6|P1[1:0]<<4|P2[1:0]<<2|P3[1:0]`.
  Trailing partial group (pixelCount % 4 != 0) is zero-padded to full group size.
- PACKING_EXPANDED_MSB (2): reserved (OEM expanded variant), not produced by v0 writer.
