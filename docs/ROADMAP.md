# RawRec: Product Roadmap & Strategic Positioning

This document outlines the product positioning, competitor differentiation, and scheduled development phases for RawRec.

---

## 1. Executive Summary & Market Positioning

RawRec provides an open-source, hardware-direct cinema recording pipeline for Android. Most camera applications rely on the mobile operating system ISP, baking noise reduction, gamut compression, and artificial edge-sharpening into the image. RawRec bypasses the ISP entirely by streaming untouched 10-bit Bayer data directly from Qualcomm IFE-RDI DMA channels into an open-specification container.

```
+-----------------------------------------------------------------------------------+
| RAWREC RELEASE & ROADMAP TIMELINE                                                 |
+-----------------------------------------------------------------------------------+
| Phase 10: Release Hardening & CI/CD               [Weeks 1-2]                     |
| Phase 11: In-App Cinema Player & Direct Export    [Weeks 3-4]                     |
| Phase 12: Real-time GPU 3D LUT Viewfinder         [Weeks 5-6]                     |
| Phase 13: Expanded Sensor & Multi-Camera Matrix   [Weeks 7-8]                     |
| Phase 14: DaVinci Resolve Native OFX Plugin       [Weeks 9-10]                    |
+-----------------------------------------------------------------------------------+
```

---

## 2. Competitor Landscape & Differentiation

| Competitor | Core Claim | Unique Mechanism | Soph. Level | Gap or Weakness | How RawRec Differs |
|---|---|---|---|---|---|
| **Blackmagic Camera** | Cinema controls on mobile | Blackmagic Cloud integration and UI skin | 3 | Trapped in Qualcomm ISP. No raw sensor recording on Android. Device-restricted. | RawRec captures unmediated RAW_SENSOR Bayer buffers directly on open hardware. |
| **MotionCam Pro** | True raw video on phone | Proprietary memory ring-buffer and mcraw format | 4 | Closed-source format. Expensive paid licenses. Severe thermal throttling on long takes. | RawRec uses open RVSP containers, zero-allocation buffer pools, and free Apache 2.0 code. |
| **mcpro24fps** | Advanced manual video capture | Software log curve transforms over MediaCodec | 2 | Still standard compressed video with permanent ISP noise reduction. | RawRec bypasses the ISP completely. Calibration metadata passes directly to CinemaDNG. |
| **RawRec** | **Pure raw sensor cinema** | **Hardware ISP bypass + 2-way NEON packing + RVSP open container** | **4** | **Requires extraction step for final edit.** | **Zero ISP alteration, zero dropped frames, fully open-source and transparent.** |

### Strategic Competitor Analysis

**Blackmagic Camera** delivers clean broadcast ergonomics but processes frames through standard Android MediaCodec encoders. When an operator pulls footage into DaVinci Resolve, white balance, tone mapping, and sensor noise suppression are already permanently baked into the compressed 8-bit or 10-bit stream.

**MotionCam Pro** demonstrated raw capture on mobile hardware, but locks users into a closed, proprietary container format. The application suffers from high CPU overhead and thermal throttling during long takes.

**mcpro24fps** offers comprehensive manual dials, but records compressed H.264 or HEVC video. Detail smearing and edge-ringing introduced by the device hardware ISP cannot be undone in post-production.

---

## 3. Core Value Propositions

### VP 1: The Pure Sensor Pipeline
Stop letting your smartphone's image processor ruin your footage with artificial sharpening and aggressive noise reduction. RawRec taps the Camera2 RAW_SENSOR stream straight from the hardware sensor, bypassing Qualcomm ISP processing entirely. Every frame is packed via 2-way sliced NEON vector code and compressed losslessly with Zstandard, delivering sustained 30 FPS recording with zero dropped frames. Your phone now outputs authentic 10-bit raw cinema frames ready for DaVinci Resolve.

### VP 2: Open Container Architecture
Your footage belongs to you, not a proprietary application ecosystem. RawRec writes into the open RVSP container, featuring a 512-byte header, frame-accurate SMPTE timecodes, and uncompressed 48 kHz PCM audio. Use our lightweight desktop studio deck for instant scrubbing and scopes, or integrate our single-header C++17 library and Python 3 decoders directly into your automated studio ingest pipeline. No subscriptions, no cloud accounts, and no closed formats.

### VP 3: Engineered for Zero Frame Drops
Most raw recording experiments fail because mobile operating systems choke on transient memory allocations and thermal buildup. RawRec is architected around a zero-allocation buffer pool that circulates 15 pre-allocated buffers between capture, packing, and compression threads, completely eliminating garbage collection freezes. Paired with active thermal monitoring and ADPF scheduling, RawRec sustains long-duration takes with guaranteed frame retention.

### VP 4: True Mobile Cinema in the Public Domain
Professional filmmaking tools should not be gated behind recurring subscriptions or device-specific commercial exclusivity. RawRec is 100% open-source under the Apache License 2.0. From the low-level NEON row-packer to the desktop studio deck and clean-room color science engine, every line of code is open for inspection, optimization, and community ownership.

---

## 4. Ideal Customer Profile & Avatar

| Category | Emotional Detail | Practical and Logical Detail |
|---|---|---|
| **Hell (Without RawRec)** | - Fear of discovering dropped frames in editing.<br>- Frustration at plastic skin textures that cannot be corrected.<br>- Exhaustion from paying monthly app subscriptions. | - 8-bit log footage banding when graded in DaVinci Resolve.<br>- App crashing after 40 seconds of recording.<br>- Software locking files into proprietary container formats. |
| **Heaven (With RawRec)** | - Pride when matching mobile footage seamlessly with cinema camera footage.<br>- Total confidence while rolling on important takes.<br>- Relief of working within a transparent open-source toolchain. | - Clean 10-bit Bayer CinemaDNG sequences with authentic film grain.<br>- Sustained 30 FPS recording with 0 dropped frames.<br>- Direct desktop playback and batch conversion with zero subscription costs. |

---

## 5. Engineering Roadmap & Deliverables

### Phase 10: Release Distribution & Build Hardening (Weeks 1-2)
- Configure ProGuard and R8 keep rules to protect JNI entrypoints and native codec interfaces.
- Validate 16 KB page-alignment across all native library LOAD segments (`librawrec.so`).
- Deploy GitHub Actions CI/CD to run JVM unit tests, build release APKs, and verify reproducible artifacts.
- Package GitHub Release v1.0.0 with signed APKs and desktop tools.

### Phase 11: In-App Cinema Review & Storage Ergonomics (Weeks 3-4)
- Integrate an on-device Cinema Review Player inside the application utilizing software MJPEG and PCM decoders.
- Build a background CinemaDNG sequence export queue inside Android using the Storage Access Framework.
- Add external USB-C SSD recording target detection for uninterrupted high-capacity capture.

### Phase 12: Real-Time GPU 3D LUT Viewfinder & Hardware Scopes (Weeks 5-6)
- Transition viewfinder rendering to an OpenGL ES 3.0 / Vulkan surface pipeline.
- Implement real-time `.cube` 3D LUT application in the preview shader without altering raw Bayer capture buffers.
- Move focus peaking, false color, and zebra calculation from CPU bitmap loops to GPU fragment shaders.

### Phase 13: Multi-Sensor Discovery & 12-Bit Architecture (Weeks 7-8)
- Expand SIMD NEON packing vectors to support 12-bit and 14-bit Bayer formats for 1-inch and ultra-high-resolution sensors.
- Enable fast-switching between Ultra-Wide, Main, and Telephoto raw-capable sensors.
- Build automated calibration probing for per-camera color matrix and black level compensation.

### Phase 14: DaVinci Resolve Native Plugin & Ecosystem (Weeks 9-10)
- Develop a native DaVinci Resolve OpenFX / input plugin to read `.rvsp` files directly on the edit timeline.
- Implement multi-threaded C++ decoding with GPU debayering acceleration.
- Publish Python automation scripts for shot logging, batch proxy generation, and metadata synchronization.
