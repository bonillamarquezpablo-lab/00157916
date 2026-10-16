package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReportStorageTest {

    @Test
    fun latestCrashReportIsSelectedOnlyFromPrivateReportDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "crash-reports")
        directory.deleteRecursively()

        try {
            assertNull(latestCrashReportFile(context))
            assertTrue(directory.mkdirs())

            val older = File(directory, "Minimo-crash-20261015-000000-000.txt")
                .apply { writeText("older report"); setLastModified(1_000L) }
            val newest = File(directory, "Minimo-crash-20261016-000000-000.txt")
                .apply { writeText("newer report"); setLastModified(2_000L) }
            File(directory, "not-a-crash-report.txt").writeText("not a crash report")

            assertEquals(newest.canonicalFile, latestCrashReportFile(context)?.canonicalFile)
            assertEquals("older report", older.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
