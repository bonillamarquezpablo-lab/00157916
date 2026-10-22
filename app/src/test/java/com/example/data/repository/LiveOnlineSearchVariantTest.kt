package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.remote.SerpApiShoppingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveOnlineSearchVariantTest {

    @Test
    fun liveVisibilityKeepsValidPricedOffersAcrossCategoriesEvenWhenHistoryRejectsThem() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            fun offer(title: String, price: Double, observedAt: Long = now) = SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=" +
                    java.net.URLEncoder.encode(title, "UTF-8"),
                observedAt = observedAt,
                condition = "new"
            )

            val examples = listOf(
                offer("Apple iPhone 16", 574.99),
                offer("Sony WH-1000XM5 Wireless Headphones", 199.99),
                offer("Alternador VW Polo 9N 1.9 SDI ASY", 89.90),
                offer("Pantalla Original Xiaomi Mi 10 Pro Versión S", 42.50),
                offer("Funda para Apple iPhone 16", 19.99),
                offer("Samsung OLED TV 55 inch", 799.00)
            )
            examples.forEach { result ->
                val visible = repository.filterVisibleLiveSearchResults(listOf(result), result.title)
                assertEquals(
                    "A priced live offer should remain visible in its own valid category: ${result.title}",
                    1,
                    visible.size
                )
            }

            // Older cached evidence or high-priced equipment can fall outside the historical
            // storage policy without becoming invisible in the live search list.
            val highValueOldOffer = offer("Industrial CNC milling machine X1000", 99_999_999.0, observedAt = 1L)
            assertEquals(
                1,
                repository.filterVisibleLiveSearchResults(
                    listOf(highValueOldOffer),
                    highValueOldOffer.title
                ).size
            )
            assertFalse(
                "History policy must remain stricter than live visibility",
                repository.filterOnlineSearchResults(
                    listOf(highValueOldOffer),
                    highValueOldOffer.title
                ).isNotEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun broadIphone16LiveSearchKeepsSiblingModelsButProSearchDoesNotMixPlusOrProMax() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            fun offer(title: String, price: Double) = SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=" +
                    java.net.URLEncoder.encode(title, "UTF-8"),
                observedAt = now,
                condition = "new"
            )

            val rawOffers = listOf(
                offer("Apple iPhone 16", 574.99),
                offer("Apple iPhone 16 Pro 256 GB", 899.00),
                offer("Apple iPhone 16 Plus", 749.00),
                offer("Apple iPhone 16 Pro Max 512 GB", 999.00)
            )

            val broadResults = repository.filterOnlineSearchResults(rawOffers, "iphone 16")
            assertEquals(
                "A broad iPhone 16 search should let the user choose each model family separately",
                4,
                broadResults.size
            )
            assertTrue(broadResults.any { it.title == "Apple iPhone 16" })
            assertTrue(broadResults.any { it.title == "Apple iPhone 16 Pro 256 GB" })
            assertTrue(broadResults.any { it.title == "Apple iPhone 16 Plus" })
            assertTrue(broadResults.any { it.title == "Apple iPhone 16 Pro Max 512 GB" })

            val proResults = repository.filterOnlineSearchResults(rawOffers, "iphone 16 pro")
            assertEquals(
                "A specific iPhone 16 Pro search must not return base, Plus or Pro Max models",
                1,
                proResults.size
            )
            assertEquals("Apple iPhone 16 Pro 256 GB", proResults.single().title)
        } finally {
            database.close()
        }
    }
}
