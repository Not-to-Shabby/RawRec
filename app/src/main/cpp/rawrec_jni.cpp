#include <jni.h>
#include <zstd.h>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <vector>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <functional>
#include <chrono>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/resource.h>
#include <sched.h>
#include <fcntl.h>
#include <unistd.h>
#endif

// ---------------------------------------------------------------------------
// Background slice worker for 2-way parallel row packing
// ---------------------------------------------------------------------------
struct SliceWorker {
    std::thread th;
    std::mutex mtx;
    std::condition_variable cv_start;
    std::condition_variable cv_done;
    std::function<void()> task;
    bool running = true;
    bool has_work = false;
    bool finished = false;

    SliceWorker() {
        th = std::thread([this]() {
#ifdef __ANDROID__
            setpriority(PRIO_PROCESS, 0, -8);
#endif
            while (true) {
                std::unique_lock<std::mutex> lk(mtx);
                cv_start.wait(lk, [this]() { return has_work || !running; });
                if (!running) break;
                if (task) task();
                has_work = false;
                finished = true;
                lk.unlock();
                cv_done.notify_one();
            }
        });
    }

    ~SliceWorker() {
        {
            std::lock_guard<std::mutex> lk(mtx);
            running = false;
        }
        cv_start.notify_one();
        if (th.joinable()) th.join();
    }

    void dispatch(std::function<void()> fn) {
        {
            std::lock_guard<std::mutex> lk(mtx);
            task = std::move(fn);
            has_work = true;
            finished = false;
        }
        cv_start.notify_one();
    }

    void wait() {
        std::unique_lock<std::mutex> lk(mtx);
        cv_done.wait(lk, [this]() { return finished; });
    }
};

static SliceWorker g_sliceWorker;

// ---------------------------------------------------------------------------
// MIPI RAW10 packing kernel — shared by every entry point.
//
// pixelStride==2 means u16 samples are contiguous, so a row is a u16 array.
// The NEON path processes 16 samples (4 MIPI groups -> 20 bytes) per
// iteration; the scalar path (also the JVM-unit-test and non-NEON fallback)
// processes one 4-sample group. Bit layout per group: bytes 0..3 = the four
// 10-bit samples >>2 (top 8 bits); byte 4 = the four 2-bit remainders
// packed as p0<<6 | p1<<4 | p2<<2 | p3.
// ---------------------------------------------------------------------------
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#define RAWREC_HAVE_NEON 1
#include <arm_neon.h>
#endif

static inline void pack_row_scalar(
        const uint8_t* row, int pixelStride, int groups, unsigned char* dst) {
    for (int gx = 0; gx < groups; ++gx) {
        const int x = gx * 4;
        auto px = [&](int k) -> int {
            const int xx = x + k;
            const uint8_t* p = row + static_cast<size_t>(xx) * pixelStride;
            return p[0] | (p[1] << 8);
        };
        const int p0 = px(0) & 1023, p1 = px(1) & 1023;
        const int p2 = px(2) & 1023, p3 = px(3) & 1023;
        dst[0] = static_cast<unsigned char>(p0 >> 2);
        dst[1] = static_cast<unsigned char>(p1 >> 2);
        dst[2] = static_cast<unsigned char>(p2 >> 2);
        dst[3] = static_cast<unsigned char>(p3 >> 2);
        dst[4] = static_cast<unsigned char>(
                ((p0 & 3) << 6) | ((p1 & 3) << 4) | ((p2 & 3) << 2) | (p3 & 3));
        dst += 5;
    }
}

#if RAWREC_HAVE_NEON
static inline void pack8_neon(const uint16_t* src, unsigned char* dst) {
    const uint16x8_t s = vld1q_u16(src);
    const uint8x8_t hi = vshrn_n_u16(s, 2);

    // Pure vector 2-bit remainder reduction: no scalar register spills
    const uint16x8_t lo = vandq_u16(s, vdupq_n_u16(3));
    const uint16_t wData[8] = {64, 16, 4, 1, 64, 16, 4, 1};
    const uint16x8_t weights = vld1q_u16(wData);
    const uint16x8_t prod = vmulq_u16(lo, weights);
    const uint16x4_t sum2 = vpadd_u16(vget_low_u16(prod), vget_high_u16(prod));
    const uint16x4_t sum4 = vpadd_u16(sum2, sum2);

    uint32_t hi0 = vget_lane_u32(vreinterpret_u32_u8(hi), 0);
    memcpy(dst, &hi0, 4);
    dst[4] = static_cast<unsigned char>(vget_lane_u16(sum4, 0));

    uint32_t hi1 = vget_lane_u32(vreinterpret_u32_u8(hi), 1);
    memcpy(dst + 5, &hi1, 4);
    dst[9] = static_cast<unsigned char>(vget_lane_u16(sum4, 1));
}

static inline void pack16_neon(const uint16_t* src, unsigned char* dst) {
    pack8_neon(src, dst);
    pack8_neon(src + 8, dst + 10);
}
#endif

/**
 * Packs one row of samples into dst. Requires pixelStride==2 for the NEON
 * path; anything else takes the scalar path (which handles any stride).
 */
static inline void pack_row(
        const uint8_t* row, int pixelStride, int width, unsigned char* dst) {
    const int groups = (width + 3) / 4;
#if RAWREC_HAVE_NEON
    if (pixelStride == 2) {
        const uint16_t* src = reinterpret_cast<const uint16_t*>(row);
        int x = 0;
        // 16-sample vector steps (4 groups -> 20 bytes).
        for (; x + 16 <= width; x += 16, dst += 20) {
            __builtin_prefetch(src + x + 64, 0, 3);
            pack16_neon(src + x, dst);
        }
        // Scalar tail (a 4096-wide row = exactly 256 steps, no tail; crops
        // with width%16 != 0 end here).
        for (; x < width; x += 4, dst += 5) {
            const uint16_t* s = src + x;
            const int p0 = s[0] & 1023, p1 = s[1] & 1023;
            const int p2 = s[2] & 1023, p3 = s[3] & 1023;
            dst[0] = static_cast<unsigned char>(p0 >> 2);
            dst[1] = static_cast<unsigned char>(p1 >> 2);
            dst[2] = static_cast<unsigned char>(p2 >> 2);
            dst[3] = static_cast<unsigned char>(p3 >> 2);
            dst[4] = static_cast<unsigned char>(
                    ((p0 & 3) << 6) | ((p1 & 3) << 4) | ((p2 & 3) << 2) | (p3 & 3));
        }
        return;
    }
#endif
    pack_row_scalar(row, pixelStride, groups, dst);
}

// ---------------------------------------------------------------------------
// MIPI RAW12 packing kernel — 2 pixels (12-bit) -> 3 bytes
// p0: dst[0] = p0>>4, dst[1] = p1>>4, dst[2] = ((p1&0xF)<<4) | (p0&0xF)
// ---------------------------------------------------------------------------
static inline void pack_row_raw12_scalar(
        const uint8_t* row, int pixelStride, int groups, unsigned char* dst) {
    for (int gx = 0; gx < groups; ++gx) {
        const int x = gx * 2;
        auto px = [&](int k) -> int {
            const int xx = x + k;
            const uint8_t* p = row + static_cast<size_t>(xx) * pixelStride;
            return p[0] | (p[1] << 8);
        };
        const int p0 = px(0) & 4095;
        const int p1 = px(1) & 4095;
        dst[0] = static_cast<unsigned char>(p0 >> 4);
        dst[1] = static_cast<unsigned char>(p1 >> 4);
        dst[2] = static_cast<unsigned char>(((p1 & 0xF) << 4) | (p0 & 0xF));
        dst += 3;
    }
}

#if RAWREC_HAVE_NEON
static inline void pack8_neon_raw12(const uint16_t* src, unsigned char* dst) {
    const uint16x8_t s = vld1q_u16(src);
    const uint8x8_t hi = vshrn_n_u16(s, 4);
    const uint16x8_t lo = vandq_u16(s, vdupq_n_u16(0x0F));
    const uint16_t wData[8] = {1, 16, 1, 16, 1, 16, 1, 16};
    const uint16x8_t weights = vld1q_u16(wData);
    const uint16x8_t prod = vmulq_u16(lo, weights);
    const uint16x4_t sum2 = vpadd_u16(vget_low_u16(prod), vget_high_u16(prod));

    const uint8_t hiArr[8] = {
        vget_lane_u8(hi, 0), vget_lane_u8(hi, 1),
        vget_lane_u8(hi, 2), vget_lane_u8(hi, 3),
        vget_lane_u8(hi, 4), vget_lane_u8(hi, 5),
        vget_lane_u8(hi, 6), vget_lane_u8(hi, 7)
    };
    dst[0] = hiArr[0]; dst[1] = hiArr[1]; dst[2] = static_cast<unsigned char>(vget_lane_u16(sum2, 0));
    dst[3] = hiArr[2]; dst[4] = hiArr[3]; dst[5] = static_cast<unsigned char>(vget_lane_u16(sum2, 1));
    dst[6] = hiArr[4]; dst[7] = hiArr[5]; dst[8] = static_cast<unsigned char>(vget_lane_u16(sum2, 2));
    dst[9] = hiArr[6]; dst[10] = hiArr[7]; dst[11] = static_cast<unsigned char>(vget_lane_u16(sum2, 3));
}

static inline void pack16_neon_raw12(const uint16_t* src, unsigned char* dst) {
    pack8_neon_raw12(src, dst);
    pack8_neon_raw12(src + 8, dst + 12);
}
#endif

static inline void pack_row_raw12(
        const uint8_t* row, int pixelStride, int width, unsigned char* dst) {
    const int groups = (width + 1) / 2;
#if RAWREC_HAVE_NEON
    if (pixelStride == 2) {
        const uint16_t* src = reinterpret_cast<const uint16_t*>(row);
        int x = 0;
        for (; x + 16 <= width; x += 16, dst += 24) {
            __builtin_prefetch(src + x + 64, 0, 3);
            pack16_neon_raw12(src + x, dst);
        }
        for (; x < width; x += 2, dst += 3) {
            const uint16_t* s = src + x;
            const int p0 = s[0] & 4095;
            const int p1 = (x + 1 < width) ? (s[1] & 4095) : 0;
            dst[0] = static_cast<unsigned char>(p0 >> 4);
            dst[1] = static_cast<unsigned char>(p1 >> 4);
            dst[2] = static_cast<unsigned char>(((p1 & 0xF) << 4) | (p0 & 0xF));
        }
        return;
    }
#endif
    pack_row_raw12_scalar(row, pixelStride, groups, dst);
}

// ---------------------------------------------------------------------------
// MIPI RAW14 packing kernel — 4 pixels (14-bit) -> 7 bytes
// ---------------------------------------------------------------------------
static inline void pack_row_raw14_scalar(
        const uint8_t* row, int pixelStride, int groups, unsigned char* dst) {
    for (int gx = 0; gx < groups; ++gx) {
        const int x = gx * 4;
        auto px = [&](int k) -> int {
            const int xx = x + k;
            const uint8_t* p = row + static_cast<size_t>(xx) * pixelStride;
            return p[0] | (p[1] << 8);
        };
        const int p0 = px(0) & 16383;
        const int p1 = px(1) & 16383;
        const int p2 = px(2) & 16383;
        const int p3 = px(3) & 16383;
        dst[0] = static_cast<unsigned char>(p0 >> 6);
        dst[1] = static_cast<unsigned char>(p1 >> 6);
        dst[2] = static_cast<unsigned char>(p2 >> 6);
        dst[3] = static_cast<unsigned char>(p3 >> 6);
        dst[4] = static_cast<unsigned char>(((p0 & 0x3F) << 2) | ((p1 & 0x30) >> 4));
        dst[5] = static_cast<unsigned char>(((p1 & 0x0F) << 4) | ((p2 & 0x3C) >> 2));
        dst[6] = static_cast<unsigned char>(((p2 & 0x03) << 6) | (p3 & 0x3F));
        dst += 7;
    }
}

static inline void pack_row_raw14(
        const uint8_t* row, int pixelStride, int width, unsigned char* dst) {
    const int groups = (width + 3) / 4;
    pack_row_raw14_scalar(row, pixelStride, groups, dst);
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10(
        JNIEnv* env, jclass, jbyteArray src,
        jint rowStride, jint pixelStride, jint width, jint height) {
    jbyte* data = env->GetByteArrayElements(src, nullptr);
    if (!data) return nullptr;
    const int groups = (width + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * height;
    std::vector<unsigned char> out(outLen);

    for (int y = 0; y < height; ++y) {
        const uint8_t* row = reinterpret_cast<const uint8_t*>(data) +
                             static_cast<size_t>(y) * rowStride;
        unsigned char* dst = out.data() + static_cast<size_t>(y) * groups * 5;
        pack_row(row, pixelStride, width, dst);
    }

    env->ReleaseByteArrayElements(src, data, JNI_ABORT);
    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(out.data()));
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10DirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride, jint width, jint height) {
    if (!dstArray) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const int groups = (width + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * height;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlPackBuf;
    if (tlPackBuf.size() < outLen) {
        tlPackBuf.resize(outLen);
    }
    unsigned char* dst = tlPackBuf.data();

    // 2-way parallel row packing: split rows across slice worker and caller
    const int midY = height / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < height; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
            pack_row(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 5);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
        pack_row(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 5);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10Direct(
        JNIEnv* env, jclass clazz, jobject srcBuffer,
        jint rowStride, jint pixelStride, jint width, jint height) {
    const int groups = (width + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * height;
    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    if (!res) return nullptr;
    if (!Java_dev_rawrec_app_codec_RawPackNative_packMipi10DirectInto(
            env, clazz, srcBuffer, res, rowStride, pixelStride, width, height)) {
        return nullptr;
    }
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10CroppedDirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight) {
    if (!dstArray || cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const jlong cap = env->GetDirectBufferCapacity(srcBuffer);
    if (cap > 0) {
        const size_t maxOffset = static_cast<size_t>(cropTop + cropHeight - 1) * rowStride +
                                 static_cast<size_t>(cropLeft + cropWidth) * pixelStride;
        if (maxOffset > static_cast<size_t>(cap)) return JNI_FALSE;
    }

    const int groups = (cropWidth + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * cropHeight;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlCroppedBuf;
    if (tlCroppedBuf.size() < outLen) {
        tlCroppedBuf.resize(outLen);
    }
    unsigned char* dst = tlCroppedBuf.data();

    const int midY = cropHeight / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < cropHeight; ++y) {
            const uint8_t* row = base +
                    static_cast<size_t>(cropTop + y) * rowStride +
                    static_cast<size_t>(cropLeft) * pixelStride;
            pack_row(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 5);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base +
                static_cast<size_t>(cropTop + y) * rowStride +
                static_cast<size_t>(cropLeft) * pixelStride;
        pack_row(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 5);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10CroppedDirect(
        JNIEnv* env, jclass clazz, jobject srcBuffer,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight) {
    if (cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0) return nullptr;
    const int groups = (cropWidth + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * cropHeight;
    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    if (!res) return nullptr;
    if (!Java_dev_rawrec_app_codec_RawPackNative_packMipi10CroppedDirectInto(
            env, clazz, srcBuffer, res, rowStride, pixelStride,
            cropLeft, cropTop, cropWidth, cropHeight)) {
        return nullptr;
    }
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi12DirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride, jint width, jint height) {
    if (!dstArray) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const int groups = (width + 1) / 2;
    const size_t outLen = static_cast<size_t>(groups) * 3 * height;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlPack12Buf;
    if (tlPack12Buf.size() < outLen) {
        tlPack12Buf.resize(outLen);
    }
    unsigned char* dst = tlPack12Buf.data();

    const int midY = height / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < height; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
            pack_row_raw12(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 3);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
        pack_row_raw12(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 3);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi12CroppedDirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight) {
    if (!dstArray || cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const jlong cap = env->GetDirectBufferCapacity(srcBuffer);
    if (cap > 0) {
        const size_t maxOffset = static_cast<size_t>(cropTop + cropHeight - 1) * rowStride +
                                 static_cast<size_t>(cropLeft + cropWidth) * pixelStride;
        if (maxOffset > static_cast<size_t>(cap)) return JNI_FALSE;
    }

    const int groups = (cropWidth + 1) / 2;
    const size_t outLen = static_cast<size_t>(groups) * 3 * cropHeight;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlCropped12Buf;
    if (tlCropped12Buf.size() < outLen) {
        tlCropped12Buf.resize(outLen);
    }
    unsigned char* dst = tlCropped12Buf.data();

    const int midY = cropHeight / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < cropHeight; ++y) {
            const uint8_t* row = base +
                    static_cast<size_t>(cropTop + y) * rowStride +
                    static_cast<size_t>(cropLeft) * pixelStride;
            pack_row_raw12(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 3);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base +
                static_cast<size_t>(cropTop + y) * rowStride +
                static_cast<size_t>(cropLeft) * pixelStride;
        pack_row_raw12(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 3);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi14DirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride, jint width, jint height) {
    if (!dstArray) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const int groups = (width + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 7 * height;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlPack14Buf;
    if (tlPack14Buf.size() < outLen) {
        tlPack14Buf.resize(outLen);
    }
    unsigned char* dst = tlPack14Buf.data();

    const int midY = height / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < height; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
            pack_row_raw14(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 7);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
        pack_row_raw14(row, pixelStride, width, dst + static_cast<size_t>(y) * groups * 7);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi14CroppedDirectInto(
        JNIEnv* env, jclass, jobject srcBuffer, jbyteArray dstArray,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight) {
    if (!dstArray || cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0) return JNI_FALSE;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return JNI_FALSE;

    const jlong cap = env->GetDirectBufferCapacity(srcBuffer);
    if (cap > 0) {
        const size_t maxOffset = static_cast<size_t>(cropTop + cropHeight - 1) * rowStride +
                                 static_cast<size_t>(cropLeft + cropWidth) * pixelStride;
        if (maxOffset > static_cast<size_t>(cap)) return JNI_FALSE;
    }

    const int groups = (cropWidth + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 7 * cropHeight;
    if (static_cast<size_t>(env->GetArrayLength(dstArray)) < outLen) return JNI_FALSE;

    thread_local std::vector<unsigned char> tlCropped14Buf;
    if (tlCropped14Buf.size() < outLen) {
        tlCropped14Buf.resize(outLen);
    }
    unsigned char* dst = tlCropped14Buf.data();

    const int midY = cropHeight / 2;
    g_sliceWorker.dispatch([=]() {
        for (int y = midY; y < cropHeight; ++y) {
            const uint8_t* row = base +
                    static_cast<size_t>(cropTop + y) * rowStride +
                    static_cast<size_t>(cropLeft) * pixelStride;
            pack_row_raw14(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 7);
        }
    });

    for (int y = 0; y < midY; ++y) {
        const uint8_t* row = base +
                static_cast<size_t>(cropTop + y) * rowStride +
                static_cast<size_t>(cropLeft) * pixelStride;
        pack_row_raw14(row, pixelStride, cropWidth, dst + static_cast<size_t>(y) * groups * 7);
    }

    g_sliceWorker.wait();

    env->SetByteArrayRegion(dstArray, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(dst));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_expandCopyDirect(
        JNIEnv* env, jclass, jobject srcBuffer,
        jint rowStride, jint pixelStride, jint width, jint height) {
    if (pixelStride != 2) return nullptr;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return nullptr;

    const size_t outLen = static_cast<size_t>(width) * height * 2;
    std::vector<unsigned char> out(outLen);
    uint8_t* dst = out.data();

    for (int y = 0; y < height; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
        memcpy(dst, row, static_cast<size_t>(width) * 2);
        dst += static_cast<size_t>(width) * 2;
    }

    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(out.data()));
    return res;
}

static inline unsigned char clamp255(int v) {
    return static_cast<unsigned char>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10ProxyDirect(
        JNIEnv* env, jclass, jobject srcBuffer,
        jint rowStride, jint pixelStride, jint width, jint height,
        jintArray quadCodes, jint whiteLevel, jint factor,
        jobject yBuf, jobject uBuf, jobject vBuf) {
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return nullptr;

    if (!quadCodes || env->GetArrayLength(quadCodes) < 4) return nullptr;
    jint* codes = env->GetIntArrayElements(quadCodes, nullptr);
    if (!codes) return nullptr;
    auto* yBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(yBuf));
    auto* uBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(uBuf));
    auto* vBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(vBuf));
    if (!yBase || !uBase || !vBase) {
        env->ReleaseIntArrayElements(quadCodes, codes, JNI_ABORT);
        return nullptr;
    }

    const int groups = (width + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * height;
    std::vector<unsigned char> packed(outLen);

    const int outW = width / factor;
    const int outH = height / factor;
    const float invWhite = 1.0f / static_cast<float>(whiteLevel > 0 ? whiteLevel : 1023);

    for (int y = 0; y < height; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
        unsigned char* dst = packed.data() + static_cast<size_t>(y) * groups * 5;
        pack_row(row, pixelStride, width, dst);
    }

    for (int oy = 0; oy < outH; ++oy) {
        for (int ox = 0; ox < outW; ++ox) {
            double sum[3] = {0, 0, 0};
            long cnt[3] = {0, 0, 0};
            for (int dy = 0; dy < factor; ++dy) {
                const int sy = oy * factor + dy;
                if (sy >= height) continue;
                const uint8_t* row = base + static_cast<size_t>(sy) * rowStride;
                for (int dx = 0; dx < factor; ++dx) {
                    const int sx = ox * factor + dx;
                    if (sx >= width) continue;
                    const uint8_t* p = row + static_cast<size_t>(sx) * pixelStride;
                    const int s = (p[0] | (p[1] << 8)) & 1023;
                    const int q = ((sy & 1) << 1) | (sx & 1);
                    const int code = codes[q];
                    sum[code] += s;
                    cnt[code]++;
                }
            }
            auto chan = [&](int c) -> float {
                const float avg = cnt[c] > 0
                    ? static_cast<float>(sum[c] / cnt[c]) : 0.f;
                const float norm = avg * invWhite;
                const float clamped = norm < 0.f ? 0.f : (norm > 1.f ? 1.f : norm);
                return sqrtf(clamped);
            };
            const float r = chan(0), g = chan(1), b = chan(2);
            const float yy = 16.f + 219.f * (0.2126f * r + 0.7152f * g + 0.0722f * b);
            const float uu = 128.f + 224.f * (-0.168736f * r - 0.331264f * g + 0.5f * b);
            const float vv = 128.f + 224.f * (0.5f * r - 0.418688f * g - 0.081312f * b);

            yBase[oy * outW + ox] = clamp255(static_cast<int>(yy + 0.5f));
            if ((oy & 1) == 0 && (ox & 1) == 0) {
                const int ci = (oy / 2) * (outW / 2) + (ox / 2);
                uBase[ci] = clamp255(static_cast<int>(uu + 0.5f));
                vBase[ci] = clamp255(static_cast<int>(vv + 0.5f));
            }
        }
    }

    env->ReleaseIntArrayElements(quadCodes, codes, JNI_ABORT);

    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(packed.data()));
    return res;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_RawPackNative_packMipi10ProxyCroppedDirect(
        JNIEnv* env, jclass, jobject srcBuffer,
        jint rowStride, jint pixelStride,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight,
        jintArray quadCodes, jint whiteLevel, jint factor,
        jobject yBuf, jobject uBuf, jobject vBuf) {
    if (cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0) return nullptr;
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    if (!base) return nullptr;

    const jlong cap = env->GetDirectBufferCapacity(srcBuffer);
    if (cap > 0) {
        const size_t maxOffset = static_cast<size_t>(cropTop + cropHeight - 1) * rowStride +
                                 static_cast<size_t>(cropLeft + cropWidth) * pixelStride;
        if (maxOffset > static_cast<size_t>(cap)) return nullptr;
    }

    if (!quadCodes || env->GetArrayLength(quadCodes) < 4) return nullptr;
    jint* codes = env->GetIntArrayElements(quadCodes, nullptr);
    if (!codes) return nullptr;
    auto* yBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(yBuf));
    auto* uBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(uBuf));
    auto* vBase = static_cast<unsigned char*>(env->GetDirectBufferAddress(vBuf));
    if (!yBase || !uBase || !vBase) {
        env->ReleaseIntArrayElements(quadCodes, codes, JNI_ABORT);
        return nullptr;
    }

    const int groups = (cropWidth + 3) / 4;
    const size_t outLen = static_cast<size_t>(groups) * 5 * cropHeight;
    std::vector<unsigned char> packed(outLen);

    const int outW = cropWidth / factor;
    const int outH = cropHeight / factor;
    const float invWhite = 1.0f / static_cast<float>(whiteLevel > 0 ? whiteLevel : 1023);

    for (int y = 0; y < cropHeight; ++y) {
        const uint8_t* row = base +
                static_cast<size_t>(cropTop + y) * rowStride +
                static_cast<size_t>(cropLeft) * pixelStride;
        unsigned char* dst = packed.data() + static_cast<size_t>(y) * groups * 5;
        pack_row(row, pixelStride, cropWidth, dst);
    }

    // Downscale the SAME crop band (sensor-space coords) so the proxy matches
    // the stored framing exactly.
    for (int oy = 0; oy < outH; ++oy) {
        for (int ox = 0; ox < outW; ++ox) {
            double sum[3] = {0, 0, 0};
            long cnt[3] = {0, 0, 0};
            for (int dy = 0; dy < factor; ++dy) {
                const int sy = cropTop + oy * factor + dy;
                const uint8_t* row = base + static_cast<size_t>(sy) * rowStride +
                                     static_cast<size_t>(cropLeft) * pixelStride;
                for (int dx = 0; dx < factor; ++dx) {
                    const int sx = ox * factor + dx;
                    if (sx >= cropWidth) continue;
                    const uint8_t* p = row + static_cast<size_t>(sx) * pixelStride;
                    const int s = (p[0] | (p[1] << 8)) & 1023;
                    const int q = ((sy & 1) << 1) | ((cropLeft + sx) & 1);
                    const int code = codes[q];
                    sum[code] += s;
                    cnt[code]++;
                }
            }
            auto chan = [&](int c) -> float {
                const float avg = cnt[c] > 0
                    ? static_cast<float>(sum[c] / cnt[c]) : 0.f;
                const float norm = avg * invWhite;
                const float clamped = norm < 0.f ? 0.f : (norm > 1.f ? 1.f : norm);
                return sqrtf(clamped);
            };
            const float r = chan(0), g = chan(1), b = chan(2);
            const float yy = 16.f + 219.f * (0.2126f * r + 0.7152f * g + 0.0722f * b);
            const float uu = 128.f + 224.f * (-0.168736f * r - 0.331264f * g + 0.5f * b);
            const float vv = 128.f + 224.f * (0.5f * r - 0.418688f * g - 0.081312f * b);

            yBase[oy * outW + ox] = clamp255(static_cast<int>(yy + 0.5f));
            if ((oy & 1) == 0 && (ox & 1) == 0) {
                const int ci = (oy / 2) * (outW / 2) + (ox / 2);
                uBase[ci] = clamp255(static_cast<int>(uu + 0.5f));
                vBase[ci] = clamp255(static_cast<int>(vv + 0.5f));
            }
        }
    }

    env->ReleaseIntArrayElements(quadCodes, codes, JNI_ABORT);

    jbyteArray res = env->NewByteArray(static_cast<jsize>(outLen));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(outLen),
                            reinterpret_cast<const jbyte*>(packed.data()));
    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_rawrec_app_codec_ZstdNative_version(JNIEnv*, jclass) {
    return static_cast<jint>(ZSTD_versionNumber());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_ZstdNative_compress(
        JNIEnv* env, jclass, jbyteArray src, jint level, jint nbWorkers) {
    const jsize n = env->GetArrayLength(src);
    if (n <= 0) return env->NewByteArray(0);

    jbyte* data = env->GetByteArrayElements(src, nullptr);
    if (!data) return nullptr;
    std::vector<unsigned char> out(ZSTD_compressBound(static_cast<size_t>(n)));

    ZSTD_CCtx* cctx = ZSTD_createCCtx();
    const size_t written = [&] {
        if (!cctx) {
            return ZSTD_compress(
                    out.data(), out.size(), data, static_cast<size_t>(n),
                    static_cast<int>(level));
        }
        ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, static_cast<int>(level));
        if (nbWorkers > 0) {
            ZSTD_CCtx_setParameter(
                    cctx, ZSTD_c_nbWorkers, static_cast<int>(nbWorkers));
        }
        return ZSTD_compress2(
                cctx, out.data(), out.size(), data, static_cast<size_t>(n));
    }();
    if (cctx) ZSTD_freeCCtx(cctx);

    env->ReleaseByteArrayElements(src, data, JNI_ABORT);

    if (ZSTD_isError(written)) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_rawrec_app_codec_ZstdNative_createCCtx(
        JNIEnv*, jclass, jint level, jint nbWorkers) {
    ZSTD_CCtx* cctx = ZSTD_createCCtx();
    if (!cctx) return 0;
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, static_cast<int>(level));
    if (nbWorkers > 0) {
        const size_t rc = ZSTD_CCtx_setParameter(
                cctx, ZSTD_c_nbWorkers, static_cast<int>(nbWorkers));
        if (ZSTD_isError(rc)) {
            ZSTD_freeCCtx(cctx);
            return 0;
        }
    }
    return reinterpret_cast<jlong>(cctx);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_rawrec_app_codec_ZstdNative_createCCtxEx(
        JNIEnv*, jclass, jint level, jint nbWorkers, jint windowLog) {
    ZSTD_CCtx* cctx = ZSTD_createCCtx();
    if (!cctx) return 0;
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, static_cast<int>(level));
    if (nbWorkers > 0) {
        ZSTD_CCtx_setParameter(cctx, ZSTD_c_nbWorkers, static_cast<int>(nbWorkers));
    }
    if (windowLog > 0) {
        ZSTD_CCtx_setParameter(cctx, ZSTD_c_windowLog, static_cast<int>(windowLog));
    }
    return reinterpret_cast<jlong>(cctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_ZstdNative_compressCtx(
        JNIEnv* env, jclass, jlong handle, jbyteArray src) {
    auto* cctx = reinterpret_cast<ZSTD_CCtx*>(handle);
    if (!cctx) return nullptr;
    const jsize n = env->GetArrayLength(src);
    if (n <= 0) return env->NewByteArray(0);

    const size_t bound = ZSTD_compressBound(static_cast<size_t>(n));
    thread_local std::vector<unsigned char> tlOut;
    if (tlOut.size() < bound) {
        tlOut.resize(bound);
    }

    jbyte* data = env->GetByteArrayElements(src, nullptr);
    if (!data) return nullptr;
    const size_t written = ZSTD_compress2(
            cctx, tlOut.data(), tlOut.size(), data, static_cast<size_t>(n));
    env->ReleaseByteArrayElements(src, data, JNI_ABORT);

    if (ZSTD_isError(written)) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(tlOut.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rawrec_app_codec_ZstdNative_freeCCtx(JNIEnv*, jclass, jlong handle) {
    auto* cctx = reinterpret_cast<ZSTD_CCtx*>(handle);
    if (cctx) ZSTD_freeCCtx(cctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rawrec_app_codec_ZstdNative_decompress(
        JNIEnv* env, jclass, jbyteArray src, jlong maxOut) {
    const jsize n = env->GetArrayLength(src);
    if (n <= 0) return env->NewByteArray(0);

    jbyte* data = env->GetByteArrayElements(src, nullptr);
    if (!data) return nullptr;
    unsigned long long expected = ZSTD_getFrameContentSize(data, static_cast<size_t>(n));
    size_t cap = (expected != ZSTD_CONTENTSIZE_ERROR &&
                  expected != ZSTD_CONTENTSIZE_UNKNOWN)
                 ? static_cast<size_t>(expected)
                 : static_cast<size_t>(maxOut);
    if (cap == 0 || cap > static_cast<unsigned long long>(maxOut)) {
        env->ReleaseByteArrayElements(src, data, JNI_ABORT);
        return nullptr;
    }

    std::vector<unsigned char> out(cap);
    const size_t written = ZSTD_decompress(
            out.data(), cap, data, static_cast<size_t>(n));
    env->ReleaseByteArrayElements(src, data, JNI_ABORT);

    if (ZSTD_isError(written)) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_rawrec_app_codec_ZstdNative_decompressInto(
        JNIEnv* env, jclass, jbyteArray src, jbyteArray dst) {
    const jsize srcLen = env->GetArrayLength(src);
    const jsize dstCap = env->GetArrayLength(dst);
    if (srcLen <= 0 || dstCap <= 0) return -1;

    jbyte* srcPtr = env->GetByteArrayElements(src, nullptr);
    if (!srcPtr) return -1;
    jbyte* dstPtr = env->GetByteArrayElements(dst, nullptr);
    if (!dstPtr) {
        env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
        return -1;
    }

    const size_t written = ZSTD_decompress(dstPtr, static_cast<size_t>(dstCap), srcPtr, static_cast<size_t>(srcLen));

    env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(dst, dstPtr, 0);

    if (ZSTD_isError(written)) return -1;
    return static_cast<jint>(written);
}

// ---------------------------------------------------------------------------
// Linux Kernel Syscall Acceleration (posix_fadvise, sched_setaffinity)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_dev_rawrec_app_codec_RawPackNative_adviseDontNeed(
        JNIEnv*, jclass, jint fd, jlong offset, jlong length) {
#if defined(__ANDROID__) || defined(__linux__)
    if (fd >= 0 && length > 0) {
        posix_fadvise(fd, static_cast<off_t>(offset), static_cast<off_t>(length), POSIX_FADV_DONTNEED);
    }
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rawrec_app_codec_RawPackNative_pinToPerformanceCores(JNIEnv*, jclass) {
#if defined(__ANDROID__) || defined(__linux__)
    const int numCores = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    if (numCores <= 1) return JNI_FALSE;

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    // On mobile big.LITTLE / DynamIQ SoCs (Qualcomm Snapdragon 845, 8s Gen 3, etc.),
    // performance cores are located in the upper indices (e.g. cores 4..7 on 8-core chips).
    const int startCore = numCores >= 8 ? (numCores / 2) : 1;
    for (int i = startCore; i < numCores; ++i) {
        CPU_SET(i, &cpuset);
    }

    const int rc = sched_setaffinity(0, sizeof(cpuset), &cpuset);
    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

