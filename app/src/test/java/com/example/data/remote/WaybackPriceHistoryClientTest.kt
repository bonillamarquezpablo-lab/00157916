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
    fun extractsSellerMatchedUrlFromGoogleRedirectAndRejectsOtherMerchants() {
        val client = newClient()
        val html = """
            <html><body>
              <a href="https://www.parfumdreams.es/customer-service">Atención al cliente</a>
              <a href="https://www.google.com/url?url=https%3A%2F%2Fwww.parfumdreams.es%2FPolo-Blue-100ml&sa=U">Parfumdreams</a>
              <a href="https://www.amazon.es/dp/B000000000">Amazon</a>
            </body></html>
        """.trimIndent()

        assertEquals(
            "https://www.parfumdreams.es/Polo-Blue-100ml",
            client.extractDirectMerchantUrl(html, "parfumdreams.ES", "Polo Blue 100ml")
        )
        assertNull(client.extractDirectMerchantUrl(html, "Ralph Lauren"))
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
}
