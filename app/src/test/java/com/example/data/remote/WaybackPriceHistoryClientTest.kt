package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaybackPriceHistoryClientTest {
    private fun newClient(): WaybackPriceHistoryClient =
        WaybackPriceHistoryClient(ApplicationProvider.getApplicationContext<Context>())

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
    fun acceptsSamsungTvRegionalSkuAndCoreModelUrlVariants() {
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
        assertNotNull("Equivalent regional SKU prefixes should not hide the same archived TV model", quote)
        assertEquals(1394.10, quote!!.price, 0.001)

        val url = client.buildDomainCdxUrl(
            domain = "samsung.com",
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            now = 1_800_000_000_000L
        )
        val pathFilter = url?.queryParameterValues("filter").orEmpty()
            .firstOrNull { (it ?: "").startsWith("original:") }.orEmpty()
        assertTrue("Archive search should also try the stable S95H model family", pathFilter.contains("s95h"))
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
        assertEquals("domain", url!!.queryParameter("matchType"))
        assertEquals("80", url.queryParameter("limit"))
        assertNull("Domain scans must preserve repeated captures over time", url.queryParameter("collapse"))
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

        val samsungUrl = client.buildDomainCdxUrl(
            domain = "samsung.com",
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            now = 1_800_000_000_000L
        )
        val samsungPathFilter = samsungUrl?.queryParameterValues("filter").orEmpty()
            .firstOrNull { (it ?: "").startsWith("original:") } ?: ""
        assertTrue("Samsung archive lookup should retain the exact model as one discovery route", samsungPathFilter.contains("tq48s95haexxc"))
        assertTrue("It should also try meaningful title words in case the SKU is absent from the URL", samsungPathFilter.contains("oled"))
        assertTrue("Alternative title-term route should allow short retailer URLs", samsungPathFilter.contains("smart"))

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
    fun rejectsAmbiguousJsonLdOffersInsteadOfChoosingTheFirstPrice() {
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

        assertNull("Multiple incompatible offer prices must not become arbitrary historical evidence", quote)
    }

    @Test
    fun negativeArchiveCacheRetriesAfterSevenDaysButPositiveCacheWaitsThirty() {
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
            preferences.edit().putBoolean("${key}_failed", true).commit()
            assertTrue(
                "A transient network failure is briefly suppressed to avoid repeated requests",
                client.wasAttemptedRecently(key, lastAttempt + 10L * 60L * 1000L)
            )
            assertFalse(
                "A transient network failure may be retried after thirty minutes",
                client.wasAttemptedRecently(key, lastAttempt + 31L * 60L * 1000L)
            )

            preferences.edit().putBoolean("${key}_failed", false).commit()
            assertTrue(
                "An empty search is suppressed during the first week",
                client.wasAttemptedRecently(key, lastAttempt + 6L * WaybackPriceHistoryClient.DAY_MS)
            )
            assertFalse(
                "An empty search can be retried after seven days",
                client.wasAttemptedRecently(key, lastAttempt + 8L * WaybackPriceHistoryClient.DAY_MS)
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
                .commit()
        }
    }

}
