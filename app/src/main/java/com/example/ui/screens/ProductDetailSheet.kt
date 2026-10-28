package com.example.ui.screens

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.example.security.safeExternalHttpsUrl
import com.example.security.openExternalHttpsUrl
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.summarizePriceHistory
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.remote.WaybackTraceStatus
import com.example.data.remote.extractDirectRetailerUrl
import com.example.ui.viewmodel.WaybackDiagnosticEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.ui.components.DealVerdictBadge
import com.example.ui.components.HeroPriceBadge
import com.example.ui.components.PriceHistoryChart
import com.example.ui.components.ProductImageView
import com.example.ui.components.StoreComparisonView

/**
 * Summarizes the current Wayback diagnostic without presenting an old HTTP 429 event as an
 * indefinitely active pause. An active cooldown is explicit only when the current UI status
 * includes its computed retry clock and remaining minutes.
 */
internal fun buildWaybackDiagnosticSummary(
    freeHistoryStatus: String?,
    steps: List<WaybackDiagnosticEntry>
): String {
    val statusText = freeHistoryStatus.orEmpty().trim()
    val precheck = steps.lastOrNull {
        it.step == 4 && it.status in setOf(WaybackTraceStatus.PASSED, WaybackTraceStatus.SKIPPED)
    }
    val precheckConfirmsNoPause = precheck?.status == WaybackTraceStatus.PASSED &&
        precheck.detail.contains("no hay pausa global activa", ignoreCase = true)
    val precheckConfirmsPause = precheck?.status == WaybackTraceStatus.SKIPPED &&
        precheck.detail.contains("pausa global", ignoreCase = true)

    fun diagnosticSeconds(detailLine: String): Int? =
        Regex("""\[(\d{2}):(\d{2}):(\d{2})]""")
            .find(detailLine)
            ?.destructured
            ?.let { (hour, minute, second) ->
                hour.toInt() * 3600 + minute.toInt() * 60 + second.toInt()
            }

    fun isMementoRateLimitLine(entry: WaybackDiagnosticEntry, detailLine: String): Boolean {
        if (detailLine.contains("MEMENTO_RATE_LIMITED", ignoreCase = true)) return true
        val namesMemento = detailLine.contains("Memento", ignoreCase = true) ||
            detailLine.contains("Arquivo.pt", ignoreCase = true)
        val namesRateLimit = detailLine.contains("HTTP 429", ignoreCase = true) ||
            detailLine.contains("limitar", ignoreCase = true) ||
            detailLine.contains("pausa propia", ignoreCase = true)
        return namesMemento && namesRateLimit &&
            (entry.step == 7 || detailLine.contains("MEMENTO", ignoreCase = true))
    }

    fun isWaybackRateLimitLine(entry: WaybackDiagnosticEntry, detailLine: String): Boolean {
        if (isMementoRateLimitLine(entry, detailLine)) return false
        // This is an explicit precheck result, not an HTTP error event.
        if (detailLine.contains("no hay pausa global activa", ignoreCase = true) ||
            detailLine.contains("la pausa global no está activa", ignoreCase = true)
        ) return false
        val normalizedLine = detailLine.lowercase(Locale.ROOT)
        // The trace can retain explanatory text or a previous attempt. Neither proves that this
        // run received HTTP 429 from Wayback.
        if (normalizedLine.contains("intento anterior") ||
            normalizedLine.contains("detiene los reintentos") ||
            normalizedLine.contains("cualquier respuesta de límite") ||
            normalizedLine.contains("si recibe") ||
            normalizedLine.contains("cuando se reciba")
        ) return false
        val explicitHttp429 = Regex("""(?i)\bHTTP\s+429\s*[:;)]""")
            .containsMatchIn(detailLine) ||
            detailLine.contains("devolvió HTTP 429", ignoreCase = true) ||
            detailLine.contains("recibió HTTP 429", ignoreCase = true) ||
            Regex("""(?i)\b(?:CDX_[A-Z0-9_]+|ARCHIVE_AVAILABILITY_API)_HTTP_429\b""")
                .containsMatchIn(detailLine)
        return detailLine.contains("WAYBACK_RATE_LIMITED", ignoreCase = true) || explicitHttp429
    }

    val precheckSeconds = precheck?.let { diagnosticSeconds(it.detail) }
    val currentTraceMementoRateLimit = steps.any { entry ->
        entry.detail.lines().any { detailLine -> isMementoRateLimitLine(entry, detailLine) }
    }
    val currentTraceRateLimit = steps.any { entry ->
        entry.step != 4 && entry.detail.lines().any { detailLine ->
            if (!isWaybackRateLimitLine(entry, detailLine)) {
                false
            } else {
                val eventSeconds = diagnosticSeconds(detailLine)
                when {
                    precheckSeconds != null && eventSeconds != null ->
                        eventSeconds > precheckSeconds ||
                            // Reports use local HH:mm:ss. A short run can cross midnight.
                            (precheckSeconds - eventSeconds > 12 * 60 * 60)
                    precheckConfirmsNoPause -> false
                    else -> true
                }
            }
        }
    }
    val hasActivePauseCountdown =
        statusText.contains("HTTP 429", ignoreCase = true) &&
            !statusText.contains("Memento", ignoreCase = true) &&
            !statusText.contains("Arquivo.pt", ignoreCase = true) &&
            statusText.contains("hasta aproximadamente", ignoreCase = true) &&
            Regex("""(?i)\ben unos \d+\s+min\b""").containsMatchIn(statusText)
    val statusTextMentionsMemento429 =
        statusText.contains("HTTP 429", ignoreCase = true) &&
            (statusText.contains("Memento", ignoreCase = true) ||
                statusText.contains("Arquivo.pt", ignoreCase = true))
    val statusRepeatsStaleRateLimit = statusText.contains("HTTP 429", ignoreCase = true) ||
        statusText.contains("pausa temporal", ignoreCase = true) ||
        statusText.contains("internet archive pidió frenar", ignoreCase = true)
    val currentRunSkippedByCache = steps.any { entry ->
        entry.step == 5 &&
            entry.status == WaybackTraceStatus.SKIPPED &&
            entry.detail.contains("caché reciente", ignoreCase = true)
    }
    val activeStep = steps.firstOrNull {
        it.isCaptureDetail && it.status == WaybackTraceStatus.RUNNING
    } ?: steps.firstOrNull { it.status == WaybackTraceStatus.RUNNING }
    val statusTextHasExplicit429 =
        statusText.contains("WAYBACK_RATE_LIMITED", ignoreCase = true) ||
            Regex("""(?i)\bHTTP\s+429\s*[:;)]""").containsMatchIn(statusText) ||
            statusText.contains("devolvió HTTP 429", ignoreCase = true) ||
            statusText.contains("recibió HTTP 429", ignoreCase = true)
    val rateLimitReported = statusTextMentionsMemento429 || statusTextHasExplicit429 ||
        steps.any { entry ->
            entry.detail.lines().any { detailLine ->
                isMementoRateLimitLine(entry, detailLine) || isWaybackRateLimitLine(entry, detailLine)
            }
        }
    val firstFailedCapture = steps.firstOrNull {
        it.isCaptureDetail && it.status == WaybackTraceStatus.FAILED
    }
    val firstFailedStep = firstFailedCapture ?: steps.firstOrNull {
        it.status == WaybackTraceStatus.FAILED
    }

    return when {
        (currentTraceMementoRateLimit || statusTextMentionsMemento429) && !currentTraceRateLimit -> {
            val currentStatus = statusText.takeIf { it.isNotBlank() }
                ?: "No hay una pausa global de Wayback confirmada en este informe."
            "Se detectó HTTP 429 en la alternativa Memento/Arquivo.pt. Esta limitación es independiente " +
                "de la pausa global de Wayback y no demuestra que esté activa. Estado actual: " +
                currentStatus
        }
        // If this run's current precheck says the pause is gone and this run has not seen a
        // fresh Wayback 429, don't show a countdown copied from an earlier attempt.
        precheckConfirmsNoPause && !currentTraceRateLimit -> {
            val currentStatus = statusText.takeIf {
                it.isNotBlank() && !hasActivePauseCountdown && !statusRepeatsStaleRateLimit
            } ?: if (currentRunSkippedByCache) {
                "La pausa global no está activa; esta consulta se omitió por caché reciente, no por un bloqueo vigente de Internet Archive."
            } else {
                "La pausa global no está activa en esta consulta."
            }
            if (rateLimitReported) {
                "El precontrol de esta consulta confirma que no había una pausa global activa. " +
                    "El HTTP 429 del resumen pertenece a un intento anterior; no significa que la pausa siga activa. " +
                    "Estado actual: " + currentStatus
            } else {
                "El precontrol confirma que no hay pausa global activa. Estado actual: " + currentStatus
            }
        }
        hasActivePauseCountdown -> statusText
        precheckConfirmsPause -> statusText.takeIf { it.isNotBlank() }
            ?: "El precontrol confirmó que la pausa global está activa; no se enviaron peticiones."
        currentTraceRateLimit -> {
            val cause = firstFailedCapture?.let {
                " Fallo individual registrado: " + it.title + "."
            }.orEmpty()
            val currentStatus = statusText.takeIf { it.isNotBlank() }
                ?.let { " Estado actual: " + it }
                ?: " Este informe no contiene una hora de reanudación confirmada."
            "Se recibió HTTP 429 de Internet Archive/Wayback durante esta búsqueda, pero el estado actual no confirma que la pausa siga activa." + cause + currentStatus
        }
        activeStep != null -> if (activeStep.isCaptureDetail) {
            "Captura en curso: " + activeStep.title
        } else {
            "Fase en curso: " + activeStep.step + ". " + activeStep.title
        }
        rateLimitReported -> {
            val cause = firstFailedCapture?.let {
                " Fallo individual registrado: " + it.title + "."
            }.orEmpty()
            val currentStatus = statusText.takeIf { it.isNotBlank() }
                ?.let { " Estado actual: " + it }
                ?: " Este informe no contiene una hora de reanudación confirmada."
            "Se recibió HTTP 429 durante esta búsqueda, pero el estado actual no confirma que la pausa siga activa." + cause + currentStatus
        }
        firstFailedStep != null -> if (firstFailedStep.isCaptureDetail) {
            "Fallo de captura: " + firstFailedStep.title
        } else {
            "Primer fallo detectado: paso " + firstFailedStep.step + ". " + firstFailedStep.title
        }
        steps.any { it.status == WaybackTraceStatus.PASSED } ->
            "No hay una fase marcada como fallida en este momento."
        else -> "Esperando resultado del diagnóstico."
    }
}

/**
 * Builds a copy/paste-ready report from the diagnostics already collected during one lookup.
 * It performs no network requests. URLs, query strings and accidental raw HTML are removed.
 */
internal data class WaybackDiagnosticFinding(
    val probableCause: String,
    val recommendedNextStep: String
)

/**
 * Converts the raw trace into a readable first diagnosis. This is a heuristic, not a claim that
 * the suspected cause is proven; the cited phase/code remains the evidence to inspect.
 */
internal fun buildWaybackDiagnosticFinding(
    freeHistoryStatus: String?,
    steps: List<WaybackDiagnosticEntry>
): WaybackDiagnosticFinding {
    val allText = buildString {
        append(freeHistoryStatus.orEmpty()).append('\n')
        steps.forEach { entry ->
            append(entry.title).append('\n')
            append(entry.detail).append('\n')
        }
    }
    val identityCodes = listOf(
        "PRODUCT_IDENTITY_MISMATCH",
        "ORIGINAL_URL_NOT_HTTPS",
        "ARCHIVE_TARGET_URL_MISMATCH",
        "QUOTE_ORIGINAL_URL_MISMATCH",
        "TARGET_PRODUCT_ID_NOT_MATCHING_EXPECTED_TITLE",
        "ORIGINAL_URL_NOT_CANONICAL"
    )
    val valueCodes = listOf(
        "INVALID_PRICE_CURRENCY_OR_DATE",
        "IMPLAUSIBLE_PRODUCT_PRICE",
        "HISTORICAL_EXCHANGE_RATE_MISSING"
    )
    val phase19 = steps.firstOrNull { it.step == 19 && !it.isCaptureDetail }
    val rejectedCount = Regex("""(?i)capturas rechazadas\s*=\s*(\d+)""")
        .find(allText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    return when {
        identityCodes.any { allText.contains(it, ignoreCase = true) } ->
            WaybackDiagnosticFinding(
                probableCause = "Hay capturas o precios que no pasan la comprobación de identidad/URL. La descarga puede ser correcta, pero el guardado los rechaza para evitar mezclar modelos o variantes.",
                recommendedNextStep = "Revisar las fases 23 y 25, y el registro individual de cada captura. Comparar título esperado, título archivado, URL original y confirmación de variante. No desactivar el filtro de identidad."
            )
        phase19?.status == WaybackTraceStatus.FAILED && rejectedCount > 0 ->
            WaybackDiagnosticFinding(
                probableCause = "El proceso llegó a la persistencia, pero la base local no aceptó todos los precios recuperados. El motivo concreto debe aparecer en los códigos de rechazo de la fase 19 y en la fase 25.",
                recommendedNextStep = "Abrir las fases 19, 25, 26 y 28. Comprobar si cada precio terminó como nuevo, duplicado válido o rechazado con un motivo; si los contadores no cuadran, corregir ese camino."
            )
        valueCodes.any { allText.contains(it, ignoreCase = true) } ->
            WaybackDiagnosticFinding(
                probableCause = "Se ha detectado un problema de datos: precio, moneda, fecha histórica, cambio de divisa o metadatos que el extractor no pudo verificar.",
                recommendedNextStep = "Revisar las fases 17, 21, 22 y 24 junto al detalle de la captura. Confirmar qué campo falta o no supera la validación antes de modificar el extractor."
            )
        allText.contains("WAYBACK_RATE_LIMITED", ignoreCase = true) ||
            Regex("""(?i)\bHTTP\s*429\b""").containsMatchIn(allText) ->
            WaybackDiagnosticFinding(
                probableCause = "El servicio de archivo aplicó un límite temporal de consultas. Esto no demuestra que no existan capturas.",
                recommendedNextStep = "Revisar las fases 4–8 para distinguir una pausa activa de un 429 antiguo, respetar el tiempo de espera y usar los resultados parciales ya recuperados."
            )
        Regex("""(?i)(TIMEOUT|TIMED_OUT|SOCKET_TIMEOUT|DNS_FAILURE|SNAPSHOT_STREAM_RESET|CONNECTION_RESET|CDX_\w+_TIMEOUT)""")
            .containsMatchIn(allText) ->
            WaybackDiagnosticFinding(
                probableCause = "Hay indicios de un problema de red o de tiempo de espera al consultar el índice o descargar una captura. Un timeout no equivale a que el archivo esté vacío.",
                recommendedNextStep = "Revisar las fases 5–14 y el detalle de cada captura: proveedor, ruta consultada, tiempo de espera y etapa exacta que no terminó. Evitar repetir consultas amplias si ya agotaron el tiempo."
            )
        allText.contains("ARCHIVE_PRICE_METADATA_MISSING", ignoreCase = true) ||
            allText.contains("PRICE_NOT_FOUND", ignoreCase = true) ||
            allText.contains("EXTRACTOR_NO_PRICE", ignoreCase = true) ->
            WaybackDiagnosticFinding(
                probableCause = "La página archivada se abrió, pero el extractor no encontró un precio verificable en el formato disponible.",
                recommendedNextStep = "Revisar las fases 21 y 22 y la fecha/ruta de esa captura. Diferenciar precio ausente en el HTML de un formato nuevo que el extractor aún no reconoce."
            )
        steps.any { it.step in 5..14 && it.status == WaybackTraceStatus.FAILED } ->
            WaybackDiagnosticFinding(
                probableCause = "El fallo parece estar antes del guardado: consulta del índice, selección de capturas, descarga o validación de la página archivada.",
                recommendedNextStep = "Localizar la primera fase fallida entre 5 y 14 y seguir el mismo identificador de captura. No cambiar la persistencia hasta confirmar que el extractor produjo un precio válido."
            )
        steps.any { it.step == 19 && it.status == WaybackTraceStatus.PASSED } ->
            WaybackDiagnosticFinding(
                probableCause = "La persistencia comunicó un resultado correcto; el siguiente punto a revisar es la actualización de la ficha/gráfica y el estado final.",
                recommendedNextStep = "Comparar las fases 19, 26, 28 y 29. Si hay puntos nuevos pero no aparecen en la ficha, revisar la lectura de Room y la actualización de interfaz."
            )
        else ->
            WaybackDiagnosticFinding(
                probableCause = "Con los datos registrados no se puede aislar una causa única. Puede que no hubiera capturas, que no se extrajera ningún precio o que la consulta se omitiera.",
                recommendedNextStep = "Buscar la primera fase fallida y el primer código de causa; si todas aparecen omitidas, comprobar por qué no arrancó la consulta. Compartir el informe completo, no solo el resumen."
            )
    }
}

internal fun buildWaybackDiagnosticExportReport(
    productTitle: String,
    freeHistoryStatus: String?,
    steps: List<WaybackDiagnosticEntry>,
    generatedAt: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).format(Date()),
    appVersion: String = "desconocida",
    androidVersion: String = "desconocida"
): String {
    fun statusLabel(entry: WaybackDiagnosticEntry): String = when (entry.status) {
        WaybackTraceStatus.PENDING -> "PENDIENTE"
        WaybackTraceStatus.RUNNING -> "EN CURSO"
        WaybackTraceStatus.PASSED -> "SUPERADO"
        WaybackTraceStatus.FAILED -> "FALLÓ"
        WaybackTraceStatus.SKIPPED -> {
            val detail = entry.detail.lowercase(Locale.ROOT)
            when {
                detail.contains("caché") || detail.contains("cache") -> "OMITIDO POR CACHÉ"
                detail.contains("pausa") || detail.contains("cooldown") -> "OMITIDO POR PAUSA"
                detail.contains("no ejecutado") || detail.contains("la cadena terminó antes") ->
                    "NO EJECUTADO"
                else -> "OMITIDO / NO APLICABA"
            }
        }
    }

    fun safeDetail(raw: String): String {
        if (raw.isBlank()) return "(sin detalle)"
        if (Regex("""(?is)<\s*(?:!doctype|html|head|body|script)\b""").containsMatchIn(raw)) {
            return "[contenido HTML ocultado]"
        }
        var safe = raw
            .replace(Regex("""(?i)https?://[^\s<>\"']+"""), "[URL ocultada]")
            .replace(Regex("""(?i)\b(?:cookie|authorization|access_token|refresh_token|api[_-]?key)\s*[:=]\s*[^\s,;]+"""), "[credencial ocultada]")
            .replace(Regex("""(?i)([?&][a-z0-9_.~-]+=)[^&\s]+""")) { match ->
                match.groupValues[1] + "[oculto]"
            }
            .replace(Regex("""(?is)<[^>]{1,300}>"""), "[etiqueta HTML]")
            .trim()
        if (safe.length > 1800) safe = safe.take(1800) + "… [detalle recortado]"
        return safe.ifBlank { "(detalle ocultado)" }
    }

    val phaseRows = steps.filterNot { it.isCaptureDetail }.associateBy { it.step }
    val captures = steps.filter { it.isCaptureDetail }
    val statuses = phaseRows.values.groupingBy { it.status }.eachCount()
    val captureStatuses = captures.groupingBy { it.status }.eachCount()
    val statusText = freeHistoryStatus?.trim().orEmpty().ifBlank { "No se registró un estado final." }
    val summary = buildWaybackDiagnosticSummary(freeHistoryStatus, steps)
    val finding = buildWaybackDiagnosticFinding(freeHistoryStatus, steps)
    val report = StringBuilder(12_000)
    report.appendLine("INFORME COMPLETO DE DIAGNÓSTICO — MÍNIMO / INTERNET ARCHIVE")
    report.appendLine("Generado: " + generatedAt)
    report.appendLine("Versión de Mínimo: " + appVersion)
    report.appendLine("Android: " + androidVersion)
    report.appendLine("Producto: " + safeDetail(productTitle.replace(Regex("""[\r\n]+"""), " ").trim().take(180)))
    report.appendLine("Resultado comunicado por la app: " + safeDetail(statusText))
    report.appendLine("Resumen del diagnóstico: " + safeDetail(summary))
    report.appendLine("Formato del informe: v2 · 30 fases generales + detalle por captura")
    report.appendLine()
    report.appendLine("HALLAZGO PRINCIPAL Y SIGUIENTE PASO")
    report.appendLine("Causa probable (orientativa): " + safeDetail(finding.probableCause))
    report.appendLine("Qué revisar ahora: " + safeDetail(finding.recommendedNextStep))
    val firstFailedPhase = phaseRows.values
        .filter { it.status == WaybackTraceStatus.FAILED }
        .minByOrNull { it.step }
    val firstFailedCapture = captures.firstOrNull { it.status == WaybackTraceStatus.FAILED }
    report.appendLine("Primera fase fallida: " + (firstFailedPhase?.let {
        "${it.step}. ${safeDetail(it.title)}"
    } ?: "ninguna registrada"))
    report.appendLine("Primera captura fallida: " + (firstFailedCapture?.let {
        safeDetail(it.title)
    } ?: "ninguna registrada"))
    report.appendLine(
        "Capturas por estado: ${captures.size} total; ${captureStatuses[WaybackTraceStatus.PASSED] ?: 0} superadas; " +
            "${captureStatuses[WaybackTraceStatus.FAILED] ?: 0} fallidas; " +
            "${captureStatuses[WaybackTraceStatus.SKIPPED] ?: 0} omitidas; " +
            "${captureStatuses[WaybackTraceStatus.RUNNING] ?: 0} en curso; " +
            "${captureStatuses[WaybackTraceStatus.PENDING] ?: 0} pendientes."
    )
    report.appendLine()
    report.appendLine("INDICIOS CLASIFICADOS POR TIPO")
    val categories = listOf(
        "Red, proveedor o límite de consultas" to Regex(
            """(?i)(HTTP\s*429|WAYBACK_RATE_LIMITED|MEMENTO_RATE_LIMITED|TIMEOUT|TIMED_OUT|SOCKET|DNS|SSL|CONNECTION|STREAM_RESET|CDX_\w+_TIMEOUT|NETWORK)"""
        ),
        "Identidad del producto o URL" to Regex(
            """(?i)(IDENTITY|PRODUCT_IDENTITY_MISMATCH|URL_\w*(MISMATCH|HTTPS|CANONICAL)|TARGET_PRODUCT|ORIGINAL_URL_NOT_HTTPS|ARCHIVE_TARGET_URL_MISMATCH|QUOTE_ORIGINAL_URL_MISMATCH)"""
        ),
        "Extracción, HTML o metadatos" to Regex(
            """(?i)(EXTRACTOR|EXTRACTION|HTML|METADATA|PARSER|DECODE|PRICE_NOT_FOUND|NO_PRICE|ARCHIVE_PRICE_METADATA_MISSING|PROFILE)"""
        ),
        "Precio, moneda o fecha" to Regex(
            """(?i)(INVALID_PRICE|CURRENCY|IMPLAUSIBLE|EXCHANGE_RATE|TIMESTAMP|OBSERVED_AT|INVALID_DATE|EURO|EUR)"""
        ),
        "Guardado local, duplicados o contadores" to Regex(
            """(?i)(PERSIST|ROOM|DUPLICAT|GUARDADO|RECHAZ|INVALID_ROW|FILAS ANTIGUAS|CONTADORES|INSERTED|SAVED POINTS)"""
        ),
        "Caché, pausa o decisión de flujo" to Regex(
            """(?i)(CACHE_SKIPPED|CACHE|COOLDOWN|CIRCUIT|PAUSA GLOBAL|NO EJECUTADO|OMITID[AO]|FALLBACK)"""
        )
    )
    for ((categoryName, pattern) in categories) {
        val events = steps.flatMap { entry ->
            entry.detail.lines().filter { it.isNotBlank() && pattern.containsMatchIn(it) }.map { line ->
                "Fase ${entry.step} — ${safeDetail(entry.title)}: ${safeDetail(line)}"
            }
        }.distinct().take(8)
        report.appendLine(categoryName + ":")
        if (events.isEmpty()) report.appendLine("   Sin indicios registrados.")
        else events.forEach { report.appendLine("   " + it) }
    }
    report.appendLine()
    report.appendLine("RESULTADOS SEPARADOS POR SERVICIO")
    val serviceGroups: List<Pair<String, (WaybackDiagnosticEntry, String) -> Boolean>> = listOf(
        "Índice CDX de Internet Archive" to { entry, line ->
            !entry.isCaptureDetail &&
                (entry.title + " " + line).contains("CDX", ignoreCase = true) &&
                !(entry.title + " " + line).contains("Memento", ignoreCase = true)
        },
        "Availability API de archive.org" to { entry, line ->
            (entry.title + " " + line).contains("Availability", ignoreCase = true) ||
                line.contains("ARCHIVE_AVAILABILITY_API", ignoreCase = true)
        },
        "Descarga y validación de capturas Wayback" to { entry, line ->
            val combined = entry.title + " " + line
            (entry.isCaptureDetail || combined.contains("captura", ignoreCase = true) ||
                combined.contains("HTML", ignoreCase = true)) &&
                (!combined.contains("CDX", ignoreCase = true) || entry.isCaptureDetail) &&
                !combined.contains("Memento", ignoreCase = true) &&
                !combined.contains("Arquivo.pt", ignoreCase = true) &&
                !combined.contains("Availability", ignoreCase = true)
        },
        "Arquivo.pt / Memento" to { entry, line ->
            (entry.title + " " + line).contains("Memento", ignoreCase = true) ||
                (entry.title + " " + line).contains("Arquivo.pt", ignoreCase = true) ||
                line.contains("MEMENTO_", ignoreCase = true)
        },
        "Guardado local y actualización del gráfico" to { entry, line ->
            entry.step == 19 || entry.step == 20 ||
                (entry.title + " " + line).contains("gráfico", ignoreCase = true) ||
                (entry.title + " " + line).contains("persistencia", ignoreCase = true)
        }
    )
    for ((serviceName, matches) in serviceGroups) {
        report.appendLine()
        report.appendLine(serviceName)
        val serviceEvents = steps.flatMap { entry ->
            entry.detail.lines().filter { it.isNotBlank() && matches(entry, it) }.map { line ->
                "Fase ${entry.step} — " + safeDetail(entry.title) + ": " + safeDetail(line)
            }
        }.distinct().take(18)
        if (serviceEvents.isEmpty()) {
            report.appendLine("   No hay eventos etiquetados para este servicio en esta búsqueda.")
        } else {
            serviceEvents.forEach { report.appendLine("   " + it) }
        }
    }
    report.appendLine()
    report.appendLine(
        "Estado de fases: " + (statuses[WaybackTraceStatus.PASSED] ?: 0) + " superadas, " +
            (statuses[WaybackTraceStatus.FAILED] ?: 0) + " fallidas, " +
            (statuses[WaybackTraceStatus.SKIPPED] ?: 0) + " no ejecutadas, " +
            (statuses[WaybackTraceStatus.RUNNING] ?: 0) + " en curso, " +
            (statuses[WaybackTraceStatus.PENDING] ?: 0) + " pendientes."
    )
    report.appendLine()
    report.appendLine("FASES GENERALES (1–30)")
    for (step in 1..30) {
        val entry = phaseRows[step]
        report.appendLine()
        if (entry == null) {
            report.appendLine(step.toString() + ". SIN REGISTRO")
            continue
        }
        report.appendLine(step.toString() + ". " + safeDetail(entry.title) + " — " + statusLabel(entry))
        entry.durationMs?.let { report.appendLine("   Duración registrada: " + it + " ms") }
        val detailLines = entry.detail.lines().filter { it.isNotBlank() }
        if (detailLines.isEmpty()) {
            report.appendLine("   Detalle: (sin detalle)")
        } else {
            detailLines.forEach { report.appendLine("   " + safeDetail(it)) }
        }
    }
    report.appendLine()
    report.appendLine("DETALLE DE CAPTURAS E INTENTOS")
    report.appendLine("Registros individuales: " + captures.size)
    if (captures.isEmpty()) {
        report.appendLine("(No se generaron registros individuales de captura.)")
    } else {
        captures.forEachIndexed { index, entry ->
            report.appendLine()
            report.appendLine((index + 1).toString() + ". " + safeDetail(entry.title) + " — " + statusLabel(entry))
            entry.durationMs?.let { report.appendLine("   Duración registrada: " + it + " ms") }
            entry.detail.lines().filter { it.isNotBlank() }
                .forEach { report.appendLine("   " + safeDetail(it)) }
        }
    }
    report.appendLine()
    report.appendLine("PRIVACIDAD: se han omitido las URL completas, parámetros de URL y contenido HTML.")
    report.appendLine("NOTA: la causa probable es una ayuda de diagnóstico basada en los registros; confirma el código y la fase indicados antes de reparar.")
    report.appendLine("Este informe refleja los registros que la app conservó durante la búsqueda; no realiza otra consulta.")
    return report.toString().trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailSheet(
    product: Product,
    onDismiss: () -> Unit,
    onOpenSetAlert: () -> Unit,
    onCompareStores: () -> Unit = {},
    onSearchFreeHistory: () -> Unit = {},
    freeHistoryStatus: String? = null,
    isSearchingFreeHistory: Boolean = false,
    waybackDiagnosticSteps: List<WaybackDiagnosticEntry> = emptyList(),
    serpApiActionStatus: String? = null,
    isComparingStores: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showWaybackDiagnostics by remember(product.id) { mutableStateOf(false) }
    var showPriceEvidenceDetails by remember(product.id) { mutableStateOf(false) }
    var diagnosticReportCopyStatus by remember(product.id) { mutableStateOf("") }
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val spanishLocale = remember { Locale("es", "ES") }
    val observedAtFormatter = remember { SimpleDateFormat("dd/MM HH:mm", spanishLocale) }
    val savedDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", spanishLocale) }
    val liveHistoryPoints = remember(product.priceHistory) {
        product.priceHistory.filterNot { it.isExternalHistoryEvidence }
    }
    val archivedHistoryPoints = remember(product.priceHistory) {
        product.priceHistory.filter { it.isArchiveEvidence }
    }
    val googleInsightPoints = remember(product.priceHistory) {
        product.priceHistory.filter { it.isGoogleInsightEvidence }
    }
    // Display the minimum from all verified, dated observations (live and historical sources).
    // This card is informational only; current-price and deal calculations still use live history.
    val observedHistorySummary = remember(product.priceHistory) {
        summarizePriceHistory(product.priceHistory)
    }
    val observedMinimumPoint = observedHistorySummary.minimumObservedPoint
    val mementoHistoryPoints = remember(archivedHistoryPoints) {
        archivedHistoryPoints.filter {
            it.eventLabel?.contains("Memento", ignoreCase = true) == true ||
                it.eventLabel?.contains("Arquivo.pt", ignoreCase = true) == true
        }
    }
    val waybackHistoryPoints = remember(archivedHistoryPoints) {
        archivedHistoryPoints.filterNot {
            it.eventLabel?.contains("Memento", ignoreCase = true) == true ||
                it.eventLabel?.contains("Arquivo.pt", ignoreCase = true) == true
        }
    }
    val oldestWaybackDate = remember(waybackHistoryPoints, savedDateFormatter) {
        waybackHistoryPoints.minByOrNull { it.timestamp }?.let {
            savedDateFormatter.format(Date(it.timestamp))
        }
    }
    val oldestMementoDate = remember(mementoHistoryPoints, savedDateFormatter) {
        mementoHistoryPoints.minByOrNull { it.timestamp }?.let {
            savedDateFormatter.format(Date(it.timestamp))
        }
    }
    val oldestGoogleInsightDate = remember(googleInsightPoints, savedDateFormatter) {
        googleInsightPoints.minByOrNull { it.timestamp }?.let {
            savedDateFormatter.format(Date(it.timestamp))
        }
    }
    val historicalSourceSummary = remember(
        googleInsightPoints, archivedHistoryPoints, waybackHistoryPoints, mementoHistoryPoints,
        oldestWaybackDate, oldestMementoDate, oldestGoogleInsightDate
    ) {
        buildString {
            if (googleInsightPoints.isNotEmpty()) {
                append("Google Shopping: ${googleInsightPoints.size} puntos")
                oldestGoogleInsightDate?.let { append(" desde $it") }
                append(".")
            }
            if (waybackHistoryPoints.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                val convertedUsdPoints = waybackHistoryPoints.count {
                    it.eventLabel?.contains("USD→EUR convertido", ignoreCase = true) == true
                }
                append("Internet Archive/Wayback: ${waybackHistoryPoints.size} capturas")
                if (convertedUsdPoints > 0) append(" (incluye ${convertedUsdPoints} convertidas de USD a EUR)")
                oldestWaybackDate?.let { append(" desde $it") }
                append(".")
            }
            if (mementoHistoryPoints.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Arquivo.pt/Memento: ${mementoHistoryPoints.size} capturas")
                oldestMementoDate?.let { append(" desde $it") }
                append(".")
            }
            if (isEmpty()) {
                append("Aún no hay historial verificable. «Buscar histórico gratis» consulta capturas públicas sin gastar cuota de SerpApi, pero solo podrá reconstruir el precio si hay una captura con título y precio en euros verificables o un precio USD con cambio histórico verificable.")
            } else {
                append(" Puede haber huecos; stock, envío y precio final deben confirmarse en la tienda.")
            }
        }
    }
    // Historical archive/insight points must never be presented as the source for today's price.
    val latestLivePricePoint = remember(liveHistoryPoints) { liveHistoryPoints.maxByOrNull { it.timestamp } }
    val isDisplayingSavedArchivePrice =
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
            !product.isPriceVerified && product.savedHistoricalPrice != null
    val displayedPriceObservedAt = if (isDisplayingSavedArchivePrice) {
        product.savedHistoricalPriceObservedAt
    } else {
        product.priceObservedAt
    }
    val sharePrice = if (isDisplayingSavedArchivePrice) {
        product.savedHistoricalPrice ?: product.currentPrice
    } else {
        product.currentPrice
    }
    val historyDayKey = System.currentTimeMillis() / 86_400_000L
    val hasCurrentVerifiedHistory = remember(
        product,
        product.priceDisplayStatus,
        historyDayKey
    ) {
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
            product.hasSufficientHistory
    }
    val currentSourceLabel = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
        product.savedHistoricalPriceSource.takeIf { it.isNotBlank() } ?: "Historial archivado"
    } else if (product.isUnverifiedLiveListing) {
        "Precio listado en búsqueda pública"
    } else if (!product.isPriceVerified) {
        "Sin precio actual verificado"
    } else {
        product.priceSource.takeIf { it.isNotBlank() }
            ?: latestLivePricePoint?.eventLabel?.takeIf { it.isNotBlank() }
            ?: "Fuente no disponible"
    }
    val currentLinkType = if (!product.isPriceVerified) {
        "Sin oferta actual verificada"
    } else {
        product.offers.firstOrNull { it.url == product.storeUrl }?.linkType
            ?: latestLivePricePoint?.linkType
            ?: "directo"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("product_detail_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(product.currentStore.brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = product.currentStore.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = product.currentStore.brandColor
                        )
                    }

                    if (product.category != ProductCategory.ALL) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = product.category.displayName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = {
                            val shareText = when {
                                hasCurrentVerifiedHistory ->
                                    "Precio observado para ${product.title}:\\n" +
                                        "• Precio actual verificado: ${String.format(spanishLocale, "%.2f", product.currentPrice)} € en ${product.currentStore.name}\\n" +
                                        "• Mínimo observado: ${String.format(spanishLocale, "%.2f", product.athMinPrice)} € (${product.athMinDate} en ${product.athMinStore})\\n" +
                                        "• Diagnóstico: ${product.verdict.badgeText} - ${product.verdict.explanation}"
                                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT ->
                                    "Precio observado para ${product.title}: ${String.format(spanishLocale, "%.2f", product.currentPrice)} € en ${product.currentStore.name}. " +
                                        "Aún no hay cobertura histórica suficiente para afirmar un mínimo de largo plazo o un descuento real."
                                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                    "Último precio guardado para ${product.title}: ${String.format(spanishLocale, "%.2f", sharePrice)} €. " +
                                        (displayedPriceObservedAt?.let { "Fecha: ${savedDateFormatter.format(Date(it))}. " }.orEmpty()) +
                                        "Fuente: $currentSourceLabel. No confirma una oferta actual, stock ni envío."
                                else ->
                                    "Ficha sin precio verificado de Mínimo: ${product.title}.\\nNo hay una oferta actual validada ni un precio histórico confirmado que se pueda compartir."
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Auditoría de precio: ${product.title}")
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Compartir auditoría de precio"))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartir informe",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar"
                        )
                    }
                }
            }

            // Hero Product Photo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.large)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large)
            ) {
                ProductImageView(
                    product = product,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Fit,
                    showAthRibbon = product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth
                )
            }

            // Title
            Text(
                text = product.title,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Prominent price classification backed by previous saved observations.
            HeroPriceBadge(
                state = product.priceBadgeState,
                modifier = Modifier.fillMaxWidth()
            )

            // Existing analysis certificate.
            DealVerdictBadge(verdict = product.verdict, compact = false)

            if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showPriceEvidenceDetails = !showPriceEvidenceDetails }
                                .padding(vertical = 2.dp)
                                .testTag("price_evidence_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Precio de referencia · falta verificar",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (showPriceEvidenceDetails) "Ocultar" else "Detalles",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (showPriceEvidenceDetails) {
                            Text(
                                text = if (product.priceHistory.isNotEmpty()) {
                                    "El precio actual no está confirmado. Los puntos del gráfico son registros antiguos y no prueban el precio actual, el stock ni el envío."
                                } else {
                                    "Mínimo aún no ha confirmado un precio actual con una fuente y una fecha verificables. El histórico solo aparecerá si encontramos capturas con precios verificables."
                                },
                                modifier = Modifier.testTag("price_evidence_details"),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
            // Key Price Metrics Grid
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Stack current and historical metrics vertically. Side-by-side weighted
                    // columns still collapse under narrow bottom-sheet/tablet constraints and
                    // wrap labels one character per line, making the card appear broken.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = when (product.priceDisplayStatus) {
                                    com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT -> "Precio actual verificado"
                                    com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION -> "Precio guardado · pendiente de revalidación"
                                    com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING -> "Precio listado · sin confirmar"
                                    com.example.data.model.PriceDisplayStatus.DEMONSTRATION -> "Precio no disponible"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format(spanishLocale, "%.2f", if (!product.isPriceVerified && product.savedHistoricalPrice != null) product.savedHistoricalPrice else product.currentPrice)} €",
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                color = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            displayedPriceObservedAt?.let { observedAt ->
                                Text(
                                    text = if (isDisplayingSavedArchivePrice) {
                                        "Precio guardado el " + savedDateFormatter.format(Date(observedAt))
                                    } else if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                                        "Guardado " + observedAtFormatter.format(Date(observedAt))
                                    } else {
                                        "Consultado " + observedAtFormatter.format(Date(observedAt))
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showPriceEvidenceDetails = !showPriceEvidenceDetails }
                                    .padding(vertical = 3.dp)
                                    .testTag("price_evidence_toggle"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (product.isPriceVerified) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = when {
                                        isDisplayingSavedArchivePrice ->
                                            "Precio histórico guardado · no es una oferta actual"
                                        product.isPriceVerified ->
                                            "Precio verificado · confirma el importe final en tienda"
                                        product.isUnverifiedLiveListing ->
                                            "Oferta sin verificar · falta un enlace directo"
                                        else -> "Precio guardado · pendiente de comprobar"
                                    },
                                    modifier = Modifier.weight(1f),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (showPriceEvidenceDetails) "Ocultar" else "Detalles",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (showPriceEvidenceDetails) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 22.dp)
                                        .testTag("price_evidence_details"),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = "Fuente: $currentSourceLabel",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
                                        !isDisplayingSavedArchivePrice
                                    ) {
                                        Text(
                                            text = product.priceFreshnessLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    Text(
                                        text = when {
                                            isDisplayingSavedArchivePrice ->
                                                "Es un precio histórico guardado; no confirma una oferta actual, disponibilidad, envío ni stock."
                                            product.isUnverifiedLiveListing ->
                                                "El importe apareció en una búsqueda pública, pero falta un enlace directo verificable. Confirma vendedor, precio final, envío y stock."
                                            product.isPriceVerified ->
                                                "Confirma el precio final y el stock en la tienda."
                                            else ->
                                                "No hay una oferta actual validada. El histórico solo cuenta cuando las capturas aportan precio y fecha verificables."
                                        },
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (product.isPriceVerified) {
                                        Text(
                                            text = "Tipo de enlace: $currentLinkType",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // A historical minimum requires at least two distinct dated observations.
                        // Archive/Google-insight lows are shown as observed evidence, never as an
                        // all-time guarantee or as an input to the live-price deal verdict.
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            if (observedMinimumPoint != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (observedMinimumPoint.isExternalHistoryEvidence) {
                                            "MÍNIMO HISTÓRICO OBSERVADO"
                                        } else {
                                            "MÍNIMO OBSERVADO"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF059669)
                                    )
                                }
                                Text(
                                    text = "${String.format(spanishLocale, "%.2f", observedMinimumPoint.price)} €",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp,
                                    color = Color(0xFF059669)
                                )
                                Text(
                                    text = "${observedMinimumPoint.dateLabel} en ${observedMinimumPoint.storeName}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = if (product.priceHistory.isNotEmpty()) "Primer registro guardado" else "Historial en curso",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when {
                                        liveHistoryPoints.size == 1 -> "1 observación real · la gráfica ya está disponible"
                                        liveHistoryPoints.size > 1 -> "${liveHistoryPoints.size} fechas registradas · evolución en curso"
                                        else -> "Se necesitan observaciones reales para mostrar la evolución"
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (hasCurrentVerifiedHistory || (product.isOfficialPvpVerified && product.officialPvp > 0.0)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (hasCurrentVerifiedHistory) {
                                Column {
                                    Text(
                                        text = "Media de 90 días",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.avg90dPrice)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            if (product.isOfficialPvpVerified && product.officialPvp > 0.0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PVP de referencia",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.officialPvp)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }
                            }
                            if (product.isPriceFresh && product.isOfficialPvpVerified && product.officialPvp > product.currentPrice) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Diferencia frente al PVP",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.realSavingsAmount)} € (${product.realSavingsPercent}%)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF059669)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (product.isPriceVerified || product.priceHistory.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Historial anterior a Mínimo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        historicalSourceSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Only validated observations reach priceHistory. Show genuine archive captures
            // even when there is no verified current quote; never extrapolate missing dates.
            PriceHistoryChart(
                priceHistory = liveHistoryPoints,
                avg90dPrice = product.avg90dPrice,
                archiveHistory = archivedHistoryPoints,
                googleInsightHistory = googleInsightPoints
            )

            // Only show offers that came back from the latest provider query.
            if (product.offers.isNotEmpty()) {
                StoreComparisonView(
                    offers = product.offers,
                    athPrice = product.athMinPrice,
                    hasSufficientHistory = hasCurrentVerifiedHistory,
                    productTitle = product.title
                )
            }

            // The price provider does not independently verify the bundled EAN.
            if (product.isEanVerified && product.ean.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = "Código de barras",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EAN verificado: ${product.ean}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }
            }

            // Free archive lookup needs a product destination, not a verified live quote.
            if (product.storeUrl.isNotBlank() ||
                product.currentStore.officialUrl.isNotBlank() ||
                product.offers.isNotEmpty()
            ) {
                OutlinedButton(
                    onClick = {
                        showWaybackDiagnostics = true
                        diagnosticReportCopyStatus = ""
                        onSearchFreeHistory()
                    },
                    enabled = !isSearchingFreeHistory && !isComparingStores,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("free_history_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isSearchingFreeHistory) "Buscando histórico gratuito…" 
                        else "Buscar histórico gratis · Internet Archive"
                    )
                }
                freeHistoryStatus?.let { status ->
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { showWaybackDiagnostics = !showWaybackDiagnostics },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("wayback_diagnostic_toggle"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (showWaybackDiagnostics) "Ocultar diagnóstico Wayback"
                        else "Ver diagnóstico Wayback · ${waybackDiagnosticSteps.count { !it.isCaptureDetail }} fases + ${waybackDiagnosticSteps.count { it.isCaptureDetail }} capturas"
                    )
                }
                if (showWaybackDiagnostics || isSearchingFreeHistory) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wayback_diagnostic_panel"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Diagnóstico local de Wayback",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            val diagnosisSummary = buildWaybackDiagnosticSummary(
                                freeHistoryStatus = freeHistoryStatus,
                                steps = waybackDiagnosticSteps
                            )
                            val diagnosisFinding = buildWaybackDiagnosticFinding(
                                freeHistoryStatus = freeHistoryStatus,
                                steps = waybackDiagnosticSteps
                            )
                            Text(
                                diagnosisSummary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Causa probable: ${diagnosisFinding.probableCause}",
                                modifier = Modifier.testTag("wayback_diagnostic_finding"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Siguiente paso: ${diagnosisFinding.recommendedNextStep}",
                                modifier = Modifier.testTag("wayback_diagnostic_next_step"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "30 fases generales más registros por captura: fecha, ruta, tienda, causa de red, decodificación y resultado del extractor. Sin consultas extra; URL completas, parámetros y HTML se ocultan en el informe copiable.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    val appVersion = runCatching {
                                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                    }.getOrNull() ?: "desconocida"
                                    val report = buildWaybackDiagnosticExportReport(
                                        productTitle = product.title,
                                        freeHistoryStatus = freeHistoryStatus,
                                        steps = waybackDiagnosticSteps,
                                        appVersion = appVersion,
                                        androidVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                                    )
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("Informe de diagnóstico Mínimo", report)
                                        )
                                        diagnosticReportCopyStatus =
                                            "Informe copiado. Pégalo en el chat para revisar el fallo."
                                    } else {
                                        diagnosticReportCopyStatus =
                                            "No se pudo acceder al portapapeles. Inténtalo de nuevo."
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .testTag("copy_wayback_diagnostic_report_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Copiar informe completo para revisar")
                            }
                            if (diagnosticReportCopyStatus.isNotBlank()) {
                                Text(
                                    diagnosticReportCopyStatus,
                                    modifier = Modifier.testTag("wayback_diagnostic_copy_status"),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            waybackDiagnosticSteps.forEach { entry ->
                                val latestStatusDetail = entry.detail.lines().lastOrNull().orEmpty()
                                val statusLabel = when (entry.status) {
                                    WaybackTraceStatus.PENDING -> "PENDIENTE"
                                    WaybackTraceStatus.RUNNING -> "EN CURSO"
                                    WaybackTraceStatus.PASSED -> "SUPERADO"
                                    WaybackTraceStatus.FAILED -> "FALLÓ"
                                    WaybackTraceStatus.SKIPPED -> when {
                                        latestStatusDetail.contains("caché", ignoreCase = true) ||
                                            latestStatusDetail.contains("cache", ignoreCase = true) -> "CACHÉ"
                                        latestStatusDetail.contains("pausa", ignoreCase = true) ||
                                            latestStatusDetail.contains("cooldown", ignoreCase = true) -> "PAUSA"
                                        latestStatusDetail.contains("no ejecutado", ignoreCase = true) ->
                                            "NO EJECUTADO"
                                        else -> "OMITIDO"
                                    }
                                }
                                val statusColor = when (entry.status) {
                                    WaybackTraceStatus.PASSED -> Color(0xFF047857)
                                    WaybackTraceStatus.FAILED -> Color(0xFFB91C1C)
                                    WaybackTraceStatus.RUNNING -> Color(0xFF1D4ED8)
                                    WaybackTraceStatus.PENDING,
                                    WaybackTraceStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            if (entry.isCaptureDetail) entry.title else "${entry.step}. ${entry.title}",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            statusLabel,
                                            color = statusColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (entry.detail.isNotBlank()) {
                                        Text(
                                            entry.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                    entry.durationMs?.let { duration ->
                                        Text(
                                            "Duración del último intento: ${duration} ms",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                                if (entry.step != 15) {
                                    Spacer(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Manual, quota-confirmed store comparison.
            OutlinedButton(
                onClick = onCompareStores,
                enabled = !isComparingStores,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("compare_stores_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isComparingStores) "Consultando tiendas…" else "Comparar tiendas · 1 búsqueda")
            }
            serpApiActionStatus?.let { status ->
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Primary Actions: Alert setting and Open store
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenSetAlert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("set_alert_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF047857)
                    )
                ) {
                    Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (product.isTracked) "Modificar seguimiento" else "Guardar seguimiento",
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val exactRetailerUrl = product.storeUrl
                        .takeIf { product.isPriceVerified }
                        ?.let(::extractDirectRetailerUrl)
                    val knownRetailer = Store.findKnownByName(
                        name = product.currentStore.name,
                        sourceUrl = product.storeUrl
                    )
                    val destination = exactRetailerUrl
                        ?: knownRetailer?.searchUrlFor(product.title)
                        ?: product.currentStore.officialUrl.takeIf { extractDirectRetailerUrl(it) != null }
                        ?: product.storeUrl
                    OutlinedButton(
                        onClick = {
                            openExternalHttpsUrl(context, destination)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        enabled = safeExternalHttpsUrl(destination) != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            when {
                                exactRetailerUrl != null -> "Ver en ${product.currentStore.name}"
                                knownRetailer?.supportsProductSearch == true -> "Buscar en ${knownRetailer.name}"
                                knownRetailer != null -> "Abrir web de ${knownRetailer.name}"
                                extractDirectRetailerUrl(product.currentStore.officialUrl) != null -> "Abrir web de ${product.currentStore.name}"
                                else -> "Abrir en Google Shopping"
                            },
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                }
            }
        }
    }
}
