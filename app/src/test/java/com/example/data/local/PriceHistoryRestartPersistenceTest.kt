package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ProductCategory
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceHistoryRestartPersistenceTest {

    @Test
    fun verifiedPriceHistorySurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "price-history-restart-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var database: AppDatabase? = null

        fun openDatabase(): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4,
                    AppDatabase.MIGRATION_4_5,
                    AppDatabase.MIGRATION_5_6,
                    AppDatabase.MIGRATION_6_7,
                    AppDatabase.MIGRATION_7_8,
                    AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10
                )
                .allowMainThreadQueries()
                .build()

        try {
            val now = System.currentTimeMillis()
            val productId = "restart-history-iphone-15-plus"
            val title = "Apple iPhone 15 Plus 256GB"
            database = openDatabase()
            database!!.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Apple",
                    category = ProductCategory.SMARTPHONES.name,
                    currentPrice = 859.93,
                    athMinPrice = 859.93,
                    athMinDate = "Historial en construcción",
                    athMinStore = "PcComponentes",
                    athMaxPrice = 859.93,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "pccomponentes",
                    storeUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb",
                    ean = "",
                    // Simulate a stale persisted summary: valid saved observations must restore it.
                    isPriceVerified = false
                )
            )

            val olderObservedAt = now - TimeUnit.DAYS.toMillis(30)
            val latestObservedAt = now - TimeUnit.HOURS.toMillis(1)
            database!!.priceObservationDao().insertAll(
                listOf(
                    PriceObservationEntity(
                        productId = productId,
                        storeId = "pccomponentes",
                        price = 799.00,
                        currency = "EUR",
                        observedAt = olderObservedAt,
                        sourceUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb?observation=older",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = title,
                        storeName = "PcComponentes",
                        sourceType = "Google Shopping",
                        linkType = "directo",
                        collectedAt = olderObservedAt,
                        evidenceType = "live"
                    ),
                    PriceObservationEntity(
                        productId = productId,
                        storeId = "pccomponentes",
                        price = 859.93,
                        currency = "EUR",
                        observedAt = latestObservedAt,
                        sourceUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb?observation=latest",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = title,
                        storeName = "PcComponentes",
                        sourceType = "Google Shopping",
                        linkType = "directo",
                        collectedAt = latestObservedAt,
                        evidenceType = "live"
                    )
                )
            )
            database!!.close()
            database = null

            // Reopen the same on-disk database, just as a fresh app process does.
            database = openDatabase()
            val reopenedRepository = ProductRepository(
                storeDao = database!!.storeDao(),
                productDao = database!!.productDao(),
                trackedDao = database!!.trackedProductDao(),
                priceObservationDao = database!!.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val restored = withTimeout(10_000L) {
                reopenedRepository.allProductsStream.first { products ->
                    products.any { it.id == productId && it.priceHistory.size >= 2 }
                }.first { it.id == productId }
            }

            assertTrue("A validated saved live price must restore the current verified state", restored.isPriceVerified)
            assertEquals("The latest saved live quote should remain current", 859.93, restored.currentPrice, 0.001)
            assertEquals("The older observed minimum must still be available", 799.00, restored.athMinPrice, 0.001)
            assertEquals("Both observations must reappear in the rebuilt graph data", 2, restored.priceHistory.size)
            assertTrue("The old saved observation must be retained", restored.priceHistory.any { it.price == 799.00 })
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }
}
