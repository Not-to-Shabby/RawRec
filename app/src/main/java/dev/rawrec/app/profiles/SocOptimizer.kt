package dev.rawrec.app.profiles

import android.os.Process

data class SocTuning(
    val family: SocFamily,
    val recommendedCompressWorkers: Int,
    val recommendedQueueCapacities: QueueCapacities,
    val threadPriority: Int,
    val defaultPacking: String,
    val strideAlignmentBytes: Int,
    val description: String,
    val zstdLevel: Int = 1
)

data class QueueCapacities(
    val inCapacity: Int,
    val midCapacity: Int,
    val outCapacity: Int,
    val maxImages: Int
)

object SocOptimizer {

    /**
     * Thermal degradation stages, ordered. Mirrors Android's
     * PowerManager.THERMAL_STATUS_* ladder plus a Xiaomi HyperOS stage
     * (action_temp_state_change, temp_state % 10, 0..5) mapped onto the same
     * scale: Xiaomi stage 3+ ~ MODERATE, 4 ~ SEVERE, 5 ~ CRITICAL.
     */
    enum class ThermalStage { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

    fun tuningFor(
        family: SocFamily = SocFamily.detect(),
        isHighRes50Mp: Boolean = false,
        availableCores: Int = Runtime.getRuntime().availableProcessors(),
        thermalStage: ThermalStage = ThermalStage.NONE
    ): SocTuning {
        val base = current(family, isHighRes50Mp, availableCores)
        if (thermalStage == ThermalStage.NONE) return base
        return when (thermalStage) {
            ThermalStage.LIGHT -> base
            // MODERATE: shed one zstd worker — throughput margin shrinks but
            // the writer stays fed (outQ absorbs bursts).
            ThermalStage.MODERATE -> base.copy(
                recommendedCompressWorkers = (base.recommendedCompressWorkers - 1)
                    .coerceAtLeast(2),
                description = base.description + " (thermal: moderate, workers-1)"
            )
            // SEVERE: minimum workers + tighter inbound queue to bound memory
            // pressure as the kernel begins throttling.
            ThermalStage.SEVERE -> base.copy(
                recommendedCompressWorkers = 2,
                recommendedQueueCapacities = base.recommendedQueueCapacities.copy(
                    inCapacity = (base.recommendedQueueCapacities.inCapacity - 2)
                        .coerceAtLeast(2)
                ),
                description = base.description + " (thermal: severe, workers=2, inQ-2)"
            )
            // CRITICAL: keep SEVERE sizing; the caller surfaces the stage via
            // RecStats.error so the operator sees it in the studio header.
            ThermalStage.CRITICAL -> base.copy(
                recommendedCompressWorkers = 2,
                recommendedQueueCapacities = base.recommendedQueueCapacities.copy(
                    inCapacity = (base.recommendedQueueCapacities.inCapacity - 2)
                        .coerceAtLeast(2)
                ),
                description = base.description + " (thermal: CRITICAL)"
            )
            ThermalStage.NONE -> base
        }
    }

    fun current(
        family: SocFamily = SocFamily.detect(),
        isHighRes50Mp: Boolean = false,
        availableCores: Int = Runtime.getRuntime().availableProcessors()
    ): SocTuning {
        val totalCores = availableCores.coerceAtLeast(1)

        val queueCaps = if (isHighRes50Mp) {
            // 50MP unbinned: each frame ~63MB packed / ~100MB unpacked.
            // Strict queue bounding prevents Android Low Memory Killer (LMK).
            QueueCapacities(inCapacity = 3, midCapacity = 4, outCapacity = 6, maxImages = 6)
        } else {
            // Standard 12MP binned: 8-frame inCapacity (267ms), 12-frame midQ (400ms), 12-frame outQ & maxImages=12
            // Memory footprint: ~235MB pooled raw + ~150MB compressed outQ = ~385MB (safe within 512MB heap limit).
            QueueCapacities(inCapacity = 8, midCapacity = 12, outCapacity = 12, maxImages = 12)
        }

        return when (family) {
            SocFamily.QUALCOMM -> {
                // Qualcomm Prime + Performance cluster tuning (e.g. Snapdragon 8s Gen 3 / SM8635).
                // 4 workers provide the 450+ MB/s compression throughput needed for full 4:3 12.6MP RAW.
                val workers = (totalCores - 2).coerceIn(2, 4)
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = workers,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    defaultPacking = "MIPI10_PACKED",
                    strideAlignmentBytes = 64,
                    description = "Qualcomm Snapdragon Kryo/Oryon core tuning",
                    zstdLevel = -3 // Ultra-fast mode: achieves ~40 fps aggregate compression throughput on Kryo cores
                )
            }
            SocFamily.MEDIATEK -> {
                // MediaTek Dimensity (all-big core layout on 9300; 128/256-byte stride quirks).
                val workers = (totalCores - 1).coerceIn(2, 4)
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = workers,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    defaultPacking = "MIPI10_PACKED",
                    strideAlignmentBytes = 128,
                    description = "MediaTek Dimensity Ultra/Big core tuning"
                )
            }
            SocFamily.SAMSUNG_EXYNOS -> {
                val workers = (totalCores - 2).coerceIn(2, 3)
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = workers,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    defaultPacking = "MIPI10_PACKED",
                    strideAlignmentBytes = 64,
                    description = "Samsung Exynos Performance cluster tuning"
                )
            }
            SocFamily.GOOGLE_TENSOR -> {
                val workers = (totalCores - 2).coerceIn(2, 3)
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = workers,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    defaultPacking = "MIPI10_PACKED",
                    strideAlignmentBytes = 64,
                    description = "Google Tensor dual-prime affinity tuning"
                )
            }
            SocFamily.UNISOC -> {
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = 2,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_DEFAULT,
                    defaultPacking = "MIPI10_PACKED",
                    strideAlignmentBytes = 64,
                    description = "Unisoc memory-bandwidth conservative tuning"
                )
            }
            SocFamily.GENERIC -> {
                val workers = (totalCores - 1).coerceIn(2, 3)
                SocTuning(
                    family = family,
                    recommendedCompressWorkers = workers,
                    recommendedQueueCapacities = queueCaps,
                    threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    defaultPacking = "AUTODETECT",
                    strideAlignmentBytes = 64,
                    description = "Generic ARM64 multithread tuning"
                )
            }
        }
    }

    fun applyThreadPriority(priority: Int) {
        runCatching { Process.setThreadPriority(priority) }
    }
}
