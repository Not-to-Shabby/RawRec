package dev.rawrec.app.util

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught exception handler for RawRec.
 *
 * Traps any fatal Kotlin/Java crash on any thread, formats a comprehensive
 * diagnostic report with full stack traces, logs to AppLog (rawrec.log),
 * appends to a persistent crash.log file on disk, and calls fd.sync() to
 * guarantee data is committed to UFS storage before the process terminates.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "RawRec-Crash"
    const val CRASH_FILE_NAME = "crash.log"
    private const val MAX_CRASH_LOG_BYTES = 2L * 1024 * 1024 // 2MB cap

    private var logsDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    fun init(dir: File) {
        if (isInstalled) return
        logsDir = dir.apply { mkdirs() }
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        isInstalled = true
        runCatching {
            AppLog.d(TAG, "CrashHandler installed, sink=${crashFile()?.absolutePath}")
        }
    }

    fun crashFile(): File? = logsDir?.let { File(it, CRASH_FILE_NAME) }

    fun hasCrashLogs(): Boolean {
        val f = crashFile() ?: return false
        return f.exists() && f.length() > 0
    }

    fun readCrashLog(): String {
        val f = crashFile() ?: return ""
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun clearCrashLogs(): Boolean {
        val f = crashFile() ?: return false
        return if (f.exists()) f.delete() else true
    }

    fun formatCrashReport(
        thread: Thread,
        throwable: Throwable,
        timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()),
        deviceInfo: String = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}, Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT})"
    ): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        val stackTrace = sw.toString()

        return buildString {
            appendLine("==================== FATAL EXCEPTION ====================")
            appendLine("Timestamp : $timestamp")
            appendLine("Thread    : ${thread.name} (id=${thread.id}, priority=${thread.priority})")
            appendLine("Device    : $deviceInfo")
            appendLine("Exception : ${throwable.javaClass.name}")
            appendLine("Message   : ${throwable.message ?: "none"}")
            appendLine("--- Stack Trace ---")
            append(stackTrace)
            appendLine("=========================================================")
            appendLine()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val report = formatCrashReport(thread, throwable)

        // 1. Mirror to system Logcat & AppLog
        runCatching {
            Log.e(TAG, "FATAL CRASH on thread ${thread.name}", throwable)
            AppLog.e(TAG, "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
        }

        // 2. Persist synchronously to crash.log and sync hardware buffer
        crashFile()?.let { file ->
            runCatching {
                if (file.length() > MAX_CRASH_LOG_BYTES) {
                    val old = File(file.parentFile, "crash.old.log")
                    old.delete()
                    file.renameTo(old)
                }
                FileOutputStream(file, true).use { fos ->
                    fos.write(report.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync() // Force physical disk sync before process death
                }
            }
        }

        // 3. Chain to previous default handler so system dialog/termination executes
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
