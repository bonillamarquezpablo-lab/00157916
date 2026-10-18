package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaybackPriceHistoryClientTest {
    private fun newClient(): WaybackPriceHistoryClient =
        WaybackPriceHistoryClient(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun liveWaybackLookupFindsVerifiedEurQuoteWhenEnabled() = runBlocking {
        assumeTrue(
            "Live Wayback probe runs only in its dedicated CI step",
            System.getenv("MINIMO_WAYBACK_LIVE_SMOKE") == "1"
        )
        val client = newClient()
        // Exercise the real throttled client only. Raw preflight probes bypassed its cooldown
        // and pacing, adding unnecessary requests and potentially triggering HTTP 429 themselves.
        // Keep the optional live probe to one product only. It must never scan several
        // stores in one CI run; deterministic multi-path coverage belongs in mocked tests.
        val targets = listOf(
            "pccomponentes.com" to "Samsung Galaxy S25 Edge 5G 12GB 512GB 6.7 Titanio Azul"
        )
        val outcomes = mutableListOf<String>()
        var verifiedQuoteFound = false

        for ((domain, title) in targets) {
            val result = withTimeout(12_000L) {
                client.fetchHistoryByMerchantDomain(
                    domain = domain,
                    expectedTitle = title,
                    lookbackDays = 730
                )
            }
            val diagnostics = result.diagnosticCodes.sorted().joinToString(",")
            outcomes += "${domain}/${title}: quotes=${result.quotes.size}, captures=${result.capturesFound}, checked=${result.capturesChecked}, requestFailed=${result.requestFailed}, diagnostics=${diagnostics}"
            if (result.quotes.isNotEmpty()) {
                verifiedQuoteFound = true
                break
            }
            if (result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED")) break
        }

        println("LIVE_WAYBACK_SMOKE " + outcomes.joinToString(" | "))
        assertTrue(
            "No verified historical EUR quote recovered from live Wayback. " + outcomes.joinToString(" | "),
            verifiedQuoteFound
        )
    }

    @Test
    fun cdxBatchLookupRecoversPriceAndDeduplicatesConcurrentLookups() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank Cascade Test ${System.currentTimeMillis()}"
        val archivedUrl = "https://www.shop.example/products/anker-prime-27k-power-bank"
        val requests = CopyOnWriteArrayList<String>()
        val cdxBody = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","$archivedUrl","200","text/html"]
            ]
        """.trimIndent()
        val archivedHtml = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"Product","name":"$title",
                 "offers":{"@type":"Offer","price":"79.99","priceCurrency":"EUR"}}
              </script>
            </head><body></body></html>
        """.trimIndent()

        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val hostAndPath = request.url.host + request.url.encodedPath
            requests += hostAndPath
            val (body, contentType) = when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") ->
                    cdxBody to "application/json; charset=utf-8"
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") ->
                    archivedHtml to "text/html; charset=utf-8"
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }.build()

        try {
            val client = WaybackPriceHistoryClient(context, http)
            val results = withTimeout(12_000L) {
                coroutineScope {
                    listOf(
                        async {
                            client.fetchHistoryByMerchantDomain(
                                domain = "shop.example",
                                expectedTitle = title,
                                lookbackDays = 730
                            )
                        },
                        async {
                            client.fetchHistoryByMerchantDomain(
                                domain = "shop.example",
                                expectedTitle = title,
                                lookbackDays = 730
                            )
                        }
                    ).awaitAll()
                }
            }
            val result = results.first { it.quotes.isNotEmpty() }

            assertEquals("The batched lookup should verify the archived EUR price", 1, result.quotes.size)
            assertEquals(79.99, result.quotes.single().price, 0.001)
            assertEquals("EUR", result.quotes.single().currency)
            assertEquals("Availability must not issue per-date HTTP requests", 0, requests.count { it.startsWith("archive.org/wayback/available") })
            assertEquals("Concurrent duplicate lookups must share one CDX request", 1, requests.count { it.startsWith("web.archive.org/cdx/search/cdx") })
            assertEquals("The duplicate result should use the completed lookup cache", 1, results.count { it.skippedRecently })
            assertEquals("Only one archived snapshot is needed for this one-capture fixture", 1, requests.count { it.startsWith("web.archive.org/web/") })
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun cdxHttp429StopsFurtherRequestsAndPersistsRetryAfter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()
        val requests = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.host + request.url.encodedPath
            val contentType = "text/plain; charset=utf-8"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "300")
                .body("rate limited".toResponseBody(contentType.toMediaType()))
                .build()
        }.build()

        try {
            val client = WaybackPriceHistoryClient(context, http)
            val title = "Anker Prime 27K Rate Limit Test ${System.currentTimeMillis()}"
            val first = withTimeout(2_000L) {
                client.fetchHistoryByMerchantDomain("shop.example", title, lookbackDays = 730)
            }
            assertTrue(first.requestFailed)
            assertTrue(first.diagnosticCodes.contains("WAYBACK_RATE_LIMITED"))
            assertEquals("A 429 must stop before trying another host or snapshot", 1, requests.size)

            val until = preferences.getLong("wayback_rate_limit_until", 0L)
            assertTrue("Retry-After must be persisted", until > System.currentTimeMillis() + 240_000L)
            val second = withTimeout(1_000L) {
                client.fetchHistoryByMerchantDomain("shop.example", title, lookbackDays = 730)
            }
            assertTrue(second.diagnosticCodes.contains("WAYBACK_RATE_LIMITED"))
            assertEquals("The cooldown must prevent any immediate follow-up request", 1, requests.size)
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun parsesWaybackAvailabilityCaptureOnlyWhenMarkedAvailable() {
        val client = newClient()
        val available = """
            {
              "url":"www.apple.com/es/iphone-16/",
              "archived_snapshots":{
                "closest":{
                  "status":"200",
                  "available":true,
                  "url":"https://web.archive.org/web/20250101120000/https://www.apple.com/es/shop/buy-iphone/iphone-16/",
                  "timestamp":"20250101120000"
                }
              }
            }
        """.trimIndent()

        val capture = client.parseAvailabilityCapture(
            available,
            "https://www.apple.com/es/iphone-16/"
        )

        assertNotNull(capture)
        assertEquals("20250101120000", capture!!.rawTimestamp)
        assertEquals(
            "https://www.apple.com/es/shop/buy-iphone/iphone-16/",
            capture.originalUrl
        )
        assertNull(
            client.parseAvailabilityCapture(
                """{"archived_snapshots":{"closest":{"available":false,"timestamp":"20250101120000"}}}""",
                "https://www.apple.com/es/iphone-16/"
            )
        )
    }

    @Test
    fun directUrlCandidatesStripTrackingWithoutDiscardingProductParameters() {
        val client = newClient()
        val original = "https://www.shop.example/product/anker-prime?utm_source=shopping&gclid=click123&sku=27k"

        val candidates = client.buildDirectUrlCandidates(original)

        assertEquals(original, candidates.first())
        assertEquals(2, candidates.size)
        assertEquals(
            "https://www.shop.example/product/anker-prime?sku=27k",
            candidates[1]
        )
        assertTrue(candidates.all { client.isEligibleDirectUrl(it) })
        assertFalse(candidates[1].contains("utm_source"))
        assertFalse(candidates[1].contains("gclid"))
        assertTrue("Functional SKU query parameters must be preserved", candidates[1].contains("sku=27k"))
    }

    @Test
    fun directUrlCandidatesTrySlashVariantWhenThereAreNoTrackingParameters() {
        val client = newClient()
        val candidates = client.buildDirectUrlCandidates("https://shop.example/product/anker-prime")

        assertEquals(
            listOf(
                "https://shop.example/product/anker-prime",
                "https://shop.example/product/anker-prime/"
            ),
            candidates
        )
    }

    @Test
    fun buildsBoundedProductSpecificAvailabilityCandidates() {
        val client = newClient()
        val appleCandidates = client.buildAvailabilityCandidateUrls("apple.com", "iPhone 16")
        assertTrue(
            "Spanish Apple product path should be tried before broad CDX domain scans",
            appleCandidates.contains("https://www.apple.com/es/iphone-16/")
        )
        assertTrue(appleCandidates.size <= 6)
        assertTrue(appleCandidates.any { it.startsWith("https://www.apple.com/") })
        assertTrue(appleCandidates.any { it.startsWith("https://apple.com/") })

        val pcCandidates = client.buildAvailabilityCandidateUrls(
            "pccomponentes.com",
            "Apple iPhone 16 128 GB Negro"
        )
        assertTrue(
            "Known retailer product-search URL should be tried for structured listing metadata",
            pcCandidates.first().startsWith("https://www.pccomponentes.com/search/")
        )

        val samsungCandidates = client.buildAvailabilityCandidateUrls(
            "samsung.com",
            "Samsung Galaxy S25 Edge"
        )
        assertTrue(
            "Samsung's localized product route should be included",
            samsungCandidates.contains("https://www.samsung.com/es/smartphones/galaxy-s/galaxy-s25-edge/")
        )
        assertTrue(samsungCandidates.size <= 6)
    }

    @Test
    fun parsesOnlySuccessfulHtmlCapturesInTimestampOrder() {
        val client = newClient()
        val body = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20230101120000","https://shop.example/product/anker","200","text/html"],
              ["20230201120000","https://shop.example/product/anker","404","text/html"],
              ["20230301120000","https://shop.example/product/anker","200","application/pdf"],
              ["20230401120000","https://shop.example/product/anker","200","text/html"],
              ["20230501120000","https://www.google.com/search?q=anker","200","text/html"]
            ]
        """.trimIndent()

        val captures = client.parseCaptureIndex(body)

        assertEquals(2, captures.size)
        assertEquals("20230101120000", captures.first().rawTimestamp)
        assertEquals("20230401120000", captures.last().rawTimestamp)
    }

    @Test
    fun domainDiscoveryChoosesDistinctProductUrlsBeforeExactHistoryQueries() {
        val client = newClient()
        val body = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","https://shop.example/products/anker-prime-27k","200","text/html"],
              ["20250201120000","https://shop.example/products/anker-prime-27k","200","text/html"],
              ["20250301120000","https://shop.example/item/anker-prime","200","text/html"],
              ["20250401120000","https://shop.example/item/anker-prime","200","text/html"],
              ["20250501120000","https://shop.example/products/unrelated","200","text/html"]
            ]
        """.trimIndent()

        val captures = client.parseCaptureIndex(body)
        val candidates = client.selectDistinctProductUrls(captures)

        assertEquals(
            listOf(
                "https://shop.example/products/anker-prime-27k",
                "https://shop.example/item/anker-prime"
            ),
            candidates
        )
        assertEquals(2, client.selectDistinctProductUrls(captures, limit = 2).size)
    }

    @Test
    fun acceptsVerifiedEurJsonLdPriceWhenProductTitleMatches() {
        val client = newClient()
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@type":"Product",
                "name":"Anker Prime 27K Power Bank",
                "offers":{"@type":"Offer","price":"89.99","priceCurrency":"EUR"}
              }
              </script>
            </head></html>
        """.trimIndent()
        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/product/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20230101120000id_/https://shop.example/product/anker-prime-27k",
            observedAt = 1_672_574_400_000L
        )

        assertNotNull(quote)
        assertEquals(89.99, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
        assertEquals("Anker Prime 27K Power Bank", quote.productTitle)
        assertEquals(1_672_574_400_000L, quote.observedAt)
        assertTrue(quote.snapshotUrl.contains("web.archive.org"))
    }

    @Test
    fun rejectsUnknownCurrencyAmbiguousPageTitleAndUnrelatedProduct() {
        val client = newClient()
        val usdHtml = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Anker Prime 27K Power Bank",
             "offers":{"@type":"Offer","price":"89.99","priceCurrency":"USD"}}
            </script>
        """.trimIndent()
        val unrelatedHtml = """
            <script type="application/ld+json">
            {"@type":"Product","name":"USB-C Cable 2m",
             "offers":{"@type":"Offer","price":"8.99","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()
        val noStructuredPrice = "<html><title>Anker Prime 27K Power Bank</title><body>89,99 €</body></html>"

        assertNull(client.parseArchivedPrice(
            usdHtml,
            "Anker Prime 27K Power Bank",
            "https://shop.example/product/anker-prime-27k",
            "https://web.archive.org/web/20230101120000id_/https://shop.example/product/anker-prime-27k",
            1_672_574_400_000L
        ))
        assertNull(client.parseArchivedPrice(
            unrelatedHtml,
            "Anker Prime 27K Power Bank",
            "https://shop.example/product/anker-prime-27k",
            "https://web.archive.org/web/20230101120000id_/https://shop.example/product/anker-prime-27k",
            1_672_574_400_000L
        ))
        assertNull(client.parseArchivedPrice(
            noStructuredPrice,
            "Anker Prime 27K Power Bank",
            "https://shop.example/product/anker-prime-27k",
            "https://web.archive.org/web/20230101120000id_/https://shop.example/product/anker-prime-27k",
            1_672_574_400_000L
        ))
    }

    @Test
    fun rejectsDifferentModelVariantEvenWhenMostTitleWordsMatch() {
        val client = newClient()
        val wrongCapacityHtml = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Anker Prime 20K Power Bank",
             "offers":{"@type":"Offer","price":"59.99","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()

        assertNull(
            "A 20K archived page must not supply history for the 27K model",
            client.parseArchivedPrice(
                html = wrongCapacityHtml,
                expectedTitle = "Anker Prime 27K Power Bank",
                originalUrl = "https://shop.example/product/anker-prime-27k",
                snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/anker-prime-27k",
                observedAt = 1_735_732_800_000L
            )
        )

        val sameCapacityDifferentFormatting = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Samsung Galaxy S26 Ultra 512 GB",
             "offers":{"@type":"Offer","price":"1299.00","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()
        assertNotNull(
            "Equivalent capacity formats such as 512GB and 512 GB should still match",
            client.parseArchivedPrice(
                html = sameCapacityDifferentFormatting,
                expectedTitle = "Samsung Galaxy S26 Ultra 512GB",
                originalUrl = "https://shop.example/product/galaxy-s26-ultra",
                snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/galaxy-s26-ultra",
                observedAt = 1_735_732_800_000L
            )
        )
    }

    @Test
    fun acceptsSamsungTvRegionalPrefixAndInchLabelDifferences() {
        val client = newClient()
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Samsung QE48S95HAEXXC OLED 48 inch Smart TV",
             "offers":{"@type":"Offer","price":"1394.10","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            originalUrl = "https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("Equivalent TQ/QE regional identifiers for the same Samsung model should match", quote)
        assertEquals(1394.10, quote!!.price, 0.001)

        val url = client.buildDomainCdxUrl(
            domain = "samsung.com",
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            now = 1_800_000_000_000L
        )
        val pathFilter = url?.queryParameterValues("filter").orEmpty()
            .firstOrNull { (it ?: "").startsWith("original:") }.orEmpty()
        assertTrue("Archive lookup should try the stable S95H family model", pathFilter.contains("s95h"))
        assertTrue("Archive lookup should retain the exact regional SKU as a fallback", pathFilter.contains("tq48s95haexxc"))
        assertTrue("Archive lookup should allow product-specific title terms when the SKU is not in the URL", pathFilter.contains("oled"))
        assertTrue("Archive lookup should combine the title fallback with a second distinctive term", pathFilter.contains("smart"))
    }

    @Test
    fun rejectsArchivedSamsungTvWithDifferentDiagonalAndModel() {
        val client = newClient()
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Samsung QE55S95HFEHTXC OLED 55 inch Smart TV",
             "offers":{"@type":"Offer","price":"1548.87","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            originalUrl = "https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            observedAt = 1_735_732_800_000L
        )

        assertNull("A different model and diagonal must never be accepted as historical evidence", quote)
    }

    @Test
    fun acceptsEquivalentSonyHeadphoneModelSpelling() {
        val client = newClient()
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Sony WH1000XM5 Wireless Noise Canceling Headphones",
             "offers":{"@type":"Offer","price":"249.99","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Sony WH-1000XM5 Wireless Headphones",
            originalUrl = "https://shop.example/product/sony-wh1000xm5",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/sony-wh1000xm5",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull(
            "Equivalent Sony model formatting should pass the identity-key comparison",
            quote
        )
    }

    @Test
    fun readsExplicitEurProductMetaTagsAndRejectsGoogleUrlAsArchiveTarget() {
        val client = newClient()
        val html = """
            <html><head>
              <meta property="og:title" content="Autec Zenit 5.5x14 5x100" />
              <meta property="product:price:amount" content="133.12" />
              <meta property="product:price:currency" content="EUR" />
            </head></html>
        """.trimIndent()
        val quote = client.parseArchivedPrice(
            html,
            "Autec Zenit 5.5x14 5x100",
            "https://shop.example/wheels/autec-zenit",
            "https://web.archive.org/web/20230101120000id_/https://shop.example/wheels/autec-zenit",
            1_672_574_400_000L
        )

        assertNotNull(quote)
        assertEquals(133.12, quote!!.price, 0.001)
        assertTrue(client.isEligibleDirectUrl("https://shop.example/wheels/autec-zenit"))
        assertFalse(client.isEligibleDirectUrl("https://www.google.com/shopping/product/123456"))
        assertFalse(client.isEligibleDirectUrl("https://shop.example/"))
    }
    @Test
    fun parsesDotOnlyEuropeanThousandsSeparatorInArchivedMetadata() {
        val client = newClient()
        val html = """
            <html><head>
              <meta property="og:title" content="Samsung Galaxy S26 Ultra 512GB" />
              <meta property="product:price:amount" content="1.299" />
              <meta property="product:price:currency" content="EUR" />
            </head></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Samsung Galaxy S26 Ultra 512GB",
            originalUrl = "https://shop.example/product/galaxy-s26-ultra",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/galaxy-s26-ultra",
            observedAt = 1_735_732_800_000L
        )
        assertNotNull(quote)
        assertEquals(1299.0, quote!!.price, 0.001)
    }

    @Test
    fun resolvesKnownMerchantLabelsAndBuildsBoundedFreeDomainQuery() {
        val client = newClient()
        assertEquals("notino.es", client.resolveMerchantDomain("Notino.es"))
        assertEquals("douglas.es", client.resolveMerchantDomain("Douglas"))
        assertEquals("amazon.es", client.resolveMerchantDomain("Amazon España"))
        assertNull("Generic search-engine labels must not be treated as stores", client.resolveMerchantDomain("Google Shopping"))

        val url = client.buildDomainCdxUrl(
            domain = "notino.es",
            expectedTitle = "Académie Scientifique de Beauté Calendario de Adviento 2024",
            now = 1_800_000_000_000L,
            lookbackDays = 1600
        )
        assertNotNull(url)
        assertEquals("host", url!!.queryParameter("matchType"))
        assertEquals("6", url.queryParameter("limit"))
        assertNull("Host discovery avoids slow collapse processing", url.queryParameter("collapse"))
        assertEquals("20250115", url.queryParameter("from"))
        assertTrue(url.queryParameterValues("filter").orEmpty().any { raw ->
            val filter = raw.orEmpty()
            filter.startsWith("original:") && filter.contains("academie") && filter.contains("scientifique")
        })
        assertNull(
            "A generic category is too ambiguous for a domain-wide archive search",
            client.buildDomainCdxUrl("notino.es", "Calendario de Adviento", now = 1_800_000_000_000L)
        )

        val shortModelUrl = client.buildDomainCdxUrl(
            domain = "example.es",
            expectedTitle = "Volkswagen Polo 9N3 1.9 SDI",
            now = 1_800_000_000_000L
        )
        assertNotNull("Short model codes should be included in the archive URL filter", shortModelUrl)
        assertTrue(shortModelUrl!!.queryParameterValues("filter").orEmpty().any { raw ->
            raw.orEmpty().startsWith("original:") && raw.orEmpty().contains("9n3")
        })
    }

    @Test
    fun parsesEuropeanThousandsAndDecimalSeparatorsInArchivedPriceMetadata() {
        val client = newClient()
        val html = """
            <html><head>
              <meta property="og:title" content="Samsung Galaxy S26 Ultra 512GB" />
              <meta property="product:price:amount" content="1.299,00" />
              <meta property="product:price:currency" content="EUR" />
            </head></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Samsung Galaxy S26 Ultra 512GB",
            originalUrl = "https://shop.example/product/galaxy-s26-ultra",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/galaxy-s26-ultra",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull(quote)
        assertEquals(1299.0, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun resolvesRecognisedMerchantNamesWhenGoogleDoesNotExposeTheDestinationUrl() {
        val client = newClient()
        val googleUrl = "https://www.google.com/search?q=Apple+iPhone+16"

        assertEquals("lamanzanita.es", client.resolveMerchantDomain("La Manzanita", googleUrl))
        assertEquals("nike.com", client.resolveMerchantDomain("Nike Oficial", googleUrl))
        assertEquals("sprinter.es", client.resolveMerchantDomain("Sprinter Deportes", googleUrl))

        assertEquals(
            "A stored retailer domain should outrank Google's intermediate URL",
            "pixmania.com",
            client.resolveMerchantDomain("Pixmania", googleUrl, "pixmania.com")
        )
        assertEquals(
            "A complete official/store URL should normalize to its merchant host",
            "pixmania.com",
            client.resolveMerchantDomain("Pixmania", googleUrl, "https://www.pixmania.com/es/")
        )

        assertEquals("Apple Store" to "apple.com", client.resolveBrandMerchant("Apple iPhone 16 256GB"))
        assertEquals("Nike" to "nike.com", client.resolveBrandMerchant("Nike Air Max Plus Zapatillas"))
        assertEquals("Anker" to "anker.com", client.resolveBrandMerchant("Anker Prime 27K Power Bank"))
        assertEquals(null, client.resolveBrandMerchant("Producto genérico sin marca"))

        val appleUrl = client.buildDomainCdxUrl(
            domain = "apple.com",
            expectedTitle = "Apple iPhone 16 256GB",
            now = 1_800_000_000_000L
        )
        val applePathFilter = appleUrl?.queryParameterValues("filter").orEmpty()
            .firstOrNull { (it ?: "").startsWith("original:") } ?: ""
        assertTrue("An iPhone 16 path should be found despite a title capacity that is not in the URL", applePathFilter.contains("iphone"))
        assertTrue(applePathFilter.contains("16"))
        assertFalse("Capacity alone should not constrain retailer URL discovery", applePathFilter.contains("256gb"))

        val nikeUrl = client.buildDomainCdxUrl(
            domain = "nike.com",
            expectedTitle = "Nike Air Max Plus Zapatillas",
            now = 1_800_000_000_000L
        )
        val nikePathFilter = nikeUrl?.queryParameterValues("filter").orEmpty()
            .firstOrNull { (it ?: "").startsWith("original:") } ?: ""
        assertTrue(nikePathFilter.contains("air"))
        assertTrue(nikePathFilter.contains("max"))
        assertFalse(nikePathFilter.contains("zapatillas"))
        assertFalse("The host brand itself usually is not in product URL paths", nikePathFilter.contains("nike"))
        assertFalse(
            "Distinctive model identifiers should not be combined with extra generic word alternatives",
            nikePathFilter.contains("(?=.*air)(?=.*max)")
        )
    }


    @Test
    fun explicitProductPriceMetadataTakesPrecedenceOverConflictingJsonLdOffers() {
        val client = newClient()
        val html = """
            <html><head>
              <meta property="og:title" content="Anker Prime 27K Power Bank" />
              <meta property="product:price:amount" content="79.99" />
              <meta property="product:price:currency" content="EUR" />
              <script type="application/ld+json">
              {"@type":"Product","name":"Anker Prime 27K Power Bank",
               "offers":{"@type":"Offer","price":"99.99","priceCurrency":"EUR"}}
              </script>
            </head></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/products/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/anker-prime-27k",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull(quote)
        assertEquals("Explicit page price metadata should beat a conflicting JSON-LD offer", 79.99, quote!!.price, 0.001)
    }

    @Test
    fun selectsLowestVerifiedEurOfferWhenExactProductHasMultipleOffers() {
        val client = newClient()
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Anker Prime 27K Power Bank",
             "offers":[
               {"@type":"Offer","price":"79.99","priceCurrency":"EUR"},
               {"@type":"Offer","price":"99.99","priceCurrency":"EUR"}
             ]}
            </script>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/products/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/anker-prime-27k",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull(quote)
        assertEquals("Select the lowest validated EUR offer, independent of JSON-LD order", 79.99, quote!!.price, 0.001)
    }

    @Test
    fun rejectsRefurbishedCaptureForProductExpectedAsNewOrUnspecifiedCondition() {
        val client = newClient()
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Apple iPhone 16 128 GB Negro Reacondicionado",
             "offers":{"@type":"Offer","price":"699.99","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()

        assertNull(
            client.parseArchivedPrice(
                html = html,
                expectedTitle = "Apple iPhone 16 128 GB Negro",
                originalUrl = "https://shop.example/products/apple-iphone-16",
                snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/apple-iphone-16",
                observedAt = 1_735_732_800_000L
            )
        )
    }

    @Test
    fun negativeArchiveCacheRetriesAfterSixHoursButPositiveCacheWaitsThirty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val client = newClient()
        val key = "attempt_retry_policy_regression"
        val lastAttempt = 1_700_000_000_000L
        try {
            preferences.edit()
                .putLong(key, lastAttempt)
                .putBoolean("${key}_has_quotes", false)
                .putBoolean("${key}_failed", false)
                .commit()
            preferences.edit()
                .putBoolean("${key}_failed", true)
                .putBoolean("${key}_rate_limited", true)
                .putLong("wayback_rate_limit_until", lastAttempt + 120_000L)
                .commit()
            assertTrue(
                "A rate-limited lookup is paused until the stored cooldown expires",
                client.wasAttemptedRecently(key, lastAttempt + 60_000L)
            )
            assertFalse(
                "A rate-limited lookup can retry when Retry-After cooldown expires",
                client.wasAttemptedRecently(key, lastAttempt + 180_000L)
            )
            preferences.edit()
                .putBoolean("${key}_rate_limited", false)
                .remove("wayback_rate_limit_until")
                .commit()
            assertTrue(
                "A failed network request is briefly cached to avoid amplifying server throttling",
                client.wasAttemptedRecently(key, lastAttempt + 10L * 60L * 1000L)
            )
            assertFalse(
                "A failed network request can be retried after thirty minutes",
                client.wasAttemptedRecently(key, lastAttempt + 31L * 60L * 1000L)
            )

            preferences.edit().putBoolean("${key}_failed", false).commit()
            assertTrue(
                "An empty search is cached briefly to avoid repeated requests",
                client.wasAttemptedRecently(key, lastAttempt + 5L * 60L * 60L * 1000L)
            )
            assertFalse(
                "An empty search can be retried after six hours",
                client.wasAttemptedRecently(key, lastAttempt + 7L * 60L * 60L * 1000L)
            )

            preferences.edit()
                .putBoolean("${key}_has_quotes", true)
                .putBoolean("${key}_failed", false)
                .commit()
            assertTrue(
                "A useful history remains cached for thirty days",
                client.wasAttemptedRecently(key, lastAttempt + 8L * WaybackPriceHistoryClient.DAY_MS)
            )
            assertFalse(
                "A useful history can be refreshed after thirty days",
                client.wasAttemptedRecently(key, lastAttempt + 31L * WaybackPriceHistoryClient.DAY_MS)
            )
        } finally {
            preferences.edit()
                .remove(key)
                .remove("${key}_has_quotes")
                .remove("${key}_failed")
                .remove("${key}_rate_limited")
                .remove("wayback_rate_limit_until")
                .commit()
        }
    }


    @Test
    fun availabilityRequestsArePacedAndClockChangesCannotCreateLongWaits() {
        val client = newClient()
        val now = 1_800_000_000_000L

        assertEquals(0L, client.computeAvailabilityDelayMs(0L, now))
        assertEquals(1_000L, client.computeAvailabilityDelayMs(now, now + 500L))
        assertEquals(0L, client.computeAvailabilityDelayMs(now, now + 2_000L))
        assertEquals(
            "A backwards clock adjustment must not pause requests for longer than one interval",
            1_500L,
            client.computeAvailabilityDelayMs(now, now - WaybackPriceHistoryClient.DAY_MS)
        )
    }

    @Test
    fun rateLimitRetryAfterIsHonouredAndBounded() {
        val client = newClient()
        val now = 1_800_000_000_000L

        assertEquals(now + 120_000L, client.computeRateLimitUntil("120", now))
        assertEquals(
            "A zero or expired retry delay still has a minimum pause",
            now + 60_000L,
            client.computeRateLimitUntil("0", now)
        )
        assertEquals(
            "Unreasonable Retry-After values are capped at six hours",
            now + 6L * 60L * 60L * 1000L,
            client.computeRateLimitUntil("999999", now)
        )
        assertEquals(
            "Missing or invalid headers use a one-hour default pause",
            now + 60L * 60L * 1000L,
            client.computeRateLimitUntil("not-a-date", now)
        )
    }

    @Test
    fun rateLimitCooldownIsPersistedAndPreventsNewRequestsAcrossClientInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val client = newClient()
        client.clearRateLimitForTests()
        try {
            preferences.edit()
                .putLong("wayback_rate_limit_until", System.currentTimeMillis() + 10L * 60L * 1000L)
                .commit()

            val secondClient = newClient()
            val result = withTimeout(1_000L) {
                secondClient.fetchHistory(
                    originalUrl = "https://shop.example/product/anker-prime-27k",
                    expectedTitle = "Anker Prime 27K Power Bank"
                )
            }

            assertTrue(result.requestFailed)
            assertTrue(result.diagnosticCodes.contains("WAYBACK_RATE_LIMITED"))
            assertTrue("The cooldown is shared by new client instances", result.quotes.isEmpty())
        } finally {
            preferences.edit().remove("wayback_rate_limit_until").commit()
        }
    }

    @Test
    fun resolvesSamsungEdgeAndCommonSellerDomainsFromSavedStoreNames() {
        val client = newClient()
        val googleUrl = "https://www.google.com/search?q=Samsung+Galaxy+S26+Edge"

        assertEquals("samsung.com", client.resolveMerchantDomain("Samsung Shop España", googleUrl, "samsung.com/es"))
        assertEquals("amazon.es", client.resolveMerchantDomain("Amazon España", googleUrl, "amazon.es"))
        assertEquals("fnac.es", client.resolveMerchantDomain("Fnac España", googleUrl, "fnac.es"))
        assertEquals(
            "shop-example.co.uk",
            client.resolveMerchantDomain("shop-example.co.uk")
        )

        val s26 = client.buildDomainCdxUrl(
            domain = "samsung.com",
            expectedTitle = "Samsung Galaxy S26 Edge 256GB",
            now = 1_800_000_000_000L
        )
        val s26Filter = s26?.queryParameterValues("filter").orEmpty()
            .firstOrNull { it.orEmpty().startsWith("original:") }.orEmpty()
        assertTrue(s26Filter.contains("galaxy"))
        assertTrue(s26Filter.contains("26"))

        val s25 = client.buildDomainCdxUrl(
            domain = "samsung.com",
            expectedTitle = "Samsung Galaxy S25 Edge 256GB",
            now = 1_800_000_000_000L
        )
        val s25Filter = s25?.queryParameterValues("filter").orEmpty()
            .firstOrNull { it.orEmpty().startsWith("original:") }.orEmpty()
        assertTrue(s25Filter.contains("galaxy"))
        assertTrue(s25Filter.contains("25"))
    }



    @Test
    fun distinguishesValidEmptyCdxIndexFromMalformedErrorBody() {
        val client = newClient()
        assertTrue(client.isValidCaptureIndexResponse("[]"))
        assertTrue(
            client.isValidCaptureIndexResponse(
                """[["timestamp","original","statuscode","mimetype"]]"""
            )
        )
        assertFalse(client.isValidCaptureIndexResponse("<html>503 Service Unavailable</html>"))
        assertFalse(client.isValidCaptureIndexResponse("""{"error":"rate limited"}"""))
        assertFalse(client.isValidCaptureIndexResponse("""[["date","url"],["20260101120000","https://example.es/"]]"""))
    }


 
    @Test
    fun cdxQueriesHaveEnoughBoundedTimeForSlowArchiveResponses() {
        val client = newClient()
        val now = 1_800_000_000_000L
        val domainUrl = client.buildDomainCdxUrl(
            "shop.example",
            "Anker Prime 27K power bank",
            now,
            365
        )!!
        assertEquals("domain query must remain bounded", "host", domainUrl.queryParameter("matchType"))
        assertEquals("product-specific path matching must be retained",
            true, domainUrl.queryParameterValues("filter").orEmpty().any { it?.startsWith("original:") == true })
    }

    @Test
    fun classifiesWaybackNetworkFailuresWithoutLeakingExceptionText() {
        val client = newClient()
        assertEquals("CDX_EXACT_TIMEOUT", client.classifyNetworkFailure("CDX_EXACT", java.net.SocketTimeoutException("secret url")))
        assertEquals("CDX_DOMAIN_TIMEOUT", client.classifyNetworkFailure("CDX_DOMAIN", java.io.InterruptedIOException("timeout")))
        assertEquals("SNAPSHOT_TIMEOUT", client.classifyNetworkFailure("SNAPSHOT", java.io.InterruptedIOException("timeout")))
        assertEquals("CDX_EXACT_DNS", client.classifyNetworkFailure("CDX_EXACT", java.net.UnknownHostException("private host")))
        assertEquals("CDX_DOMAIN_NETWORK", client.classifyNetworkFailure("CDX_DOMAIN", java.io.IOException("private host")))
        assertEquals("CDX_DOMAIN_UNEXPECTED_ERROR", client.classifyNetworkFailure("CDX_DOMAIN", IllegalStateException("private data")))
    }



    @Test
    fun domainCdxQueryHasBoundedProductSpecificRequestParameters() {
        val client = newClient()
        val url = client.buildDomainCdxUrl(
            domain = "shop.example",
            expectedTitle = "Anker Prime 27K Power Bank",
            now = 1_800_000_000_000L,
            lookbackDays = 1600
        )
        assertNotNull(url)
        assertEquals("web.archive.org", url!!.host)
        assertEquals("/cdx/search/cdx", url.encodedPath)
        assertEquals("shop.example", url.queryParameter("url"))
        assertEquals("host", url.queryParameter("matchType"))
        assertTrue(
            "The CDX original-field filter must limit URLs to distinguishing title/model tokens",
            url.queryParameterValues("filter").orEmpty().any { it.orEmpty().startsWith("original:(?i)") }
        )
        assertEquals("json", url.queryParameter("output"))
        assertEquals("6", url.queryParameter("limit"))
        assertNull("Host discovery avoids slow collapse processing", url.queryParameter("collapse"))
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it == "statuscode:200" })
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it == "mimetype:text/html" })
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it.orEmpty().startsWith("original:") })
        assertEquals("20250115", url.queryParameter("from"))
        assertEquals("20270115", url.queryParameter("to"))
    }

    @Test
    fun domainDiscoveryTargetsConcreteHostsWithoutSlowCollapse() {
        val client = newClient()
        assertEquals(
            listOf("www.shop.example", "shop.example"),
            client.domainDiscoveryHosts("shop.example")
        )
        assertEquals(
            listOf("eu.ugreen.com"),
            client.domainDiscoveryHosts("eu.ugreen.com")
        )
        assertEquals(
            listOf("www.example.co.uk", "example.co.uk"),
            client.domainDiscoveryHosts("example.co.uk")
        )

        val urls = client.buildDomainCdxHostUrls(
            domain = "shop.example",
            expectedTitle = "Anker Prime 27K Power Bank",
            now = 1_800_000_000_000L,
            lookbackDays = 730
        )
        assertNotNull(urls)
        assertEquals(2, urls!!.size)
        assertEquals(listOf("www.shop.example", "shop.example"), urls.map { it.queryParameter("url") })
        assertTrue(urls.all { it.queryParameter("matchType") == "host" })
        assertTrue(urls.all { it.queryParameter("collapse") == null })
        assertTrue(urls.all { it.queryParameter("limit") == "6" })
    }

    @Test
    fun domainCdxQueryRejectsUnrecognisedOrGenericTargets() {
        val client = newClient()
        assertNull(client.buildDomainCdxUrl("https://google.com", "Anker Prime 27K Power Bank"))
        assertNull(client.buildDomainCdxUrl("shop.example", ""))
        assertNull(client.buildDomainCdxUrl("shop.example", "Producto genérico"))
        assertNull(client.buildDomainCdxUrl("shop.example", "Anker"))
    }

}
