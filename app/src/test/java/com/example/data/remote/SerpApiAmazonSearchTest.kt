package com.example.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SerpApiAmazonSearchTest {

    @Test
    fun parsesEuroPricesPrimeDeliveryAndSortsByPriceWhileRejectingUnsafeResults() {
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "organic_results": [
                {
                  "title": "Anker Prime Power Bank 200W",
                  "price": "89,99 €",
                  "extracted_price": 89.99,
                  "link": "https://www.amazon.es/dp/B0ANKER111/",
                  "link_clean": "https://www.amazon.es/dp/B0ANKER111/",
                  "thumbnail": "https://m.media-amazon.com/images/I/anker.jpg",
                  "rating": 4.7,
                  "reviews": 1200,
                  "prime": true,
                  "delivery": ["Entrega GRATIS con Prime"]
                },
                {
                  "title": "Anker Power Bank",
                  "price": "39,99 €",
                  "extracted_price": 39.99,
                  "link": "https://www.amazon.es/dp/B0ANKER222/",
                  "link_clean": "https://www.amazon.es/dp/B0ANKER222/",
                  "rating": 4.5,
                  "reviews": 250,
                  "prime": false
                },
                {
                  "title": "Anker Power Bank 20000mAh",
                  "price": "29,99 €",
                  "extracted_price": 29.99,
                  "link": "https://www.amazon.es/dp/B0ANKERUSED/",
                  "second_hand_condition": "used"
                },
                {
                  "title": "External unsafe result",
                  "price": "1,99 €",
                  "extracted_price": 1.99,
                  "link": "https://example.com/product",
                  "link_clean": "https://example.com/product"
                },
                {
                  "title": "Wrong currency result",
                  "price": "$10.99",
                  "extracted_price": 10.99,
                  "link": "https://www.amazon.es/dp/B0ANKER333/",
                  "link_clean": "https://www.amazon.es/dp/B0ANKER333/"
                }
              ]
            }
            """.trimIndent()
        )

        val results = SerpApiClient().parseAmazonResults(payload, observedAt = 1234L)

        assertEquals(2, results.size)
        assertEquals(39.99, results[0].price, 0.001)
        assertEquals("Amazon.es", results[0].storeName)
        assertTrue(results.all { it.sourceUrl.startsWith("https://www.amazon.es/") })
        assertEquals(89.99, results[1].price, 0.001)
        assertTrue(results[1].primeEligible)
        assertEquals("Entrega GRATIS con Prime", results[1].delivery)
        assertEquals(1234L, results[1].observedAt)
    }

    @Test
    fun acceptsStructuredShoppingPricesWithoutEuroLabelAndRejectsForeignCurrency() {
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Anker Power Bank 20000mAh",
                  "price": "39.99",
                  "extracted_price": 39.99,
                  "source": "Amazon.es",
                  "link": "https://www.amazon.es/dp/B0ANKER123"
                },
                {
                  "title": "Anker Power Bank 20000mAh",
                  "price": "$10.99",
                  "extracted_price": 10.99,
                  "source": "Foreign store",
                  "link": "https://shop.example/product"
                }
              ]
            }
            """.trimIndent()
        )

        val results = SerpApiClient().parseShoppingResults(payload, observedAt = 1234L)
        assertEquals(1, results.size)
        assertEquals(39.99, results.single().price, 0.001)
        assertEquals("Amazon.es", results.single().storeName)

        val requested = SerpApiRequestProduct(
            id = "anker-powerbank",
            title = "Anker Power Bank 20000mAh",
            brand = "Anker"
        )
        val quotes = SerpApiClient().parseQuotes(payload, requested, observedAt = 1234L)
        assertEquals(1, quotes.size)
        assertEquals(39.99, quotes.single().price, 0.001)
        assertEquals("EUR", quotes.single().currency)
    }
}
