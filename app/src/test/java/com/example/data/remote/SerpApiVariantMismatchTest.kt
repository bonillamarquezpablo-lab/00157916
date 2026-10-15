package com.example.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.example.data.model.Store
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SerpApiVariantMismatchTest {

    @Test
    fun rejectsWrongShoeSizeWhenQueryOmitsTheWordTallaButKeepsTheRequestedSize() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Nike Run Defy - Gris - Zapatillas Running Hombre talla 44",
                      "price": "44,99 €",
                      "extracted_price": 44.99,
                      "link": "https://www.nike.com/es/t/run-defy-44",
                      "source": "Nike"
                    },
                    {
                      "title": "Nike Run Defy - Gris - Zapatillas Running Hombre talla 41",
                      "price": "49,99 €",
                      "extracted_price": 49.99,
                      "link": "https://www.nike.com/es/t/run-defy-41",
                      "source": "Nike"
                    },
                    {
                      "title": "Nike P 6000 Hombre",
                      "price": "119,99 €",
                      "extracted_price": 119.99,
                      "link": "https://www.nike.com/es/t/p-6000",
                      "source": "Nike"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "zapatillas nike 41",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(2, report.results.size)
        assertTrue(report.results.any { it.title.contains("talla 41", ignoreCase = true) })
        assertTrue(
            "A product page with a size selector should not be rejected just because its title omits the size",
            report.results.any { it.title == "Nike P 6000 Hombre" }
        )
        assertFalse(report.results.any { it.title.contains("talla 44", ignoreCase = true) })
        assertEquals(1, report.rejectedAsIrrelevant)
    }

    @Test
    fun unwrapsGoogleRedirectsAndDoesNotPretendAGenericGooglePageIsARetailerUrl() {
        assertEquals(
            "https://www.nike.com/es/t/air-max-plus",
            extractDirectRetailerUrl(
                "https://www.google.com/url?sa=t&url=https%3A%2F%2Fwww.nike.com%2Fes%2Ft%2Fair-max-plus"
            )
        )
        assertEquals(
            "https://lamanzanita.es/producto/iphone-16/",
            extractDirectRetailerUrl("https://lamanzanita.es/producto/iphone-16/")
        )
        assertEquals(
            null,
            extractDirectRetailerUrl("https://www.google.com/search?q=iphone+16")
        )
    }

    @Test
    fun knownRetailersSearchOnTheirOwnSiteInsteadOfGoogle() {
        val url = Store.LA_MANZANITA.searchUrlFor("Apple iPhone 16")
        assertTrue(url.startsWith("https://lamanzanita.es/"))
        assertFalse(url.contains("google.", ignoreCase = true))
        assertTrue(url.contains("post_type=product"))
        assertEquals(Store.LA_MANZANITA, Store.findKnownByName("La Manzanita"))
    }
    @Test
    fun ankerAndUgreenFallbackLinksOpenBrandSearchInsteadOfGoogle() {
        val anker = Store.findKnownByName("Anker", "https://www.google.com/shopping/product/123456789")
        val ugreen = Store.findKnownByName("UGREEN", "https://www.google.com/shopping/product/123456789")

        assertEquals(Store.ANKER_STORE, anker)
        assertEquals(Store.UGREEN_STORE, ugreen)
        assertTrue(anker!!.supportsProductSearch)
        assertTrue(ugreen!!.supportsProductSearch)

        val ankerUrl = anker.searchUrlFor("Anker Prime Power Bank 27,650 mAh")
        val ugreenUrl = ugreen.searchUrlFor("UGREEN Nexode 20000mAh 100W")
        assertTrue(ankerUrl.startsWith("https://www.anker.com/eu-en/search?q="))
        assertTrue(ugreenUrl.startsWith("https://www.ugreen.com/es-es/search?q="))
        assertFalse(ankerUrl.contains("google.", ignoreCase = true))
        assertFalse(ugreenUrl.contains("google.", ignoreCase = true))
    }

    @Test
    fun rejectsStorageVariantWhenRamAndStorageUseTheSameUnit() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Samsung Galaxy S26 Ultra 12GB RAM 256GB Storage",
                      "price": "999,99 €",
                      "extracted_price": 999.99,
                      "link": "https://www.samsung.com/es/smartphones/galaxy-s26-ultra/",
                      "source": "Samsung"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Samsung Galaxy S26 Ultra 12GB RAM 512GB Storage",
            observedAt = 1_800_000_000_000L
        )

        assertTrue(
            "A result with 256GB storage must not pass a 512GB search just because both mention 12GB RAM",
            report.results.isEmpty()
        )
        assertEquals(1, report.rejectedAsIrrelevant)
    }

}
