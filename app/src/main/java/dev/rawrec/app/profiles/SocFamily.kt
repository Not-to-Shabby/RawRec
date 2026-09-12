package dev.rawrec.app.profiles

import android.os.Build

/**
 * Supported System-on-Chip (SoC) architectures for hardware-specific optimizations.
 */
enum class SocFamily {
    QUALCOMM,
    MEDIATEK,
    SAMSUNG_EXYNOS,
    GOOGLE_TENSOR,
    UNISOC,
    GENERIC;

    companion object {
        fun detect(
            hardware: String = Build.HARDWARE.orEmpty(),
            board: String = Build.BOARD.orEmpty(),
            socModel: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        ): SocFamily {
            val combined = "$hardware $board $socModel".uppercase()
            return when {
                combined.containsAny(
                    "QCOM", "QUALCOMM", "SNAPDRAGON", "SM8", "SM7", "SM6", "SM4",
                    "SDM", "MSM", "PERIDOT", "PINEAPPLE", "KALAMA", "TARO", "LAHAINA"
                ) -> QUALCOMM

                combined.containsAny(
                    "MT", "MTK", "MEDIATEK", "DIMENSITY"
                ) -> MEDIATEK

                combined.containsAny(
                    "EXYNOS", "UNIVERSAL", "S5E"
                ) -> SAMSUNG_EXYNOS

                combined.containsAny(
                    "TENSOR", "ZUMA", "GS101", "GS201", "WHITECHAPEL"
                ) -> GOOGLE_TENSOR

                combined.containsAny(
                    "SPRD", "SPREADTRUM", "UNISOC", "UMS", "T618", "T606", "T760", "T820"
                ) -> UNISOC

                else -> GENERIC
            }
        }

        private fun String.containsAny(vararg terms: String): Boolean =
            terms.any { this.contains(it) }
    }
}
