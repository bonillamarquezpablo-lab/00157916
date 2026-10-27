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
        assertTrue(report.contains("FASES GENERALES (1–30)"))
        assertTrue(report.contains("22. Decisión del extractor y causa de rechazo del precio — FALLÓ"))
        assertTrue(report.contains("ARCHIVE_PRICE_METADATA_MISSING"))
        assertTrue(report.contains("DETALLE DE CAPTURAS E INTENTOS"))
        assertTrue(report.contains("SNAPSHOT_STREAM_RESET"))
        assertTrue(report.contains("captura 1 de 1"))
        assertTrue(report.contains("1. SIN REGISTRO"))
        assertTrue(report.contains("30. SIN REGISTRO"))
        assertTrue(report.contains("HALLAZGO PRINCIPAL Y SIGUIENTE PASO"))
        assertTrue(report.contains("Causa probable (orientativa):"))
        assertTrue(report.contains("Qué revisar ahora:"))
        assertTrue(report.contains("INDICIOS CLASIFICADOS POR TIPO"))
        assertTrue(report.contains("Formato del informe: v2 · 30 fases generales"))
        assertTrue(report.contains("14. Evaluación HTML de capturas — FALLÓ"))
        assertTrue(report.contains("[contenido HTML ocultado]"))
        assertTrue(report.contains("[URL ocultada]"))
        assertFalse(report.contains("https://shop.example"))
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("private archived page"))
    }

    @Test
    fun reportPrioritizesIdentityAndPersistenceRejectionWithAConcreteNextStep() {
        val entries = listOf(
            WaybackDiagnosticEntry(
                step = 19,
                title = "Guardado del histórico en la base de datos local",
                status = WaybackTraceStatus.FAILED,
                detail = "Capturas rechazadas=4; motivos=ORIGINAL_URL_NOT_HTTPS=1, PRODUCT_IDENTITY_MISMATCH=3."
            ),
            WaybackDiagnosticEntry(
                step = 23,
                title = "Identidad del producto y vínculo URL comprobados",
                status = WaybackTraceStatus.FAILED,
                detail = "Precios extraídos=4; rechazos relacionados con identidad/URL=3."
            ),
            WaybackDiagnosticEntry(
                step = 0,
                title = "captura 1 de 4 · id abcdefgh · fecha 20221012120000 · ruta CDX_URL_PREFIX · tienda shop.example · Guardado",
                status = WaybackTraceStatus.FAILED,
                detail = "PRODUCT_IDENTITY_MISMATCH; URL https://shop.example/item?sku=private-sku",
                isCaptureDetail = true,
                captureKey = "identity-test"
            )
        )

        val finding = buildWaybackDiagnosticFinding("No se añadió histórico nuevo", entries)
        val report = buildWaybackDiagnosticExportReport(
            productTitle = "Producto de prueba",
            freeHistoryStatus = "No se añadió histórico nuevo",
            steps = entries,
            generatedAt = "2026-10-27 12:00:00 UTC",
            appVersion = "1.7.test",
            androidVersion = "Android 17 (API 37)"
        )

        assertTrue(finding.probableCause.contains("identidad/URL"))
        assertTrue(finding.recommendedNextStep.contains("fases 23 y 25"))
        assertTrue(report.contains("PRODUCT_IDENTITY_MISMATCH"))
        assertTrue(report.contains("Qué revisar ahora:"))
        assertFalse(report.contains("https://shop.example"))
        assertFalse(report.contains("private-sku"))
    }

    @Test
    fun timeoutFindingDoesNotClaimThatAnEmptyArchiveWasProven() {
        val entries = listOf(
            WaybackDiagnosticEntry(
                step = 6,
                title = "Respuesta CDX",
                status = WaybackTraceStatus.FAILED,
                detail = "CDX_URL_PREFIX_TIMEOUT; CDX_URL_PREFIX_YEAR_TIMEOUT."
            ),
            WaybackDiagnosticEntry(
                step = 11,
                title = "Descarga HTML de capturas",
                status = WaybackTraceStatus.FAILED,
                detail = "SNAPSHOT_STREAM_RESET."
            )
        )
        val finding = buildWaybackDiagnosticFinding("No se recuperó precio", entries)
        assertTrue(finding.probableCause.contains("red o de tiempo de espera"))
        assertTrue(finding.recommendedNextStep.contains("Un timeout", ignoreCase = true) ||
            finding.recommendedNextStep.contains("tiempo", ignoreCase = true))
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
    fun mementoRateLimitIsReportedSeparatelyFromWaybackGlobalPause() {
        val status = "Arquivo.pt (Memento) devolvió HTTP 429. Mínimo pausará las consultas a esa fuente hasta aproximadamente las 03:55 (en unos 14 min). Esta pausa es independiente de la pausa global de Internet Archive/Wayback."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 4,
                title = "Precontrol de pausa global y preparación de consulta",
                status = WaybackTraceStatus.PASSED,
                detail = "[03:40:07] Precontrol completado: no hay pausa global activa."
            ),
            WaybackDiagnosticEntry(
                step = 7,
                title = "Fallback CDX alternativo / Availability API",
                status = WaybackTraceStatus.PASSED,
                detail = "[03:40:42] Memento recuperó 2 precios archivados y los envió al guardado. Arquivo.pt también devolvió HTTP 429 y activó su pausa independiente de Memento; esto no implica una pausa global de Wayback."
            )
        )

        val summary = buildWaybackDiagnosticSummary(status, rows)
        assertTrue(summary.contains("Se detectó HTTP 429 en la alternativa Memento/Arquivo.pt"))
        assertTrue(summary.contains("independiente", ignoreCase = true))
        assertTrue(summary.contains("no demuestra que esté activa", ignoreCase = true))
        assertFalse(summary.contains("el HTTP 429 del resumen pertenece a un intento anterior", ignoreCase = true))
    }

    @Test
    fun explanatoryHttp429MentionDoesNotMasqueradeAsAnActualResponse() {
        val status = "Se encontraron capturas con precios previos del producto seleccionado, pero ya existían esos registros diarios y no se añadieron puntos nuevos. Diagnóstico Wayback: MEMENTO_RECOVERED_QUOTES."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 4,
                title = "Precontrol de pausa global y preparación de consulta",
                status = WaybackTraceStatus.PASSED,
                detail = "[13:15:07] Precontrol completado: no hay pausa global activa."
            ),
            WaybackDiagnosticEntry(
                step = 6,
                title = "Respuesta CDX",
                status = WaybackTraceStatus.PASSED,
                detail = "[13:15:40] CDX_URL_PREFIX_TIMEOUT · 10000 ms; [13:15:46] CDX_URL_PREFIX_YEAR_TIMEOUT · 6001 ms."
            ),
            WaybackDiagnosticEntry(
                step = 7,
                title = "Fallback CDX alternativo / Availability API",
                status = WaybackTraceStatus.SKIPPED,
                detail = "[13:15:40] La consulta global por prefijo agotó el tiempo; se revisará el año 2022. El control global de ritmo se mantiene; HTTP 429 detiene los reintentos."
            )
        )

        val summary = buildWaybackDiagnosticSummary(status, rows)
        assertTrue(summary.contains("El precontrol confirma que no hay pausa global activa"))
        assertFalse(summary.contains("Se recibió HTTP 429 de Internet Archive/Wayback durante esta búsqueda"))
        assertFalse(summary.contains("El HTTP 429 del resumen pertenece a un intento anterior"))
    }

    @Test
    fun staleStatusHeadlineCannotOverrideFreshNoPausePrecheckWhenOnlyCdxtimeoutsWereSeen() {
        val staleStatus =
            "Se recibió HTTP 429 de Internet Archive/Wayback durante esta búsqueda, pero el estado actual no confirma que la pausa siga activa."
        val rows = listOf(
            WaybackDiagnosticEntry(
                step = 4,
                title = "Precontrol de pausa global y preparación de consulta",
                status = WaybackTraceStatus.PASSED,
                detail = "[13:15:07] Precontrol completado: no hay pausa global activa."
            ),
            WaybackDiagnosticEntry(
                step = 6,
                title = "Respuesta CDX",
                status = WaybackTraceStatus.FAILED,
                detail = "[13:15:40] CDX_URL_PREFIX_TIMEOUT · 10000 ms; [13:15:46] CDX_URL_PREFIX_YEAR_TIMEOUT · 6001 ms."
            ),
            WaybackDiagnosticEntry(
                step = 7,
                title = "Fallback CDX alternativo / Availability API",
                status = WaybackTraceStatus.SKIPPED,
                detail = "[13:15:40] La consulta por prefijo agotó el tiempo. El control global de ritmo se mantiene; cualquier respuesta de límite detiene los reintentos."
            )
        )

        val summary = buildWaybackDiagnosticSummary(staleStatus, rows)
        assertTrue(summary.contains("no había una pausa global activa"))
        assertTrue(summary.contains("HTTP 429 del resumen pertenece a un intento anterior"))
        assertFalse(summary.contains("Se recibió HTTP 429 de Internet Archive/Wayback durante esta búsqueda"))
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
