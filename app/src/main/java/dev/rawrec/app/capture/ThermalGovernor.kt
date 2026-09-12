package dev.rawrec.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import dev.rawrec.app.profiles.SocFamily
import dev.rawrec.app.profiles.SocOptimizer
import dev.rawrec.app.util.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thermal-aware capture governor.
 *
 * Two independent sources feed one [SocOptimizer.ThermalStage]:
 *  - The standard Android [PowerManager.OnThermalStatusChangedListener] (API 29+).
 *  - Predictive headroom polling via [PowerManager.getThermalHeadroom] (API 30+):
 *    a 10-second forecast above 0.85 escalates one stage BEFORE the hardware
 *    throttles, giving the zstd pool time to drain at reduced size.
 *  - Xiaomi HyperOS thermal broadcasts (`action_temp_state_change`, extra
 *    `temp_state`, stage = value % 10 — matches the stock camera's
 *    ThermalDetector, see tools\apk_out\Camera\sources\com\android\camera\e5.java)
 *    — registered only on Xiaomi devices.
 *
 * The stage consumer is [RecordingController]: on escalation it rescales ONLY
 * the zstd worker pool (never the camera session) and surfaces CRITICAL via
 * RecStats.error. All listeners are best-effort: runCatching-guarded so a
 * vendor PowerManager stub cannot crash recording.
 */
class ThermalGovernor(
    private val context: Context,
    private val onStageChanged: (SocOptimizer.ThermalStage) -> Unit
) {
    companion object {
        private const val TAG = "RawRec-Thermal"

        /** Xiaomi HyperOS thermal broadcast (stock ThermalDetector action). */
        private const val XIAOMI_THERMAL_ACTION = "action_temp_state_change"

        /** Headroom forecast horizon: act 10s ahead of hardware throttling. */
        private const val HEADROOM_FORECAST_S = 10

        /** 10s-forecast headroom above which we pre-escalate one stage. */
        private const val HEADROOM_PREDICTIVE_THRESHOLD = 0.85

        private val POLL_INTERVAL_MS = 3_000L

        /**
         * Maps Android PowerManager thermal status ints to our ladder.
         * Pure, JVM-testable.
         */
        fun stageFromAndroidStatus(status: Int): SocOptimizer.ThermalStage = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> SocOptimizer.ThermalStage.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> SocOptimizer.ThermalStage.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> SocOptimizer.ThermalStage.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> SocOptimizer.ThermalStage.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> SocOptimizer.ThermalStage.CRITICAL
            else -> SocOptimizer.ThermalStage.NONE
        }

        /**
         * Maps a Xiaomi `temp_state` broadcast extra to our ladder
         * (stage = value % 10; 0..2 stay none/light, 3 -> MODERATE,
         * 4 -> SEVERE, 5 -> CRITICAL). Pure, JVM-testable.
         */
        fun stageFromXiaomiTempState(tempState: Int): SocOptimizer.ThermalStage =
            when ((tempState % 10).coerceIn(0, 5)) {
                0, 1 -> SocOptimizer.ThermalStage.NONE
                2 -> SocOptimizer.ThermalStage.LIGHT
                3 -> SocOptimizer.ThermalStage.MODERATE
                4 -> SocOptimizer.ThermalStage.SEVERE
                else -> SocOptimizer.ThermalStage.CRITICAL
            }
    }

    private val stage = AtomicReference(SocOptimizer.ThermalStage.NONE)
    val currentStage: SocOptimizer.ThermalStage get() = stage.get()

    private var statusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var xiaomiReceiver: BroadcastReceiver? = null
    private var poller: ScheduledExecutorService? = null

    /** Highest stage seen from the predictive headroom poll this cycle. */
    @Volatile private var predictiveBoost = false

    fun start() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        // Standard thermal status listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val l = PowerManager.OnThermalStatusChangedListener { status ->
                    onAndroidStatus(status)
                }
                pm.addThermalStatusListener(context.mainExecutor, l)
                statusListener = l
                AppLog.i(TAG, "thermal status listener registered (current=${pm.currentThermalStatus})")
                onAndroidStatus(pm.currentThermalStatus)
            }.onFailure { AppLog.w(TAG, "thermal status listener unavailable: ${it.message}") }
        }

        // Predictive headroom polling (API 30+): escalate one stage early when
        // the 10s forecast crosses the threshold.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            poller = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "rvsp-thermal-poll").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay({ pollHeadroom(pm) }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) }
        }

        // Xiaomi HyperOS thermal broadcasts — the stock camera listens to the
        // same action; registered only on Xiaomi builds.
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val tempState = intent?.getIntExtra("temp_state", -1) ?: -1
                        if (tempState >= 0) {
                            onXiaomiTempState(tempState)
                        }
                    }
                }
                val filter = IntentFilter(XIAOMI_THERMAL_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                xiaomiReceiver = receiver
                AppLog.i(TAG, "Xiaomi thermal broadcast receiver registered")
            }.onFailure { AppLog.w(TAG, "Xiaomi thermal receiver unavailable: ${it.message}") }
        }
    }

    fun stop() {
        runCatching {
            statusListener?.let { l ->
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.removeThermalStatusListener(l)
            }
        }
        statusListener = null
        runCatching { xiaomiReceiver?.let { context.unregisterReceiver(it) } }
        xiaomiReceiver = null
        poller?.shutdownNow()
        poller = null
        predictiveBoost = false
        stage.set(SocOptimizer.ThermalStage.NONE)
    }

    private fun pollHeadroom(pm: PowerManager) {
        runCatching {
            // getThermalHeadroom returns a negative float when it can't
            // forecast (no vendor data); treat anything < 0 as no-signal.
            val forecast = pm.getThermalHeadroom(HEADROOM_FORECAST_S)
            if (forecast < 0f) {
                // Not supported on this OEM/ROM (e.g. log: No temperature thresholds found)
                // Stop poller to avoid spamming system_server IPC every 3 seconds.
                poller?.shutdown()
                poller = null
                return
            }
            val nextPredictive = forecast in 0.0f..Float.MAX_VALUE &&
                    forecast >= HEADROOM_PREDICTIVE_THRESHOLD
            if (nextPredictive != predictiveBoost) {
                predictiveBoost = nextPredictive
                AppLog.i(TAG, "thermal headroom(${HEADROOM_FORECAST_S}s)=$forecast -> predictiveBoost=$nextPredictive")
                publishMax(stage.get())
            }
        }
    }

    private fun onAndroidStatus(status: Int) {
        val s = stageFromAndroidStatus(status)
        AppLog.i(TAG, "android thermal status=$status -> stage=$s")
        publishMax(s)
    }

    private fun onXiaomiTempState(tempState: Int) {
        val s = stageFromXiaomiTempState(tempState)
        AppLog.i(TAG, "xiaomi temp_state=$tempState -> stage=$s")
        publishMax(s)
    }

    /**
     * Escalation is monotonic within a cycle: sources can report differing
     * stages; we keep the max of the reported source stage (± the predictive
     * boost) but never let a lower source reading de-escalate a higher one
     * until [stop] resets. De-escalation between takes is acceptable.
     */
    private fun publishMax(reported: SocOptimizer.ThermalStage) {
        val boosted = if (predictiveBoost) escalateOne(reported) else reported
        val prev = stage.get()
        val next = if (boosted.ordinal >= prev.ordinal) boosted else prev
        if (next != prev) {
            stage.set(next)
            AppLog.w(TAG, "thermal stage $prev -> $next (predictiveBoost=$predictiveBoost)")
            onStageChanged(next)
        }
    }

    private fun escalateOne(s: SocOptimizer.ThermalStage): SocOptimizer.ThermalStage =
        SocOptimizer.ThermalStage.entries[(s.ordinal + 1)
            .coerceAtMost(SocOptimizer.ThermalStage.CRITICAL.ordinal)]
}
