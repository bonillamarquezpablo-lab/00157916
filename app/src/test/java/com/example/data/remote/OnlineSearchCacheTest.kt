package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
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
class OnlineSearchCacheTest {

    @Test
    fun reusesFreshResultsAndPreservesAmazonMetadataWithoutSavingQueryText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineSearchCache(context)
        val now = 1_800_000_000_000L
        val result = SerpApiShoppingResult(
            title = "Anker Prime power bank",
            price = 79.99,
            storeName = "Amazon.es",
            sourceUrl = "https://www.amazon.es/dp/B0CACHE01",
            thumbnailUrl = "https://m.media-amazon.com/images/I/cache.jpg",
            delivery = "Entrega GRATIS con Prime",
            rating = 4.7,
            reviewCount = 120,
            observedAt = now - 10_000L,
            primeEligible = true,
            condition = "used",
            isSuspiciousPrice = true,
            googleProductId = "shopping-anker-27k"
        )

        cache.write("AMAZON", "Anker Prime 20.000 mAh", listOf(result), now)
        val cached = cache.read("amazon", "  anker   prime 20.000 mAh ", now + 30_000L)

        assertNotNull(cached)
        assertEquals(1, cached!!.results.size)
        assertEquals(79.99, cached.results[0].price, 0.001)
        assertEquals("Amazon.es", cached.results[0].storeName)
        assertEquals("Entrega GRATIS con Prime", cached.results[0].delivery)
        assertTrue(cached.results[0].primeEligible)
        assertEquals("used", cached.results[0].condition)
        assertTrue(cached.results[0].isSuspiciousPrice)
        assertEquals("shopping-anker-27k", cached.results[0].googleProductId)
        assertEquals(4.7, cached.results[0].rating!!, 0.001)
    }

    @Test
    fun cachesUnfilteredProviderResponseFor24HoursWithoutCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineSearchCache(context)
        val now = 1_800_200_000_000L
        val response = JSONObject(
            """
            {
              "api_key": "MUST_NOT_BE_STORED",
              "search_metadata": {"status": "Success", "token": "ALSO_SECRET"},
              "shopping_results": [
                {"title":"Autec Zenit","price":"123,15 €","extracted_price":123.15,"link":"https://shop.example/zenit","source":"Tienda"}
              ]
            }
            """.trimIndent()
        )

        cache.writeRaw("GOOGLE_SHOPPING", "autec zenit 14 5x100", response.toString(), now)
        val cached = cache.read("google_shopping", "autec zenit 14 5x100", now + OnlineSearchCache.CACHE_TTL_MS - 1L)

        assertNotNull(cached)
        assertTrue(cached!!.results.isEmpty())
        assertNotNull(cached.rawResponse)
        assertTrue(cached.rawResponse!!.contains("Autec Zenit"))
        assertFalse(cached.rawResponse!!.contains("MUST_NOT_BE_STORED"))
        assertFalse(cached.rawResponse!!.contains("ALSO_SECRET"))
        assertFalse(cached.rawResponse!!.contains("api_key"))
        assertEquals(24L * 60L * 60L * 1000L, OnlineSearchCache.CACHE_TTL_MS)
        assertNull(cache.read("google_shopping", "autec zenit 14 5x100", now + OnlineSearchCache.CACHE_TTL_MS))
    }

    @Test
    fun expiresStaleResultsAndDoesNotMixProviders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineSearchCache(context)
        val now = 1_800_100_000_000L
        val result = SerpApiShoppingResult(
            title = "USB-C cable",
            price = 7.5,
            storeName = "Tienda",
            sourceUrl = "https://shop.example/cable",
            observedAt = now
        )
        cache.write("GOOGLE_SHOPPING", "usb c cable", listOf(result), now)

        assertNull(cache.read("AMAZON", "usb c cable", now + 1_000L))
        assertNotNull(cache.read("GOOGLE_SHOPPING", "usb c cable", now + 1_000L))
        assertNull(cache.read("GOOGLE_SHOPPING", "usb c cable", now + OnlineSearchCache.CACHE_TTL_MS))
    }
    @Test
    fun invalidateRemovesOnlyTheRequestedProviderAndQuery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineSearchCache(context)
        val now = 1_800_300_000_000L
        val googleResult = SerpApiShoppingResult(
            title = "Anker Prime",
            price = 99.0,
            storeName = "Tienda",
            sourceUrl = "https://shop.example/anker",
            observedAt = now
        )
        val amazonResult = googleResult.copy(storeName = "Amazon.es")
        cache.write("GOOGLE_SHOPPING", "anker prime", listOf(googleResult), now)
        cache.write("AMAZON", "anker prime", listOf(amazonResult), now)
        cache.write("GOOGLE_SHOPPING", "anker prime 27k", listOf(googleResult.copy(title = "Anker Prime 27K")), now)

        cache.invalidate("GOOGLE_SHOPPING", "anker prime")

        assertNull(cache.read("GOOGLE_SHOPPING", "anker prime", now + 1L))
        assertNotNull(cache.read("AMAZON", "anker prime", now + 1L))
        assertNotNull(cache.read("GOOGLE_SHOPPING", "anker prime 27k", now + 1L))
    }


    @Test
    fun oversizedRawPayloadIsRejectedSoCallerCanCacheCompactOffersInstead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineSearchCache(context)
        val now = 1_800_400_000_000L
        val query = "oversized-cache-regression"
        val response = JSONObject()
            .put("shopping_results", org.json.JSONArray().put(
                JSONObject().put("large_description", "x".repeat(OnlineSearchCache.MAX_RAW_RESPONSE_CHARS + 100))
            ))
            .toString()

        assertTrue(response.length > OnlineSearchCache.MAX_RAW_RESPONSE_CHARS)
        assertFalse(cache.writeRaw("GOOGLE_SHOPPING", query, response, now))
        assertNull(cache.read("GOOGLE_SHOPPING", query, now + 1L))
    }

}
