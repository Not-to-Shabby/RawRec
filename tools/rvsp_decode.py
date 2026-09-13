#!/usr/bin/env python3
"""
RVSP Reference Decoder (Python 3)
RawRec Video Sensor Payload (.rvsp) standalone decoder and inspector.

Usage:
    python rvsp_decode.py info <take.rvsp>
    python rvsp_decode.py extract-wav <take.rvsp> [out.wav]
    python rvsp_decode.py dump-frame <take.rvsp> [--frame=0] [--out=frame0.raw]
    python rvsp_decode.py list-frames <take.rvsp>
"""

import sys
import os
import struct
import json
import argparse
from typing import Dict, List, Tuple, Optional, Any

try:
    import numpy as np
    HAVE_NUMPY = True
except ImportError:
    HAVE_NUMPY = False

try:
    import zstandard as zstd
    HAVE_ZSTD = True
except ImportError:
    HAVE_ZSTD = False


class RvspHeader:
    def __init__(self, data: bytes):
        if len(data) < 512:
            raise ValueError(f"Header data too small: expected 512 bytes, got {len(data)}")
        
        magic, major, minor, hdr_size, flags = struct.unpack_from("<4sHHHH", data, 0)
        if magic != b"RVSP":
            raise ValueError(f"Invalid magic: expected b'RVSP', got {magic}")
        
        self.magic = magic.decode("ascii")
        self.version_major = major
        self.version_minor = minor
        self.header_size = hdr_size
        self.flags = flags

        self.video_codec = data[12:28].split(b"\x00")[0].decode("ascii", errors="ignore")
        self.audio_codec = data[28:36].split(b"\x00")[0].decode("ascii", errors="ignore")

        (
            self.width,
            self.height,
            self.bit_depth,
            self.cfa,
            self.packing,
            _,
            self.white_level,
        ) = struct.unpack_from("<IIBBBBI", data, 36)

        self.black_level = list(struct.unpack_from("<4i", data, 52))
        self.color_matrix1 = list(struct.unpack_from("<9f", data, 68))
        self.as_shot_neutral = list(struct.unpack_from("<3f", data, 104))
        self.nominal_fps_milli = struct.unpack_from("<I", data, 116)[0]
        self.created_unix_us = struct.unpack_from("<Q", data, 120)[0]

        self.camera_model = data[128:192].split(b"\x00")[0].decode("utf-8", errors="ignore")
        self.lens_id = data[192:224].split(b"\x00")[0].decode("utf-8", errors="ignore")
        self.index_offset, self.meta_json_size = struct.unpack_from("<QI", data, 224)
        self.meta_json: Dict[str, Any] = {}

    @property
    def fps(self) -> float:
        return self.nominal_fps_milli / 1000.0 if self.nominal_fps_milli > 0 else 30.0

    @property
    def cfa_name(self) -> str:
        names = {0: "RGGB", 1: "GRBG", 2: "GBRG", 3: "BGGR"}
        return names.get(self.cfa, "UNKNOWN")

    @property
    def packing_name(self) -> str:
        names = {0: "EXPANDED_LSB", 1: "MIPI_PACKED", 2: "EXPANDED_MSB", 3: "MIPI_RAW12", 4: "MIPI_RAW14"}
        return names.get(self.packing, f"CUSTOM_{self.packing}")


class RvspRecord:
    def __init__(self, magic: bytes, payload_bytes: int, ts_ns: int, exp_ns: int, iso: int, seq: int, data_offset: int):
        self.magic = magic
        self.payload_bytes = payload_bytes
        self.ts_ns = ts_ns
        self.exp_ns = exp_ns
        self.iso = iso
        self.sequence = seq
        self.data_offset = data_offset

    @property
    def is_video(self) -> bool:
        return self.magic.startswith(b"FRM")

    @property
    def is_audio(self) -> bool:
        return self.magic.startswith(b"AUD")


class RvspReader:
    def __init__(self, filepath: str):
        self.filepath = filepath
        self.file = open(filepath, "rb")
        self.header = self._read_header()
        self.frames: List[RvspRecord] = []
        self.audio_chunks: List[RvspRecord] = []
        self._scan_records()

    def close(self):
        if self.file and not self.file.closed:
            self.file.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def _read_header(self) -> RvspHeader:
        self.file.seek(0)
        hdr_bytes = self.file.read(512)
        header = RvspHeader(hdr_bytes)

        if header.meta_json_size > 0:
            self.file.seek(512)
            meta_bytes = self.file.read(header.meta_json_size)
            try:
                header.meta_json = json.loads(meta_bytes.decode("utf-8").strip())
            except Exception:
                header.meta_json = {}

        return header

    def _scan_records(self):
        self.file.seek(0, os.SEEK_END)
        file_size = self.file.tell()

        cursor = 512 + self.header.meta_json_size
        while cursor + 32 <= file_size:
            self.file.seek(cursor)
            rec_hdr = self.file.read(32)
            if len(rec_hdr) < 32:
                break

            magic, payload_len, ts_ns, exp_ns, iso, seq = struct.unpack_from("<4sIQQII", rec_hdr, 0)
            if not magic.startswith(b"FRM") and not magic.startswith(b"AUD"):
                break
            if cursor + 32 + payload_len > file_size:
                break

            rec = RvspRecord(magic, payload_len, ts_ns, exp_ns, iso, seq, cursor + 32)
            if rec.is_video:
                self.frames.append(rec)
            elif rec.is_audio:
                self.audio_chunks.append(rec)

            cursor += 32 + payload_len

    def read_payload(self, record: RvspRecord) -> bytes:
        self.file.seek(record.data_offset)
        return self.file.read(record.payload_bytes)

    def decode_frame(self, record: RvspRecord) -> bytes:
        raw_payload = self.read_payload(record)
        if self.header.video_codec == "ZSTD":
            if not HAVE_ZSTD:
                raise RuntimeError("zstandard module required for ZSTD decoding (pip install zstandard)")
            dctx = zstd.ZstdDecompressor()
            return dctx.decompress(raw_payload)
        return raw_payload


def unpack_mipi10(packed_bytes: bytes, width: int, height: int):
    """
    Unpacks MIPI CSI-2 RAW10 bit-packed byte array into uint16 samples.
    """
    total_pixels = width * height
    num_groups = (total_pixels + 3) // 4
    src_len = len(packed_bytes)

    if HAVE_NUMPY:
        # Fast vectorised NumPy unpacking
        usable_groups = min(num_groups, src_len // 5)
        arr = np.frombuffer(packed_bytes[:usable_groups * 5], dtype=np.uint8).reshape(-1, 5)

        b0 = arr[:, 0].astype(np.uint16)
        b1 = arr[:, 1].astype(np.uint16)
        b2 = arr[:, 2].astype(np.uint16)
        b3 = arr[:, 3].astype(np.uint16)
        b4 = arr[:, 4].astype(np.uint16)

        p0 = (b0 << 2) | ((b4 >> 6) & 0x03)
        p1 = (b1 << 2) | ((b4 >> 4) & 0x03)
        p2 = (b2 << 2) | ((b4 >> 2) & 0x03)
        p3 = (b3 << 2) | (b4 & 0x03)

        out = np.column_stack((p0, p1, p2, p3)).reshape(-1)
        if len(out) > total_pixels:
            out = out[:total_pixels]
        return out.reshape((height, width))
    else:
        # Pure Python fallback
        out = []
        si = 0
        while si + 5 <= src_len and len(out) < total_pixels:
            b0 = packed_bytes[si]
            b1 = packed_bytes[si + 1]
            b2 = packed_bytes[si + 2]
            b3 = packed_bytes[si + 3]
            b4 = packed_bytes[si + 4]

            out.append((b0 << 2) | ((b4 >> 6) & 0x03))
            out.append((b1 << 2) | ((b4 >> 4) & 0x03))
            out.append((b2 << 2) | ((b4 >> 2) & 0x03))
            out.append((b3 << 2) | (b4 & 0x03))
            si += 5
        return out[:total_pixels]


def unpack_mipi12(packed_bytes: bytes, width: int, height: int):
    """
    Unpacks MIPI CSI-2 RAW12 bit-packed byte array into uint16 samples (2 pixels -> 3 bytes).
    """
    total_pixels = width * height
    num_groups = (total_pixels + 1) // 2
    src_len = len(packed_bytes)

    if HAVE_NUMPY:
        usable_groups = min(num_groups, src_len // 3)
        arr = np.frombuffer(packed_bytes[:usable_groups * 3], dtype=np.uint8).reshape(-1, 3)
        b0 = arr[:, 0].astype(np.uint16)
        b1 = arr[:, 1].astype(np.uint16)
        b2 = arr[:, 2].astype(np.uint16)

        p0 = (b0 << 4) | (b2 & 0x0F)
        p1 = (b1 << 4) | ((b2 >> 4) & 0x0F)
        out = np.column_stack((p0, p1)).reshape(-1)
        if len(out) > total_pixels:
            out = out[:total_pixels]
        return out.reshape((height, width))
    else:
        out = []
        si = 0
        while si + 3 <= src_len and len(out) < total_pixels:
            b0 = packed_bytes[si]
            b1 = packed_bytes[si + 1]
            b2 = packed_bytes[si + 2]
            out.append((b0 << 4) | (b2 & 0x0F))
            out.append((b1 << 4) | ((b2 >> 4) & 0x0F))
            si += 3
        return out[:total_pixels]


def unpack_mipi14(packed_bytes: bytes, width: int, height: int):
    """
    Unpacks MIPI CSI-2 RAW14 bit-packed byte array into uint16 samples (4 pixels -> 7 bytes).
    """
    total_pixels = width * height
    num_groups = (total_pixels + 3) // 4
    src_len = len(packed_bytes)

    if HAVE_NUMPY:
        usable_groups = min(num_groups, src_len // 7)
        arr = np.frombuffer(packed_bytes[:usable_groups * 7], dtype=np.uint8).reshape(-1, 7)
        b0 = arr[:, 0].astype(np.uint16)
        b1 = arr[:, 1].astype(np.uint16)
        b2 = arr[:, 2].astype(np.uint16)
        b3 = arr[:, 3].astype(np.uint16)
        b4 = arr[:, 4].astype(np.uint16)
        b5 = arr[:, 5].astype(np.uint16)
        b6 = arr[:, 6].astype(np.uint16)

        p0 = (b0 << 6) | (b4 >> 2)
        p1 = (b1 << 6) | (((b4 & 0x03) << 4) | (b5 >> 4))
        p2 = (b2 << 6) | (((b5 & 0x0F) << 2) | (b6 >> 6))
        p3 = (b3 << 6) | (b6 & 0x3F)
        out = np.column_stack((p0, p1, p2, p3)).reshape(-1)
        if len(out) > total_pixels:
            out = out[:total_pixels]
        return out.reshape((height, width))
    else:
        out = []
        si = 0
        while si + 7 <= src_len and len(out) < total_pixels:
            b0 = packed_bytes[si]; b1 = packed_bytes[si + 1]; b2 = packed_bytes[si + 2]; b3 = packed_bytes[si + 3]
            b4 = packed_bytes[si + 4]; b5 = packed_bytes[si + 5]; b6 = packed_bytes[si + 6]
            out.append((b0 << 6) | (b4 >> 2))
            out.append((b1 << 6) | (((b4 & 0x03) << 4) | (b5 >> 4)))
            out.append((b2 << 6) | (((b5 & 0x0F) << 2) | (b6 >> 6)))
            out.append((b3 << 6) | (b6 & 0x3F))
            si += 7
        return out[:total_pixels]


def write_wav(out_path: str, audio_pcm: bytes, sample_rate: int = 48000, channels: int = 2, bits_per_sample: int = 16):
    total_bytes = len(audio_pcm)
    byte_rate = sample_rate * channels * bits_per_sample // 8
    block_align = channels * bits_per_sample // 8

    with open(out_path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", total_bytes + 36))
        f.write(b"WAVEfmt ")
        f.write(struct.pack("<IHHIIHH", 16, 1, channels, sample_rate, byte_rate, block_align, bits_per_sample))
        f.write(b"data")
        f.write(struct.pack("<I", total_bytes))
        f.write(audio_pcm)


def cmd_info(args):
    with RvspReader(args.file) as reader:
        h = reader.header
        print(f"Container   : {h.magic} v{h.version_major}.{h.version_minor}")
        print(f"Camera      : {h.camera_model}")
        print(f"Geometry    : {h.width}x{h.height} bitDepth={h.bit_depth} cfa={h.cfa} ({h.cfa_name})")
        print(f"Packing     : {h.packing} ({h.packing_name})")
        print(f"Codecs      : video={h.video_codec} audio={h.audio_codec}")
        print(f"Levels      : white={h.white_level} black={h.black_level}")
        print(f"Nominal FPS : {h.fps:.2f}")
        print(f"Frames      : {len(reader.frames)} video frames, {len(reader.audio_chunks)} audio chunks")

        if h.meta_json:
            print("\nMetadata:")
            for k, v in h.meta_json.items():
                print(f"  {k}: {v}")


def cmd_extract_wav(args):
    with RvspReader(args.file) as reader:
        if not reader.audio_chunks:
            print("No audio chunks found in file.")
            return

        pcm_data = bytearray()
        for chunk in reader.audio_chunks:
            pcm_data.extend(reader.read_payload(chunk))

        out_path = args.out or os.path.splitext(args.file)[0] + ".wav"
        rate = reader.header.meta_json.get("audioSampleRate", 48000)
        ch = reader.header.meta_json.get("audioChannels", 2)
        write_wav(out_path, bytes(pcm_data), sample_rate=rate, channels=ch, bits_per_sample=16)
        print(f"Extracted {len(reader.audio_chunks)} audio chunks ({len(pcm_data)} bytes) -> {out_path}")


def cmd_dump_frame(args):
    with RvspReader(args.file) as reader:
        if not reader.frames:
            print("No video frames found in file.")
            return

        idx = min(max(0, args.frame), len(reader.frames) - 1)
        rec = reader.frames[idx]
        decoded = reader.decode_frame(rec)

        h = reader.header
        if h.packing == 1:
            samples = unpack_mipi10(decoded, h.width, h.height)
            out_path = args.out or f"frame_{idx:06d}.raw"
            with open(out_path, "wb") as f:
                if HAVE_NUMPY and isinstance(samples, np.ndarray):
                    f.write(samples.astype(np.uint16).tobytes())
                else:
                    f.write(struct.pack(f"<{len(samples)}H", *samples))
            print(f"Dumped frame {idx} ({h.width}x{h.height} 10-bit MIPI unpacked raw) -> {out_path}")
        elif h.packing == 3:
            samples = unpack_mipi12(decoded, h.width, h.height)
            out_path = args.out or f"frame_{idx:06d}.raw"
            with open(out_path, "wb") as f:
                if HAVE_NUMPY and isinstance(samples, np.ndarray):
                    f.write(samples.astype(np.uint16).tobytes())
                else:
                    f.write(struct.pack(f"<{len(samples)}H", *samples))
            print(f"Dumped frame {idx} ({h.width}x{h.height} 12-bit MIPI unpacked raw) -> {out_path}")
        elif h.packing == 4:
            samples = unpack_mipi14(decoded, h.width, h.height)
            out_path = args.out or f"frame_{idx:06d}.raw"
            with open(out_path, "wb") as f:
                if HAVE_NUMPY and isinstance(samples, np.ndarray):
                    f.write(samples.astype(np.uint16).tobytes())
                else:
                    f.write(struct.pack(f"<{len(samples)}H", *samples))
            print(f"Dumped frame {idx} ({h.width}x{h.height} 14-bit MIPI unpacked raw) -> {out_path}")
        else:
            out_path = args.out or f"frame_{idx:06d}.bin"
            with open(out_path, "wb") as f:
                f.write(decoded)
            print(f"Dumped frame {idx} raw payload -> {out_path}")


def cmd_list_frames(args):
    with RvspReader(args.file) as reader:
        print(f"{'Index':>6} {'Seq':>6} {'Timestamp (ns)':>18} {'Exposure (ms)':>14} {'ISO':>6} {'Size':>10}")
        print("-" * 65)
        for i, fr in enumerate(reader.frames):
            exp_ms = fr.exp_ns / 1e6
            print(f"{i:6d} {fr.sequence:6d} {fr.ts_ns:18d} {exp_ms:14.2f} {fr.iso:6d} {fr.payload_bytes:10d}")


def main():
    parser = argparse.ArgumentParser(description="RVSP Reference Decoder")
    subparsers = parser.add_subparsers(dest="command", required=True)

    p_info = subparsers.add_parser("info", help="Display container header and telemetry info")
    p_info.add_argument("file", help="Path to .rvsp file")
    p_info.set_defaults(func=cmd_info)

    p_wav = subparsers.add_parser("extract-wav", help="Extract audio track to RIFF WAV")
    p_wav.add_argument("file", help="Path to .rvsp file")
    p_wav.add_argument("out", nargs="?", default=None, help="Output WAV path")
    p_wav.add_argument("--out-path", "-o", dest="out_flag", help="Output WAV path (flag alternative)")
    p_wav.set_defaults(func=lambda a: cmd_extract_wav(argparse.Namespace(file=a.file, out=a.out or a.out_flag)))

    p_dump = subparsers.add_parser("dump-frame", help="Dump single raw frame to file")
    p_dump.add_argument("file", help="Path to .rvsp file")
    p_dump.add_argument("--frame", "-f", type=int, default=0, help="Frame index (0-based)")
    p_dump.add_argument("--out", "-o", help="Output file path")
    p_dump.set_defaults(func=cmd_dump_frame)

    p_list = subparsers.add_parser("list-frames", help="List all frame headers and timestamps")
    p_list.add_argument("file", help="Path to .rvsp file")
    p_list.set_defaults(func=cmd_list_frames)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
