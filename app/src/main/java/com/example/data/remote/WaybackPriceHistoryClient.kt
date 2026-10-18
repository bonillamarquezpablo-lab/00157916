package com.example.data.remote

import android.content.Context
import com.example.data.model.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val attempt = attemptKey("url|$originalUrl|$expectedTitle")
        if (wasAttemptedRecently(attempt, now)) {
            return@withContext WaybackBackfillResult(skippedRecently = true, diagnosticCodes = setOf("CACHE_SKIPPED"))
        }
        val cdxUrl = buildCdxUrl(originalUrl, now, lookbackDays)
            ?: return@withContext WaybackBackfillResult(diagnosticCodes = setOf("CDX_URL_BUILD_FAILED"))
        queryCdxAndFetchPrices(
            cdxUrl, expectedTitle, attempt, now,
            maxSnapshots = MAX_DIRECT_SNAPSHOTS_PER_URL,
            cdxTimeoutSeconds = DIRECT_CDX_TIMEOUT_SECONDS,
            cdxStage = "CDX_EXACT"
        )
    }

    /**
     * Free fallback for Google Shopping results that expose only an intermediate Google link.
     * It scans archived URL paths on a known merchant domain for product-specific terms, then
     * accepts a point only when the archived page title and EUR metadata match the product.
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
        val cdxUrl = buildDomainCdxUrl(normalizedDomain, expectedTitle, now, lookbackDays)
            ?: return@withContext WaybackBackfillResult(diagnosticCodes = setOf("CDX_QUERY_NOT_BUILDABLE"))
        val attempt = attemptKey("domain|$normalizedDomain|$expectedTitle")
        if (wasAttemptedRecently(attempt, now)) {
            return@withContext WaybackBackfillResult(skippedRecently = true, diagnosticCodes = setOf("CACHE_SKIPPED"))
        }
        queryCdxAndFetchPrices(
            cdxUrl, expectedTitle, attempt, now,
            maxSnapshots = MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP,
            cdxTimeoutSeconds = DOMAIN_CDX_TIMEOUT_SECONDS,
            cdxStage = "CDX_DOMAIN"
        )
    }

    internal fun wasAttemptedRecently(key: String, now: Long): Boolean {
        val lastAttempt = preferences.getLong(key, 0L)
        if (lastAttempt <= 0L || now < lastAttempt) return false
        // A failed HTTP attempt should not block an explicit retry for 30 minutes.
        // Failed requests are retriable immediately; only meaningful/negative completed
        // lookups use a cache interval to avoid repeatedly scanning the same archive.
        val hadQuotes = preferences.getBoolean("${key}_has_quotes", false)
        val failed = preferences.getBoolean("${key}_failed", false)
        if (failed && !hadQuotes) return false
        val retryInterval = if (hadQuotes) RETRY_INTERVAL_MS else NEGATIVE_RETRY_INTERVAL_MS
        return now - lastAttempt < retryInterval
    }

    private fun rememberAttempt(
        key: String,
        now: Long,
        hasQuotes: Boolean,
        failed: Boolean
    ) {
        preferences.edit()
            .putLong(key, now)
            .putBoolean("${key}_has_quotes", hasQuotes)
            .putBoolean("${key}_failed", failed)
            .apply()
    }

    private suspend fun queryCdxAndFetchPrices(
        cdxUrl: HttpUrl,
        expectedTitle: String,
        cacheKey: String,
        now: Long,
        maxSnapshots: Int,
        cdxTimeoutSeconds: Long,
        cdxStage: String
    ): WaybackBackfillResult {
        val cdxHttp = http.newBuilder()
            // OkHttp's read timeout is independent of callTimeout. Raise both together so the
            // domain lookup can actually use its configured budget instead of still failing at 5s.
            .readTimeout(cdxTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(cdxTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(cdxUrl).header("User-Agent", USER_AGENT).get().build()
        val captures = try {
            cdxHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
                    return WaybackBackfillResult(requestFailed = true, diagnosticCodes = setOf("${cdxStage}_HTTP_${response.code}"))
                }
                val body = readBodyLimited(response.body, MAX_CDX_BODY_BYTES)
                if (body == null) {
                    rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
                    return WaybackBackfillResult(requestFailed = true, diagnosticCodes = setOf("${cdxStage}_BODY_EMPTY_OR_LIMIT"))
                }
                if (!isValidCaptureIndexResponse(body)) {
                    rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
                    return WaybackBackfillResult(requestFailed = true, diagnosticCodes = setOf("${cdxStage}_INVALID_RESPONSE"))
                }
                parseCaptureIndex(body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
            return WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = setOf(classifyNetworkFailure(cdxStage, failure))
            )
        }

        val sampled = sampleCaptures(captures, maxSnapshots)
        val semaphore = Semaphore(MAX_CONCURRENT_SNAPSHOT_REQUESTS)
        // Build the bounded snapshot client once per lookup and reuse its connection pool.
        val snapshotHttp = http.newBuilder()
            .callTimeout(SNAPSHOT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val fetched = coroutineScope {
            sampled.map { capture ->
                async {
                    semaphore.withPermit {
                        val snapshotUrl = buildSnapshotUrl(capture)
                            ?: return@withPermit SnapshotFetch(false, null, "SNAPSHOT_URL_INVALID")
                        val snapshotRequest = Request.Builder()
                            .url(snapshotUrl)
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()
                        try {
                            snapshotHttp.newCall(snapshotRequest).execute().use { response ->
                                if (!response.isSuccessful) {
                                    SnapshotFetch(false, null, "SNAPSHOT_HTTP_${response.code}")
                                } else {
                                    val html = readBodyLimited(response.body, MAX_SNAPSHOT_BODY_BYTES)
                                    if (html == null) {
                                        SnapshotFetch(false, null, "SNAPSHOT_BODY_EMPTY_OR_LIMIT")
                                    } else {
                                        SnapshotFetch(
                                            fetched = true,
                                            quote = parseArchivedPrice(
                                                html = html,
                                                expectedTitle = expectedTitle,
                                                originalUrl = capture.originalUrl,
                                                snapshotUrl = snapshotUrl,
                                                observedAt = capture.timestamp
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            SnapshotFetch(
                                fetched = false,
                                quote = null,
                                diagnosticCode = classifyNetworkFailure("SNAPSHOT", failure)
                            )
                        }
                    }
                }
            }.awaitAll()
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
            failed = quotes.isEmpty() && anySnapshotFailed
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
     * Search only URLs whose paths contain distinctive product terms on a known retailer domain.
     * The archive CDX API supports domain matching and URL-field regular-expression filters.
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
            if (modelNumber != null) listOf("(?=.*galaxy)(?=.*edge)(?=.*$modelNumber)") else emptyList()
        } else emptyList()
        // A product SKU/model code is the strongest URL hint, but some retailers use
        // short generic slugs and keep the SKU only in page metadata. Search by either a
        // distinctive model code or two title terms; strict page validators still apply.
        val modelAlternatives = modelTokens.take(2).map { token ->
            "(?=.*${urlTokenPattern(token)})"
        }
        val textAlternatives = if (textTokens.size >= 2) {
            listOf("(?=.*${urlTokenPattern(textTokens[0])})(?=.*${urlTokenPattern(textTokens[1])})")
        } else emptyList()
        val requiredLookaheads = (modelAlternatives + textAlternatives + modelFamilyAlternatives).distinct()
        if (requiredLookaheads.isEmpty()) return null
        val start = (now - lookbackDays.coerceAtLeast(1).toLong() * DAY_MS).coerceAtLeast(0L)
        val from = formatUtc(start, "yyyyMMdd")
        val to = formatUtc(now, "yyyyMMdd")
        val urlPattern = "(?i)(?:" + requiredLookaheads.joinToString("|") + ").*"
        // Keep the CDX response narrow. Broad multi-year domain scans often time out on
        // large retailers; direct URL lookups still use the full configured lookback.
        return HttpUrl.Builder()
            .scheme("https")
            .host("web.archive.org")
            .addPathSegments("cdx/search/cdx")
            .addQueryParameter("url", "$normalizedDomain/")
            .addQueryParameter("matchType", "domain")
            .addQueryParameter("output", "json")
            .addQueryParameter("fl", "timestamp,original,statuscode,mimetype")
            .addQueryParameter("filter", "statuscode:200")
            .addQueryParameter("filter", "mimetype:text/html")
            .addQueryParameter("filter", "original:$urlPattern")
            .addQueryParameter("from", formatUtc((now - DOMAIN_LOOKBACK_DAYS.toLong() * DAY_MS).coerceAtLeast(start), "yyyyMMdd"))
            .addQueryParameter("to", to)
            // Keep multiple timestamps for a URL; history needs multiple captures to build a curve.
            .addQueryParameter("limit", MAX_DOMAIN_INDEX_ROWS.toString())
            .build()
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
            "original", "product", "producto", "neu", "zapatilla", "zapatillas", "calzado",
            "zapato", "zapatos", "sneaker", "sneakers", "shoe", "shoes", "calendario",
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

        // JSON-LD can contain several sellers/offers or both regular and sale prices. Never let
        // the JSON property order decide which price becomes historical evidence.
        val matchingJsonLd = jsonLdCandidates.filter(::isValidCandidate)
        val matching = explicitMeta ?: run {
            val distinctPrices = matchingJsonLd
                .map { it.second to it.third.uppercase(Locale.ROOT) }
                .distinct()
            if (distinctPrices.size != 1) return null
            matchingJsonLd.firstOrNull()
        } ?: return null

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
        private const val DIRECT_CDX_TIMEOUT_SECONDS = 15L
        private const val DOMAIN_CDX_TIMEOUT_SECONDS = 20L
        private const val MAX_DIRECT_SNAPSHOTS_PER_URL = 4
        private const val MAX_DOMAIN_SNAPSHOTS_PER_LOOKUP = 4
        private const val SNAPSHOT_CALL_TIMEOUT_SECONDS = 3L
        private const val MAX_DOMAIN_INDEX_ROWS = 80
        private const val DOMAIN_LOOKBACK_DAYS = 730
        private const val MAX_CONCURRENT_SNAPSHOT_REQUESTS = 4
        const val RETRY_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1000L
        const val FAILURE_RETRY_INTERVAL_MS = 30L * 60L * 1000L
        private const val MAX_CDX_BODY_BYTES = 256 * 1024
        private const val MAX_SNAPSHOT_BODY_BYTES = 2 * 1024 * 1024
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_HISTORICAL_PRICE = 0.50
        const val MAX_HISTORICAL_PRICE = 10_000.0
        private const val USER_AGENT = "MinimoPriceHistory/1.0 (historical lookup from public web archive)"
    }
}
