package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.model.ProductCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Calendar
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
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
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
                live(220.0, todayStart - 36L * 60L * 60L * 1000L, "old"),
                live(190.0, todayStart - 12L * 60L * 60L * 1000L, "middle"),
                live(200.0, now, "current"),
                // Simulate a row saved by an older v8 build before its provenance marker was repaired.
                PriceObservationEntity(
                    productId = productId,
                    storeId = "sample-store-archive",
                    price = 129.99,
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
                    price = 149.99,
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
                products.any { it.id == productId }
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
            assertEquals("archive rows must not change 'Historial desde'",
                todayStart - 36L * 60L * 60L * 1000L,
                product.priceHistory.filterNot { it.isArchiveEvidence }.minOf { it.timestamp })
            assertTrue(product.priceHistory.any { it.sourceUrl == archiveUrl1 })

            val mismatchedCaptureTime = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse("20260901000000")!!
                .time
            val mismatchedArchiveInserted = repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                storeName = "Tienda de prueba",
                quotes = listOf(
                    ArchivedPriceQuote(
                        observedAt = mismatchedCaptureTime,
                        price = 129.99,
                        currency = "EUR",
                        productTitle = "Apple iPhone 17 Pro Max",
                        originalUrl = "https://shop.example/product/anker-prime",
                        snapshotUrl = "https://web.archive.org/web/20260901000000id_/https://shop.example/product/anker-prime"
                    )
                )
            )
            assertEquals("a capture describing a different product must never enter this history", 0, mismatchedArchiveInserted)
            assertFalse(
                database.priceObservationDao().observeVerifiedHistory(productId).first()
                    .any { it.productTitle == "Apple iPhone 17 Pro Max" }
            )

            val wrongSnapshotTargetInserted = repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                storeName = "Tienda de prueba",
                quotes = listOf(
                    ArchivedPriceQuote(
                        observedAt = mismatchedCaptureTime,
                        price = 19.99,
                        currency = "EUR",
                        productTitle = title,
                        originalUrl = "https://shop.example/product/anker-prime",
                        snapshotUrl = "https://web.archive.org/web/20260901000000id_/https://other.example/product/anker-prime"
                    )
                )
            )
            assertEquals(
                "a Wayback snapshot for a different merchant URL must not be stored",
                0,
                wrongSnapshotTargetInserted
            )

            val wrongCasePathInserted = repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                storeName = "Tienda de prueba",
                quotes = listOf(
                    ArchivedPriceQuote(
                        observedAt = mismatchedCaptureTime,
                        price = 19.99,
                        currency = "EUR",
                        productTitle = title,
                        originalUrl = "https://shop.example/product/anker-prime",
                        snapshotUrl = "https://web.archive.org/web/20260901000000id_/https://shop.example/Product/Anker-Prime"
                    )
                )
            )
            assertEquals(
                "a snapshot of a different case-sensitive merchant path must be rejected",
                0,
                wrongCasePathInserted
            )

            val malformedOriginalUrlInserted = repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                storeName = "Tienda de prueba",
                quotes = listOf(
                    ArchivedPriceQuote(
                        observedAt = mismatchedCaptureTime,
                        price = 19.99,
                        currency = "EUR",
                        productTitle = title,
                        originalUrl = "/relative/path",
                        snapshotUrl = "https://web.archive.org/web/20260901000000id_/https://shop.example/product/anker-prime"
                    )
                )
            )
            assertEquals(
                "malformed quote URLs must be rejected without crashing the import",
                0,
                malformedOriginalUrlInserted
            )

            val insecureSnapshotInserted = repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                storeName = "Tienda de prueba",
                quotes = listOf(
                    ArchivedPriceQuote(
                        observedAt = mismatchedCaptureTime,
                        price = 19.99,
                        currency = "EUR",
                        productTitle = title,
                        originalUrl = "https://shop.example/product/anker-prime",
                        snapshotUrl = "http://web.archive.org/web/20260901000000id_/https://shop.example/product/anker-prime"
                    )
                )
            )
            assertEquals(
                "an insecure HTTP Wayback URL must not be stored as historical evidence",
                0,
                insecureSnapshotInserted
            )

            // Re-running a backfill must not duplicate a capture already in Room.
            val validSnapshotUrl =
                "https://web.archive.org/web/20260901000000id_/https://shop.example/product/anker-prime"
            val validQuote = ArchivedPriceQuote(
                observedAt = mismatchedCaptureTime,
                price = 19.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                snapshotUrl = validSnapshotUrl
            )
            assertEquals(
                "the first valid archived capture should be persisted",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = "https://shop.example/product/anker-prime",
                    storeName = "Tienda de prueba",
                    quotes = listOf(validQuote)
                )
            )
            assertEquals(
                "importing the same archived capture again must be a no-op",
                0,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = "https://shop.example/product/anker-prime",
                    storeName = "Tienda de prueba",
                    quotes = listOf(validQuote)
                )
            )
            assertEquals(
                "only one row should exist for this Wayback snapshot",
                1,
                database.priceObservationDao().observeVerifiedHistory(productId).first()
                    .count { it.sourceUrl == validSnapshotUrl }
            )

            val sevenYearsAgo = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse("20191001000000")!!
                .time
            val sevenYearSnapshotUrl =
                "https://web.archive.org/web/20191001000000id_/https://shop.example/product/anker-prime"
            val sevenYearQuote = ArchivedPriceQuote(
                observedAt = sevenYearsAgo,
                price = 19.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = "https://shop.example/product/anker-prime",
                snapshotUrl = sevenYearSnapshotUrl
            )
            assertEquals(
                "a valid archive capture from seven years ago is within the eight-year persistence window",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = "https://shop.example/product/anker-prime",
                    storeName = "Tienda de prueba",
                    quotes = listOf(sevenYearQuote)
                )
            )
            assertTrue(
                "the seven-year capture must be present in verified historical observations",
                database.priceObservationDao().observeVerifiedHistory(productId).first()
                    .any { it.sourceUrl == sevenYearSnapshotUrl }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun dailyLiveLookupSkipsArchiveUrlEvenWhenLegacyEvidenceMarkerSaysLive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = dayStart
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            // Midday is safely inside the local day, even when the test happens at midnight.
            val observationAt = dayStart + 12L * 60L * 60L * 1000L

            database.priceObservationDao().insertAll(
                listOf(
                    PriceObservationEntity(
                        productId = "same-day-product",
                        storeId = "same-store",
                        price = 4.99,
                        observedAt = observationAt - 1_000L,
                        sourceUrl = "https://web.archive.org/web/20261015000000id_/https://shop.example/item",
                        sourceName = "Legacy archive row",
                        isVerified = true,
                        storeName = "Tienda de prueba",
                        evidenceType = "live"
                    ),
                    PriceObservationEntity(
                        productId = "same-day-product",
                        storeId = "same-store",
                        price = 49.99,
                        observedAt = observationAt,
                        sourceUrl = "https://shop.example/item",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        storeName = "Tienda de prueba",
                        evidenceType = "live"
                    )
                )
            )

            val selected = database.priceObservationDao().getObservationForStoreProductDay(
                productId = "same-day-product",
                storeId = "same-store",
                dayStart = dayStart,
                dayEnd = dayEnd
            )

            assertEquals("The live seller URL must be selected instead of a Wayback snapshot",
                "https://shop.example/item", selected?.sourceUrl)
            assertEquals(49.99, selected?.price ?: 0.0, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentDailyObservationWritesDoNotCreateDuplicateRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val now = System.currentTimeMillis()
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dayEnd = Calendar.getInstance().apply {
                timeInMillis = dayStart
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            val productId = "concurrent-daily-write"
            val observationAt = dayStart + 12L * 60L * 60L * 1000L

            fun observation(price: Double) = PriceObservationEntity(
                productId = productId,
                storeId = "same-store",
                price = price,
                currency = "EUR",
                observedAt = observationAt,
                sourceUrl = "https://shop.example/item?price=$price",
                sourceName = "Google Shopping",
                isVerified = true,
                productTitle = "Test product",
                storeName = "Tienda de prueba",
                evidenceType = "live"
            )

            coroutineScope {
                listOf(49.99, 39.99).map { price ->
                    async {
                        database.priceObservationDao().saveDailyObservationIfNewer(
                            observation = observation(price),
                            dayStart = dayStart,
                            dayEnd = dayEnd
                        )
                    }
                }.forEach { it.await() }
            }

            val rows = database.priceObservationDao()
                .observeVerifiedHistory(productId)
                .first()
            assertEquals("Concurrent refreshes must leave one row for the day", 1, rows.size)
            assertEquals("The lower price wins when timestamps are identical", 39.99, rows.single().price, 0.001)
        } finally {
            database.close()
        }
    }

}
