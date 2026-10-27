package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ProductEntity
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.repository.ProductRepository
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
class ArchivedVariantPersistenceTest {
    @Test
    fun genericArchivedTitleIsSavedOnlyWhenTheCapturedUrlConfirmsTheExactVariant() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val productId = "iphone-15-plus-256-url-validated"
        val title = "Apple iPhone 15 Plus 256GB"
        val originalUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb"
        val rawTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L))
        val observedAt = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://web.archive.org/web/" + rawTimestamp + "id_/" + originalUrl

        try {
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Apple",
                    category = "SMARTPHONES",
                    currentPrice = 899.99,
                    athMinPrice = 899.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "PcComponentes",
                    athMaxPrice = 899.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "pccomponentes",
                    storeUrl = "https://www.google.com/shopping/product/123456789",
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
            val genericHeading = ArchivedPriceQuote(
                observedAt = observedAt,
                price = 799.99,
                currency = "EUR",
                productTitle = "Apple iPhone 15 Plus",
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl,
                variantIdentityConfirmedByUrl = false
            )

            val rejectedGenericQuote = repository.recordArchivedPriceHistoryDetailed(
                expectedTitle = title,
                originalUrl = originalUrl,
                storeName = "PcComponentes",
                quotes = listOf(genericHeading),
                targetProductId = productId
            )
            assertEquals(
                "A generic HTML title must not be saved without URL proof of the missing capacity",
                0,
                rejectedGenericQuote.insertedPoints
            )
            assertEquals(1, rejectedGenericQuote.rejectedQuotes)
            assertEquals(1, rejectedGenericQuote.rejectionReasons["PRODUCT_IDENTITY_MISMATCH"])
            assertEquals(
                "The same captured HTML can be saved when the parser verified the exact URL variant",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(genericHeading.copy(variantIdentityConfirmedByUrl = true)),
                    targetProductId = productId
                )
            )
            val savedObservation = database.priceObservationDao()
                .observeVerifiedHistory(productId).first().single()
            assertEquals(
                "Room must keep the canonical selected product title after URL validation, not the generic HTML heading",
                title,
                savedObservation.productTitle
            )
            assertTrue(
                "The reason for accepting a generic archived heading must be traceable",
                savedObservation.verificationNote.orEmpty().contains("URL de captura confirmó la variante exacta")
            )

            val restored = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { it.id == productId && it.priceHistory.any { point -> point.price == 799.99 } }
                }.first { it.id == productId }
            }
            assertEquals(1, restored.priceHistory.size)
            assertEquals(799.99, restored.priceHistory.single().price, 0.001)
            assertEquals(observedAt, restored.priceHistory.single().timestamp)
            assertFalse("Archived history must never turn the saved live price into a verified current quote",
                restored.isPriceVerified)
            assertEquals(899.99, restored.currentPrice, 0.001)
        } finally {
            database.close()
        }
    }
}
