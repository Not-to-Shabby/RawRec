package dev.rawrec.app.capture

enum class EntropyStage(val label: String, val levelOffset: Int) {
    NORMAL("NORMAL", 0),       // Tier 1: Nominal scene entropy (midQ <= 4, pool >= 6)
    PRESSURE("PRESSURE", -3),  // Tier 2: Elevated entropy (midQ > 6 or pool < 6) -> fast Zstd (-5/-6)
    CRITICAL("CRITICAL", -6)   // Tier 3: Extreme entropy/stall (midQ > 11 or pool <= 2) -> ultra-fast
}

/**
 * Closed-Loop Scene-Adaptive Entropy Governor.
 *
 * Monitors queue occupancy (midQ, outQ) and circulating buffer pool health (payloadPool).
 * Dynamically shifts Zstandard compression levels to prevent queue overflow and buffer
 * exhaustion when shooting worst-case entropy scenes (high-ISO noise, dense rain, rapid panning).
 *
 * Hysteresis:
 * - Escalates immediately (1 tick) to guarantee zero dropped frames.
 * - De-escalates smoothly (requires 6 consecutive relief ticks) to prevent level flapping.
 */
class EntropyGovernor(
    val midCapacity: Int,
    val outCapacity: Int,
    val totalPoolBuffers: Int = 15,
    val nominalLevel: Int = -3,
    private val onStageChanged: ((EntropyStage, Int) -> Unit)? = null
) {
    @Volatile var currentStage: EntropyStage = EntropyStage.NORMAL
        private set

    @Volatile var activeLevel: Int = nominalLevel
        private set

    private var consecutivePressure = 0
    private var consecutiveRelief = 0

    @Synchronized
    fun evaluate(midQSize: Int, outQSize: Int, poolRemaining: Int): EntropyStage {
        val midOccupancy = midQSize.toFloat() / midCapacity.coerceAtLeast(1)
        val outOccupancy = outQSize.toFloat() / outCapacity.coerceAtLeast(1)
        val poolDepleted = poolRemaining <= 2
        val poolLow = poolRemaining <= 5

        val targetStage = when {
            poolDepleted || midOccupancy >= 0.70f || outOccupancy >= 0.85f -> EntropyStage.CRITICAL
            poolLow || midOccupancy >= 0.38f || outOccupancy >= 0.60f -> EntropyStage.PRESSURE
            else -> EntropyStage.NORMAL
        }

        if (targetStage != currentStage) {
            if (targetStage.ordinal > currentStage.ordinal) {
                // Immediate escalation on rising pressure
                consecutivePressure++
                consecutiveRelief = 0
                if (consecutivePressure >= 1) {
                    currentStage = targetStage
                    activeLevel = nominalLevel + targetStage.levelOffset
                    onStageChanged?.invoke(targetStage, activeLevel)
                }
            } else {
                // Damped de-escalation (6 consecutive quiet ticks required)
                consecutiveRelief++
                consecutivePressure = 0
                if (consecutiveRelief >= 6) {
                    currentStage = targetStage
                    activeLevel = nominalLevel + targetStage.levelOffset
                    consecutiveRelief = 0
                    onStageChanged?.invoke(targetStage, activeLevel)
                }
            }
        } else {
            consecutivePressure = 0
            consecutiveRelief = 0
        }
        return currentStage
    }

    fun reset() {
        currentStage = EntropyStage.NORMAL
        activeLevel = nominalLevel
        consecutivePressure = 0
        consecutiveRelief = 0
    }
}
