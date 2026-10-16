package com.example.data.remote

import android.content.Context
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
import java.io.ByteArrayOutputStream
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
    val available: Boolean = false
)

/**
 * Best-effort free historical backfill using Internet Archive snapshots.
 * A capture is accepted only when structured metadata exposes a EUR price and a product title
 * matching the searched item. Archive gaps are expected; this client never invents prices.
 */
class WaybackPriceHistoryClient(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
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
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!isEligibleDirectUrl(originalUrl) || expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult()
        }
        val attempt = attemptKey("archive-v2|url|$originalUrl|$expectedTitle")
        if (!forceRefresh && wasAttemptedRecently(attempt, now)) {
            return@withContext WaybackBackfillResult(skippedRecently = true)
        }
        val cdxUrl = buildCdxUrl(originalUrl, now, lookbackDays)
            ?: return@withContext WaybackBackfillResult()
        queryCdxAndFetchPrices(cdxUrl, expectedTitle, attempt, now)
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
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        forceRefresh: Boolean = false
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        val normalizedDomain = normalizeMerchantDomain(domain)
            ?: return@withContext WaybackBackfillResult()
        if (expectedTitle.isBlank()) return@withContext WaybackBackfillResult()
        val cdxUrl = buildDomainCdxUrl(normalizedDomain, expectedTitle, now, lookbackDays)
            ?: return@withContext WaybackBackfillResult()
        val attempt = attemptKey("archive-v2|domain|$normalizedDomain|$expectedTitle")
        if (!forceRefresh && wasAttemptedRecently(attempt, now)) {
            return@withContext WaybackBackfillResult(skippedRecently = true)
        }
        queryCdxAndFetchPrices(cdxUrl, expectedTitle, attempt, now)
    }

    internal fun wasAttemptedRecently(key: String, now: Long): Boolean {
        val lastAttempt = preferences.getLong(key, 0L)
        if (lastAttempt <= 0L || now < lastAttempt) return false
        // Empty searches should be retried sooner than useful archived histories.
        // Legacy cache entries without this flag are treated as negative results.
        val hadQuotes = preferences.getBoolean("${key}_has_quotes", false)
        val failed = preferences.getBoolean("${key}_failed", false)
        val retryInterval = when {
            hadQuotes -> RETRY_INTERVAL_MS
            failed -> FAILURE_RETRY_INTERVAL_MS
            else -> NEGATIVE_RETRY_INTERVAL_MS
        }
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
        now: Long
    ): WaybackBackfillResult {
        val request = Request.Builder().url(cdxUrl).header("User-Agent", USER_AGENT).get().build()
        val captures = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
                    return WaybackBackfillResult()
                }
                val body = readBodyLimited(response.body, MAX_CDX_BODY_BYTES)
                if (body == null) {
                    rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
                    return WaybackBackfillResult()
                }
                parseCaptureIndex(body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rememberAttempt(cacheKey, now, hasQuotes = false, failed = true)
            return WaybackBackfillResult()
        }

        val sampled = sampleCaptures(captures, MAX_SNAPSHOTS_PER_URL)
        val semaphore = Semaphore(MAX_CONCURRENT_SNAPSHOT_REQUESTS)
        val fetched = coroutineScope {
            sampled.map { capture ->
                async {
                    semaphore.withPermit {
                        val snapshotUrl = buildSnapshotUrl(capture) ?: return@withPermit SnapshotFetch(false, null)
                        val snapshotRequest = Request.Builder()
                            .url(snapshotUrl)
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()
                        val html = try {
                            http.newCall(snapshotRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    readBodyLimited(response.body, MAX_SNAPSHOT_BODY_BYTES)
                                } else null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        } ?: return@withPermit SnapshotFetch(false, null)
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
            }.awaitAll()
        }

        // Cache useful histories for 30 days, but retry empty/ambiguous results after 7 days.
        val quotes = fetched.mapNotNull { it.quote }
            .sortedBy { it.observedAt }
            .distinctBy { Triple(it.originalUrl, it.observedAt, it.price) }
        val allSnapshotsFailed = captures.isNotEmpty() && fetched.none { it.fetched }
        rememberAttempt(
            cacheKey,
            now,
            hasQuotes = quotes.isNotEmpty(),
            failed = quotes.isEmpty() && allSnapshotsFailed
        )
        return WaybackBackfillResult(
            quotes = quotes,
            capturesFound = captures.size,
            capturesChecked = fetched.count { it.fetched },
            available = true
        )
    }

    /** Resolve recognisable merchant labels; never guess a site from generic labels. */
    internal fun resolveMerchantDomain(storeName: String, sourceUrl: String = ""): String? {
        if (storeName.isBlank()) return null
        val hostPattern = Regex(
            """(?i)(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:es|com|net|org|eu|de|fr|it|nl|co\.uk))(?:[:/]|$)"""
        )
        val explicitHost = hostPattern.find(storeName.trim())?.groupValues?.getOrNull(1)
        if (explicitHost != null) return normalizeMerchantDomain(explicitHost)
        if (sourceUrl.isNotBlank()) {
            val sourceHost = runCatching { URI(sourceUrl).host }.getOrNull()
            sourceHost?.let(::normalizeMerchantDomain)?.let { return it }
        }

        val normalized = normalizeTitle(storeName)
        val aliases = linkedMapOf(
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
            "ebay" to "ebay.es",
            "movistar" to "movistar.es",
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
            "hp" to "hp.com",
            "fujifilm" to "eshop.fujifilm-x.com",
            "fujifilm x gfx eshop" to "eshop.fujifilm-x.com"
        )
        val match = aliases.entries.firstOrNull { (label, _) ->
            normalized == label || normalized.contains(label)
        } ?: return null
        return normalizeMerchantDomain(match.value)
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
            "hp" to ("HP" to "hp.com"),
            "fujifilm" to ("FUJIFILM X | GFX eShop" to "eshop.fujifilm-x.com"),
            "instax" to ("FUJIFILM X | GFX eShop" to "eshop.fujifilm-x.com")
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
        }
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
            "eshop.fujifilm-x.com" -> setOf("fujifilm", "fuji", "eshop", "camera", "cameras", "camara", "camaras")
            else -> setOf(normalizedDomain.substringBefore('.'))
        }
        val textTokens = tokens.filter { token ->
            token.any(Char::isLetter) && !token.any(Char::isDigit) && token !in hostBrandTerms
        }
        // Avoid broad "OR" queries: on a large retailer domain, one generic token may match
        // hundreds of unrelated URLs. Match a model code plus a distinct path term when both
        // exist. If a title only gives a model code, use that exact code; generic brand/category
        // words must not make a model URL impossible to discover.
        val requiredLookaheads = when {
            // Model-coded products are already distinctive. Don't require a secondary word like
            // "wireless" that retailers may omit from the URL slug.
            modelTokens.isNotEmpty() -> listOf("(?=.*${urlTokenPattern(modelTokens.first())})")
            textTokens.size >= 2 -> textTokens.take(2).map { "(?=.*${urlTokenPattern(it)})" }
            else -> return null
        }
        val start = (now - lookbackDays.coerceAtLeast(1).toLong() * DAY_MS).coerceAtLeast(0L)
        val from = formatUtc(start, "yyyyMMdd")
        val to = formatUtc(now, "yyyyMMdd")
        val urlPattern = "(?i)" + requiredLookaheads.joinToString("") + ".*"
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
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            // Do not collapse by timestamp here: this query spans many product URLs.
            // Keeping multiple dates per URL is what makes a historical price series possible.
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
            ?: readItempropValue(html, "name")
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)

        // Prefer the page's explicit product price metadata in a stable priority order.
        // readMeta(Set) follows document order, so each key is queried separately here.
        val metaPrice = listOf(
            "product:price:amount", "og:price:amount", "price", "product:price"
        ).firstNotNullOfOrNull { key ->
            readMeta(html, setOf(key))?.let(::parsePositivePrice)
        } ?: readItempropValue(html, "price")?.let(::parsePositivePrice)
        val metaCurrency = listOf(
            "product:price:currency", "og:price:currency", "pricecurrency"
        ).firstNotNullOfOrNull { key -> readMeta(html, setOf(key)) }
            ?: readItempropValue(html, "priceCurrency")
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

    /**
     * Read an explicit Schema.org microdata property without scraping arbitrary visible prices.
     * This supports archived product pages that use itemprop attributes instead of JSON-LD/meta tags.
     */
    private fun readItempropValue(html: String, propertyName: String): String? {
        val target = propertyName.lowercase(Locale.ROOT)
        val openingTag = Regex("""(?is)<([a-z][a-z0-9]*)\b[^>]*\bitemprop\s*=\s*["']([^"']+)["'][^>]*>""")
        val voidTags = setOf("meta", "link", "img", "input", "br", "hr", "area", "base", "embed", "source", "track", "wbr")
        for (match in openingTag.findAll(html)) {
            val properties = match.groupValues[2].lowercase(Locale.ROOT).split(Regex("\\s+"))
            if (target !in properties) continue
            val tagName = match.groupValues[1].lowercase(Locale.ROOT)
            val attributes = Regex("""(?is)([a-z_:.-]+)\s*=\s*["'](.*?)["']""")
                .findAll(match.value)
                .associate { it.groupValues[1].lowercase(Locale.ROOT) to decodeHtml(it.groupValues[2]).trim() }
            val attributeValue = sequenceOf("content", "value", "data-price")
                .mapNotNull { key -> attributes[key]?.takeIf { it.isNotBlank() } }
                .firstOrNull()
            if (attributeValue != null) return attributeValue
            if (tagName in voidTags) continue
            val closing = Regex("""(?is)</\s*${Regex.escape(tagName)}\s*>""")
                .find(html, match.range.last + 1) ?: continue
            val text = html.substring(match.range.last + 1, closing.range.first)
                .replace(Regex("""(?is)<[^>]+>"""), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isNotBlank()) return decodeHtml(text)
        }
        return null
    }

    private fun isMatchingProductTitle(expected: String, archived: String): Boolean {
        val e = normalizeTitle(expected)
        val a = normalizeTitle(archived)
        if (e.isBlank() || a.isBlank()) return false

        // Normalize product identity first so known equivalent model spellings (such as
        // Sony WH-1000XM5 vs WH1000XM5) are not rejected solely because punctuation differs.
        val sameIdentity = onlineProductIdentityKey(expected) == onlineProductIdentityKey(archived)

        // Never let a high overlap of generic words override a different model, size, year,
        // or capacity. For example, "Anker Prime 20K Power Bank" must not match a 27K listing.
        // Merge common unit spellings first so "512 GB" and "512GB" compare consistently.
        val expectedVariants = requiredVariantTokens(e)
        val archivedVariants = requiredVariantTokens(a)
        if (!sameIdentity && !archivedVariants.containsAll(expectedVariants)) return false

        if (sameIdentity) return true
        val expectedTokens = meaningfulTokens(e)
        val archivedTokens = meaningfulTokens(a)
        if (expectedTokens.isEmpty() || archivedTokens.isEmpty()) return false
        val overlap = expectedTokens.intersect(archivedTokens)
        return overlap.size >= minOf(2, expectedTokens.size) &&
            overlap.size.toDouble() / expectedTokens.size >= 0.72
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
        val quote: ArchivedPriceQuote?
    )

    internal data class WaybackCapture(
        val rawTimestamp: String,
        val timestamp: Long,
        val originalUrl: String
    )

    companion object {
        const val DEFAULT_LOOKBACK_DAYS = 1600
        const val MAX_SNAPSHOTS_PER_URL = 12
        private const val MAX_DOMAIN_INDEX_ROWS = 80
        private const val MAX_CONCURRENT_SNAPSHOT_REQUESTS = 4
        const val RETRY_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_RETRY_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        const val FAILURE_RETRY_INTERVAL_MS = 30L * 60L * 1000L
        private const val MAX_CDX_BODY_BYTES = 256 * 1024
        private const val MAX_SNAPSHOT_BODY_BYTES = 2 * 1024 * 1024
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_HISTORICAL_PRICE = 0.50
        const val MAX_HISTORICAL_PRICE = 10_000.0
        private const val USER_AGENT = "MinimoPriceHistory/1.0 (historical lookup from public web archive)"
    }
}
