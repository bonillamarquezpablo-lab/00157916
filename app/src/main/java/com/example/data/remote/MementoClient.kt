package com.example.data.remote

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Independent history source for Arquivo.pt's native Memento TimeMap API.
 *
 * This client is a bounded fallback to Wayback, not a price provider on its own: every retrieved
 * page is parsed by WaybackPriceHistoryClient's existing strict title/variant/EUR-price validator.
 */
class MementoClient(
    context: Context,
    private val priceParser: WaybackPriceHistoryClient = WaybackPriceHistoryClient(context),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetchHistory(
        originalUrl: String,
        expectedTitle: String,
        now: Long = System.currentTimeMillis(),
        lookbackDays: Int = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS,
        maxSnapshots: Int = 4,
        knownProductTitlesById: Map<String, String> = emptyMap()
    ): WaybackBackfillResult = withContext(Dispatchers.IO) {
        val originalUri = parseHttpUri(originalUrl)
        if (originalUri == null || !originalUri.scheme.equals("https", true) ||
            originalUri.host.isNullOrBlank() || expectedTitle.isBlank() ||
            isArchiveOrSearchHost(originalUri.host)
        ) {
            return@withContext WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = setOf("MEMENTO_INVALID_TARGET")
            )
        }

        val timeMapUrl = buildTimeMapUrl(originalUrl)
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
        if (timeMapBody == null) {
            return@withContext WaybackBackfillResult(
                requestFailed = true,
                diagnosticCodes = diagnostics.ifEmpty { setOf("MEMENTO_TIMEMAP_FAILED") }
            )
        }

        val captures = sampleQuarterlyCaptures(
            captures = parseTimeMap(timeMapBody, originalUrl, now, lookbackDays),
            maxSnapshots = maxSnapshots
        )
        if (captures.isEmpty()) {
            return@withContext WaybackBackfillResult(
                available = true,
                capturesFound = 0,
                requestFailed = false,
                diagnosticCodes = setOf("MEMENTO_NO_CAPTURES")
            )
        }

        val quotes = mutableListOf<ArchivedPriceQuote>()
        var capturesChecked = 0
        var requestFailed = false
        for (capture in captures) {
            try {
                http.newBuilder()
                    .callTimeout(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .newCall(
                        Request.Builder()
                            .url(capture.snapshotUrl)
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()
                    ).execute().use { response ->
                        when {
                            response.code == 429 -> {
                                requestFailed = true
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
                                        html = html,
                                        expectedTitle = expectedTitle,
                                        originalUrl = originalUrl,
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
                                        originalUrl = originalUrl,
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
            if ("MEMENTO_RATE_LIMITED" in diagnostics) break
        }

        val uniqueQuotes = quotes.distinctBy {
            listOf(
                it.targetProductId.orEmpty(), it.productTitle, it.originalUrl,
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
        val earliest = (now - lookbackDays.coerceAtLeast(1).toLong() * WaybackPriceHistoryClient.DAY_MS)
            .coerceAtLeast(0L)
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
            val rawTimestamp = Regex("""^/wayback/(\d{14})/""")
                .find(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1) ?: continue
            val timestamp = runCatching { formatter.parse(rawTimestamp)?.time }.getOrNull() ?: continue
            if (timestamp !in earliest..now) continue

            val targetInLink = Regex("""^https://arquivo\.pt/wayback/\d{14}/(https://.+)$""")
                .find(secureLink)?.groupValues?.getOrNull(1) ?: continue
            if (!sameOriginalUrl(targetInLink, originalUrl)) continue
            output += MementoCapture(rawTimestamp, timestamp, secureLink, target.toString())
        }
        return output.distinctBy { it.rawTimestamp to it.snapshotUrl }.sortedBy { it.timestamp }
    }

    internal fun sampleQuarterlyCaptures(
        captures: List<MementoCapture>,
        maxSnapshots: Int
    ): List<MementoCapture> {
        if (maxSnapshots <= 0 || captures.isEmpty()) return emptyList()
        val quarterly = captures.sortedBy { it.timestamp }
            .groupBy { capture ->
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
                calendar.timeInMillis = capture.timestamp
                calendar.get(Calendar.YEAR) * 4 + calendar.get(Calendar.MONTH) / 3
            }
            .toSortedMap()
            .values
            .map { it[it.size / 2] }
        if (quarterly.size <= maxSnapshots) return quarterly
        if (maxSnapshots == 1) return listOf(quarterly.last())
        return (0 until maxSnapshots).map { index ->
            quarterly[(index.toDouble() * (quarterly.size - 1) / (maxSnapshots - 1)).toInt()]
        }
    }

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
        const val SNAPSHOT_TIMEOUT_SECONDS = 4L
        const val MAX_TIMEMAP_BYTES = 512 * 1024
        const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "MinimoPriceHistory/1.0 (Memento historical lookup)"
    }
}
