package dev.rawrec.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocOptimizerTest {

    @Test
    fun `SocFamily detects Qualcomm hardware correctly`() {
        assertEquals(SocFamily.QUALCOMM, SocFamily.detect(hardware = "qcom", board = "peridot"))
        assertEquals(SocFamily.QUALCOMM, SocFamily.detect(hardware = "qcom", socModel = "SM8635"))
        assertEquals(SocFamily.QUALCOMM, SocFamily.detect(hardware = "pineapple", board = "kalama"))
        assertEquals(SocFamily.QUALCOMM, SocFamily.detect(hardware = "snapdragon 8 gen 3"))
    }

    @Test
    fun `SocFamily detects MediaTek hardware correctly`() {
        assertEquals(SocFamily.MEDIATEK, SocFamily.detect(hardware = "mt6989", board = "k6989v1_64"))
        assertEquals(SocFamily.MEDIATEK, SocFamily.detect(hardware = "dimensity 9300"))
        assertEquals(SocFamily.MEDIATEK, SocFamily.detect(board = "mt6895"))
    }

    @Test
    fun `SocFamily detects Samsung Exynos hardware correctly`() {
        assertEquals(SocFamily.SAMSUNG_EXYNOS, SocFamily.detect(hardware = "exynos2400", board = "s5e9945"))
        assertEquals(SocFamily.SAMSUNG_EXYNOS, SocFamily.detect(board = "universal9925"))
    }

    @Test
    fun `SocFamily detects Google Tensor hardware correctly`() {
        assertEquals(SocFamily.GOOGLE_TENSOR, SocFamily.detect(hardware = "zuma", board = "ripcurrent"))
        assertEquals(SocFamily.GOOGLE_TENSOR, SocFamily.detect(hardware = "tensor g3"))
    }

    @Test
    fun `SocFamily detects Unisoc hardware correctly`() {
        assertEquals(SocFamily.UNISOC, SocFamily.detect(hardware = "ums9620", board = "sprd"))
        assertEquals(SocFamily.UNISOC, SocFamily.detect(hardware = "unisoc t820"))
    }

    @Test
    fun `SocFamily falls back to Generic on unknown hardware`() {
        assertEquals(SocFamily.GENERIC, SocFamily.detect(hardware = "custom_fpga", board = "generic_board"))
    }

    @Test
    fun `SocOptimizer bounds memory and queues for 50MP unbinned mode`() {
        val normalTuning = SocOptimizer.current(family = SocFamily.QUALCOMM, isHighRes50Mp = false)
        assertEquals(8, normalTuning.recommendedQueueCapacities.inCapacity)
        assertEquals(12, normalTuning.recommendedQueueCapacities.midCapacity)
        assertEquals(12, normalTuning.recommendedQueueCapacities.outCapacity)
        assertEquals(12, normalTuning.recommendedQueueCapacities.maxImages)

        // 50MP unbinned mode: queue caps are strictly bounded to prevent LMK
        val highResTuning = SocOptimizer.current(family = SocFamily.QUALCOMM, isHighRes50Mp = true)
        assertEquals(3, highResTuning.recommendedQueueCapacities.inCapacity)
        assertEquals(4, highResTuning.recommendedQueueCapacities.midCapacity)
        assertEquals(6, highResTuning.recommendedQueueCapacities.outCapacity)
        assertEquals(6, highResTuning.recommendedQueueCapacities.maxImages)
    }

    @Test
    fun `SocOptimizer configures MediaTek 128-byte stride alignment`() {
        val mtk = SocOptimizer.current(family = SocFamily.MEDIATEK)
        assertEquals(128, mtk.strideAlignmentBytes)
        assertEquals(SocFamily.MEDIATEK, mtk.family)
    }

    @Test
    fun `SocOptimizer scales workers based on available CPU cores`() {
        val tuning8Cores = SocOptimizer.current(family = SocFamily.QUALCOMM, availableCores = 8)
        assertTrue(tuning8Cores.recommendedCompressWorkers in 2..4)

        val tuningLowCores = SocOptimizer.current(family = SocFamily.UNISOC, availableCores = 4)
        assertEquals(2, tuningLowCores.recommendedCompressWorkers)
    }

    // ---- Thermal degradation ladder (Tier 2) ----

    @Test
    fun `thermal ladder returns base tuning at NONE and LIGHT`() {
        val base = SocOptimizer.current(family = SocFamily.QUALCOMM, availableCores = 8)
        assertEquals(
            base.recommendedCompressWorkers,
            SocOptimizer.tuningFor(SocFamily.QUALCOMM, availableCores = 8, thermalStage = SocOptimizer.ThermalStage.NONE)
                .recommendedCompressWorkers
        )
        assertEquals(
            base.recommendedCompressWorkers,
            SocOptimizer.tuningFor(SocFamily.QUALCOMM, availableCores = 8, thermalStage = SocOptimizer.ThermalStage.LIGHT)
                .recommendedCompressWorkers
        )
    }

    @Test
    fun `thermal ladder sheds one worker at MODERATE`() {
        val base = SocOptimizer.current(family = SocFamily.QUALCOMM, availableCores = 8)
        val moderate = SocOptimizer.tuningFor(
            SocFamily.QUALCOMM, availableCores = 8, thermalStage = SocOptimizer.ThermalStage.MODERATE
        )
        assertEquals(base.recommendedCompressWorkers - 1, moderate.recommendedCompressWorkers)
    }

    @Test
    fun `thermal ladder floors workers at 2 and tightens inQ at SEVERE`() {
        val severe = SocOptimizer.tuningFor(
            SocFamily.QUALCOMM, availableCores = 8, thermalStage = SocOptimizer.ThermalStage.SEVERE
        )
        assertEquals(2, severe.recommendedCompressWorkers)
        val base = SocOptimizer.current(family = SocFamily.QUALCOMM, availableCores = 8)
        assertEquals(base.recommendedQueueCapacities.inCapacity - 2, severe.recommendedQueueCapacities.inCapacity)

        // A 2-worker family (Unisoc) cannot go below 2.
        val unisocSevere = SocOptimizer.tuningFor(
            SocFamily.UNISOC, availableCores = 4, thermalStage = SocOptimizer.ThermalStage.SEVERE
        )
        assertEquals(2, unisocSevere.recommendedCompressWorkers)
        // inQ floor is 2 even from a base of 3 (50MP mode).
        val highResSevere = SocOptimizer.tuningFor(
            SocFamily.QUALCOMM, isHighRes50Mp = true, availableCores = 8,
            thermalStage = SocOptimizer.ThermalStage.SEVERE
        )
        assertEquals(2, highResSevere.recommendedQueueCapacities.inCapacity)
    }

    @Test
    fun `thermal ladder marks CRITICAL in description`() {
        val critical = SocOptimizer.tuningFor(
            SocFamily.QUALCOMM, availableCores = 8, thermalStage = SocOptimizer.ThermalStage.CRITICAL
        )
        assertTrue(critical.description.contains("CRITICAL"))
        assertEquals(2, critical.recommendedCompressWorkers)
    }
}
