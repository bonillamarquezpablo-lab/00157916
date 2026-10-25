package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MementoClientTest {
    private fun newClient() = MementoClient(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun mementoFallbackRunsForSparseHistoryAndStopsAtThreeDistinctDates() {
        val base = 1_700_000_000_000L
        fun quote(at: Long) = ArchivedPriceQuote(
            observedAt = at,
            price = 199.99,
            currency = "EUR",
            productTitle = "Sony WH-1000XM5",
            originalUrl = "https://shop.example/sony-wh-1000xm5",
            snapshotUrl = "https://web.archive.org/web/20230101000000/https://shop.example/sony-wh-1000xm5"
        )

        val oneDate = listOf(quote(base))
        assertFalse(hasSufficientHistoricalCoverage(oneDate))
        assertFalse(
            "New archived-product discoveries are not historical points for the selected product",
            hasSufficientHistoricalCoverage(listOf(quote(base).copy(isDiscoveryCandidate = true)))
        )
        assertTrue(shouldAttemptMementoFallback(false, true, false, false))
        assertFalse(shouldAttemptMementoFallback(true, true, false, false))
        assertFalse(shouldAttemptMementoFallback(false, false, false, false))
        assertFalse(shouldAttemptMementoFallback(false, true, false, true))

        val duplicateSameDay = listOf(quote(base), quote(base + 60L * 60L * 1000L))
        assertFalse("Two captures from the same day count as one date", hasSufficientHistoricalCoverage(duplicateSameDay))

        val threeDates = listOf(
            quote(base),
            quote(base + WaybackPriceHistoryClient.DAY_MS),
            quote(base + 2L * WaybackPriceHistoryClient.DAY_MS)
        )
        assertTrue(hasSufficientHistoricalCoverage(threeDates))
        assertFalse(shouldAttemptMementoFallback(false, true, true, false))
    }

    @Test
    fun canonicalizeArchiveLookupUrlDropsTrackingButPreservesVariantParameters() {
        val client = newClient()
        assertEquals(
            "https://www.shop.example/product/item?sku=blue&size=256",
            client.canonicalizeArchiveLookupUrl(
                "https://www.shop.example/product/item?srsltid=tracking123&utm_source=shopping&sku=blue&size=256"
            )
        )
    }

    @Test
    fun arquivoReplayUrlsAreConvertedToArchivedContentUrls() {
        val client = newClient()
        val wrapper = "https://arquivo.pt/wayback/20250316233444/https://www.pccomponentes.com/apple-iphone-15-plus-256gb-amarillo-libre"
        val content = "https://arquivo.pt/wayback/20250316233444mp_/https://www.pccomponentes.com/apple-iphone-15-plus-256gb-amarillo-libre"

        assertEquals(content, client.toContentSnapshotUrl(wrapper))
        assertEquals(content, client.toContentSnapshotUrl(content))
        assertNull(client.toContentSnapshotUrl(
            "https://attacker.example/wayback/20250316233444/https://shop.example/product"
        ))
        assertNull(client.toContentSnapshotUrl(
            "https://arquivo.pt/other/20250316233444/https://shop.example/product"
        ))
    }

    @Test
    fun threeDistinctVerifiedExactDatesSkipTheExpensivePrefixScan() {
        val base = 1_750_000_000_000L
        fun quote(day: Int, discovery: Boolean = false) = ArchivedPriceQuote(
            observedAt = base + day.toLong() * WaybackPriceHistoryClient.DAY_MS,
            price = 199.99 - day,
            currency = "EUR",
            productTitle = "Sony WH-1000XM5",
            originalUrl = "https://shop.example/sony-wh-1000xm5",
            snapshotUrl = "https://arquivo.pt/wayback/20260701000000/https://shop.example/sony-wh-1000xm5",
            isDiscoveryCandidate = discovery
        )

        val threeDates = listOf(quote(0), quote(1), quote(2))
        assertTrue(shouldSkipPrefixAfterSufficientExactHistory(threeDates))
        assertFalse(shouldSkipPrefixAfterSufficientExactHistory(threeDates.take(2)))
        assertFalse(
            "Multiple captures on the same UTC date are not enough coverage",
            shouldSkipPrefixAfterSufficientExactHistory(listOf(quote(0), quote(0), quote(0)))
        )
        assertFalse(
            "New-product discovery candidates are not history of the selected product",
            shouldSkipPrefixAfterSufficientExactHistory(listOf(quote(0, true), quote(1, true), quote(2, true)))
        )
        assertFalse(
            "Quotes matched to other products do not establish coverage for this product",
            shouldSkipPrefixAfterSufficientExactHistory(
                listOf(
                    quote(0).copy(targetProductId = "other-product"),
                    quote(1).copy(targetProductId = "other-product"),
                    quote(2).copy(targetProductId = "other-product")
                )
            )
        )
    }

    @Test
    fun buildTimeMapUrlRejectsSearchEnginesAndEncodesNestedQuerySeparators() {
        val client = newClient()
        val url = client.buildTimeMapUrl("https://shop.example/product/item?sku=blue&size=256#offer")
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://arquivo.pt/wayback/timemap/link/https://shop.example/product/item"))
        assertTrue(url.contains("%3Fsku=blue%26size=256"))
        assertTrue(url.contains("%23offer"))
        assertNull(client.buildTimeMapUrl("https://www.google.com/search?q=phone"))
        assertNull(client.buildTimeMapUrl("http://shop.example/product/item"))
    }

    @Test
    fun buildCdxUrlUsesBoundedExactUrlHtmlCaptureQuery() {
        val client = newClient()
        val now = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("20261021000000")!!.time
        val url = client.buildCdxUrl(
            originalUrl = "https://shop.example/product/item?sku=blue&size=256",
            now = now,
            lookbackDays = 365
        )
        assertNotNull(url)
        val parsed = url!!.toHttpUrl()
        assertEquals("arquivo.pt", parsed.host)
        assertEquals("/wayback/cdx", parsed.encodedPath)
        assertEquals("https://shop.example/product/item?sku=blue&size=256", parsed.queryParameter("url"))
        assertEquals("exact", parsed.queryParameter("matchType"))
        assertEquals("json", parsed.queryParameter("output"))
        assertEquals("6", parsed.queryParameter("collapseTime"))
        assertEquals("status:200", parsed.queryParameterValues("filter")?.getOrNull(0))
        assertEquals("mime:text/html", parsed.queryParameterValues("filter")?.getOrNull(1))
        assertEquals("100", parsed.queryParameter("limit"))
        assertNull(client.buildCdxUrl("https://www.google.com/search?q=phone", now, 365))
    }

    @Test
    fun parseCdxNdjsonKeepsOnlyMatchingHtmlCaptureDatesInsideWindow() {
        val client = newClient()
        val now = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("20261021000000")!!.time
        val target = "https://shop.example/product/item?sku=blue&size=256"
        val body = """
            {"timestamp":"20260701000000","url":"$target","mime":"text/html","status":"200"}
            {"timestamp":"20260101000000","url":"$target","mime":"text/html","status":"200"}
            {"timestamp":"20200101000000","url":"$target","mime":"text/html","status":"200"}
            {"timestamp":"20260501000000","url":"https://other.example/product/item","mime":"text/html","status":"200"}
            {"timestamp":"20260601000000","url":"$target","mime":"text/html","status":"404"}
            {"timestamp":"20260602000000","url":"$target","mime":"image/jpeg","status":"200"}
            {"timestamp":"20260603000000","url":"$target","mime":"warc/revisit","status":"200"}
        """.trimIndent()

        val captures = client.parseCdxJsonLines(
            body = body,
            originalUrl = target,
            now = now,
            lookbackDays = 365
        )

        assertEquals(listOf("20260101000000", "20260701000000"), captures.map { it.rawTimestamp })
        assertTrue(captures.all { it.snapshotUrl.startsWith("https://arquivo.pt/wayback/") })
        assertTrue(captures.all { it.snapshotUrl.contains("/item%3Fsku=blue%26size=256") })
        assertTrue(captures.all { it.originalUrl == target })
    }

    @Test
    fun quarterlySamplingKeepsSeveralCapturesFromTheSameQuarterWithinBudget() {
        val client = newClient()
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawDates = listOf(
            "20260401000000", "20260415000000", "20260501000000", "20260615000000"
        )
        val captures = rawDates.map { raw ->
            MementoClient.MementoCapture(
                rawTimestamp = raw,
                timestamp = formatter.parse(raw)!!.time,
                snapshotUrl = "https://arquivo.pt/wayback/$raw/https://shop.example/product",
                originalUrl = "https://shop.example/product"
            )
        } + MementoClient.MementoCapture(
            rawTimestamp = rawDates[1],
            timestamp = formatter.parse(rawDates[1])!!.time,
            snapshotUrl = "https://arquivo.pt/wayback/${rawDates[1]}/https://shop.example/product?alternate=1",
            originalUrl = "https://shop.example/product?alternate=1"
        )

        val sampled = client.sampleQuarterlyCaptures(captures, maxSnapshots = 3)

        assertEquals(3, sampled.size)
        assertEquals(rawDates.first(), sampled.first().rawTimestamp)
        assertEquals(rawDates.last(), sampled.last().rawTimestamp)
        assertEquals(3, sampled.map { it.timestamp }.distinct().size)
    }

    @Test
    fun parseTimeMapExtractsOnlyTargetMatchedMementosInsideWindow() {
        val client = newClient()
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = formatter.parse("20261021000000")!!.time
        val target = "https://shop.example/products/power-bank"
        val recent = "20260701000000"
        val older = "20260101000000"
        val stale = "20200101000000"
        val body = """
            <https://arquivo.pt/wayback/20260701000000/$target>; rel="memento"; datetime="Wed, 01 Jul 2026 00:00:00 GMT",
            <https://arquivo.pt/wayback/20260101000000/$target>; rel="memento"; datetime="Thu, 01 Jan 2026 00:00:00 GMT",
            <https://arquivo.pt/wayback/20200101000000/$target>; rel="memento"; datetime="Wed, 01 Jan 2020 00:00:00 GMT",
            <https://arquivo.pt/wayback/20260501000000/https://other.example/product>; rel="memento"; datetime="Fri, 01 May 2026 00:00:00 GMT",
            <https://attacker.example/wayback/20260601000000/$target>; rel="memento"; datetime="Mon, 01 Jun 2026 00:00:00 GMT"
        """.trimIndent()

        val captures = client.parseTimeMap(
            body = body,
            originalUrl = target,
            now = now,
            lookbackDays = 365
        )

        assertEquals(listOf(older, recent), captures.map { it.rawTimestamp })
        assertTrue(captures.all { it.snapshotUrl.startsWith("https://arquivo.pt/wayback/") })
        assertTrue(captures.all { it.originalUrl == target })
    }

    @Test
    fun quarterlySamplingSpansOlderAndNewerCapturesAndHonorsCap() {
        val client = newClient()
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dates = listOf(
            "20240101000000", "20240201000000",
            "20240401000000", "20240501000000",
            "20240701000000", "20240801000000",
            "20241001000000", "20241101000000",
            "20250101000000", "20250201000000"
        )
        val captures = dates.map { raw ->
            MementoClient.MementoCapture(
                rawTimestamp = raw,
                timestamp = formatter.parse(raw)!!.time,
                snapshotUrl = "https://arquivo.pt/wayback/$raw/https://shop.example/product",
                originalUrl = "https://shop.example/product"
            )
        }
        val allQuarters = client.sampleQuarterlyCaptures(captures, maxSnapshots = 8)
        assertEquals("Use spare download slots for additional dates when CDX has fewer quarters than the cap", 8, allQuarters.size)
        val quarterIds = allQuarters.map {
            val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                timeInMillis = it.timestamp
            }
            calendar.get(java.util.Calendar.YEAR) * 4 + calendar.get(java.util.Calendar.MONTH) / 3
        }
        assertEquals(5, quarterIds.distinct().size)
        val bounded = client.sampleQuarterlyCaptures(captures, maxSnapshots = 3)
        assertEquals(3, bounded.size)
        fun quarterOf(capture: MementoClient.MementoCapture): Int {
            val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                timeInMillis = capture.timestamp
            }
            return calendar.get(java.util.Calendar.YEAR) * 4 + calendar.get(java.util.Calendar.MONTH) / 3
        }
        assertEquals(quarterOf(allQuarters.first()), quarterOf(bounded.first()))
        assertEquals(quarterOf(allQuarters.last()), quarterOf(bounded.last()))
    }
}
