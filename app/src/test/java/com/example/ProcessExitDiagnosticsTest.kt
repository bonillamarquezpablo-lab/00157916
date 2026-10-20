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
    fun userRequestedExitIsIgnored() {
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun anrIsReportable() {
        val label = describeProcessExitReason(ApplicationExitInfo.REASON_ANR)

        assertNotNull(label)
        assertTrue(label!!.contains("ANR"))
        assertTrue(label.contains("no respondía"))
    }

    @Test
    fun javaAndNativeCrashesAreReportable() {
        assertTrue(describeProcessExitReason(ApplicationExitInfo.REASON_CRASH)!!.contains("Excepción fatal"))
        assertTrue(describeProcessExitReason(ApplicationExitInfo.REASON_CRASH_NATIVE)!!.contains("Fallo nativo"))
    }

    @Test
    fun routineSystemExitsDoNotCreateErrorReports() {
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
        assertNull(describeProcessExitReason(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
    }

    @Test
    fun validCrashAndAnrReportsAreVisibleInErrorReportMenu() {
        assertTrue(isReportableProcessExitReport(reportWithReason("ANR: Android detectó que la aplicación no respondía.")))
        assertTrue(isReportableProcessExitReport(reportWithReason("Excepción fatal: Android registró que falló la app.")))
        assertTrue(isReportableProcessExitReport(reportWithReason("Fallo nativo: Android registró un fallo.")))
    }

    @Test
    fun legacyUserOrSystemExitReportsAreRejected() {
        assertFalse(isReportableProcessExitReport(reportWithReason("Cierre solicitado por el usuario o el sistema.")))
        assertFalse(isReportableProcessExitReport(reportWithReason("Android terminó el proceso por falta de memoria disponible.")))
        assertFalse(isReportableProcessExitReport("Informe ajeno a los diagnósticos de Mínimo"))
    }

    private fun reportWithReason(reason: String) = """
        Mínimo — diagnóstico del cierre anterior (registro de Android)
        Fecha de salida UTC: 2026-10-20T10:00:00.000Z
        Versión: 1.7.579.1 (57901)
        Android: 17 (API 37)
        Dispositivo: samsung SM-S948B
        Motivo registrado: $reason
        Este diagnóstico se ha guardado solo en el almacenamiento privado de Mínimo.
    """.trimIndent()
}
