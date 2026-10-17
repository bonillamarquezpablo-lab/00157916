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
