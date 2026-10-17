package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReportStoreTest {

    @Test
    fun longUnicodeStackTraceIsSavedWithinByteLimitAndRemainsReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CrashReportStore(context)
        store.clear()

        val throwable = IllegalStateException("synthetic")
        throwable.stackTrace = Array(24) { index ->
            StackTraceElement(
                "com.example.CrashCase",
                "method$index",
                "á".repeat(3_000),
                index
            )
        }
        store.write(Thread.currentThread(), throwable)

        val reportFile = java.io.File(context.filesDir, "minimo_last_crash_report.txt")
        assertTrue("The persisted report must respect the 48 KiB UTF-8 limit", reportFile.length() <= 48L * 1024L)
        val report = store.readLast()
        assertNotNull("A long multibyte report must still be readable", report)
        assertTrue(report!!.contains("IllegalStateException"))
        assertTrue(report.contains("Tipo de excepción"))
        store.clear()
    }

    @Test
    fun previouslySavedOversizedUtf8ReportIsRecoveredInsteadOfHidden() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reportFile = java.io.File(context.filesDir, "minimo_last_crash_report.txt")
        // Simulate the old character-limited writer: this file is valid UTF-8 but exceeds 48 KiB.
        reportFile.writeText("Informe antiguo\n" + "á".repeat(30_000), Charsets.UTF_8)

        val report = CrashReportStore(context).readLast()
        assertNotNull("Legacy reports must not silently disappear because they exceed the byte limit", report)
        assertTrue(report!!.startsWith("Informe antiguo"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 48 * 1024)
        reportFile.delete()
    }

    @Test
    fun reportIsPrivateReadableAndDoesNotIncludeExceptionMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CrashReportStore(context)
        store.clear()

        val sensitiveMessage = "api_key=synthetic-do-not-store-this-value"
        store.write(Thread.currentThread(), IllegalStateException(sensitiveMessage))

        val report = store.readLast()
        assertNotNull(report)
        assertTrue(report!!.contains("IllegalStateException"))
        assertTrue(report.contains("Mínimo"))
        assertFalse(report.contains(sensitiveMessage))
        assertTrue(store.clear())
        assertTrue(store.readLast() == null)
    }
}
