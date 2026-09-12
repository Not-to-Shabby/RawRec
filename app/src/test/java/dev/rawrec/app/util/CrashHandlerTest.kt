package dev.rawrec.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashHandlerTest {

    @Test
    fun `formatCrashReport includes all diagnostic sections`() {
        val thread = Thread({ }, "test-pipeline-thread")
        val exception = IllegalStateException("Camera HAL disconnected unexpectedly")

        val report = CrashHandler.formatCrashReport(
            thread = thread,
            throwable = exception,
            timestamp = "2026-09-06 12:00:00.000",
            deviceInfo = "Xiaomi POCO F6 (peridot, Android 15 API 35)"
        )

        assertTrue("Must contain timestamp", report.contains("2026-09-06 12:00:00.000"))
        assertTrue("Must contain thread name", report.contains("test-pipeline-thread"))
        assertTrue("Must contain device info", report.contains("POCO F6"))
        assertTrue("Must contain exception class", report.contains("java.lang.IllegalStateException"))
        assertTrue("Must contain message", report.contains("Camera HAL disconnected unexpectedly"))
        assertTrue("Must contain stack trace header", report.contains("--- Stack Trace ---"))
    }

    @Test
    fun `crash file lifecycle - init, write, read, clear`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "rawrec_crash_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            CrashHandler.init(tempDir)
            val file = CrashHandler.crashFile()
            assertTrue(file != null)

            // Initially clean
            CrashHandler.clearCrashLogs()
            assertFalse(CrashHandler.hasCrashLogs())
            assertEquals("", CrashHandler.readCrashLog())

            // Simulate crash write
            val dummyReport = "FATAL CRASH TEST REPORT"
            file!!.writeText(dummyReport, Charsets.UTF_8)

            assertTrue(CrashHandler.hasCrashLogs())
            assertEquals(dummyReport, CrashHandler.readCrashLog())

            // Clear
            assertTrue(CrashHandler.clearCrashLogs())
            assertFalse(CrashHandler.hasCrashLogs())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
