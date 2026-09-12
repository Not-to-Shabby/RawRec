package dev.rawrec.app.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE_NAME = "rawrec.log"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private var dir: File? = null

    fun init(logsDir: File) {
        dir = logsDir.apply { mkdirs() }
    }

    private fun sink(): File? = dir?.let { File(it, FILE_NAME) }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val f = sink() ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, f.nameWithoutExtension + ".old.log")
                old.delete()
                f.renameTo(old)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts $level/$tag: $msg${tr?.let { "\n  ${it.toString()}" } ?: ""}\n")
        }
    }

    fun v(tag: String, msg: String) {
        Log.v(tag, msg); write("V", tag, msg, null)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg); write("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg); write("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr); write("W", tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr); write("E", tag, msg, tr)
    }
}
