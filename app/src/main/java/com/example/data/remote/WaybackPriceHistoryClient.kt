package com.example.data.remote

import android.content.Context
import android.text.Html
import com.example.data.model.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ArchivedPriceQuote(
    val observedAt: Long,
    val price: Double,
    val currency: String,
    val productTitle: String,
    val originalUrl: String,
    val snapshotUrl: String,
    /** Currency and amount as found in the archived HTML, before any historical conversion. */
    val sourceCurrency: String = currency,
    val sourcePrice: Double = price,
    /** EUR received for one source-currency unit, only when converted from a foreign currency. */
    val exchangeRate: Double? = null,
    /** Effective historical rate date (never later than the snapshot date). */
    val exchangeRateDate: String? = null,
    val conversionSource: String? = null,
    /** Set only when structured metadata uniquely matches a distinct, already-saved product. */
    val targetProductId: String? = null,
    /**
     * True only when the archived title omits a variant detail that the exact archived URL
     * explicitly identifies (for example, capacity in a product slug). It never overrides a
     * conflicting model, edition, colour, or capacity.
     */
    val variantIdentityConfirmedByUrl: Boolean = false
)

data class WaybackBackfillResult(
    val quotes: List<ArchivedPriceQuote> = emptyList(),
    val capturesFound: Int = 0,
    val capturesChecked: Int = 0,
    val skippedRecently: Boolean = false,
    val available: Boolean = false,
    /** True when at least one archive request/download failed; valid partial results may still exist. */
    val requestFailed: Boolean = false,
    /** Sanitized stage codes for actionable UI diagnostics; never contains raw URLs or exception text. */
    val diagnosticCodes: Set<String> = emptySet()
)

/**
 * A valid CDX time series followed by timeouts for every HTML download is a transport failure,
 * not evidence that the archive has no captures. Avoid broad Availability/CDX probes in the same
 * lookup; let the caller use the independent Memento source and retry Wayback after its cooldown.
 */
internal fun shouldSkipDomainFallbackAfterSnapshotTimeout(
    results: Iterable<WaybackBackfillResult>
): Boolean = results.any { result ->
    result.quotes.isEmpty() &&
        result.capturesFound > 0 &&
        result.diagnosticCodes.contains("ALL_SNAPSHOTS_FAILED") &&
        result.diagnosticCodes.contains("SNAPSHOT_TIMEOUT") &&
        !result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
}

/**
 * A primary quote already verified from the exact product URL is enough to stop secondary
 * URL-prefix discovery. Secondary/sibling-product quotes alone must not suppress the lookup
 * for the product the user selected. Any sibling quotes from the same HTML are still persisted.
 */
internal fun hasVerifiedPrimaryExactCdxQuote(result: WaybackBackfillResult): Boolean =
    result.quotes.any { it.targetProductId.isNullOrBlank() }

/** Common UTC boundary for eight-calendar-year discovery, archive fallbacks and local persistence. */
internal fun archiveHistoryWindowStartTimestampUtc(now: Long, lookbackDays: Int): Long {
    val safeNow = now.coerceAtLeast(0L)
    if (lookbackDays >= WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS) {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            timeInMillis = safeNow
            add(Calendar.YEAR, -8)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis.coerceAtLeast(0L)
    }
    return (safeNow - lookbackDays.coerceAtLeast(1).toLong() * WaybackPriceHistoryClient.DAY_MS)
        .coerceAtLeast(0L)
}

enum class WaybackTraceStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

/** Local-only event; complete URLs, headers, API keys and HTML are deliberately omitted. */
data class WaybackTraceEvent(
    // Stable internal client phase; kept unchanged for transport-level tests.
    val step: Int,
    val status: WaybackTraceStatus,
    val detail: String,
    val durationMs: Long? = null,
    // Optional phase number in the expanded on-device report. This is local diagnostic metadata.
    val diagnosticStep: Int? = null
)

/**
 * Fully decoded and classified retailer URL. Query data is retained for diagnostics and variant
 * identity, while tracking-only parameters are removed from canonicalUrl for archive lookups.
 * Full URLs and query values must never be written to the on-device diagnostic log.
 */
internal data class DecomposedProductUrl(
    val resolvedUrl: String,
    val canonicalUrl: String,
    val scheme: String,
    val host: String,
    val merchantDomain: String,
    val path: String,
    val pathSegments: List<String>,
    val queryParameters: Map<String, List<String>>,
    val variantParameters: Map<String, List<String>>,
    val productIdentifiers: Set<String>,
    val trackingParameters: Set<String>,
    val fragment: String?,
    val redirectDepth: Int
)

private fun emitWaybackTrace(
    listener: ((WaybackTraceEvent) -> Unit)?,
    step: Int,
    status: WaybackTraceStatus,
    detail: String,
    durationMs: Long? = null,
    diagnosticStep: Int? = null
) {
    if (listener == null) return
    runCatching { listener(WaybackTraceEvent(step, status, detail, durationMs, diagnosticStep)) }
}

/**
 * Report all relevant phases when a fresh lookup is intentionally suppressed by the negative
 * cache. Without these events the UI shows a generic "chain ended" message even though the
 * client deliberately avoided a duplicate request.
 */
private fun emitCachedLookupSkippedTrace(
    listener: ((WaybackTraceEvent) -> Unit)?,
    stage: String,
    fallbackWasAlsoSkipped: Boolean
) {
    emitWaybackTrace(
        listener, 4, WaybackTraceStatus.SKIPPED,
        "No ejecutado: consulta CDX de $stage omitida por caché reciente; no se envió petición."
    )
    emitWaybackTrace(
        listener, 5, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hubo respuesta CDX nueva porque se reutilizó el resultado reciente en caché."
    )
    if (fallbackWasAlsoSkipped) {
        emitWaybackTrace(
            listener, 6, WaybackTraceStatus.SKIPPED,
            "No ejecutado: la alternativa de este dominio también se omitió por caché reciente."
        )
    }
    emitWaybackTrace(
        listener, 7, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no se extrajeron capturas nuevas porque la consulta CDX se omitió por caché.",
        diagnosticStep = 8
    )
    emitWaybackTrace(
        listener, 7, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay capturas nuevas que normalizar porque no se consultó el índice por caché.",
        diagnosticStep = 9
    )
    emitWaybackTrace(
        listener, 7, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay fechas de capturas que validar porque no se consultó el índice por caché.",
        diagnosticStep = 10
    )
    emitWaybackTrace(
        listener, 8, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay una captura nueva que descargar; la consulta fue omitida por caché.",
        diagnosticStep = 11
    )
    emitWaybackTrace(
        listener, 9, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay HTML nuevo que evaluar porque no se descargó una captura.",
        diagnosticStep = 12
    )
    emitWaybackTrace(
        listener, 10, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no se encontró una segunda captura nueva porque la consulta fue omitida por caché.",
        diagnosticStep = 13
    )
    emitWaybackTrace(
        listener, 11, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay una segunda página HTML nueva que evaluar.",
        diagnosticStep = 14
    )
    emitWaybackTrace(
        listener, 12, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay título archivado que verificar porque no se descargó ninguna captura.",
        diagnosticStep = 15
    )
    emitWaybackTrace(
        listener, 13, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay precio extraíble porque no se descargó ninguna captura.",
        diagnosticStep = 16
    )
    emitWaybackTrace(
        listener, 13, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no se puede validar el importe en EUR sin un precio extraído.",
        diagnosticStep = 17
    )
    emitWaybackTrace(
        listener, 14, WaybackTraceStatus.SKIPPED,
        "No ejecutado: no hay capturas nuevas que consolidar porque la consulta fue omitida por caché.",
        diagnosticStep = 18
    )
}

/** Translate stable client phase IDs into the clearer 20-stage on-device diagnostic. */
internal fun diagnosticIndexForWaybackTrace(event: WaybackTraceEvent): Int {
    event.diagnosticStep?.takeIf { it in 1..20 }?.let { return it }
    return when (event.step) {
        4 -> 5  // CDX request sent
        5 -> 6  // CDX response classified
        6 -> 7  // fallback
        7 -> 8  // capture index extraction
        8 -> 11 // HTML capture 1 download
        9 -> 12 // HTML capture 1 barriers
        10 -> 13 // HTML capture 2 download
        11 -> 14 // HTML capture 2 barriers
        12 -> 15 // product identity
        13 -> 16 // price extraction
        14 -> 17 // amount/currency validation
        else -> event.step.coerceIn(1, 20)
    }
}

/**
 * Step 5 aggregates bounded CDX requests. Network reachability is not enough to call historical
 * recovery successful: a valid but empty index is a failed recovery unless another source found
 * captures. Capture identity and price are validated in later stages.
 */
internal fun summarizeCdxStep5Events(
    events: List<WaybackTraceEvent>,
    availabilityRecovered: Boolean = false,
    availabilityCapturesFound: Int = 0
): WaybackTraceEvent? {
    val terminal = events.filter { event ->
        event.step == 5 && event.status in setOf(
            WaybackTraceStatus.PASSED,
            WaybackTraceStatus.FAILED,
            WaybackTraceStatus.SKIPPED
        )
    }
    if (terminal.isEmpty()) return null

    val valid = terminal.filter { event ->
        event.status == WaybackTraceStatus.PASSED &&
            event.detail.contains("respuesta válida", ignoreCase = true)
    }
    val failures = terminal.filter { it.status == WaybackTraceStatus.FAILED }

    if (valid.isNotEmpty()) {
        val lastValid = valid.last()
        val candidateCount = Regex(
            """respuesta válida;\s*(\d+)\s+captura\(s\) candidatas""",
            RegexOption.IGNORE_CASE
        ).find(lastValid.detail)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val usableCaptureCount = valid.sumOf { event ->
            Regex(
                """respuesta válida;\s*(\d+)\s+captura\(s\) candidatas""",
                RegexOption.IGNORE_CASE
            ).find(event.detail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        } + availabilityCapturesFound
        val recoveredCaptures = usableCaptureCount > 0
        val detail = buildString {
            append("Se recibieron y clasificaron ${valid.size} respuesta(s) CDX válida(s).")
            if (candidateCount != null) {
                append(" La última respuesta válida devolvió ${candidateCount} captura(s) candidata(s).")
            }
            if (failures.isNotEmpty()) {
                append(" Además, una consulta secundaria falló: ")
                append(failures.last().detail.take(180))
                append(".")
            }
            if (recoveredCaptures) {
                append(" Se encontraron capturas para verificar; el paso 7 confirma si producen un precio válido.")
            } else {
                append(" Las respuestas llegaron, pero no encontraron capturas utilizables; no hay histórico que validar.")
            }
        }
        return WaybackTraceEvent(
            step = 5,
            status = if (recoveredCaptures) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
            detail = detail,
            durationMs = lastValid.durationMs,
            diagnosticStep = 6
        )
    }

    val lastFailure = failures.lastOrNull()
    if (lastFailure != null) {
        if (availabilityRecovered) {
            val recoveryDetail = if (availabilityCapturesFound > 0) {
                " Availability encontró $availabilityCapturesFound captura(s) para validar."
            } else {
                " Availability respondió, pero no encontró capturas utilizables en las URL consultadas; todavía no hay histórico que validar."
            }
            val recoverySummary = if (availabilityCapturesFound > 0) {
                " La alternativa sí recuperó capturas; los pasos 8–17 verifican su identidad y precio."
            } else {
                " La respuesta de red se recuperó, pero eso no significa que se haya recuperado histórico. Los pasos 8–17 se omiten si no hay capturas."
            }
            return WaybackTraceEvent(
                step = 5,
                // A responsive fallback is not a successful history recovery when it found no captures.
                // This status drives the on-device report, so do not paint an empty result green.
                status = if (availabilityCapturesFound > 0) {
                    WaybackTraceStatus.PASSED
                } else {
                    WaybackTraceStatus.FAILED
                },
                detail = "CDX no respondió de forma válida en ${failures.size} intento(s). Último diagnóstico: ${lastFailure.detail.take(180)}.$recoveryDetail$recoverySummary",
                durationMs = lastFailure.durationMs,
                diagnosticStep = 6
            )
        }
        return WaybackTraceEvent(
            step = 5,
            status = WaybackTraceStatus.FAILED,
            detail = "No se consiguió una respuesta CDX válida en ${failures.size} intento(s). Último diagnóstico: ${lastFailure.detail.take(180)}",
            durationMs = lastFailure.durationMs,
            diagnosticStep = 6
        )
    }

    return terminal.lastOrNull()
}

/**
 * Best-effort free historical backfill using Internet Archive snapshots.
 * A capture is accepted only when structured metadata exposes a EUR price and a product title
 * matching the searched item. Archive gaps are expected; this client never invents prices.
 */
class WaybackPriceHistoryClient(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val fxHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "minimo_wayback_backfill",
        Context.MODE_PRIVATE
    )
    private val snapshotSemaphore = Semaphore(MAX_CONCURRENT_SNAPSHOT_REQUESTS)
    // All client instances share one permit: several product cards must not fan out against
    // Internet Archive at the same time.
    private val availabilitySemaphore = GLOBAL_AVAILABILITY_SEMAPHORE

    suspend fun fetchHistory(
        originalUrl: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        onTrace: ((WaybackTraceEvent) -> Unit)? = null,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean("enabled", true)) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("WAYBACK_DISABLED"))
        }
        if (expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("PRODUCT_TITLE_EMPTY"))
        }
        // Resolve embedded Google/Ads destinations before eligibility checks. The decomposer
        // keeps product identifiers (SKU, colour, capacity, variant IDs) while canonicalizing
        // only tracking parameters for archive requests.
        val decomposedUrl = decomposeProductUrl(originalUrl)
            ?: return@withContext WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = setOf("DIRECT_URL_INVALID")
            )
        val directCandidates = buildDirectUrlCandidates(originalUrl)
        val directUrl = directCandidates.lastOrNull() ?: decomposedUrl.canonicalUrl
        val attempt = attemptKey("url-cdx-batch-v9-exact-first|${decomposedUrl.merchantDomain}|${decomposedUrl.productIdentifiers.sorted().joinToString(",")}|$directUrl|$expectedTitle")
        serializedArchiveLookup {
            if (activeRateLimitUntil(System.currentTimeMillis()) != null) {
                return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                )
            }
            if (!forceRefresh && wasAttemptedRecently(attempt, System.currentTimeMillis())) {
                val previousAttemptFailed = preferences.getBoolean("${attempt}_failed", false)
                emitCachedLookupSkippedTrace(
                    onTrace,
                    stage = "directa",
                    fallbackWasAlsoSkipped = false
                )
                return@serializedArchiveLookup WaybackBackfillResult(
                    skippedRecently = true,
                    requestFailed = previousAttemptFailed,
                    diagnosticCodes = setOf(
                        if (previousAttemptFailed) "CACHE_SKIPPED_AFTER_FAILURE" else "CACHE_SKIPPED"
                    )
                )
            }

            val snapshotResultsCache = mutableMapOf<String, SnapshotFetch>()
            val prefixCdxUrl = buildCdxUrl(directUrl, now, lookbackDays, matchType = "prefix")
                ?: return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("DIRECT_URL_INVALID")
                )
            val exactCdxUrl = buildCdxUrl(directUrl, now, lookbackDays, matchType = "exact")
            val exactResult = if (exactCdxUrl == null) {
                WaybackBackfillResult(diagnosticCodes = setOf("CDX_EXACT_URL_NOT_BUILT"))
            } else {
                queryCdxAndFetchPrices(
                    cdxUrl = exactCdxUrl,
                    expectedTitle = expectedTitle,
                    cacheKey = attemptKey("url-cdx-exact-v1|$directUrl|$expectedTitle"),
                    now = now,
                    lookbackDays = lookbackDays,
                    maxSnapshots = MAX_DIRECT_SNAPSHOTS_PER_URL,
                    cdxTimeoutSeconds = EXACT_CDX_TIMEOUT_SECONDS,
                    cdxStage = "CDX_URL_EXACT",
                    snapshotTimeoutSeconds = DIRECT_CDX_SNAPSHOT_TIMEOUT_SECONDS,
                    onTrace = onTrace,
                    knownProductTitlesById = knownProductTitlesById,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes
                )
            }
            if (exactResult.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")) {
                rememberAttempt(attempt, now, hasQuotes = false, failed = true, rateLimited = true)
                return@serializedArchiveLookup exactResult
            }
            if (shouldSkipDomainFallbackAfterSnapshotTimeout(listOf(exactResult))) {
                rememberAttempt(attempt, now, hasQuotes = false, failed = true)
                emitWaybackTrace(
                    onTrace, 6, WaybackTraceStatus.SKIPPED,
                    "CDX encontró capturas, pero todas las descargas HTML agotaron el tiempo; se omite la consulta de prefijo para evitar peticiones duplicadas."
                )
                return@serializedArchiveLookup exactResult
            }
            // The exact CDX response already gives us the historical candidates for this URL.
            // Once at least one price for the selected product has passed identity + price checks,
            // do not spend another timeout on prefix discovery. Other offers found in those same
            // HTML captures are already streamed to Room by onVerifiedQuotes.
            if (hasVerifiedPrimaryExactCdxQuote(exactResult)) {
                rememberAttempt(attempt, now, hasQuotes = true, failed = false)
                return@serializedArchiveLookup exactResult.copy(diagnosticCodes = emptySet())
            }

            // Prefix matching is only needed when exact CDX has no verified price for the selected product.
            val prefixResult = queryCdxAndFetchPrices(
                cdxUrl = prefixCdxUrl,
                expectedTitle = expectedTitle,
                cacheKey = attempt,
                now = now,
                lookbackDays = lookbackDays,
                maxSnapshots = MAX_DIRECT_SNAPSHOTS_PER_URL,
                cdxTimeoutSeconds = DIRECT_CDX_TIMEOUT_SECONDS,
                cdxStage = "CDX_URL_PREFIX",
                snapshotTimeoutSeconds = DIRECT_CDX_SNAPSHOT_TIMEOUT_SECONDS,
                onTrace = onTrace,
                knownProductTitlesById = knownProductTitlesById,
                snapshotResultsCache = snapshotResultsCache,
                onVerifiedQuotes = onVerifiedQuotes
            )
            val mergedQuotes = (exactResult.quotes + prefixResult.quotes)
                .distinctBy { listOf(it.originalUrl, it.observedAt.toString(), it.price.toString(), it.currency) }
                .sortedBy { it.observedAt }
            val merged = prefixResult.copy(
                quotes = mergedQuotes,
                capturesFound = maxOf(exactResult.capturesFound, prefixResult.capturesFound),
                capturesChecked = exactResult.capturesChecked + prefixResult.capturesChecked,
                available = exactResult.available || prefixResult.available,
                requestFailed = mergedQuotes.isEmpty() && (exactResult.requestFailed || prefixResult.requestFailed),
                diagnosticCodes = if (mergedQuotes.isNotEmpty()) {
                    emptySet()
                } else {
                    exactResult.diagnosticCodes + prefixResult.diagnosticCodes
                }
            )
            rememberAttempt(
                attempt,
                now,
                hasQuotes = merged.quotes.isNotEmpty(),
                failed = merged.requestFailed,
                rateLimited = merged.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
            )
            merged
        }
    }

    /**
     * Free fallback for Google Shopping results that expose only an intermediate Google link.
     * It probes a bounded set of likely product-page URLs through Wayback's availability API,
     * then accepts a point only when archived title and EUR metadata match the product.
     */
    suspend fun fetchHistoryByMerchantDomain(
        domain: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        preferredUrl: String? = null,
        preferredUrlAlreadyQueried: Boolean = false,
        onTrace: ((WaybackTraceEvent) -> Unit)? = null,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean("enabled", true)) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("WAYBACK_DISABLED"))
        }
        // Merchant metadata can be incomplete even when the offer has a valid direct URL.
        // Recover the host from that URL before rejecting the fallback; this adds no network call
        // and the normalizer still rejects search-engine/archive hosts.
        val resolvedPreferredUrl = preferredUrl?.let { raw ->
            decomposeProductUrl(raw)?.resolvedUrl ?: raw.takeIf(::isEligibleDirectUrl)
        }
        val normalizedDomain = normalizeMerchantDomain(domain)
            ?: resolvedPreferredUrl?.let { decomposeProductUrl(it)?.merchantDomain }
            ?: run {
                emitWaybackTrace(
                    onTrace, 4, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no se envió CDX porque no se pudo identificar un dominio de tienda válido (DOMAIN_UNRESOLVED).",
                    diagnosticStep = 5
                )
                emitWaybackTrace(
                    onTrace, 5, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay respuesta CDX porque no se envió una petición; causa DOMAIN_UNRESOLVED.",
                    diagnosticStep = 6
                )
                emitWaybackTrace(
                    onTrace, 6, WaybackTraceStatus.FAILED,
                    "Alternativa no iniciada: DOMAIN_UNRESOLVED; no se pudo convertir el dato de tienda en un dominio válido.",
                    diagnosticStep = 7
                )
                emitWaybackTrace(
                    onTrace, 7, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no se consultó un índice porque el dominio de tienda no se pudo resolver.",
                    diagnosticStep = 8
                )
                emitWaybackTrace(
                    onTrace, 7, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay capturas que normalizar porque el dominio de tienda no se pudo resolver.",
                    diagnosticStep = 9
                )
                emitWaybackTrace(
                    onTrace, 7, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay fechas de capturas que validar porque el dominio no se pudo resolver.",
                    diagnosticStep = 10
                )
                emitWaybackTrace(
                    onTrace, 8, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no existe captura descargable sin una consulta válida al índice.",
                    diagnosticStep = 11
                )
                emitWaybackTrace(
                    onTrace, 9, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay HTML de captura que evaluar.",
                    diagnosticStep = 12
                )
                emitWaybackTrace(
                    onTrace, 10, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay una segunda captura que descargar.",
                    diagnosticStep = 13
                )
                emitWaybackTrace(
                    onTrace, 11, WaybackTraceStatus.SKIPPED,
                    "No ejecutado: no hay una segunda captura que evaluar.",
                    diagnosticStep = 14
                )
                emitWaybackTrace(
                    onTrace, 12, WaybackTraceStatus.SKIPPED,
                    "Verificación del producto omitida: no se consultó ningún archivo porque el dominio es inválido.",
                    diagnosticStep = 15
                )
                emitWaybackTrace(
                    onTrace, 13, WaybackTraceStatus.SKIPPED,
                    "Extracción del precio omitida: no hay HTML histórico que analizar.",
                    diagnosticStep = 16
                )
                emitWaybackTrace(
                    onTrace, 13, WaybackTraceStatus.SKIPPED,
                    "Validación del importe omitida: no se extrajo ningún precio.",
                    diagnosticStep = 17
                )
                emitWaybackTrace(
                    onTrace, 14, WaybackTraceStatus.SKIPPED,
                    "Consolidación omitida: no hay capturas porque no se pudo resolver el dominio.",
                    diagnosticStep = 18
                )
                return@withContext WaybackBackfillResult(diagnosticCodes = setOf("DOMAIN_UNRESOLVED"))
            }
        if (expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("PRODUCT_TITLE_EMPTY"))
        }
        val preferredUrlIdentity = resolvedPreferredUrl?.let {
            decomposeProductUrl(it)?.canonicalUrl ?: it
        }.orEmpty()
        val preferredUrlIdentifiers = resolvedPreferredUrl
            ?.let(::decomposeProductUrl)
            ?.productIdentifiers
            .orEmpty()
            .sorted()
            .joinToString(",")
        val attempt = attemptKey(
            "domain-cdx-batch-v9-url-discovery|$normalizedDomain|$expectedTitle|$preferredUrlIdentity|$preferredUrlIdentifiers"
        )
        serializedArchiveLookup {
            if (activeRateLimitUntil(System.currentTimeMillis()) != null) {
                return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                )
            }
            if (shouldSkipRecentAttempt(attempt, System.currentTimeMillis(), forceRefresh)) {
                val previousAttemptFailed = preferences.getBoolean("${attempt}_failed", false)
                emitCachedLookupSkippedTrace(
                    onTrace,
                    stage = "por dominio",
                    fallbackWasAlsoSkipped = true
                )
                return@serializedArchiveLookup WaybackBackfillResult(
                    skippedRecently = true,
                    requestFailed = previousAttemptFailed,
                    diagnosticCodes = setOf(
                        if (previousAttemptFailed) "CACHE_SKIPPED_AFTER_FAILURE" else "CACHE_SKIPPED"
                    )
                )
            }

            val snapshotResultsCache = mutableMapOf<String, SnapshotFetch>()

            // Query CDX directly: one index response can contain multiple timestamps, avoiding
            // repeated availability calls for individual dates. Try the apex host only if the
            // www host returned a valid empty result; never keep probing after a timeout or 429.
            val cdxUrls = buildDomainCdxHostUrls(
                domain = normalizedDomain,
                expectedTitle = expectedTitle,
                now = now,
                lookbackDays = lookbackDays
            )?.take(MAX_DOMAIN_CDX_HOST_QUERIES).orEmpty()
            if (cdxUrls.isEmpty()) {
                emitWaybackTrace(
                    onTrace, 5, WaybackTraceStatus.SKIPPED,
                    "No se pudo construir una consulta amplia de dominio; se priorizarán la URL directa y las rutas de producto conocidas.",
                    diagnosticStep = 5
                )
            }

            // Google Shopping can already trigger a direct-URL CDX lookup immediately before
            // this fallback. Do not spend another identical index request or a broad host scan
            // after that lookup returned no usable price: the two known URLs can be checked via
            // Availability within the local time budget. This is also more reliable when a site
            // archived a slash/variant URL different from the product link.
            val generatedProductUrls = buildAvailabilityCandidateUrls(normalizedDomain, expectedTitle)
            val productUrlCandidates = (
                listOfNotNull(resolvedPreferredUrl?.takeIf(::isEligibleDirectUrl)) + generatedProductUrls
            ).distinct()
            val productUrlCandidate = productUrlCandidates.firstOrNull()

            // When Shopping does not give a real seller URL, productUrlCandidate is only a
            // URL guess assembled from the title. Do not spend the 7-second CDX timeout on that
            // guessed path before asking Availability, which is much cheaper and can return the
            // exact archived URL. If Availability yields no verified quote, the bounded alternate
            // CDX probe still runs at the end of runDomainAvailabilityFallback.
            if (resolvedPreferredUrl == null && productUrlCandidate != null) {
                return@serializedArchiveLookup runDomainAvailabilityFallback(
                    normalizedDomain = normalizedDomain,
                    expectedTitle = expectedTitle,
                    preferredUrl = productUrlCandidate,
                    now = now,
                    lookbackDays = lookbackDays,
                    cacheKey = attempt,
                    failureCodes = setOf("CDX_INFERRED_URL_AVAILABILITY_FIRST"),
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    forceRefresh = forceRefresh
                )
            }
            if (preferredUrlAlreadyQueried && resolvedPreferredUrl != null) {
                return@serializedArchiveLookup runDomainAvailabilityFallback(
                    normalizedDomain = normalizedDomain,
                    expectedTitle = expectedTitle,
                    preferredUrl = resolvedPreferredUrl,
                    now = now,
                    lookbackDays = lookbackDays,
                    cacheKey = attempt,
                    failureCodes = setOf("CDX_DIRECT_URL_ALREADY_QUERIED"),
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    forceRefresh = forceRefresh
                )
            }
            if (productUrlCandidate != null) {
                val productCdxUrl = buildCdxUrl(productUrlCandidate, now, lookbackDays)
                if (productCdxUrl != null) {
                    val productAttemptKey = attemptKey(
                        "domain-product-prefix-v5|$normalizedDomain|$expectedTitle|$productUrlCandidate"
                    )
                    val productResult = queryCdxAndFetchPrices(
                        cdxUrl = productCdxUrl,
                        expectedTitle = expectedTitle,
                        cacheKey = productAttemptKey,
                        now = now,
                        lookbackDays = lookbackDays,
                        maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
                        cdxTimeoutSeconds = DOMAIN_EXACT_CDX_TIMEOUT_SECONDS,
                        cdxStage = "CDX_PRODUCT_PREFIX",
                        snapshotTimeoutSeconds = CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS,
                        onTrace = onTrace,
                        snapshotResultsCache = snapshotResultsCache,
                        onVerifiedQuotes = onVerifiedQuotes,
                        knownProductTitlesById = knownProductTitlesById
                    )
                    if (productResult.quotes.isNotEmpty()) {
                        rememberAttempt(attempt, now, hasQuotes = true, failed = false)
                        return@serializedArchiveLookup productResult.copy(diagnosticCodes = emptySet())
                    }
                    if (productResult.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")) {
                        rememberAttempt(attempt, now, hasQuotes = false, failed = true, rateLimited = true)
                        return@serializedArchiveLookup productResult
                    }
                    // Whether the product-prefix query returned a valid empty index or a
                    // transient/non-fatal index error, go to the bounded Availability fallback
                    // now. The broad host+regex CDX request routinely takes 6-7 seconds and was
                    // consuming the remaining 10-second local budget before Availability could run.
                    return@serializedArchiveLookup runDomainAvailabilityFallback(
                        normalizedDomain = normalizedDomain,
                        expectedTitle = expectedTitle,
                        preferredUrl = resolvedPreferredUrl ?: productUrlCandidate,
                        now = now,
                        lookbackDays = lookbackDays,
                        cacheKey = attempt,
                        failureCodes = productResult.diagnosticCodes.ifEmpty {
                            setOf("CDX_PRODUCT_PREFIX_NO_VERIFIED_PRICE")
                        },
                        onTrace = onTrace,
                        snapshotResultsCache = snapshotResultsCache,
                        onVerifiedQuotes = onVerifiedQuotes,
                        forceRefresh = forceRefresh
                    )
                }
            }

            // If no CDX host query can be constructed, still try the validated direct URL and
            // bounded Availability candidates. A weak/missing title token must not suppress a
            // usable retailer URL and leave every later phase unexecuted.
            if (cdxUrls.isEmpty()) {
                return@serializedArchiveLookup runDomainAvailabilityFallback(
                    normalizedDomain = normalizedDomain,
                    expectedTitle = expectedTitle,
                    preferredUrl = resolvedPreferredUrl,
                    now = now,
                    lookbackDays = lookbackDays,
                    cacheKey = attempt,
                    failureCodes = setOf("DOMAIN_CDX_CANDIDATES_EMPTY"),
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    forceRefresh = forceRefresh
                )
            }

            // Only when no product-shaped URL can be generated do we try a broad host scan.
            // Keep this request short: if the archive is slow, the bounded availability fallback
            // must still have time to run before the ViewModel's per-candidate deadline.
            var lastResult: WaybackBackfillResult? = null
            for (cdxUrl in cdxUrls) {
                if (activeRateLimitUntil(System.currentTimeMillis()) != null) {
                    return@serializedArchiveLookup WaybackBackfillResult(
                        requestFailed = true,
                        diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                    )
                }
                val result = queryCdxAndFetchPrices(
                    cdxUrl = cdxUrl,
                    expectedTitle = expectedTitle,
                    cacheKey = attempt,
                    now = now,
                    lookbackDays = lookbackDays,
                    maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
                    cdxTimeoutSeconds = CDX_FALLBACK_TIMEOUT_SECONDS,
                    cdxStage = "CDX_DOMAIN",
                    snapshotTimeoutSeconds = CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS,
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    knownProductTitlesById = knownProductTitlesById
                )
                lastResult = result
                if (result.quotes.isNotEmpty()) {
                    return@serializedArchiveLookup result.copy(diagnosticCodes = emptySet())
                }
                if (result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")) {
                    return@serializedArchiveLookup result
                }
                // Do not repeat broad CDX scans after a transient server/network failure.
                // Move to the bounded product-specific Availability fallback instead.
                val transientDomainFailure = result.diagnosticCodes.any { code ->
                    code == "CDX_DOMAIN_TIMEOUT" ||
                        (code.startsWith("CDX_DOMAIN_HTTP_") &&
                            code.substringAfterLast('_').toIntOrNull()
                                ?.let { it in RETRYABLE_CDX_HTTP_CODES } == true)
                }
                if (result.requestFailed && transientDomainFailure) {
                    return@serializedArchiveLookup runDomainAvailabilityFallback(
                        normalizedDomain = normalizedDomain,
                        expectedTitle = expectedTitle,
                        preferredUrl = resolvedPreferredUrl,
                        now = now,
                        lookbackDays = lookbackDays,
                        cacheKey = attempt,
                        failureCodes = result.diagnosticCodes,
                        onTrace = onTrace,
                        snapshotResultsCache = snapshotResultsCache,
                        onVerifiedQuotes = onVerifiedQuotes,
                        knownProductTitlesById = knownProductTitlesById,
                        forceRefresh = forceRefresh
                    )
                }
                if (
                    result.requestFailed ||
                    result.capturesFound > 0 ||
                    !result.diagnosticCodes.contains("CDX_NO_CAPTURES")
                ) {
                    return@serializedArchiveLookup result
                }
                // Only the confirmed empty www host permits one fallback to the apex host.
            }
            lastResult ?: WaybackBackfillResult(diagnosticCodes = setOf("CDX_NO_CAPTURES"))
        }
    }

    /**
     * Legacy availability-based candidate lookup retained for the narrowly scoped parser tests.
     * Production entry points prefer batched CDX queries to avoid one network call per target date.
     */
    private suspend fun runDomainAvailabilityFallback(
        normalizedDomain: String,
        expectedTitle: String,
        preferredUrl: String?,
        now: Long,
        lookbackDays: Int,
        cacheKey: String,
        failureCodes: Set<String>,
        onTrace: ((WaybackTraceEvent) -> Unit)?,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        snapshotResultsCache: MutableMap<String, SnapshotFetch>? = null,
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult {
        val failureLabel = when {
            failureCodes.contains("CDX_DIRECT_URL_ALREADY_QUERIED") ->
                "La URL directa ya se comprobó; se buscan variantes archivadas"
            failureCodes.contains("CDX_NO_CAPTURES") ->
                "La consulta de producto no encontró capturas; se prueban otras URL"
            failureCodes.contains("CDX_DOMAIN_TIMEOUT") ->
                "CDX de dominio agotó el tiempo"
            failureCodes.any { it.contains("CDX_PRODUCT_PREFIX") && it.endsWith("_TIMEOUT") } ->
                "CDX de URL de producto agotó el tiempo"
            else ->
                "La consulta CDX no devolvió un precio verificable; se prueban otras URL"
        }
        emitWaybackTrace(
            onTrace, 6, WaybackTraceStatus.RUNNING,
            "$failureLabel. Se activa la alternativa limitada de Availability API."
        )

        val candidateUrls = orderAvailabilityCandidateUrls(
            preferredUrl = preferredUrl,
            generatedUrls = buildAvailabilityCandidateUrls(normalizedDomain, expectedTitle),
            expectedTitle = expectedTitle
        ).take(MAX_AVAILABILITY_URL_CANDIDATES)

        // Keep the fallback to three URLs after either a transient CDX timeout or HTTP 5xx.
        // Preserve the normal candidate order for future/manual discovery, but promote the known
        // retailer search URL into the third slot for this short fallback. This avoids spending
        // all three probes on guessed slugs while keeping product-specific candidates first.
        val transientCdxFailure = failureCodes.any { code ->
            code.endsWith("_TIMEOUT") ||
                code.substringAfterLast('_').toIntOrNull()
                    ?.let { it in RETRYABLE_CDX_HTTP_CODES } == true
        }
        val retailerSearchUrl = Store.ALL_STORES.firstOrNull { store ->
            normalizeMerchantDomain(store.domain) == normalizedDomain && store.supportsProductSearch
        }?.searchUrlFor(expectedTitle)?.takeIf(::isEligibleDirectUrl)
        val fallbackCandidateUrls = if (
            transientCdxFailure && retailerSearchUrl != null && retailerSearchUrl in candidateUrls
        ) {
            val nonSearchCandidates = candidateUrls.filterNot { it == retailerSearchUrl }
            (nonSearchCandidates.take(2) + retailerSearchUrl + nonSearchCandidates.drop(2))
        } else {
            candidateUrls
        }

        if (fallbackCandidateUrls.isEmpty()) {
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
            return WaybackBackfillResult(requestFailed = true, diagnosticCodes = failureCodes)
        }
        val availabilityProbeLimit = if (transientCdxFailure) {
            minOf(MAX_DOMAIN_AVAILABILITY_PROBE_CANDIDATES, 3)
        } else {
            MAX_DOMAIN_AVAILABILITY_PROBE_CANDIDATES
        }
        val fallback = queryAvailabilityAndFetchPrices(
            candidateUrls = fallbackCandidateUrls,
            expectedTitle = expectedTitle,
            cacheKey = cacheKey,
            now = now,
            lookbackDays = lookbackDays,
            maxSnapshots = MAX_CDX_FALLBACK_SNAPSHOTS,
            // Keep probes bounded after a transient index error. The known URL comes first,
            // followed by only two alternates; we still try CDX afterward if one quote is found.
            maxCandidates = availabilityProbeLimit,
            stage = "DOMAIN_AVAILABILITY_FALLBACK",
            probeOlderCapture = true,
            onTrace = onTrace,
            snapshotResultsCache = snapshotResultsCache,
            onVerifiedQuotes = onVerifiedQuotes,
            knownProductTitlesById = knownProductTitlesById,
            forceRefresh = forceRefresh
        )
        emitWaybackTrace(
            onTrace, 6,
            when {
                fallback.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") -> WaybackTraceStatus.SKIPPED
                fallback.quotes.isNotEmpty() || fallback.capturesFound > 0 -> WaybackTraceStatus.PASSED
                else -> WaybackTraceStatus.FAILED
            },
            when {
                fallback.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ->
                    "Pausa temporal de Internet Archive (HTTP 429). Mínimo ha detenido las consultas; no se puede concluir si existen capturas."
                fallback.quotes.isNotEmpty() ->
                    "Fallback completado: se recuperó un precio verificable."
                fallback.capturesFound > 0 ->
                    "Fallback encontró capturas; falta verificar identidad y precio."
                fallback.requestFailed ->
                    "La recuperación no pudo completarse: ${fallback.diagnosticCodes.joinToString(", ").take(180)}."
                else ->
                    "La red respondió, pero no se recuperaron capturas históricas utilizables; no hay precio que verificar."
            }
        )
        // Availability usually returns only the closest capture for each URL. A single
        // verified quote is useful evidence, but it is not a time series; do not return early
        // before the CDX fallback has a chance to discover older dated captures.
        if (fallback.quotes.size >= 2) {
            return fallback.copy(diagnosticCodes = emptySet())
        }
        if (fallback.quotes.size == 1) {
            emitWaybackTrace(
                onTrace, 6, WaybackTraceStatus.RUNNING,
                "Availability encontró una sola fecha verificable; se continúa con CDX para buscar capturas históricas de otras fechas."
            )
        }

        // Availability may answer correctly but miss a capture on the guessed URL. Once the
        // bounded API probes are exhausted, try one distinct product-shaped CDX prefix. This is
        // deliberately after Availability and uses a 3-second ceiling, so the extra discovery
        // route cannot consume the whole per-store budget. Archived title + EUR price validation
        // remains mandatory before any quote is accepted.
        var alternateResult: WaybackBackfillResult? = null
        if (!fallback.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") &&
            activeRateLimitUntil(System.currentTimeMillis()) == null
        ) {
            val availabilityConfirmedUrl = fallback.quotes
                .firstOrNull { it.originalUrl != preferredUrl && isEligibleDirectUrl(it.originalUrl) }
                ?.originalUrl
            val alternateUrl = availabilityConfirmedUrl
                ?: buildAvailabilityCandidateUrls(normalizedDomain, expectedTitle)
                    .firstOrNull { it != preferredUrl && isEligibleDirectUrl(it) }
            val alternateCdxUrl = alternateUrl?.let { buildCdxUrl(it, now, lookbackDays) }
            if (alternateUrl != null && alternateCdxUrl != null) {
                emitWaybackTrace(
                    onTrace, 6, WaybackTraceStatus.RUNNING,
                    "Availability no encontró un precio verificable; se comprueba una URL alternativa mediante CDX antes de cerrar la búsqueda."
                )
                val alternateCacheKey = attemptKey(
                    "domain-alternate-product-prefix-v5|$normalizedDomain|$expectedTitle|$alternateUrl"
                )
                val candidateResult = queryCdxAndFetchPrices(
                    cdxUrl = alternateCdxUrl,
                    expectedTitle = expectedTitle,
                    cacheKey = alternateCacheKey,
                    now = now,
                    lookbackDays = lookbackDays,
                    maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
                    cdxTimeoutSeconds = ALTERNATE_PRODUCT_CDX_TIMEOUT_SECONDS,
                    cdxStage = "CDX_ALTERNATE_PREFIX",
                    snapshotTimeoutSeconds = CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS,
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    knownProductTitlesById = knownProductTitlesById
                )
                alternateResult = candidateResult
                when {
                    candidateResult.quotes.isNotEmpty() -> {
                        val mergedQuotes = (fallback.quotes + candidateResult.quotes)
                            .distinctBy { listOf(it.originalUrl, it.observedAt.toString(), it.price.toString(), it.currency) }
                            .sortedBy { it.observedAt }
                        rememberAttempt(cacheKey, now, hasQuotes = true, failed = false)
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.PASSED,
                            "La URL alternativa encontró ${candidateResult.quotes.size} precio(s) histórico(s) verificado(s); se combinaron con las fechas válidas de Availability."
                        )
                        return candidateResult.copy(
                            quotes = mergedQuotes,
                            capturesFound = maxOf(fallback.capturesFound, candidateResult.capturesFound),
                            capturesChecked = fallback.capturesChecked + candidateResult.capturesChecked,
                            available = fallback.available || candidateResult.available,
                            requestFailed = false,
                            diagnosticCodes = emptySet()
                        )
                    }
                    candidateResult.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") -> {
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.SKIPPED,
                            "CDX alternativo recibió HTTP 429; se detienen las consultas para respetar la pausa global."
                        )
                    }
                    candidateResult.capturesFound > 0 -> {
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.PASSED,
                            "La URL alternativa encontró ${candidateResult.capturesFound} captura(s), pero ninguna produjo un precio verificable."
                        )
                    }
                    candidateResult.requestFailed -> {
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.FAILED,
                            "Availability respondió sin capturas y la consulta CDX alternativa falló (${candidateResult.diagnosticCodes.joinToString(", ")}); no se recuperó histórico."
                        )
                    }
                    else -> {
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.FAILED,
                            "Availability y CDX respondieron, pero ninguna ruta recuperó capturas históricas utilizables."
                        )
                    }
                }
            }
        }

        // Availability and exact-prefix CDX can miss a real archived page when the retailer
        // changed its product URL. As a final bounded recovery, search the retailer's CDX index
        // using product-specific path tokens; the parser still requires matching variant + price.
        // This is background history work and never delays the live shopping results.
        val domainDiscoveryResults = mutableListOf<WaybackBackfillResult>()
        val priorRateLimited = fallback.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ||
            alternateResult?.diagnosticCodes?.contains("WAYBACK_RATE_LIMITED") == true
        // If the one alternate product-prefix request has already timed out or failed at the
        // transport/server level, do not fan out into two broad host scans. They hit the same
        // CDX service, usually time out again, and can consume the rest of the per-product budget.
        // Valid empty responses still allow discovery; only transient failures stop it.
        val alternateTransientFailure = alternateResult?.let { result ->
            result.requestFailed && result.diagnosticCodes.any { code ->
                code.endsWith("_TIMEOUT") || code.endsWith("_DNS") ||
                    (code.contains("_HTTP_") &&
                        code.substringAfterLast('_').toIntOrNull()
                            ?.let { it in RETRYABLE_CDX_HTTP_CODES } == true)
            }
        } == true
        if (alternateTransientFailure && !priorRateLimited) {
            // The alternate product-prefix query already failed at transport level. Broad host
            // scans hit the same CDX service and routinely time out too, so stop here instead
            // of turning one timeout into three sequential requests. A later manual retry can
            // try again after the transient failure cache expires.
            emitWaybackTrace(
                onTrace, 6, WaybackTraceStatus.FAILED,
                "La consulta CDX alternativa agotó el tiempo; se omite el barrido del dominio para no repetir la misma caída del servicio."
            )
        }
        // A valid empty product-prefix response is already a negative result for the narrow
        // product path. After checking bounded Availability and one alternate prefix, do not
        // escalate it into an expensive host-wide scan; keep recovery targeted and predictable.
        val skipBroadDomainDiscovery = failureCodes.contains("CDX_NO_CAPTURES")
        if (fallback.quotes.isEmpty() && !priorRateLimited && !alternateTransientFailure &&
            !skipBroadDomainDiscovery &&
            activeRateLimitUntil(System.currentTimeMillis()) == null
        ) {
            val domainQueries = buildDomainCdxHostUrls(
                domain = normalizedDomain,
                expectedTitle = expectedTitle,
                now = now,
                lookbackDays = lookbackDays
            ).orEmpty().take(MAX_DOMAIN_CDX_HOST_QUERIES)
            for ((index, domainCdxUrl) in domainQueries.withIndex()) {
                if (activeRateLimitUntil(System.currentTimeMillis()) != null) break
                emitWaybackTrace(
                    onTrace, 6, WaybackTraceStatus.RUNNING,
                    "Las URL de producto previstas no dieron precio; se buscan páginas alternativas del dominio (${index + 1}/${domainQueries.size})."
                )
                val domainCacheKey = attemptKey(
                    "domain-bounded-host-cdx-v1|${normalizedDomain}|${expectedTitle}|${domainCdxUrl}"
                )
                val discovered = queryCdxAndFetchPrices(
                    cdxUrl = domainCdxUrl,
                    expectedTitle = expectedTitle,
                    cacheKey = domainCacheKey,
                    now = now,
                    lookbackDays = lookbackDays,
                    maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
                    cdxTimeoutSeconds = if (alternateTransientFailure) CDX_FALLBACK_TIMEOUT_SECONDS else DOMAIN_CDX_TIMEOUT_SECONDS,
                    cdxStage = "CDX_DOMAIN",
                    snapshotTimeoutSeconds = CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS,
                    onTrace = onTrace,
                    snapshotResultsCache = snapshotResultsCache,
                    onVerifiedQuotes = onVerifiedQuotes,
                    knownProductTitlesById = knownProductTitlesById
                )
                domainDiscoveryResults += discovered
                when {
                    discovered.quotes.isNotEmpty() -> {
                        val mergedQuotes = (fallback.quotes + listOfNotNull(alternateResult).flatMap { it.quotes } + discovered.quotes)
                            .distinctBy { listOf(it.originalUrl, it.observedAt.toString(), it.price.toString(), it.currency) }
                            .sortedBy { it.observedAt }
                        rememberAttempt(cacheKey, now, hasQuotes = true, failed = false)
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.PASSED,
                            "El índice del dominio encontró ${discovered.quotes.size} precio(s) histórico(s) verificado(s); se conservaron las observaciones de las otras rutas."
                        )
                        return discovered.copy(
                            quotes = mergedQuotes,
                            capturesFound = fallback.capturesFound + discovered.capturesFound,
                            capturesChecked = fallback.capturesChecked + discovered.capturesChecked,
                            available = fallback.available || discovered.available,
                            requestFailed = false,
                            diagnosticCodes = emptySet()
                        )
                    }
                    discovered.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") -> {
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.SKIPPED,
                            "El índice pidió reducir consultas (HTTP 429); se detiene la búsqueda."
                        )
                        break
                    }
                    discovered.requestFailed && discovered.diagnosticCodes.any {
                        it.endsWith("_TIMEOUT") || it.endsWith("_DNS") ||
                            (it.contains("_HTTP_") && it.substringAfterLast('_').toIntOrNull()
                                ?.let { status -> status in RETRYABLE_CDX_HTTP_CODES } == true)
                    } -> {
                        val tryNextDistinctHost = shouldTryNextDomainHostAfterTimeout(
                            currentIndex = index,
                            hostUrls = domainQueries,
                            diagnosticCodes = discovered.diagnosticCodes
                        )
                        if (tryNextDistinctHost) {
                            emitWaybackTrace(
                                onTrace, 6, WaybackTraceStatus.RUNNING,
                                "El host www agotó el tiempo; se probará una sola vez el dominio raíz, sin repetir consultas al mismo host."
                            )
                            continue
                        }
                        emitWaybackTrace(
                            onTrace, 6, WaybackTraceStatus.FAILED,
                            "La búsqueda acotada del índice del dominio falló (${discovered.diagnosticCodes.joinToString(", ")}); se evita insistir contra el archivo."
                        )
                        break
                    }
                    else -> {
                        emitWaybackTrace(
                            onTrace, 6,
                            if (discovered.capturesFound > 0) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
                            if (discovered.capturesFound > 0) {
                                "El índice del dominio encontró capturas, pero ninguna dio un precio verificable para la variante seleccionada."
                            } else {
                                "El índice del dominio respondió, pero no encontró capturas para el producto en esa ruta."
                            }
                        )
                    }
                }
            }
        }

        val combined = (listOf(fallback) + listOfNotNull(alternateResult) + domainDiscoveryResults)
            .drop(1)
            .fold(fallback) { accumulated, result ->
                accumulated.copy(
                    capturesFound = accumulated.capturesFound + result.capturesFound,
                    capturesChecked = accumulated.capturesChecked + result.capturesChecked,
                    available = accumulated.available || result.available,
                    requestFailed = accumulated.requestFailed || result.requestFailed,
                    diagnosticCodes = accumulated.diagnosticCodes + result.diagnosticCodes
                )
            }
        rememberAttempt(
            cacheKey,
            now,
            hasQuotes = combined.quotes.isNotEmpty(),
            failed = combined.requestFailed && combined.quotes.isEmpty(),
            rateLimited = combined.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
        )
        // Preserve the distinction between a valid empty response and a transport failure. The
        // result includes diagnostics from both bounded recovery routes, even when neither yields
        // a verified EUR price.
        return combined.copy(
            requestFailed = combined.requestFailed,
            diagnosticCodes = combined.diagnosticCodes + failureCodes
        )
    }

    /**
     * Put product-shaped candidates ahead of a preferred URL only when the URL explicitly
     * identifies a different purchasable variant. Missing colour/capacity in a family URL is
     * unknown, not a conflict; strict archived-title and price validation remains authoritative.
     */
    internal fun orderAvailabilityCandidateUrls(
        preferredUrl: String?,
        generatedUrls: List<String>,
        expectedTitle: String
    ): List<String> {
        val candidates = (listOfNotNull(preferredUrl?.takeIf(::isEligibleDirectUrl)) +
            generatedUrls.filter(::isEligibleDirectUrl)).distinct()
        // A preferred URL can be a generic family page, so it must not automatically outrank a
        // generated URL that explicitly names the selected capacity and colour. Keep stable order
        // for equally informative links, and push URLs that explicitly contradict the SKU last.
        return candidates.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> {
                    availabilityCandidateSpecificityScore(expectedTitle, it.value)
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    private fun availabilityCandidateSpecificityScore(
        expectedTitle: String,
        candidateUrl: String
    ): Int {
        if (hasExplicitVariantConflictInProductUrl(expectedTitle, candidateUrl)) return -10_000
        val uri = runCatching { URI(candidateUrl) }.getOrNull() ?: return Int.MIN_VALUE
        val pathText = uri.path.orEmpty().split('/')
            .filter { it.isNotBlank() }
            .joinToString(" ") { decodePathUrlComponent(it) }
        val identityQueryKeys = setOf(
            // Include retailer search-query parameters when ranking a store's real search URL.
            // Otherwise exact product-shaped guesses always outrank this URL and can consume
            // the whole bounded Availability budget before the known search route is tried.
            "query", "q", "keyword", "search",
            "sku", "skuid", "sku_id", "variant", "variantid", "variant_id", "variation",
            "variationid", "variation_id", "productid", "product_id", "id_product",
            "offerid", "offer_id", "pid", "itemid", "item_id", "asin", "mpn", "gtin",
            "ean", "model", "modelid", "color", "colour", "colorid", "colourid",
            "storage", "capacity", "size", "configuration", "config", "option", "optionid"
        )
        val queryParts = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val key = decodeQueryUrlComponent(parameter.substringBefore('=')).lowercase(Locale.ROOT)
            val value = decodeQueryUrlComponent(parameter.substringAfter('=', ""))
            if (key !in identityQueryKeys || value.isBlank()) null else key to value
        }
        val normalized = normalizeTitle(
            pathText + " " + queryParts.joinToString(" ") { it.second }
        )
        val expected = normalizeTitle(expectedTitle)
        if (normalized.isBlank() || expected.isBlank()) return 0

        var score = 0
        val modelPattern = Regex(
            """\b(iphone|ipad|pixel|redmi|poco|galaxy\s+s|galaxy\s+a|galaxy\s+z)\s*(\d{1,3})\b"""
        )
        val expectedModel = modelPattern.find(expected)
        val urlModel = modelPattern.find(normalized)
        if (expectedModel != null && urlModel != null &&
            expectedModel.groupValues[1].replace(Regex("\\s+"), "") ==
                urlModel.groupValues[1].replace(Regex("\\s+"), "") &&
            expectedModel.groupValues[2] == urlModel.groupValues[2]
        ) score += 8

        val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorage = storagePattern.findAll(expected)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        val urlStorage = storagePattern.findAll(normalized)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        if (expectedStorage.isNotEmpty() && urlStorage.any { it in expectedStorage }) score += 6

        val expectedColour = productColourFamily(expected)
        val urlColour = productColourFamily(normalized)
        if (expectedColour != null && urlColour == expectedColour) score += 6

        val expectedVariants = productModelVariantTokens(expected)
        val urlVariants = productModelVariantTokens(normalized)
        if (expectedVariants.isNotEmpty() && urlVariants.containsAll(expectedVariants)) score += 5

        // The official Apple iPhone 15 configurator encodes display, capacity and colour in its
        // path. Treat a fully matching configurator URL as stronger than an opaque SKU on a
        // generic family page; that generic URL can still expose a sibling offer in archived HTML.
        val expectedIphone15 = Regex("""\biphone\s+15\b""").containsMatchIn(expected)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val applePath = (host == "apple.com" || host.endsWith(".apple.com")) &&
            uri.path.orEmpty().contains("/shop/buy-iphone/iphone-15/pantalla-de-")
        val expectedDisplay = if ("plus" in expectedVariants) "6,7" else "6,1"
        if (expectedIphone15 && applePath &&
            uri.path.orEmpty().contains("pantalla-de-$expectedDisplay") &&
            expectedStorage.isNotEmpty() && urlStorage.containsAll(expectedStorage) &&
            expectedColour != null && urlColour == expectedColour
        ) score += 20

        // Opaque SKU/variant IDs still carry useful product-specific intent even when the URL
        // does not repeat colour or capacity in readable text.
        if (queryParts.any { (key, _) ->
                key in setOf("sku", "skuid", "sku_id", "variant", "variantid", "variant_id",
                    "variation", "variationid", "variation_id", "productid", "product_id",
                    "id_product", "offerid", "offer_id", "asin", "ean", "gtin")
            }) score += 15

        return score
    }

    /**
     * Inspect only identity-bearing URL parts. A family URL that omits capacity/colour must not
     * fail this check, while an explicit "iphone-15-plus", different capacity, or different colour
     * must not be the first archive candidate for a base iPhone 15 variant.
     */
    internal fun hasExplicitVariantConflictInProductUrl(
        expectedTitle: String,
        candidateUrl: String
    ): Boolean {
        val uri = runCatching { URI(candidateUrl) }.getOrNull() ?: return false
        val expected = normalizeTitle(expectedTitle)
        if (expected.isBlank()) return false

        val pathText = uri.path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString(" ") { decodePathUrlComponent(it) }
        val identityQueryKeys = setOf(
            "sku", "skuid", "sku_id", "variant", "variantid", "variant_id", "variation",
            "variationid", "variation_id", "productid", "product_id", "id_product",
            "offerid", "offer_id", "pid", "itemid", "item_id", "asin", "mpn", "gtin",
            "ean", "model", "modelid", "color", "colour", "colorid", "colourid",
            "storage", "capacity", "size", "configuration", "config", "option", "optionid"
        )
        val queryIdentityText = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val rawKey = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', "")
            val key = decodeQueryUrlComponent(rawKey).lowercase(Locale.ROOT)
            if (key !in identityQueryKeys) return@mapNotNull null
            decodeQueryUrlComponent(rawValue).takeIf { it.isNotBlank() }
        }.joinToString(" ")
        val candidate = normalizeTitle("$pathText $queryIdentityText")
        if (candidate.isBlank()) return false

        // Compare numeric model family where the URL names one explicitly (e.g. iPhone 15 vs 16).
        val modelPattern = Regex(
            """\b(iphone|ipad|pixel|redmi|poco|galaxy\s+s|galaxy\s+a|galaxy\s+z)\s*(\d{1,3})\b"""
        )
        val expectedModel = modelPattern.find(expected)
        val candidateModel = modelPattern.find(candidate)
        if (expectedModel != null && candidateModel != null &&
            (expectedModel.groupValues[1].replace(Regex("\\s+"), "") !=
                candidateModel.groupValues[1].replace(Regex("\\s+"), "") ||
                expectedModel.groupValues[2] != candidateModel.groupValues[2])
        ) return true

        // Do not treat omitted qualifiers as a conflict. Only reject qualifiers explicitly named
        // by the candidate that contradict the selected product.
        val expectedVariants = productModelVariantTokens(expected)
        val candidateVariants = productModelVariantTokens(candidate)
        if (candidateVariants.any { it !in expectedVariants }) return true

        val expectedColour = productColourFamily(expected)
        val candidateColour = productColourFamily(candidate)
        if (expectedColour != null && candidateColour != null && expectedColour != candidateColour) {
            return true
        }

        val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorage = storagePattern.findAll(expected)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        val candidateStorage = storagePattern.findAll(candidate)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        return expectedStorage.isNotEmpty() && candidateStorage.isNotEmpty() &&
            expectedStorage.intersect(candidateStorage).isEmpty()
    }

    private suspend fun queryAvailabilityAndFetchPrices(
        candidateUrls: List<String>,
        expectedTitle: String,
        cacheKey: String,
        now: Long,
        lookbackDays: Int,
        maxSnapshots: Int,
        maxCandidates: Int,
        stage: String,
        probeOlderCapture: Boolean = true,
        onTrace: ((WaybackTraceEvent) -> Unit)? = null,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        snapshotResultsCache: MutableMap<String, SnapshotFetch>? = null,
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult {
        val urls = candidateUrls.asSequence()
            .filter(::isEligibleDirectUrl)
            .distinct()
            .take(maxCandidates.coerceAtLeast(1))
            .toList()
        if (urls.isEmpty()) {
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
            return WaybackBackfillResult(requestFailed = true, diagnosticCodes = setOf("${stage}_NO_CANDIDATES"))
        }

        // First probe the most recent target for each bounded URL so an archived capture on
        // a later candidate is not hidden by an older-date request for the first URL. The number
        // of total Availability requests, including optional older-date probes, never exceeds
        // maxCandidates. This keeps request pacing predictable and avoids amplifying archive load.
        val dates = availabilityTargetDates(now, lookbackDays, 2)
        val probes = mutableListOf<AvailabilityProbe>()
        for (originalUrl in urls) {
            if (probes.size >= maxCandidates.coerceAtLeast(1)) break
            val recentProbe = availabilitySemaphore.withPermit {
                queryAvailability(originalUrl, dates.first(), stage)
            }
            probes += recentProbe
            if (recentProbe.diagnosticCode == "WAYBACK_RATE_LIMITED") break
            val uniqueRecentCaptures = probes.mapNotNull { it.capture }
                .distinctBy { it.rawTimestamp to it.originalUrl }
            if (uniqueRecentCaptures.size >= maxSnapshots.coerceAtLeast(1)) break
            if (recentProbe.requestFailed) {
                val code = recentProbe.diagnosticCode.orEmpty()
                // A candidate-specific 404 or timeout should not hide a capture archived under
                // the next bounded product URL. Other broad failures stop to protect the archive.
                if (code == "${stage}_HTTP_404" || code.endsWith("_TIMEOUT")) continue
                break
            }
        }
        // If the URL list has fewer items than the request budget, spend only the remaining slots
        // on older target dates for captures already found. Never issue an extra sixth probe just
        // because a URL had both a recent and an older snapshot.
        val hasTerminalAvailabilityFailure = probes.any { probe ->
            if (!probe.requestFailed) {
                false
            } else {
                val code = probe.diagnosticCode.orEmpty()
                code != "WAYBACK_RATE_LIMITED" &&
                    code != "${stage}_HTTP_404" &&
                    !code.endsWith("_TIMEOUT")
            }
        }
        if (probeOlderCapture &&
            probes.size < maxCandidates.coerceAtLeast(1) &&
            probes.none { it.diagnosticCode == "WAYBACK_RATE_LIMITED" } &&
            !hasTerminalAvailabilityFailure
        ) {
            val recentCaptures = probes.mapNotNull { it.capture }
                .distinctBy { it.rawTimestamp to it.originalUrl }
            for (capture in recentCaptures) {
                if (probes.size >= maxCandidates.coerceAtLeast(1)) break
                val currentUniqueCaptureCount = probes.mapNotNull { it.capture }
                    .distinctBy { it.rawTimestamp to it.originalUrl }.size
                if (currentUniqueCaptureCount >= maxSnapshots.coerceAtLeast(1)) break
                val olderProbe = availabilitySemaphore.withPermit {
                    queryAvailability(capture.originalUrl, dates.last(), stage)
                }
                probes += olderProbe
                if (olderProbe.diagnosticCode == "WAYBACK_RATE_LIMITED") break
                val code = olderProbe.diagnosticCode.orEmpty()
                if (olderProbe.requestFailed &&
                    code != "${stage}_HTTP_404" && !code.endsWith("_TIMEOUT")
                ) break
            }
        }
        val allAvailabilityCaptures = probes.mapNotNull { it.capture }
            .distinctBy { it.rawTimestamp to it.originalUrl }
        val windowStart = historyWindowStartTimestamp(now, lookbackDays)
        val captures = allAvailabilityCaptures
            .filter { it.timestamp in windowStart..now }
            .sortedBy { it.timestamp }
        val outsideWindowCount = allAvailabilityCaptures.size - captures.size
        val successfulProbes = probes.count { !it.requestFailed }
        val failedProbeCount = probes.count { it.requestFailed }
        val probeDiagnostics = probes.mapNotNull { it.diagnosticCode }.toSet()
        val availabilityTraceStatus = when {
            probeDiagnostics.contains("WAYBACK_RATE_LIMITED") -> WaybackTraceStatus.SKIPPED
            captures.isNotEmpty() -> WaybackTraceStatus.PASSED
            // HTTP 200 with an empty Availability result means the network responded, not that
            // historical captures were recovered. Keep the step red rather than implying success.
            else -> WaybackTraceStatus.FAILED
        }
        val availabilityTraceDetail = when {
            probeDiagnostics.contains("WAYBACK_RATE_LIMITED") ->
                "Availability se detuvo al recibir HTTP 429; no se enviaron más consultas."
            captures.isNotEmpty() ->
                "Availability encontró ${captures.size} captura(s) en ${probes.size} URL(s) probadas; ${failedProbeCount} intento(s) fallido(s): ${probeDiagnostics.joinToString(", ").take(180)}. Se conservan las capturas válidas."
            successfulProbes > 0 ->
                "Availability respondió correctamente en ${successfulProbes} URL(s), pero no encontró capturas. ${failedProbeCount} intento(s) fallido(s): ${probeDiagnostics.joinToString(", ").take(180)}."
            else ->
                "Todas las ${probes.size} consultas Availability fallaron: ${probeDiagnostics.joinToString(", ").take(180)}."
        }
        emitWaybackTrace(
            onTrace, 7, availabilityTraceStatus, availabilityTraceDetail,
            diagnosticStep = 7
        )

        // Availability is a separate capture source from the CDX index. Reflect its successful
        // rows in the same detailed report so a CDX timeout does not leave steps 8–10 falsely
        // marked as unexecuted when the fallback has already returned usable captures.
        val captureExtractionStatus = when {
            captures.isNotEmpty() -> WaybackTraceStatus.PASSED
            probeDiagnostics.contains("WAYBACK_RATE_LIMITED") -> WaybackTraceStatus.SKIPPED
            successfulProbes > 0 -> WaybackTraceStatus.SKIPPED
            else -> WaybackTraceStatus.FAILED
        }
        emitWaybackTrace(
            onTrace, 7, captureExtractionStatus,
            when {
                captures.isNotEmpty() ->
                    "Availability API devolvió ${captures.size} captura(s) válidas dentro del período solicitado."
                probeDiagnostics.contains("WAYBACK_RATE_LIMITED") ->
                    "Extracción omitida: HTTP 429; se respeta la pausa global."
                successfulProbes > 0 ->
                    "Availability respondió correctamente, pero no devolvió capturas utilizables."
                else ->
                    "Extracción fallida: ninguna consulta Availability produjo una respuesta utilizable."
            },
            diagnosticStep = 8
        )
        emitWaybackTrace(
            onTrace, 7,
            if (captures.isNotEmpty()) WaybackTraceStatus.PASSED else WaybackTraceStatus.SKIPPED,
            when {
                captures.isNotEmpty() ->
                    "Candidatas Availability normalizadas y deduplicadas: ${captures.map { it.originalUrl }.distinct().size} URL(s) únicas."
                probeDiagnostics.contains("WAYBACK_RATE_LIMITED") ->
                    "Normalización omitida por HTTP 429: se respeta la pausa global; no se puede concluir que falten capturas."
                else ->
                    "No hay URL candidatas que normalizar porque Availability no aportó capturas dentro del período."
            },
            diagnosticStep = 9
        )
        emitWaybackTrace(
            onTrace, 7,
            when {
                captures.isNotEmpty() -> WaybackTraceStatus.PASSED
                probeDiagnostics.contains("WAYBACK_RATE_LIMITED") -> WaybackTraceStatus.SKIPPED
                successfulProbes > 0 -> WaybackTraceStatus.SKIPPED
                else -> WaybackTraceStatus.FAILED
            },
            when {
                captures.isNotEmpty() ->
                    "Fechas Availability validadas: ${captures.size} captura(s) dentro de los últimos ${lookbackDays.coerceAtLeast(1)} días; ${outsideWindowCount} descartada(s) fuera del intervalo."
                probeDiagnostics.contains("WAYBACK_RATE_LIMITED") ->
                    "Validación de fechas omitida por HTTP 429: se respeta la pausa global; no se interpreta como ausencia de capturas."
                successfulProbes > 0 ->
                    "No había fechas de capturas para validar; la API respondió sin capturas disponibles."
                else ->
                    "No se pudo validar el período porque ninguna consulta Availability respondió correctamente."
            },
            diagnosticStep = 10
        )

        // A 429 ends this backfill immediately. Do not follow successful earlier probes with
        // snapshot downloads while the archive has explicitly asked us to slow down.
        if (probeDiagnostics.contains("WAYBACK_RATE_LIMITED")) {
            rememberAttempt(
                cacheKey,
                now,
                hasQuotes = false,
                failed = true,
                rateLimited = true
            )
            return WaybackBackfillResult(
                capturesFound = captures.size,
                available = successfulProbes > 0,
                requestFailed = true,
                diagnosticCodes = probeDiagnostics
            )
        }

        if (captures.isEmpty()) {
            val allFailed = successfulProbes == 0
            // A mixture of timeouts/404s and successful empty responses is not a confirmed
            // "no history" result. Mark it as a failure so it gets the short retry window rather
            // than being cached as a clean negative for six hours.
            val anyProbeFailed = failedProbeCount > 0
            val attemptFailed = allFailed || anyProbeFailed
            val diagnostics = linkedSetOf<String>().apply {
                addAll(probeDiagnostics)
                if (isEmpty()) add("${stage}_NO_CAPTURES")
                else if (!allFailed) add("${stage}_NO_CAPTURES")
            }
            val noCaptureDetail = if (allFailed) {
                "No se descargó ninguna captura porque todas las consultas Availability fallaron."
            } else if (anyProbeFailed) {
                "Availability no encontró capturas y al menos una URL falló; no se cacheará como ausencia confirmada de histórico."
            } else {
                "Availability respondió, pero no encontró capturas en las URL consultadas dentro del período."
            }
            emitWaybackTrace(
                onTrace, 8, WaybackTraceStatus.SKIPPED, noCaptureDetail, diagnosticStep = 11
            )
            emitWaybackTrace(
                onTrace, 9, WaybackTraceStatus.SKIPPED,
                "No hay HTML de captura 1 que evaluar: no se encontró ninguna captura.",
                diagnosticStep = 12
            )
            emitWaybackTrace(
                onTrace, 10, WaybackTraceStatus.SKIPPED,
                "No se encontró una segunda captura independiente porque Availability no devolvió capturas.",
                diagnosticStep = 13
            )
            emitWaybackTrace(
                onTrace, 11, WaybackTraceStatus.SKIPPED,
                "Evaluación de la captura 2 omitida porque Availability no encontró una captura.",
                diagnosticStep = 14
            )
            emitWaybackTrace(
                onTrace, 12, WaybackTraceStatus.SKIPPED,
                "Verificación del producto omitida: Availability no devolvió una captura para descargar.",
                diagnosticStep = 15
            )
            emitWaybackTrace(
                onTrace, 13, WaybackTraceStatus.SKIPPED,
                "Extracción del precio omitida: no hay HTML de una captura histórica.",
                diagnosticStep = 16
            )
            emitWaybackTrace(
                onTrace, 13, WaybackTraceStatus.SKIPPED,
                "Validación de importe omitida: no se extrajo ningún precio.",
                diagnosticStep = 17
            )
            emitWaybackTrace(
                onTrace, 14, WaybackTraceStatus.SKIPPED,
                "Consolidación omitida: no hay precios que consolidar porque no se descargó ninguna captura.",
                diagnosticStep = 18
            )
            rememberAttempt(
                cacheKey,
                now,
                hasQuotes = false,
                failed = attemptFailed,
                rateLimited = probeDiagnostics.contains("WAYBACK_RATE_LIMITED")
            )
            return WaybackBackfillResult(
                available = successfulProbes > 0,
                requestFailed = attemptFailed,
                diagnosticCodes = diagnostics
            )
        }

        val sampled = sampleUnprocessedCaptures(
            captures = captures,
            max = maxSnapshots,
            processedCaptureKeys = snapshotResultsCache?.keys.orEmpty()
        )
        val fetched = mutableListOf<SnapshotFetch>()
        for ((snapshotIndex, capture) in sampled.withIndex()) {
            val snapshot = fetchSnapshotOnce(
                capture = capture,
                expectedTitle = expectedTitle,
                snapshotTimeoutSeconds = SNAPSHOT_CALL_TIMEOUT_SECONDS,
                snapshotOrdinal = snapshotIndex + 1,
                snapshotTotal = sampled.size,
                captureSourceStage = stage,
                onTrace = onTrace,
                knownProductTitlesById = knownProductTitlesById,
                snapshotResultsCache = snapshotResultsCache,
                onVerifiedQuotes = onVerifiedQuotes
            )
            fetched += snapshot
            if (snapshot.diagnosticCode == "WAYBACK_RATE_LIMITED") break
        }
        if (sampled.size == 1) {
            emitWaybackTrace(
                onTrace, 10, WaybackTraceStatus.SKIPPED,
                "No se encontró una segunda captura independiente en Availability; solo se recuperó 1 captura dentro del período.",
                diagnosticStep = 13
            )
            emitWaybackTrace(
                onTrace, 11, WaybackTraceStatus.SKIPPED,
                "Evaluación de la captura 2 omitida porque Availability solo devolvió una captura.",
                diagnosticStep = 14
            )
        }

        val availabilityQuotes = fetched
            .flatMap { snapshot -> listOfNotNull(snapshot.quote) + snapshot.additionalQuotes }
            .sortedBy { it.observedAt }
            // Preserve distinct merchant URLs as distinct observations. Room and the graph can
            // deduplicate by product/date later, but dropping a second seller here lost evidence
            // before it ever reached persistence.
            .distinctBy {
                listOf(
                    it.targetProductId.orEmpty(), it.productTitle, it.originalUrl,
                    it.observedAt.toString(), it.price.toString(), it.currency.uppercase(Locale.ROOT)
                )
            }

        // Availability returns only the closest capture for a queried URL/date. Once one of
        // those snapshots has proved the selected product and price, use its verified original
        // retailer URL to ask CDX for the time series in one index request. This is the missing
        // bridge between "one price recovered by Availability" and "a multi-date historical
        // series". It also means the CDX request (and its actual response/failure) is visible in
        // diagnostic steps 5–6 instead of those steps staying "not executed" for Google-only
        // saved products. Never run this discovery from an unverified candidate URL.
        val verifiedSeriesUrl = fetched
            .mapNotNull { it.quote }
            .firstOrNull { quote ->
                quote.originalUrl.isNotBlank() && isEligibleDirectUrl(quote.originalUrl)
            }?.originalUrl
        val supplementalCdxQuotes = mutableListOf<ArchivedPriceQuote>()
        val supplementalCdxDiagnostics = linkedSetOf<String>()
        var supplementalCdxCaptures = 0
        var supplementalCdxChecked = 0
        if (verifiedSeriesUrl != null && !probeDiagnostics.contains("WAYBACK_RATE_LIMITED")) {
            val seriesCacheKey = attemptKey(
                "availability-verified-url-cdx-series-v1|$verifiedSeriesUrl|$expectedTitle|$lookbackDays"
            )
            if (forceRefresh || !wasAttemptedRecently(seriesCacheKey, now)) {
                val seriesCdxUrl = buildCdxUrl(
                    originalUrl = verifiedSeriesUrl,
                    now = now,
                    lookbackDays = lookbackDays,
                    matchType = "prefix"
                )
                if (seriesCdxUrl != null) {
                    emitWaybackTrace(
                        onTrace, 4, WaybackTraceStatus.RUNNING,
                        "Availability confirmó una captura; se consulta CDX sobre la URL verificada para recuperar otras fechas."
                    )
                    val seriesResult = queryCdxAndFetchPrices(
                        cdxUrl = seriesCdxUrl,
                        expectedTitle = expectedTitle,
                        cacheKey = seriesCacheKey,
                        now = now,
                        lookbackDays = lookbackDays,
                        maxSnapshots = maxSnapshots.coerceAtMost(8).coerceAtLeast(2),
                        cdxTimeoutSeconds = DIRECT_CDX_TIMEOUT_SECONDS,
                        cdxStage = "CDX_AVAILABILITY_SERIES",
                        snapshotTimeoutSeconds = SNAPSHOT_CALL_TIMEOUT_SECONDS,
                        onTrace = onTrace,
                        knownProductTitlesById = knownProductTitlesById
                    )
                    supplementalCdxQuotes += seriesResult.quotes
                    supplementalCdxDiagnostics += seriesResult.diagnosticCodes
                    supplementalCdxCaptures = seriesResult.capturesFound
                    supplementalCdxChecked = seriesResult.capturesChecked
                }
            } else {
                emitWaybackTrace(
                    onTrace, 4, WaybackTraceStatus.SKIPPED,
                    "La serie CDX de la URL verificada ya se consultó recientemente; se evita repetir tráfico."
                )
            }
        }

        val quotes = (availabilityQuotes + supplementalCdxQuotes)
            .sortedBy { it.observedAt }
            // Keep one row per product/store URL/date/price. A CDX time series supplements the
            // Availability point instead of replacing it, while duplicate copies of the same
            // capture still collapse deterministically.
            .distinctBy {
                listOf(
                    it.targetProductId.orEmpty(), it.productTitle, it.originalUrl,
                    it.observedAt.toString(), it.price.toString(), it.currency.uppercase(Locale.ROOT)
                )
            }
        val diagnostics = linkedSetOf<String>().apply {
            addAll(probeDiagnostics)
            addAll(supplementalCdxDiagnostics)
            fetched.mapNotNullTo(this) { it.diagnosticCode }
            if (quotes.isEmpty() && fetched.any { it.fetched }) add("NO_VERIFIED_EUR_PRICE")
            else if (quotes.isEmpty() && isEmpty()) add("${stage}_NO_VERIFIED_PRICE")
        }
        val verifiedBeforeDedup = (
            fetched.flatMap { snapshot -> listOfNotNull(snapshot.quote) + snapshot.additionalQuotes } +
                supplementalCdxQuotes
            ).sortedBy { it.observedAt }
        val duplicatesRemoved = (verifiedBeforeDedup.size - quotes.size).coerceAtLeast(0)
        val identityMismatches = fetched.count { it.diagnosticCode == "ARCHIVE_PRODUCT_TITLE_MISMATCH" }
        val missingEurPrices = fetched.count { it.diagnosticCode == "ARCHIVE_EUR_PRICE_MISSING" }
        val consolidationDetail = buildString {
            append(
                "Consolidación histórica: ${fetched.count { it.fetched && !it.reused }} HTML de Availability descargado(s), " +
                    "${supplementalCdxChecked} HTML adicional(es) comprobado(s) desde CDX, " +
                    "${verifiedBeforeDedup.size} precio(s) verificado(s), ${duplicatesRemoved} duplicado(s) eliminado(s)."
            )
            if (quotes.isEmpty()) {
                when {
                    identityMismatches > 0 ->
                        append(" No se guardó ningún importe porque ${identityMismatches} captura(s) no acreditaron el modelo/variante exactos.")
                    missingEurPrices > 0 ->
                        append(" No se guardó ningún importe porque ${missingEurPrices} captura(s) no aportaron un precio EUR verificable.")
                    fetched.any { it.diagnosticCode == "ARCHIVE_USD_FX_UNAVAILABLE" } ->
                        append(" No se guardó ningún importe porque faltó un cambio histórico USD/EUR verificable.")
                    fetched.any { it.fetched } ->
                        append(" Las capturas se descargaron, pero ninguna produjo un precio histórico verificable.")
                }
            }
        }
        emitWaybackTrace(
            onTrace, 14,
            when {
                quotes.isNotEmpty() -> WaybackTraceStatus.PASSED
                captures.isEmpty() -> WaybackTraceStatus.SKIPPED
                else -> WaybackTraceStatus.FAILED
            },
            consolidationDetail,
            diagnosticStep = 18
        )
        val anySnapshotFailed = fetched.any { !it.fetched }
        // If no verified quote was obtained and any probe/download failed, treat the attempt as
        // transient. A successful HTML download with a mismatching title is a clean negative;
        // a timeout of a sibling capture is not.
        val failed = quotes.isEmpty() && (
            successfulProbes == 0 || failedProbeCount > 0 || anySnapshotFailed
        )
        rememberAttempt(
            cacheKey,
            now,
            hasQuotes = quotes.isNotEmpty(),
            failed = failed,
            rateLimited = probeDiagnostics.contains("WAYBACK_RATE_LIMITED") ||
                fetched.any { it.diagnosticCode == "WAYBACK_RATE_LIMITED" }
        )
        return WaybackBackfillResult(
            quotes = quotes,
            capturesFound = maxOf(captures.size, supplementalCdxCaptures),
            capturesChecked = fetched.count { it.fetched && !it.reused } + supplementalCdxChecked,
            available = successfulProbes > 0,
            requestFailed = quotes.isEmpty() && (failed || (anySnapshotFailed && successfulProbes == 0)),
            diagnosticCodes = diagnostics
        )
    }

    private suspend fun queryAvailability(
        originalUrl: String,
        targetDate: String,
        stage: String
    ): AvailabilityProbe {
        val endpoint = HttpUrl.Builder()
            .scheme("https")
            .host("archive.org")
            .addPathSegments("wayback/available")
            .addQueryParameter("url", originalUrl)
            .addQueryParameter("timestamp", targetDate)
            .build()
        val availabilityHttp = http.newBuilder()
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(endpoint).header("User-Agent", USER_AGENT).get().build()
        return try {
            // The shared permit and persisted request gate are also used by CDX fallback calls.
            awaitArchiveRequestTurn()?.let { code ->
                return AvailabilityProbe(requestFailed = true, diagnosticCode = code)
            }
            availabilityHttp.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    activateRateLimit(response.header("Retry-After"), System.currentTimeMillis())
                    AvailabilityProbe(requestFailed = true, diagnosticCode = "WAYBACK_RATE_LIMITED")
                } else if (!response.isSuccessful) {
                    AvailabilityProbe(requestFailed = true, diagnosticCode = "${stage}_HTTP_${response.code}")
                } else {
                    val body = readBodyLimited(response.body, MAX_AVAILABILITY_BODY_BYTES)
                    if (body == null) {
                        AvailabilityProbe(requestFailed = true, diagnosticCode = "${stage}_BODY_EMPTY_OR_LIMIT")
                    } else {
                        val capture = parseAvailabilityCapture(body, originalUrl)
                        if (runCatching { JSONObject(body) }.isFailure) {
                            AvailabilityProbe(requestFailed = true, diagnosticCode = "${stage}_INVALID_RESPONSE")
                        } else {
                            AvailabilityProbe(capture = capture)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AvailabilityProbe(requestFailed = true, diagnosticCode = classifyNetworkFailure(stage, failure))
        }
    }

    internal fun buildSnapshotHttpClient(snapshotTimeoutSeconds: Long): OkHttpClient {
        val timeout = snapshotTimeoutSeconds.coerceAtLeast(1L)
        return http.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout, TimeUnit.SECONDS)
            .build()
    }

    internal fun snapshotCacheKey(capture: WaybackCapture): String =
        capture.rawTimestamp + "|" + capture.originalUrl

    internal fun historyWindowStartTimestamp(now: Long, lookbackDays: Int): Long =
        archiveHistoryWindowStartTimestampUtc(now, lookbackDays)

    /** Stable, privacy-safe identifier for one archived page fetch in the on-device trace. */
    internal fun snapshotDiagnosticLabel(
        capture: WaybackCapture,
        ordinal: Int,
        total: Int,
        sourceStage: String
    ): String {
        val safeStage = sourceStage.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(40).ifBlank { "CDX" }
        val safeHost = runCatching { URI(capture.originalUrl).host }.getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { host -> Regex("""^[a-z0-9.-]+$""").matches(host) }
            ?: "host-desconocido"
        val safeTimestamp = capture.rawTimestamp.filter(Char::isDigit).take(14)
        val captureId = MessageDigest.getInstance("SHA-256")
            .digest((capture.rawTimestamp + "|" + capture.originalUrl).toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff).map { ch ->
                    if (ch in '0'..'9') ('a'.code + (ch - '0')).toChar() else ch
                }.joinToString("")
            }
        return "captura ${ordinal.coerceAtLeast(1)} de ${total.coerceAtLeast(1)} · id $captureId · fecha ${safeTimestamp.ifBlank { "desconocida" }} · ruta $safeStage · tienda $safeHost"
    }

    private suspend fun fetchSnapshotOnce(
        capture: WaybackCapture,
        expectedTitle: String,
        snapshotTimeoutSeconds: Long,
        snapshotOrdinal: Int,
        snapshotTotal: Int,
        captureSourceStage: String,
        onTrace: ((WaybackTraceEvent) -> Unit)?,
        knownProductTitlesById: Map<String, String>,
        snapshotResultsCache: MutableMap<String, SnapshotFetch>?,
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)?
    ): SnapshotFetch {
        val key = snapshotCacheKey(capture)
        snapshotResultsCache?.get(key)?.let { cached ->
            val step = if (snapshotOrdinal <= 1) 8 else 10
            emitWaybackTrace(
                onTrace, step, WaybackTraceStatus.SKIPPED,
                "${snapshotDiagnosticLabel(capture, snapshotOrdinal, snapshotTotal, captureSourceStage)} ya procesada en esta búsqueda; se reutiliza el resultado sin descargar el mismo HTML otra vez."
            )
            return cached.copy(reused = true)
        }
        val snapshot = fetchAndVerifySnapshot(
            capture = capture,
            expectedTitle = expectedTitle,
            snapshotTimeoutSeconds = snapshotTimeoutSeconds,
            snapshotOrdinal = snapshotOrdinal,
            snapshotTotal = snapshotTotal,
            captureSourceStage = captureSourceStage,
            onTrace = onTrace,
            knownProductTitlesById = knownProductTitlesById
        )
        snapshotResultsCache?.set(key, snapshot)
        val verifiedQuotes = listOfNotNull(snapshot.quote) + snapshot.additionalQuotes
        if (verifiedQuotes.isNotEmpty()) onVerifiedQuotes?.invoke(verifiedQuotes)
        return snapshot
    }

    private class SnapshotRetryBlockedException(val diagnosticCode: String) :
        IOException(diagnosticCode)

    internal fun isDnsResolutionFailure(failure: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            if (current is UnknownHostException) return true
            val message = current.message.orEmpty().lowercase(Locale.ROOT)
            if (
                message.contains("unable to resolve host") ||
                message.contains("failed to resolve") ||
                message.contains("no address associated with hostname") ||
                message.contains("nodename nor servname provided") ||
                message.contains("temporary failure in name resolution")
            ) return true
            current = current.cause
        }
        return false
    }

    internal suspend fun <T> retrySnapshotDnsOnce(
        request: suspend () -> T,
        beforeRetry: suspend () -> Boolean
    ): T {
        var retried = false
        while (true) {
            try {
                return request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!isDnsResolutionFailure(failure)) throw failure
                if (retried) throw failure
                if (!beforeRetry()) throw SnapshotRetryBlockedException("WAYBACK_RATE_LIMITED")
                retried = true
            }
        }
    }

    private suspend fun fetchAndVerifySnapshot(
        capture: WaybackCapture,
        expectedTitle: String,
        snapshotTimeoutSeconds: Long,
        snapshotOrdinal: Int = 1,
        snapshotTotal: Int = 1,
        captureSourceStage: String = "CDX",
        onTrace: ((WaybackTraceEvent) -> Unit)? = null,
        knownProductTitlesById: Map<String, String> = emptyMap()
    ): SnapshotFetch {
        val captureLabel = snapshotDiagnosticLabel(capture, snapshotOrdinal, snapshotTotal, captureSourceStage)
        val captureTrace: ((WaybackTraceEvent) -> Unit)? = onTrace?.let { downstream ->
            { event: WaybackTraceEvent ->
                downstream(event.copy(detail = "$captureLabel · ${event.detail}"))
            }
        }
        val downloadStep = if (snapshotOrdinal <= 1) 8 else 10
        val evaluateStep = if (snapshotOrdinal <= 1) 9 else 11
        val snapshotLabel = "captura $snapshotOrdinal de ${snapshotTotal.coerceAtLeast(1)}"
        emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.RUNNING,
            "Descarga HTML de la $snapshotLabel iniciada; URL completa omitida.")
        val snapshotUrl = buildSnapshotUrl(capture)
        if (snapshotUrl == null) {
            emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.FAILED, "No se pudo construir la URL archivada.")
            emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.SKIPPED, "No se descargó HTML para evaluar.")
            skipArchiveValidationTrace(captureTrace, "La URL del snapshot no era válida.")
            return SnapshotFetch(false, null, "SNAPSHOT_URL_INVALID")
        }
        val request = Request.Builder().url(snapshotUrl).header("User-Agent", USER_AGENT).get().build()
        val snapshotHttp = buildSnapshotHttpClient(snapshotTimeoutSeconds)
        val startedAt = System.nanoTime()
        var snapshotRequestAttempts = 0

        return availabilitySemaphore.withPermit {
            val gate = awaitArchiveRequestTurn()
            if (gate != null) {
                emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.FAILED, "Descarga bloqueada por control de ritmo: $gate.")
                emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.SKIPPED, "No hubo HTML que evaluar.")
                skipArchiveValidationTrace(captureTrace, "La descarga quedó bloqueada antes de recibir HTML.")
                return@withPermit SnapshotFetch(false, null, gate)
            }
            try {
                retrySnapshotDnsOnce(
                    request = {
                        snapshotRequestAttempts++
                        snapshotHttp.newCall(request).execute()
                    },
                    beforeRetry = {
                        delay(SNAPSHOT_DNS_RETRY_DELAY_MS)
                        awaitArchiveRequestTurn() == null
                    }
                ).use { response ->
                    val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    if (response.code == 429) {
                        activateRateLimit(response.header("Retry-After"), System.currentTimeMillis())
                        emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.FAILED, "HTTP 429: Internet Archive pidió frenar las consultas.", elapsed)
                        emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.FAILED, "El servidor limitó la descarga; no se pudo analizar el HTML.")
                        skipArchiveValidationTrace(captureTrace, "HTTP 429; validación cancelada para respetar el límite.")
                        SnapshotFetch(false, null, "WAYBACK_RATE_LIMITED")
                    } else if (!response.isSuccessful) {
                        val code = "SNAPSHOT_HTTP_${response.code}"
                        emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.FAILED, "La descarga respondió $code.", elapsed)
                        emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.FAILED, "No se recibió una página HTML utilizable.")
                        skipArchiveValidationTrace(captureTrace, "La descarga devolvió un código HTTP no satisfactorio.")
                        SnapshotFetch(false, null, code)
                    } else {
                        val html = readBodyLimited(response.body, MAX_SNAPSHOT_BODY_BYTES)
                        if (html == null) {
                            emitWaybackTrace(captureTrace, downloadStep, WaybackTraceStatus.FAILED, "HTTP ${response.code}; contenido vacío o por encima del límite permitido.", elapsed)
                            emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.FAILED, "No se pudo evaluar el HTML descargado.")
                            skipArchiveValidationTrace(captureTrace, "El cuerpo HTML estaba vacío o superaba el límite seguro.")
                            SnapshotFetch(false, null, "SNAPSHOT_BODY_EMPTY_OR_LIMIT")
                        } else {
                            val redirectCount = generateSequence(response.priorResponse) { it.priorResponse }.count()
                            val finalHost = response.request.url.host
                            emitWaybackTrace(
                                captureTrace, downloadStep, WaybackTraceStatus.PASSED,
                                "HTTP ${response.code}; HTML recibido (${html.length} caracteres); host final ${finalHost}; redirecciones ${redirectCount}; captura ${capture.rawTimestamp}. URL completa y contenido omitidos.",
                                elapsed
                            )
                            val barrier = detectArchivedHtmlBarrier(html)
                            if (barrier != null) {
                                emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.FAILED, "La captura parece bloqueada: $barrier.")
                                skipArchiveValidationTrace(captureTrace, "Se detectó una barrera; no se intentó validar título ni precio.")
                                SnapshotFetch(true, null, barrier)
                            } else {
                                emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.PASSED, "HTML recibido y sin marcadores comunes de cookies, CAPTCHA o bloqueo.")
                                val document = Jsoup.parse(html)
                                val sourceQuote = parseArchivedPrice(
                                    document = document,
                                    expectedTitle = expectedTitle,
                                    originalUrl = capture.originalUrl,
                                    snapshotUrl = snapshotUrl,
                                    observedAt = capture.timestamp,
                                    acceptedCurrencies = setOf("EUR", "USD")
                                )
                                val quote = when (sourceQuote?.currency?.uppercase(Locale.ROOT)) {
                                    "USD" -> sourceQuote?.let { convertUsdQuoteToEurAtCaptureDate(it, captureTrace) }
                                    else -> sourceQuote
                                }
                                reportArchiveFieldTrace(captureTrace, document, expectedTitle, capture.originalUrl, quote, sourceQuote)
                                val secondarySourceQuotes = parseArchivedPricesForKnownProducts(
                                    document = document,
                                    primaryExpectedTitle = expectedTitle,
                                    knownProductTitlesById = knownProductTitlesById,
                                    originalUrl = capture.originalUrl,
                                    snapshotUrl = snapshotUrl,
                                    observedAt = capture.timestamp,
                                    acceptedCurrencies = setOf("EUR", "USD")
                                )
                                val secondaryQuotes = secondarySourceQuotes.mapNotNull { secondary ->
                                    when (secondary.currency.uppercase(Locale.ROOT)) {
                                        "USD" -> convertUsdQuoteToEurAtCaptureDate(secondary, captureTrace)
                                        "EUR" -> secondary
                                        else -> null
                                    }
                                }
                                SnapshotFetch(
                                    fetched = true,
                                    quote = quote,
                                    additionalQuotes = secondaryQuotes,
                                    diagnosticCode = when {
                                        quote != null -> null
                                        sourceQuote?.currency?.equals("USD", ignoreCase = true) == true -> "ARCHIVE_USD_FX_UNAVAILABLE"
                                        else -> diagnoseArchivedPrice(document, expectedTitle, capture.originalUrl)
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (blocked: SnapshotRetryBlockedException) {
                emitWaybackTrace(
                    captureTrace, downloadStep, WaybackTraceStatus.SKIPPED,
                    "Reintento DNS omitido porque la pausa global de Internet Archive está activa."
                )
                emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.SKIPPED, "No hubo HTML que evaluar.")
                skipArchiveValidationTrace(captureTrace, "El reintento quedó bloqueado por el control de ritmo.")
                SnapshotFetch(false, null, blocked.diagnosticCode)
            } catch (failure: Exception) {
                val code = classifyNetworkFailure("SNAPSHOT", failure)
                val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                val originalHost = runCatching { URI(capture.originalUrl).host }.getOrNull()
                    ?.lowercase(Locale.ROOT) ?: "host-desconocido"
                val rootCause = generateSequence(failure as Throwable?) { it.cause }.lastOrNull()
                val rootCauseType = rootCause?.javaClass?.simpleName?.take(60) ?: "desconocida"
                val budgetSeconds = snapshotTimeoutSeconds.coerceAtLeast(1L)
                emitWaybackTrace(
                    captureTrace, downloadStep, WaybackTraceStatus.FAILED,
                    "Descarga fallida: $code; causa raíz $rootCauseType; intentos de red $snapshotRequestAttempts; presupuesto ${budgetSeconds}s; destino de archivo web.archive.org; host original $originalHost; captura ${capture.rawTimestamp}; ruta $captureSourceStage. No se recibió una respuesta HTML; URL completa omitida.",
                    elapsed
                )
                emitWaybackTrace(captureTrace, evaluateStep, WaybackTraceStatus.FAILED, "No fue posible evaluar el HTML.")
                skipArchiveValidationTrace(captureTrace, "La descarga terminó con error de red.")
                SnapshotFetch(false, null, code)
            }
        }
    }

    /**
     * Converts an explicitly identified USD archive price with a historical rate dated no later
     * than the capture. Failed/missing rates never fall back to today's exchange rate.
     */
    internal suspend fun convertUsdQuoteToEurAtCaptureDate(
        quote: ArchivedPriceQuote,
        onTrace: ((WaybackTraceEvent) -> Unit)? = null
    ): ArchivedPriceQuote? = withContext(Dispatchers.IO) {
        if (!quote.currency.equals("USD", ignoreCase = true)) return@withContext quote
        if (!quote.price.isFinite() || quote.price !in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE ||
            quote.observedAt <= 0L
        ) {
            emitWaybackTrace(
                onTrace, 14, WaybackTraceStatus.FAILED,
                "El importe USD no supera la validación numérica; no se convirtió ni se guardó.",
                diagnosticStep = 17
            )
            return@withContext null
        }

        val utc = TimeZone.getTimeZone("UTC")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = utc }
        val captureDate = dateFormat.format(Date(quote.observedAt))
        val fromCalendar = Calendar.getInstance(utc, Locale.ROOT).apply {
            time = Date(quote.observedAt)
            add(Calendar.DAY_OF_YEAR, -14)
        }
        val fromDate = dateFormat.format(fromCalendar.time)
        val cacheKey = "fx_usd_eur_capture_" + captureDate
        val cachedRate = preferences.getString(cacheKey + "_rate", null)?.toDoubleOrNull()
        val cachedDate = preferences.getString(cacheKey + "_date", null)
        val cachedRateIsValid = cachedRate != null && cachedDate != null &&
            cachedDate <= captureDate && cachedRate in 0.05..5.0
        var rate: Double? = cachedRate.takeIf { cachedRateIsValid }
        var rateDate: String? = cachedDate.takeIf { cachedRateIsValid }
        if (rate == null || rateDate == null) {
            rate = null
            rateDate = null
            try {
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.frankfurter.dev")
                    .addPathSegment("v2")
                    .addPathSegment("rates")
                    .addQueryParameter("from", fromDate)
                    .addQueryParameter("to", captureDate)
                    .addQueryParameter("base", "USD")
                    .addQueryParameter("quotes", "EUR")
                    .addQueryParameter("providers", "ECB")
                    .build()
                val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
                fxHttp.newCall(request).execute().use { response ->
                    val body = readBodyLimited(response.body, 96 * 1024)
                    if (response.isSuccessful && body != null) {
                        val rows = JSONArray(body)
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val candidateDate = row.optString("date")
                            val candidateBase = row.optString("base").uppercase(Locale.ROOT)
                            val candidateQuote = row.optString("quote").uppercase(Locale.ROOT)
                            val candidateRate = row.optDouble("rate", Double.NaN)
                            if (candidateDate.matches(Regex("""\d{4}-\d{2}-\d{2}""")) &&
                                candidateDate <= captureDate &&
                                candidateBase == "USD" && candidateQuote == "EUR" &&
                                candidateRate.isFinite() && candidateRate in 0.05..5.0 &&
                                (rateDate == null || candidateDate > rateDate!!)
                            ) {
                                rate = candidateRate
                                rateDate = candidateDate
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // FX is optional: a network/API failure must never create an estimated exchange rate.
            }
            val newlyResolvedRate = rate
            val newlyResolvedDate = rateDate
            if (newlyResolvedRate != null && !newlyResolvedDate.isNullOrBlank() &&
                newlyResolvedDate <= captureDate
            ) {
                preferences.edit()
                    .putString(cacheKey + "_rate", newlyResolvedRate.toString())
                    .putString(cacheKey + "_date", newlyResolvedDate)
                    .apply()
            }
        }

        val usableRate = rate
        val effectiveDate = rateDate
        if (usableRate == null || effectiveDate.isNullOrBlank() || effectiveDate > captureDate) {
            emitWaybackTrace(
                onTrace, 14, WaybackTraceStatus.FAILED,
                "Se detectó un precio en USD, pero no se encontró un cambio histórico USD/EUR verificable anterior o igual a la captura. Precio descartado.",
                diagnosticStep = 17
            )
            return@withContext null
        }

        val convertedPrice = quote.price * usableRate
        if (!convertedPrice.isFinite() || convertedPrice !in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE) {
            emitWaybackTrace(
                onTrace, 14, WaybackTraceStatus.FAILED,
                "La conversión histórica quedó fuera del rango de precios permitido; no se guardó.",
                diagnosticStep = 17
            )
            return@withContext null
        }

        emitWaybackTrace(
            onTrace, 14, WaybackTraceStatus.PASSED,
            "Precio USD convertido a EUR con tipo de cambio histórico " +
                effectiveDate + " (1 USD = " + String.format(Locale.ROOT, "%.4f", usableRate) +
                " EUR). Fuente: Frankfurter/ECB.",
            diagnosticStep = 17
        )
        quote.copy(
            price = convertedPrice,
            currency = "EUR",
            sourceCurrency = "USD",
            sourcePrice = quote.price,
            exchangeRate = usableRate,
            exchangeRateDate = effectiveDate,
            conversionSource = "Frankfurter/ECB"
        )
    }

    private fun detectArchivedHtmlBarrier(html: String): String? {
        val head = html.take(160_000).lowercase(Locale.ROOT)
        return when {
            // Cloudflare challenge pages archived by Wayback often omit cf-chl-* markers.
            "cf-chl-" in head || "cloudflare ray id" in head ||
                "checking your browser" in head || "just a moment" in head ||
                "ddos protection" in head || "attention required! | cloudflare" in head ||
                "performing security verification" in head ||
                "checking if the site connection is secure" in head ->
                "CLOUDFLARE_CHALLENGE"
            "verify you are human" in head || "prove you are human" in head ||
                "robot check" in head || "complete the captcha" in head ||
                "complete this captcha" in head || "solve the captcha" in head ||
                "enter the captcha" in head || "please enter the characters" in head ||
                "enable javascript and cookies to continue" in head ||
                Regex("""(?is)<title[^>]*>[^<]*(?:captcha|robot check|human verification|verification required)[^<]*</title>""")
                    .containsMatchIn(head) -> "CAPTCHA_OR_ROBOT_CHECK"
            "access denied" in head || "request blocked" in head || "temporarily blocked" in head ->
                "ACCESS_DENIED_OR_BLOCKED"
            Regex("""(?is)<title[^>]*>\s*(404|page not found|página no encontrada|not found)\b""")
                .containsMatchIn(head) -> "ARCHIVED_PAGE_404"
            else -> null
        }
    }

    private fun reportArchiveFieldTrace(
        onTrace: ((WaybackTraceEvent) -> Unit)?,
        document: Document,
        expectedTitle: String,
        originalUrl: String,
        quote: ArchivedPriceQuote?,
        sourceQuote: ArchivedPriceQuote? = quote
    ) {
        val jsonLd = extractJsonLdProductOffers(document)
        val pageTitles = extractArchivedPageTitles(document)
        val titles = (jsonLd.map { it.first } + pageTitles).filter { it.isNotBlank() }
        val expectedTitleForTrace = expectedTitle.replace(Regex("""\s+"""), " ").trim().take(100)
        val detectedTitlesForTrace = titles.take(3)
            .joinToString(" | ") { it.replace(Regex("""\s+"""), " ").trim().take(100) }
        val structuredMatch = jsonLd.any { isMatchingTitleForArchivedUrl(expectedTitle, it.first, originalUrl) }
        val structuredConflict = jsonLd.firstOrNull {
            hasExplicitProductVariantConflict(expectedTitle, it.first) &&
                !isMatchingTitleForArchivedUrl(expectedTitle, it.first, originalUrl)
        }
        val pageTitleMatch = pageTitles.any { isMatchingTitleForArchivedUrl(expectedTitle, it, originalUrl) }
        val titleMatches = structuredMatch || (structuredConflict == null && pageTitleMatch)
        emitWaybackTrace(
            onTrace, 12,
            if (titles.isEmpty() || !titleMatches) WaybackTraceStatus.FAILED else WaybackTraceStatus.PASSED,
            when {
                titles.isEmpty() -> "No se encontró título en JSON-LD, og:title, twitter:title ni <title>."
                structuredConflict != null && !structuredMatch ->
                    "La oferta estructurada identifica otra variante («" +
                        structuredConflict.first.replace(Regex("""\s+"""), " ").trim().take(100) +
                        "»); el título general de la página no basta para confirmar «" +
                        expectedTitleForTrace + "». No se aceptará su precio."
                titleMatches -> "Título/modelo de la oferta archivada coincide con el producto seleccionado."
                else -> "No coincide la identidad. Producto esperado: «$expectedTitleForTrace». Título(s) archivado(s): «$detectedTitlesForTrace». No se aceptará un precio de otra variante."
            }
        )

        val metaPrice = listOf("product:price:amount", "og:price:amount", "price", "product:price")
            .firstNotNullOfOrNull { key ->
                (readMeta(document, setOf(key)) ?: readItemPropValue(document, key))?.let(::parsePositivePrice)
            }
        val metaCurrency = listOf("product:price:currency", "og:price:currency", "pricecurrency")
            .firstNotNullOfOrNull { key ->
                readMeta(document, setOf(key)) ?: readItemPropValue(document, key)
            }?.uppercase(Locale.ROOT)
        val visiblePrice = if (quote == null) {
            extractVisibleProductPrice(document, metaCurrency, setOf("EUR", "USD"))?.first
        } else null
        val hasPriceCandidate = jsonLd.isNotEmpty() || metaPrice != null || visiblePrice != null || quote != null || sourceQuote != null
        emitWaybackTrace(
            onTrace, 13,
            if (hasPriceCandidate) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
            when {
                jsonLd.isNotEmpty() -> "Precio detectado en metadatos JSON-LD (${jsonLd.size} oferta(s))."
                metaPrice != null -> "Precio numérico detectado en metadatos de la página."
                visiblePrice != null -> "Precio visible detectado en un bloque de precio del producto y con moneda EUR."
                quote != null -> "Precio histórico verificado para el producto."
                else -> "No se encontró precio numérico en datos estructurados, metadatos ni bloques de precio reconocidos."
            }
        )
        val prices = jsonLd.map { it.second } + listOfNotNull(metaPrice, visiblePrice, sourceQuote?.price, quote?.price)
        val numericValid = prices.any { it.isFinite() && it in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE }
        val valid = numericValid && quote != null &&
            quote.currency.equals("EUR", ignoreCase = true) &&
            quote.price.isFinite() && quote.price in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE
        emitWaybackTrace(
            onTrace, 14,
            if (valid) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
            when {
                valid && quote != null && quote.sourceCurrency.equals("USD", ignoreCase = true) ->
                    "Importe final convertido a EUR validado; tipo de cambio y fecha efectiva constan en el detalle anterior."
                valid && quote != null -> "Precio validado: moneda EUR y rango permitido; identidad aceptada."
                !titleMatches ->
                    "Se detectó un importe, pero no coincide la identidad del producto (paso 15); el precio se descarta."
                sourceQuote?.currency?.equals("USD", ignoreCase = true) == true && quote == null ->
                    "Se encontró un importe USD, pero no hay cambio histórico fechado verificable; no se guardó."
                !numericValid -> "No hay importe numérico positivo dentro del rango permitido."
                else -> "Se detectó un importe, pero no se confirmó EUR ni una conversión histórica válida."
            }
        )
    }

    private fun skipArchiveValidationTrace(
        onTrace: ((WaybackTraceEvent) -> Unit)?,
        detail: String
    ) {
        emitWaybackTrace(onTrace, 12, WaybackTraceStatus.SKIPPED, detail)
        emitWaybackTrace(onTrace, 13, WaybackTraceStatus.SKIPPED, detail)
        emitWaybackTrace(onTrace, 14, WaybackTraceStatus.SKIPPED, detail)
    }

    private fun diagnoseArchivedPrice(document: Document, expectedTitle: String, originalUrl: String): String {
        val jsonLd = extractJsonLdProductOffers(document)
        val pageTitles = extractArchivedPageTitles(document)
        val metaPrice = listOf(
            "product:price:amount", "og:price:amount", "price", "product:price"
        ).firstNotNullOfOrNull { key ->
            (readMeta(document, setOf(key)) ?: readItemPropValue(document, key))?.let(::parsePositivePrice)
        }
        val metaCurrency = listOf(
            "product:price:currency", "og:price:currency", "pricecurrency"
        ).firstNotNullOfOrNull { key ->
            readMeta(document, setOf(key)) ?: readItemPropValue(document, key)
        }?.uppercase(Locale.ROOT)
        if (pageTitles.isEmpty() && jsonLd.isEmpty()) return "ARCHIVE_TITLE_MISSING"
        val visiblePrice = extractVisibleProductPrice(document, metaCurrency)?.first
        if (metaPrice == null && jsonLd.isEmpty() && visiblePrice == null) return "ARCHIVE_PRICE_METADATA_MISSING"
        val candidates = jsonLd +
            (if (pageTitles.isNotEmpty() && metaPrice != null && !metaCurrency.isNullOrBlank()) {
                pageTitles.map { title -> Triple(title, metaPrice, metaCurrency) }
            } else emptyList()) +
            (if (pageTitles.isNotEmpty() && visiblePrice != null) {
                pageTitles.map { title -> Triple(title, visiblePrice, "EUR") }
            } else emptyList())
        if (jsonLd.isNotEmpty() &&
            jsonLd.none { isMatchingTitleForArchivedUrl(expectedTitle, it.first, originalUrl) } &&
            jsonLd.any { hasExplicitProductVariantConflict(expectedTitle, it.first) }
        ) return "ARCHIVE_PRODUCT_TITLE_MISMATCH"
        if (candidates.none { isMatchingTitleForArchivedUrl(expectedTitle, it.first, originalUrl) }) {
            return "ARCHIVE_PRODUCT_TITLE_MISMATCH"
        }
        if (candidates.none { it.third.equals("EUR", ignoreCase = true) }) {
            return "ARCHIVE_EUR_PRICE_MISSING"
        }
        return "ARCHIVE_PRICE_AMBIGUOUS_OR_INVALID"
    }

    /**
     * Decomposes retailer links into the actual destination, host/path, all query parameters and
     * variant identifiers. Google Shopping/Ads wrappers are resolved before archive eligibility.
     */
    internal fun decomposeProductUrl(input: String): DecomposedProductUrl? {
        val resolution = extractDirectRetailerUrlWithDepth(input) ?: return null
        val resolved = resolution.first
        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        if (!isEligibleDirectUrl(resolved) || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val merchantDomain = normalizeMerchantDomain(host) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        val pathSegments = path.split('/')
            .filter { it.isNotBlank() }
            .map(::decodePathUrlComponent)
        val rawQueryParts = uri.rawQuery?.split('&').orEmpty().filter { it.isNotBlank() }
        val decodedPairs = rawQueryParts.mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            val key = decodeQueryUrlComponent(pair[0]).lowercase(Locale.ROOT)
            if (key.isBlank()) return@mapNotNull null
            key to decodeQueryUrlComponent(pair.getOrElse(1) { "" })
        }
        val queryParameters = decodedPairs.groupBy({ it.first }, { it.second })
        val trackingNames = setOf(
            "gclid", "dclid", "fbclid", "msclkid", "mc_eid", "mc_cid", "igshid",
            "gad_source", "gad_campaignid", "gbraid", "wbraid", "ref", "ref_", "tag", "linkcode",
            "campid", "scid", "affid", "affiliate", "sourceid", "srsltid",
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_name", "campaignid", "adgroupid", "creative", "network",
            "gclsrc", "irclickid", "twclid", "ttclid", "li_fat_id", "epik", "yclid",
            "awc", "afftrack", "zanpid", "s_kwcid", "mkt_tok", "_gl", "_ga"
        )
        val removedTracking = decodedPairs.map { it.first }
            .filter { it.startsWith("utm_") || it in trackingNames }
            .toSet()
        val variantKeys = setOf(
            "sku", "skuid", "sku_id", "variant", "variantid", "variant_id", "id_variant",
            "variant_sku", "variant_sku_id", "variation", "variationid", "variation_id",
            "productid", "product_id", "id_product", "id_product_attribute", "offerid", "offer_id",
            "pid", "itemid", "item_id", "asin", "mpn", "gtin", "ean", "model", "modelid",
            "color", "colour", "colorid", "colourid", "storage", "capacity", "size",
            "configuration", "config", "option", "optionid", "attribute_id", "device", "style", "styleid"
        )
        val variantParameters = queryParameters.filterKeys { it in variantKeys }
        val identifiers = linkedSetOf<String>()
        pathSegments.filter { segment ->
            segment.length >= 2 && (segment.any(Char::isDigit) ||
                Regex("""(?i)[a-z]{1,8}\d[a-z0-9]{1,12}""").matches(segment))
        }.forEach(identifiers::add)
        variantParameters.values.flatten().filter { it.isNotBlank() }
            .forEach { identifiers += it.take(100) }
        val canonicalQuery = rawQueryParts.filterNot { part ->
            val key = decodeQueryUrlComponent(part.substringBefore('=')).lowercase(Locale.ROOT)
            key.startsWith("utm_") || key in trackingNames
        }
        val base = scheme + "://" + uri.rawAuthority + path
        val canonicalUrl = base + if (canonicalQuery.isNotEmpty()) {
            "?" + canonicalQuery.joinToString("&")
        } else ""
        return DecomposedProductUrl(
            resolvedUrl = resolved.substringBefore('#'),
            canonicalUrl = canonicalUrl,
            scheme = scheme,
            host = host,
            merchantDomain = merchantDomain,
            path = path,
            pathSegments = pathSegments,
            queryParameters = queryParameters,
            variantParameters = variantParameters,
            productIdentifiers = identifiers,
            trackingParameters = removedTracking,
            fragment = uri.rawFragment?.let(::decodePathUrlComponent),
            redirectDepth = resolution.second
        )
    }

    private fun decodeQueryUrlComponent(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)

    private fun decodePathUrlComponent(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(value)

    internal fun buildDirectUrlCandidates(originalUrl: String): List<String> {
        val parts = decomposeProductUrl(originalUrl) ?: return emptyList()
        val uri = runCatching { URI(parts.resolvedUrl) }.getOrNull() ?: return emptyList()
        val candidates = linkedSetOf(parts.resolvedUrl)
        val trackingNames = setOf(
            "gclid", "dclid", "fbclid", "msclkid", "mc_eid", "mc_cid", "igshid",
            "gad_source", "gad_campaignid", "gbraid", "wbraid", "ref", "ref_", "tag", "linkcode",
            "campid", "scid", "affid", "affiliate", "sourceid", "srsltid",
            "irclickid", "twclid", "ttclid", "li_fat_id", "epik", "yclid",
            "awc", "afftrack", "zanpid", "s_kwcid", "mkt_tok", "_gl", "_ga"
        )
        val queryParts = uri.rawQuery?.split('&').orEmpty()
        val cleanedParts = queryParts.filterNot { part ->
            val name = decodeQueryUrlComponent(part.substringBefore('=')).lowercase(Locale.ROOT)
            name.startsWith("utm_") || name in trackingNames
        }
        val base = buildString {
            append(uri.scheme.lowercase(Locale.ROOT))
            append("://")
            append(uri.rawAuthority)
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
        }
        if (cleanedParts.isNotEmpty()) {
            candidates += base + "?" + cleanedParts.joinToString("&")
        } else if (parts.trackingParameters.isNotEmpty()) {
            candidates += base
        }

        // Keep functional SKU/colour/capacity queries untouched. Add a slash probe only when the
        // URL is queryless; such a probe is common for canonical retailer product pages.
        if (candidates.size == 1 && uri.rawQuery.isNullOrBlank() && uri.rawPath.isNotBlank()) {
            val path = uri.rawPath!!
            val alternatePath = if (path.endsWith("/")) path.trimEnd('/').ifBlank { "/" } else "$path/"
            val slashVariant = buildString {
                append(uri.scheme.lowercase(Locale.ROOT))
                append("://")
                append(uri.rawAuthority)
                append(alternatePath)
            }
            // Keep the exact retailer URL last so fetchHistory's selected target does not drop a
            // real no-trailing-slash capture merely because a slash probe was also generated.
            candidates.clear()
            candidates += slashVariant
            candidates += parts.resolvedUrl
        }
        return candidates.filter(::isEligibleDirectUrl).distinct().take(2)
    }

    internal fun parseAvailabilityCapture(body: String, fallbackOriginalUrl: String): WaybackCapture? {
        val closest = try {
            JSONObject(body).optJSONObject("archived_snapshots")?.optJSONObject("closest")
        } catch (_: JSONException) {
            null
        } ?: return null
        if (!closest.optBoolean("available", false)) return null
        val rawTimestamp = closest.optString("timestamp")
        if (!rawTimestamp.matches(Regex("""\d{14}"""))) return null
        val timestamp = runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(rawTimestamp)?.time
        }.getOrNull() ?: return null
        val archivedSnapshotUrl = closest.optString("url")
        val originalFromSnapshot = Regex(
            """(?i)/web/\d{14}(?:id_|if_)?/(https?://.+)$"""
        ).find(archivedSnapshotUrl)?.groupValues?.getOrNull(1)
        val original = originalFromSnapshot?.takeIf(::isEligibleDirectUrl)
            ?: fallbackOriginalUrl.takeIf(::isEligibleDirectUrl)
            ?: return null
        return WaybackCapture(rawTimestamp, timestamp, original)
    }

    /**
     * Manufacturer product URLs usually identify a model family, not its memory or colour SKU.
     * Strip those variant-only words when generating canonical Apple/Samsung product-page
     * candidates; exact merchant URLs, when available, are still tried first.
     */
    private fun canonicalRetailerProductSlug(slug: String): String {
        val words = slug.split('-').filter { it.isNotBlank() }
        val simpleColors = setOf(
            "black", "white", "blue", "pink", "yellow", "green", "purple", "red",
            "silver", "gold", "gray", "grey", "midnight", "starlight", "graphite",
            "ultramarine", "teal", "indigo", "violet", "lavender", "coral", "titanium",
            "natural", "negro", "negra", "blanco", "blanca", "azul", "rosa", "amarillo",
            "amarilla", "verde", "morado", "morada", "rojo", "roja", "plata", "dorado",
            "dorada", "gris", "titanio"
        )
        val colorPairs = setOf(
            "space gray", "space black", "natural titanium", "desert titanium",
            "white titanium", "black titanium", "blue titanium", "purple titanium",
            "alpine green", "sierra blue", "deep purple"
        )
        val result = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val next = words.getOrNull(index + 1)
            val pair = next?.let { "$word $it" }
            when {
                pair != null && pair in colorPairs -> index += 2
                Regex("""^\d{1,4}(?:gb|tb)$""").matches(word) -> index += 1
                Regex("""^\d{1,4}$""").matches(word) && next != null && next in setOf("gb", "tb") -> index += 2
                word in simpleColors -> index += 1
                else -> {
                    result += word
                    index += 1
                }
            }
        }
        return result.joinToString("-").replace(Regex("-+"), "-").trim('-')
    }

    /**
     * Apple's generic iPhone 15 family URL can expose JSON-LD for a sibling model (notably
     * iPhone 15 Plus) instead of the requested SKU. Build the official Spain configurator path
     * when model, storage and colour are known, so Availability looks up the exact archived
     * variant before spending its small probe budget on generic family pages.
     */
    private fun buildAppleIphone15VariantUrl(host: String, expectedTitle: String): String? {
        val normalized = normalizeTitle(expectedTitle)
        if (!Regex("""\biphone\s+15\b""").containsMatchIn(normalized)) return null

        val variantTokens = productModelVariantTokens(normalized)
        // iPhone 15's Apple configurator has base and Plus variants only. Do not guess a route for
        // Pro/Max/other trims, whose page layout and display dimensions differ.
        if (variantTokens.any { it != "plus" }) return null

        val capacity = Regex("""\b(128|256|512)\s*gb\b""")
            .find(normalized)?.groupValues?.get(1) ?: return null
        val colourSlug = when (productColourFamily(normalized)) {
            "black" -> "negro"
            "blue" -> "azul"
            "green" -> "verde"
            "yellow" -> "amarillo"
            "pink" -> "rosa"
            else -> return null
        }
        val display = if ("plus" in variantTokens) "6,7" else "6,1"

        return runCatching {
            HttpUrl.Builder()
                .scheme("https")
                .host(host)
                .addPathSegments("es/shop/buy-iphone/iphone-15")
                // Keep this as one path segment. OkHttp encodes Apple's double-prime character
                // using the same %E2%80%B3 form used by official Apple Spain product URLs.
                .addPathSegment("pantalla-de-$display″-" + capacity + "gb-$colourSlug")
                .build()
                .toString()
        }.getOrNull()
    }

    internal fun buildAvailabilityCandidateUrls(domain: String, expectedTitle: String): List<String> {
        val normalizedDomain = normalizeMerchantDomain(domain) ?: return emptyList()
        if (expectedTitle.isBlank()) return emptyList()
        val fullSlug = Normalizer.normalize(expectedTitle, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (fullSlug.isBlank()) return emptyList()
        val prefixes = when (normalizedDomain) {
            "apple.com" -> listOf("apple")
            "samsung.com" -> listOf("samsung")
            "mi.com", "xiaomi.com" -> listOf("xiaomi", "redmi", "mi")
            "sony.es", "sony.com" -> listOf("sony")
            "nike.com" -> listOf("nike")
            "adidas.com" -> listOf("adidas")
            else -> emptyList()
        }
        val slugs = linkedSetOf(fullSlug)
        prefixes.forEach { prefix ->
            if (fullSlug.startsWith("$prefix-")) slugs += fullSlug.removePrefix("$prefix-")
        }
        val hosts = domainDiscoveryHosts(normalizedDomain)
        val paths = linkedSetOf<String>()
        val canonicalSlug = canonicalRetailerProductSlug(fullSlug)
        // When the Shopping provider exposes only a merchant domain, prefer one canonical
        // route without colour/storage suffixes before guessing variant-specific URLs. Many
        // retailers route all purchasable variants through the same product page.
        // Keep the complete variant slug below as a fallback; no candidate is trusted without
        // checking a real archived page and validating its product title + EUR price.
        if (
            canonicalSlug.isNotBlank() &&
            canonicalSlug != fullSlug &&
            normalizedDomain != "apple.com" &&
            normalizedDomain != "samsung.com"
        ) {
            paths += "/$canonicalSlug/"
        }
        if (normalizedDomain == "apple.com") {
            // Apple canonical product pages omit storage and colour (e.g. /es/iphone-15/).
            // Put the normalised model-family URL ahead of SKU-shaped guesses.
            val canonicalAppleSlug = canonicalRetailerProductSlug(fullSlug).removePrefix("apple-")
            if (canonicalAppleSlug.startsWith("iphone-") ||
                canonicalAppleSlug.startsWith("ipad-") ||
                canonicalAppleSlug.startsWith("macbook-")
            ) {
                // Probe Apple's purchase flow first because its archived HTML is more likely
                // to expose model/offer metadata than the general marketing landing page.
                if (canonicalAppleSlug.startsWith("iphone-")) {
                    paths += "/es/shop/buy-iphone/$canonicalAppleSlug/"
                }
                paths += "/es/$canonicalAppleSlug/"
            }
            slugs.filter { it.startsWith("iphone-") || it.startsWith("ipad-") || it.startsWith("macbook-") }
                .forEach { paths += "/es/$it/" }
        }
        if (normalizedDomain == "samsung.com") {
            val canonicalSamsungSlugs = slugs.map(::canonicalRetailerProductSlug).distinct()
            canonicalSamsungSlugs.filter { it.startsWith("galaxy-") }
                .forEach { paths += "/es/smartphones/galaxy-s/$it/" }
            slugs.filter { it.startsWith("galaxy-") }
                .forEach { paths += "/es/smartphones/galaxy-s/$it/" }

            // Saved product titles are sometimes shortened by shopping providers to
            // "Samsung S26" or "Samsung S26 Ultra", omitting "Galaxy". Derive the
            // canonical Samsung product path from the model code before trying generic
            // slugs such as /s26/, which are very unlikely to be archived product pages.
            val samsungModel = Regex(
                """(?i)\bS\s*(\d{1,2})(?:\s*(Ultra|Plus|FE|Edge))?\b"""
            ).find(expectedTitle)
            if (samsungModel != null) {
                val modelNumber = samsungModel.groupValues[1]
                val variant = samsungModel.groupValues.getOrNull(2)
                    .orEmpty()
                    .lowercase(Locale.ROOT)
                    .ifBlank {
                        if (Regex("""(?i)\bS\s*\d{1,2}\s*\+""").containsMatchIn(expectedTitle)) {
                            "plus"
                        } else {
                            ""
                        }
                    }
                val modelSlug = "galaxy-s$modelNumber" +
                    if (variant.isNotBlank()) "-$variant" else ""
                paths.add("/es/smartphones/galaxy-s/$modelSlug/")
            }
        }
        slugs.forEach { slug ->
            paths += "/$slug/"
            paths += "/es/$slug/"
            paths += "/es-es/$slug/"
            paths += "/products/$slug/"
            paths += "/product/$slug/"
            paths += "/p/$slug/"
        }
        // Known retailer search URLs can contain Product/Offer JSON-LD for multiple items.
        // Prefer that bounded, product-specific query over guessing more paths, but still validate
        // the archived product title and EUR price before accepting any result.
        val storeSearchUrl = Store.ALL_STORES.firstOrNull { store ->
            normalizeMerchantDomain(store.domain) == normalizedDomain && store.supportsProductSearch
        }?.searchUrlFor(expectedTitle)?.takeIf(::isEligibleDirectUrl)
        // Interleave www and apex hosts so a missing www archive does not consume the whole
        // candidate budget before the root host is tried.
        val guessedProductUrls = paths.flatMap { path ->
            hosts.map { host ->
                HttpUrl.Builder().scheme("https").host(host).addPathSegments(path.trim('/')).addPathSegment("").build().toString()
            }
        }
        // For every retailer, try likely product-specific routes before generic search pages;
        // product pages are more likely to expose the exact model and a verifiable historical price.
        // Samsung gets a stricter priority for its canonical model-page paths below.
        val orderedCandidates = if (normalizedDomain == "samsung.com") {
            val canonicalSamsungUrls = guessedProductUrls.filter { candidate ->
                runCatching {
                    URI(candidate).rawPath.orEmpty()
                        .startsWith("/es/smartphones/galaxy-s/")
                }.getOrDefault(false)
            }
            val canonicalSet = canonicalSamsungUrls.toSet()
            // Try up to three canonical model routes before a generic retailer search page.
            // Product-specific captures are much more likely to expose the right model and price.
            canonicalSamsungUrls.take(3) +
                listOfNotNull(storeSearchUrl) +
                canonicalSamsungUrls.drop(3) +
                guessedProductUrls.filterNot { it in canonicalSet }
        } else {
            // Keep three product-specific guesses ahead of the search URL in the normal
            // candidate list. The transient-error fallback promotes the search URL separately,
            // without weakening this broader discovery order.
            guessedProductUrls.take(3) +
                listOfNotNull(storeSearchUrl) +
                guessedProductUrls.drop(3)
        }
        // Try the exact Apple SKU configurator on each known host before any family/marketing
        // page. www and apex can have different archive coverage; using only the first host meant
        // a missing SKU capture could push the search onto a broad page whose JSON-LD lists Plus
        // offers, causing step 15 to reject every price despite a potentially archived exact SKU.
        // The identity validator remains strict: this only changes candidate discovery/order.
        val appleVariantUrls = if (normalizedDomain == "apple.com") {
            hosts.mapNotNull { host -> buildAppleIphone15VariantUrl(host, expectedTitle) }
        } else emptyList()
        return (appleVariantUrls + orderedCandidates)
            .distinct()
            .take(MAX_AVAILABILITY_URL_CANDIDATES)
    }

    private fun availabilityTargetDates(now: Long, lookbackDays: Int, count: Int): List<String> {
        val days = lookbackDays.coerceAtLeast(1).toLong()
        return (0 until count.coerceAtLeast(1)).map { index ->
            val offsetDays = if (count <= 1) 0L else days * index / (count - 1)
            formatUtc((now - offsetDays * DAY_MS).coerceAtLeast(0L), "yyyyMMdd")
        }.distinct()
    }

    private data class AvailabilityProbe(
        val capture: WaybackCapture? = null,
        val requestFailed: Boolean = false,
        val diagnosticCode: String? = null
    )


    internal fun shouldSkipRecentAttempt(
        key: String,
        now: Long,
        forceRefresh: Boolean
    ): Boolean = !forceRefresh && wasAttemptedRecently(key, now)

    internal fun wasAttemptedRecently(key: String, now: Long): Boolean {
        val lastAttempt = preferences.getLong(key, 0L)
        if (lastAttempt <= 0L || now < lastAttempt) return false
        val hadQuotes = preferences.getBoolean("${key}_has_quotes", false)
        val failed = preferences.getBoolean("${key}_failed", false)
        val rateLimited = preferences.getBoolean("${key}_rate_limited", false)
        // Failed lookups are cached briefly. For an explicit HTTP 429, the global Retry-After
        // cooldown is authoritative; let the same product retry as soon as that pause expires.
        if (failed && !hadQuotes && rateLimited) {
            return preferences.getLong(KEY_RATE_LIMIT_UNTIL, 0L) > now
        }
        val retryInterval = when {
            hadQuotes -> RETRY_INTERVAL_MS
            failed -> FAILURE_RETRY_INTERVAL_MS
            else -> NEGATIVE_RETRY_INTERVAL_MS
        }
        return now - lastAttempt < retryInterval
    }

    private suspend fun <T> serializedArchiveLookup(block: suspend () -> T): T {
        GLOBAL_BACKFILL_MUTEX.lock()
        return try {
            block()
        } finally {
            GLOBAL_BACKFILL_MUTEX.unlock()
        }
    }

    private suspend fun awaitArchiveRequestTurn(): String? {
        if (activeRateLimitUntil(System.currentTimeMillis()) != null) return "WAYBACK_RATE_LIMITED"
        val now = System.currentTimeMillis()
        val lastRequestAt = preferences.getLong(KEY_LAST_AVAILABILITY_REQUEST_AT, 0L)
        val waitMs = computeAvailabilityDelayMs(lastRequestAt, now)
        if (waitMs > 0L) delay(waitMs)
        // Recheck after waiting; another client may have activated the cooldown.
        if (activeRateLimitUntil(System.currentTimeMillis()) != null) return "WAYBACK_RATE_LIMITED"
        preferences.edit().putLong(KEY_LAST_AVAILABILITY_REQUEST_AT, System.currentTimeMillis()).apply()
        return null
    }

    internal fun computeAvailabilityDelayMs(lastRequestAt: Long, now: Long): Long {
        if (lastRequestAt <= 0L) return 0L
        // Cap wait time to one interval so clock changes cannot stall history for hours.
        return (lastRequestAt + MIN_AVAILABILITY_REQUEST_INTERVAL_MS - now)
            .coerceIn(0L, MIN_AVAILABILITY_REQUEST_INTERVAL_MS)
    }

    internal fun computeRateLimitUntil(retryAfter: String?, now: Long): Long {
        val fallback = now + DEFAULT_RATE_LIMIT_COOLDOWN_MS
        val header = retryAfter?.trim().orEmpty()
        if (header.isEmpty()) return fallback
        val seconds = header.toLongOrNull()
        val requestedUntil = if (seconds != null) {
            now + seconds.coerceIn(0L, MAX_RATE_LIMIT_COOLDOWN_MS / 1000L) * 1000L
        } else {
            runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(header)?.time
            }.getOrNull() ?: return fallback
        }
        return requestedUntil.coerceIn(
            now + MIN_RATE_LIMIT_COOLDOWN_MS,
            now + MAX_RATE_LIMIT_COOLDOWN_MS
        )
    }

    /**
     * Escalates the local pause after repeated HTTP 429 responses within one day. Some archive
     * limits are imposed upstream without a useful Retry-After header, so retrying after the same
     * five-minute default can reproduce the same block. The pause honours a longer server-provided
     * Retry-After, never exceeds six hours, and does not probe Wayback to test whether it expired.
     */
    internal fun computeAdaptiveRateLimitUntil(
        retryAfter: String?,
        now: Long,
        consecutiveRateLimitCount: Int
    ): Long {
        val hits = consecutiveRateLimitCount.coerceIn(1, MAX_RATE_LIMIT_HITS)
        val exponent = (hits - 1).coerceAtMost(10)
        val multiplier = 1L shl exponent
        val adaptiveDelay = (DEFAULT_RATE_LIMIT_COOLDOWN_MS * multiplier)
            .coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS)
        val serverDelayUntil = computeRateLimitUntil(retryAfter, now)
        return maxOf(serverDelayUntil, now + adaptiveDelay)
            .coerceAtMost(now + MAX_RATE_LIMIT_COOLDOWN_MS)
    }

    private fun activateRateLimit(retryAfter: String?, now: Long): Long {
        val lastRateLimitAt = preferences.getLong(KEY_RATE_LIMIT_LAST_HIT_AT, 0L)
        val previousHits = preferences.getInt(KEY_RATE_LIMIT_HIT_COUNT, 0)
        val repeatedWithinWindow = lastRateLimitAt > 0L &&
            now >= lastRateLimitAt &&
            now - lastRateLimitAt <= RATE_LIMIT_ESCALATION_WINDOW_MS
        val hitCount = if (repeatedWithinWindow) {
            (previousHits + 1).coerceAtMost(MAX_RATE_LIMIT_HITS)
        } else {
            1
        }
        val until = maxOf(
            preferences.getLong(KEY_RATE_LIMIT_UNTIL, 0L),
            computeAdaptiveRateLimitUntil(retryAfter, now, hitCount)
        )
        preferences.edit()
            .putLong(KEY_RATE_LIMIT_UNTIL, until)
            .putLong(KEY_RATE_LIMIT_LAST_HIT_AT, now)
            .putInt(KEY_RATE_LIMIT_HIT_COUNT, hitCount)
            .apply()
        return until
    }

    /**
     * Returns the end of Internet Archive's shared 429 cooldown, if one is still active.
     * The UI uses this to explain when a retry can be sent without probing the archive again.
     */
    internal fun activeRateLimitUntil(now: Long = System.currentTimeMillis()): Long? =
        preferences.getLong(KEY_RATE_LIMIT_UNTIL, 0L).takeIf { it > now }

    internal fun clearRateLimitForTests() {
        preferences.edit()
            .remove(KEY_RATE_LIMIT_UNTIL)
            .remove(KEY_RATE_LIMIT_LAST_HIT_AT)
            .remove(KEY_RATE_LIMIT_HIT_COUNT)
            .remove(KEY_LAST_AVAILABILITY_REQUEST_AT)
            .apply()
    }

    private fun rememberAttempt(
        key: String,
        now: Long,
        hasQuotes: Boolean,
        failed: Boolean,
        rateLimited: Boolean = false
    ) {
        preferences.edit()
            .putLong(key, now)
            .putBoolean("${key}_has_quotes", hasQuotes)
            .putBoolean("${key}_failed", failed)
            .putBoolean("${key}_rate_limited", rateLimited)
            .apply()
    }

    private suspend fun queryCdxIndexOnly(
        cdxUrl: HttpUrl,
        cdxTimeoutSeconds: Long,
        cdxStage: String
    ): CdxIndexOutcome {
        val cdxHttp = http.newBuilder()
            .readTimeout(cdxTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(cdxTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(cdxUrl).header("User-Agent", USER_AGENT).get().build()
        // The caller queryCdxAndFetchPrices applies the shared persisted 429 cooldown and
        // 1.5-second pacing gate before entering this response parser. Do not gate twice here.
        return try {
            executeCdxWithTransientRetry(cdxHttp, request, maxRetries = 0).use { response ->
                if (response.code == 429) {
                    activateRateLimit(response.header("Retry-After"), System.currentTimeMillis())
                    return CdxIndexOutcome(
                        requestFailed = true,
                        diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                    )
                }
                if (!response.isSuccessful) {
                    return CdxIndexOutcome(
                        requestFailed = true,
                        diagnosticCodes = setOf("${cdxStage}_HTTP_${response.code}")
                    )
                }
                val body = readBodyLimited(response.body, MAX_CDX_BODY_BYTES)
                if (body == null) {
                    return CdxIndexOutcome(
                        requestFailed = true,
                        diagnosticCodes = setOf("${cdxStage}_BODY_EMPTY_OR_LIMIT")
                    )
                }
                if (!isValidCaptureIndexResponse(body)) {
                    return CdxIndexOutcome(
                        requestFailed = true,
                        diagnosticCodes = setOf("${cdxStage}_INVALID_RESPONSE")
                    )
                }
                CdxIndexOutcome(captures = parseCaptureIndex(body), available = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CdxIndexOutcome(
                requestFailed = true,
                diagnosticCodes = setOf(classifyNetworkFailure(cdxStage, failure))
            )
        }
    }

    internal fun selectDistinctProductUrls(
        captures: List<WaybackCapture>,
        limit: Int = MAX_DOMAIN_PRODUCT_URL_CANDIDATES
    ): List<String> =
        captures.asSequence()
            .map { it.originalUrl }
            .filter(::isEligibleDirectUrl)
            .distinct()
            .take(limit.coerceAtLeast(0))
            .toList()

    private suspend fun queryCdxAndFetchPrices(
        cdxUrl: HttpUrl,
        expectedTitle: String,
        cacheKey: String,
        now: Long,
        lookbackDays: Int,
        maxSnapshots: Int,
        cdxTimeoutSeconds: Long,
        cdxStage: String,
        snapshotTimeoutSeconds: Long = SNAPSHOT_CALL_TIMEOUT_SECONDS,
        onTrace: ((WaybackTraceEvent) -> Unit)? = null,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        snapshotResultsCache: MutableMap<String, SnapshotFetch>? = null,
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null
    ): WaybackBackfillResult {
        emitWaybackTrace(onTrace, 4, WaybackTraceStatus.RUNNING,
            "$cdxStage: petición CDX preparada; URL completa omitida.")
        val startedAt = System.nanoTime()
        val cdxOutcome = availabilitySemaphore.withPermit {
            val gate = awaitArchiveRequestTurn()
            if (gate != null) {
                emitWaybackTrace(onTrace, 4, WaybackTraceStatus.SKIPPED,
                    "Consulta no enviada: bloqueada por el control de ritmo.")
                emitWaybackTrace(onTrace, 5, WaybackTraceStatus.SKIPPED,
                    "No hubo respuesta HTTP porque la petición no salió.")
                CdxIndexOutcome(requestFailed = true, diagnosticCodes = setOf(gate))
            } else {
                emitWaybackTrace(onTrace, 4, WaybackTraceStatus.PASSED, "$cdxStage: petición CDX enviada.")
                emitWaybackTrace(onTrace, 5, WaybackTraceStatus.RUNNING, "$cdxStage: esperando respuesta.")
                val outcome = queryCdxIndexOnly(cdxUrl, cdxTimeoutSeconds, cdxStage)
                val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                emitWaybackTrace(
                    onTrace, 5,
                    if (outcome.requestFailed) WaybackTraceStatus.FAILED else WaybackTraceStatus.PASSED,
                    if (outcome.requestFailed) "$cdxStage: ${outcome.diagnosticCodes.joinToString(", ").ifBlank { "respuesta inválida" }}."
                    else "$cdxStage: respuesta válida; ${outcome.captures.size} captura(s) candidatas.",
                    elapsed
                )
                outcome
            }
        }
        if (cdxOutcome.requestFailed) {
            // The parser never ran: the upstream CDX request produced no usable response.
            // Keep the actual failure on step 6 and mark extraction as blocked, not as a second
            // independent code failure.
            emitWaybackTrace(
                onTrace, 7, WaybackTraceStatus.SKIPPED,
                "Extracción no ejecutada: CDX no entregó una respuesta utilizable. Causa del paso 6: ${cdxOutcome.diagnosticCodes.joinToString(", ")}.",
                diagnosticStep = 8
            )
            emitWaybackTrace(
                onTrace, 7, WaybackTraceStatus.SKIPPED,
                "Normalización omitida: no hubo filas CDX utilizables que normalizar.",
                diagnosticStep = 9
            )
            emitWaybackTrace(
                onTrace, 7, WaybackTraceStatus.SKIPPED,
                "Validación de fechas omitida: CDX no devolvió una lista válida de capturas.",
                diagnosticStep = 10
            )
            emitWaybackTrace(
                onTrace, 8, WaybackTraceStatus.SKIPPED,
                "No se pudo iniciar la descarga de la captura 1 porque el índice CDX no respondió correctamente.",
                diagnosticStep = 11
            )
            emitWaybackTrace(
                onTrace, 9, WaybackTraceStatus.SKIPPED,
                "No hay HTML de captura 1 que evaluar: CDX no proporcionó una captura utilizable.",
                diagnosticStep = 12
            )
            emitWaybackTrace(
                onTrace, 10, WaybackTraceStatus.SKIPPED,
                "No existe una segunda captura para descargar: CDX no devolvió una lista utilizable.",
                diagnosticStep = 13
            )
            emitWaybackTrace(
                onTrace, 11, WaybackTraceStatus.SKIPPED,
                "No hay una segunda captura que evaluar porque el índice CDX no respondió.",
                diagnosticStep = 14
            )
            emitWaybackTrace(
                onTrace, 12, WaybackTraceStatus.SKIPPED,
                "No se pudo comprobar la identidad: CDX no proporcionó ninguna captura para descargar.",
                diagnosticStep = 15
            )
            emitWaybackTrace(
                onTrace, 13, WaybackTraceStatus.SKIPPED,
                "Extracción de precio omitida: no se descargó HTML de una captura CDX.",
                diagnosticStep = 16
            )
            emitWaybackTrace(
                onTrace, 13, WaybackTraceStatus.SKIPPED,
                "Validación de importe omitida: no se obtuvo ningún precio de una captura CDX.",
                diagnosticStep = 17
            )
            emitWaybackTrace(
                onTrace, 14, WaybackTraceStatus.SKIPPED,
                "Consolidación omitida: no se pudo descargar ninguna captura de CDX.",
                diagnosticStep = 18
            )
            val rateLimited = cdxOutcome.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true, rateLimited = rateLimited)
            return WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = cdxOutcome.diagnosticCodes
            )
        }
        val indexCaptures = cdxOutcome.captures
        emitWaybackTrace(
            onTrace, 7,
            WaybackTraceStatus.PASSED,
            if (indexCaptures.isNotEmpty()) "CDX parseado correctamente: ${indexCaptures.size} fila(s) de captura válida(s) antes de aplicar límites del historial; máximo a revisar ${maxSnapshots}."
            else "CDX respondió y se parseó correctamente, pero devolvió 0 filas de captura; es ausencia de datos, no fallo del extractor.",
            diagnosticStep = 8
        )

        val distinctCandidateUrls = indexCaptures.map { it.originalUrl }.distinct()
        emitWaybackTrace(
            onTrace, 7,
            when {
                indexCaptures.isEmpty() -> WaybackTraceStatus.SKIPPED
                distinctCandidateUrls.isNotEmpty() -> WaybackTraceStatus.PASSED
                else -> WaybackTraceStatus.FAILED
            },
            "Candidatas normalizadas: ${distinctCandidateUrls.size} URL(s) únicas a partir de ${indexCaptures.size} fila(s); límite de descargas ${maxSnapshots}. No se registran las URL completas.",
            diagnosticStep = 9
        )

        val windowStart = historyWindowStartTimestamp(now, lookbackDays)
        val captures = indexCaptures.filter { it.timestamp in windowStart..now }
        val outsideWindowCount = indexCaptures.size - captures.size
        emitWaybackTrace(
            onTrace, 7,
            when {
                indexCaptures.isEmpty() -> WaybackTraceStatus.SKIPPED
                captures.isNotEmpty() -> WaybackTraceStatus.PASSED
                else -> WaybackTraceStatus.FAILED
            },
            when {
                indexCaptures.isEmpty() ->
                    "No hay fechas que validar porque el índice no devolvió filas de captura."
                captures.isNotEmpty() ->
                    "Fechas comprobadas: ${captures.size} captura(s) dentro de los últimos ${lookbackDays.coerceAtLeast(1)} días; ${outsideWindowCount} descartada(s) fuera del intervalo solicitado."
                else ->
                    "El índice devolvió ${indexCaptures.size} fila(s), pero ninguna fecha cae dentro del período solicitado de ${lookbackDays.coerceAtLeast(1)} días."
            },
            diagnosticStep = 10
        )

        val sampled = sampleCaptures(captures, maxSnapshots)
        val fetched = mutableListOf<SnapshotFetch>()
        for ((snapshotIndex, capture) in sampled.withIndex()) {
            val snapshot = fetchSnapshotOnce(
                capture = capture,
                expectedTitle = expectedTitle,
                snapshotTimeoutSeconds = snapshotTimeoutSeconds,
                snapshotOrdinal = snapshotIndex + 1,
                snapshotTotal = sampled.size,
                captureSourceStage = cdxStage,
                onTrace = onTrace,
                knownProductTitlesById = knownProductTitlesById,
                snapshotResultsCache = snapshotResultsCache,
                onVerifiedQuotes = onVerifiedQuotes
            )
            fetched += snapshot
            if (snapshot.diagnosticCode == "WAYBACK_RATE_LIMITED") break
        }
        when {
            sampled.isEmpty() -> {
                emitWaybackTrace(
                    onTrace, 8, WaybackTraceStatus.SKIPPED,
                    "No se descargó la captura 1: la respuesta CDX no contenía capturas válidas dentro del período.",
                    diagnosticStep = 11
                )
                emitWaybackTrace(
                    onTrace, 9, WaybackTraceStatus.SKIPPED,
                    "No hay HTML de captura 1 que evaluar porque no había capturas disponibles.",
                    diagnosticStep = 12
                )
                emitWaybackTrace(
                    onTrace, 10, WaybackTraceStatus.SKIPPED,
                    "No se encontró una segunda captura independiente para descargar (0 capturas disponibles).",
                    diagnosticStep = 13
                )
                emitWaybackTrace(
                    onTrace, 11, WaybackTraceStatus.SKIPPED,
                    "No hay una segunda captura que evaluar porque el índice no devolvió capturas.",
                    diagnosticStep = 14
                )
                emitWaybackTrace(
                    onTrace, 12, WaybackTraceStatus.SKIPPED,
                    "Verificación del producto omitida: CDX respondió, pero no devolvió ninguna captura del período.",
                    diagnosticStep = 15
                )
                emitWaybackTrace(
                    onTrace, 13, WaybackTraceStatus.SKIPPED,
                    "Extracción del precio omitida: no hay HTML de una captura para analizar.",
                    diagnosticStep = 16
                )
                emitWaybackTrace(
                    onTrace, 13, WaybackTraceStatus.SKIPPED,
                    "Validación del importe omitida: no se obtuvo un precio porque el índice estaba vacío.",
                    diagnosticStep = 17
                )
            }
            sampled.size == 1 -> {
                emitWaybackTrace(
                    onTrace, 10, WaybackTraceStatus.SKIPPED,
                    "No se encontró una segunda captura independiente en el índice; solo había 1 captura válida dentro del período.",
                    diagnosticStep = 13
                )
                emitWaybackTrace(
                    onTrace, 11, WaybackTraceStatus.SKIPPED,
                    "Evaluación de la captura 2 omitida porque no había una segunda captura disponible.",
                    diagnosticStep = 14
                )
            }
        }

        // Cache useful histories for 30 days, but allow an empty result to be retried after 6 hours.
        val verifiedQuotesBeforeDedup = fetched.mapNotNull { it.quote }
            .sortedBy { it.observedAt }
        val quotes = verifiedQuotesBeforeDedup
            // Multiple candidate URLs can point to the same archived price at the same timestamp.
            // Keep a single history point; otherwise one Availability snapshot returned for two
            // URL variants is incorrectly persisted twice.
            .distinctBy { listOf(it.targetProductId.orEmpty(), it.productTitle, it.observedAt.toString(), it.price.toString(), it.currency.uppercase(Locale.ROOT)) }
        val duplicatesRemoved = verifiedQuotesBeforeDedup.size - quotes.size
        emitWaybackTrace(
            onTrace, 14,
            when {
                quotes.isNotEmpty() -> WaybackTraceStatus.PASSED
                captures.isEmpty() -> WaybackTraceStatus.SKIPPED
                else -> WaybackTraceStatus.FAILED
            },
            "Consolidación histórica: ${fetched.count { it.fetched && !it.reused }} HTML descargado(s), ${verifiedQuotesBeforeDedup.size} precio(s) verificado(s), ${duplicatesRemoved} duplicado(s) eliminado(s).",
            diagnosticStep = 18
        )
        val anySnapshotFailed = captures.isNotEmpty() && fetched.any { !it.fetched }
        val allSnapshotsFailed = captures.isNotEmpty() && fetched.isNotEmpty() && fetched.none { it.fetched }
        val diagnosticCodes = linkedSetOf<String>().apply {
            fetched.mapNotNullTo(this) { it.diagnosticCode }
            if (captures.isEmpty()) add("CDX_NO_CAPTURES")
            else if (quotes.isEmpty() && fetched.any { it.fetched }) add("NO_VERIFIED_EUR_PRICE")
        }
        rememberAttempt(
            cacheKey,
            now,
            hasQuotes = quotes.isNotEmpty(),
            failed = quotes.isEmpty() && anySnapshotFailed,
            rateLimited = diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
        )
        if (allSnapshotsFailed) diagnosticCodes.add("ALL_SNAPSHOTS_FAILED")
        return WaybackBackfillResult(
            quotes = quotes,
            capturesFound = captures.size,
            capturesChecked = fetched.count { it.fetched && !it.reused },
            available = true,
            requestFailed = anySnapshotFailed,
            diagnosticCodes = diagnosticCodes
        )
    }

    /**
     * Wayback's public CDX endpoint intermittently returns 5xx responses for otherwise valid
     * queries. Retry only transient server failures once; never retry 4xx, timeouts, or arbitrary
     * exceptions, so the free-history action remains bounded and responsive.
     */
    private suspend fun executeCdxWithTransientRetry(
        client: OkHttpClient,
        request: Request,
        maxRetries: Int = MAX_CDX_HTTP_RETRIES
    ): okhttp3.Response {
        repeat(maxRetries + 1) { attempt ->
            val response = client.newCall(request).execute()
            val shouldRetry = response.code in RETRYABLE_CDX_HTTP_CODES &&
                attempt < maxRetries
            if (!shouldRetry) return response
            response.close()

            // The CDX index is shared public infrastructure. A short exponential pause plus
            // jitter avoids synchronized repeat traffic across clients while retaining a strict
            // single-retry ceiling for UI responsiveness.
            val jitterMs = (System.nanoTime().ushr(1) % (CDX_RETRY_JITTER_MS + 1))
            delay(CDX_RETRY_DELAY_MS + (attempt * CDX_RETRY_DELAY_MS) + jitterMs)
        }
        error("CDX request loop exited unexpectedly")
    }

    /** Resolve recognisable merchant labels; never guess a site from generic labels. */
    internal fun resolveMerchantDomain(
        storeName: String,
        sourceUrl: String = "",
        knownStoreDomain: String = ""
    ): String? {
        val explicitHost = Regex(
            """(?i)(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:es|com|net|org|eu|de|fr|it|nl|co\.uk))(?:[:/]|$)"""
        ).find(storeName.trim())?.groupValues?.getOrNull(1)
        if (explicitHost != null) return normalizeMerchantDomain(explicitHost)
        normalizeMerchantDomain(knownStoreDomain)?.let { return it }
        Store.findKnownByName(storeName, sourceUrl)?.let {
            normalizeMerchantDomain(it.domain)?.let { domain -> return domain }
        }
        if (sourceUrl.isNotBlank()) {
            decomposeProductUrl(sourceUrl)?.merchantDomain?.let { return it }
            runCatching { URI(sourceUrl).host }.getOrNull()
                ?.let(::normalizeMerchantDomain)?.let { return it }
        }
        // Empty seller labels are common for Google Shopping intermediary rows. A valid saved
        // domain or real source URL above is still sufficient; only stop before alias matching.
        if (storeName.isBlank()) return null
        val normalized = normalizeTitle(storeName)
        val aliases = listOf(
            "pccomponentes" to "pccomponentes.com",
            "phone house" to "phonehouse.es",
            "phonehouse" to "phonehouse.es",
            "la manzanita" to "lamanzanita.es",
            "nike" to "nike.com",
            "adidas" to "adidas.com",
            "puma" to "eu.puma.com",
            "asics" to "asics.com",
            "new balance" to "newbalance.es",
            "reebok" to "reebok.eu",
            "vans" to "vans.com",
            "converse" to "converse.com",
            "jdsports" to "jdsports.es",
            "foot locker" to "footlocker.es",
            "zalando" to "zalando.es",
            "mediamarkt" to "mediamarkt.es",
            "media markt" to "mediamarkt.es",
            "el corte ingles" to "elcorteingles.es",
            "carrefour" to "carrefour.es",
            "fnac" to "fnac.es",
            "amazon" to "amazon.es",
            "notino" to "notino.es",
            "douglas" to "douglas.es",
            "alcampo" to "alcampo.es",
            "leroy merlin" to "leroymerlin.es",
            "decathlon" to "decathlon.es",
            "worten" to "worten.es",
            "miravia" to "miravia.es",
            "conforama" to "conforama.es",
            "bauhaus" to "bauhaus.es",
            "ikea" to "ikea.com",
            "lidl" to "lidl.es",
            "aliexpress" to "aliexpress.com",
            "sprinter" to "sprinter.es",
            "powerplanet" to "powerplanetonline.com",
            "tradeinn" to "tradeinn.com",
            "backmarket" to "backmarket.es",
            "neobyte" to "neobyte.es",
            "coolmod" to "coolmod.com",
            "alternate" to "alternate.es",
            "ldlc" to "ldlc.com",
            "apple" to "apple.com",
            "samsung" to "samsung.com",
            "xiaomi" to "mi.com",
            "lenovo" to "lenovo.com",
            "dell" to "dell.com",
            "hp" to "hp.com"
        )
        val match = aliases.firstOrNull { (label, _) ->
            normalized == label || normalized.contains(label)
        } ?: return null
        return normalizeMerchantDomain(match.second)
    }
    /**
     * If a Shopping provider hides the merchant URL/name, fall back only to a recognized
     * product brand's own store domain. This is a fallback source, not an assumption that the
     * cheapest third-party seller is the brand itself; archived metadata must still verify title
     * and EUR price before any point is saved.
     */
    internal fun resolveBrandMerchant(productTitle: String): Pair<String, String>? {
        val normalized = normalizeTitle(productTitle)
        val brands = listOf(
            "new balance" to ("New Balance" to "newbalance.es"),
            "apple" to ("Apple Store" to "apple.com"),
            "iphone" to ("Apple Store" to "apple.com"),
            "samsung" to ("Samsung" to "samsung.com"),
            "galaxy" to ("Samsung" to "samsung.com"),
            "nike" to ("Nike" to "nike.com"),
            "adidas" to ("Adidas" to "adidas.com"),
            "puma" to ("Puma" to "eu.puma.com"),
            "asics" to ("ASICS" to "asics.com"),
            "reebok" to ("Reebok" to "reebok.eu"),
            "converse" to ("Converse" to "converse.com"),
            "vans" to ("Vans" to "vans.com"),
            "anker" to ("Anker" to "anker.com"),
            "ugreen" to ("UGREEN" to "ugreen.com"),
            "baseus" to ("Baseus" to "baseus.com"),
            "sony" to ("Sony" to "sony.es"),
            "xiaomi" to ("Xiaomi" to "mi.com"),
            "redmi" to ("Xiaomi" to "mi.com"),
            "jbl" to ("JBL" to "jbl.com"),
            "logitech" to ("Logitech" to "logitech.com"),
            "dyson" to ("Dyson" to "dyson.es"),
            "lenovo" to ("Lenovo" to "lenovo.com"),
            "dell" to ("Dell" to "dell.com"),
            "hp" to ("HP" to "hp.com")
        )
        val match = brands.firstOrNull { (brand, _) ->
            Regex("""\b${Regex.escape(brand)}\b""").containsMatchIn(normalized)
        } ?: return null
        val domain = normalizeMerchantDomain(match.second.second) ?: return null
        return match.second.first to domain
    }

    private fun normalizeMerchantDomain(value: String): String? {
        val raw = value.trim().trimEnd('.', ',', ';')
        if (raw.isBlank()) return null
        // URI schemes are case-insensitive. Treat full URLs and bare host/path values uniformly;
        // parsing the host also safely removes ports, paths, query strings and fragments.
        val absolute = if (Regex("""(?i)^https?://""").containsMatchIn(raw)) raw else "https://$raw"
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.").orEmpty()
        if (!host.matches(Regex("""(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}"""))) {
            return null
        }
        if (host == "google.com" || host.endsWith(".google.com") || host == "google.es" ||
            host.endsWith(".google.es") || host == "bing.com" || host.endsWith(".bing.com") ||
            host == "web.archive.org" || host == "archive.org"
        ) return null
        return host
    }

    internal fun buildCdxUrl(
        originalUrl: String,
        now: Long,
        lookbackDays: Int,
        matchType: String = "prefix"
    ): HttpUrl? {
        val start = historyWindowStartTimestamp(now, lookbackDays)
        val from = formatUtc(start, "yyyyMMdd")
        val to = formatUtc(now, "yyyyMMdd")
        return HttpUrl.Builder()
            .scheme("https")
            .host("web.archive.org")
            .addPathSegments("cdx/search/cdx")
            .addQueryParameter("url", originalUrl)
            // Prefix matching handles archived canonical variants such as a trailing slash,
            // locale folder or child variant URL. The HTML product-title + EUR-price verifier is
            // still mandatory, so a neighbouring product cannot be accepted by URL similarity.
            .addQueryParameter("matchType", matchType)
            .addQueryParameter("output", "json")
            .addQueryParameter("fl", "timestamp,original,statuscode,mimetype")
            .addQueryParameter("filter", "statuscode:200")
            .addQueryParameter("filter", "mimetype:text/html")
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            .addQueryParameter("collapse", "timestamp:6")
            // Ask CDX for the newest bounded set, not the first (oldest) rows. Negative limit
            // returns the last N matches; 120 monthly rows cover the configured multi-year window
            // without letting early captures crowd newer dates out of the quarterly sampler.
            .addQueryParameter("limit", "-120")
            .build()
    }

    /**
     * Build a product-filtered CDX query for a single host. Discovery uses host scope and
     * avoids urlkey collapsing; dated captures are fetched later through exact-URL queries.
     */
    internal fun buildDomainCdxUrl(
        domain: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): HttpUrl? {
        val normalizedDomain = normalizeMerchantDomain(domain) ?: return null
        val tokens = historicalUrlSearchTokens(expectedTitle)
        if (tokens.isEmpty()) return null
        val modelTokens = tokens.filter { token ->
            token.any(Char::isDigit) && token.any(Char::isLetter) &&
                !Regex("""^\d+(?:[.,]\d+)?(?:gb|tb|mb|mah|wh|hz|w|mm|cm|inch|inches|pulgadas?)$""")
                    .containsMatchIn(token)
        }.toMutableList()
        // Samsung TV URLs may expose a family code such as S95H instead of the complete
        // regional SKU (TQ48S95HAEXXC / QE48S95HAEXXC). Search that fallback too.
        Regex("""(?i)\b(?:tq|qe)\d{2}(s\d{2}[a-z])""")
            .findAll(normalizeTitle(expectedTitle))
            .map { it.groupValues[1].lowercase(Locale.ROOT) }
            .forEach { coreModel -> if (coreModel !in modelTokens) modelTokens.add(0, coreModel) }
        val hostBrandTerms = when (normalizedDomain) {
            "apple.com" -> setOf("apple")
            "samsung.com" -> setOf("samsung", "galaxy")
            "nike.com" -> setOf("nike")
            "adidas.com" -> setOf("adidas")
            "eu.puma.com" -> setOf("puma")
            "newbalance.es" -> setOf("new", "balance", "newbalance")
            "anker.com" -> setOf("anker")
            "ugreen.com" -> setOf("ugreen")
            "baseus.com" -> setOf("baseus")
            "mi.com" -> setOf("xiaomi", "redmi", "mi")
            "sony.es" -> setOf("sony")
            "jbl.com" -> setOf("jbl")
            "logitech.com" -> setOf("logitech")
            "dyson.es" -> setOf("dyson")
            "lenovo.com" -> setOf("lenovo")
            "dell.com" -> setOf("dell")
            "hp.com" -> setOf("hp")
            else -> setOf(normalizedDomain.substringBefore('.'))
        }
        val textTokens = tokens.filter { token ->
            token.any(Char::isLetter) && !token.any(Char::isDigit) && token !in hostBrandTerms
        }
        // Galaxy Edge pages may use URLs like galaxy-s26-edge, without a SKU.
        // Match title variants with either Galaxy 26 Edge or Galaxy S26 Edge.
        val normalizedTitle = normalizeTitle(expectedTitle)
        val modelFamilyAlternatives = if (
            normalizedDomain == "samsung.com" &&
            Regex("""\bgalaxy\s+s?\d{2}\s+edge\b""").containsMatchIn(normalizedTitle)
        ) {
            val modelNumber = Regex("""\bgalaxy\s+s?(\d{2})\s+edge\b""")
                .find(normalizedTitle)?.groupValues?.getOrNull(1)
            if (modelNumber != null) {
                listOf("galaxy.*(?:s)?$modelNumber.*edge|galaxy.*edge.*(?:s)?$modelNumber")
            } else emptyList()
        } else emptyList()
        // CDX's original-field filter is a regular expression, but look-ahead expressions
        // are not handled consistently by every archive index deployment. Use portable,
        // bounded alternatives instead. Model/SKU tokens get priority; when URLs omit them,
        // accept either ordering of two distinctive title words. Archived-page validation
        // below remains the final authority, so a loose URL match can never become a price.
        val modelAlternatives = modelTokens.take(3).map { urlTokenPattern(it) }
        // A single ordinary word is too broad for a domain-wide CDX scan. Require either
        // a model/SKU token, a known model-family expression, or two useful product-name terms.
        // The final page validator remains mandatory even when a URL passes this filter.
        val textAlternatives = if (textTokens.size >= 2) {
            val first = urlTokenPattern(textTokens[0])
            val second = urlTokenPattern(textTokens[1])
            listOf("$first.*$second", "$second.*$first")
        } else emptyList()
        val urlAlternatives = (modelAlternatives + modelFamilyAlternatives + textAlternatives).distinct()
        if (urlAlternatives.isEmpty()) return null
        val start = historyWindowStartTimestamp(now, lookbackDays)
        val from = formatUtc(start, "yyyyMMdd")
        val to = formatUtc(now, "yyyyMMdd")
        val urlPattern = "(?i).*(?:" + urlAlternatives.joinToString("|") + ").*"
        // Keep the CDX response narrow. Broad multi-year domain scans often time out on
        // large retailers; direct URL lookups still use the full configured lookback.
        return HttpUrl.Builder()
            .scheme("https")
            .host("web.archive.org")
            .addPathSegments("cdx/search/cdx")
            // Host scope narrows the CDX key range to one retailer host. A companion
            // query may target the apex host when www has no captures.
            // Product-specific filtering and final archived-page validation remain mandatory.
            .addQueryParameter("url", normalizedDomain)
            .addQueryParameter("matchType", "host")
            .addQueryParameter("output", "json")
            .addQueryParameter("fl", "timestamp,original,statuscode,mimetype")
            .addQueryParameter("filter", "statuscode:200")
            .addQueryParameter("filter", "mimetype:text/html")
            .addQueryParameter("filter", "original:$urlPattern")
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            // A small result window can be exhausted by unrelated captures before a
            // matching product URL appears. The portable token filter stays bounded; return
            // a few more index rows while keeping snapshot downloads capped separately.
            .addQueryParameter("limit", MAX_DOMAIN_INDEX_ROWS.toString())
            .build()
    }

    /**
     * A timeout on www does not prove that the apex host has no archived pages. Permit exactly
     * one next-host probe when it is a distinct URL; never retry the same host or continue after
     * HTTP 429. The caller caps the host list to two candidates.
     */
    internal fun shouldTryNextDomainHostAfterTimeout(
        currentIndex: Int,
        hostUrls: List<HttpUrl>,
        diagnosticCodes: Set<String>
    ): Boolean {
        if (diagnosticCodes.contains("WAYBACK_RATE_LIMITED")) return false
        if ("CDX_DOMAIN_TIMEOUT" !in diagnosticCodes) return false
        val current = hostUrls.getOrNull(currentIndex) ?: return false
        val next = hostUrls.getOrNull(currentIndex + 1) ?: return false
        return next.queryParameter("url") != current.queryParameter("url")
    }

    /** Build www-host and apex-host requests for a registrable retailer root. */
    internal fun buildDomainCdxHostUrls(
        domain: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): List<HttpUrl>? {
        val normalizedDomain = normalizeMerchantDomain(domain) ?: return null
        val base = buildDomainCdxUrl(normalizedDomain, expectedTitle, now, lookbackDays) ?: return null
        val hosts = domainDiscoveryHosts(normalizedDomain)
        return hosts.map { host ->
            base.newBuilder()
                .setQueryParameter("url", host)
                .setQueryParameter("matchType", "host")
                .removeAllQueryParameters("collapse")
                .build()
        }
    }

    internal fun domainDiscoveryHosts(domain: String): List<String> {
        val normalized = normalizeMerchantDomain(domain) ?: return emptyList()
        val labels = normalized.split('.')
        val suffix = labels.takeLast(2).joinToString(".")
        val rootLabelCount = if (suffix in MULTI_LABEL_PUBLIC_SUFFIXES) 3 else 2
        return if (labels.size <= rootLabelCount) {
            listOf("www.$normalized", normalized).distinct()
        } else {
            // Domains already hosted on a regional/subdomain host (e.g. eu.ugreen.com)
            // must not be rewritten to www.<subdomain>, which commonly does not exist.
            listOf(normalized)
        }
    }

    /** Match both compact model codes (9n3) and split retailer slugs (iphone-16). */
    private fun urlTokenPattern(token: String): String {
        val pieces = token.split(Regex("""(?<=[a-z])(?=\d)|(?<=\d)(?=[a-z])"""))
        val compact = Regex.escape(token)
        val split = pieces.joinToString("[^a-z0-9]*") { Regex.escape(it) }
        return "(?:$compact|$split)"
    }

    private fun historicalUrlSearchTokens(expectedTitle: String): List<String> {
        val ignored = setOf(
            "the", "and", "for", "with", "from", "used", "de", "del", "la", "las", "el",
            "los", "un", "una", "para", "con", "en", "of", "by", "sale", "buy", "official",
            "original", "product", "producto", "generic", "generico", "muestra", "demo",
            "sample", "test", "item", "articulo", "article", "unknown", "desconocido",
            "neu", "zapatilla", "zapatillas", "calzado", "zapato", "zapatos",
            "sneaker", "sneakers", "shoe", "shoes", "calendario",
            "calendarios", "calendar", "calendars", "advent", "adviento", "adventskalender",
            "beauty", "women", "woman", "hombre", "hombres", "mujer", "mujeres", "men",
            "damen", "frauen", "gb", "tb", "mb"
        )
        val rawTokens = normalizeTitle(expectedTitle).split(' ')
            .filter { token ->
                token.isNotBlank() && token !in ignored &&
                    !Regex("""^\d+(?:[.,]\d+)?(?:gb|tb|mb|mah|wh|hz|w|mm|cm|inch|inches|pulgadas?)$""")
                        .containsMatchIn(token)
            }
            .distinct()
        // Join split model families like "iPhone 16" so a URL containing iphone-16 can be
        // found, while excluding capacity tokens such as 256GB from path matching.
        val modelFamilies = setOf("iphone", "redmi", "poco", "pixel", "galaxy", "wh", "dyson", "roborock")
        val joinedModels = rawTokens.indices.mapNotNull { index ->
            val family = rawTokens[index]
            val next = rawTokens.getOrNull(index + 1) ?: return@mapNotNull null
            if (family in modelFamilies && Regex("""^\d+[a-z0-9]*$""").matches(next)) {
                family + next
            } else null
        }
        val modelTokens = (joinedModels + rawTokens.filter { token ->
            token.length >= 2 && token.any(Char::isDigit) && token.any(Char::isLetter)
        }).distinct()
        // Preserve short path terms in product names (e.g. Air Max Plus). The parser later
        // verifies the archived page title and price, so multiple path tokens are just discovery.
        val textTokens = rawTokens.filter { token ->
            token.any(Char::isLetter) && !token.any(Char::isDigit) && token.length >= 3
        }
        val yearTokens = rawTokens.filter {
            Regex("""(?:19|20)\d{2}""").matches(it)
        }
        return (modelTokens + textTokens + yearTokens)
            .distinct()
            .take(6)
    }

    private fun buildSnapshotUrl(capture: WaybackCapture): String? {
        if (capture.timestamp <= 0L || !isEligibleDirectUrl(capture.originalUrl)) return null
        return "https://web.archive.org/web/" + capture.rawTimestamp + "id_/" + capture.originalUrl
    }

    internal fun isValidCaptureIndexResponse(body: String): Boolean {
        val root = try { JSONArray(body) } catch (_: JSONException) { return false }
        if (root.length() == 0) return true
        val header = root.optJSONArray(0) ?: return false
        val fields = (0 until header.length()).map { header.optString(it) }.toSet()
        return "timestamp" in fields && "original" in fields
    }

    internal fun parseCaptureIndex(body: String): List<WaybackCapture> {
        val root = try { JSONArray(body) } catch (_: JSONException) { return emptyList() }
        if (root.length() < 2) return emptyList()
        val header = root.optJSONArray(0) ?: return emptyList()
        fun column(name: String): Int {
            for (i in 0 until header.length()) if (header.optString(i) == name) return i
            return -1
        }
        val tsCol = column("timestamp")
        val urlCol = column("original")
        val statusCol = column("statuscode")
        val mimeCol = column("mimetype")
        if (tsCol < 0 || urlCol < 0) return emptyList()
        val out = mutableListOf<WaybackCapture>()
        for (i in 1 until root.length()) {
            val row = root.optJSONArray(i) ?: continue
            val rawTs = row.optString(tsCol)
            if (!rawTs.matches(Regex("""\d{14}"""))) continue
            if (statusCol >= 0 && row.optString(statusCol) != "200") continue
            if (mimeCol >= 0 && !row.optString(mimeCol).contains("text/html", ignoreCase = true)) continue
            val original = row.optString(urlCol).takeIf(::isEligibleDirectUrl) ?: continue
            val timestamp = runCatching {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(rawTs)?.time
            }.getOrNull() ?: continue
            out += WaybackCapture(rawTs, timestamp, original)
        }
        return out.distinctBy { it.rawTimestamp to it.originalUrl }.sortedBy { it.timestamp }
    }

    internal fun parseArchivedPrice(
        html: String,
        expectedTitle: String,
        originalUrl: String,
        snapshotUrl: String,
        observedAt: Long
    ): ArchivedPriceQuote? {
        if (html.isBlank()) return null
        return parseArchivedPrice(
            document = Jsoup.parse(html),
            expectedTitle = expectedTitle,
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            observedAt = observedAt
        )
    }

    internal fun parseArchivedPrice(
        document: Document,
        expectedTitle: String,
        originalUrl: String,
        snapshotUrl: String,
        observedAt: Long,
        acceptedCurrencies: Set<String> = setOf("EUR")
    ): ArchivedPriceQuote? {
        // The product price/title may exist only in <head> metadata or JSON-LD, so an empty
        // rendered body is not enough to reject an archived document.
        if (expectedTitle.isBlank() || observedAt <= 0L) return null
        val jsonLdCandidates = extractJsonLdProductOffers(document)
        val pageTitles = extractArchivedPageTitles(document)

        val metaPrice = listOf(
            "product:price:amount", "og:price:amount", "price", "product:price"
        ).firstNotNullOfOrNull { key ->
            (readMeta(document, setOf(key)) ?: readItemPropValue(document, key))?.let(::parsePositivePrice)
        }
        val metaCurrency = listOf(
            "product:price:currency", "og:price:currency", "pricecurrency"
        ).firstNotNullOfOrNull { key ->
            readMeta(document, setOf(key)) ?: readItemPropValue(document, key)
        }?.uppercase(Locale.ROOT)

        val allowedCurrencies = acceptedCurrencies.map { it.uppercase(Locale.ROOT) }.toSet()
        fun matchesArchivedTitle(title: String): Boolean =
            isMatchingTitleForArchivedUrl(expectedTitle, title, originalUrl)

        fun isValidCandidate(candidate: Triple<String, Double, String>): Boolean =
            candidate.third.uppercase(Locale.ROOT) in allowedCurrencies &&
                candidate.second in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE &&
                matchesArchivedTitle(candidate.first)

        val matchingIdentityJsonLd = jsonLdCandidates.filter { matchesArchivedTitle(it.first) }
        val matchingJsonLd = matchingIdentityJsonLd.filter(::isValidCandidate)
        val structuredVariantConflict = jsonLdCandidates.firstOrNull {
            hasExplicitProductVariantConflict(expectedTitle, it.first) &&
                !matchesArchivedTitle(it.first)
        }

        // Do not borrow a generic page/meta title if its structured offer identifies another SKU,
        // or if the matching structured offer carries a non-EUR/out-of-range price.
        if (matchingIdentityJsonLd.isNotEmpty() && matchingJsonLd.isEmpty()) return null
        if (structuredVariantConflict != null && matchingJsonLd.isEmpty()) return null

        // Keep the established retailer behavior where explicit EUR price metadata takes priority
        // over another offer price for the same verified product. Disable this fallback when a
        // conflicting structured product variant was detected.
        val explicitMeta = if (structuredVariantConflict == null &&
            metaPrice != null && !metaCurrency.isNullOrBlank()
        ) {
            pageTitles.asSequence()
                .filter(::matchesArchivedTitle)
                .map { title -> Triple(title, metaPrice, metaCurrency) }
                .firstOrNull(::isValidCandidate)
        } else null

        val eurStructured = matchingJsonLd.filter { it.third.equals("EUR", ignoreCase = true) }
        val matching = when {
            explicitMeta?.third.equals("EUR", ignoreCase = true) -> explicitMeta
            eurStructured.isNotEmpty() -> eurStructured.minWithOrNull(
                compareBy<Triple<String, Double, String>> { it.second }
                    .thenBy { normalizeTitle(it.first) }
            )
            else -> explicitMeta ?: matchingJsonLd.minWithOrNull(
                compareBy<Triple<String, Double, String>> { it.second }
                    .thenBy { normalizeTitle(it.first) }
            )
        }
        if (matching != null) {
            return ArchivedPriceQuote(
                observedAt = observedAt,
                price = matching.second,
                currency = matching.third.uppercase(Locale.ROOT),
                productTitle = decodeHtml(matching.first).trim(),
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl,
                sourceCurrency = matching.third.uppercase(Locale.ROOT),
                sourcePrice = matching.second,
                variantIdentityConfirmedByUrl = matchesVariantDetailsConfirmedByUrl(
                    expectedTitle, matching.first, originalUrl
                )
            )
        }

        // Use rendered price only from a price-specific element and only after product identity matches.
        val matchingPageTitle = pageTitles.firstOrNull(::matchesArchivedTitle)
            ?: return null
        val visibleCandidate = extractVisibleProductPrice(document, metaCurrency, allowedCurrencies) ?: return null
        val visiblePrice = visibleCandidate.first
        val visibleCurrency = visibleCandidate.second
        return ArchivedPriceQuote(
            observedAt = observedAt,
            price = visiblePrice,
            currency = visibleCurrency,
            productTitle = decodeHtml(matchingPageTitle).trim(),
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            sourceCurrency = visibleCurrency,
            sourcePrice = visiblePrice,
            variantIdentityConfirmedByUrl = matchesVariantDetailsConfirmedByUrl(
                expectedTitle, matchingPageTitle, originalUrl
            )
        )
    }

    /**
     * Extract additional offers from structured Product/Offer records, and only when a record
     * matches exactly one different product already saved in Room. Page-level metadata is
     * intentionally excluded because it can describe only the primary SKU.
     */
    /**
     * Returns product/offer URLs attached to a matching structured JSON-LD offer. Family pages
     * may contain several variants; the page URL alone cannot verify a secondary offer's storage.
     * The returned URLs are still checked against the requested SKU before they can validate it.
     */
    internal fun jsonLdProductUrlsForOffer(
        document: Document,
        productTitle: String,
        expectedPrice: Double,
        expectedCurrency: String,
        pageUrl: String
    ): List<String> {
        if (productTitle.isBlank() || expectedPrice <= 0.0) return emptyList()
        val roots = document.select("script").asSequence()
            .filter { script ->
                script.attr("type").substringBefore(';').trim()
                    .equals("application/ld+json", ignoreCase = true)
            }
            .mapNotNull { script ->
                val payload = script.data().ifBlank { script.html() }
                    .trim()
                    .removePrefix("<!--").removeSuffix("-->").trim()
                try { JSONTokener(payload).nextValue() } catch (_: Exception) { null }
            }
            .toList()
        val wantedName = normalizeTitle(productTitle)
        val wantedCurrency = expectedCurrency.uppercase(Locale.ROOT)
        val urls = linkedSetOf<String>()

        fun resolveCandidate(raw: String?) {
            if (raw.isNullOrBlank() || raw == "null") return
            val resolved = runCatching { URI(pageUrl).resolve(raw).toString() }.getOrNull() ?: return
            val uri = runCatching { URI(resolved) }.getOrNull() ?: return
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.host.equals("web.archive.org", ignoreCase = true) ||
                uri.host.equals("archive.org", ignoreCase = true) ||
                uri.host.equals("arquivo.pt", ignoreCase = true) ||
                !isEligibleDirectUrl(resolved)
            ) return
            urls += resolved
        }

        fun matchingOffer(offers: Any?): Boolean {
            var matched = false
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val priceValue = listOf(
                            value.opt("price"),
                            value.optJSONObject("priceSpecification")?.opt("price")
                        ).firstNotNullOfOrNull { raw ->
                            when (raw) {
                                is Number -> raw.toDouble().takeIf { it.isFinite() && it > 0.0 }
                                else -> raw?.toString()?.let(::parsePositivePrice)
                            }
                        }
                        val currency = listOf(
                            value.optString("priceCurrency"),
                            value.optJSONObject("priceSpecification")?.optString("priceCurrency").orEmpty()
                        ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
                            .uppercase(Locale.ROOT)
                        if (priceValue != null &&
                            kotlin.math.abs(priceValue - expectedPrice) < 0.011 &&
                            currency == wantedCurrency
                        ) {
                            matched = true
                            resolveCandidate(value.optString("url"))
                            value.optJSONObject("itemOffered")?.optString("url")?.let(::resolveCandidate)
                        }
                        val keys = value.keys()
                        while (keys.hasNext()) visit(value.opt(keys.next()))
                    }
                    is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                }
            }
            visit(offers)
            return matched
        }

        fun visitProducts(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val productTypes = setOf("product", "productgroup", "individualproduct", "productmodel")
                    val name = value.optString("name").takeIf { it.isNotBlank() && it != "null" }
                    if (name != null && normalizeTitle(name) == wantedName &&
                        jsonLdTypeNames(value).any { it in productTypes } &&
                        matchingOffer(value.opt("offers"))
                    ) {
                        resolveCandidate(value.optString("url"))
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) visitProducts(value.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until value.length()) visitProducts(value.opt(index))
            }
        }
        roots.forEach(::visitProducts)
        return urls.toList()
    }

    internal fun parseArchivedPricesForKnownProducts(
        document: Document,
        primaryExpectedTitle: String,
        knownProductTitlesById: Map<String, String>,
        originalUrl: String,
        snapshotUrl: String,
        observedAt: Long,
        acceptedCurrencies: Set<String> = setOf("EUR", "USD")
    ): List<ArchivedPriceQuote> {
        if (primaryExpectedTitle.isBlank() || observedAt <= 0L || knownProductTitlesById.isEmpty()) return emptyList()
        val allowedCurrencies = acceptedCurrencies.map { it.uppercase(Locale.ROOT) }.toSet()
        val output = mutableListOf<ArchivedPriceQuote>()
        for ((archivedTitle, price, rawCurrency) in extractJsonLdProductOffers(document)) {
            val currency = rawCurrency.uppercase(Locale.ROOT)
            if (currency !in allowedCurrencies || price !in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE) continue
            val matches = knownProductTitlesById.entries.mapNotNull { (productId, savedTitle) ->
                if (productId.isBlank() || savedTitle.isBlank()) return@mapNotNull null
                // Never stream the selected product back as its own secondary variant. Use the
                // strict SKU comparison first, with the exact URL only to resolve a title that
                // omits variant details. A sibling with a conflicting capacity/colour is not
                // classified as the primary product simply because it appears on the same page.
                val primaryNormalizedTitle = normalizeTitle(primaryExpectedTitle)
                val savedNormalizedTitle = normalizeTitle(savedTitle)
                val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
                val primaryStorage = storagePattern.findAll(primaryNormalizedTitle)
                    .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
                val savedStorage = storagePattern.findAll(savedNormalizedTitle)
                    .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
                val explicitStorageConflict = primaryStorage.isNotEmpty() &&
                    savedStorage.isNotEmpty() && primaryStorage != savedStorage
                val primaryColour = productColourFamily(primaryNormalizedTitle)
                val savedColour = productColourFamily(savedNormalizedTitle)
                val explicitColourConflict = primaryColour != null && savedColour != null &&
                    primaryColour != savedColour
                val explicitModelVariantConflict =
                    productModelVariantTokens(primaryNormalizedTitle) !=
                        productModelVariantTokens(savedNormalizedTitle)

                // A saved row with a different explicit capacity, colour or edition is a sibling,
                // even if a broad archive-page matcher could otherwise treat the family URL as a
                // match. Never drop it from the sibling-offer pipeline as if it were the primary.
                val isPrimarySavedProduct = !explicitStorageConflict &&
                    !explicitColourConflict && !explicitModelVariantConflict && (
                        isMatchingProductTitle(primaryExpectedTitle, savedTitle) ||
                            (isMatchingTitleForArchivedUrl(primaryExpectedTitle, savedTitle, originalUrl) &&
                                !hasExplicitVariantConflictInProductUrl(primaryExpectedTitle, originalUrl))
                    )
                if (isPrimarySavedProduct) return@mapNotNull null

                val offerUrls = jsonLdProductUrlsForOffer(
                    document = document,
                    productTitle = archivedTitle,
                    expectedPrice = price,
                    expectedCurrency = currency,
                    pageUrl = originalUrl
                )
                // A structured product URL is stronger evidence than a family-page URL. If it
                // explicitly names storage or colour that the saved row does not identify, do
                // not attach the price to that broader/ambiguous row.
                val urlExplicitlyContradictsSavedSku = offerUrls.any { offerUrl ->
                    val uri = runCatching { URI(offerUrl) }.getOrNull()
                    val urlIdentityText = normalizeTitle(
                        decodePathUrlComponent(uri?.rawPath.orEmpty()) + " " +
                            uri?.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
                                val key = decodeQueryUrlComponent(parameter.substringBefore('='))
                                    .lowercase(Locale.ROOT)
                                val value = decodeQueryUrlComponent(parameter.substringAfter('=', ""))
                                if (key in setOf(
                                        "sku", "skuid", "variant", "variantid", "variant_id",
                                        "capacity", "storage", "color", "colour", "model", "gtin", "ean"
                                    )
                                ) value else null
                            }.joinToString(" ")
                    )
                    val expectedStorage = Regex("""\b\d+\s*(?:gb|tb)\b""")
                        .findAll(normalizeTitle(savedTitle))
                        .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }
                        .toSet()
                    val urlStorage = Regex("""\b\d+\s*(?:gb|tb)\b""")
                        .findAll(urlIdentityText)
                        .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }
                        .toSet()
                    val expectedColour = productColourFamily(savedTitle)
                    val urlColour = productColourFamily(urlIdentityText)
                    hasExplicitVariantConflictInProductUrl(savedTitle, offerUrl) ||
                        (urlStorage.isNotEmpty() &&
                            (expectedStorage.isEmpty() || urlStorage.any { it !in expectedStorage })) ||
                        (urlColour != null &&
                            (expectedColour == null || expectedColour != urlColour))
                }
                if (urlExplicitlyContradictsSavedSku) return@mapNotNull null

                // Do not let a capture URL for the selected SKU independently confirm a
                // conflicting sibling row. The sibling must match the structured offer and, when
                // the capture URL conflicts with that row, its own Product/Offer URL must confirm it.
                val pageConfirmsVariant =
                    !hasExplicitVariantConflictInProductUrl(savedTitle, originalUrl) &&
                        isMatchingTitleForArchivedUrl(savedTitle, archivedTitle, originalUrl)
                val offerUrlConfirmsVariant = offerUrls.any { offerUrl ->
                    !hasExplicitVariantConflictInProductUrl(savedTitle, offerUrl) &&
                        matchesVariantDetailsConfirmedByUrl(savedTitle, archivedTitle, offerUrl)
                }
                // hasExplicitProductVariantConflict also flags a title that omits capacity.
                // That is not proof of a mismatch when this exact JSON-LD offer has a URL that
                // independently verifies the missing SKU details. Explicit model/edition/
                // colour/capacity conflicts still fail matchesVariantDetailsConfirmedByUrl.
                if (hasExplicitProductVariantConflict(savedTitle, archivedTitle) &&
                    !offerUrlConfirmsVariant
                ) return@mapNotNull null
                // On a family page the URL may identify the primary target but not this sibling.
                // Only accept a sibling when its own Product/Offer URL independently confirms it.
                if (!pageConfirmsVariant && !offerUrlConfirmsVariant) return@mapNotNull null
                Triple(productId, savedTitle, offerUrlConfirmsVariant)
            }
            // Duplicate retailer listings or similarly named variants are ambiguous: do not guess.
            if (matches.size != 1) continue
            val (productId, canonicalTitle, offerUrlConfirmed) = matches.single()
            output += ArchivedPriceQuote(
                observedAt = observedAt,
                price = price,
                currency = currency,
                productTitle = canonicalTitle,
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl,
                sourceCurrency = currency,
                sourcePrice = price,
                targetProductId = productId,
                variantIdentityConfirmedByUrl = offerUrlConfirmed
            )
            if (output.size >= 8) break
        }
        return output.distinctBy {
            listOf(it.targetProductId.orEmpty(), it.observedAt.toString(), it.price.toString(), it.currency)
        }
    }

    internal fun extractJsonLdProductOffers(document: Document): List<Triple<String, Double, String>> {
        val roots = document.select("script").asSequence()
            .filter { script ->
                script.attr("type").substringBefore(';').trim()
                    .equals("application/ld+json", ignoreCase = true)
            }
            .mapNotNull { script ->
                val payload = script.data().ifBlank { script.html() }
                    .trim()
                    .removePrefix("<!--").removeSuffix("-->").trim()
                try { JSONTokener(payload).nextValue() } catch (_: Exception) { null }
            }
            .toList()
        val output = mutableListOf<Triple<String, Double, String>>()
        val productNamesById = linkedMapOf<String, Pair<String, String>>()
        roots.forEach { indexJsonLdProducts(it, productNamesById) }
        roots.forEach { visitJsonLd(it, output) }
        roots.forEach { visitLinkedJsonLdOffers(it, productNamesById, output) }
        return output.distinct()
    }

    private fun jsonLdTypeNames(value: JSONObject): Set<String> {
        val rawType = value.opt("@type") ?: return emptySet()
        val types = when (rawType) {
            is JSONArray -> (0 until rawType.length()).mapNotNull { rawType.optString(it).takeIf { typeName -> typeName.isNotBlank() } }
            else -> listOf(rawType.toString())
        }
        return types.map { it.substringAfterLast('/').substringAfterLast('#').lowercase(Locale.ROOT) }.toSet()
    }

    private fun jsonLdCurrencyHint(value: JSONObject): String {
        return value.optString("priceCurrency")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: value.optJSONObject("priceSpecification")?.optString("priceCurrency")
                ?.takeIf { it.isNotBlank() && it != "null" }
            ?: ""
    }

    private fun indexJsonLdProducts(value: Any?, productsById: MutableMap<String, Pair<String, String>>) {
        when (value) {
            is JSONObject -> {
                val productTypes = setOf("product", "productgroup", "individualproduct", "productmodel")
                val name = value.optString("name").takeIf { it.isNotBlank() && it != "null" }
                val id = value.optString("@id").takeIf { it.isNotBlank() && it != "null" }
                if (name != null && id != null && jsonLdTypeNames(value).any { it in productTypes }) {
                    productsById[id] = name to jsonLdCurrencyHint(value).uppercase(Locale.ROOT)
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "offers") indexJsonLdProducts(value.opt(key), productsById)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) indexJsonLdProducts(value.opt(i), productsById)
        }
    }

    private fun linkedProductIdentities(
        value: Any?,
        productsById: Map<String, Pair<String, String>>
    ): List<Pair<String, String>> {
        val found = linkedSetOf<Pair<String, String>>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val id = node.optString("@id").takeIf { it.isNotBlank() && it != "null" }
                    if (id != null) productsById[id]?.let(found::add)
                    val name = node.optString("name").takeIf { it.isNotBlank() && it != "null" }
                    if (name != null) found += name to jsonLdCurrencyHint(node).uppercase(Locale.ROOT)
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return found.toList()
    }

    private fun visitLinkedJsonLdOffers(
        value: Any?,
        productsById: Map<String, Pair<String, String>>,
        out: MutableList<Triple<String, Double, String>>
    ) {
        when (value) {
            is JSONObject -> {
                val itemOffered = value.opt("itemOffered")
                if (itemOffered != null &&
                    (value.has("price") || value.has("lowPrice") || value.has("priceSpecification"))
                ) {
                    val identities = linkedProductIdentities(itemOffered, productsById)
                    val localCurrency = jsonLdCurrencyHint(value)
                    for ((name, productCurrency) in identities) {
                        val offers = collectOfferPrices(value, localCurrency.ifBlank { productCurrency })
                        offers.forEach { (price, currency) -> out += Triple(name, price, currency) }
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "offers") visitLinkedJsonLdOffers(value.opt(key), productsById, out)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) visitLinkedJsonLdOffers(value.opt(i), productsById, out)
        }
    }

    private fun visitJsonLd(value: Any?, out: MutableList<Triple<String, Double, String>>) {
        when (value) {
            is JSONObject -> {
                val name = value.optString("name").takeIf { it.isNotBlank() && it != "null" }
                val offers = value.opt("offers")
                val productCurrency = value.optString("priceCurrency")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: value.optJSONObject("priceSpecification")?.optString("priceCurrency")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    .orEmpty()
                if (!name.isNullOrBlank() && offers != null) {
                    collectOfferPrices(offers, productCurrency).forEach { (price, currency) ->
                        out += Triple(name, price, currency)
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "offers") visitJsonLd(value.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) visitJsonLd(value.opt(i), out)
        }
    }

    private fun collectOfferPrices(value: Any?, inheritedCurrency: String = ""): List<Pair<Double, String>> {
        val found = mutableListOf<Pair<Double, String>>()
        fun visit(node: Any?, parentCurrency: String) {
            when (node) {
                is JSONObject -> {
                    val specification = node.optJSONObject("priceSpecification")
                    val localCurrency = node.optString("priceCurrency")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: specification?.optString("priceCurrency")
                            ?.takeIf { it.isNotBlank() && it != "null" }
                    val currency = localCurrency ?: parentCurrency
                    // Product pages often expose a ProductGroup/AggregateOffer with lowPrice
                    // instead of a single Offer.price. Use the explicit aggregate minimum only
                    // when a normal price is absent; the product-title, EUR and range checks still
                    // run before any historical observation can be saved.
                    val rawPrice = node.opt("price")
                        ?: specification?.opt("price")
                        ?: node.opt("lowPrice")
                        ?: specification?.opt("lowPrice")
                    val price = parsePositivePrice(rawPrice?.toString())
                    if (price != null && currency.isNotBlank()) found += price to currency.uppercase(Locale.ROOT)
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in setOf("price", "priceCurrency", "priceSpecification")) visit(node.opt(key), currency)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), parentCurrency)
            }
        }
        visit(value, inheritedCurrency.uppercase(Locale.ROOT))
        return found
    }

    private fun extractArchivedPageTitles(document: Document): List<String> {
        val titles = buildList {
            addAll(listOfNotNull(
                readMeta(document, setOf("og:title")),
                readMeta(document, setOf("twitter:title")),
                readMeta(document, setOf("title")),
                readMeta(document, setOf("headline")),
                readItemPropValue(document, "name"),
                readItemPropValue(document, "headline"),
                document.selectFirst("title")?.text()
            ))
            addAll(extractHtmlProductTitleCandidates(document))
        }
        return titles.asSequence()
            .map { decodeHtml(it).replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length in 3..180 }
            .distinctBy(::normalizeTitle)
            .take(16)
            .toList()
    }

    /**
     * Recover product headings and product-name attributes from archived HTML. Jsoup creates a
     * balanced DOM even when tags are missing or nested incorrectly. Candidates are still checked
     * against model, trim, condition, colour and capacity before a price can be accepted.
     */
    private fun extractHtmlProductTitleCandidates(document: Document): List<String> {
        val output = linkedSetOf<String>()
        val productSelector = Regex("""(?i)(product[-_ ]?(?:title|name)|producttitle|productname|pdp[-_ ]?title)""")
        for (element in document.getAllElements()) {
            if (output.size >= 12) break
            val tagName = element.normalName()
            val itemProps = element.attr("itemprop").lowercase(Locale.ROOT)
                .split(Regex("""\s+""")).filter { it.isNotBlank() }
            val selectorText = listOf(
                element.className(), element.id(), element.attr("data-testid"),
                element.attr("data-test"), element.attr("data-qa")
            ).filter { it.isNotBlank() }.joinToString(" ")
            val isTitleElement = tagName in setOf("h1", "h2", "h3") ||
                itemProps.any { it in setOf("name", "headline") } ||
                productSelector.containsMatchIn(selectorText)
            sequenceOf("data-product-title", "data-product-name")
                .map { element.attr(it).trim() }
                .firstOrNull { it.length in 3..180 }
                ?.let(output::add)
            if (!isTitleElement || tagName in setOf("script", "style", "noscript", "meta", "input", "img")) continue

            val candidate = sequenceOf(
                element.attr("content"),
                element.attr("value"),
                element.attr("aria-label"),
                element.text()
            ).map { it.replace(Regex("""\s+"""), " ").trim() }
                .firstOrNull { it.length in 3..180 }
            if (candidate != null) output += candidate
        }
        return output.distinctBy(::normalizeTitle).toList()
    }

    /**
     * Extract visible price only from an element explicitly associated with product pricing.
     * Prefer itemprop price and current/sale selectors over wrappers containing crossed-out prices.
     * A EUR marker or currency metadata scoped to the price/product is required.
     */
    private fun extractVisibleProductPrice(
        document: Document,
        knownCurrency: String?,
        acceptedCurrencies: Set<String> = setOf("EUR")
    ): Pair<Double, String>? {
        val priceSelector = Regex("""(?i)(product[-_ ]?price|current[-_ ]?price|sale[-_ ]?price|selling[-_ ]?price|final[-_ ]?price|special[-_ ]?price|price__current|price--current|price__sale|price--sale|price__amount|price-amount|pricevalue|price-value|a-price|pdp-price|priceblock_(?:our|deal|sale)price|^price$)""")
        val rejectedSelector = Regex("""(?i)(old|previous|regular|original|was[-_ ]?price|list[-_ ]?price|strike|crossed|installment|monthly|shipping|unit[-_ ]?price|price[-_ ]?before)""")
        val voidTags = setOf("meta", "input", "img", "br", "hr", "link", "source", "area", "embed", "track", "wbr")
        val euroAmount = Regex("""(?i)(?:€\s*[0-9][0-9., \u00a0]*|[0-9][0-9., \u00a0]*\s*€|\bEUR\s*[0-9][0-9., \u00a0]*|[0-9][0-9., \u00a0]*\s*\bEUR\b)""")
        val usdAmount = Regex("""(?i)(?:\$\s*[0-9][0-9., \u00a0]*|[0-9][0-9., \u00a0]*\s*\$|\bUSD\s*[0-9][0-9., \u00a0]*|[0-9][0-9., \u00a0]*\s*\bUSD\b)""")
        val number = Regex("""(?<![A-Za-z0-9])(?:[0-9]{1,3}(?:[., \u00a0][0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?(?![A-Za-z0-9])""")
        val allowed = acceptedCurrencies.map { it.uppercase(Locale.ROOT) }.toSet()
        val candidates = mutableListOf<Triple<Int, Double, String>>()
        val preferredCurrentPrice = Regex("""(?i)(current[-_ ]?price|sale[-_ ]?price|selling[-_ ]?price|price__current|price--current|price__sale|price--sale|priceblock_(?:deal|sale)price)""")
        for (element in document.getAllElements()) {
            val tagName = element.normalName()
            if (tagName in voidTags) continue
            val itemProps = element.attr("itemprop").lowercase(Locale.ROOT)
                .split(Regex("""\s+""")).filter { it.isNotBlank() }
            val selector = listOf(
                element.className(), element.id(), element.attr("data-testid"),
                element.attr("data-test"), element.attr("data-qa")
            ).filter { it.isNotBlank() }.joinToString(" ")
            val hasDataPrice = sequenceOf("data-price", "data-price-amount", "data-product-price")
                .any { element.hasAttr(it) }
            val isPriceElement = itemProps.any { it in setOf("price", "lowprice") } ||
                hasDataPrice || priceSelector.containsMatchIn(selector)
            if (!isPriceElement || rejectedSelector.containsMatchIn(selector)) continue

            val text = element.text().replace(Regex("""\s+"""), " ").trim()
            val directValue = sequenceOf(
                "content", "data-price", "data-price-amount", "data-product-price", "value", "data-value"
            ).map { element.attr(it) }.firstOrNull { it.isNotBlank() }
            val localCurrency = sequenceOf(element.attr("data-currency"), element.attr("currency"))
                .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT)
            val scopedEuroAmount = euroAmount.find(text)
            val scopedUsdAmount = usdAmount.find(text)
            // Some stores put the amount in a child span and the euro marker in its price wrapper.
            // Infer EUR only from an explicit marker; never default an unlabelled number to EUR.
            val visibleCurrency = when {
                scopedEuroAmount != null &&
                    !text.contains(36.toChar()) && !text.contains("£") &&
                    !text.contains("USD", ignoreCase = true) && !text.contains("GBP", ignoreCase = true) &&
                    !text.contains("CAD", ignoreCase = true) && !text.contains("AUD", ignoreCase = true) -> "EUR"
                scopedUsdAmount != null &&
                    !text.contains("€") && !text.contains("£") && !text.contains("EUR", ignoreCase = true) &&
                    (localCurrency == "USD" || knownCurrency.equals("USD", ignoreCase = true)) -> "USD"
                else -> null
            }
            val effectiveCurrency = localCurrency?.takeIf { it in allowed }
                ?: knownCurrency?.uppercase(Locale.ROOT)?.takeIf { it in allowed }
                ?: visibleCurrency?.takeIf { it in allowed }
            if (effectiveCurrency == null) continue

            val visibleCurrencyAmount = when (effectiveCurrency) {
                "USD" -> scopedUsdAmount
                "EUR" -> scopedEuroAmount
                else -> null
            }
            val hasOpposingCurrency = when (effectiveCurrency) {
                "USD" -> Regex("""(?i)(€|£|\bEUR\b|\bGBP\b|\bCAD\b|\bAUD\b)""").containsMatchIn(text)
                "EUR" -> Regex("""(?i)(\$|£|\bUSD\b|\bGBP\b|\bCAD\b|\bAUD\b|\bPLN\b|\bSEK\b|\bNOK\b|\bDKK\b|\bCHF\b)""").containsMatchIn(text)
                else -> true
            }
            if (hasOpposingCurrency) continue

            // A bare dollar sign is ambiguous (USD/CAD/AUD), so USD requires explicit USD
            // currency metadata or a data-currency marker on this price element.
            if (effectiveCurrency == "USD" &&
                localCurrency != "USD" && knownCurrency?.equals("USD", ignoreCase = true) != true
            ) continue
            if (visibleCurrencyAmount == null && localCurrency != effectiveCurrency &&
                knownCurrency?.uppercase(Locale.ROOT) != effectiveCurrency
            ) continue

            val rawNumber = visibleCurrencyAmount?.value ?: directValue ?: number.find(text)?.value ?: continue
            val parsed = parsePositivePrice(rawNumber) ?: continue
            if (parsed !in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE) continue
            val priority = when {
                itemProps.contains("price") -> 0
                preferredCurrentPrice.containsMatchIn(selector) -> 1
                else -> 2
            }
            candidates += Triple(priority, parsed, effectiveCurrency)
        }
        return candidates.minByOrNull { it.first }?.let { it.second to it.third }
    }

        private fun readMeta(document: Document, names: Set<String>): String? {
        val normalizedNames = names.map { it.lowercase(Locale.ROOT) }.toSet()
        for (element in document.select("meta")) {
            val keys = listOf(element.attr("property"), element.attr("name"), element.attr("itemprop"))
                .filter { it.isNotBlank() }
                .flatMap { it.lowercase(Locale.ROOT).split(Regex("""\s+""")) }
            if (keys.any { it in normalizedNames }) {
                element.attr("content").takeIf { it.isNotBlank() }?.let { return decodeHtml(it).trim() }
            }
        }
        return null
    }

    private fun readItemPropValue(document: Document, name: String): String? {
        val wanted = name.lowercase(Locale.ROOT)
        for (element in document.getAllElements()) {
            val itemProps = element.attr("itemprop").lowercase(Locale.ROOT)
                .split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (wanted !in itemProps) continue
            val explicit = sequenceOf(
                "content", "value", "data-price", "data-price-amount", "data-product-price", "data-value"
            ).map { element.attr(it) }.firstOrNull { it.isNotBlank() }
            if (explicit != null) return decodeHtml(explicit).trim()
            val text = element.text().replace(Regex("""\s+"""), " ").trim()
            if (text.isNotBlank() && text.length <= 120) return text
        }
        return null
    }

    /**
     * Apple can archive the exact configurator URL while its structured product title omits
     * storage and colour (for example, just "iPhone 15"). In that narrow case the canonical
     * configurator path is the SKU identity anchor. Do not use this for generic family pages or
     * for titles that explicitly contradict the selected model, storage, or colour.
     */
    private fun isMatchingTitleForArchivedUrl(
        expectedTitle: String,
        archivedTitle: String,
        originalUrl: String
    ): Boolean {
        if (isMatchingProductTitle(expectedTitle, archivedTitle)) return true
        if (matchesVariantDetailsConfirmedByUrl(expectedTitle, archivedTitle, originalUrl)) return true

        val expected = normalizeTitle(expectedTitle)
        val archived = normalizeTitle(archivedTitle)
        if (!Regex("""\biphone\s+15\b""").containsMatchIn(expected) ||
            !Regex("""\biphone\s+15\b""").containsMatchIn(archived)
        ) return false

        val expectedVariants = productModelVariantTokens(expected)
        if (expectedVariants.any { it != "plus" }) return false
        val uri = runCatching { URI(originalUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        if (host != "apple.com") return false

        val capacity = Regex("""\b(128|256|512)\s*gb\b""")
            .find(expected)?.groupValues?.get(1) ?: return false
        val colourSlug = when (productColourFamily(expected)) {
            "black" -> "negro"
            "blue" -> "azul"
            "green" -> "verde"
            "yellow" -> "amarillo"
            "pink" -> "rosa"
            else -> return false
        }
        val display = if ("plus" in expectedVariants) "6,7" else "6,1"
        val expectedPath = "/es/shop/buy-iphone/iphone-15/pantalla-de-$display″-${capacity}gb-$colourSlug"
        if (!uri.path.orEmpty().lowercase(Locale.ROOT).contains(expectedPath)) return false

        // Missing variant labels on Apple's page title are acceptable because the exact route
        // has been verified. An explicit mismatch is not.
        val archivedVariants = productModelVariantTokens(archived)
        if (archivedVariants.any { it !in expectedVariants }) return false
        val archivedColour = productColourFamily(archived)
        val expectedColour = productColourFamily(expected)
        if (archivedColour != null && archivedColour != expectedColour) return false

        val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val archivedStorage = storagePattern.findAll(archived)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        if (archivedStorage.isNotEmpty() && "${capacity}gb" !in archivedStorage) return false
        return true
    }

    private fun isMatchingProductTitle(expected: String, archived: String): Boolean {
        val e = normalizeTitle(expected)
        val a = normalizeTitle(archived)
        if (e.isBlank() || a.isBlank()) return false
        if (productConditionKey(e) != productConditionKey(a)) return false

        // Colours are optional in some archived headings, but explicit conflicting colours
        // must never be treated as the same SKU. Normalize common Spanish/English/Italian names.
        val expectedColourFamily = productColourFamily(e)
        val archivedColourFamily = productColourFamily(a)
        // Missing colour in an archived title is unknown, not a contradiction. Only an
        // explicitly different archived colour rejects the candidate; storage and model checks
        // below remain strict so a generic page cannot lend a price to another SKU.
        if (expectedColourFamily != archivedColourFamily) return false

        // Trim/model suffixes (Pro, Ultra, Max, etc.) are meaningful for phones and scooters.
        // Require the same explicit trim markers on both titles to avoid cross-variant prices.
        if (productModelVariantTokens(e) != productModelVariantTokens(a)) return false
        val strictStoragePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorageIdentity = strictStoragePattern.findAll(e)
            .map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        val archivedStorageIdentity = strictStoragePattern.findAll(a)
            .map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        if (expectedStorageIdentity != archivedStorageIdentity) return false

        // Xiaomi archive titles frequently omit a colour suffix or translate "Gris" to
        // "Grey/Gray/Grigio". Treat the exact Mi model as the identity anchor, while refusing
        // any conflicting numeric/storage variant or an explicitly different colour.
        val expectedXiaomiModel = xiaomiMiModel(e)
        val archivedXiaomiModel = xiaomiMiModel(a)
        if (expectedXiaomiModel != null && archivedXiaomiModel != null) {
            if (expectedXiaomiModel != archivedXiaomiModel) return false
            val expectedVariants = requiredVariantTokens(canonicalizeArchiveTitle(e))
            val archivedVariants = requiredVariantTokens(canonicalizeArchiveTitle(a))
            if (!archivedVariants.containsAll(expectedVariants)) return false
            val expectedColour = productColourFamily(e)
            val archivedColour = productColourFamily(a)
            if (expectedColour != null && archivedColour != null &&
                expectedColour != archivedColour
            ) return false
            return true
        }

        // Titles from Samsung's regional catalogues use TQ/QE prefixes for the same EU TV
        // model. Compare the exact compact model code and diagonal before fuzzy text matching.
        if (Regex("""\bsamsung\b""").containsMatchIn(e) &&
            Regex("""\bsamsung\b""").containsMatchIn(a)) {
            fun samsungModel(value: String): String? =
                Regex("""\b(?:tq|qe)(\d{2}[a-z0-9]{5,})""")
                    .find(value)?.groupValues?.getOrNull(1)
                    ?: Regex("""\b(\d{2}[a-z0-9]{5,})""")
                        .find(value)?.groupValues?.getOrNull(1)

            fun diagonal(value: String): String? =
                Regex("""\b(\d{2})\s*(?:inch|inches|pulgadas)?\b""")
                    .find(value)?.groupValues?.getOrNull(1)

            val expectedModel = samsungModel(e)
            val archivedModel = samsungModel(a)
            val expectedDiagonal = diagonal(e)
            val archivedDiagonal = diagonal(a)
            if (expectedModel != null && archivedModel != null &&
                expectedModel == archivedModel &&
                expectedDiagonal != null && archivedDiagonal != null &&
                expectedDiagonal == archivedDiagonal) return true
        }

        val canonicalExpected = canonicalizeArchiveTitle(e)
        val canonicalArchived = canonicalizeArchiveTitle(a)
        val sameIdentity =
            onlineProductIdentityKey(canonicalExpected) == onlineProductIdentityKey(canonicalArchived)

        // Card identity intentionally ignores storage for deduplication. Require exact capacity
        // tokens even when the broad model identity matches; retain existing equivalence rules for
        // model-code spelling (e.g. WH-1000XM5 versus WH1000XM5).
        val expectedVariants = requiredVariantTokens(canonicalExpected)
        val archivedVariants = requiredVariantTokens(canonicalArchived)
        val storageToken = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorage = storageToken.findAll(canonicalExpected)
            .map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        val archivedStorage = storageToken.findAll(canonicalArchived)
            .map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        if (expectedStorage != archivedStorage) return false
        if (!sameIdentity && !archivedVariants.containsAll(expectedVariants)) return false
        if (sameIdentity) return true

        val expectedTokens = meaningfulTokens(canonicalExpected)
        val archivedTokens = meaningfulTokens(canonicalArchived)
        if (expectedTokens.isEmpty() || archivedTokens.isEmpty()) return false
        val overlap = expectedTokens.intersect(archivedTokens)
        return overlap.size >= minOf(2, expectedTokens.size) &&
            overlap.size.toDouble() / expectedTokens.size >= 0.72
    }
    /**
     * Allows a page heading to omit capacity/colour only when the archived original URL
     * explicitly names every missing detail. The model and edition must still match exactly.
     * This supports retailer pages whose visible heading is generic but whose URL targets a SKU.
     */
    internal fun matchesVariantDetailsConfirmedByUrl(
        expectedTitle: String,
        archivedTitle: String,
        originalUrl: String
    ): Boolean {
        val expected = normalizeTitle(expectedTitle)
        val archived = normalizeTitle(archivedTitle)
        if (expected.isBlank() || archived.isBlank()) return false
        if (productConditionKey(expected) != productConditionKey(archived)) return false
        if (productModelVariantTokens(expected) != productModelVariantTokens(archived)) return false

        val expectedColour = productColourFamily(expected)
        val archivedColour = productColourFamily(archived)
        if (expectedColour != null && archivedColour != null && expectedColour != archivedColour) return false

        val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorage = storagePattern.findAll(expected)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        val archivedStorage = storagePattern.findAll(archived)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()

        // An archived page that explicitly names an unrequested capacity is a different SKU.
        if (archivedStorage.any { it !in expectedStorage }) return false
        val missingStorage = expectedStorage.any { it !in archivedStorage }

        val uri = runCatching { URI(originalUrl) }.getOrNull() ?: return false
        val pathText = decodePathUrlComponent(uri.rawPath.orEmpty())
        val identityQueryKeys = setOf(
            "sku", "skuid", "sku_id", "variant", "variantid", "variant_id", "variation",
            "variationid", "variation_id", "productid", "product_id", "offerid", "offer_id",
            "pid", "itemid", "item_id", "asin", "mpn", "gtin", "ean", "model", "modelid",
            "color", "colour", "colorid", "colourid", "storage", "capacity", "size",
            "configuration", "config", "option", "optionid"
        )
        val queryIdentityText = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val rawKey = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', "")
            val key = decodeQueryUrlComponent(rawKey).lowercase(Locale.ROOT)
            if (key !in identityQueryKeys) null else decodeQueryUrlComponent(rawValue).takeIf { it.isNotBlank() }
        }.joinToString(" ")
        val urlTitle = normalizeTitle(pathText + " " + queryIdentityText)
        val urlStorage = storagePattern.findAll(urlTitle)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale.ROOT) }.toSet()
        if (missingStorage && !urlStorage.containsAll(expectedStorage)) return false

        val urlColour = productColourFamily(urlTitle)
        val missingColour = expectedColour != null && archivedColour == null
        // Do not attach a colour-specific archived offer to a saved product whose colour is
        // unknown. The selected product must request the colour that the URL proves.
        if (expectedColour == null && archivedColour != null) return false
        if (missingColour && urlColour != expectedColour) return false
        fun coreIdentity(value: String): String =
            onlineProductIdentityKey(canonicalizeArchiveTitle(value)).split('|')
                .filterNot {
                    it.startsWith("memory:") ||
                        it.startsWith("color:") ||
                        it.startsWith("specs:")
                }
                .joinToString("|")

        if (!missingStorage && !missingColour) {
            // When the archived page URL points at the primary SKU, a sibling offer needs its
            // own URL to independently prove the complete variant. A title match alone is not
            // sufficient. Confirm every explicit discriminator present in the saved title.
            val expectedModel = Regex(
                """\b(iphone|ipad|pixel|redmi|poco|galaxy\s+s|galaxy\s+a|galaxy\s+z)\s*(\d{1,3})\b"""
            ).find(expected)
            val urlModel = Regex(
                """\b(iphone|ipad|pixel|redmi|poco|galaxy\s+s|galaxy\s+a|galaxy\s+z)\s*(\d{1,3})\b"""
            ).find(urlTitle)
            if (expectedModel != null &&
                (urlModel == null ||
                    expectedModel.groupValues[1].replace(Regex("\\s+"), "") !=
                        urlModel.groupValues[1].replace(Regex("\\s+"), "") ||
                    expectedModel.groupValues[2] != urlModel.groupValues[2])
            ) return false

            val expectedVariants = productModelVariantTokens(expected)
            val urlVariants = productModelVariantTokens(urlTitle)
            if (!urlVariants.containsAll(expectedVariants)) return false
            if (expectedStorage.isNotEmpty() && !urlStorage.containsAll(expectedStorage)) return false
            if (expectedColour != null && urlColour != expectedColour) return false

            // A URL without any SKU-specific evidence is merely another generic product page.
            val hasSkuEvidence = expectedStorage.isNotEmpty() ||
                expectedColour != null ||
                expectedVariants.isNotEmpty() ||
                expectedModel != null
            if (!hasSkuEvidence) return false
            return coreIdentity(expected) == coreIdentity(archived)
        }

        return coreIdentity(expected) == coreIdentity(archived)
    }

    private fun hasExplicitProductVariantConflict(expected: String, archived: String): Boolean {
        val e = canonicalizeArchiveTitle(normalizeTitle(expected))
        val a = canonicalizeArchiveTitle(normalizeTitle(archived))
        if (e.isBlank() || a.isBlank() || isMatchingProductTitle(expected, archived)) return false

        val expectedIdentifiers = requiredVariantTokens(e)
        val archivedIdentifiers = requiredVariantTokens(a)
        val sharedIdentifier = expectedIdentifiers.intersect(archivedIdentifiers).isNotEmpty()
        val sameBroadIdentity = onlineProductIdentityKey(e) == onlineProductIdentityKey(a)
        if (!sharedIdentifier && !sameBroadIdentity) return false

        val expectedColour = productColourFamily(e)
        val archivedColour = productColourFamily(a)
        if (expectedColour != null && expectedColour != archivedColour) return true

        val expectedModelVariants = productModelVariantTokens(e)
        val archivedModelVariants = productModelVariantTokens(a)
        if (expectedModelVariants != archivedModelVariants &&
            (expectedModelVariants.isNotEmpty() || archivedModelVariants.isNotEmpty())
        ) return true

        val storagePattern = Regex("""\b\d+\s*(?:gb|tb)\b""")
        val expectedStorage = storagePattern.findAll(e).map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        val archivedStorage = storagePattern.findAll(a).map { it.value.replace(Regex("""\s+"""), "") }.toSet()
        if (expectedStorage.isNotEmpty() && !archivedStorage.containsAll(expectedStorage)) return true

        return expectedIdentifiers.isNotEmpty() &&
            expectedIdentifiers.any { it.matches(Regex("""\d+(?:gb|tb|mah|wh|w|hz|mm|cm)?""")) } &&
            !archivedIdentifiers.containsAll(expectedIdentifiers)
    }

    private fun xiaomiMiModel(normalizedTitle: String): String? {
        if (!Regex("""\bxiaomi\b""").containsMatchIn(normalizedTitle) &&
            !Regex("""\bmi\s+\d{1,2}\s+(?:pro|lite|ultra|note)\b""").containsMatchIn(normalizedTitle)
        ) return null
        return Regex("""\bmi\s+(\d{1,2})\s+(pro|lite|ultra|note)\b""")
            .find(normalizedTitle)
            ?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }

    private fun productColourFamily(normalizedTitle: String): String? {
        val tokens = normalizedTitle.split(' ').toSet()
        val matches = linkedSetOf<String>()
        if (tokens.any { it in setOf("gris", "grey", "gray", "grigio", "silver", "plata", "argento") }) matches += "grey"
        if (tokens.any { it in setOf("negro", "negra", "black", "nero") }) matches += "black"
        if (tokens.any { it in setOf("blanco", "blanca", "white", "bianco") }) matches += "white"
        if (tokens.any { it in setOf("azul", "blue", "bleu") }) matches += "blue"
        if (tokens.any { it in setOf("verde", "green", "verde") }) matches += "green"
        if (tokens.any { it in setOf("rojo", "roja", "red", "rosso") }) matches += "red"
        if (tokens.any { it in setOf("amarillo", "amarilla", "yellow", "giallo") }) matches += "yellow"
        if (tokens.any { it in setOf("rosa", "pink", "rose") }) matches += "pink"
        if (tokens.any { it in setOf("morado", "morada", "purple", "violet", "violeta") }) matches += "purple"
        if (tokens.any { it in setOf("dorado", "dorada", "gold", "oro") }) matches += "gold"
        if (tokens.any { it in setOf("naranja", "orange", "arancione") }) matches += "orange"
        if (tokens.any { it in setOf("marron", "brown", "cafe") }) matches += "brown"
        if (tokens.any { it in setOf("beige", "arena", "sand") }) matches += "beige"
        return matches.sorted().joinToString("+").takeIf { it.isNotBlank() }
    }

    private fun productConditionKey(title: String): String = when {
        Regex("""\b(reacondicionado|reacondicionada|refurbished|renewed)\b""")
            .containsMatchIn(title) -> "refurbished"
        Regex("""\b(usado|usada|used|seminuevo|seminueva|segunda mano)\b""")
            .containsMatchIn(title) -> "used"
        Regex("""\b(open box|open-box|caja abierta)\b""")
            .containsMatchIn(title) -> "open-box"
        else -> "standard"
    }

    private fun canonicalizeArchiveTitle(normalizedTitle: String): String {
        // Xiaomi's global/European catalogue commonly alternates between "Xiaomi" and "Mi"
        // for the same electric-scooter family. Normalize only this explicit product phrase.
        var canonical = normalizedTitle
            .replace(Regex("""\bxiaomi\s+mi\s+electric\s+scooter\b"""), "xiaomi electric scooter")
            .replace(Regex("""\bmi\s+electric\s+scooter\b"""), "xiaomi electric scooter")
        // These regional prefixes are only interchangeable for Samsung model identifiers.
        if (!Regex("""\bsamsung\b""").containsMatchIn(canonical)) return canonical
        canonical = canonical
            // Normalize only known Samsung TV regional model prefixes in compact alphanumeric
            // identifiers (e.g. TQ48S95HAEXXC vs QE48S95HAEXXC); retain size and suffix.
            .replace(Regex("""\b(?:tq|qe)(?=\d{2}[a-z0-9]{5,})"""), "")
            .replace(Regex("""\b(\d{2,})\s+inches?\b"""), "$1")
            .replace(Regex("""\b(?:2k|4k|8k|uhd|fhd|fullhd)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return canonical
    }

    /**
     * Explicit model/trim names that distinguish common product lines. We compare the set
     * rather than only checking overlap, so "Scooter 4 Pro" cannot match "Scooter 4 Ultra"
     * and "Galaxy S26 Pro" cannot silently match "Galaxy S26 Pro Max".
     */
    private fun productModelVariantTokens(normalizedTitle: String): Set<String> {
        val qualifiers = setOf(
            "pro", "ultra", "lite", "plus", "max", "mini", "edge", "fe", "se",
            "air", "sport", "gt", "prime", "active", "essential", "neo", "note",
            "fold", "flip", "play", "classic", "racing", "performance", "master",
            "touring", "urban", "city", "offroad", "slim", "super", "ti", "xt",
            "xtx", "gre", "oled", "qled", "s", "x", "mark", "mk", "ii", "iii", "iv"
        )
        val canonical = normalizedTitle.replace(Regex("""\boff\s+road\b"""), "offroad")
        return canonical.split(' ').filter { it in qualifiers }.toSet()
    }

    /**
     * Variant-defining numeric tokens must survive a fuzzy title match. Capacity and model
     * numbers are required when present in the selected product title.
     */
    private fun requiredVariantTokens(normalizedTitle: String): Set<String> {
        val unitRegex = Regex("""\b(\d+)\s+(gb|tb|mb|mah|wh|w|hz|mm|cm|inch|inches)\b""")
        val canonical = unitRegex.replace(normalizedTitle) { match ->
            match.groupValues[1] + match.groupValues[2]
        }
        return canonical.split(' ').filter { token ->
            token.length >= 2 && token.any(Char::isDigit) &&
                (token.any(Char::isLetter) || token.all(Char::isDigit))
        }.toSet()
    }

    private fun meaningfulTokens(value: String): Set<String> {
        val ignored = setOf(
            "the", "and", "for", "with", "from", "new", "used", "de", "del", "la", "las", "el",
            "los", "un", "una", "para", "con", "en", "of", "by", "sale", "buy", "official",
            "original", "product", "producto", "neu", "felge", "felgen", "llanta", "llantas",
            "rueda", "ruedas", "wheel", "wheels", "rim", "rims", "alloy", "alloywheel"
        )
        return value.split(' ').filter { it.length >= 2 && it !in ignored }.toSet()
    }

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(decodeHtml(value).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            // Preserve the plus suffix on phone models such as Galaxy S26+; punctuation
            // removal otherwise makes it indistinguishable from the base S26 variant.
            .replace(Regex("""\b(s\d{1,2})\s*\+"""), "$1 plus")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun parsePositivePrice(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        var numeric = raw.trim().replace(Regex("""[^\d,.-]"""), "")
        if (numeric.isBlank()) return null
        val comma = numeric.lastIndexOf(',')
        val dot = numeric.lastIndexOf('.')
        numeric = when {
            comma >= 0 && dot >= 0 && comma > dot ->
                numeric.replace(".", "").replace(',', '.')
            comma >= 0 && dot >= 0 && dot > comma ->
                numeric.replace(",", "")
            comma >= 0 && dot < 0 -> {
                val decimalDigits = numeric.length - comma - 1
                if (decimalDigits in 1..2) numeric.replace(',', '.') else numeric.replace(",", "")
            }
            comma < 0 && dot >= 0 && numeric.substringAfterLast('.').length == 3 ->
                numeric.replace(".", "")
            else -> numeric
        }
        val price = numeric.toDoubleOrNull() ?: return null
        return price.takeIf { it.isFinite() && it > 0.0 }
    }

    internal fun isEligibleDirectUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        if (!uri.scheme.equals("https", ignoreCase = true) || host.isBlank() || uri.rawUserInfo != null ||
            uri.path.isNullOrBlank() || uri.path == "/" ||
            isIntermediaryRedirectHost(host) || isNonRetailerArchiveOrSearchHost(host)
        ) return false
        if (host == "web.archive.org" || host == "archive.org" ||
            host.startsWith("google.") || host.endsWith(".google.com") || host.endsWith(".google.es") ||
            host == "bing.com" || host.endsWith(".bing.com")
        ) return false
        return true
    }

    /** Read bounded response bodies so unexpectedly large archived pages cannot spike app memory. */
    internal fun classifyNetworkFailure(stage: String, failure: Exception): String = when {
        failure is SocketTimeoutException || failure is InterruptedIOException -> "${stage}_TIMEOUT"
        isDnsResolutionFailure(failure) -> "${stage}_DNS"
        failure is IOException -> "${stage}_NETWORK"
        else -> "${stage}_UNEXPECTED_ERROR"
    }

    private fun readBodyLimited(body: ResponseBody?, maxBytes: Int): String? {
        body ?: return null
        if (body.contentLength() > maxBytes) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > maxBytes - total) return null
                output.write(buffer, 0, count)
                total += count
            }
        }
        // Old retailer snapshots may use ISO-8859-1 or another declared HTML charset.
        // Decode using the HTTP Content-Type instead of forcing UTF-8, which can corrupt product
        // names/accents and make exact title verification reject a valid capture.
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return output.toString(charset.name())
    }

    /**
     * Select a representative capture per UTC quarter before applying the download cap.
     * CDX already returns a bounded, monthly-collapsed index; choosing by quarter prevents
     * dense recent months from crowding older periods out of the chart. Every returned item is
     * still independently checked for product identity and price after its HTML is downloaded.
     */
    internal fun sampleCaptures(captures: List<WaybackCapture>, max: Int): List<WaybackCapture> {
        if (max <= 0 || captures.isEmpty()) return emptyList()
        val chronological = captures
            .distinctBy { it.rawTimestamp to it.originalUrl }
            .sortedBy { it.timestamp }
        val quarterly = chronological
            .groupBy { capture ->
                val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT)
                utc.timeInMillis = capture.timestamp
                utc.get(java.util.Calendar.YEAR) * 4 + utc.get(java.util.Calendar.MONTH) / 3
            }
            .toSortedMap()
            .values
            .map { quarterCaptures -> quarterCaptures[quarterCaptures.size / 2] }

        if (quarterly.size <= max) return quarterly
        if (max == 1) return listOf(quarterly.last())
        return (0 until max).map { index ->
            quarterly[(index.toDouble() * (quarterly.size - 1) / (max - 1)).toInt()]
        }.distinctBy { it.rawTimestamp to it.originalUrl }
    }

    /**
     * When exact-URL and prefix CDX results overlap, sample captures that have not already been
     * processed first. If every capture is already cached, return the cached set so callers can
     * reuse its results instead of turning a cache hit into a false empty-history result.
     */
    internal fun sampleUnprocessedCaptures(
        captures: List<WaybackCapture>,
        max: Int,
        processedCaptureKeys: Set<String>
    ): List<WaybackCapture> {
        if (max <= 0 || captures.isEmpty()) return emptyList()
        val unique = captures.distinctBy { it.rawTimestamp to it.originalUrl }
        val unprocessed = unique.filterNot { capture ->
            snapshotCacheKey(capture) in processedCaptureKeys
        }
        return sampleCaptures(unprocessed.ifEmpty { unique }, max)
    }

    private fun attemptKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return "attempt_" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun formatUtc(timestamp: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    private data class CdxIndexOutcome(
        val captures: List<WaybackCapture> = emptyList(),
        val available: Boolean = false,
        val requestFailed: Boolean = false,
        val diagnosticCodes: Set<String> = emptySet()
    )

    private data class SnapshotFetch(
        val fetched: Boolean,
        val quote: ArchivedPriceQuote?,
        val diagnosticCode: String? = null,
        val additionalQuotes: List<ArchivedPriceQuote> = emptyList(),
        val reused: Boolean = false
    )

    internal data class WaybackCapture(
        val rawTimestamp: String,
        val timestamp: Long,
        val originalUrl: String
    )

    companion object {
        const val DEFAULT_LOOKBACK_DAYS = 2920
        // CDX queries run in parallel for the exact URL and merchant-domain fallback.
        // A larger read budget is needed on slow archive responses; concurrency and row caps
        // below still bound total work per product.
        private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
            "co.uk", "com.au", "co.nz", "co.jp", "co.in", "com.br", "com.mx",
            "com.sg", "com.tr", "co.za", "com.ar", "com.cn", "com.tw", "com.pl"
        )
        private const val MAX_CDX_HTTP_RETRIES = 1
        private const val MAX_CONCURRENT_AVAILABILITY_REQUESTS = 1
        private const val MAX_AVAILABILITY_URL_CANDIDATES = 6
        private const val MAX_AVAILABILITY_PROBE_CANDIDATES = 2
        private const val MAX_DOMAIN_AVAILABILITY_PROBE_CANDIDATES = 5
        private const val MAX_CDX_FALLBACK_SNAPSHOTS = 8
        private const val MAX_DOMAIN_CDX_HOST_QUERIES = 2
        // This runs in the background history backfill, not in the main search UI.
        // Four seconds accommodates ordinary CDX latency while staying inside the ViewModel's
        // independent 10-second cap for the whole domain fallback.
        // Broad host scans are a last resort; retain a short bound so they cannot starve
        // Availability fallback or delay the primary shopping-search experience.
        private const val CDX_FALLBACK_TIMEOUT_SECONDS = 3L
        // Historical snapshots sometimes establish the archive connection slowly. The connect,
        // read and whole-call budgets are aligned at 8 seconds; archive work remains bounded and
        // does not run in the live shopping-search request path.
        private const val CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS = 8L
        private const val DIRECT_CDX_SNAPSHOT_TIMEOUT_SECONDS = 8L
        private const val MAX_AVAILABILITY_BODY_BYTES = 64 * 1024
        private const val CDX_RETRY_DELAY_MS = 500L
        private const val CDX_RETRY_JITTER_MS = 350L
        private val RETRYABLE_CDX_HTTP_CODES = setOf(500, 502, 503, 504)
        // Keep direct prefix matching bounded; an exact URL query is attempted first to avoid
        // paying this cost when the exact archived address already provides the required series.
        private const val EXACT_CDX_TIMEOUT_SECONDS = 4L
        private const val DIRECT_CDX_TIMEOUT_SECONDS = 7L
        // This last-resort host scan only runs if no product URL can be built. Keep it
        // short enough to leave time for the two bounded Availability probes.
        private const val DOMAIN_CDX_TIMEOUT_SECONDS = 5L
        // Product-specific CDX lookup is narrower than scanning every URL on a retailer host.
        // A short host-index fallback can discover changed product slugs after path lookups fail.
        private const val DOMAIN_EXACT_CDX_TIMEOUT_SECONDS = 7L
        private const val ALTERNATE_PRODUCT_CDX_TIMEOUT_SECONDS = 5L
        private const val MAX_DOMAIN_PRODUCT_URL_CANDIDATES = 2
        private const val MAX_DIRECT_SNAPSHOTS_PER_URL = 12
        private const val MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP = 8
        // Used only by historical backfill, never by the primary shopping search.
        private const val SNAPSHOT_CALL_TIMEOUT_SECONDS = 7L
        private const val MAX_DOMAIN_INDEX_ROWS = 20
        private const val MAX_CONCURRENT_SNAPSHOT_REQUESTS = 4
        const val RETRY_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1000L
        // Timeouts/transport failures are transient; allow a manual retry after two minutes.
        // Genuine empty results retain the longer negative cache to protect Internet Archive.
        const val FAILURE_RETRY_INTERVAL_MS = 2L * 60L * 1000L
        // Kept at 1.5 seconds globally across CDX, Availability, and snapshot requests.
        private const val MIN_AVAILABILITY_REQUEST_INTERVAL_MS = 1_500L
        // Retain this legacy preference key so older saved pacing timestamps still apply.
        private const val KEY_LAST_AVAILABILITY_REQUEST_AT = "wayback_last_availability_request_at"
        private const val MIN_RATE_LIMIT_COOLDOWN_MS = 60L * 1000L
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 5L * 60L * 1000L
        private const val MAX_RATE_LIMIT_COOLDOWN_MS = 6L * 60L * 60L * 1000L
        private const val RATE_LIMIT_ESCALATION_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val MAX_RATE_LIMIT_HITS = 8
        private const val KEY_RATE_LIMIT_UNTIL = "wayback_rate_limit_until"
        private const val KEY_RATE_LIMIT_LAST_HIT_AT = "wayback_rate_limit_last_hit_at"
        private const val KEY_RATE_LIMIT_HIT_COUNT = "wayback_rate_limit_hit_count"
        private val GLOBAL_AVAILABILITY_SEMAPHORE = Semaphore(1)
        private val GLOBAL_BACKFILL_MUTEX = Mutex()
        private const val MAX_CDX_BODY_BYTES = 256 * 1024
        private const val MAX_SNAPSHOT_BODY_BYTES = 2 * 1024 * 1024
        private const val SNAPSHOT_DNS_RETRY_DELAY_MS = 250L
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_HISTORICAL_PRICE = 0.50
        const val MAX_HISTORICAL_PRICE = 10_000.0
        private const val USER_AGENT = "MinimoPriceHistory/1.0 (historical lookup from public web archive)"
    }
}