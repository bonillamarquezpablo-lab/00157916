package com.example

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessExitDiagnosticsTest {

    @Test
    fun userRequestedExitIsReportedWithoutBeingMislabelledAsCodeCrash() {
        val label = describeProcessExitReason(ApplicationExitInfo.REASON_USER_REQUESTED)

        assertNotNull(label)
        assertTrue(label!!.contains("Forzar detención"))
        assertTrue(label.contains("no implica por sí solo un fallo"))
        assertFalse(label.contains("Excepción fatal"))
    }

    @Test
    fun anrIsIdentifiedExplicitly() {
        val label = describeProcessExitReason(ApplicationExitInfo.REASON_ANR)

        assertNotNull(label)
        assertTrue(label!!.contains("ANR"))
        assertTrue(label.contains("no respondía"))
    }

    @Test
    fun normalPackageUpdateDoesNotCreateAnErrorReport() {
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }
}
