package dev.rawrec.app.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import dev.rawrec.app.util.AppLog

/**
 * Dynamic vendor camera tag engine for Qualcomm, Xiaomi HyperOS, MediaTek, and other SoCs.
 * Uses safe inspection of availableCaptureRequestKeys so vendor tags are only applied when
 * confirmed supported by the underlying camera HAL.
 */
object VendorTags {
    private const val TAG = "RawRec-VendorTags"

    /**
     * Finds any available capture request key whose name contains or equals [targetName].
     */
    fun findRequestKey(chars: CameraCharacteristics, targetName: String): CaptureRequest.Key<*>? {
        return runCatching {
            chars.availableCaptureRequestKeys.firstOrNull {
                it.name.contains(targetName, ignoreCase = true)
            }
        }.getOrNull()
    }

    /**
     * Safely applies a vendor tag to a CaptureRequest.Builder if the key is supported.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> setVendorTag(
        builder: CaptureRequest.Builder?,
        key: CaptureRequest.Key<T>?,
        value: T
    ): Boolean {
        if (builder == null || key == null) return false
        return runCatching {
            builder.set(key, value)
            true
        }.getOrDefault(false)
    }

    /**
     * Discover and return all vendor tags present on the device characteristics.
     */
    fun discoverVendorTags(chars: CameraCharacteristics): List<String> {
        return runCatching {
            chars.availableCaptureRequestKeys
                .map { it.name }
                .filter { name ->
                    name.startsWith("com.xiaomi") ||
                    name.startsWith("org.codeaurora") ||
                    name.startsWith("org.quic") ||
                    name.startsWith("com.qti") ||
                    name.startsWith("com.qualcomm") ||
                    name.startsWith("com.mediatek") ||
                    name.startsWith("com.samsung") ||
                    name.startsWith("com.google") ||
                    name.startsWith("com.sprd")
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Apply known performance and RAW stream optimization vendor tags.
     */
    fun applyOptimizations(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics
    ) {
        val vendorKeys = runCatching { chars.availableCaptureRequestKeys }.getOrNull().orEmpty()
        var appliedCount = 0

        for (k in vendorKeys) {
            val name = k.name
            try {
                when {
                    // Qualcomm / QTI: select manual exposure/ISO priority over 3A
                    name.contains("select_priority", ignoreCase = true) -> {
                        (k as? CaptureRequest.Key<Int>)?.let {
                            builder.set(it, 1)
                            appliedCount++
                        }
                    }
                    // Xiaomi RAW mode / High performance streaming
                    name.contains("rawmode", ignoreCase = true) || name.contains("raw_mode", ignoreCase = true) -> {
                        val applied = (k as? CaptureRequest.Key<Byte>)?.let {
                            builder.set(it, 1.toByte())
                            true
                        } ?: (k as? CaptureRequest.Key<Int>)?.let {
                            builder.set(it, 1)
                            true
                        } ?: false
                        if (applied) appliedCount++
                    }
                }
            } catch (e: Exception) {
                AppLog.d(TAG, "Failed setting vendor tag $name: ${e.message}")
            }
        }

        if (appliedCount > 0) {
            AppLog.i(TAG, "Applied $appliedCount vendor optimization tags")
        }
    }
}
