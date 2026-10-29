package com.example.data.remote

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Shared bounded quarterly sampler for archived captures. It preserves coverage across years,
 * then uses spare slots for distinct capture timestamps only; alternate retailer URLs for the
 * same snapshot timestamp must not consume extra downloads or create duplicate chart points.
 */
internal fun <T> sampleByQuarter(
    items: List<T>,
    max: Int,
    timestampOf: (T) -> Long,
    keyOf: (T) -> Any
): List<T> {
    if (max <= 0 || items.isEmpty()) return emptyList()
    val chronological = items.distinctBy(keyOf).sortedBy(timestampOf)
    val quarterly = chronological
        .groupBy { item ->
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
            utc.timeInMillis = timestampOf(item)
            utc.get(Calendar.YEAR) * 4 + utc.get(Calendar.MONTH) / 3
        }
        .toSortedMap()
        .values
        .map { it[it.size / 2] }

    if (quarterly.size >= max) {
        if (max == 1) return listOf(quarterly.last())
        return (0 until max)
            .map { index -> quarterly[(index.toDouble() * (quarterly.size - 1) / (max - 1)).toInt()] }
            .distinctBy(timestampOf)
    }

    val selectedKeys = quarterly.map(keyOf).toSet()
    val selectedTimestamps = quarterly.map(timestampOf).toSet()
    val remaining = chronological
        .filterNot { keyOf(it) in selectedKeys || timestampOf(it) in selectedTimestamps }
        .distinctBy(timestampOf)
    val slots = minOf(max - quarterly.size, remaining.size)
    val extra = when {
        slots <= 0 -> emptyList()
        slots == 1 -> listOf(remaining[remaining.size / 2])
        else -> (0 until slots).map { index ->
            remaining[(index.toDouble() * (remaining.size - 1) / (slots - 1)).toInt()]
        }
    }
    return (quarterly + extra).distinctBy(timestampOf).sortedBy(timestampOf)
}

/**
 * A single archived point proves that one capture is useful, but it is not yet a meaningful
 * trend. Ask the independent archive for help when fewer than three distinct UTC dates exist.
 */
internal fun hasSufficientHistoricalCoverage(
    quotes: Iterable<ArchivedPriceQuote>,
    minimumDistinctDates: Int = 3
): Boolean {
    if (minimumDistinctDates <= 0) return true
    return quotes.asSequence()
        .filterNot { it.isDiscoveryCandidate }
        .map { Math.floorDiv(it.observedAt, WaybackPriceHistoryClient.DAY_MS) }
        .distinct()
        .take(minimumDistinctDates)
        .count() >= minimumDistinctDates
}

internal fun shouldAttemptMementoFallback(
    alreadyAttempted: Boolean,
    hasEligibleDirectUrl: Boolean,
    hasSufficientHistory: Boolean,
    rateLimited: Boolean
): Boolean = !alreadyAttempted && hasEligibleDirectUrl && !hasSufficientHistory && !rateLimited

/** Capture Memento traffic in debug and private sideload diagnostics; release remains no-op. */
private fun createMementoHttpClient(context: Context): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
    addDebugNetworkInspector(context.applicationContext, builder)
    return builder.build()
}

/**
 * Independent history source for Arquivo.pt's native Memento TimeMap API.
 *
 * This client is a bounded fallback to Wayback, not a price provider on its own: every retrieved
 * page is parsed by WaybackPriceHistoryClient's existing strict title/variant/EUR-price validator.
 */
class MementoClient(
    context: Context,
    private val priceParser: WaybackPriceHistoryClient = WaybackPriceHistoryClient(context),
    private val http: OkHttpClient = createMementoHttpClient(context)
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "minimo_memento_history", Context.MODE_PRIVATE
    )

    private fun requestGate(wallClockMillis: Long): Set<String>? = synchronized(preferences) {
        val cooldownUntil = preferences.getLong(KEY_COOLDOWN_UNTIL, 0L)
        if (wallClockMillis < cooldownUntil) return@synchronized setOf("MEMENTO_COOLDOWN_ACTIVE")
        val lastRequest = preferences.getLong(KEY_LAST_REQUEST, 0L)
        if (wallClockMillis - lastRequest < MIN_REQUEST_INTERVAL_MS) {
            return@synchronized setOf("MEMENTO_LOCAL_THROTTLE")
        }
        preferences.edit().putLong(KEY_LAST_REQUEST, wallClockMillis).commit()
        null
    }

    private fun activateRateLimit(wallClockMillis: Long) {
        synchronized(preferences) {
            preferences.edit().putLong(KEY_COOLDOWN_UNTIL, wallClockMillis + RATE_LIMIT_COOLDOWN_MS).commit()
        }
    }

    /** Returns Arquivo.pt's independent persisted HTTP 429 cooldown, if it is still active. */
    internal fun activeRateLimitUntil(now: Long = System.currentTimeMillis()): Long? =
        preferences.getLong(KEY_COOLDOWN_UNTIL, 0L).takeIf { it > now }

    /**
     * Arquivo.pt's normal replay URL serves a wrapper page whose iframe contains the archived
     * document. Parse the archived document itself (mp_) so the product title and JSON-LD offers
     * are visible to the same strict validator used for Wayback captures.
     */
    internal fun toContentSnapshotUrl(snapshotUrl: String): String? {
        val uri = parseHttpUri(snapshotUrl) ?: return null
        if (!uri.host.equals(ARCHIVE_HOST, ignoreCase = true)) return null
        val replayPrefix = Regex("""^/wayback/(\d{14})(?:id_|mp_)?/""")
        if (!replayPrefix.containsMatchIn(uri.rawPath.orEmpty())) return null
        return snapshotUrl.replaceFirst(Regex("""(/wayback/\d{14})(?:id_|mp_)?/"""), "$1mp_/")
    }

    /** Strip tracking-only query fields while retaining functional SKU/variant parameters. */
    internal fun canonicalizeArchiveLookupUrl(originalUrl: String): String? {
        val uri = parseHttpUri(originalUrl) ?: return null
        if (isArchiveOrSearchHost(uri.host.orEmpty())) return null
        val candidates = priceParser.buildDirectUrlCandidates(originalUrl)
        // Arquivo.pt's index often stores the canonical product path, not a storefront's
        // variant/country/currency/tracking query string. Prefer a queryless equivalent only
        // when the supplied link actually has a query and the candidate keeps the same path.
        // Functional SKU/colour/capacity parameters are preserved because the shared candidate
        // builder does not produce a queryless candidate for those links.
        val cleanSamePath = if (!uri.rawQuery.isNullOrBlank()) {
            candidates.firstOrNull { candidate ->
                val candidateUri = parseHttpUri(candidate)
                candidateUri != null &&
                    candidateUri.rawPath == uri.rawPath &&
                    candidateUri.rawQuery.isNullOrBlank()
            }
        } else {
            null
        }
        return cleanSamePath ?: candidates.lastOrNull() ?: originalUrl.substringBefore('#')
    }

    suspend fun fetchHistory(
        originalUrl: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS,
        maxSnapshots: Int = 4,
        knownProductTitlesById: Map<String, String> = emptyMap(),
        onVerifiedQuotes: (suspend (List<ArchivedPriceQuote>) -> Unit)? = null
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        val archiveTargetUrl = canonicalizeArchiveLookupUrl(originalUrl)
        val originalUri = archiveTargetUrl?.let(::parseHttpUri)
        if (archiveTargetUrl.isNullOrBlank() || originalUri == null ||
            !originalUri.scheme.equals("https", true) ||
            originalUri.host.isNullOrBlank() || expectedTitle.isBlank() ||
            isArchiveOrSearchHost(originalUri.host)
        ) {
            return@withContext WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = setOf("MEMENTO_INVALID_TARGET")
            )
        }

        val gateDiagnostics = requestGate(System.currentTimeMillis())
        if (gateDiagnostics != null) {
            return@withContext WaybackBackfillResult(
                skippedRecently = true,
                requestFailed = false,
                diagnosticCodes = gateDiagnostics
            )
        }

        val timeMapUrl = buildTimeMapUrl(archiveTargetUrl)
            ?: return@withContext WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = setOf("MEMENTO_TIMEMAP_URL_INVALID")
            )
        val diagnostics = linkedSetOf<String>()
        val timeMapBody = try {
            http.newBuilder()
                .callTimeout(TIMEMAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEMAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url(timeMapUrl)
                        .header("Accept", "application/link-format")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                ).execute().use { response ->
                    when {
                        response.code == 429 -> {
                            activateRateLimit(System.currentTimeMillis())
                            diagnostics += "MEMENTO_RATE_LIMITED"
                            null
                        }
                        !response.isSuccessful -> {
                            diagnostics += "MEMENTO_TIMEMAP_HTTP_${response.code}"
                            null
                        }
                        else -> readBodyLimited(response.body, MAX_TIMEMAP_BYTES).also {
                            if (it == null) diagnostics += "MEMENTO_TIMEMAP_BODY_EMPTY_OR_LIMIT"
                        }
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostics += "MEMENTO_TIMEMAP_NETWORK_ERROR"
            null
        }

        var captureCandidates = timeMapBody
            ?.let { parseTimeMap(it, archiveTargetUrl, now, lookbackDays) }
            .orEmpty()
        var cdxAvailable = false
        var cdxRequestFailed = false
        var usedCdxFallback = false
        // Some valid product URLs return 404 from the Link-format TimeMap endpoint even though
        // Arquivo.pt's CDX index still contains dated captures. Query its official bounded JSON
        // index only for that case or a valid empty TimeMap; never repeat the request after HTTP 429.
        val shouldTryCdx = "MEMENTO_RATE_LIMITED" !in diagnostics &&
            (timeMapBody != null || diagnostics.contains("MEMENTO_TIMEMAP_HTTP_404"))
        if (captureCandidates.isEmpty() && shouldTryCdx) {
            usedCdxFallback = true
            val cdx = fetchCdxCaptures(
                originalUrl = archiveTargetUrl,
                now = now,
                lookbackDays = lookbackDays
            )
            diagnostics += cdx.diagnosticCodes
            captureCandidates = cdx.captures
            cdxAvailable = cdx.available
            cdxRequestFailed = cdx.requestFailed
            if (cdx.available && !cdx.requestFailed) {
                // The CDX index responded correctly even when it had no rows. Report that as an
                // empty historical index, not as a still-failed TimeMap request.
                diagnostics.remove("MEMENTO_TIMEMAP_HTTP_404")
            }
        }

        val captures = sampleQuarterlyCaptures(
            captures = captureCandidates,
            // The fallback spends an extra bounded index request. Three snapshots keep the
            // whole lookup within the independent ViewModel timeout while still sampling dates.
            maxSnapshots = if (usedCdxFallback) minOf(maxSnapshots, MAX_CDX_FALLBACK_SNAPSHOTS) else maxSnapshots
        )
        if (captures.isEmpty()) {
            val noCaptureResult = captureCandidates.isEmpty() &&
                (timeMapBody != null || cdxAvailable) &&
                !cdxRequestFailed && "MEMENTO_RATE_LIMITED" !in diagnostics
            if (noCaptureResult) diagnostics += "MEMENTO_NO_CAPTURES"
            return@withContext WaybackBackfillResult(
                available = timeMapBody != null || cdxAvailable,
                capturesFound = 0,
                capturesChecked = 0,
                requestFailed = cdxRequestFailed || (!noCaptureResult && "MEMENTO_RATE_LIMITED" !in diagnostics),
                diagnosticCodes = diagnostics.ifEmpty { setOf("MEMENTO_TIMEMAP_FAILED") }
            )
        }

        val quotes = mutableListOf<ArchivedPriceQuote>()
        var capturesChecked = 0
        var requestFailed = false
        for (capture in captures) {
            val quoteCountBeforeCapture = quotes.size
            val contentSnapshotUrl = toContentSnapshotUrl(capture.snapshotUrl)
            if (contentSnapshotUrl == null) {
                requestFailed = true
                diagnostics += "MEMENTO_SNAPSHOT_URL_INVALID"
                continue
            }
            try {
                http.newBuilder()
                    .callTimeout(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .newCall(
                        Request.Builder()
                            .url(contentSnapshotUrl)
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()
                    ).execute().use { response ->
                        when {
                            response.code == 429 -> {
                                requestFailed = true
                                activateRateLimit(System.currentTimeMillis())
                                diagnostics += "MEMENTO_RATE_LIMITED"
                            }
                            !response.isSuccessful -> {
                                requestFailed = true
                                diagnostics += "MEMENTO_SNAPSHOT_HTTP_${response.code}"
                            }
                            !response.request.url.host.equals(ARCHIVE_HOST, ignoreCase = true) -> {
                                requestFailed = true
                                diagnostics += "MEMENTO_REDIRECT_HOST_REJECTED"
                            }
                            else -> {
                                val html = readBodyLimited(response.body, MAX_SNAPSHOT_BYTES)
                                capturesChecked++
                                if (html == null) {
                                    diagnostics += "MEMENTO_SNAPSHOT_BODY_EMPTY_OR_LIMIT"
                                } else {
                                    val primarySource = priceParser.parseArchivedPrice(
                                        document = org.jsoup.Jsoup.parse(html),
                                        expectedTitle = expectedTitle,
                                        originalUrl = archiveTargetUrl,
                                        snapshotUrl = capture.snapshotUrl,
                                        observedAt = capture.timestamp,
                                        acceptedCurrencies = setOf("EUR", "USD")
                                    )
                                    val primary = when (primarySource?.currency?.uppercase(Locale.ROOT)) {
                                        "USD" -> primarySource?.let {
                                            priceParser.convertUsdQuoteToEurAtCaptureDate(it)
                                        }
                                        else -> primarySource
                                    }
                                    if (primary != null) quotes += primary

                                    val secondarySources = priceParser.parseArchivedPricesForKnownProducts(
                                        document = org.jsoup.Jsoup.parse(html),
                                        primaryExpectedTitle = expectedTitle,
                                        knownProductTitlesById = knownProductTitlesById,
                                        originalUrl = archiveTargetUrl,
                                        snapshotUrl = capture.snapshotUrl,
                                        observedAt = capture.timestamp,
                                        acceptedCurrencies = setOf("EUR", "USD")
                                    )
                                    for (secondary in secondarySources) {
                                        val validated = when (secondary.currency.uppercase(Locale.ROOT)) {
                                            "USD" -> priceParser.convertUsdQuoteToEurAtCaptureDate(secondary)
                                            "EUR" -> secondary
                                            else -> null
                                        }
                                        if (validated != null) quotes += validated
                                    }
                                    if (primary == null && secondarySources.isEmpty()) {
                                        diagnostics += "MEMENTO_NO_VERIFIED_PRODUCT_PRICE"
                                    }
                                }
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                requestFailed = true
                diagnostics += "MEMENTO_SNAPSHOT_NETWORK_ERROR"
            }
            if (quotes.size > quoteCountBeforeCapture) {
                onVerifiedQuotes?.invoke(quotes.drop(quoteCountBeforeCapture))
            }
            if ("MEMENTO_RATE_LIMITED" in diagnostics) break
        }

        val uniqueQuotes = quotes.distinctBy {
            listOf(
                it.targetProductId.orEmpty(), it.productTitle, it.originalUrl,
                it.productUrl.orEmpty(), it.isDiscoveryCandidate.toString(),
                it.observedAt.toString(), it.price.toString(), it.currency.uppercase(Locale.ROOT)
            )
        }.sortedBy { it.observedAt }
        if (uniqueQuotes.isNotEmpty()) diagnostics += "MEMENTO_RECOVERED_QUOTES"
        else if (diagnostics.isEmpty()) diagnostics += "MEMENTO_NO_VERIFIED_PRICE"
        WaybackBackfillResult(
            quotes = uniqueQuotes,
            capturesFound = captures.size,
            capturesChecked = capturesChecked,
            available = true,
            requestFailed = requestFailed,
            diagnosticCodes = diagnostics
        )
    }

    /**
     * Query Arquivo.pt's native CDX index when the Link-format TimeMap is missing or empty.
     * The index is metadata only: every snapshot still passes the same archived product-title
     * and EUR-price parser used by the normal Memento path.
     */
    private suspend fun fetchCdxCaptures(
        originalUrl: String,
        now: Long,
        lookbackDays: Int
    ): CdxCaptureResult {
        val cdxUrl = buildCdxUrl(originalUrl, now, lookbackDays)
            ?: return CdxCaptureResult(requestFailed = true, diagnosticCodes = setOf("MEMENTO_CDX_URL_INVALID"))
        return try {
            http.newBuilder()
                .callTimeout(TIMEMAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEMAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url(cdxUrl)
                        .header("Accept", "text/x-ndjson")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                ).execute().use { response ->
                    when {
                        response.code == 429 -> {
                            activateRateLimit(System.currentTimeMillis())
                            CdxCaptureResult(
                                requestFailed = true,
                                diagnosticCodes = setOf("MEMENTO_RATE_LIMITED")
                            )
                        }
                        !response.isSuccessful -> CdxCaptureResult(
                            requestFailed = true,
                            diagnosticCodes = setOf("MEMENTO_CDX_HTTP_${response.code}")
                        )
                        else -> {
                            val body = readBodyLimited(response.body, MAX_TIMEMAP_BYTES)
                            if (body == null) {
                                CdxCaptureResult(
                                    available = true,
                                    requestFailed = true,
                                    diagnosticCodes = setOf("MEMENTO_CDX_BODY_EMPTY_OR_LIMIT")
                                )
                            } else {
                                val captures = parseCdxJsonLines(body, originalUrl, now, lookbackDays)
                                CdxCaptureResult(
                                    captures = captures,
                                    available = true,
                                    diagnosticCodes = if (captures.isEmpty()) {
                                        setOf("MEMENTO_CDX_NO_CAPTURES")
                                    } else {
                                        setOf("MEMENTO_CDX_CAPTURE_INDEX")
                                    }
                                )
                            }
                        }
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CdxCaptureResult(
                requestFailed = true,
                diagnosticCodes = setOf(
                    if (error is java.net.SocketTimeoutException) "MEMENTO_CDX_TIMEOUT"
                    else "MEMENTO_CDX_NETWORK_ERROR"
                )
            )
        }
    }

    /** Builds a bounded, exact-URL CDX query; no merchant-wide index scan is performed. */
    internal fun buildCdxUrl(
        originalUrl: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS
    ): String? {
        val uri = parseHttpUri(originalUrl) ?: return null
        if (!uri.scheme.equals("https", true) || isArchiveOrSearchHost(uri.host.orEmpty())) return null
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val earliest = archiveHistoryWindowStartTimestampUtc(now, lookbackDays)
        return "https://$ARCHIVE_HOST/wayback/cdx".toHttpUrl().newBuilder()
            .addQueryParameter("url", originalUrl)
            .addQueryParameter("matchType", "exact")
            .addQueryParameter("from", formatter.format(Date(earliest)))
            .addQueryParameter("to", formatter.format(Date(now)))
            .addQueryParameter("filter", "status:200")
            .addQueryParameter("filter", "mime:text/html")
            .addQueryParameter("resolveRevisits", "true")
            .addQueryParameter("collapseTime", "6")
            // Keep the index request bounded. The parser validates the returned timestamp window
            // and deduplicates captures before downloading any archived HTML.
            .addQueryParameter("limit", MAX_CDX_LINES.toString())
            .addQueryParameter("output", "json")
            .build()
            .toString()
    }

    /** Parses bounded Arquivo.pt CDX NDJSON, requiring a successful HTML capture of this URL. */
    internal fun parseCdxJsonLines(
        body: String,
        originalUrl: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS
    ): List<MementoCapture> {
        if (body.isBlank()) return emptyList()
        val target = parseHttpUri(originalUrl) ?: return emptyList()
        if (!target.scheme.equals("https", true) || isArchiveOrSearchHost(target.host.orEmpty())) {
            return emptyList()
        }
        val earliest = archiveHistoryWindowStartTimestampUtc(now, lookbackDays)
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val captures = mutableListOf<MementoCapture>()
        for (line in body.lineSequence().take(MAX_CDX_LINES)) {
            if (line.isBlank()) continue
            val item = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val timestampText = item.optString("timestamp").trim()
            if (timestampText.length !in 4..14 || !timestampText.all(Char::isDigit)) continue
            val canonicalTimestamp = timestampText.padEnd(14, '0')
            val timestamp = runCatching { formatter.parse(canonicalTimestamp)?.time }.getOrNull() ?: continue
            if (timestamp !in earliest..now) continue
            val status = item.optString("status").toIntOrNull()
            val mime = item.optString("mime").substringBefore(';').trim().lowercase(Locale.ROOT)
            if (status != 200 || mime !in setOf("text/html", "application/xhtml+xml")) continue
            val archivedOriginal = item.optString("url").trim()
            if (!sameOriginalUrl(archivedOriginal, originalUrl)) continue
            // The captured URL is a suffix inside the archive replay path. Encode its query
            // separators there instead of accidentally sending them as query parameters to arquivo.pt.
            val replayTarget = archivedOriginal
                .replace("?", "%3F")
                .replace("&", "%26")
                .replace("#", "%23")
                .replace(" ", "%20")
            val snapshotUrl = "https://$ARCHIVE_HOST/wayback/$canonicalTimestamp/$replayTarget"
            if (!isValidArchiveSnapshotUrl(snapshotUrl)) continue
            captures += MementoCapture(
                rawTimestamp = canonicalTimestamp,
                timestamp = timestamp,
                snapshotUrl = snapshotUrl,
                originalUrl = target.toString()
            )
        }
        return captures.distinctBy { it.rawTimestamp to it.snapshotUrl }.sortedBy { it.timestamp }
    }

    private fun isValidArchiveSnapshotUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) ||
            normalizeHost(uri.host.orEmpty()) != ARCHIVE_HOST ||
            uri.userInfo != null
        ) return false
        val path = uri.rawPath.orEmpty()
        val prefix = "/wayback/"
        if (!path.startsWith(prefix)) return false
        val rest = path.removePrefix(prefix)
        val timestamp = rest.take(14)
        return timestamp.length == 14 &&
            timestamp.all(Char::isDigit) &&
            rest.drop(14).startsWith("/https://")
    }

    private data class CdxCaptureResult(
        val captures: List<MementoCapture> = emptyList(),
        val available: Boolean = false,
        val requestFailed: Boolean = false,
        val diagnosticCodes: Set<String> = emptySet()
    )

    internal fun buildTimeMapUrl(originalUrl: String): String? {
        val uri = parseHttpUri(originalUrl) ?: return null
        if (!uri.scheme.equals("https", true) || isArchiveOrSearchHost(uri.host.orEmpty())) return null
        // The target is a path suffix in the Memento API. Encode characters that would otherwise
        // be interpreted as the outer request's query/fragment, but retain URL path separators.
        val safeTarget = originalUrl
            .replace("?", "%3F")
            .replace("&", "%26")
            .replace("#", "%23")
            .replace(" ", "%20")
        return "https://$ARCHIVE_HOST/wayback/timemap/link/$safeTarget"
    }

    internal fun parseTimeMap(
        body: String,
        originalUrl: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS
    ): List<MementoCapture> {
        if (body.isBlank()) return emptyList()
        val target = parseHttpUri(originalUrl) ?: return emptyList()
        val earliest = archiveHistoryWindowStartTimestampUtc(now, lookbackDays)
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val mementoLink = Regex(
            """<([^>]+)>\s*;\s*rel=["']memento["'](?:\s*;\s*datetime=["']([^"']+)["'])?""",
            RegexOption.IGNORE_CASE
        )
        val output = mutableListOf<MementoCapture>()
        for (match in mementoLink.findAll(body)) {
            val rawLink = match.groupValues[1].trim()
            val secureLink = rawLink.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            val uri = parseHttpUri(secureLink) ?: continue
            if (!uri.scheme.equals("https", true) ||
                !uri.host.equals(ARCHIVE_HOST, ignoreCase = true) ||
                uri.userInfo != null
            ) continue
            val rawTimestamp = Regex("""^/wayback/(\d{14})(?:id_|mp_)?/""")
                .find(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1) ?: continue
            val timestamp = runCatching { formatter.parse(rawTimestamp)?.time }.getOrNull() ?: continue
            if (timestamp !in earliest..now) continue

            val targetInLink = Regex("""^https://arquivo\.pt/wayback/(\d{14})(?:id_|mp_)?/(https://.+)$""")
                .find(secureLink)?.groupValues?.getOrNull(2) ?: continue
            if (!sameOriginalUrl(targetInLink, originalUrl)) continue

            // Build the replay URL from the validated canonical target rather than trusting
            // whether TimeMap emitted its nested query raw or encoded. Otherwise ?sku=... can
            // become a query on arquivo.pt itself instead of part of the archived page path.
            val replayTarget = originalUrl
                .replace("?", "%3F")
                .replace("&", "%26")
                .replace("#", "%23")
                .replace(" ", "%20")
            val snapshotUrl = "https://$ARCHIVE_HOST/wayback/$rawTimestamp/$replayTarget"
            output += MementoCapture(rawTimestamp, timestamp, snapshotUrl, target.toString())
        }
        return output.distinctBy { it.rawTimestamp to it.snapshotUrl }.sortedBy { it.timestamp }
    }

    internal fun sampleQuarterlyCaptures(
        captures: List<MementoCapture>,
        maxSnapshots: Int
    ): List<MementoCapture> = sampleByQuarter(
        items = captures,
        max = maxSnapshots,
        timestampOf = { it.timestamp },
        keyOf = { it.rawTimestamp to it.snapshotUrl }
    )

    private fun sameOriginalUrl(first: String, second: String): Boolean {
        val a = parseHttpUri(first) ?: return false
        val b = parseHttpUri(second) ?: return false
        return a.scheme.equals(b.scheme, true) &&
            normalizeHost(a.host.orEmpty()) == normalizeHost(b.host.orEmpty()) &&
            a.rawPath.orEmpty().trimEnd('/').ifBlank { "/" } ==
                b.rawPath.orEmpty().trimEnd('/').ifBlank { "/" } &&
            a.rawQuery.orEmpty() == b.rawQuery.orEmpty()
    }

    private fun parseHttpUri(raw: String): URI? = runCatching {
        URI(raw.trim()).takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null }
    }.getOrNull()

    private fun isArchiveOrSearchHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        return normalized == ARCHIVE_HOST ||
            normalized == "web.archive.org" ||
            normalized == "archive.org" ||
            normalized == "google.com" ||
            normalized.endsWith(".google.com") ||
            normalized == "google.es" ||
            normalized.endsWith(".google.es") ||
            normalized == "bing.com" ||
            normalized.endsWith(".bing.com")
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.ROOT).removePrefix("www.")

    private fun readBodyLimited(body: ResponseBody?, maxBytes: Int): String? {
        if (body == null) return null
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    internal data class MementoCapture(
        val rawTimestamp: String,
        val timestamp: Long,
        val snapshotUrl: String,
        val originalUrl: String
    )

    private companion object {
        const val ARCHIVE_HOST = "arquivo.pt"
        const val TIMEMAP_TIMEOUT_SECONDS = 5L
        const val MIN_REQUEST_INTERVAL_MS = 2_000L
        const val RATE_LIMIT_COOLDOWN_MS = 15L * 60L * 1000L
        const val KEY_LAST_REQUEST = "last_memento_request"
        const val KEY_COOLDOWN_UNTIL = "memento_cooldown_until"
        const val SNAPSHOT_TIMEOUT_SECONDS = 4L
        const val MAX_TIMEMAP_BYTES = 512 * 1024
        const val MAX_CDX_LINES = 100
        const val MAX_CDX_FALLBACK_SNAPSHOTS = 3
        const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "MinimoPriceHistory/1.0 (Memento historical lookup)"
    }
}
