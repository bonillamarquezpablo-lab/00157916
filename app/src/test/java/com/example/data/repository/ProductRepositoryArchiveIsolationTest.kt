package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.model.ProductCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductRepositoryArchiveIsolationTest {

    @Test
    fun waybackRowsDoNotChangeCurrentMinimumBadgesOrHistoryStart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val now = System.currentTimeMillis()
            val productId = "archive-isolation-test"
            val title = "Anker Prime 27K Power Bank"
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Anker",
                    category = ProductCategory.ALL.name,
                    currentPrice = 250.0,
                    athMinPrice = 125.0,
                    athMinDate = "legacy",
                    athMinStore = "legacy",
                    athMaxPrice = 300.0,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "sample-store",
                    storeUrl = "https://shop.example/product/anker-prime",
                    ean = "",
                    isPriceVerified = true
                )
            )

            fun live(price: Double, observedAt: Long, suffix: String) = PriceObservationEntity(
                productId = productId,
                storeId = "sample-store-$suffix",
                price = price,
                currency = "EUR",
                observedAt = observedAt,
                sourceUrl = "https://shop.example/product/anker-prime?offer=$suffix",
                sourceName = "SerpApi · Google Shopping",
                isVerified = true,
                productTitle = title,
                storeName = "Tienda de prueba",
                sourceType = "Google Shopping",
                linkType = "directo",
                collectedAt = now,
                evidenceType = "live"
            )

            val archiveUrl1 = "https://web.archive.org/web/20251001000000id_/https://shop.example/product/anker-prime"
            val archiveUrl2 = "https://web.archive.org/web/20260201000000id_/https://shop.example/product/anker-prime"
            val observations = listOf(
                live(220.0, now - 30L * 60L * 60L * 1000L, "old"),
                live(190.0, now - 18L * 60L * 60L * 1000L, "middle"),
                live(200.0, now - 6L * 60L * 60L * 1000L, "current"),
                // Simulate a row saved by an older v8 build before its provenance marker was repaired.
                PriceObservationEntity(
                    productId = productId,
                    storeId = "sample-store-archive",
                    price = 5.0,
                    currency = "EUR",
                    observedAt = now - 400L * 24L * 60L * 60L * 1000L,
                    sourceUrl = archiveUrl1,
                    sourceName = "Internet Archive (Wayback Machine)",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Tienda de prueba",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "live",
                    sourcePageUrl = "https://shop.example/product/anker-prime"
                ),
                PriceObservationEntity(
                    productId = productId,
                    storeId = "sample-store-archive-2",
                    price = 7.0,
                    currency = "EUR",
                    observedAt = now - 150L * 24L * 60L * 60L * 1000L,
                    sourceUrl = archiveUrl2,
                    sourceName = "Internet Archive (Wayback Machine)",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Tienda de prueba",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "archive",
                    sourcePageUrl = "https://shop.example/product/anker-prime"
                )
            )
            database.priceObservationDao().insertAll(observations)

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val product = repository.allProductsStream.first { products ->
                products.any { it.id == productId && it.priceHistory.size >= 5 }
            }.first { it.id == productId }

            assertEquals("live provider's current quote remains current", 200.0, product.currentPrice, 0.001)
            assertEquals("archive prices cannot set the observed minimum", 190.0, product.athMinPrice, 0.001)
            assertEquals("archive prices cannot set the observed maximum", 220.0, product.athMaxPrice, 0.001)
            assertEquals("both archived rows remain available for the separate archive section", 2,
                product.priceHistory.count { it.isArchiveEvidence })
            assertFalse("archive dates must not create a 90-day live history", product.hasSufficientHistory)

            val liveOnly = product.copy(priceHistory = product.priceHistory.filterNot { it.isArchiveEvidence })
            assertEquals("archive rows must not change the badge classification",
                liveOnly.priceBadgeState, product.priceBadgeState)
            assertEquals("archive rows must not change the live chart's oldest point",
                liveOnly.priceHistory.minOf { it.timestamp }, product.priceHistory
                    .filterNot { it.isArchiveEvidence }.minOf { it.timestamp })
            assertTrue(product.priceHistory.any { it.sourceUrl == archiveUrl1 })
        } finally {
            database.close()
        }
    }
}
