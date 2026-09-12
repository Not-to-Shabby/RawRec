package dev.rawrec.app.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dev.rawrec.app.util.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tier 3 — DVFS & scheduling helpers for the capture pipeline:
 *
 * 1. ADPF hint session (API 31+, [android.os.PerformanceHintManager]): the
 *    capture thread, zstd workers, and writer are registered as a hint group
 *    with the frame period as target duration. Each compressed frame reports
 *    its actual pack+compress duration; the SoC's DVFS governor then holds
 *    clocks where the pipeline needs them instead of down-clocking during
 *    inter-frame gaps. Published gains for continuous workloads: 30-50%
 *    reduction in thermal throttling (source: developer.android.com ADPF).
 *    Registered TIDs must belong to this process — all ours do.
 *
 * 2. TOP_APP cgroup steering for the writer thread: the platform-sanctioned
 *    way (vs restricted sched_setaffinity) to bias EAS/uclamp toward
 *    performance cores for a non-root app.
 *
 * 3. HyperOS powerkeeper notify: the stock Xiaomi camera broadcasts
 *    record_start/record_end to com.miui.powerkeeper so the vendor governor
 *    lifts camera power limits during recording (decompiled evidence in
 *    tools\apk_out\Camera\sources\com\android\camera\f5.java).
 *
 * Everything is runCatching-guarded: an OEM without ADPF or a non-MIUI
 * build degrades to a no-op without touching recording.
 */
class PerfSession(private val context: Context) {
    companion object {
        private const val TAG = "RawRec-Perf"
        private const val POWERKEEPER_ACTION_RECORD_START = "com.miui.powerkeeper.record_start"
        private const val POWERKEEPER_ACTION_RECORD_END = "com.miui.powerkeeper.record_end"
        private const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"
    }

    private var hintSession: android.os.PerformanceHintManager.Session? = null
    private val registeredTids = CopyOnWriteArrayList<Int>()

    // Last report bookkeeping so the caller can measure per-item durations.
    private val markNs = ThreadLocal.withInitial { 0L }

    val isActive: Boolean get() = hintSession != null

    /**
     * Starts the hint session for the given thread IDs with the project frame
     * period as the target work duration. Idempotent: restarts replace the
     * session (pool rescale passes the new worker TIDs via [updateThreads]).
     */
    fun startHintSession(tids: IntArray, framePeriodNs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val phm = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE)
                    as? android.os.PerformanceHintManager ?: return
            endHintSession()
            val session = phm.createHintSession(tids, framePeriodNs)
            if (session != null) {
                hintSession = session
                registeredTids.clear()
                registeredTids.addAll(tids.toList())
                AppLog.i(TAG, "ADPF hint session started: ${tids.size} threads, target=${framePeriodNs / 1_000_000}ms")
            } else {
                AppLog.w(TAG, "ADPF hint session unavailable on this OEM")
            }
        }.onFailure { AppLog.w(TAG, "ADPF start failed: ${it.message}") }
    }

    /** Re-registers the thread list after a zstd pool rescale. */
    fun updateThreads(tids: IntArray) {
        val session = hintSession ?: return
        runCatching {
            session.setThreads(tids)
            registeredTids.clear()
            registeredTids.addAll(tids.toList())
            AppLog.i(TAG, "ADPF hint session threads updated: ${tids.size}")
        }.onFailure { AppLog.w(TAG, "ADPF setThreads failed: ${it.message}") }
    }

    /** Marks the start of a work item on the calling (registered) thread. */
    fun markWorkStart() {
        markNs.set(System.nanoTime())
    }

    /**
     * Reports the actual duration since [markWorkStart] to the governor.
     * Rate-safe: called at frame rate (~30Hz) which is below the preferred
     * update rate (~60Hz).
     */
    fun reportWorkDone() {
        val session = hintSession ?: return
        val start = markNs.get()
        if (start == 0L) return
        markNs.set(0L)
        val duration = System.nanoTime() - start
        runCatching { session.reportActualWorkDuration(duration) }
            .onFailure { AppLog.w(TAG, "ADPF report failed: ${it.message}") }
    }

    fun endHintSession() {
        hintSession?.let { s ->
            runCatching { s.close() }
        }
        hintSession = null
        registeredTids.clear()
    }

    /**
     * TOP_APP cgroup steering was evaluated and dropped:
     * Process.setThreadGroup/THREAD_GROUP_TOP_APP are @SystemApi — hidden
     * from normal apps. ADPF hint sessions (above) plus
     * THREAD_PRIORITY_URGENT_DISPLAY on the writer are the sanctioned
     * non-root route.
     */

    /**
     * Notifies the HyperOS power governor that a recording session is active,
     * exactly as the stock camera does (record_start/record_end with quality
     * and fps extras). Xiaomi + Qualcomm only; no-op elsewhere.
     */
    fun notifyPowerkeeperRecordingStart(fps: Double, quality: String) {
        if (!isXiaomiQualcomm()) return
        runCatching {
            val intent = Intent(POWERKEEPER_ACTION_RECORD_START).apply {
                setPackage(POWERKEEPER_PACKAGE)
                putExtra("fps", fps.toInt())
                putExtra("quality", quality)
            }
            context.sendBroadcast(intent)
            AppLog.i(TAG, "powerkeeper record_start notified (fps=$fps quality=$quality)")
        }.onFailure { AppLog.w(TAG, "powerkeeper notify failed: ${it.message}") }
    }

    fun notifyPowerkeeperRecordingEnd() {
        if (!isXiaomiQualcomm()) return
        runCatching {
            context.sendBroadcast(
                Intent(POWERKEEPER_ACTION_RECORD_END).setPackage(POWERKEEPER_PACKAGE)
            )
            AppLog.i(TAG, "powerkeeper record_end notified")
        }.onFailure { AppLog.w(TAG, "powerkeeper notify failed: ${it.message}") }
    }

    private fun isXiaomiQualcomm(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
                dev.rawrec.app.profiles.SocFamily.detect() == dev.rawrec.app.profiles.SocFamily.QUALCOMM
}
