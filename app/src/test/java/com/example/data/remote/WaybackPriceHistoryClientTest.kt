package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
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
import com.example.ui.viewmodel.mergeWaybackDiagnosticStatus
import com.example.ui.viewmodel.isSupersededWaybackSkipDetail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaybackPriceHistoryClientTest {
    @Test
    fun repeatedWaybackAttemptsCannotDowngradeAnAlreadyExecutedDiagnosticStage() {
        assertEquals(
            "An Availability capture keeps extraction marked as passed after a later CDX timeout",
            WaybackTraceStatus.PASSED,
            mergeWaybackDiagnosticStatus(WaybackTraceStatus.PASSED, WaybackTraceStatus.SKIPPED)
        )
        assertEquals(
            "An HTML download remains executed when a different route skips the same stage",
            WaybackTraceStatus.PASSED,
            mergeWaybackDiagnosticStatus(WaybackTraceStatus.PASSED, WaybackTraceStatus.FAILED)
        )
        assertEquals(
            "A real validation failure must not be relabelled as skipped by another attempt",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(WaybackTraceStatus.FAILED, WaybackTraceStatus.SKIPPED)
        )
        assertEquals(
            "A retry may move a skipped stage back to running before it completes",
            WaybackTraceStatus.RUNNING,
            mergeWaybackDiagnosticStatus(WaybackTraceStatus.SKIPPED, WaybackTraceStatus.RUNNING)
        )
        assertEquals(
            "A later successful attempt may recover a previously failed stage",
            WaybackTraceStatus.PASSED,
            mergeWaybackDiagnosticStatus(WaybackTraceStatus.FAILED, WaybackTraceStatus.PASSED)
        )
        assertEquals(
            "A final no-capture outcome must override an earlier green status only for the recovery stage",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.PASSED,
                WaybackTraceStatus.FAILED,
                6,
                "Las respuestas llegaron, pero no encontraron capturas utilizables; no hay histórico que validar."
            )
        )

        assertEquals(
            "Availability being reachable is not a recovered history when all probes return no captures",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.PASSED,
                WaybackTraceStatus.FAILED,
                7,
                "Availability respondió correctamente en 5 URL(s), pero no encontró capturas. 0 intento(s) fallido(s)."
            )
        )
        assertEquals(
            "A final no-capture fallback summary must also override an earlier green step 7",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.PASSED,
                WaybackTraceStatus.FAILED,
                7,
                "Availability respondió, pero no encontró capturas y alguna consulta falló: CDX_DOMAIN_TIMEOUT."
            )
        )
        assertEquals(
            "The no-capture override must not rewrite unrelated diagnostic stages",
            WaybackTraceStatus.PASSED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.PASSED,
                WaybackTraceStatus.FAILED,
                15,
                "No coincide la identidad del producto."
            )
        )
        assertEquals(
            "A later capture DNS failure must make the aggregate second-capture row red",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.PASSED,
                WaybackTraceStatus.FAILED,
                13,
                "captura 4 de 8 · fecha 20250901123456 · ruta CDX_URL_PREFIX · tienda www.pccomponentes.com · SNAPSHOT_DNS"
            )
        )
        assertEquals(
            "A later successful capture must not hide an earlier failed capture in the same row",
            WaybackTraceStatus.FAILED,
            mergeWaybackDiagnosticStatus(
                WaybackTraceStatus.FAILED,
                WaybackTraceStatus.PASSED,
                13,
                "captura 3 de 8 · HTML recibido"
            )
        )
        assertTrue(
            "A CDX-only skip message must disappear once Availability downloads that capture",
            isSupersededWaybackSkipDetail(
                "[00:40:02] No se pudo iniciar la descarga de la captura 1 porque el índice CDX no respondió correctamente."
            )
        )
        assertTrue(
            "A stale extraction skip must be removed after another route extracts captures",
            isSupersededWaybackSkipDetail(
                "[00:40:02] Extracción no ejecutada: CDX no entregó una respuesta utilizable."
            )
        )
        assertTrue(
            "A CDX no-capture message must disappear once an alternate source downloads HTML",
            isSupersededWaybackSkipDetail(
                "[00:40:02] No se pudo comprobar la identidad: CDX no proporcionó ninguna captura para descargar."
            )
        )
        assertTrue(
            "A stale CDX price-extraction skip must disappear after a real HTML evaluation",
            isSupersededWaybackSkipDetail(
                "[00:40:02] Extracción de precio omitida: no se descargó HTML de una captura CDX."
            )
        )
        assertTrue(
            "An Availability no-capture skip must disappear if another attempt really checks a capture",
            isSupersededWaybackSkipDetail(
                "[00:40:02] Verificación del producto omitida: Availability no devolvió una captura para descargar."
            )
        )
        assertFalse(
            "A real product-identity mismatch must remain visible",
            isSupersededWaybackSkipDetail(
                "[00:40:07] No coincide la identidad del producto: el título archivado pertenece a otra variante."
            )
        )
    }

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
    fun cdxBatchLookupRecoversPriceAndDeduplicatesConcurrentLookupsWithCaptchaLibraryScript() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank Edición Española Cascade Test ${System.currentTimeMillis()}"
        val archivedUrl = "https://www.shop.example/products/anker-prime-27k-power-bank"
        val requests = CopyOnWriteArrayList<String>()
        val cdxMatchTypes = CopyOnWriteArrayList<String>()
        val cdxBody = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","$archivedUrl","200","text/html"]
            ]
        """.trimIndent()
        val archivedHtml = """
            <html><head>
              <!-- A normal page may load a CAPTCHA library without presenting a challenge. -->
              <script src="/assets/recaptcha-widget.js"></script>
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
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    cdxMatchTypes += request.url.queryParameter("matchType").orEmpty()
                    cdxBody to "application/json; charset=utf-8"
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") ->
                    archivedHtml to "text/html; charset=iso-8859-1"
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
                                lookbackDays = 730,
                                preferredUrl = archivedUrl
                            )
                        },
                        async {
                            client.fetchHistoryByMerchantDomain(
                                domain = "shop.example",
                                expectedTitle = title,
                                lookbackDays = 730,
                                preferredUrl = archivedUrl
                            )
                        }
                    ).awaitAll()
                }
            }
            val result = results.first { it.quotes.isNotEmpty() }

            assertEquals("The batched lookup should verify the archived EUR price", 1, result.quotes.size)
            assertTrue("At least one verified quote must be recovered", result.quotes.isNotEmpty())
            assertTrue("Every recovered quote must retain the verified EUR price", result.quotes.all { kotlin.math.abs(it.price - 79.99) < 0.001 })
            assertEquals("EUR", result.quotes.single().currency)
            assertEquals("Availability must not issue per-date HTTP requests", 0, requests.count { it.startsWith("archive.org/wayback/available") })
            assertEquals("Concurrent duplicate lookups must share one CDX request", 1, requests.count { it.startsWith("web.archive.org/cdx/search/cdx") })
            assertEquals("The product URL prefix should be queried before any broad host scan", "prefix", cdxMatchTypes.single())
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
    fun manualHistoryRefreshBypassesPerProductCacheButNormalLookupStillSkipsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = WaybackPriceHistoryClient(context)
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val key = "attempt_manual_refresh_test"
        val now = System.currentTimeMillis()
        preferences.edit()
            .putLong(key, now)
            .putBoolean("${key}_has_quotes", true)
            .putBoolean("${key}_failed", false)
            .putBoolean("${key}_rate_limited", false)
            .commit()
        try {
            assertTrue(client.shouldSkipRecentAttempt(key, now, forceRefresh = false))
            assertFalse(
                "Manual refresh must recheck dates despite the per-product cache",
                client.shouldSkipRecentAttempt(key, now, forceRefresh = true)
            )
        } finally {
            preferences.edit()
                .remove(key)
                .remove("${key}_has_quotes")
                .remove("${key}_failed")
                .remove("${key}_rate_limited")
                .commit()
        }
    }

    @Test
    fun cachedDirectLookupEmitsExplicitSkippedTraceAndDoesNotSendNetworkRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime cache diagnostic " + System.currentTimeMillis()
        val originalUrl = "https://shop.example/products/anker-prime-cache-test"
        val baseClient = WaybackPriceHistoryClient(context)
        val canonicalUrl = baseClient.buildDirectUrlCandidates(originalUrl).last()
        val urlParts = baseClient.decomposeProductUrl(originalUrl)!!
        val rawKey = "url-cdx-batch-v9-exact-first|" +
            urlParts.merchantDomain + "|" +
            urlParts.productIdentifiers.sorted().joinToString(",") + "|" +
            canonicalUrl + "|" + title
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        val cacheKey = "attempt_" + digest.take(16).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val now = System.currentTimeMillis()
        preferences.edit()
            .putLong(cacheKey, now)
            .putBoolean(cacheKey + "_has_quotes", false)
            .putBoolean(cacheKey + "_failed", false)
            .putBoolean(cacheKey + "_rate_limited", false)
            .commit()

        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val noNetworkClient = OkHttpClient.Builder().addInterceptor {
            throw AssertionError("A recent negative cache entry must not start another HTTP request")
        }.build()

        try {
            val result = WaybackPriceHistoryClient(context, noNetworkClient).fetchHistory(
                originalUrl = originalUrl,
                expectedTitle = title,
                onTrace = { traceEvents += it }
            )

            assertTrue("The recent lookup should be skipped to protect the archive", result.skippedRecently)
            assertTrue(
                "Step 5 must explicitly say the request was skipped by cache",
                traceEvents.any {
                    it.step == 4 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("caché reciente")
                }
            )
            assertTrue(
                "Step 6 must not remain pending when no fresh response was requested",
                traceEvents.any {
                    it.step == 5 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("no hubo respuesta CDX nueva")
                }
            )
            assertTrue(
                "Capture extraction must explain that no new request was made",
                traceEvents.any {
                    it.diagnosticStep == 8 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("omitió por caché")
                }
            )
            assertTrue(
                "Identity validation must be explicitly skipped without archived HTML",
                traceEvents.any {
                    it.diagnosticStep == 15 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("no se descargó ninguna captura")
                }
            )
            assertTrue(
                "Price extraction must be explicitly skipped without archived HTML",
                traceEvents.any {
                    it.diagnosticStep == 16 && it.status == WaybackTraceStatus.SKIPPED
                }
            )
        } finally {
            preferences.edit()
                .remove(cacheKey)
                .remove(cacheKey + "_has_quotes")
                .remove(cacheKey + "_failed")
                .remove(cacheKey + "_rate_limited")
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun unresolvedMerchantDomainReportsNoRequestInsteadOfAnUnexecutedGenericChain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val client = WaybackPriceHistoryClient(context)
        val result = client.fetchHistoryByMerchantDomain(
            domain = "Google Shopping",
            expectedTitle = "Apple iPhone 15 256 GB Amarillo",
            onTrace = { traceEvents += it }
        )

        assertTrue(result.diagnosticCodes.contains("DOMAIN_UNRESOLVED"))
        assertTrue(
            "CDX request must say no request was sent because the merchant domain was invalid",
            traceEvents.any {
                it.diagnosticStep == 5 && it.status == WaybackTraceStatus.SKIPPED &&
                    it.detail.contains("DOMAIN_UNRESOLVED")
            }
        )
        assertTrue(
            "Fallback must say it failed to start, not that it successfully executed",
            traceEvents.any {
                it.diagnosticStep == 7 && it.status == WaybackTraceStatus.FAILED &&
                    it.detail.contains("DOMAIN_UNRESOLVED")
            }
        )
        assertTrue(
            "Identity, price and EUR checks must be skipped when no capture was queried",
            (15..17).all { step ->
                traceEvents.any {
                    it.diagnosticStep == step && it.status == WaybackTraceStatus.SKIPPED
                }
            }
        )
    }

    @Test
    fun availabilityHttp429StopsFurtherRequestsAndPersistsRetryAfter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .remove("wayback_rate_limit_last_hit_at")
            .remove("wayback_rate_limit_hit_count")
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
                .remove("wayback_rate_limit_last_hit_at")
                .remove("wayback_rate_limit_hit_count")
                .commit()
        }
    }

    @Test
    fun exactDirectCdxVerifiedPrimaryQuoteSkipsPrefixAndNotifiesCallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .remove("wayback_rate_limit_last_hit_at")
            .remove("wayback_rate_limit_hit_count")
            .commit()

        val title = "Anker Prime 27K Power Bank Exact CDX Regression ${System.currentTimeMillis()}"
        val originalUrl = "https://shop.example/products/anker-prime-27k-exact-regression"
        val requestUrls = CopyOnWriteArrayList<String>()
        val cdxMatchTypes = CopyOnWriteArrayList<String>()
        val cdxBody = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","$originalUrl","200","text/html"]
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
            requestUrls += hostAndPath
            when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    cdxMatchTypes += request.url.queryParameter("matchType").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(cdxBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected request during exact-CDX test: $hostAndPath")
            }
        }.build()

        try {
            val callbackQuotes = CopyOnWriteArrayList<ArchivedPriceQuote>()
            val result = withTimeout(12_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistory(
                    originalUrl = originalUrl,
                    expectedTitle = title,
                    lookbackDays = 2_920,
                    onVerifiedQuotes = { callbackQuotes.addAll(it) }
                )
            }

            assertEquals("Only the exact CDX query should run after a verified primary price", listOf("exact"), cdxMatchTypes.toList())
            assertEquals(1, result.quotes.size)
            assertEquals(79.99, result.quotes.single().price, 0.001)
            assertEquals("The incremental callback must receive the verified primary quote", 1, callbackQuotes.size)
            assertEquals(79.99, callbackQuotes.single().price, 0.001)
            assertEquals(1, requestUrls.count { it.startsWith("web.archive.org/web/") })
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .remove("wayback_rate_limit_last_hit_at")
                .remove("wayback_rate_limit_hit_count")
                .commit()
        }
    }

    @Test
    fun directCdx429ActivatesTheSharedPauseAndPreventsASecondRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .remove("wayback_rate_limit_last_hit_at")
            .remove("wayback_rate_limit_hit_count")
            .commit()

        val requests = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.host + request.url.encodedPath
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "300")
                .body("rate limited".toResponseBody("text/plain; charset=utf-8".toMediaType()))
                .build()
        }.build()

        try {
            val client = WaybackPriceHistoryClient(context, http)
            val title = "Anker Prime 27K Direct CDX Rate Limit ${System.currentTimeMillis()}"
            val originalUrl = "https://shop.example/product/anker-prime-27k"
            val first = withTimeout(5_000L) {
                client.fetchHistory(originalUrl, title)
            }
            assertTrue(first.requestFailed)
            assertTrue(first.diagnosticCodes.contains("WAYBACK_RATE_LIMITED"))
            assertEquals("The first direct CDX request is the only network request", 1, requests.size)
            assertTrue(
                "The shared cooldown remains active after CDX responds with 429",
                client.activeRateLimitUntil() != null
            )

            val second = withTimeout(1_000L) {
                client.fetchHistory(originalUrl, title)
            }
            assertTrue(second.diagnosticCodes.contains("WAYBACK_RATE_LIMITED"))
            assertEquals("No direct CDX retry may be sent during the shared pause", 1, requests.size)
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .remove("wayback_rate_limit_last_hit_at")
                .remove("wayback_rate_limit_hit_count")
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
        val original = "https://www.shop.example/product/anker-prime?utm_source=shopping&gclid=click123&gad_campaignid=campaign1&irclickid=partner1&sku=27k&variant_id=yellow-256"

        val candidates = client.buildDirectUrlCandidates(original)

        assertEquals(original, candidates.first())
        assertEquals(2, candidates.size)
        assertEquals(
            "https://www.shop.example/product/anker-prime?sku=27k&variant_id=yellow-256",
            candidates[1]
        )
        assertTrue(candidates.all { client.isEligibleDirectUrl(it) })
        assertFalse(candidates[1].contains("utm_source"))
        assertFalse(candidates[1].contains("gclid"))
        assertFalse(candidates[1].contains("gad_campaignid"))
        assertFalse(candidates[1].contains("irclickid"))
        assertTrue("Functional SKU query parameters must be preserved", candidates[1].contains("sku=27k"))
        assertTrue("Functional variant ID parameters must be preserved", candidates[1].contains("variant_id=yellow-256"))
        val decomposed = client.decomposeProductUrl(original)
        assertNotNull(decomposed)
        assertTrue(decomposed!!.trackingParameters.containsAll(setOf("utm_source", "gclid", "gad_campaignid", "irclickid")))
        assertEquals(listOf("yellow-256"), decomposed.variantParameters["variant_id"])
    }

    @Test
    fun fullyDecodesNestedGoogleRedirectAndPreservesProductVariantParameters() {
        val client = newClient()
        val encodedDestination =
            "https%253A%252F%252Fshop.example%252Fproduct%252Fiphone-15" +
                "%253Fsku%253Diphone15-yellow-256%2526color%253Dyellow" +
                "%2526storage%253D256GB%2526utm_source%253Dgoogle"
        val wrapped = "https://www.google.com/url?sa=t&url=" + encodedDestination + "&source=web"

        assertEquals(
            "https://shop.example/product/iphone-15?sku=iphone15-yellow-256&color=yellow&storage=256GB&utm_source=google",
            extractDirectRetailerUrl(wrapped)
        )
        val decomposed = client.decomposeProductUrl(wrapped)
        assertNotNull("The URL wrapper must resolve before archive eligibility is checked", decomposed)
        assertEquals("shop.example", decomposed!!.merchantDomain)
        assertEquals(listOf("product", "iphone-15"), decomposed.pathSegments)
        assertEquals(listOf("yellow"), decomposed.queryParameters["color"])
        assertEquals(listOf("256GB"), decomposed.queryParameters["storage"])
        assertEquals(listOf("iphone15-yellow-256"), decomposed.variantParameters["sku"])
        assertTrue(decomposed.productIdentifiers.contains("iphone-15"))
        assertTrue(decomposed.productIdentifiers.contains("iphone15-yellow-256"))
        assertTrue(decomposed.trackingParameters.contains("utm_source"))
        assertEquals("The resolver should report the single embedded redirect hop", 1, decomposed.redirectDepth)
        assertEquals(
            "https://shop.example/product/iphone-15?sku=iphone15-yellow-256&color=yellow&storage=256GB",
            decomposed.canonicalUrl
        )
        assertEquals(
            "shop.example",
            client.resolveMerchantDomain("Google Shopping", wrapped)
        )
        val candidates = client.buildDirectUrlCandidates(wrapped)
        assertEquals("The product URL must be used instead of the Google redirect", 
            "https://shop.example/product/iphone-15?sku=iphone15-yellow-256&color=yellow&storage=256GB",
            candidates.last()
        )
        assertTrue(candidates.all(client::isEligibleDirectUrl))
    }

    @Test
    fun followsMultipleRedirectWrappersAndReportsTheirDepth() {
        val client = newClient()
        val retailer = "https://shop.example/products/redmi-17?sku=redmi-17-blue&color=blue"
        val facebook = "https://l.facebook.com/l.php?u=" +
            java.net.URLEncoder.encode(retailer, Charsets.UTF_8.name())
        val google = "https://www.google.com/url?url=" +
            java.net.URLEncoder.encode(facebook, Charsets.UTF_8.name())

        val resolved = extractDirectRetailerUrlWithDepth(google)
        assertNotNull(resolved)
        assertEquals(retailer, resolved!!.first)
        assertEquals(2, resolved.second)

        val decomposed = client.decomposeProductUrl(google)
        assertNotNull(decomposed)
        assertEquals(2, decomposed!!.redirectDepth)
        assertEquals("shop.example", decomposed.merchantDomain)
        assertEquals(listOf("redmi-17-blue"), decomposed.variantParameters["sku"])
        assertEquals(listOf("blue"), decomposed.variantParameters["color"])
    }

    @Test
    fun decodesBingBase64ClickTargetBeforeWaybackLookup() {
        val client = newClient()
        val retailer = "https://shop.example/products/anker-prime?sku=anker-prime-27k&color=black"
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(retailer.toByteArray(Charsets.UTF_8))
        val wrapped = "https://www.bing.com/ck/a?u=a1" + payload + "&ntb=1"

        assertEquals(retailer, extractDirectRetailerUrl(wrapped))
        val decomposed = client.decomposeProductUrl(wrapped)
        assertNotNull(decomposed)
        assertEquals("shop.example", decomposed!!.merchantDomain)
        assertEquals(listOf("anker-prime-27k"), decomposed.variantParameters["sku"])
        assertEquals(listOf("black"), decomposed.variantParameters["color"])
        assertEquals(1, decomposed.redirectDepth)
    }

    @Test
    fun rejectsArchiveAndSearchIntermediariesAsRetailerDestinations() {
        assertNull(extractDirectRetailerUrl("https://web.archive.org/web/20250101/https://shop.example/product"))
        assertNull(extractDirectRetailerUrl("https://bit.ly/unresolved-short-link"))
        assertFalse(newClient().isEligibleDirectUrl("https://www.googleadservices.com/pagead/aclk?adurl=https%3A%2F%2Fshop.example%2Fproduct"))
    }

    @Test
    fun directUrlCandidatesTrySlashVariantOnlyForQuerylessPages() {
        val client = newClient()
        val queryless = client.buildDirectUrlCandidates("https://shop.example/product/anker-prime")
        assertEquals(
            "Try the slash variant, but keep the exact archived target last for the lookup",
            listOf(
                "https://shop.example/product/anker-prime/",
                "https://shop.example/product/anker-prime"
            ),
            queryless
        )

        val variantUrl = "https://shop.example/product/anker-prime?sku=27k"
        assertEquals(
            "A meaningful product query must not be rewritten as a different slash URL",
            listOf(variantUrl),
            client.buildDirectUrlCandidates(variantUrl)
        )
    }

    @Test
    fun exactAppleSkuUrlAllowsGenericTitleButRejectsExplicitSiblingVariant() {
        val client = newClient()
        val expected = "Apple iPhone 15 256 GB Amarillo"
        val exactSkuUrl =
            "https://www.apple.com/es/shop/buy-iphone/iphone-15/pantalla-de-6,1%E2%80%B3-256gb-amarillo"
        val snapshotUrl = "https://web.archive.org/web/20240101000000/$exactSkuUrl"
        val genericTitleHtml = """
            <html><head><title>iPhone 15</title>
              <script type="application/ld+json">
                {"@type":"Product","name":"iPhone 15",
                 "offers":{"@type":"Offer","price":"699.00","priceCurrency":"EUR"}}
              </script>
            </head><body><h1>iPhone 15</h1></body></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = genericTitleHtml,
            expectedTitle = expected,
            originalUrl = exactSkuUrl,
            snapshotUrl = snapshotUrl,
            observedAt = 1704067200000L
        )
        assertNotNull(
            "An exact Apple SKU URL may verify a generic page title when the title omits colour and storage",
            quote
        )
        assertEquals(699.0, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)

        val siblingVariantHtml = """
            <html><head><title>iPhone 15 Plus 256 GB Amarillo</title>
              <script type="application/ld+json">
                {"@type":"Product","name":"iPhone 15 Plus 256 GB Amarillo",
                 "offers":{"@type":"Offer","price":"799.00","priceCurrency":"EUR"}}
              </script>
            </head><body><h1>iPhone 15 Plus 256 GB Amarillo</h1></body></html>
        """.trimIndent()
        assertNull(
            "The exact base-model URL must never accept an explicit iPhone 15 Plus offer",
            client.parseArchivedPrice(
                html = siblingVariantHtml,
                expectedTitle = expected,
                originalUrl = exactSkuUrl,
                snapshotUrl = snapshotUrl,
                observedAt = 1704067200000L
            )
        )
    }

    @Test
    fun explicitlyWrongVariantUrlIsDeferredButFamilyUrlRemainsEligible() {
        val client = newClient()
        val expected = "Apple iPhone 15 256 GB Amarillo"
        val baseModelUrl = "https://www.apple.com/es/shop/buy-iphone/iphone-15/"
        val plusModelUrl = "https://www.apple.com/es/shop/buy-iphone/iphone-15-plus/"

        assertFalse(
            "A family page that omits colour/storage is unknown, not an explicit mismatch",
            client.hasExplicitVariantConflictInProductUrl(expected, baseModelUrl)
        )
        assertTrue(
            "The iPhone 15 Plus URL must not be treated as the base iPhone 15",
            client.hasExplicitVariantConflictInProductUrl(expected, plusModelUrl)
        )
        assertTrue(
            "An URL with a different explicit capacity and colour must be flagged",
            client.hasExplicitVariantConflictInProductUrl(
                expected,
                "https://shop.example/iphone-15?capacity=128gb&color=black"
            )
        )
        assertFalse(
            "A URL whose explicit capacity and colour match remains eligible",
            client.hasExplicitVariantConflictInProductUrl(
                expected,
                "https://shop.example/iphone-15?capacity=256gb&color=yellow"
            )
        )

        val ordered = client.orderAvailabilityCandidateUrls(
            preferredUrl = plusModelUrl,
            generatedUrls = listOf(baseModelUrl),
            expectedTitle = expected
        )
        assertEquals(
            "A conflicting preferred URL must not consume the first archive probe",
            listOf(baseModelUrl, plusModelUrl),
            ordered
        )

        val variantSpecificUrl = "https://www.apple.com/es/iphone-15-256-gb-amarillo/"
        val specificFirst = client.orderAvailabilityCandidateUrls(
            preferredUrl = baseModelUrl,
            generatedUrls = listOf(
                "https://www.apple.com/es/iphone-15/",
                variantSpecificUrl
            ),
            expectedTitle = expected
        )
        assertEquals(
            "A URL that explicitly identifies the selected model, capacity and colour should outrank a generic family page",
            variantSpecificUrl,
            specificFirst.first()
        )

        val opaqueSkuUrl = "https://www.apple.com/es/shop/buy-iphone/iphone-15/?sku=SKU123456"
        val skuFirst = client.orderAvailabilityCandidateUrls(
            preferredUrl = opaqueSkuUrl,
            generatedUrls = listOf(variantSpecificUrl),
            expectedTitle = expected
        )
        assertEquals(
            "An opaque SKU query on the preferred URL remains a strong identity signal",
            opaqueSkuUrl,
            skuFirst.first()
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
        assertTrue(
            "Apple's archived purchase flow should be tried when its marketing page has no price metadata",
            appleCandidates.contains("https://www.apple.com/es/shop/buy-iphone/iphone-16/")
        )
        assertTrue(appleCandidates.size <= 6)
        assertTrue(appleCandidates.any { it.startsWith("https://www.apple.com/") })
        assertTrue(appleCandidates.any { it.startsWith("https://apple.com/") })

        val appleStorageColorCandidates = client.buildAvailabilityCandidateUrls(
            domain = "apple.com",
            expectedTitle = "Apple iPhone 15 128 GB Negro"
        )
        assertTrue(
            "The archived lookup must try the exact iPhone 15 storage/colour route before a generic family page",
            appleStorageColorCandidates.first().contains(
                "/shop/buy-iphone/iphone-15/pantalla-de-6,1%E2%80%B3-128gb-negro",
                ignoreCase = true
            )
        )
        assertTrue(
            "Try the exact SKU route on the apex host before falling back to a generic Apple page",
            appleStorageColorCandidates.getOrNull(1)?.startsWith("https://apple.com/es/shop/buy-iphone/iphone-15/") == true &&
                appleStorageColorCandidates[1].contains(
                    "/pantalla-de-6,1%E2%80%B3-128gb-negro",
                    ignoreCase = true
                )
        )
        assertFalse(
            "An exact SKU route has no trailing slash, matching Apple's published URL",
            appleStorageColorCandidates.first().endsWith("/")
        )
        assertTrue(
            "The generic purchase route remains available as a fallback",
            appleStorageColorCandidates.contains("https://www.apple.com/es/shop/buy-iphone/iphone-15/")
        )
        assertTrue(
            "The canonical marketing page remains as a fallback after the purchase route",
            appleStorageColorCandidates.contains("https://www.apple.com/es/iphone-15/")
        )

        val appleYellowCandidates = client.buildAvailabilityCandidateUrls(
            domain = "apple.com",
            expectedTitle = "Apple iPhone 15 256 GB Amarillo"
        )
        assertTrue(
            "The expected base iPhone 15 SKU must use the 6.1-inch yellow configurator route",
            appleYellowCandidates.first().contains(
                "/pantalla-de-6,1%E2%80%B3-256gb-amarillo",
                ignoreCase = true
            )
        )
        val applePlusCandidates = client.buildAvailabilityCandidateUrls(
            domain = "apple.com",
            expectedTitle = "Apple iPhone 15 Plus 256 GB Amarillo"
        )
        assertTrue(
            "The Plus SKU must use the 6.7-inch route, not the base model's 6.1-inch route",
            applePlusCandidates.first().contains(
                "/pantalla-de-6,7%E2%80%B3-256gb-amarillo",
                ignoreCase = true
            )
        )
        val orderedWithGenericSku = client.orderAvailabilityCandidateUrls(
            preferredUrl = "https://www.apple.com/es/shop/buy-iphone/iphone-15/?sku=SKU123456",
            generatedUrls = appleYellowCandidates,
            expectedTitle = "Apple iPhone 15 256 GB Amarillo"
        )
        assertTrue(
            "A matching Apple configurator route must outrank an opaque SKU attached to a generic family URL",
            orderedWithGenericSku.first().contains("/pantalla-de-6,1%E2%80%B3-256gb-amarillo", ignoreCase = true)
        )

        val pcCandidates = client.buildAvailabilityCandidateUrls(
            "pccomponentes.com",
            "Apple iPhone 16 128 GB Negro"
        )
        assertTrue(
            "A product-shaped URL must be tried before a generic retailer search page",
            !pcCandidates.first().contains("/search/")
        )
        val pcSearchIndex = pcCandidates.indexOfFirst {
            it.startsWith("https://www.pccomponentes.com/search/")
        }
        assertTrue(
            "The known retailer search URL must remain available after the first three product-route guesses",
            pcSearchIndex >= 3
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
        val samsungSearchUrl = com.example.data.model.Store.ALL_STORES
            .firstOrNull { it.domain.contains("samsung.com", ignoreCase = true) && it.supportsProductSearch }
            ?.searchUrlFor("Samsung Galaxy S25 Edge")
        if (samsungSearchUrl != null) {
            assertTrue(
                "The Samsung search page must remain available after canonical product URLs",
                samsungCandidates.indexOf(samsungSearchUrl) >= 2
            )
        }
    }

    @Test
    fun prioritizesCanonicalSamsungPathWhenSavedTitleOmitsGalaxy() {
        val client = newClient()

        val standardCandidates = client.buildAvailabilityCandidateUrls(
            domain = "samsung.com",
            expectedTitle = "Samsung S26"
        )
        assertEquals(
            "A shortened title should lead with the canonical Galaxy S26 page",
            "https://www.samsung.com/es/smartphones/galaxy-s/galaxy-s26/",
            standardCandidates.first()
        )

        val ultraCandidates = client.buildAvailabilityCandidateUrls(
            domain = "samsung.com",
            expectedTitle = "Samsung S26 Ultra"
        )
        assertEquals(
            "The model variant should be retained in the canonical product path",
            "https://www.samsung.com/es/smartphones/galaxy-s/galaxy-s26-ultra/",
            ultraCandidates.first()
        )
        assertTrue(standardCandidates.size <= 6)
        assertTrue(ultraCandidates.size <= 6)

        val storageColourUltraCandidates = client.buildAvailabilityCandidateUrls(
            domain = "samsung.com",
            expectedTitle = "Samsung Galaxy S26 Ultra 256GB Titanium Black"
        )
        assertEquals(
            "Samsung's canonical model URL must ignore storage and colour variants",
            "https://www.samsung.com/es/smartphones/galaxy-s/galaxy-s26-ultra/",
            storageColourUltraCandidates.first()
        )
    }

    @Test
    fun canonicalVariantSlugIsTriedBeforeStorageAndColourSpecificGuessForUnknownRetailer() {
        val client = newClient()
        val candidates = client.buildAvailabilityCandidateUrls(
            domain = "shop.example",
            expectedTitle = "Apple iPhone 15 128 GB Negro"
        )

        assertTrue("A bounded merchant candidate list should be created", candidates.isNotEmpty())
        assertEquals(
            "The canonical product slug should not contain storage or colour",
            "https://www.shop.example/apple-iphone-15/",
            candidates.first()
        )
        assertTrue(
            "The full variant-specific path must remain as a later fallback",
            candidates.any { it.contains("apple-iphone-15-128-gb-negro") }
        )
        assertTrue("Candidate fan-out must remain bounded", candidates.size <= 6)
    }

    @Test
    fun validEmptyProductPrefixCdxUsesAvailabilityWithoutBroadDomainScan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank Empty CDX Fallback ${System.currentTimeMillis()}"
        val preferredUrl = "https://shop.example/products/anker-prime-27k-empty-cdx.html"
        val requests = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
        val cdxMatchTypes = CopyOnWriteArrayList<String>()
        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val emptyCdxBody = """[["timestamp","original","statuscode","mimetype"]]"""
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
            when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    cdxMatchTypes += request.url.queryParameter("matchType").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(emptyCdxBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val originalUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("Availability request has no product URL")
                    availabilityUrls += originalUrl
                    val body = if (availabilityUrls.size == 1) {
                        """{"archived_snapshots":{"closest":{"available":false}}}"""
                    } else {
                        val snapshotUrl = "https://web.archive.org/web/20250101120000id_/$originalUrl"
                        """
                            {"archived_snapshots":{"closest":{
                              "available":true,"timestamp":"20250101120000","url":"$snapshotUrl"
                            }}}
                        """.trimIndent()
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
        }.build()

        try {
            val result = withTimeout(25_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    onTrace = { event -> traceEvents += event }
                )
            }
            val cdxRequestCount = requests.count { it == "web.archive.org/cdx/search/cdx" }
            assertTrue(
                "Product-prefix CDX requests must remain bounded (count=$cdxRequestCount, types=$cdxMatchTypes)",
                cdxRequestCount in 1..5
            )
            assertTrue(
                "An empty product-prefix result must not trigger broad host scans (types=$cdxMatchTypes)",
                cdxMatchTypes.isNotEmpty() && cdxMatchTypes.none { it.equals("host", ignoreCase = true) }
            )
            assertEquals("Known URL is the first bounded Availability candidate", preferredUrl, availabilityUrls.first())
            assertTrue("The second candidate must be tried after a valid empty response", availabilityUrls.size >= 2)
            assertFalse("A valid empty product query must not trigger the slow broad host scan",
                cdxMatchTypes.contains("host"))
            assertTrue("The fallback must recover at least one verified quote", result.quotes.isNotEmpty())
            assertTrue("All recovered captures must pass EUR price verification", result.quotes.all { kotlin.math.abs(it.price - 79.99) < 0.001 })
            assertTrue("Step 5 must classify an empty JSON CDX body as a valid response",
                traceEvents.any {
                    it.step == 5 && it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("0 captura(s)")
                })
            assertTrue("Step 6 must pass when the fallback recovered a verified price",
                traceEvents.any { it.step == 6 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 7 must report captures returned by Availability",
                traceEvents.any { it.step == 7 && it.status == WaybackTraceStatus.PASSED })
            assertTrue(
                "Availability captures must mark diagnostic stage 8 as executed even when CDX returned an empty index",
                traceEvents.any {
                    it.diagnosticStep == 8 && it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("Availability API devolvió")
                }
            )
            assertTrue(
                "Availability candidate URLs must be reflected in diagnostic stage 9",
                traceEvents.any {
                    it.diagnosticStep == 9 && it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("Candidatas Availability normalizadas")
                }
            )
            assertTrue(
                "Availability capture dates must be reflected in diagnostic stage 10",
                traceEvents.any {
                    it.diagnosticStep == 10 && it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("Fechas Availability validadas")
                }
            )
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun domainCdxTimeoutFallsBackToAvailabilityAndSavesVerifiedPrice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank Fallback Test ${System.currentTimeMillis()}"
        val requests = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val preferredUrl = "https://www.pccomponentes.com/anker-prime-27k-power-bank.html"
        val realStoreSearchUrl = com.example.data.model.Store.ALL_STORES
            .first { it.domain.contains("pccomponentes.com", ignoreCase = true) && it.supportsProductSearch }
            .searchUrlFor(title)!!
        val cdxQueriedUrls = CopyOnWriteArrayList<String>()
        val cdxSeriesBody = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","$realStoreSearchUrl","200","text/html"],
              ["20260901120000","$realStoreSearchUrl","200","text/html"]
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
            when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    val indexedUrl = request.url.queryParameter("url").orEmpty()
                    cdxQueriedUrls += indexedUrl
                    if (indexedUrl == realStoreSearchUrl) {
                        // Once Availability has confirmed the real retailer URL, CDX must be
                        // queried against that verified URL to retrieve multiple archive dates.
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/json; charset=utf-8")
                            .body(cdxSeriesBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                    } else {
                        // The guessed/direct product URL still reproduces the failure on-device.
                        throw java.net.SocketTimeoutException("simulated CDX product URL timeout")
                    }
                }
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val originalUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("Availability request has no product URL")
                    availabilityUrls += originalUrl
                    val availabilityProbeNumber = availabilityUrls.size
                    // First candidate simulates a transient per-URL timeout. The bounded
                    // fallback must continue to the second candidate instead of aborting early.
                    if (availabilityProbeNumber == 1) {
                        // Reproduce a real elapsed timeout, rather than an instant exception.
                        Thread.sleep(3_500L)
                        throw java.net.SocketTimeoutException("simulated first Availability URL timeout")
                    }
                    val body = if (originalUrl == realStoreSearchUrl) {
                        val snapshotUrl = "https://web.archive.org/web/20250101120000id_/$originalUrl"
                        """
                            {"archived_snapshots":{"closest":{
                              "available":true,"timestamp":"20250101120000","url":"$snapshotUrl"
                            }}}
                        """.trimIndent()
                    } else {
                        // Product-shaped guesses without a capture must be exhausted before
                        // the generic search page is tried as the fifth bounded candidate.
                        """{"archived_snapshots":{}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    // Reproduce an archived page that takes longer than the old 3-second timeout.
                    Thread.sleep(3_500L)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
        }.build()

        try {
            val result = withTimeout(45_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "pccomponentes.com",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    onTrace = { event -> traceEvents += event }
                )
            }
            assertTrue(
                "A product-specific CDX timeout must still try the bounded Availability API",
                requests.contains("archive.org/wayback/available")
            )
            assertEquals("The exact saved retailer URL must be the first Availability candidate", preferredUrl, availabilityUrls.first())
            assertEquals(
                "Transient CDX timeouts must keep Availability fallback bounded to three URLs",
                3,
                availabilityUrls.size
            )
            assertEquals(
                "The real retailer search URL should be the final fallback after product-shaped guesses",
                realStoreSearchUrl,
                availabilityUrls.last()
            )
            assertTrue("The verified archived EUR price should be recovered after a 3.5-second HTML response", result.quotes.isNotEmpty())
            assertTrue("Every accepted result must retain the verified EUR price", result.quotes.all { kotlin.math.abs(it.price - 79.99) < 0.001 })
            assertTrue(
                "The verified Availability URL must be queried through CDX to recover more than one date",
                cdxQueriedUrls.contains(realStoreSearchUrl) &&
                    result.quotes.map { it.observedAt }.distinct().size >= 2
            )
            assertTrue(
                "Diagnostic steps 5–6 must record the CDX series request instead of remaining unexecuted",
                traceEvents.any { it.step == 4 && it.status == WaybackTraceStatus.PASSED &&
                    it.detail.contains("CDX_AVAILABILITY_SERIES") } &&
                    traceEvents.any { it.step == 5 && it.detail.contains("CDX_AVAILABILITY_SERIES") }
            )
            assertTrue(requests.any { it.startsWith("web.archive.org/web/") })
            assertTrue(
                "Step 8 must be marked blocked/skipped when CDX failed upstream; step 6 keeps the actual timeout",
                traceEvents.any {
                    it.diagnosticStep == 8 &&
                        it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("Extracción no ejecutada: CDX no entregó una respuesta utilizable") &&
                        it.detail.contains("CDX_PRODUCT_PREFIX_TIMEOUT")
                }
            )
            assertTrue(
                "The Availability capture belongs to the fallback phase",
                traceEvents.any {
                    it.diagnosticStep == 7 &&
                        it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("Availability encontró 1 captura(s)") &&
                        it.detail.contains("DOMAIN_AVAILABILITY_FALLBACK_TIMEOUT")
                }
            )
            assertTrue(
                "A slow snapshot that succeeds must be recorded as downloaded HTML",
                traceEvents.any {
                    diagnosticIndexForWaybackTrace(it) == 11 &&
                        it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("HTML recibido")
                }
            )
            assertTrue(
                "Step 13 must explain when a second capture does not exist",
                traceEvents.any {
                    it.diagnosticStep == 13 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("solo se recuperó 1 captura")
                }
            )
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun triesAlternateProductCdxAfterAvailabilityReturnsNoCaptures() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank Alternate CDX Recovery " + System.currentTimeMillis()
        val preferredUrl = "https://shop.example/products/anker-prime-27k-original.html"
        val client = WaybackPriceHistoryClient(context, OkHttpClient())
        val alternateUrl = client.buildAvailabilityCandidateUrls("shop.example", title)
            .firstOrNull { it != preferredUrl }
            ?: throw AssertionError("Expected at least one product URL variant for the CDX fallback")
        val cdxTargets = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val archivedHtml = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"Product","name":"$title",
                 "offers":{"@type":"Offer","price":"79.99","priceCurrency":"EUR"}}
              </script>
            </head><body></body></html>
        """.trimIndent()
        val cdxBody = """
            [
              ["timestamp","original","statuscode","mimetype"],
              ["20250101120000","$alternateUrl","200","text/html"]
            ]
        """.trimIndent()

        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    availabilityUrls += request.url.queryParameter("url").orEmpty()
                    val body = """{"archived_snapshots":{"closest":{"available":false}}}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    cdxTargets += request.url.queryParameter("url").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(cdxBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: " + request.url.host + request.url.encodedPath)
            }
        }.build()

        try {
            val result = withTimeout(20_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    preferredUrlAlreadyQueried = true,
                    onTrace = { event -> traceEvents += event }
                )
            }

            assertTrue("The bounded Availability fallback must be attempted first", availabilityUrls.size >= 2)
            assertEquals("A different product-shaped URL must be queried through CDX", alternateUrl, cdxTargets.last())
            assertEquals(79.99, result.quotes.single().price, 0.001)
            assertEquals(alternateUrl, result.quotes.single().originalUrl)
            assertTrue(
                "The alternate CDX result must be parsed into a real capture",
                traceEvents.any {
                    it.diagnosticStep == 8 && it.status == WaybackTraceStatus.PASSED &&
                        it.detail.contains("CDX parseado correctamente")
                }
            )
            assertTrue(
                "If only one capture exists, step 13 must explain the skip rather than claim the chain stopped",
                traceEvents.any {
                    it.diagnosticStep == 13 && it.status == WaybackTraceStatus.SKIPPED &&
                        it.detail.contains("solo había 1 captura válida")
                }
            )
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun availabilityEmptyPlusAlternateCdxTimeoutIsCachedAsFailureNotSixHourNegative() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime timeout must retry " + System.currentTimeMillis()
        val preferredUrl = "https://shop.example/products/anker-prime-timeout-cache.html"
        val client = WaybackPriceHistoryClient(context)
        val decomposedPreferredUrl = client.decomposeProductUrl(preferredUrl)
        val preferredUrlIdentity = decomposedPreferredUrl?.canonicalUrl ?: preferredUrl
        val preferredUrlIdentifiers = decomposedPreferredUrl?.productIdentifiers
            .orEmpty().sorted().joinToString(",")
        val rawKey = "domain-cdx-batch-v9-url-discovery|shop.example|$title|$preferredUrlIdentity|$preferredUrlIdentifiers"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        val attemptKey = "attempt_" + digest.take(16).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val requestHosts = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requestHosts += request.url.host + request.url.encodedPath
            when {
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val body = """{"archived_snapshots":{"closest":{"available":false}}}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") ->
                    throw java.net.SocketTimeoutException("simulated alternate CDX timeout")
                else -> throw AssertionError("Unexpected Wayback request: " + request.url)
            }
        }.build()

        try {
            val result = withTimeout(20_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    preferredUrlAlreadyQueried = true
                )
            }

            assertTrue("Availability empty + CDX timeout is a partial transport failure", result.requestFailed)
            assertTrue(
                "The combined lookup must retain the alternate CDX timeout diagnosis",
                result.diagnosticCodes.any { it.contains("TIMEOUT") }
            )
            assertTrue(
                "The parent domain attempt must be marked as failed, not as a clean negative result",
                preferences.getBoolean(attemptKey + "_failed", false)
            )
            assertEquals(
                "After the alternate product-prefix timeout, do not fan out into broad www/apex CDX scans",
                1,
                requestHosts.count { it == "web.archive.org/cdx/search/cdx" }
            )
            val beforeRetry = requestHosts.size
            val immediateRetry = WaybackPriceHistoryClient(
                context,
                OkHttpClient.Builder().addInterceptor {
                    throw AssertionError("A failed lookup is briefly cached to avoid a rapid repeat")
                }.build()
            ).fetchHistoryByMerchantDomain(
                domain = "shop.example",
                expectedTitle = title,
                lookbackDays = 730,
                preferredUrl = preferredUrl,
                preferredUrlAlreadyQueried = true
            )
            assertTrue("The immediate repeat should be tagged as a failed-attempt cache skip", immediateRetry.skippedRecently)
            assertTrue(immediateRetry.diagnosticCodes.contains("CACHE_SKIPPED_AFTER_FAILURE"))
            assertTrue("The first attempt must have contacted both services", beforeRetry >= 3)
        } finally {
            preferences.edit()
                .remove(attemptKey)
                .remove(attemptKey + "_has_quotes")
                .remove(attemptKey + "_failed")
                .remove(attemptKey + "_rate_limited")
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun partialAvailabilityTimeoutPlusEmptyCdxDoesNotBecomeLongNegativeCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker partial availability failure " + System.currentTimeMillis()
        val preferredUrl = "https://shop.example/products/anker-partial-timeout.html"
        val client = WaybackPriceHistoryClient(context)
        val decomposedPreferredUrl = client.decomposeProductUrl(preferredUrl)
        val preferredUrlIdentity = decomposedPreferredUrl?.canonicalUrl ?: preferredUrl
        val preferredUrlIdentifiers = decomposedPreferredUrl?.productIdentifiers
            .orEmpty().sorted().joinToString(",")
        val rawKey = "domain-cdx-batch-v9-url-discovery|shop.example|$title|$preferredUrlIdentity|$preferredUrlIdentifiers"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        val attemptKey = "attempt_" + digest.take(16).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val availabilityRequests = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val count = availabilityRequests.incrementAndGet()
                    if (count == 1) {
                        throw java.net.SocketTimeoutException("simulated first Availability timeout")
                    }
                    val body = """{"archived_snapshots":{"closest":{"available":false}}}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    val emptyIndex = """[["timestamp","original","statuscode","mimetype"]]"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(emptyIndex.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: " + request.url)
            }
        }.build()

        try {
            val result = withTimeout(20_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    preferredUrlAlreadyQueried = true
                )
            }

            assertTrue("The fixture must include both a timeout and valid empty API responses", availabilityRequests.get() >= 2)
            assertTrue(
                "A timeout from one Availability URL must remain a transient failure even if other URLs return empty",
                result.requestFailed
            )
            assertTrue(
                "The parent attempt must be eligible for a short retry instead of a six-hour negative cache",
                preferences.getBoolean(attemptKey + "_failed", false)
            )
            assertTrue(result.diagnosticCodes.any { it.contains("TIMEOUT") })
        } finally {
            preferences.edit()
                .remove(attemptKey)
                .remove(attemptKey + "_has_quotes")
                .remove(attemptKey + "_failed")
                .remove(attemptKey + "_rate_limited")
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun usesPreferredAvailabilityUrlWhenBroadDomainCdxFilterCannotBeBuilt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Cafetera"
        val preferredUrl = "https://shop.example/products/cafetera"
        val now = System.currentTimeMillis()
        val capturedAt = now - 2L * 60L * 60L * 1000L
        val timestamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(capturedAt))
        val archivedSnapshotUrl = "https://web.archive.org/web/" + timestamp + "id_/" + preferredUrl
        val broadCdxRequests = java.util.concurrent.atomic.AtomicInteger()
        val availabilityRequests = java.util.concurrent.atomic.AtomicInteger()
        val emptyCdxBody = """[["timestamp","original","statuscode","mimetype"]]"""
        val archivedHtml = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"Product","name":"Cafetera",
                 "offers":{"@type":"Offer","price":"99.99","priceCurrency":"EUR"}}
              </script>
            </head><body><h1>Cafetera</h1></body></html>
        """.trimIndent()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    availabilityRequests.incrementAndGet()
                    val requestedUrl = request.url.queryParameter("url").orEmpty()
                    val body = if (requestedUrl == preferredUrl) {
                        """{"archived_snapshots":{"closest":{"available":true,"timestamp":"$timestamp","url":"$archivedSnapshotUrl"}}}"""
                    } else {
                        """{"archived_snapshots":{"closest":{"available":false}}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    if (request.url.queryParameter("matchType").equals("host", ignoreCase = true)) {
                        broadCdxRequests.incrementAndGet()
                        throw AssertionError("A one-word title must never trigger a broad host scan")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(emptyCdxBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected archive request: " + request.url.host + request.url.encodedPath)
            }
        }.build()

        try {
            val client = WaybackPriceHistoryClient(context, http)
            assertEquals(
                "A one-word title has no safe broad-domain URL filter",
                null,
                client.buildDomainCdxHostUrls("shop.example", title, now = now, lookbackDays = 730)
            )
            val result = withTimeout(20_000L) {
                client.fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    now = now,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    preferredUrlAlreadyQueried = true
                )
            }

            assertEquals("A one-word title must not trigger broad host scans", 0, broadCdxRequests.get())
            assertTrue("The direct retailer URL must still be checked with Availability", availabilityRequests.get() >= 1)
            assertEquals(1, result.quotes.size)
            assertEquals(99.99, result.quotes.single().price, 0.001)
            assertEquals(preferredUrl, result.quotes.single().originalUrl)
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun domainCdxHttp500FallsBackToAvailabilityAndSavesVerifiedPrice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Power Bank HTTP 500 Recovery ${System.currentTimeMillis()}"
        val preferredUrl = "https://shop.example/products/anker-prime-27k-http-500-recovery.html"
        val requests = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
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
            when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Temporary index failure")
                        .body("temporary index failure".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val originalUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("Availability request has no product URL")
                    availabilityUrls += originalUrl
                    val body = if (availabilityUrls.size == 1) {
                        val snapshotUrl = "https://web.archive.org/web/20250101120000id_/$originalUrl"
                        """
                            {"archived_snapshots":{"closest":{
                              "available":true,"timestamp":"20250101120000","url":"$snapshotUrl"
                            }}}
                        """.trimIndent()
                    } else {
                        """{"archived_snapshots":{"closest":{"available":false}}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
        }.build()

        try {
            val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
            val result = withTimeout(10_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    onTrace = { event -> traceEvents += event }
                )
            }
            assertTrue("A transient CDX HTTP 500 must activate the bounded Availability fallback",
                requests.contains("archive.org/wayback/available"))
            assertEquals("The known offer URL must be tried first", preferredUrl, availabilityUrls.first())
            assertEquals("The archived EUR price must be verified", 79.99, result.quotes.single().price, 0.001)
            assertTrue("The original HTTP 500 must remain visible in diagnostics",
                traceEvents.any {
                    it.step == 5 && it.status == WaybackTraceStatus.FAILED &&
                        it.detail.contains("CDX_PRODUCT_PREFIX_HTTP_500")
                })
            assertTrue("The fallback must complete successfully",
                traceEvents.any { it.step == 6 && it.status == WaybackTraceStatus.PASSED })
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun inferredMerchantUrlUsesAvailabilityFirstThenCdxForMultipleHistoricalDates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Availability First ${System.currentTimeMillis()}"
        val requests = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
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
            when {
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val originalUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("Availability request has no product URL")
                    availabilityUrls += originalUrl
                    val body = if (availabilityUrls.size == 1) {
                        val snapshotUrl = "https://web.archive.org/web/20250101120000id_/$originalUrl"
                        """
                            {"archived_snapshots":{"closest":{
                              "available":true,"timestamp":"20250101120000","url":"$snapshotUrl"
                            }}}
                        """.trimIndent()
                    } else {
                        """{"archived_snapshots":{"closest":{"available":false}}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(archivedHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    // Availability only supplies the closest snapshot. After one verified point,
                    // CDX must be queried to recover a dated series instead of returning early.
                    val indexedUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("CDX request has no product URL")
                    val body = """
                        [
                          ["timestamp","original","statuscode","mimetype"],
                          ["20250101120000","$indexedUrl","200","text/html"],
                          ["20261001120000","$indexedUrl","200","text/html"]
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
        }.build()

        try {
            val result = withTimeout(25_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730
                )
            }
            assertTrue("The inferred seller URL must be checked through Availability", availabilityUrls.isNotEmpty())
            assertEquals("The first archive network call must be Availability, not CDX",
                "archive.org/wayback/available", requests.first())
            assertTrue("A single Availability quote must trigger CDX discovery for other dates",
                requests.any { it == "web.archive.org/cdx/search/cdx" })
            assertTrue("CDX must add distinct historical dates to the Availability observation",
                result.quotes.map { it.observedAt }.distinct().size >= 2)
            assertTrue(result.quotes.all { kotlin.math.abs(it.price - 79.99) < 0.001 })
            assertTrue(result.quotes.all { it.currency == "EUR" })
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
    }

    @Test
    fun resolvesPhoneHouseSellerWhenShoppingOnlyProvidesAnIntermediaryUrl() {
        val domain = newClient().resolveMerchantDomain(
            storeName = "Phone House",
            sourceUrl = "https://www.google.com/shopping/product/123456"
        )
        assertEquals("A known mobile retailer must resolve to its own domain, not Xiaomi/Apple brand fallback",
            "phonehouse.es", domain)
    }

    @Test
    fun domainFallbackContinuesWhenFirstCaptureHasNoVerifiedPrice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("wayback_rate_limit_until")
            .remove("wayback_last_availability_request_at")
            .commit()

        val title = "Anker Prime 27K Cookie Wall Test ${System.currentTimeMillis()}"
        val preferredUrl = "https://shop.example/products/anker-prime-27k.html"
        val requests = CopyOnWriteArrayList<String>()
        val availabilityUrls = CopyOnWriteArrayList<String>()
        val traceEvents = CopyOnWriteArrayList<WaybackTraceEvent>()
        val validHtml = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"Product","name":"$title",
                 "offers":{"@type":"Offer","price":"79.99","priceCurrency":"EUR"}}
              </script>
            </head><body></body></html>
        """.trimIndent()
        val cookieWallHtml = """
            <html><head><title>Just a moment...</title></head>
            <body><h1>DDoS protection</h1>
              Enable JavaScript and cookies to continue.
            </body></html>
        """.trimIndent()

        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val hostAndPath = request.url.host + request.url.encodedPath
            requests += hostAndPath
            when {
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/cdx/search/cdx") -> {
                    throw java.net.SocketTimeoutException("simulated CDX domain timeout")
                }
                request.url.host == "archive.org" &&
                    request.url.encodedPath == "/wayback/available" -> {
                    val originalUrl = request.url.queryParameter("url")
                        ?: throw AssertionError("Availability request has no product URL")
                    availabilityUrls += originalUrl
                    // The quarterly sampler deliberately keeps only one capture per quarter.
                    // Use two independent quarters so the cookie-wall capture and the later valid
                    // capture both survive sampling and the parser is tested for real.
                    val snapshotTimestamp = if (availabilityUrls.size == 1) {
                        "20250101120000"
                    } else {
                        "20250401120000"
                    }
                    val snapshotUrl = "https://web.archive.org/web/${snapshotTimestamp}id_/$originalUrl"
                    val body = """
                        {"archived_snapshots":{"closest":{
                          "available":true,"timestamp":"$snapshotTimestamp","url":"$snapshotUrl"
                        }}}
                    """.trimIndent()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                request.url.host == "web.archive.org" &&
                    request.url.encodedPath.startsWith("/web/") -> {
                    val snapshotNumber = requests.count { it.startsWith("web.archive.org/web/") }
                    val html = if (snapshotNumber == 1) cookieWallHtml else validHtml
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
                else -> throw AssertionError("Unexpected Wayback request: $hostAndPath")
            }
        }.build()

        try {
            val result = withTimeout(25_000L) {
                WaybackPriceHistoryClient(context, http).fetchHistoryByMerchantDomain(
                    domain = "shop.example",
                    expectedTitle = title,
                    lookbackDays = 730,
                    preferredUrl = preferredUrl,
                    onTrace = { event -> traceEvents += event }
                )
            }
            assertTrue("The direct offer URL must be the first candidate", availabilityUrls.first() == preferredUrl)
            assertTrue("The bounded second URL candidate must be queried", availabilityUrls.size >= 2)
            assertTrue(
                "A cookie wall at the first snapshot must not hide a valid price in the second",
                requests.count { it.startsWith("web.archive.org/web/") } >= 2
            )
            assertEquals(79.99, result.quotes.single().price, 0.001)
            assertTrue("Step 4 records that the CDX request was sent",
                traceEvents.any { it.step == 4 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 5 identifies the simulated product-specific CDX timeout",
                traceEvents.any {
                    it.step == 5 && it.status == WaybackTraceStatus.FAILED &&
                        it.detail.contains("CDX_PRODUCT_PREFIX_TIMEOUT")
                })
            assertTrue("Step 6 records the fallback completing successfully",
                traceEvents.any { it.step == 6 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 7 records captures returned by the fallback",
                traceEvents.any { it.step == 7 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 8 records the first HTML download",
                traceEvents.any { it.step == 8 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 9 pinpoints the cookie/challenge barrier",
                traceEvents.any {
                    it.step == 9 && it.status == WaybackTraceStatus.FAILED &&
                        it.detail.contains("CLOUDFLARE_CHALLENGE")
                })
            assertTrue("Step 10 records the second HTML download",
                traceEvents.any { it.step == 10 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 11 accepts the second HTML without a barrier",
                traceEvents.any { it.step == 11 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 12 verifies the product title",
                traceEvents.any { it.step == 12 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 13 finds a structured price",
                traceEvents.any { it.step == 13 && it.status == WaybackTraceStatus.PASSED })
            assertTrue("Step 14 verifies a EUR price",
                traceEvents.any { it.step == 14 && it.status == WaybackTraceStatus.PASSED })
        } finally {
            preferences.edit()
                .remove("wayback_rate_limit_until")
                .remove("wayback_last_availability_request_at")
                .commit()
        }
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
    fun eightYearWindowUsesCalendarAnniversaryAndIncludesBoundaryDayFromMidnightUtc() {
        val client = newClient()
        val now = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("20261021163000")!!.time

        val start = client.historyWindowStartTimestamp(
            now = now,
            lookbackDays = WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS
        )
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = start
        }
        assertEquals(2018, utc.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.OCTOBER, utc.get(java.util.Calendar.MONTH))
        assertEquals(21, utc.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(0, utc.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, utc.get(java.util.Calendar.MINUTE))
        assertEquals(0, utc.get(java.util.Calendar.SECOND))
        assertEquals(0, utc.get(java.util.Calendar.MILLISECOND))
    }

    @Test
    fun defaultHistoryWindowUsesTheEightYearUtcCalendarAnniversary() {
        val now = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("20261021163000")!!.time
        val boundary = archiveHistoryWindowStartTimestampUtc(now, WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS)
        val formatted = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(boundary))
        assertEquals("20181021000000", formatted)
    }

    @Test
    fun directCdxRequestKeepsTheNewestBoundedMonthlyWindow() {
        val client = newClient()
        val now = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("20261021000000")!!.time

        val url = client.buildCdxUrl(
            originalUrl = "https://shop.example/products/anker-prime",
            now = now,
            lookbackDays = 2_920
        )

        assertNotNull(url)
        assertEquals("-120", url!!.queryParameter("limit"))
        assertEquals("timestamp:6", url.queryParameter("collapse"))
        assertEquals("statuscode:200", url.queryParameterValues("filter")?.firstOrNull())
        assertEquals("mimetype:text/html", url.queryParameterValues("filter")?.getOrNull(1))
        assertEquals("20261021", url.queryParameter("to"))
        assertEquals(
            "The CDX query must include the whole UTC boundary date eight calendar years ago",
            "20181021",
            url.queryParameter("from")
        )

        val exactUrl = client.buildCdxUrl(
            originalUrl = "https://shop.example/products/anker-prime",
            now = now,
            lookbackDays = 2_920,
            matchType = "exact"
        )
        assertEquals("exact", exactUrl?.queryParameter("matchType"))
        assertEquals("prefix", url.queryParameter("matchType"))
    }

    @Test
    fun cdxSamplerKeepsQuarterCoverageAndUsesUnusedSlotsForDenseQuarters() {
        val client = newClient()
        val formatter = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            isLenient = false
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val rawDates = listOf(
            "20240115000000", "20240215000000",
            "20240415000000", "20240515000000",
            "20240715000000", "20240815000000",
            "20241015000000", "20241115000000",
            "20250115000000", "20250215000000",
            "20250415000000", "20250515000000",
            "20250715000000", "20250815000000",
            "20251015000000", "20251115000000"
        )
        val captures = rawDates.map { raw ->
            WaybackPriceHistoryClient.WaybackCapture(
                rawTimestamp = raw,
                timestamp = formatter.parse(raw)!!.time,
                originalUrl = "https://shop.example/product/item"
            )
        }

        val fullQuarterSeries = client.sampleCaptures(captures, max = 16)
        assertEquals("The sampler should use the full download budget when extra dated captures exist", 16, fullQuarterSeries.size)
        val quarters = fullQuarterSeries.map { capture ->
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = capture.timestamp
            }
            calendar.get(java.util.Calendar.YEAR) * 4 + calendar.get(java.util.Calendar.MONTH) / 3
        }
        assertEquals("Every archived quarter should remain represented", 8, quarters.distinct().size)

        val boundedSeries = client.sampleCaptures(captures, max = 4)
        assertEquals("The HTTP/download budget remains a hard upper bound", 4, boundedSeries.size)
        val quarterOf: (WaybackPriceHistoryClient.WaybackCapture) -> Int = { capture ->
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = capture.timestamp
            }
            calendar.get(java.util.Calendar.YEAR) * 4 + calendar.get(java.util.Calendar.MONTH) / 3
        }
        assertEquals("The oldest quarter should remain represented", quarterOf(fullQuarterSeries.first()), quarterOf(boundedSeries.first()))
        assertEquals("The newest quarter should remain represented", quarterOf(fullQuarterSeries.last()), quarterOf(boundedSeries.last()))
    }

    @Test
    fun cdxSamplerKeepsMultipleDatesWhenAllCapturesFallWithinOneQuarter() {
        val client = newClient()
        val formatter = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
            isLenient = false
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val captures = listOf(
            "20260501000000", "20260515000000", "20260601000000", "20260615000000"
        ).map { raw ->
            WaybackPriceHistoryClient.WaybackCapture(
                rawTimestamp = raw,
                timestamp = formatter.parse(raw)!!.time,
                originalUrl = "https://shop.example/product/item"
            )
        }

        val sampled = client.sampleCaptures(captures, max = 4)

        assertEquals("Do not collapse several dated captures to one point when budget remains", 4, sampled.size)
        assertEquals(captures.map { it.rawTimestamp }, sampled.map { it.rawTimestamp })
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
    fun multiOfferParserRoutesOnlyUnambiguousSecondarySavedProduct() {
        val client = newClient()
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@graph":[
                  {"@type":"Product","name":"Anker Prime 27K Power Bank",
                   "offers":{"@type":"Offer","price":"89.99","priceCurrency":"EUR"}},
                  {"@type":"Product","name":"Anker 737 Power Bank 24K",
                   "offers":{"@type":"Offer","price":"69.99","priceCurrency":"EUR"}}
                ]
              }
              </script>
            </head></html>
        """.trimIndent()
        val originalUrl = "https://www.pccomponentes.com/anker-power-bank-family"
        val snapshotUrl = "https://web.archive.org/web/20260901000000id_/$originalUrl"
        val document = Jsoup.parse(html)
        val quotes = client.parseArchivedPricesForKnownProducts(
            document = document,
            primaryExpectedTitle = "Anker Prime 27K Power Bank",
            knownProductTitlesById = mapOf(
                "product-prime" to "Anker Prime 27K Power Bank",
                "product-737" to "Anker 737 Power Bank 24K"
            ),
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            observedAt = 1_788_200_000_000L
        )

        assertEquals("The primary item must not be duplicated as a secondary variant", 1, quotes.size)
        assertEquals("product-737", quotes.single().targetProductId)
        assertEquals("Anker 737 Power Bank 24K", quotes.single().productTitle)
        assertEquals(69.99, quotes.single().price, 0.001)
        assertEquals("EUR", quotes.single().currency)
    }

    @Test
    fun secondaryOfferUsesItsOwnSkuUrlWhenFamilyPageTitleOmitsCapacity() {
        val client = newClient()
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@graph":[
                  {"@type":"Product","name":"Apple iPhone 15 256GB Amarillo",
                   "url":"https://www.apple.com/es/shop/buy-iphone/iphone-15/pantalla-de-6,1%22-256gb-amarillo",
                   "offers":{"@type":"Offer","price":"899.99","priceCurrency":"EUR"}},
                  {"@type":"Product","name":"Apple iPhone 15 Plus",
                   "url":"https://www.pccomponentes.com/apple-iphone-15-plus-256gb",
                   "offers":{"@type":"Offer","price":"799.99","priceCurrency":"EUR"}}
                ]
              }
              </script>
            </head></html>
        """.trimIndent()
        val originalUrl =
            "https://www.apple.com/es/shop/buy-iphone/iphone-15/pantalla-de-6,1%22-256gb-amarillo"
        val snapshotUrl = "https://web.archive.org/web/20260901000000id_/$originalUrl"

        val quotes = client.parseArchivedPricesForKnownProducts(
            document = Jsoup.parse(html),
            primaryExpectedTitle = "Apple iPhone 15 256GB Amarillo",
            knownProductTitlesById = mapOf(
                "base-yellow-256" to "Apple iPhone 15 256GB Amarillo",
                "plus-unknown" to "Apple iPhone 15 Plus",
                "plus-256" to "Apple iPhone 15 Plus 256GB"
            ),
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            observedAt = 1_788_200_000_000L
        )

        assertEquals("The offer URL should route the generic heading only to the matching capacity", 1, quotes.size)
        assertEquals("plus-256", quotes.single().targetProductId)
        assertEquals("Apple iPhone 15 Plus 256GB", quotes.single().productTitle)
        assertEquals(799.99, quotes.single().price, 0.001)
        assertTrue(quotes.single().variantIdentityConfirmedByUrl)
    }

    @Test
    fun secondaryOfferUrlCannotAssign256GbPriceTo128GbOrUnknownCapacityRows() {
        val client = newClient()
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@type":"Product",
                "name":"Apple iPhone 15 Plus",
                "url":"https://www.pccomponentes.com/apple-iphone-15-plus-128gb",
                "offers":{"@type":"Offer","price":"699.99","priceCurrency":"EUR"}
              }
              </script>
            </head></html>
        """.trimIndent()
        val originalUrl =
            "https://www.apple.com/es/shop/buy-iphone/iphone-15/pantalla-de-6,1%22-256gb-amarillo"
        val quotes = client.parseArchivedPricesForKnownProducts(
            document = Jsoup.parse(html),
            primaryExpectedTitle = "Apple iPhone 15 256GB Amarillo",
            knownProductTitlesById = mapOf(
                "plus-unknown" to "Apple iPhone 15 Plus",
                "plus-256" to "Apple iPhone 15 Plus 256GB"
            ),
            originalUrl = originalUrl,
            snapshotUrl = "https://web.archive.org/web/20260901000000id_/$originalUrl",
            observedAt = 1_788_200_000_000L
        )
        assertTrue("A 128 GB URL must not populate a 256 GB or unspecified-capacity product", quotes.isEmpty())
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
    fun acceptsGenericArchivedHeadingOnlyWhenUrlConfirmsTheMissingStorage() {
        val client = newClient()
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@type":"Product",
                "name":"Apple iPhone 15 Plus",
                "offers":{"@type":"Offer","price":"799.99","priceCurrency":"EUR"}
              }
              </script>
            </head></html>
        """.trimIndent()
        val originalUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb"
        val snapshotUrl =
            "https://web.archive.org/web/20250101120000id_/$originalUrl"
        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Apple iPhone 15 Plus 256GB",
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A generic heading is acceptable when the archived URL proves the exact capacity", quote)
        assertEquals(799.99, quote!!.price, 0.001)
        assertTrue("The relaxed title match must be explicitly marked as URL-verified",
            quote.variantIdentityConfirmedByUrl)

        val colourSpecificUrl =
            "https://www.pccomponentes.com/apple-iphone-15-plus-256gb-amarillo"
        val colourSpecificQuote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Apple iPhone 15 Plus 256GB Amarillo",
            originalUrl = colourSpecificUrl,
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/$colourSpecificUrl",
            observedAt = 1_735_732_800_000L
        )
        assertNotNull(
            "A generic archived heading is usable when the captured URL proves both capacity and colour",
            colourSpecificQuote
        )
        assertTrue(colourSpecificQuote!!.variantIdentityConfirmedByUrl)

        val genericUrl = "https://www.pccomponentes.com/apple-iphone-15-plus"
        val genericSnapshot = "https://web.archive.org/web/20250101120000id_/$genericUrl"
        assertNull(
            "A generic page URL must not lend a price to a specific capacity",
            client.parseArchivedPrice(
                html = html,
                expectedTitle = "Apple iPhone 15 Plus 256GB",
                originalUrl = genericUrl,
                snapshotUrl = genericSnapshot,
                observedAt = 1_735_732_800_000L
            )
        )
        assertNull(
            "A generic colour heading requires the URL to prove the requested colour as well",
            client.parseArchivedPrice(
                html = html,
                expectedTitle = "Apple iPhone 15 Plus 256GB Amarillo",
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl,
                observedAt = 1_735_732_800_000L
            )
        )
        assertNull(
            "A Plus page must never match the base iPhone 15",
            client.parseArchivedPrice(
                html = html,
                expectedTitle = "Apple iPhone 15 256GB",
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl,
                observedAt = 1_735_732_800_000L
            )
        )
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
    fun parsesExplicitUsdMetadataAsSourceCurrencyForConversionPipeline() {
        val client = newClient()
        val capturedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("2025-01-03")!!.time
        val html = """
            <html><head>
              <meta property="og:title" content="Anker Prime 27K Power Bank">
              <meta property="product:price:amount" content="100.00">
              <meta property="product:price:currency" content="USD">
              <script type="application/ld+json">
                {"@type":"Product","name":"Anker Prime 27K Power Bank",
                 "offers":{"@type":"Offer","price":"100.00","priceCurrency":"USD"}}
              </script>
            </head></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            document = Jsoup.parse(html),
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/product/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250103000000id_/https://shop.example/product/anker-prime-27k",
            observedAt = capturedAt,
            acceptedCurrencies = setOf("EUR", "USD")
        )

        assertNotNull("Explicit USD metadata is recognized only for the conversion stage", quote)
        assertEquals("USD", quote!!.currency)
        assertEquals(100.0, quote.price, 0.001)
    }

    @Test
    fun prefersOriginalEurOfferOverUsdMetadataWhenBothAreAvailable() {
        val client = newClient()
        val capturedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("2025-01-03")!!.time
        val html = """
            <html><head>
              <meta property="og:title" content="Anker Prime 27K Power Bank">
              <meta property="product:price:amount" content="100.00">
              <meta property="product:price:currency" content="USD">
              <script type="application/ld+json">
                {"@type":"Product","name":"Anker Prime 27K Power Bank",
                 "offers":{"@type":"Offer","price":"94.99","priceCurrency":"EUR"}}
              </script>
            </head></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            document = Jsoup.parse(html),
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/product/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250103000000id_/https://shop.example/product/anker-prime-27k",
            observedAt = capturedAt,
            acceptedCurrencies = setOf("EUR", "USD")
        )
        assertNotNull(quote)
        assertEquals("EUR", quote!!.currency)
        assertEquals(94.99, quote.price, 0.001)
    }

    @Test
    fun leavesUsdPriceUnconvertedWhenTheHistoricalRateIsMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val cacheKey = "fx_usd_eur_capture_2025-01-06"
        preferences.edit().remove(cacheKey + "_rate").remove(cacheKey + "_date").commit()
        val events = CopyOnWriteArrayList<WaybackTraceEvent>()
        val fxHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body("[]".toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.build()
        val capturedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("2025-01-06")!!.time
        val sourceQuote = ArchivedPriceQuote(
            observedAt = capturedAt,
            price = 100.0,
            currency = "USD",
            productTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/product/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250106000000id_/https://shop.example/product/anker-prime-27k"
        )
        try {
            val converted = WaybackPriceHistoryClient(context, fxHttp = fxHttp)
                .convertUsdQuoteToEurAtCaptureDate(sourceQuote) { events += it }
            assertNull("No historical rate means no guessed conversion", converted)
            assertTrue(events.any {
                it.diagnosticStep == 17 && it.status == WaybackTraceStatus.FAILED &&
                    it.detail.contains("Precio descartado")
            })
        } finally {
            preferences.edit().remove(cacheKey + "_rate").remove(cacheKey + "_date").commit()
        }
    }

    @Test
    fun convertsUsdUsingTheLatestHistoricalEcbRateNotLaterThanCaptureDate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val cacheKey = "fx_usd_eur_capture_2025-01-03"
        preferences.edit().remove(cacheKey + "_rate").remove(cacheKey + "_date").commit()
        val requests = CopyOnWriteArrayList<String>()
        val fxHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath + "?" + request.url.encodedQuery
            val body = """
                [
                  {"date":"2025-01-02","base":"USD","quote":"EUR","rate":0.96},
                  {"date":"2025-01-04","base":"USD","quote":"EUR","rate":0.99}
                ]
            """.trimIndent()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.build()
        val capturedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("2025-01-03")!!.time
        val sourceQuote = ArchivedPriceQuote(
            observedAt = capturedAt,
            price = 100.0,
            currency = "USD",
            productTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/product/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250103000000id_/https://shop.example/product/anker-prime-27k"
        )

        try {
            val converted = WaybackPriceHistoryClient(context, fxHttp = fxHttp)
                .convertUsdQuoteToEurAtCaptureDate(sourceQuote)
            assertNotNull(converted)
            assertEquals(96.0, converted!!.price, 0.001)
            assertEquals("EUR", converted.currency)
            assertEquals("USD", converted.sourceCurrency)
            assertEquals(100.0, converted.sourcePrice, 0.001)
            assertEquals(0.96, converted.exchangeRate!!, 0.0001)
            assertEquals("2025-01-02", converted.exchangeRateDate)
            assertEquals("Frankfurter/ECB", converted.conversionSource)
            assertTrue("Historical request must be limited to the capture date",
                requests.single().contains("to=2025-01-03"))
            assertTrue("Historical request must use USD as its base currency",
                requests.single().contains("base=USD"))
        } finally {
            preferences.edit().remove(cacheKey + "_rate").remove(cacheKey + "_date").commit()
        }
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
        assertFalse(
            "Credentialed URLs must never be sent to the public archive",
            client.isEligibleDirectUrl("https://user:secret@shop.example/product")
        )
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
            lookbackDays = 2920
        )
        assertNotNull(url)
        assertEquals("host", url!!.queryParameter("matchType"))
        assertEquals("20", url.queryParameter("limit"))
        assertNull("Host discovery avoids slow collapse processing", url.queryParameter("collapse"))
        assertEquals("20190115", url.queryParameter("from"))
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
        assertEquals(
            "A blank seller label must still resolve from the saved store domain",
            "pixmania.com",
            client.resolveMerchantDomain("", googleUrl, "https://www.pixmania.com/es/")
        )
        assertEquals(
            "A blank seller label must still resolve from a verified direct retailer URL",
            "pccomponentes.com",
            client.resolveMerchantDomain("", "https://www.pccomponentes.com/smartphones")
        )
        assertEquals("sprinter.es", client.resolveMerchantDomain("Sprinter Deportes", googleUrl))
        assertEquals(
            "A bad saved domain label must not override a recognised merchant name",
            "apple.com",
            client.resolveMerchantDomain("Apple", googleUrl, "Google Shopping")
        )
        assertNull(
            "A provider label is not a valid store domain even when saved in Store.domain",
            client.resolveMerchantDomain("Google Shopping", googleUrl, "Google Shopping")
        )

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
    fun usesMatchingSecondaryTitleAndReadsUnquotedMetaAttributes() {
        val client = newClient()
        val html = """
            <html><head>
              <meta property=og:title content="PC Componentes | Tecnología y ofertas" />
              <meta name=twitter:title content="Anker Prime 27K Power Bank" />
              <meta property=product:price:amount content=79.99 />
              <meta property=product:price:currency content=EUR />
            </head><body></body></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://www.pccomponentes.com/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.pccomponentes.com/anker-prime-27k",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A generic og:title must not hide a matching twitter:title", quote)
        assertEquals(79.99, quote!!.price, 0.001)
        assertEquals("Anker Prime 27K Power Bank", quote.productTitle)
    }

    @Test
    fun parsesJsonLdWhenScriptTypeAttributeIsUnquoted() {
        val client = newClient()
        val html = """
            <html><head>
              <script type=application/ld+json>
                {"@type":"Product","name":"Anker Prime 27K Power Bank",
                 "offers":{"@type":"Offer","price":"89.99","priceCurrency":"EUR"}}
              </script>
            </head><body></body></html>
        """.trimIndent()

        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "Anker Prime 27K Power Bank",
            originalUrl = "https://shop.example/products/anker-prime-27k",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/anker-prime-27k",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("Valid product JSON-LD can use an unquoted MIME attribute", quote)
        assertEquals(89.99, quote!!.price, 0.001)
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
    fun parsesXiaomiMi10ProWhenArchivedColourIsTranslatedAndPriceUsesMicrodata() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <meta property="og:title" content="Mi 10 Pro 5G Grey - Xiaomi">
                  <meta itemprop="priceCurrency" content="EUR">
                </head><body>
                  <div itemprop="offers">
                    <span itemprop="price">183,71</span>
                  </div>
                </body></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Mi 10 Pro Gris",
            originalUrl = "https://www.mi.com/es/product/mi-10-pro/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.mi.com/es/product/mi-10-pro/",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A matching Xiaomi Mi model with translated grey colour and Schema.org microdata should parse", quote)
        assertEquals(183.71, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun parsesXiaomiMi10ProAggregateOfferLowPrice() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head><title>Xiaomi Mi 10 Pro 5G Gray</title>
                <script type="application/ld+json">
                {"@type":"Product","name":"Xiaomi Mi 10 Pro 5G Gray",
                 "offers":{"@type":"AggregateOffer","lowPrice":"183.71",
                           "highPrice":"219.00","priceCurrency":"EUR"}}
                </script></head></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Mi 10 Pro Gris",
            originalUrl = "https://www.mi.com/es/product/mi-10-pro/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.mi.com/es/product/mi-10-pro/",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("AggregateOffer.lowPrice is a real structured offer value", quote)
        assertEquals(183.71, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun rejectsXiaomiMiOfferUrlForDifferentModelWhenArchiveTitleOmitsCapacity() {
        val client = newClient()
        val primaryUrl = "https://shop.example/products/anker-prime-27k"
        val wrongModelUrl = "https://shop.example/products/xiaomi-mi-11-pro-128gb"
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@graph":[
                  {"@type":"Product","name":"Anker Prime 27K Power Bank",
                   "url":"$primaryUrl",
                   "offers":{"@type":"Offer","price":"299.99","priceCurrency":"EUR"}},
                  {"@type":"Product","name":"Xiaomi 10 Pro",
                   "url":"$wrongModelUrl",
                   "offers":{"@type":"Offer","price":"199.99","priceCurrency":"EUR"}}
                ]
              }
              </script>
            </head></html>
        """.trimIndent()

        val quotes = client.parseArchivedPricesForKnownProducts(
            document = Jsoup.parse(html),
            primaryExpectedTitle = "Anker Prime 27K Power Bank",
            knownProductTitlesById = mapOf("mi10-128" to "Xiaomi Mi 10 Pro 128GB"),
            originalUrl = primaryUrl,
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/$primaryUrl",
            observedAt = 1_735_732_800_000L
        )

        assertTrue(
            "A Mi 11 offer URL cannot validate the saved Xiaomi Mi 10 product even if the archived title omits capacity",
            quotes.none { it.targetProductId == "mi10-128" }
        )
    }

    @Test
    fun rejectsXiaomiMi10ProCaptureWithExplicitlyDifferentColour() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Product","name":"Xiaomi Mi 10 Pro 5G Black",
                 "offers":{"@type":"Offer","price":"183.71","priceCurrency":"EUR"}}
                </script></head></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Mi 10 Pro Gris",
            originalUrl = "https://www.mi.com/es/product/mi-10-pro/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.mi.com/es/product/mi-10-pro/",
            observedAt = 1_735_732_800_000L
        )

        assertNull("A grey product must not accept a capture explicitly labelled black", quote)
    }

    @Test
    fun readsScooterTitleFromH1AndVisibleEuroPriceWithoutStructuredMetadata() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head></head><body>
                  <div class="pdp-product-title">
                    <h1 itemprop="name">Mi Electric Scooter 4 Ultra Grey</h1>
                  </div>
                  <div class="product-price"><span class="price__amount">449,99</span> €</div>
                </body></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Electric Scooter 4 Ultra Gris",
            originalUrl = "https://www.mi.com/es/product/xiaomi-electric-scooter-4-ultra/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.mi.com/es/product/xiaomi-electric-scooter-4-ultra/",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("H1 title plus a scoped visible EUR price should work without JSON-LD", quote)
        assertEquals(449.99, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun choosesCurrentScooterPriceInsteadOfCrossedOutPreviousPrice() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><body>
                  <h1>Xiaomi Mi Electric Scooter 4 Ultra Grey</h1>
                  <div class="product-price">
                    <del class="old-price">499,99 €</del>
                    <span class="current-price">449,99 €</span>
                  </div>
                </body></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Electric Scooter 4 Ultra Gris",
            originalUrl = "https://shop.example/xiaomi-electric-scooter-4-ultra",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/xiaomi-electric-scooter-4-ultra",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull(quote)
        assertEquals("The displayed selling price must win over crossed-out list price", 449.99, quote!!.price, 0.001)
    }

    @Test
    fun rejectsScooterProCaptureForExpectedUltraVariant() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <script type="application/ld+json">
                {"@type":"Product","name":"Xiaomi Mi Electric Scooter 4 Pro Black",
                 "offers":{"@type":"Offer","price":"399.99","priceCurrency":"EUR"}}
                </script>
            """.trimIndent(),
            expectedTitle = "Xiaomi Electric Scooter 4 Ultra Black",
            originalUrl = "https://shop.example/products/xiaomi-electric-scooter-4",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/xiaomi-electric-scooter-4",
            observedAt = 1_735_732_800_000L
        )

        assertNull("A similar product family is not enough when Pro/Ultra variants differ", quote)
    }

    @Test
    fun rejectsXboxSeriesXCaptureForExpectedSeriesS() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <script type="application/ld+json">
                {"@type":"Product","name":"Xbox Series X 1TB",
                 "offers":{"@type":"Offer","price":"499.99","priceCurrency":"EUR"}}
                </script>
            """.trimIndent(),
            expectedTitle = "Xbox Series S 1TB",
            originalUrl = "https://shop.example/xbox-series-s",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/xbox-series-s",
            observedAt = 1_735_732_800_000L
        )
        assertNull("Xbox Series S and Series X are separate product models", quote)
    }

    @Test
    fun rejectsNvidiaSuperCaptureForExpectedTiGraphicsCard() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <script type="application/ld+json">
                {"@type":"Product","name":"NVIDIA GeForce RTX 4070 Super",
                 "offers":{"@type":"Offer","price":"649.99","priceCurrency":"EUR"}}
                </script>
            """.trimIndent(),
            expectedTitle = "NVIDIA GeForce RTX 4070 Ti",
            originalUrl = "https://shop.example/rtx-4070-ti",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/rtx-4070-ti",
            observedAt = 1_735_732_800_000L
        )
        assertNull("RTX 4070 Ti and RTX 4070 Super are different variants", quote)
    }

    @Test
    fun rejectsSamsungGalaxyS26BaseCaptureForExpectedPlusVariant() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <script type="application/ld+json">
                {"@type":"Product","name":"Samsung Galaxy S26",
                 "offers":{"@type":"Offer","price":"899.99","priceCurrency":"EUR"}}
                </script>
            """.trimIndent(),
            expectedTitle = "Samsung Galaxy S26+",
            originalUrl = "https://shop.example/samsung-galaxy-s26-plus",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/samsung-galaxy-s26-plus",
            observedAt = 1_735_732_800_000L
        )

        assertNull("Galaxy S26+ must not be confused with the base Galaxy S26", quote)
    }

    @Test
    fun parsesProductAndOfferLinkedByIdInsideJsonLdGraph() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                <script type="application/ld+json">
                {
                  "@context":"https://schema.org",
                  "@graph":[
                    {"@type":"Product","@id":"https://shop.example/items/scooter#product",
                     "name":"Xiaomi Electric Scooter 4 Ultra Black"},
                    {"@type":"Offer","itemOffered":{"@id":"https://shop.example/items/scooter#product"},
                     "price":"449.99","priceCurrency":"EUR"}
                  ]
                }
                </script></head></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Mi Electric Scooter 4 Ultra Black",
            originalUrl = "https://shop.example/items/scooter",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/items/scooter",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A product linked to a separate Offer in JSON-LD @graph should be recognized", quote)
        assertEquals(449.99, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun inheritsEurCurrencyFromProductWhenJsonLdOfferOmitsCurrency() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head><title>Xiaomi Mi 10 Pro</title>
                <script type="application/ld+json">
                {"@type":"Product","name":"Xiaomi Mi 10 Pro","priceCurrency":"EUR",
                 "offers":{"@type":"Offer","price":"183.71"}}
                </script></head></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Mi 10 Pro",
            originalUrl = "https://shop.example/xiaomi-mi-10-pro",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/xiaomi-mi-10-pro",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("An inherited product currency should be applied to its Offer", quote)
        assertEquals(183.71, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun archivedProductVariantCannotBorrowMatchingGenericPageMetadata() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <meta property="og:title" content="Apple iPhone 15 256 GB Amarillo">
                  <meta property="product:price:amount" content="399.99">
                  <meta property="product:price:currency" content="EUR">
                  <script type="application/ld+json">
                    {"@type":"Product","name":"Apple iPhone 15 Plus 128 GB Negro",
                     "offers":{"@type":"Offer","price":"299.99","priceCurrency":"EUR"}}
                  </script>
                </head><body>
                  <h1>Apple iPhone 15 Plus 128 GB Negro</h1>
                  <div class="product-price">299,99 €</div>
                </body></html>
            """.trimIndent(),
            expectedTitle = "Apple iPhone 15 256 GB Amarillo",
            originalUrl = "https://shop.example/product/iphone-15?sku=yellow-256",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/iphone-15?sku=yellow-256",
            observedAt = 1_735_732_800_000L
        )

        assertNull(
            "A generic/stale page title must not override structured metadata for another colour, capacity and trim",
            quote
        )
    }

    @Test
    fun matchingIphone15OfferIsAcceptedWhenSiblingIphone15PlusOfferAlsoExists() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <title>iPhone 15 y iPhone 15 Plus</title>
                  <script type="application/ld+json">
                    {
                      "@graph": [
                        {
                          "@type": "Product",
                          "name": "Apple iPhone 15 256 GB Amarillo",
                          "offers": {"@type":"Offer","price":"499.00","priceCurrency":"EUR"}
                        },
                        {
                          "@type": "Product",
                          "name": "Apple iPhone 15 Plus 128 GB Negro",
                          "offers": {"@type":"Offer","price":"599.00","priceCurrency":"EUR"}
                        }
                      ]
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
            expectedTitle = "Apple iPhone 15 256 GB Amarillo",
            originalUrl = "https://shop.example/product/iphone-15?sku=yellow-256",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/iphone-15?sku=yellow-256",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A sibling Plus offer must not invalidate an exact matching base-model offer", quote)
        assertEquals(499.00, quote!!.price, 0.001)
        assertEquals("Apple iPhone 15 256 GB Amarillo", quote.productTitle)
        assertEquals("EUR", quote.currency)
    }

    @Test
    fun acceptsExactIphoneModelAndCapacityWhenArchivedTitleOmitsColour() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <title>iPhone 15</title>
                  <script type="application/ld+json">
                    {
                      "@type": "Product",
                      "name": "Apple iPhone 15 256 GB",
                      "offers": {"@type":"Offer","price":"499.00","priceCurrency":"EUR"}
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
            expectedTitle = "Apple iPhone 15 256 GB Amarillo",
            originalUrl = "https://shop.example/product/iphone-15?sku=yellow-256",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/iphone-15?sku=yellow-256",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("A missing archived colour is not a conflict when the model and capacity match", quote)
        assertEquals(499.00, quote!!.price, 0.001)
        assertTrue(quote.productTitle.contains("256 GB"))
    }

    @Test
    fun archivedUsdOfferIsRejectedEvenWhenThePageTitleMatches() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <meta property="og:title" content="Apple iPhone 15 256 GB Amarillo">
                  <meta property="product:price:amount" content="499.00">
                  <meta property="product:price:currency" content="USD">
                  <script type="application/ld+json">
                    {"@type":"Product","name":"Apple iPhone 15 256 GB Amarillo",
                     "offers":{"@type":"Offer","price":"499.00","priceCurrency":"USD"}}
                  </script>
                </head><body>
                  <h1>Apple iPhone 15 256 GB Amarillo</h1>
                  <div class="product-price" data-currency="USD">$499.00</div>
                </body></html>
            """.trimIndent(),
            expectedTitle = "Apple iPhone 15 256 GB Amarillo",
            originalUrl = "https://shop.example/product/iphone-15?sku=yellow-256",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/product/iphone-15?sku=yellow-256",
            observedAt = 1_735_732_800_000L
        )

        assertNull("USD must never be stored as EUR historical price", quote)
    }

    @Test
    fun archivedMetaTitleDecodesNumericSpanishHtmlEntities() {
        val client = newClient()
        val quote = client.parseArchivedPrice(
            html = """
                <html><head>
                  <meta property="og:title" content="Xiaomi Redmi 17 Edici&#243;n Especial">
                  <meta property="product:price:amount" content="299,99">
                  <meta property="product:price:currency" content="EUR">
                </head><body></body></html>
            """.trimIndent(),
            expectedTitle = "Xiaomi Redmi 17 Edición Especial",
            originalUrl = "https://shop.example/products/xiaomi-redmi-17",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/xiaomi-redmi-17",
            observedAt = 1_735_732_800_000L
        )

        assertNotNull("Numeric HTML entities must be decoded before product-title matching", quote)
        assertEquals(299.99, quote!!.price, 0.001)
        assertEquals("EUR", quote.currency)
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
                "A failed network request stays briefly cached to avoid amplifying server throttling",
                client.wasAttemptedRecently(key, lastAttempt + 60_000L)
            )
            assertFalse(
                "A failed network request can be retried after the two-minute transient-failure window",
                client.wasAttemptedRecently(key, lastAttempt + 3L * 60L * 1000L)
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
            "Missing or invalid Retry-After headers use a short five-minute safety pause",
            now + 5L * 60L * 1000L,
            client.computeRateLimitUntil("not-a-date", now)
        )
    }

    @Test
    fun repeatedRateLimitsIncreaseCooldownAndRemainCappedAtSixHours() {
        val client = newClient()
        val now = 1_800_000_000_000L
        val minute = 60_000L
        val hour = 60L * minute

        assertEquals(now + 5L * minute, client.computeAdaptiveRateLimitUntil(null, now, 1))
        assertEquals(now + 10L * minute, client.computeAdaptiveRateLimitUntil(null, now, 2))
        assertEquals(now + 20L * minute, client.computeAdaptiveRateLimitUntil(null, now, 3))
        assertEquals(
            "A server Retry-After longer than the local escalation remains authoritative",
            now + hour,
            client.computeAdaptiveRateLimitUntil("3600", now, 2)
        )
        assertEquals(
            "Repeated HTTP 429 responses must never pause the app for more than six hours",
            now + 6L * hour,
            client.computeAdaptiveRateLimitUntil(null, now, 99)
        )
    }

    @Test
    fun activeRateLimitUntilExposesOnlyFutureCooldown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val client = newClient()
        val now = System.currentTimeMillis()
        try {
            preferences.edit().putLong("wayback_rate_limit_until", now + 120_000L).commit()
            assertEquals(
                "The UI can read the persisted retry time",
                now + 120_000L,
                client.activeRateLimitUntil(now)
            )
            assertNull(
                "An expired cooldown must not block a later user retry",
                client.activeRateLimitUntil(now + 120_000L)
            )
        } finally {
            preferences.edit().remove("wayback_rate_limit_until").commit()
        }
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
    fun expandedDiagnosticMapsLegacyClientStagesToTheirDetailedUiPhases() {
        assertEquals(5, diagnosticIndexForWaybackTrace(WaybackTraceEvent(4, WaybackTraceStatus.PASSED, "CDX enviada")))
        assertEquals(6, diagnosticIndexForWaybackTrace(WaybackTraceEvent(5, WaybackTraceStatus.PASSED, "Respuesta válida")))
        assertEquals(7, diagnosticIndexForWaybackTrace(WaybackTraceEvent(6, WaybackTraceStatus.PASSED, "Fallback")))
        assertEquals(8, diagnosticIndexForWaybackTrace(WaybackTraceEvent(7, WaybackTraceStatus.PASSED, "Capturas extraídas")))
        assertEquals(11, diagnosticIndexForWaybackTrace(WaybackTraceEvent(8, WaybackTraceStatus.PASSED, "HTML 1")))
        assertEquals(12, diagnosticIndexForWaybackTrace(WaybackTraceEvent(9, WaybackTraceStatus.PASSED, "Barreras 1")))
        assertEquals(13, diagnosticIndexForWaybackTrace(WaybackTraceEvent(10, WaybackTraceStatus.PASSED, "HTML 2")))
        assertEquals(14, diagnosticIndexForWaybackTrace(WaybackTraceEvent(11, WaybackTraceStatus.PASSED, "Barreras 2")))
        assertEquals(15, diagnosticIndexForWaybackTrace(WaybackTraceEvent(12, WaybackTraceStatus.PASSED, "Identidad")))
        assertEquals(16, diagnosticIndexForWaybackTrace(WaybackTraceEvent(13, WaybackTraceStatus.PASSED, "Precio")))
        assertEquals(17, diagnosticIndexForWaybackTrace(WaybackTraceEvent(14, WaybackTraceStatus.PASSED, "EUR")))
    }

    @Test
    fun supersededCdxSkipIsRecognizedAsStaleAfterSnapshotFailure() {
        assertTrue(
            isSupersededWaybackSkipDetail(
                "[14:22:26] No se pudo comprobar la identidad: CDX no proporcionó ninguna captura para descargar."
            )
        )
    }

    @Test
    fun additionalDiagnosticPhasesCanBeMappedWithoutChangingInternalTransportPhase() {
        val candidateUrls = WaybackTraceEvent(
            step = 7,
            status = WaybackTraceStatus.PASSED,
            detail = "URL candidatas únicas: 2",
            diagnosticStep = 9
        )
        val timeWindow = WaybackTraceEvent(
            step = 7,
            status = WaybackTraceStatus.PASSED,
            detail = "Fechas dentro del período: 3",
            diagnosticStep = 10
        )
        val consolidated = WaybackTraceEvent(
            step = 14,
            status = WaybackTraceStatus.PASSED,
            detail = "Precios verificados: 2; duplicados: 0",
            diagnosticStep = 18
        )
        assertEquals(7, candidateUrls.step)
        assertEquals(9, diagnosticIndexForWaybackTrace(candidateUrls))
        assertEquals(10, diagnosticIndexForWaybackTrace(timeWindow))
        assertEquals(18, diagnosticIndexForWaybackTrace(consolidated))
    }

    @Test
    fun stepSixDoesNotMarkRecoverySuccessfulWhenAvailabilityFindsNoCaptures() {
        val result = summarizeCdxStep5Events(
            events = listOf(
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.FAILED,
                    detail = "CDX_URL_PREFIX: CDX_URL_PREFIX_TIMEOUT.",
                    durationMs = 7_001L
                )
            ),
            availabilityRecovered = true,
            availabilityCapturesFound = 0
        )

        assertNotNull(result)
        assertEquals(WaybackTraceStatus.FAILED, result!!.status)
        assertTrue(result.detail.contains("Availability respondió, pero no encontró capturas utilizables"))
        assertTrue(result.detail.contains("no significa que se haya recuperado histórico"))
    }

    @Test
    fun stepFiveFailsRecoveryWhenValidCdxResponseContainsNoCapturesAndFallbackTimesOut() {
        val result = summarizeCdxStep5Events(
            listOf(
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.RUNNING,
                    detail = "CDX_URL_PREFIX: esperando respuesta."
                ),
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.PASSED,
                    detail = "CDX_URL_PREFIX: respuesta válida; 0 captura(s) candidatas.",
                    durationMs = 692L
                ),
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.RUNNING,
                    detail = "CDX_DOMAIN: esperando respuesta."
                ),
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.FAILED,
                    detail = "CDX_DOMAIN: CDX_DOMAIN_TIMEOUT.",
                    durationMs = 7_008L
                )
            )
        )

        assertNotNull(result)
        assertEquals(WaybackTraceStatus.FAILED, result!!.status)
        assertTrue(result.detail.contains("1 respuesta(s) CDX válida(s)"))
        assertTrue(result.detail.contains("0 captura(s) candidata(s)"))
        assertTrue(result.detail.contains("CDX_DOMAIN_TIMEOUT"))
        assertTrue(result.detail.contains("no hay histórico que validar"))
    }

    @Test
    fun stepFiveFailsWhenEveryCdxRequestFailsBeforeAValidResponse() {
        val result = summarizeCdxStep5Events(
            listOf(
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.FAILED,
                    detail = "CDX_URL_PREFIX: CDX_URL_PREFIX_TIMEOUT.",
                    durationMs = 5_000L
                ),
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.FAILED,
                    detail = "CDX_PRODUCT_PREFIX: CDX_PRODUCT_PREFIX_HTTP_500.",
                    durationMs = 900L
                )
            )
        )

        assertNotNull(result)
        assertEquals(WaybackTraceStatus.FAILED, result!!.status)
        assertTrue(result.detail.contains("2 intento(s)"))
        assertTrue(result.detail.contains("CDX_PRODUCT_PREFIX_HTTP_500"))
    }

    @Test
    fun stepSixReportsRecoveryWhenCdxTimesOutButAvailabilityFindsCaptures() {
        val result = summarizeCdxStep5Events(
            events = listOf(
                WaybackTraceEvent(
                    step = 5,
                    status = WaybackTraceStatus.FAILED,
                    detail = "CDX_PRODUCT_PREFIX: CDX_PRODUCT_PREFIX_TIMEOUT.",
                    durationMs = 5_002L
                )
            ),
            availabilityRecovered = true,
            availabilityCapturesFound = 1
        )

        assertNotNull(result)
        assertEquals(WaybackTraceStatus.PASSED, result!!.status)
        assertEquals(6, diagnosticIndexForWaybackTrace(result))
        assertTrue(result.detail.contains("CDX_PRODUCT_PREFIX_TIMEOUT"))
        assertTrue(result.detail.contains("Availability encontró 1 captura(s) para validar"))
        assertTrue(result.detail.contains("verifican su identidad y precio"))
    }

    @Test
    fun stepFiveUsesLatestValidResponseAndKeepsAdditionalFailureVisible() {
        val result = summarizeCdxStep5Events(
            listOf(
                WaybackTraceEvent(5, WaybackTraceStatus.PASSED,
                    "CDX_URL_PREFIX: respuesta válida; 3 captura(s) candidatas.", 800L),
                WaybackTraceEvent(5, WaybackTraceStatus.PASSED,
                    "CDX_PRODUCT_PREFIX: respuesta válida; 1 captura(s) candidatas.", 950L),
                WaybackTraceEvent(5, WaybackTraceStatus.FAILED,
                    "CDX_DOMAIN: CDX_DOMAIN_HTTP_500.", 1_100L)
            )
        )

        assertNotNull(result)
        assertEquals(WaybackTraceStatus.PASSED, result!!.status)
        assertTrue(result.detail.contains("2 respuesta(s) CDX válida(s)"))
        assertTrue(result.detail.contains("1 captura(s) candidata(s)"))
        assertTrue(result.detail.contains("CDX_DOMAIN_HTTP_500"))
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
    fun snapshotHttpClientAppliesTimeoutToConnectReadAndWholeCall() {
        val configured = newClient().buildSnapshotHttpClient(8L)
        assertEquals("Connection timeout must not remain at the base client's 4 seconds", 8_000, configured.connectTimeoutMillis)
        assertEquals("HTML read timeout must use the same bounded budget", 8_000, configured.readTimeoutMillis)
        assertEquals("Whole snapshot call timeout must use the same bounded budget", 8_000, configured.callTimeoutMillis)
    }

    @Test
    fun snapshotTimeoutSkipsRedundantAvailabilityFallbackButNotWhenNoCaptureWasFound() {
        val timedOutCaptures = WaybackBackfillResult(
            capturesFound = 3,
            requestFailed = true,
            diagnosticCodes = setOf("SNAPSHOT_TIMEOUT", "ALL_SNAPSHOTS_FAILED")
        )
        assertTrue(shouldSkipDomainFallbackAfterSnapshotTimeout(listOf(timedOutCaptures)))

        val noCaptures = timedOutCaptures.copy(capturesFound = 0)
        assertFalse(shouldSkipDomainFallbackAfterSnapshotTimeout(listOf(noCaptures)))

        val verifiedPartialPrice = timedOutCaptures.copy(
            quotes = listOf(
                ArchivedPriceQuote(
                    observedAt = 1_700_000_000_000L,
                    price = 79.99,
                    currency = "EUR",
                    productTitle = "Producto de prueba",
                    originalUrl = "https://shop.example/product",
                    snapshotUrl = "https://web.archive.org/web/20231114000000/https://shop.example/product"
                )
            )
        )
        assertFalse(shouldSkipDomainFallbackAfterSnapshotTimeout(listOf(verifiedPartialPrice)))
    }

    @Test
    fun identicalWaybackCaptureUrlAndTimestampShareOneSnapshotCacheKey() {
        val client = newClient()
        val url = "https://www.pccomponentes.com/producto"
        val first = WaybackPriceHistoryClient.WaybackCapture(
            rawTimestamp = "20250101120000",
            timestamp = 1_735_732_800_000L,
            originalUrl = url
        )
        val sameCapture = first.copy()
        val differentDate = first.copy(
            rawTimestamp = "20250901120000",
            timestamp = 1_756_732_800_000L
        )
        assertEquals(client.snapshotCacheKey(first), client.snapshotCacheKey(sameCapture))
        assertFalse(client.snapshotCacheKey(first) == client.snapshotCacheKey(differentDate))
    }

    @Test
    fun snapshotDiagnosticLabelDistinguishesEveryCaptureDateRouteAndStoreHost() {
        val client = newClient()
        val capture = WaybackPriceHistoryClient.WaybackCapture(
            rawTimestamp = "20250901123456",
            timestamp = 1_756_716_896_000L,
            originalUrl = "https://www.pccomponentes.com/producto/privado?sku=12345"
        )

        val label = client.snapshotDiagnosticLabel(
            capture = capture,
            ordinal = 4,
            total = 12,
            sourceStage = "CDX_URL_PREFIX"
        )

        assertTrue(label.contains("captura 4 de 12"))
        assertTrue(Regex("""id [a-j]{8}""").containsMatchIn(label))
        assertTrue(label.contains("fecha 20250901123456"))
        assertTrue(label.contains("ruta CDX_URL_PREFIX"))
        assertTrue(label.contains("tienda www.pccomponentes.com"))
        assertFalse("Diagnostic labels must not leak the product path or query", label.contains("/producto/privado"))
        assertFalse("Diagnostic labels must not leak the SKU query parameter", label.contains("sku=12345"))

        val anotherRouteLabel = client.snapshotDiagnosticLabel(
            capture = capture.copy(originalUrl = "https://www.pccomponentes.com/otro-producto?sku=67890"),
            ordinal = 5,
            total = 12,
            sourceStage = "CDX_ALTERNATE_PREFIX"
        )
        val labelId = Regex("""id ([a-j]{8})""").find(label)!!.groupValues[1]
        val otherLabelId = Regex("""id ([a-j]{8})""").find(anotherRouteLabel)!!.groupValues[1]
        assertFalse("Capture IDs must distinguish two URLs even when their dates match", labelId == otherLabelId)
        assertTrue(anotherRouteLabel.contains("ruta CDX_ALTERNATE_PREFIX"))
        assertFalse(anotherRouteLabel.contains("67890"))
    }

    @Test
    fun prefixCaptureSamplingPrefersUnprocessedDatesAndReusesCacheWhenAllOverlap() {
        val client = newClient()
        val first = WaybackPriceHistoryClient.WaybackCapture(
            rawTimestamp = "20250101120000",
            timestamp = 1_735_732_800_000L,
            originalUrl = "https://www.pccomponentes.com/producto"
        )
        val second = first.copy(
            rawTimestamp = "20250901120000",
            timestamp = 1_756_732_800_000L
        )
        val third = first.copy(
            rawTimestamp = "20260101120000",
            timestamp = 1_767_254_400_000L
        )
        val processed = setOf(client.snapshotCacheKey(first), client.snapshotCacheKey(second))

        assertEquals(
            "A prefix result must spend its download budget on new captures first",
            listOf(third),
            client.sampleUnprocessedCaptures(listOf(first, second, third), 3, processed)
        )
        assertEquals(
            "If every capture is cached, return cached captures so their results can be reused",
            listOf(first, second),
            client.sampleUnprocessedCaptures(listOf(first, second), 3, processed)
        )
    }

    @Test
    fun snapshotDnsFailureRetriesAtMostOnceAndCanRecover() = runBlocking {
        val client = newClient()
        var attempts = 0
        var retryChecks = 0
        val result = client.retrySnapshotDnsOnce(
            request = {
                attempts++
                if (attempts == 1) throw java.net.UnknownHostException("temporary DNS failure")
                "recovered"
            },
            beforeRetry = {
                retryChecks++
                true
            }
        )
        assertEquals("recovered", result)
        assertEquals(2, attempts)
        assertEquals(1, retryChecks)

        attempts = 0
        retryChecks = 0
        try {
            client.retrySnapshotDnsOnce(
                request = {
                    attempts++
                    throw java.net.UnknownHostException("persistent DNS failure")
                },
                beforeRetry = {
                    retryChecks++
                    true
                }
            )
            throw AssertionError("Persistent DNS failure must be reported after one retry")
        } catch (_: java.net.UnknownHostException) {
            // Expected: one original request plus one retry, never an unbounded loop.
        }
        assertEquals(2, attempts)
        assertEquals(1, retryChecks)

        attempts = 0
        retryChecks = 0
        val wrappedResult = client.retrySnapshotDnsOnce(
            request = {
                attempts++
                if (attempts == 1) throw java.io.IOException(
                    "connection setup failed",
                    java.net.UnknownHostException("wrapped temporary DNS failure")
                )
                "recovered wrapped DNS"
            },
            beforeRetry = {
                retryChecks++
                true
            }
        )
        assertEquals("recovered wrapped DNS", wrappedResult)
        assertEquals("Wrapped DNS failures must receive the same single bounded retry", 2, attempts)
        assertEquals(1, retryChecks)
    }

    @Test
    fun snapshotDnsRetryIsCancelledWhenGlobalArchiveGateBlocksIt() = runBlocking {
        val client = newClient()
        var attempts = 0
        try {
            client.retrySnapshotDnsOnce(
                request = {
                    attempts++
                    throw java.net.UnknownHostException("temporary DNS failure")
                },
                beforeRetry = { false }
            )
            throw AssertionError("Retry must be stopped when the shared archive gate is closed")
        } catch (failure: java.io.IOException) {
            assertEquals("WAYBACK_RATE_LIMITED", failure.message)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun classifiesWaybackNetworkFailuresWithoutLeakingExceptionText() {
        val client = newClient()
        assertEquals("CDX_URL_PREFIX_TIMEOUT", client.classifyNetworkFailure("CDX_URL_PREFIX", java.net.SocketTimeoutException("secret url")))
        assertEquals("CDX_DOMAIN_TIMEOUT", client.classifyNetworkFailure("CDX_DOMAIN", java.io.InterruptedIOException("timeout")))
        assertEquals("SNAPSHOT_TIMEOUT", client.classifyNetworkFailure("SNAPSHOT", java.io.InterruptedIOException("timeout")))
        assertEquals("CDX_URL_PREFIX_DNS", client.classifyNetworkFailure("CDX_URL_PREFIX", java.net.UnknownHostException("private host")))
        assertEquals(
            "SNAPSHOT_DNS",
            client.classifyNetworkFailure(
                "SNAPSHOT",
                java.io.IOException("connection failed", java.net.UnknownHostException("private host"))
            )
        )
        assertEquals(
            "SNAPSHOT_DNS",
            client.classifyNetworkFailure("SNAPSHOT", java.io.IOException("Unable to resolve host \"web.archive.org\""))
        )
        assertEquals("CDX_DOMAIN_NETWORK", client.classifyNetworkFailure("CDX_DOMAIN", java.io.IOException("private host")))
        assertEquals("CDX_DOMAIN_UNEXPECTED_ERROR", client.classifyNetworkFailure("CDX_DOMAIN", IllegalStateException("private data")))
    }



    @Test
    fun defaultHistoricalLookbackCoversOlderProductsBeyondFourYears() {
        assertTrue(
            "Historical lookup should include older products such as the Xiaomi Mi 10 Pro",
            WaybackPriceHistoryClient.DEFAULT_LOOKBACK_DAYS >= 2_500
        )
    }

    @Test
    fun triesApexHostOnceAfterWwwCdxTimeoutWithoutRepeatingSameHost() {
        val client = newClient()
        val hosts = client.buildDomainCdxHostUrls(
            domain = "phonehouse.es",
            expectedTitle = "Xiaomi Mi 10 Pro Gris",
            now = 1_800_000_000_000L
        )!!
        assertEquals(2, hosts.size)
        assertEquals("www.phonehouse.es", hosts[0].queryParameter("url"))
        assertEquals("phonehouse.es", hosts[1].queryParameter("url"))

        assertTrue(
            client.shouldTryNextDomainHostAfterTimeout(0, hosts, setOf("CDX_DOMAIN_TIMEOUT"))
        )
        assertFalse(
            "Do not retry a host after its one bounded attempt",
            client.shouldTryNextDomainHostAfterTimeout(1, hosts, setOf("CDX_DOMAIN_TIMEOUT"))
        )
        assertFalse(
            "Respect Internet Archive's 429 cooldown",
            client.shouldTryNextDomainHostAfterTimeout(0, hosts, setOf("WAYBACK_RATE_LIMITED"))
        )
        assertFalse(
            "Do not spend the second host query on an HTTP server error",
            client.shouldTryNextDomainHostAfterTimeout(0, hosts, setOf("CDX_DOMAIN_HTTP_503"))
        )
        assertFalse(
            "Do not retry the same host under a duplicate URL",
            client.shouldTryNextDomainHostAfterTimeout(0, listOf(hosts[0], hosts[0]), setOf("CDX_DOMAIN_TIMEOUT"))
        )
    }

    @Test
    fun domainCdxQueryHasBoundedProductSpecificRequestParameters() {
        val client = newClient()
        val url = client.buildDomainCdxUrl(
            domain = "shop.example",
            expectedTitle = "Anker Prime 27K Power Bank",
            now = 1_800_000_000_000L,
            lookbackDays = 2920
        )
        assertNotNull(url)
        assertEquals("web.archive.org", url!!.host)
        assertEquals("/cdx/search/cdx", url.encodedPath)
        assertEquals("shop.example", url.queryParameter("url"))
        assertEquals("host", url.queryParameter("matchType"))
        val originalFilter = url.queryParameterValues("filter").orEmpty()
            .mapNotNull { it?.takeIf { filter -> filter.startsWith("original:") } }
            .single()
        assertTrue(
            "The CDX original-field filter must limit URLs to distinguishing title/model tokens",
            originalFilter.startsWith("original:(?i).*")
        )
        assertFalse(
            "Use portable regex alternatives rather than look-ahead expressions unsupported by some CDX deployments",
            originalFilter.contains("(?=")
        )
        assertTrue(originalFilter.contains("27k") || originalFilter.contains("prime"))
        assertEquals("json", url.queryParameter("output"))
        assertEquals("20", url.queryParameter("limit"))
        assertNull("Host discovery avoids slow collapse processing", url.queryParameter("collapse"))
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it == "statuscode:200" })
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it == "mimetype:text/html" })
        assertTrue(url.queryParameterValues("filter").orEmpty().any { it.orEmpty().startsWith("original:") })
        assertEquals("20190115", url.queryParameter("from"))
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
        assertTrue(urls.all { it.queryParameter("limit") == "20" })
    }

    @Test
    fun domainCdxQueryRejectsGenericOnlyTermsAndSingleBrandWords() {
        val client = newClient()
        assertNull(client.buildDomainCdxUrl("shop.example", "Producto genérico"))
        assertNull(client.buildDomainCdxUrl("shop.example", "Producto demo"))
        assertNull(client.buildDomainCdxUrl("shop.example", "Anker"))
        assertNotNull(client.buildDomainCdxUrl("shop.example", "Anker Prime 27K Power Bank"))
    }

    @Test
    fun domainCdxQueryRejectsUnrecognisedOrGenericTargets() {
        val client = newClient()
        assertNull(client.buildDomainCdxUrl("https://google.com", "Anker Prime 27K Power Bank"))
        assertNull(client.buildDomainCdxUrl("shop.example", ""))
        assertNull(client.buildDomainCdxUrl("shop.example", "Producto genérico"))
        assertNull(client.buildDomainCdxUrl("shop.example", "Anker"))
    }


    @Test
    fun domainCdxHostQueryNormalizesFullUrlWithMixedCaseSchemeAndPath() {
        val client = newClient()
        val urls = client.buildDomainCdxHostUrls(
            domain = "HTTPS://WWW.shop.example/catalog/item/",
            expectedTitle = "Anker Prime 27K Power Bank",
            now = 1_800_000_000_000L,
            lookbackDays = 730
        )
        assertNotNull(urls)
        assertEquals(listOf("www.shop.example", "shop.example"), urls!!.map { it.queryParameter("url") })
    }


    @Test
    fun unknownStructuredProductWithItsOwnSameStoreUrlBecomesDiscoveryCandidate() {
        val client = newClient()
        val primaryUrl = "https://www.pccomponentes.com/apple-iphone-15-256gb-amarillo"
        val discoveredUrl = "https://www.pccomponentes.com/sony-wh-1000xm5-auriculares-negro"
        val snapshotUrl = "https://web.archive.org/web/20250101120000id_/$primaryUrl"
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@graph":[
                  {"@type":"Product","name":"Apple iPhone 15 256GB Amarillo",
                   "url":"$primaryUrl",
                   "offers":{"@type":"Offer","price":"899.99","priceCurrency":"EUR"}},
                  {"@type":"Product","name":"Sony WH-1000XM5 Auriculares Inalámbricos Negro",
                   "url":"$discoveredUrl",
                   "offers":{"@type":"Offer","price":"249.99","priceCurrency":"EUR"}}
                ]
              }
              </script>
            </head></html>
        """.trimIndent()

        val quotes = client.parseArchivedPricesForKnownProducts(
            document = Jsoup.parse(html),
            primaryExpectedTitle = "Apple iPhone 15 256GB Amarillo",
            knownProductTitlesById = emptyMap(),
            originalUrl = primaryUrl,
            snapshotUrl = snapshotUrl,
            observedAt = 1_735_732_800_000L
        )

        assertEquals("Only the unknown secondary product should go to staging", 1, quotes.size)
        val quote = quotes.single()
        assertTrue(quote.isDiscoveryCandidate)
        assertNull(quote.targetProductId)
        assertEquals("Sony WH-1000XM5 Auriculares Inalámbricos Negro", quote.productTitle)
        assertEquals(discoveredUrl, quote.productUrl)
        assertEquals(249.99, quote.price, 0.001)
    }

    @Test
    fun accessoryOffersAreNeverReturnedAsDiscoveryCandidates() {
        val client = newClient()
        val primaryUrl = "https://www.pccomponentes.com/apple-iphone-15-256gb-amarillo"
        val html = """
            <html><head>
              <script type="application/ld+json">
              {
                "@context":"https://schema.org",
                "@graph":[
                  {"@type":"Product","name":"Apple iPhone 15 256GB Amarillo",
                   "url":"$primaryUrl",
                   "offers":{"@type":"Offer","price":"899.99","priceCurrency":"EUR"}},
                  {"@type":"Product","name":"Funda protectora Apple iPhone 15, carcasa de silicona",
                   "url":"https://www.pccomponentes.com/funda-protectora-iphone-15",
                   "offers":{"@type":"Offer","price":"12.99","priceCurrency":"EUR"}}
                ]
              }
              </script>
            </head></html>
        """.trimIndent()

        val quotes = client.parseArchivedPricesForKnownProducts(
            document = Jsoup.parse(html),
            primaryExpectedTitle = "Apple iPhone 15 256GB Amarillo",
            knownProductTitlesById = emptyMap(),
            originalUrl = primaryUrl,
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/$primaryUrl",
            observedAt = 1_735_732_800_000L
        )

        assertTrue("Accessory pages must not pollute the discovery quarantine", quotes.isEmpty())
    }

}