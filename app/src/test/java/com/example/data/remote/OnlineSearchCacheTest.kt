package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
            primeEligible = true
        )

        cache.write("AMAZON", "Anker Prime 20.000 mAh", listOf(result), now)
        val cached = cache.read("amazon", "  anker   prime 20.000 mAh ", now + 30_000L)

        assertNotNull(cached)
        assertEquals(1, cached!!.results.size)
        assertEquals(79.99, cached.results[0].price, 0.001)
        assertEquals("Amazon.es", cached.results[0].storeName)
        assertEquals("Entrega GRATIS con Prime", cached.results[0].delivery)
        assertTrue(cached.results[0].primeEligible)
        assertEquals(4.7, cached.results[0].rating!!, 0.001)
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
}
