package com.example.ui.screens

import com.example.data.remote.WaybackTraceStatus
import com.example.ui.viewmodel.WaybackDiagnosticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductDetailSheetDiagnosticTest {
    @Test
    fun activeRateLimitDiagnosisShowsTheRetryClockAndRemainingMinutes() {
        val status = "Internet Archive ha pedido una pausa temporal (HTTP 429). Mínimo no enviará más peticiones hasta aproximadamente las 20:05 (en unos 7 min). No se consume cuota de SerpApi."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 8,
                title = "Descarga de captura",
                status = WaybackTraceStatus.FAILED,
                detail = "HTTP 429: Internet Archive pidió frenar las consultas."
            )
        )

        assertEquals(status, buildWaybackDiagnosticSummary(status, rows))
    }

    @Test
    fun latestNoPausePrecheckOverridesStaleHttp429CountdownOnCacheOnlyRetry() {
        val staleStatus = "Internet Archive ha pedido una pausa temporal (HTTP 429). " +
            "Mínimo no enviará más peticiones hasta aproximadamente las 20:05 (en unos 7 min)."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 4,
                title = "Precontrol de pausa global y preparación de consulta",
                status = WaybackTraceStatus.PASSED,
                detail = "[19:51:31] Precontrol completado: no hay pausa global activa."
            ),
            WaybackDiagnosticEntry(
                step = 5,
                title = "Petición enviada al índice CDX",
                status = WaybackTraceStatus.SKIPPED,
                detail = "[19:51:31] No ejecutado: consulta CDX omitida por caché reciente."
            ),
            WaybackDiagnosticEntry(
                step = 7,
                title = "Fallback CDX alternativo / Availability API",
                status = WaybackTraceStatus.FAILED,
                detail = "[19:50:44] WAYBACK_RATE_LIMITED: HTTP 429 de un intento anterior."
            )
        )

        val summary = buildWaybackDiagnosticSummary(staleStatus, rows)
        assertTrue(summary.contains("no había una pausa global activa", ignoreCase = true))
        assertTrue(summary.contains("intento anterior", ignoreCase = true))
        assertFalse("A stale countdown must not be shown as a currently active pause",
            summary.contains("en unos 7 min", ignoreCase = true))
    }

    @Test
    fun stalePauseHeadlineWithoutCountdownDoesNotOverrideCurrentCacheOnlyStatus() {
        val staleStatus =
            "Pausa temporal de Internet Archive (HTTP 429). Mínimo ha detenido las consultas; no implica que falten capturas."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 4,
                title = "Precontrol de pausa global y preparación de consulta",
                status = WaybackTraceStatus.PASSED,
                detail = "[19:51:31] Precontrol completado: no hay pausa global activa."
            ),
            WaybackDiagnosticEntry(
                step = 5,
                title = "Petición enviada al índice CDX",
                status = WaybackTraceStatus.SKIPPED,
                detail = "[19:51:31] No ejecutado: consulta CDX omitida por caché reciente."
            ),
            WaybackDiagnosticEntry(
                step = 7,
                title = "Fallback CDX alternativo / Availability API",
                status = WaybackTraceStatus.FAILED,
                detail = "[19:50:44] WAYBACK_RATE_LIMITED: HTTP 429 de un intento anterior."
            )
        )

        val summary = buildWaybackDiagnosticSummary(staleStatus, rows)
        assertTrue(summary.contains("no había una pausa global activa", ignoreCase = true))
        assertTrue(summary.contains("omitió por caché reciente", ignoreCase = true))
        assertFalse(
            "A stale pause headline without a retry clock must not look like a current pause",
            summary.contains("Pausa temporal de Internet Archive", ignoreCase = true)
        )
    }

    @Test
    fun oldRateLimitTraceDoesNotClaimThatThePauseIsStillActiveWhenStatusShowsCache() {
        val status = "Estas consultas gratuitas ya se comprobaron recientemente. La caché evita repetir búsquedas completadas sin resultados útiles."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 8,
                title = "Descarga de captura",
                status = WaybackTraceStatus.FAILED,
                detail = "HTTP 429: Internet Archive pidió frenar las consultas."
            )
        )

        val summary = buildWaybackDiagnosticSummary(status, rows)
        assertTrue(summary.contains("no confirma que la pausa siga activa", ignoreCase = true))
        assertTrue(summary.contains(status))
        assertFalse(summary.startsWith("Pausa temporal de Internet Archive", ignoreCase = true))
    }
}
