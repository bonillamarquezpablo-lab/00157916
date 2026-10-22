package com.example.data.remote

import org.json.JSONArray
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
    fun pricedAdidasSuperstarVariantsWithoutSellerLinksRemainVisibleViaShoppingSearchFallback() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "adidas Originals Zapatillas Superstar II Mujer talla 39",
                      "price": "119,95 €",
                      "extracted_price": 119.95,
                      "source": "Google Shopping"
                    },
                    {
                      "title": "adidas Superstar II Hombre talla 42",
                      "price": "120,00 €",
                      "extracted_price": 120.00,
                      "source": "Google Shopping"
                    },
                    {
                      "title": "Zapatillas adidas Originals Superstar II Niños Pokemon talla 33",
                      "price": "69,99 €",
                      "extracted_price": 69.99,
                      "source": "Google Shopping"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Adidas Superstar",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(
            "Keep priced variants when seller link fields are missing",
            3,
            report.results.size
        )
        assertTrue(report.results.all { isGoogleShoppingUrl(it.sourceUrl) })
        assertTrue(report.results.all { it.storeName == "Google Shopping" })
        assertTrue(report.discardedOffers.none { it.reason.contains("enlace HTTPS útil") })
        assertTrue(report.results.any { it.sourceUrl.contains("q=") && it.sourceUrl.contains("Superstar") })
    }

    @Test
    fun googleShoppingUrlDetectionRejectsLookalikeDomains() {
        assertTrue(isGoogleShoppingUrl("https://www.google.com/shopping/product/123456"))
        assertTrue(isGoogleShoppingUrl("https://www.google.es/search?tbm=shop"))
        assertTrue(isGoogleShoppingUrl("https://shopping.google.co.uk/"))

        assertFalse(isGoogleShoppingUrl("https://google.com.attacker.example/search"))
        assertFalse(isGoogleShoppingUrl("https://google.attacker.example/search"))
        assertFalse(isGoogleShoppingUrl("https://www.notgoogle.com/shopping"))
    }

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

    @Test
    fun singleCapacityQueryRejectsWrongStorageButKeepsTheMatchingStorage() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Samsung Galaxy S26 Ultra 12GB RAM 256GB Storage",
                      "price": "899,99 €",
                      "extracted_price": 899.99,
                      "link": "https://shop.example.com/samsung-s26-ultra-256",
                      "source": "Tienda A"
                    },
                    {
                      "title": "Samsung Galaxy S26 Ultra 12GB RAM 512GB Storage",
                      "price": "1.099,99 €",
                      "extracted_price": 1099.99,
                      "link": "https://shop.example.com/samsung-s26-ultra-512",
                      "source": "Tienda B"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Samsung Galaxy S26 Ultra 512GB",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(1, report.results.size)
        assertEquals("Samsung Galaxy S26 Ultra 12GB RAM 512GB Storage", report.results.single().title)
        assertEquals(1, report.rejectedAsIrrelevant)
    }

    @Test
    fun ranksExactModelPhraseBeforeLowerPricedLooseMatch() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Power Bank 27K for Anker Prime",
                      "price": "69,99 €",
                      "extracted_price": 69.99,
                      "link": "https://shop.example.com/anker-prime-27k",
                      "source": "Tienda barata"
                    },
                    {
                      "title": "Anker Prime 27K Power Bank",
                      "price": "79,99 €",
                      "extracted_price": 79.99,
                      "link": "https://www.anker.com/products/prime-power-bank-27k",
                      "source": "Anker"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Anker Prime 27K Power Bank",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(2, report.results.size)
        assertEquals("Anker Prime 27K Power Bank", report.results.first().title)
        assertTrue(report.results.first().price > report.results.last().price)
    }

    @Test
    fun refusesToReturnGenericGoogleSearchAsTheOnlyOfferDestination() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [{
                    "title": "Sony WH-1000XM5 headphones",
                    "price": "199,99 €",
                    "extracted_price": 199.99,
                    "source": "Tienda desconocida"
                  }]
                }
                """.trimIndent()
            ),
            query = "Sony WH-1000XM5",
            observedAt = 1_800_000_000_000L
        )

        assertTrue(report.results.isEmpty())
        assertEquals(1, report.rejectedForLink)
    }

    @Test
    fun keepsExactXiaomiMi10ProButRejectsRedmiNote10ProAndMi11Pro() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Xiaomi Mi 10 Pro 5G 8GB/256GB Gris",
                      "price": "180,00 €",
                      "extracted_price": 180.0,
                      "link": "https://shop.example/mi-10-pro",
                      "source": "Tienda A"
                    },
                    {
                      "title": "Xiaomi Redmi Note 10 Pro 6GB/128GB",
                      "price": "100,00 €",
                      "extracted_price": 100.0,
                      "link": "https://shop.example/redmi-note-10-pro",
                      "source": "Tienda B"
                    },
                    {
                      "title": "Xiaomi Mi 11 Pro 8GB/256GB",
                      "price": "220,00 €",
                      "extracted_price": 220.0,
                      "link": "https://shop.example/mi-11-pro",
                      "source": "Tienda C"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "xiaomi mi 10 pro",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(1, report.results.size)
        assertEquals("Xiaomi Mi 10 Pro 5G 8GB/256GB Gris", report.results.single().title)
        assertEquals(2, report.rejectedAsIrrelevant)
        assertTrue(hasXiaomiModelMismatch("xiaomi mi 10 pro", "Xiaomi Redmi Note 10 Pro"))
        assertTrue(hasXiaomiModelMismatch("xiaomi mi 10 pro", "Xiaomi Mi 11 Pro"))
        assertFalse(hasXiaomiModelMismatch("xiaomi mi 10 pro", "Xiaomi Mi 10 Pro 5G"))
    }

    @Test
    fun formatsResultAgeForFreshnessDisclosure() {
        assertEquals(
            "hace 2 h 15 min",
            formatOnlineSnapshotAge(capturedAt = 1_000L, now = 1_000L + 135L * 60_000L)
        )
        assertEquals(
            "hace menos de 1 min",
            formatOnlineSnapshotAge(capturedAt = 5_000L, now = 5_001L)
        )
    }

    @Test
    fun unknownSellerMustNotBeSilentlyLabeledAsAmazon() {
        val unknown = Store.findByName("Tienda local")

        assertFalse(unknown.id == Store.AMAZON_ES.id)
        assertFalse(unknown.name.contains("Amazon", ignoreCase = true))
        assertEquals("Tienda sin verificar", unknown.category)
        assertFalse(unknown.hasOfficialSite)
        assertEquals("unknown.invalid", unknown.domain)
        assertEquals("", unknown.officialUrl)
    }

    @Test
    fun generatedKnownRetailerIdStillResolvesToKnownSeller() {
        assertEquals(Store.ANKER_STORE, Store.findByName("retailer_anker_com"))
        assertEquals(Store.UGREEN_STORE, Store.findByName("retailer_ugreen_com"))
    }

    @Test
    fun ankerAndUgreenFallbackLinksStayOnFirstPartyDomains() {
        val anker = Store.findKnownByName("Anker", "https://www.google.com/shopping/product/123456789")
        val ugreen = Store.findKnownByName("UGREEN", "https://www.google.com/shopping/product/123456789")

        assertEquals(Store.ANKER_STORE, anker)
        assertEquals(Store.UGREEN_STORE, ugreen)
        assertTrue(anker!!.supportsProductSearch)
        assertTrue(ugreen!!.supportsProductSearch)
        assertTrue(anker.searchUrlFor("Anker Prime Power Bank").startsWith("https://www.anker.com/eu-en/search?q="))
        assertTrue(ugreen.searchUrlFor("UGREEN Nexode 100W").startsWith("https://eu.ugreen.com/es-es/search?q="))
    }

    @Test
    fun rejectsStructuredForeignCurrencyWhenPriceLabelHasNoCurrencySymbol() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [{
                    "title": "Anker Prime 27K Power Bank",
                    "price": "99.99",
                    "extracted_price": 99.99,
                    "currency": "USD",
                    "source": "Tienda",
                    "link": "https://shop.example/anker-prime"
                  }]
                }
                """.trimIndent()
            ),
            query = "Anker Prime 27K Power Bank",
            observedAt = 1_800_000_000_000L
        )

        assertTrue("A numeric USD amount must not be treated as EUR when its label omits '$'", report.results.isEmpty())
        assertEquals(1, report.rejectedForPriceOrCurrency)
    }

    @Test
    fun acceptsStructuredEurCurrencyWhenPriceLabelOmitsSymbol() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [{
                    "title": "Anker Prime 27K Power Bank",
                    "price": "99.99",
                    "extracted_price": 99.99,
                    "currency": "EUR",
                    "source": "Tienda",
                    "link": "https://shop.example/anker-prime"
                  }]
                }
                """.trimIndent()
            ),
            query = "Anker Prime 27K Power Bank",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(1, report.results.size)
        assertEquals(99.99, report.results.single().price, 0.001)
    }


    @Test
    fun broadSamsungBrandSearchIncludesGalaxyFamilyNamesAndUsesAllProviderSections() {
        val client = SerpApiClient()
        fun offer(index: Int, title: String): JSONObject = JSONObject()
            .put("title", title)
            .put("price", "${100 + index}.00 €")
            .put("extracted_price", 100.0 + index)
            .put("link", "https://shop${index}.example.com/product/${index}")
            .put("source", "Tienda ${index}")

        val shopping = JSONArray()
        val featured = JSONArray()
        val inline = JSONArray()
        repeat(40) { index -> shopping.put(offer(index, "Samsung Galaxy Modelo ${index}")) }
        repeat(40) { index -> featured.put(offer(40 + index, "Galaxy Watch modelo ${index}")) }
        repeat(40) { index -> inline.put(offer(80 + index, "Galaxy Buds modelo ${index}")) }

        val report = client.parseShoppingResultsDetailed(
            root = JSONObject()
                .put("shopping_results", shopping)
                .put("featured_shopping_results", featured)
                .put("inline_shopping_results", inline)
                .put("categorized_shopping_results", JSONArray().put(
                    JSONObject().put("category", "Samsung").put(
                        "shopping_results", JSONArray().put(
                            offer(120, "Xiaomi Redmi Note 14")
                        )
                    )
                )),
            query = "Samsung",
            observedAt = 1_800_000_000_000L
        )

        assertEquals("All returned sections should be examined from the same provider response", 121, report.rawResultCount)
        assertEquals("A broad brand search should retain up to the configured 100 result ceiling", 100, report.results.size)
        assertEquals("Keep a wide set of distinct retailer offers", 100, report.results.map { it.storeName }.distinct().size)
        assertTrue("Samsung product family titles like Galaxy Watch should match", report.results.any { it.title.startsWith("Galaxy Watch") })
        assertTrue("Samsung product family titles like Galaxy Buds should match", report.results.any { it.title.startsWith("Galaxy Buds") })
        assertFalse("A different brand must still be rejected", report.results.any { it.title.contains("Xiaomi", ignoreCase = true) })
    }

    @Test
    fun broadXiaomiScooterSearchKeepsRelatedModelsForSelection() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Xiaomi Electric Scooter 5 Max",
                      "price": "457,94 €",
                      "extracted_price": 457.94,
                      "link": "https://shop.example/xiaomi-scooter-5-max",
                      "source": "Tienda"
                    },
                    {
                      "title": "Xiaomi Electric Scooter 4 Pro",
                      "price": "399,00 €",
                      "extracted_price": 399.00,
                      "link": "https://shop.example/xiaomi-scooter-4-pro",
                      "source": "Tienda"
                    },
                    {
                      "title": "Samsung Galaxy S26",
                      "price": "899,00 €",
                      "extracted_price": 899.00,
                      "link": "https://shop.example/samsung-s26",
                      "source": "Tienda"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Xiaomi scooter",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(2, report.results.size)
        assertTrue(report.results.any { it.title.contains("5 Max") })
        assertTrue(report.results.any { it.title.contains("4 Pro") })
        assertFalse(report.results.any { it.title.contains("Samsung") })
    }



    @Test
    fun broadFamilyMatcherKeepsCommonModelsFromEnglishAndSpanishListings() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Xiaomi Electric Scooter 5 Max",
                      "price": "457,94 €",
                      "extracted_price": 457.94,
                      "link": "https://shop.example/xiaomi-scooter-5-max",
                      "source": "Tienda"
                    },
                    {
                      "title": "Xiaomi Electric Scooter 4 Pro",
                      "price": "399,00 €",
                      "extracted_price": 399.00,
                      "link": "https://shop.example/xiaomi-scooter-4-pro",
                      "source": "Tienda"
                    },
                    {
                      "title": "Samsung Galaxy S26",
                      "price": "899,00 €",
                      "extracted_price": 899.00,
                      "link": "https://shop.example/samsung-s26",
                      "source": "Tienda"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Xiaomi scooter",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(2, report.results.size)
        assertTrue(report.results.any { it.title.contains("5 Max") })
        assertTrue(report.results.any { it.title.contains("4 Pro") })
        assertFalse(report.results.any { it.title.contains("Samsung") })
    }

    @Test
    fun broadFamilyMatcherRejectsDifferentBrandEvenWhenCategoryMatches() {
        val client = SerpApiClient()
        val report = client.parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "shopping_results": [
                    {
                      "title": "Xiaomi Electric Scooter 5 Max",
                      "price": "457,94 €",
                      "extracted_price": 457.94,
                      "link": "https://shop.example/xiaomi-scooter",
                      "source": "Tienda"
                    },
                    {
                      "title": "Segway Ninebot Max G2 Electric Scooter",
                      "price": "599,00 €",
                      "extracted_price": 599.00,
                      "link": "https://shop.example/segway-scooter",
                      "source": "Tienda"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Xiaomi scooter",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(1, report.results.size)
        assertTrue(report.results.single().title.contains("Xiaomi"))
    }


}
