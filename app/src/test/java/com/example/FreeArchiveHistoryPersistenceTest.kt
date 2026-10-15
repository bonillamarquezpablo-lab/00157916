package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ProductEntity
import com.example.data.local.PriceObservationEntity
import com.example.data.repository.ProductRepository
import com.example.data.remote.ArchivedPriceQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreeArchiveHistoryPersistenceTest {

    @Test
    fun archivedPricesAreVisibleWithoutVerifyingCurrentPriceAndImportsAreDeduplicated() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L))
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"

        try {
            database.productDao().insert(
                ProductEntity(
                    id = "anker-prime-27k-test",
                    title = title,
                    brand = "Anker",
                    category = "HOME_APPLIANCES",
                    currentPrice = 499.99,
                    athMinPrice = 499.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "Tienda de prueba",
                    athMaxPrice = 499.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "pccomponentes",
                    storeUrl = originalUrl,
                    ean = "",
                    isPriceVerified = false
                )
            )

            // A stale, unverified archive row must not block a fresh capture for the same
            // product/store/day merely because its placeholder price happens to be lower.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "anker-prime-27k-test",
                    storeId = "pccomponentes",
                    price = 1.00,
                    currency = "EUR",
                    observedAt = archivedAt,
                    sourceUrl = snapshotUrl,
                    sourceName = "Legacy unverified archive",
                    isVerified = false,
                    verificationNote = "Unverified fixture; must not block a validated capture.",
                    productTitle = title,
                    storeName = "PcComponentes",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = System.currentTimeMillis(),
                    evidenceType = "archive",
                    sourcePageUrl = originalUrl
                )
            )

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val quote = ArchivedPriceQuote(
                observedAt = archivedAt,
                price = 89.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl
            )

            assertEquals(
                "A trusted historical quote should save even when the current quote is unverified",
                1,
                repository.recordArchivedPriceHistory(title, originalUrl, "PcComponentes", listOf(quote))
            )
            assertEquals(
                "Re-importing the same archived day must not create another archive row",
                0,
                repository.recordArchivedPriceHistory(title, originalUrl, "Tienda de prueba", listOf(quote))
            )

            val product = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { row ->
                        row.id == "anker-prime-27k-test" &&
                            row.priceHistory.any { it.eventLabel.equals("Internet Archive", ignoreCase = true) }
                    }
                }.first { it.id == "anker-prime-27k-test" }
            }
            assertFalse("An archived quote must not mark the current price verified", product.isPriceVerified)
            assertEquals("The saved current price must not be overwritten by a historic quote", 499.99, product.currentPrice, 0.001)
            assertEquals(1, product.priceHistory.size)
            assertEquals("Internet Archive", product.priceHistory.single().eventLabel)
            assertEquals(89.99, product.priceHistory.single().price, 0.001)
            assertEquals(archivedAt, product.priceHistory.single().timestamp)
            assertTrue("Historical points are available even without a verified live quote", product.priceHistory.isNotEmpty())
        } finally {
            database.close()
        }
    }
 
    @Test
    fun archivedSnapshotMustValidateMerchantAgainstOriginalRetailerUrl() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L))
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"

        try {
            database.productDao().insert(
                ProductEntity(
                    id = "anker-prime-27k-seller-mismatch",
                    title = title,
                    brand = "Anker",
                    category = "HOME_APPLIANCES",
                    currentPrice = 499.99,
                    athMinPrice = 499.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "PcComponentes",
                    athMaxPrice = 499.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "pccomponentes",
                    storeUrl = originalUrl,
                    ean = "",
                    isPriceVerified = false
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val quote = ArchivedPriceQuote(
                observedAt = archivedAt,
                price = 89.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl
            )

            // Reject contradictory seller metadata before persistence. Marking such a row as
            // verified could pollute other history/aggregate queries even if the chart hides it.
            assertEquals(
                0,
                repository.recordArchivedPriceHistory(title, originalUrl, "Amazon.es", listOf(quote))
            )
            assertEquals(0, database.priceObservationDao().getObservationCount("anker-prime-27k-seller-mismatch"))

            val product = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { it.id == "anker-prime-27k-seller-mismatch" }
                }.first { it.id == "anker-prime-27k-seller-mismatch" }
            }
            assertFalse(product.isPriceVerified)
            assertEquals(499.99, product.currentPrice, 0.001)
            assertTrue(
                "A snapshot labelled Amazon must not be trusted for a PcComponentes URL",
                product.priceHistory.isEmpty()
            )
        } finally {
            database.close()
        }
    }

}
