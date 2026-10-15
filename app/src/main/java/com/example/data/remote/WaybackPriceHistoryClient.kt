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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
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
    val storeLinkUnresolved: Boolean = false
)

/**
 * Best-effort free historical backfill using Internet Archive snapshots.
 * A capture is accepted only when structured metadata exposes a EUR price and a product title
 * matching the searched item. Archive gaps are expected; this client never invents prices.
 */
class WaybackPriceHistoryClient(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
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
        storeName: String = ""
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean("enabled", true)) {
            return@withContext WaybackBackfillResult()
        }
        if (expectedTitle.isBlank()) {
            return@withContext WaybackBackfillResult()
        }
        val canResolveShoppingLink = isGoogleShoppingUrl(originalUrl) && storeName.isNotBlank()
        if (!isEligibleDirectUrl(originalUrl) && !canResolveShoppingLink) {
            return@withContext WaybackBackfillResult()
        }

        val attemptKey = attemptKey(originalUrl + "|" + expectedTitle)
        val lastAttempt = preferences.getLong(attemptKey, 0L)
        if (lastAttempt > 0L && now >= lastAttempt && now - lastAttempt < RETRY_INTERVAL_MS) {
            return@withContext WaybackBackfillResult(skippedRecently = true)
        }

        val resolvedOriginalUrl = if (isEligibleDirectUrl(originalUrl)) {
            originalUrl
        } else {
            resolveDirectMerchantUrl(originalUrl, storeName) ?: run {
                // Cache an unsuccessful extraction too, so repeated searches do not repeatedly
                // fetch a Google Shopping page that does not expose the seller destination.
                preferences.edit().putLong(attemptKey, now).apply()
                return@withContext WaybackBackfillResult(storeLinkUnresolved = true)
            }
        }

        val cdxUrl = buildCdxUrl(resolvedOriginalUrl, now, lookbackDays)
            ?: return@withContext WaybackBackfillResult()
        val cdxRequest = Request.Builder().url(cdxUrl).header("User-Agent", USER_AGENT).get().build()
        val captures = try {
            http.newCall(cdxRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext WaybackBackfillResult()
                parseCaptureIndex(response.body?.string().orEmpty())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext WaybackBackfillResult()
        }

        val sampled = sampleCaptures(captures, MAX_SNAPSHOTS_PER_URL)
        val semaphore = Semaphore(MAX_CONCURRENT_SNAPSHOT_REQUESTS)
        val fetched = coroutineScope {
            sampled.map { capture ->
                async {
                    semaphore.withPermit {
                        val snapshotUrl = buildSnapshotUrl(capture) ?: return@withPermit SnapshotFetch(false, null)
                        val request = Request.Builder().url(snapshotUrl).header("User-Agent", USER_AGENT).get().build()
                        val html = try {
                            http.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.string() else null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        } ?: return@withPermit SnapshotFetch(false, null)
                        SnapshotFetch(
                            fetched = true,
                            quote = parseArchivedPrice(html, expectedTitle, resolvedOriginalUrl, snapshotUrl, capture.timestamp)
                        )
                    }
                }
            }.awaitAll()
        }

        // Negative results are cached too, avoiding repeated load on the public archive.
        preferences.edit().putLong(attemptKey, now).apply()
        WaybackBackfillResult(
            quotes = fetched.mapNotNull { it.quote }.sortedBy { it.observedAt }
                .distinctBy { it.observedAt to it.price },
            capturesFound = captures.size,
            capturesChecked = fetched.count { it.fetched },
            available = true
        )
    }

    private fun resolveDirectMerchantUrl(googleUrl: String, storeName: String): String? {
        val request = Request.Builder()
            .url(googleUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val html = try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return extractDirectMerchantUrl(html, storeName)
    }

    /**
     * Extract only outbound URLs whose host matches the seller name shown by Shopping.
     * Google redirect parameters are unwrapped, but generic links and other sellers are rejected.
     */
    internal fun extractDirectMerchantUrl(html: String, storeName: String): String? {
        if (html.isBlank() || storeName.isBlank()) return null
        val decodedHtml = html
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\/", "/")
        val candidates = linkedSetOf<String>()

        fun decodeQueryValue(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

        fun collectUrl(rawValue: String, depth: Int = 0) {
            if (depth > 3) return
            val value = decodeHtml(rawValue).trim().trim('"', '\'')
            if (value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("http://", ignoreCase = true)
            ) candidates += value

            val uri = runCatching { URI(value) }.getOrNull() ?: return
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            if (!host.startsWith("google.") && !host.endsWith(".google.com") && !host.endsWith(".google.es")) return

            val redirectParameters = setOf("q", "url", "adurl", "uddg", "target", "dest", "destination", "redirect", "redirect_url")
            uri.rawQuery.orEmpty().split('&').forEach { parameter ->
                val pair = parameter.split('=', limit = 2)
                if (pair.size != 2) return@forEach
                val key = decodeQueryValue(pair[0]).lowercase(Locale.ROOT)
                if (key !in redirectParameters) return@forEach
                val target = decodeQueryValue(pair[1])
                    .replace("\\u0026", "&", ignoreCase = true)
                    .replace("&amp;", "&", ignoreCase = true)
                if (target.startsWith("https://", ignoreCase = true) ||
                    target.startsWith("http://", ignoreCase = true)
                ) collectUrl(target, depth + 1)
            }
        }

        Regex("""(?is)\\bhref\\s*=\\s*["'](.*?)["']""")
            .findAll(decodedHtml)
            .forEach { collectUrl(it.groupValues[1]) }
        Regex("""https?://[^\\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedHtml)
            .forEach { collectUrl(it.value) }

        val storeCompact = normalizeTitle(storeName).filter(Char::isLetterOrDigit)
        if (storeCompact.length < 4) return null
        fun hostMatchesStore(url: String): Boolean {
            val hostLabels = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.").split('.') }
                .getOrNull() ?: return false
            return hostLabels.any { label ->
                val compactLabel = normalizeTitle(label).filter(Char::isLetterOrDigit)
                compactLabel.length >= 4 &&
                    (storeCompact.contains(compactLabel) || compactLabel.contains(storeCompact))
            }
        }
        return candidates.firstOrNull { isEligibleDirectUrl(it) && hostMatchesStore(it) }
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
        val candidates = mutableListOf<Triple<String, Double, String>>()
        extractJsonLdProductOffers(html).forEach(candidates::add)

        val pageTitle = readMeta(html, setOf("og:title", "twitter:title"))
            ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtml)
        val currency = readMeta(html, setOf("product:price:currency", "og:price:currency", "priceCurrency", "pricecurrency"))
        val price = readMeta(html, setOf("product:price:amount", "og:price:amount", "price", "product:price"))
            ?.let(::parsePositivePrice)
        if (!pageTitle.isNullOrBlank() && price != null && !currency.isNullOrBlank()) {
            candidates += Triple(pageTitle, price, currency.uppercase(Locale.ROOT))
        }

        val matching = candidates.firstOrNull { candidate ->
            candidate.third.equals("EUR", ignoreCase = true) &&
                candidate.second in MIN_HISTORICAL_PRICE..MAX_HISTORICAL_PRICE &&
                isMatchingProductTitle(expectedTitle, candidate.first)
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
        if (onlineProductIdentityKey(expected) == onlineProductIdentityKey(archived)) return true
        val expectedTokens = meaningfulTokens(e)
        val archivedTokens = meaningfulTokens(a)
        if (expectedTokens.isEmpty() || archivedTokens.isEmpty()) return false
        val overlap = expectedTokens.intersect(archivedTokens)
        return overlap.size >= minOf(2, expectedTokens.size) &&
            overlap.size.toDouble() / expectedTokens.size >= 0.72
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
        val normalized = raw.trim().replace(",", ".").replace(Regex("""[^\d.]"""), "")
        val price = normalized.toDoubleOrNull() ?: return null
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
        private const val MAX_CONCURRENT_SNAPSHOT_REQUESTS = 4
        const val RETRY_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_HISTORICAL_PRICE = 0.50
        const val MAX_HISTORICAL_PRICE = 10_000.0
        private const val USER_AGENT = "MinimoPriceHistory/1.0 (historical lookup from public web archive)"
    }
}
