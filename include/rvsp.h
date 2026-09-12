#ifndef RVSP_H
#define RVSP_H

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <memory>
#include <array>
#include <algorithm>
#include <iostream>

#ifdef RVSP_ENABLE_ZSTD
#include <zstd.h>
#endif

namespace rvsp {

enum class CfaPattern : uint8_t {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

enum class Packing : uint8_t {
    EXPANDED_LSB = 0,
    MIPI_PACKED = 1,
    EXPANDED_MSB = 2
};

struct Header {
    uint16_t versionMajor = 0;
    uint16_t versionMinor = 1;
    uint16_t headerSize = 512;
    uint16_t flags = 0;
    std::string videoCodec = "STORE";
    std::string audioCodec = "NONE";
    uint32_t width = 0;
    uint32_t height = 0;
    uint8_t bitDepth = 10;
    CfaPattern cfa = CfaPattern::BGGR;
    Packing packing = Packing::MIPI_PACKED;
    uint32_t whiteLevel = 1023;
    std::array<int32_t, 4> blackLevel = {64, 64, 64, 64};
    std::array<float, 9> colorMatrix1 = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::array<float, 3> asShotNeutral = {1.0f, 1.0f, 1.0f};
    uint32_t nominalFpsMilli = 30000;
    uint64_t createdUnixUs = 0;
    std::string cameraModel;
    std::string lensId;
    uint64_t indexOffset = 0;
    uint32_t metaJsonSize = 0;
    std::string metaJson;

    double fps() const {
        return nominalFpsMilli > 0 ? (nominalFpsMilli / 1000.0) : 30.0;
    }

    const char* cfaName() const {
        switch (cfa) {
            case CfaPattern::RGGB: return "RGGB";
            case CfaPattern::GRBG: return "GRBG";
            case CfaPattern::GBRG: return "GBRG";
            case CfaPattern::BGGR: return "BGGR";
            default: return "UNKNOWN";
        }
    }
};

struct Record {
    char magic[4] = {0, 0, 0, 0};
    uint32_t payloadBytes = 0;
    uint64_t timestampNs = 0;
    uint64_t exposureNs = 0;
    uint32_t iso = 0;
    uint32_t sequence = 0;
    uint64_t dataOffset = 0;

    bool isVideo() const {
        return magic[0] == 'F' && magic[1] == 'R' && magic[2] == 'M';
    }

    bool isAudio() const {
        return magic[0] == 'A' && magic[1] == 'U' && magic[2] == 'D';
    }
};

class Reader {
public:
    Reader() = default;
    ~Reader() { close(); }

    bool open(const std::string& filepath) {
        close();
        fileStream_.open(filepath, std::ios::binary);
        if (!fileStream_.is_open()) return false;

        filePath_ = filepath;
        if (!readHeader()) {
            close();
            return false;
        }
        scanRecords();
        return true;
    }

    void close() {
        if (fileStream_.is_open()) {
            fileStream_.close();
        }
        frames_.clear();
        audioChunks_.clear();
        header_ = Header();
    }

    const Header& header() const { return header_; }
    const std::vector<Record>& frames() const { return frames_; }
    const std::vector<Record>& audioChunks() const { return audioChunks_; }

    bool readPayload(const Record& rec, std::vector<uint8_t>& out) {
        if (!fileStream_.is_open()) return false;
        out.resize(rec.payloadBytes);
        fileStream_.seekg(rec.dataOffset, std::ios::beg);
        fileStream_.read(reinterpret_cast<char*>(out.data()), rec.payloadBytes);
        return fileStream_.gcount() == static_cast<std::streamsize>(rec.payloadBytes);
    }

    /**
     * Unpacks 10-bit MIPI CSI-2 RAW10 packed buffer into contiguous 16-bit array.
     * Output must have at least (width * height) uint16_t elements.
     */
    static void unpackMipi10(const uint8_t* src, size_t srcLen, uint16_t* dst, uint32_t width, uint32_t height) {
        const size_t totalPixels = static_cast<size_t>(width) * height;
        size_t si = 0;
        size_t di = 0;

        while (di + 4 <= totalPixels && si + 5 <= srcLen) {
            const uint8_t b0 = src[si];
            const uint8_t b1 = src[si + 1];
            const uint8_t b2 = src[si + 2];
            const uint8_t b3 = src[si + 3];
            const uint8_t b4 = src[si + 4];

            dst[di]     = static_cast<uint16_t>((b0 << 2) | ((b4 >> 6) & 0x03));
            dst[di + 1] = static_cast<uint16_t>((b1 << 2) | ((b4 >> 4) & 0x03));
            dst[di + 2] = static_cast<uint16_t>((b2 << 2) | ((b4 >> 2) & 0x03));
            dst[di + 3] = static_cast<uint16_t>((b3 << 2) | (b4 & 0x03));

            si += 5;
            di += 4;
        }
    }

#ifdef RVSP_ENABLE_ZSTD
    /** Decompresses single-shot Zstd payload into out buffer. */
    static bool decompressZstd(const uint8_t* src, size_t srcLen, std::vector<uint8_t>& dst, size_t expectedBytes) {
        dst.resize(expectedBytes);
        size_t ret = ZSTD_decompress(dst.data(), dst.size(), src, srcLen);
        if (ZSTD_isError(ret)) return false;
        dst.resize(ret);
        return true;
    }
#endif

private:
    std::string filePath_;
    std::ifstream fileStream_;
    Header header_;
    std::vector<Record> frames_;
    std::vector<Record> audioChunks_;

    static uint16_t readU16LE(const uint8_t* p) {
        return static_cast<uint16_t>(p[0] | (p[1] << 8));
    }

    static uint32_t readU32LE(const uint8_t* p) {
        return static_cast<uint32_t>(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
    }

    static int32_t readI32LE(const uint8_t* p) {
        return static_cast<int32_t>(readU32LE(p));
    }

    static uint64_t readU64LE(const uint8_t* p) {
        uint64_t lo = readU32LE(p);
        uint64_t hi = readU32LE(p + 4);
        return lo | (hi << 32);
    }

    static float readF32LE(const uint8_t* p) {
        uint32_t u = readU32LE(p);
        float f;
        std::memcpy(&f, &u, sizeof(float));
        return f;
    }

    static std::string readFixedString(const uint8_t* p, size_t maxLen) {
        size_t len = 0;
        while (len < maxLen && p[len] != '\0') len++;
        return std::string(reinterpret_cast<const char*>(p), len);
    }

    bool readHeader() {
        std::vector<uint8_t> h(512);
        fileStream_.seekg(0, std::ios::beg);
        fileStream_.read(reinterpret_cast<char*>(h.data()), 512);
        if (fileStream_.gcount() < 512) return false;

        // Magic verification
        if (std::memcmp(h.data(), "RVSP", 4) != 0) return false;

        header_.versionMajor = readU16LE(h.data() + 4);
        header_.versionMinor = readU16LE(h.data() + 6);
        header_.headerSize = readU16LE(h.data() + 8);
        header_.flags = readU16LE(h.data() + 10);
        header_.videoCodec = readFixedString(h.data() + 12, 16);
        header_.audioCodec = readFixedString(h.data() + 28, 8);
        header_.width = readU32LE(h.data() + 36);
        header_.height = readU32LE(h.data() + 40);
        header_.bitDepth = h[44];
        header_.cfa = static_cast<CfaPattern>(h[45]);
        header_.packing = static_cast<Packing>(h[46]);
        header_.whiteLevel = readU32LE(h.data() + 48);

        for (int i = 0; i < 4; i++) {
            header_.blackLevel[i] = readI32LE(h.data() + 52 + i * 4);
        }
        for (int i = 0; i < 9; i++) {
            header_.colorMatrix1[i] = readF32LE(h.data() + 68 + i * 4);
        }
        for (int i = 0; i < 3; i++) {
            header_.asShotNeutral[i] = readF32LE(h.data() + 104 + i * 4);
        }
        header_.nominalFpsMilli = readU32LE(h.data() + 116);
        header_.createdUnixUs = readU64LE(h.data() + 120);
        header_.cameraModel = readFixedString(h.data() + 128, 64);
        header_.lensId = readFixedString(h.data() + 192, 32);
        header_.indexOffset = readU64LE(h.data() + 224);
        header_.metaJsonSize = readU32LE(h.data() + 232);

        // Read metaJson at byte offset 512 if present
        if (header_.metaJsonSize > 0 && header_.metaJsonSize < 1024 * 1024) {
            std::vector<char> meta(header_.metaJsonSize + 1, 0);
            fileStream_.seekg(512, std::ios::beg);
            fileStream_.read(meta.data(), header_.metaJsonSize);
            header_.metaJson = std::string(meta.data(), fileStream_.gcount());
        }

        return true;
    }

    void scanRecords() {
        frames_.clear();
        audioChunks_.clear();

        fileStream_.seekg(0, std::ios::end);
        const uint64_t fileSize = fileStream_.tellg();

        uint64_t cursor = 512 + header_.metaJsonSize;
        std::vector<uint8_t> recHdr(32);

        while (cursor + 32 <= fileSize) {
            fileStream_.seekg(cursor, std::ios::beg);
            fileStream_.read(reinterpret_cast<char*>(recHdr.data()), 32);
            if (fileStream_.gcount() < 32) break;

            Record r;
            std::memcpy(r.magic, recHdr.data(), 4);
            r.payloadBytes = readU32LE(recHdr.data() + 4);
            r.timestampNs = readU64LE(recHdr.data() + 8);
            r.exposureNs = readU64LE(recHdr.data() + 16);
            r.iso = readU32LE(recHdr.data() + 24);
            r.sequence = readU32LE(recHdr.data() + 28);
            r.dataOffset = cursor + 32;

            if (!r.isVideo() && !r.isAudio()) break;
            if (cursor + 32 + r.payloadBytes > fileSize) break;

            if (r.isVideo()) {
                frames_.push_back(r);
            } else if (r.isAudio()) {
                audioChunks_.push_back(r);
            }

            cursor += 32 + r.payloadBytes;
        }
    }
};

} // namespace rvsp

#endif // RVSP_H
