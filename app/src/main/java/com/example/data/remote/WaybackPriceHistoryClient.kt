package com.example.data.remote

import android.content.Context
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
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.text.Normalizer
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
    val snapshotUrl: String
)

data class WaybackBackfillResult(
    val quotes: List<ArchivedPriceQuote> = emptyList(),
    val capturesFound: Int = 0,
    val capturesChecked: Int = 0,
    val skippedRecently: Boolean = false,
    val available: Boolean = false,
    /** True when the archive request or every snapshot download failed; distinct from no captures. */
    val requestFailed: Boolean = false,
    /** Sanitized stage codes for actionable UI diagnostics; never contains raw URLs or exception text. */
    val diagnosticCodes: Set<String> = emptySet()
)

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
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean("enabled", true)) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("WAYBACK_DISABLED"))
        }
        if (!isEligibleDirectUrl(originalUrl)) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("DIRECT_URL_INVALID"))
        }
        if (expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("PRODUCT_TITLE_EMPTY"))
        }
        val attempt = attemptKey("url-cdx-batch-v3|$originalUrl|$expectedTitle")
        serializedArchiveLookup {
            if (activeRateLimitUntil(System.currentTimeMillis()) != null) {
                return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                )
            }
            if (wasAttemptedRecently(attempt, System.currentTimeMillis())) {
                val previousAttemptFailed = preferences.getBoolean("${attempt}_failed", false)
                return@serializedArchiveLookup WaybackBackfillResult(
                    skippedRecently = true,
                    requestFailed = previousAttemptFailed,
                    diagnosticCodes = setOf(
                        if (previousAttemptFailed) "CACHE_SKIPPED_AFTER_FAILURE" else "CACHE_SKIPPED"
                    )
                )
            }

            // One exact CDX request returns many timestamps at once. Prefer the clean URL variant
            // without tracking/session parameters; take two distant captures for a useful graph.
            val directUrl = buildDirectUrlCandidates(originalUrl).lastOrNull() ?: originalUrl
            val cdxUrl = buildCdxUrl(directUrl, now, lookbackDays)
                ?: return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("DIRECT_URL_INVALID")
                )
            queryCdxAndFetchPrices(
                cdxUrl = cdxUrl,
                expectedTitle = expectedTitle,
                cacheKey = attempt,
                now = now,
                maxSnapshots = MAX_DIRECT_SNAPSHOTS_PER_URL,
                cdxTimeoutSeconds = DIRECT_CDX_TIMEOUT_SECONDS,
                cdxStage = "CDX_EXACT",
                snapshotTimeoutSeconds = DIRECT_CDX_SNAPSHOT_TIMEOUT_SECONDS
            )
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
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean("enabled", true)) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("WAYBACK_DISABLED"))
        }
        val normalizedDomain = normalizeMerchantDomain(domain)
            ?: return@withContext WaybackBackfillResult(diagnosticCodes = setOf("DOMAIN_UNRESOLVED"))
        if (expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult(diagnosticCodes = setOf("PRODUCT_TITLE_EMPTY"))
        }
        val attempt = attemptKey("domain-cdx-batch-v3|$normalizedDomain|$expectedTitle")
        serializedArchiveLookup {
            if (activeRateLimitUntil(System.currentTimeMillis()) != null) {
                return@serializedArchiveLookup WaybackBackfillResult(
                    requestFailed = true,
                    diagnosticCodes = setOf("WAYBACK_RATE_LIMITED")
                )
            }
            if (wasAttemptedRecently(attempt, System.currentTimeMillis())) {
                val previousAttemptFailed = preferences.getBoolean("${attempt}_failed", false)
                return@serializedArchiveLookup WaybackBackfillResult(
                    skippedRecently = true,
                    requestFailed = previousAttemptFailed,
                    diagnosticCodes = setOf(
                        if (previousAttemptFailed) "CACHE_SKIPPED_AFTER_FAILURE" else "CACHE_SKIPPED"
                    )
                )
            }

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
                return@serializedArchiveLookup WaybackBackfillResult(
                    diagnosticCodes = setOf("DOMAIN_CDX_CANDIDATES_EMPTY")
                )
            }

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
                    maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
                    cdxTimeoutSeconds = CDX_FALLBACK_TIMEOUT_SECONDS,
                    cdxStage = "CDX_DOMAIN",
                    snapshotTimeoutSeconds = CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS
                )
                lastResult = result
                if (result.quotes.isNotEmpty()) {
                    return@serializedArchiveLookup result.copy(diagnosticCodes = emptySet())
                }
                if (result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ||
                    result.requestFailed ||
                    result.capturesFound > 0 ||
                    !result.diagnosticCodes.contains("CDX_NO_CAPTURES")
                ) {
                    return@serializedArchiveLookup result
                }
                // A slow CDX domain index should not end the whole lookup. Fall back to one
                // product-specific URL probe via Availability API; this is narrower than another
                // host-wide scan and still validates the archived title and EUR price.
                if (result.requestFailed &&
                    result.diagnosticCodes.contains("CDX_DOMAIN_TIMEOUT") &&
                    !result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
                ) {
                    val candidateUrls = buildAvailabilityCandidateUrls(normalizedDomain, expectedTitle)
                    if (candidateUrls.isNotEmpty()) {
                        val fallback = queryAvailabilityAndFetchPrices(
                            candidateUrls = candidateUrls,
                            expectedTitle = expectedTitle,
                            cacheKey = attempt,
                            now = now,
                            lookbackDays = lookbackDays,
                            maxSnapshots = 1,
                            maxCandidates = 1,
                            stage = "DOMAIN_AVAILABILITY_FALLBACK",
                            probeOlderCapture = false
                        )
                        if (fallback.quotes.isNotEmpty()) {
                            return@serializedArchiveLookup fallback.copy(diagnosticCodes = emptySet())
                        }
                        return@serializedArchiveLookup fallback.copy(
                            requestFailed = true,
                            diagnosticCodes = fallback.diagnosticCodes + "CDX_DOMAIN_TIMEOUT"
                        )
                    }
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
    private suspend fun queryAvailabilityAndFetchPrices(
        candidateUrls: List<String>,
        expectedTitle: String,
        cacheKey: String,
        now: Long,
        lookbackDays: Int,
        maxSnapshots: Int,
        maxCandidates: Int,
        stage: String,
        probeOlderCapture: Boolean = true
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

        // Cascade across URL variants efficiently: probe the newest capture for each URL;
        // once one candidate works, query an older target date for that same URL to build history.
        // A 429 or network failure stops immediately, and no more than three availability calls run.
        val dates = availabilityTargetDates(now, lookbackDays, 2)
        val probes = mutableListOf<AvailabilityProbe>()
        for (originalUrl in urls) {
            val recentProbe = availabilitySemaphore.withPermit {
                queryAvailability(originalUrl, dates.first(), stage)
            }
            probes += recentProbe
            if (recentProbe.diagnosticCode == "WAYBACK_RATE_LIMITED") break
            if (recentProbe.requestFailed) {
                if (recentProbe.diagnosticCode == "${stage}_HTTP_404") continue
                break
            }
            if (recentProbe.capture != null) {
                if (probeOlderCapture) {
                    val olderProbe = availabilitySemaphore.withPermit {
                        queryAvailability(originalUrl, dates.last(), stage)
                    }
                    probes += olderProbe
                }
                break
            }
        }
        val captures = probes.mapNotNull { it.capture }
            .filter { it.timestamp <= now && it.timestamp >= (now - lookbackDays.coerceAtLeast(1).toLong() * DAY_MS).coerceAtLeast(0L) }
            .distinctBy { it.rawTimestamp to it.originalUrl }
            .sortedBy { it.timestamp }
        val successfulProbes = probes.count { !it.requestFailed }
        val probeDiagnostics = probes.mapNotNull { it.diagnosticCode }.toSet()

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
            val diagnostics = linkedSetOf<String>().apply {
                addAll(probeDiagnostics)
                if (isEmpty()) add("${stage}_NO_CAPTURES")
                else if (!allFailed) add("${stage}_NO_CAPTURES")
            }
            rememberAttempt(
                cacheKey,
                now,
                hasQuotes = false,
                failed = allFailed,
                rateLimited = probeDiagnostics.contains("WAYBACK_RATE_LIMITED")
            )
            return WaybackBackfillResult(
                available = successfulProbes > 0,
                requestFailed = allFailed,
                diagnosticCodes = diagnostics
            )
        }

        val sampled = sampleCaptures(captures, maxSnapshots)
        val fetched = mutableListOf<SnapshotFetch>()
        for (capture in sampled) {
            val snapshot = fetchAndVerifySnapshot(
                capture = capture,
                expectedTitle = expectedTitle,
                snapshotTimeoutSeconds = SNAPSHOT_CALL_TIMEOUT_SECONDS
            )
            fetched += snapshot
            if (snapshot.diagnosticCode == "WAYBACK_RATE_LIMITED") break
        }

        val quotes = fetched.mapNotNull { it.quote }
            .sortedBy { it.observedAt }
            .distinctBy { Triple(it.originalUrl, it.observedAt, it.price) }
        val diagnostics = linkedSetOf<String>().apply {
            addAll(probeDiagnostics)
            fetched.mapNotNullTo(this) { it.diagnosticCode }
            if (quotes.isEmpty() && fetched.any { it.fetched }) add("NO_VERIFIED_EUR_PRICE")
            else if (quotes.isEmpty() && isEmpty()) add("${stage}_NO_VERIFIED_PRICE")
        }
        val anySnapshotFailed = fetched.any { !it.fetched }
        val allSnapshotsFailed = fetched.isNotEmpty() && fetched.none { it.fetched }
        val failed = quotes.isEmpty() && (successfulProbes == 0 || allSnapshotsFailed)
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
            capturesFound = captures.size,
            capturesChecked = fetched.count { it.fetched },
            available = successfulProbes > 0,
            requestFailed = failed || (quotes.isEmpty() && anySnapshotFailed && successfulProbes == 0),
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
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
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

    private suspend fun fetchAndVerifySnapshot(
        capture: WaybackCapture,
        expectedTitle: String,
        snapshotTimeoutSeconds: Long
    ): SnapshotFetch {
        val snapshotUrl = buildSnapshotUrl(capture)
            ?: return SnapshotFetch(false, null, "SNAPSHOT_URL_INVALID")
        val request = Request.Builder()
            .url(snapshotUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val snapshotHttp = http.newBuilder()
            .readTimeout(snapshotTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(snapshotTimeoutSeconds, TimeUnit.SECONDS)
            .build()

        return availabilitySemaphore.withPermit {
            val gate = awaitArchiveRequestTurn()
            if (gate != null) {
                return@withPermit SnapshotFetch(false, null, gate)
            }
            try {
                snapshotHttp.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        activateRateLimit(response.header("Retry-After"), System.currentTimeMillis())
                        SnapshotFetch(false, null, "WAYBACK_RATE_LIMITED")
                    } else if (!response.isSuccessful) {
                        SnapshotFetch(false, null, "SNAPSHOT_HTTP_${response.code}")
                    } else {
                        val html = readBodyLimited(response.body, MAX_SNAPSHOT_BODY_BYTES)
                        if (html == null) {
                            SnapshotFetch(false, null, "SNAPSHOT_BODY_EMPTY_OR_LIMIT")
                        } else {
                            val quote = parseArchivedPrice(
                                html = html,
                                expectedTitle = expectedTitle,
                                originalUrl = capture.originalUrl,
                                snapshotUrl = snapshotUrl,
                                observedAt = capture.timestamp
                            )
                            SnapshotFetch(
                                fetched = true,
                                quote = quote,
                                diagnosticCode = if (quote == null) {
                                    diagnoseArchivedPrice(html, expectedTitle)
                                } else null
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SnapshotFetch(false, null, classifyNetworkFailure("SNAPSHOT", failure))
            }
        }
    }

    private fun diagnoseArchivedPrice(html: String, expectedTitle: String): String {
        val jsonLd = extractJsonLdProductOffers(html)
        val pageTitle = readMeta(html, setOf("og:title"))
            ?: readMeta(html, setOf("twitter:title"))
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        val metaPrice = listOf(
            "product:price:amount", "og:price:amount", "price", "product:price"
        ).firstNotNullOfOrNull { key -> readMeta(html, setOf(key))?.let(::parsePositivePrice) }
        val metaCurrency = listOf(
            "product:price:currency", "og:price:currency", "pricecurrency"
        ).firstNotNullOfOrNull { key -> readMeta(html, setOf(key)) }?.uppercase(Locale.ROOT)
        if (pageTitle.isNullOrBlank() && jsonLd.isEmpty()) return "ARCHIVE_TITLE_MISSING"
        if (metaPrice == null && jsonLd.isEmpty()) return "ARCHIVE_PRICE_METADATA_MISSING"
        val candidates = jsonLd + if (!pageTitle.isNullOrBlank() && metaPrice != null && !metaCurrency.isNullOrBlank()) {
            listOf(Triple(pageTitle, metaPrice, metaCurrency))
        } else emptyList()
        if (candidates.none { it.third.equals("EUR", ignoreCase = true) }) {
            return "ARCHIVE_EUR_PRICE_MISSING"
        }
        if (candidates.none { isMatchingProductTitle(expectedTitle, it.first) }) {
            return "ARCHIVE_PRODUCT_TITLE_MISMATCH"
        }
        return "ARCHIVE_PRICE_AMBIGUOUS_OR_INVALID"
    }

    internal fun buildDirectUrlCandidates(originalUrl: String): List<String> {
        if (!isEligibleDirectUrl(originalUrl)) return emptyList()
        val uri = runCatching { URI(originalUrl) }.getOrNull() ?: return listOf(originalUrl)
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return listOf(originalUrl)

        val candidates = linkedSetOf(originalUrl)
        val trackingNames = setOf(
            "gclid", "dclid", "fbclid", "msclkid", "mc_eid", "mc_cid", "igshid",
            "gad_source", "gbraid", "wbraid", "ref", "ref_", "tag", "linkcode",
            "campid", "scid", "affid", "affiliate", "sourceid", "srsltid"
        )
        val queryParts = uri.rawQuery?.split('&').orEmpty()
        val cleanedParts = queryParts.filterNot { part ->
            val rawName = part.substringBefore('=')
            val name = runCatching {
                java.net.URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(rawName).lowercase(Locale.ROOT)
            name.startsWith("utm_") || name in trackingNames
        }
        val trackingRemoved = cleanedParts.size != queryParts.size
        val base = buildString {
            append(uri.scheme.lowercase(Locale.ROOT))
            append("://")
            append(uri.rawAuthority)
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
        }
        if (cleanedParts.isNotEmpty()) {
            candidates += "$base?${cleanedParts.joinToString("&")}"
        } else if (trackingRemoved) {
            candidates += base
        }

        // Queryless retailer pages are often archived under the opposite slash convention.
        // Try that only when no tracking-free query candidate was available.
        if (candidates.size == 1 && uri.rawPath.isNullOrBlank().not()) {
            val path = uri.rawPath!!
            val alternatePath = if (path.endsWith("/")) path.trimEnd('/').ifBlank { "/" } else "$path/"
            candidates += buildString {
                append(uri.scheme.lowercase(Locale.ROOT))
                append("://")
                append(uri.rawAuthority)
                append(alternatePath)
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append("?").append(it) }
            }
        }
        return candidates.filter(::isEligibleDirectUrl).take(2)
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
        if (normalizedDomain == "apple.com") {
            slugs.filter { it.startsWith("iphone-") || it.startsWith("ipad-") || it.startsWith("macbook-") }
                .forEach { paths += "/es/$it/" }
        }
        if (normalizedDomain == "samsung.com") {
            slugs.filter { it.startsWith("galaxy-") }
                .forEach { paths += "/es/smartphones/galaxy-s/$it/" }
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
        return (listOfNotNull(storeSearchUrl) + guessedProductUrls)
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

    private fun activateRateLimit(retryAfter: String?, now: Long): Long {
        val until = maxOf(
            preferences.getLong(KEY_RATE_LIMIT_UNTIL, 0L),
            computeRateLimitUntil(retryAfter, now)
        )
        preferences.edit().putLong(KEY_RATE_LIMIT_UNTIL, until).apply()
        return until
    }

    private fun activeRateLimitUntil(now: Long): Long? =
        preferences.getLong(KEY_RATE_LIMIT_UNTIL, 0L).takeIf { it > now }

    internal fun clearRateLimitForTests() {
        preferences.edit().remove(KEY_RATE_LIMIT_UNTIL).apply()
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
        maxSnapshots: Int,
        cdxTimeoutSeconds: Long,
        cdxStage: String,
        snapshotTimeoutSeconds: Long = SNAPSHOT_CALL_TIMEOUT_SECONDS
    ): WaybackBackfillResult {
        val cdxOutcome = availabilitySemaphore.withPermit {
            val gate = awaitArchiveRequestTurn()
            if (gate != null) {
                CdxIndexOutcome(requestFailed = true, diagnosticCodes = setOf(gate))
            } else {
                queryCdxIndexOnly(cdxUrl, cdxTimeoutSeconds, cdxStage)
            }
        }
        if (cdxOutcome.requestFailed) {
            val rateLimited = cdxOutcome.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true, rateLimited = rateLimited)
            return WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = cdxOutcome.diagnosticCodes
            )
        }
        val captures = cdxOutcome.captures

        val sampled = sampleCaptures(captures, maxSnapshots)
        val fetched = mutableListOf<SnapshotFetch>()
        for (capture in sampled) {
            val snapshot = fetchAndVerifySnapshot(
                capture = capture,
                expectedTitle = expectedTitle,
                snapshotTimeoutSeconds = snapshotTimeoutSeconds
            )
            fetched += snapshot
            if (snapshot.diagnosticCode == "WAYBACK_RATE_LIMITED") break
        }

        // Cache useful histories for 30 days, but allow an empty result to be retried after 6 hours.
        val quotes = fetched.mapNotNull { it.quote }
            .sortedBy { it.observedAt }
            .distinctBy { Triple(it.originalUrl, it.observedAt, it.price) }
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
            capturesChecked = fetched.count { it.fetched },
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
        if (storeName.isBlank()) return null
        val explicitHost = Regex(
            """(?i)(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:es|com|net|org|eu|de|fr|it|nl|co\.uk))(?:[:/]|$)"""
        ).find(storeName.trim())?.groupValues?.getOrNull(1)
        if (explicitHost != null) return normalizeMerchantDomain(explicitHost)
        normalizeMerchantDomain(knownStoreDomain)?.let { return it }
        Store.findKnownByName(storeName, sourceUrl)?.let {
            normalizeMerchantDomain(it.domain)?.let { domain -> return domain }
        }
        if (sourceUrl.isNotBlank()) {
            runCatching { URI(sourceUrl).host }.getOrNull()
                ?.let(::normalizeMerchantDomain)?.let { return it }
        }
        val normalized = normalizeTitle(storeName)
        val aliases = listOf(
            "pccomponentes" to "pccomponentes.com",
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
        val raw = value.trim().removePrefix("https://").removePrefix("http://")
        val uri = runCatching { URI("https://$raw") }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        if (!host.matches(Regex("""(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}(?:\.[a-z]{2,})?"""))) {
            return null
        }
        if (host == "google.com" || host.endsWith(".google.com") || host.endsWith(".google.es") ||
            host == "bing.com" || host.endsWith(".bing.com") || host == "web.archive.org" ||
            host == "archive.org"
        ) return null
        return host
    }

    private fun buildCdxUrl(originalUrl: String, now: Long, lookbackDays: Int): HttpUrl? {
        val start = (now - lookbackDays.coerceAtLeast(1).toLong() * DAY_MS).coerceAtLeast(0L)
        val from = formatUtc(start, "yyyyMMdd")
        val to = formatUtc(now, "yyyyMMdd")
        return HttpUrl.Builder()
            .scheme("https")
            .host("web.archive.org")
            .addPathSegments("cdx/search/cdx")
            .addQueryParameter("url", originalUrl)
            .addQueryParameter("matchType", "exact")
            .addQueryParameter("output", "json")
            .addQueryParameter("fl", "timestamp,original,statuscode,mimetype")
            .addQueryParameter("filter", "statuscode:200")
            .addQueryParameter("filter", "mimetype:text/html")
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            .addQueryParameter("collapse", "timestamp:6")
            .addQueryParameter("limit", "100")
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
        val start = (now - lookbackDays.coerceAtLeast(1).toLong() * DAY_MS).coerceAtLeast(0L)
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
            .addQueryParameter("from", formatUtc((now - DOMAIN_LOOKBACK_DAYS.toLong() * DAY_MS).coerceAtLeast(start), "yyyyMMdd"))
            .addQueryParameter("to", to)
            // A small result window can be exhausted by unrelated captures before a
            // matching product URL appears. The portable token filter stays bounded; return
            // a few more index rows while keeping snapshot downloads capped separately.
            .addQueryParameter("limit", MAX_DOMAIN_INDEX_ROWS.toString())
            .build()
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
        if (html.isBlank() || expectedTitle.isBlank() || observedAt <= 0L) return null
        val jsonLdCandidates = extractJsonLdProductOffers(html)

        val pageTitle = readMeta(html, setOf("og:title"))
            ?: readMeta(html, setOf("twitter:title"))
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)

        // Prefer the page's explicit product price metadata in a stable priority order.
        // readMeta(Set) follows document order, so each key is queried separately here.
        val metaPrice = listOf(
            "product:price:amount", "og:price:amount", "price", "product:price"
        ).firstNotNullOfOrNull { key ->
            readMeta(html, setOf(key))?.let(::parsePositivePrice)
        }
        val metaCurrency = listOf(
            "product:price:currency", "og:price:currency", "pricecurrency"
        ).firstNotNullOfOrNull { key -> readMeta(html, setOf(key)) }
            ?.uppercase(Locale.ROOT)

        fun isValidCandidate(candidate: Triple<String, Double, String>): Boolean =
            candidate.third.equals("EUR", ignoreCase = true) &&
                candidate.second in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE &&
                isMatchingProductTitle(expectedTitle, candidate.first)

        val explicitMeta = if (!pageTitle.isNullOrBlank() && metaPrice != null && !metaCurrency.isNullOrBlank()) {
            Triple(pageTitle, metaPrice, metaCurrency).takeIf(::isValidCandidate)
        } else null

        // A retailer page can expose multiple valid EUR offers for the exact same product.
        // Product title, numeric variants, and condition are validated before this point, so the
        // lowest matching offer is a deterministic historical market price—not JSON property order.
        // Explicit page-level product price metadata remains authoritative when valid.
        val matchingJsonLd = jsonLdCandidates.filter(::isValidCandidate)
        val matching = explicitMeta ?: matchingJsonLd.minWithOrNull(
            compareBy<Triple<String, Double, String>> { it.second }
                .thenBy { normalizeTitle(it.first) }
        ) ?: return null

        return ArchivedPriceQuote(
            observedAt = observedAt,
            price = matching.second,
            currency = "EUR",
            productTitle = decodeHtml(matching.first).trim(),
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl
        )
    }

    private fun extractJsonLdProductOffers(html: String): List<Triple<String, Double, String>> {
        val scripts = Regex(
            """(?is)<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script\s*>"""
        ).findAll(html).map { it.groupValues[1] }
        val output = mutableListOf<Triple<String, Double, String>>()
        for (script in scripts) {
            val json = try { JSONTokener(decodeHtml(script)).nextValue() } catch (_: Exception) { null }
            visitJsonLd(json, output)
        }
        return output
    }

    private fun visitJsonLd(value: Any?, out: MutableList<Triple<String, Double, String>>) {
        when (value) {
            is JSONObject -> {
                val name = value.optString("name").takeIf { it.isNotBlank() && it != "null" }
                val offers = value.opt("offers")
                if (!name.isNullOrBlank() && offers != null) {
                    collectOfferPrices(offers).forEach { (price, currency) ->
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

    private fun collectOfferPrices(value: Any?): List<Pair<Double, String>> {
        val found = mutableListOf<Pair<Double, String>>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val specification = node.optJSONObject("priceSpecification")
                    val currency = node.optString("priceCurrency")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: specification?.optString("priceCurrency").orEmpty()
                    val rawPrice = node.opt("price") ?: specification?.opt("price")
                    val price = parsePositivePrice(rawPrice?.toString())
                    if (price != null && currency.isNotBlank()) found += price to currency.uppercase(Locale.ROOT)
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in setOf("price", "priceCurrency", "priceSpecification")) visit(node.opt(key))
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return found
    }

    private fun readMeta(html: String, names: Set<String>): String? {
        val normalizedNames = names.map { it.lowercase(Locale.ROOT) }.toSet()
        for (tag in Regex("""(?is)<meta\b[^>]*>""").findAll(html)) {
            val attributes = Regex("""(?is)([a-z_:.-]+)\s*=\s*["'](.*?)["']""")
                .findAll(tag.value)
                .associate { it.groupValues[1].lowercase(Locale.ROOT) to decodeHtml(it.groupValues[2]).trim() }
            val key = (attributes["property"] ?: attributes["name"] ?: attributes["itemprop"])
                ?.lowercase(Locale.ROOT)
            if (key in normalizedNames) attributes["content"]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun isMatchingProductTitle(expected: String, archived: String): Boolean {
        val e = normalizeTitle(expected)
        val a = normalizeTitle(archived)
        if (e.isBlank() || a.isBlank()) return false
        if (productConditionKey(e) != productConditionKey(a)) return false

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

        // Variant-defining numeric/model tokens must not conflict or silently disappear.
        val expectedVariants = requiredVariantTokens(canonicalExpected)
        val archivedVariants = requiredVariantTokens(canonicalArchived)
        if (!sameIdentity && !archivedVariants.containsAll(expectedVariants)) return false
        if (sameIdentity) return true

        val expectedTokens = meaningfulTokens(canonicalExpected)
        val archivedTokens = meaningfulTokens(canonicalArchived)
        if (expectedTokens.isEmpty() || archivedTokens.isEmpty()) return false
        val overlap = expectedTokens.intersect(archivedTokens)
        return overlap.size >= minOf(2, expectedTokens.size) &&
            overlap.size.toDouble() / expectedTokens.size >= 0.72
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
        // These regional prefixes are only interchangeable for Samsung model identifiers.
        if (!Regex("""\bsamsung\b""").containsMatchIn(normalizedTitle)) return normalizedTitle
        return normalizedTitle
            // Normalize only known Samsung TV regional model prefixes in compact alphanumeric
            // identifiers (e.g. TQ48S95HAEXXC vs QE48S95HAEXXC); retain size and suffix.
            .replace(Regex("""\b(?:tq|qe)(?=\d{2}[a-z0-9]{5,})"""), "")
            .replace(Regex("""\b(\d{2,})\s+inches?\b"""), "$1")
            .replace(Regex("""\b(?:2k|4k|8k|uhd|fhd|fullhd)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            // Samsung catalogue titles sometimes include an explicit region code in one title
            // but omit it in the other; identity matching below still guards diagonal/model.
    }

    /**
     * Variant-defining tokens that must survive a fuzzy title match. Mixed alphanumeric model
     * codes and standalone numbers with at least two digits are treated as distinguishing values.
     * Generic words still use the looser overlap rule above.
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
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)

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
        if (uri.scheme != "https" || host.isBlank() || uri.path.isNullOrBlank() || uri.path == "/") return false
        if (host == "web.archive.org" || host == "archive.org" ||
            host.startsWith("google.") || host.endsWith(".google.com") || host.endsWith(".google.es") ||
            host == "bing.com" || host.endsWith(".bing.com")
        ) return false
        return true
    }

    /** Read bounded response bodies so unexpectedly large archived pages cannot spike app memory. */
    internal fun classifyNetworkFailure(stage: String, failure: Exception): String = when (failure) {
        is SocketTimeoutException, is InterruptedIOException -> "${stage}_TIMEOUT"
        is UnknownHostException -> "${stage}_DNS"
        is IOException -> "${stage}_NETWORK"
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
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sampleCaptures(captures: List<WaybackCapture>, max: Int): List<WaybackCapture> {
        if (captures.size <= max) return captures
        if (max <= 1) return listOf(captures.first())
        return (0 until max).map { index ->
            captures[(index.toDouble() * (captures.size - 1) / (max - 1)).toInt()]
        }.distinctBy { it.rawTimestamp to it.originalUrl }
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
        val diagnosticCode: String? = null
    )

    internal data class WaybackCapture(
        val rawTimestamp: String,
        val timestamp: Long,
        val originalUrl: String
    )

    companion object {
        const val DEFAULT_LOOKBACK_DAYS = 1600
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
        private const val MAX_CDX_FALLBACK_SNAPSHOTS = 2
        private const val MAX_DOMAIN_CDX_HOST_QUERIES = 2
        private const val CDX_FALLBACK_TIMEOUT_SECONDS = 3L
        private const val CDX_FALLBACK_SNAPSHOT_TIMEOUT_SECONDS = 2L
        private const val DIRECT_CDX_SNAPSHOT_TIMEOUT_SECONDS = 2L
        private const val MAX_AVAILABILITY_BODY_BYTES = 64 * 1024
        private const val CDX_RETRY_DELAY_MS = 500L
        private const val CDX_RETRY_JITTER_MS = 350L
        private val RETRYABLE_CDX_HTTP_CODES = setOf(500, 502, 503, 504)
        private const val DIRECT_CDX_TIMEOUT_SECONDS = 5L
        // Keep the broad domain-index request aligned with the UI's strict four-second budget.
        private const val DOMAIN_CDX_TIMEOUT_SECONDS = 4L
        private const val DOMAIN_EXACT_CDX_TIMEOUT_SECONDS = 2L
        private const val MAX_DOMAIN_PRODUCT_URL_CANDIDATES = 2
        private const val MAX_DIRECT_SNAPSHOTS_PER_URL = 2
        private const val MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP = 2
        private const val SNAPSHOT_CALL_TIMEOUT_SECONDS = 3L
        private const val MAX_DOMAIN_INDEX_ROWS = 20
        private const val DOMAIN_LOOKBACK_DAYS = 730
        private const val MAX_CONCURRENT_SNAPSHOT_REQUESTS = 4
        const val RETRY_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1000L
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60L * 1000L
        private const val MIN_AVAILABILITY_REQUEST_INTERVAL_MS = 1_500L
        private const val KEY_LAST_AVAILABILITY_REQUEST_AT = "wayback_last_availability_request_at"
        private const val MIN_RATE_LIMIT_COOLDOWN_MS = 60L * 1000L
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60L * 60L * 1000L
        private const val MAX_RATE_LIMIT_COOLDOWN_MS = 6L * 60L * 60L * 1000L
        private const val KEY_RATE_LIMIT_UNTIL = "wayback_rate_limit_until"
        private val GLOBAL_AVAILABILITY_SEMAPHORE = Semaphore(1)
        private val GLOBAL_BACKFILL_MUTEX = Mutex()
        private const val MAX_CDX_BODY_BYTES = 256 * 1024
        private const val MAX_SNAPSHOT_BODY_BYTES = 2 * 1024 * 1024
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_HISTORICAL_PRICE = 0.50
        const val MAX_HISTORICAL_PRICE = 10_000.0
        private const val USER_AGENT = "MinimoPriceHistory/1.0 (historical lookup from public web archive)"
    }
}
