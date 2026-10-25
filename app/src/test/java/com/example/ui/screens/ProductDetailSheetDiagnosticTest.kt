package com.example.ui.screens

import com.example.data.remote.WaybackTraceStatus
import com.example.ui.viewmodel.WaybackDiagnosticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductDetailSheetDiagnosticTest {
    @Test
    fun exportedWaybackReportIncludesAllPhasesCaptureAttemptsAndSanitizesUrlsAndHtml() {
        val entries = listOf(
            WaybackDiagnosticEntry(
                step = 5,
                title = "Petición enviada al índice CDX",
                status = WaybackTraceStatus.FAILED,
                detail = "[12:00:04] SNAPSHOT_STREAM_RESET al consultar https://shop.example/product?token=secret-value",
                durationMs = 812
            ),
            WaybackDiagnosticEntry(
                step = 22,
                title = "Decisión del extractor y causa de rechazo del precio",
                status = WaybackTraceStatus.FAILED,
                detail = "Resultado del extractor: causa=ARCHIVE_PRICE_METADATA_MISSING"
            ),
            WaybackDiagnosticEntry(
                step = 0,
                title = "captura 1 de 1 · id abcdefgh · fecha 20250101120000 · ruta CDX_URL_PREFIX · tienda shop.example · Descarga HTML",
                status = WaybackTraceStatus.FAILED,
                detail = "[12:00:05] SNAPSHOT_STREAM_RESET; detalle oculto",
                isCaptureDetail = true,
                captureKey = "capture-test"
            ),
            WaybackDiagnosticEntry(
                step = 14,
                title = "Evaluación HTML de capturas",
                status = WaybackTraceStatus.FAILED,
                detail = "<html><body>private archived page</body></html>"
            )
        )

        val report = buildWaybackDiagnosticExportReport(
            productTitle = "Anker Prime 27K Power Bank",
            freeHistoryStatus = "No se recuperó un precio histórico verificable",
            steps = entries,
            generatedAt = "2026-10-25 12:00:00 UTC",
            appVersion = "1.6.1400",
            androidVersion = "Android 17 (API 37)"
        )

        assertTrue(report.contains("INFORME COMPLETO DE DIAGNÓSTICO"))
        assertTrue(report.contains("Versión de Mínimo: 1.6.1400"))
        assertTrue(report.contains("FASES GENERALES (1–22)"))
        assertTrue(report.contains("22. Decisión del extractor y causa de rechazo del precio — FALLÓ"))
        assertTrue(report.contains("ARCHIVE_PRICE_METADATA_MISSING"))
        assertTrue(report.contains("DETALLE DE CAPTURAS E INTENTOS"))
        assertTrue(report.contains("SNAPSHOT_STREAM_RESET"))
        assertTrue(report.contains("captura 1 de 1"))
        assertTrue(report.contains("1. SIN REGISTRO"))
        assertTrue(report.contains("14. Evaluación HTML de capturas — FALLÓ"))
        assertTrue(report.contains("[contenido HTML ocultado]"))
        assertTrue(report.contains("[URL ocultada]"))
        assertFalse(report.contains("https://shop.example"))
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("private archived page"))
    }

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
