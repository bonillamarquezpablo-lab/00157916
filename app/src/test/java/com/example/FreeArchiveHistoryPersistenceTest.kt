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
    fun archivedPricesWithNonCanonicalEuroCurrencyRemainVisibleAndDeduplicated() = runBlocking<Unit> {
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
                    storeUrl = "https://www.google.com/shopping/product/123456789?hl=es",
                    ean = "",
                    // Simulate a stale cached flag from an old live quote.
                    isPriceVerified = true
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

            database.productDao().markProductsWithVerifiedObservations()
            assertFalse(
                "Archive-only/unverified observations must clear a stale positive current-price flag",
                database.productDao().getProductById("anker-prime-27k-test")!!.isPriceVerified
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
                currency = " eur ",
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

            val alternateSellerUrl = "https://www.amazon.es/dp/B0TESTANKER"
            val alternateSellerQuote = quote.copy(
                originalUrl = alternateSellerUrl,
                snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${alternateSellerUrl}"
            )
            assertEquals(
                "An unverified product may not accept history from another seller URL",
                0,
                repository.recordArchivedPriceHistory(
                    title,
                    alternateSellerUrl,
                    "Amazon.es",
                    listOf(alternateSellerQuote)
                )
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
    fun invalidLegacyArchiveRowDoesNotBlockValidatedCaptureForSameDay() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val productId = "archive-invalid-duplicate-test"
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(
            Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L)
        )
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        // CDX contains a valid older capture whose embedded original URL is HTTP.
        // The wrapper stays HTTPS, and product identity is compared to the current HTTPS URL.
        val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/http://www.pccomponentes.com/anker-prime-27k"

        try {
            database.productDao().insert(
                ProductEntity(
                    id = productId,
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
            // Legacy row is labelled archive and verified, but its source URL is the live
            // retailer page, not an archived snapshot. It is filtered from the chart and must
            // not count as an existing valid archive point.
            val malformedArchiveId = database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "pccomponentes",
                    price = 89.99,
                    currency = "EUR",
                    observedAt = archivedAt,
                    sourceUrl = originalUrl,
                    sourceName = "Legacy malformed archive",
                    isVerified = true,
                    productTitle = title,
                    storeName = "PcComponentes",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = System.currentTimeMillis(),
                    evidenceType = "archive",
                    sourcePageUrl = originalUrl
                )
            )
            assertFalse(
                "The DAO verified-history query must not expose an archive-labelled row whose URL is a live retailer page",
                database.priceObservationDao().observeVerifiedHistory(productId).first()
                    .any { it.id == malformedArchiveId }
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
                price = 99.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = originalUrl,
                snapshotUrl = snapshotUrl
            )

            assertEquals(
                "A malformed legacy row must not block a validated archived capture for that day",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(quote)
                )
            )
            assertEquals(
                "A valid archived snapshot with an HTTP original URL must remain visible in DAO history",
                1,
                database.priceObservationDao().observeVerifiedHistory(productId).first()
                    .count { it.sourceUrl == snapshotUrl }
            )
            val restored = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { row ->
                        row.id == productId && row.priceHistory.any { it.isArchiveEvidence }
                    }
                }.first { it.id == productId }
            }
            assertEquals(1, restored.priceHistory.count { it.isArchiveEvidence })
            assertEquals(99.99, restored.priceHistory.single { it.isArchiveEvidence }.price, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyMementoUrlIsRecognizedByArchiveDailyDeduplication() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val productId = "legacy-memento-dedup-test"
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(
            Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L)
        )
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val mementoUrl = "https://arquivo.pt/wayback/$rawTimestamp/$originalUrl"

        try {
            // Legacy imports sometimes have a Memento URL but retain the old "live" evidence label.
            // Archive identity must therefore be detected from the URL as well as evidenceType.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "pccomponentes",
                    price = 89.99,
                    currency = "EUR",
                    observedAt = archivedAt,
                    sourceUrl = mementoUrl,
                    sourceName = "Legacy Memento",
                    isVerified = true,
                    productTitle = title,
                    storeName = "PcComponentes",
                    sourceType = "Memento",
                    linkType = "Archivo histórico",
                    collectedAt = System.currentTimeMillis(),
                    evidenceType = "live",
                    sourcePageUrl = originalUrl
                )
            )
            val day = java.util.Calendar.getInstance().apply {
                timeInMillis = archivedAt
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = day.timeInMillis
            val dayEnd = (day.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis

            val dao = database.priceObservationDao()
            assertEquals(
                "Legacy Memento rows must participate in same-day archive deduplication",
                1,
                dao.getArchivedObservationsForStoreProductDay(
                    productId, "pccomponentes", dayStart, dayEnd
                ).size
            )
            assertTrue(
                "The single-row archive lookup must also recognize Memento URLs",
                dao.getArchivedObservationForStoreProductDay(
                    productId, "pccomponentes", dayStart, dayEnd
                ) != null
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun mementoArchiveCapturePersistsAndAppearsInSavedPriceHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "memento-history-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
            )
            .allowMainThreadQueries()
            .build()
        val productId = "anker-memento-restart"
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L))
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://arquivo.pt/wayback/$rawTimestamp/$originalUrl"

        try {
            database.productDao().insert(
                ProductEntity(
                    id = productId,
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
                snapshotUrl = snapshotUrl,
                targetProductId = productId
            )

            assertEquals(
                "A Memento quote must be accepted only for the explicitly selected existing product",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(quote),
                    targetProductId = productId
                )
            )
            val restored = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { row ->
                        row.id == productId && row.priceHistory.any { it.isArchiveEvidence }
                    }
                }.first { it.id == productId }
            }
            assertEquals(499.99, restored.currentPrice, 0.001)
            assertFalse("A Memento snapshot must not verify today's price", restored.isPriceVerified)
            assertEquals(1, restored.priceHistory.count { it.isArchiveEvidence })
            assertEquals(89.99, restored.priceHistory.single { it.isArchiveEvidence }.price, 0.001)
            assertTrue(restored.priceHistory.single { it.isArchiveEvidence }.eventLabel.orEmpty().contains("Memento"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun selectedGoogleShoppingProductCanSaveHistoryFromItsChosenRetailerOffer() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val productId = "anker-selected-google-only"
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
                    id = productId,
                    title = title,
                    brand = "Anker",
                    category = "HOME_APPLIANCES",
                    currentPrice = 499.99,
                    athMinPrice = 499.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "Google Shopping",
                    athMaxPrice = 499.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "google_shopping",
                    storeUrl = "https://www.google.com/shopping/product/123456789?hl=es",
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

            assertEquals(
                "Without product scope, an indirect Google row must not accept an arbitrary seller",
                0,
                repository.recordArchivedPriceHistory(title, originalUrl, "PcComponentes", listOf(quote))
            )
            assertEquals(
                "A retailer offer selected from this exact product can be persisted safely",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(quote),
                    targetProductId = productId
                )
            )
            val saved = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { row ->
                        row.id == productId &&
                            row.priceHistory.any { it.eventLabel.equals("Internet Archive", ignoreCase = true) }
                    }
                }.first { it.id == productId }
            }
            assertEquals(499.99, saved.currentPrice, 0.001)
            assertFalse(saved.isPriceVerified)
            assertTrue(saved.priceHistory.any { it.isArchiveEvidence })
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
            val lookalikeGoogleUrl = "https://google.attacker.example/anker-prime-27k"
            val lookalikeQuote = quote.copy(
                originalUrl = lookalikeGoogleUrl,
                snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${lookalikeGoogleUrl}"
            )
            assertEquals(
                "A lookalike Google hostname must not be trusted as a retailer/archive target",
                0,
                repository.recordArchivedPriceHistory(
                    title,
                    lookalikeGoogleUrl,
                    "Tienda desconocida",
                    listOf(lookalikeQuote)
                )
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

    @Test
    fun archivedHistoryUsesExactRetailerUrlWhenTitlesAreDuplicated() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        val unrelatedUrl = "https://www.amazon.es/dp/B0ANKERPRIME"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L))
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"

        fun product(id: String, url: String, storeId: String) = ProductEntity(
            id = id,
            title = title,
            brand = "Anker",
            category = "HOME_APPLIANCES",
            currentPrice = 499.99,
            athMinPrice = 499.99,
            athMinDate = "Historial en construcción",
            athMinStore = storeId,
            athMaxPrice = 499.99,
            avg90dPrice = 0.0,
            officialPvp = 0.0,
            currentStoreId = storeId,
            storeUrl = url,
            ean = "",
            isPriceVerified = false
        )

        try {
            // Same model title, different retailer URL. The archive must not be attached to
            // whichever matching title happens to come first in Room's result order.
            database.productDao().insert(product("duplicate-amazon", unrelatedUrl, "amazon_es"))
            database.productDao().insert(product("duplicate-pccomponentes", originalUrl, "pccomponentes"))
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

            assertEquals(1, repository.recordArchivedPriceHistory(title, originalUrl, "PcComponentes", listOf(quote)))
            assertEquals("Do not contaminate the Amazon listing", 0, database.priceObservationDao().getObservationCount("duplicate-amazon"))
            assertEquals("Save history on the matching retailer listing", 1, database.priceObservationDao().getObservationCount("duplicate-pccomponentes"))
        } finally {
            database.close()
        }
    }

    @Test
    fun googleShoppingOnlyHistoryIsRejectedWhenDuplicateProductCardsAreAmbiguous() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        val googleOnlyUrl = "https://www.google.com/shopping/product/123456789?hl=es"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rawTimestamp = timestampFormat.format(
            Date(System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L)
        )
        val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
        val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"

        fun product(id: String, storeId: String) = ProductEntity(
            id = id,
            title = title,
            brand = "Anker",
            category = "HOME_APPLIANCES",
            currentPrice = 499.99,
            athMinPrice = 499.99,
            athMinDate = "Historial en construcción",
            athMinStore = storeId,
            athMaxPrice = 499.99,
            avg90dPrice = 0.0,
            officialPvp = 0.0,
            currentStoreId = storeId,
            storeUrl = googleOnlyUrl,
            ean = "",
            isPriceVerified = false
        )

        try {
            database.productDao().insert(product("google-only-duplicate-a", "pccomponentes"))
            database.productDao().insert(product("google-only-duplicate-b", "amazon_es"))
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
                "Two cards with the same title and only Google links must not receive an arbitrary archive match",
                0,
                repository.recordArchivedPriceHistory(title, originalUrl, "PcComponentes", listOf(quote))
            )
            assertEquals(0, database.priceObservationDao().getObservationCount("google-only-duplicate-a"))
            assertEquals(0, database.priceObservationDao().getObservationCount("google-only-duplicate-b"))
        } finally {
            database.close()
        }
    }

    @Test
    fun onlineProductLookupReturnsUnverifiedProductWithoutWaitingForHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        try {
            database.productDao().insert(
                ProductEntity(
                    id = "unverified-no-history", title = title, brand = "Anker",
                    category = "HOME_APPLIANCES", currentPrice = 499.99,
                    athMinPrice = 499.99, athMinDate = "Historial en construcción",
                    athMinStore = "Tienda de prueba", athMaxPrice = 499.99,
                    avg90dPrice = 0.0, officialPvp = 0.0, currentStoreId = "pccomponentes",
                    storeUrl = "https://www.pccomponentes.com/anker-prime-27k",
                    ean = "", isPriceVerified = false
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(), productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val product = withTimeout(2_000L) {
                repository.awaitProductForOnlineIdentity(
                    com.example.data.remote.onlineProductIdentityKey(title)
                )
            }
            assertEquals("unverified-no-history", product.id)
            assertFalse(product.isPriceVerified)
            assertTrue(product.priceHistory.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun archivedSnapshotsNeverQualifyAsLatestLivePriceOrPriceAlertCandidates() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.priceObservationDao()
            val now = System.currentTimeMillis()
            dao.insert(
                PriceObservationEntity(
                    productId = "archive-alert-isolation",
                    storeId = "shop",
                    price = 10.00,
                    currency = "EUR",
                    observedAt = now - 1_000L,
                    sourceUrl = "https://web.archive.org/web/20251010120000id_/https://shop.example/product",
                    sourceName = "Internet Archive",
                    isVerified = true,
                    productTitle = "Test product",
                    storeName = "Shop",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "archive",
                    sourcePageUrl = "https://shop.example/product"
                )
            )
            dao.insert(
                PriceObservationEntity(
                    productId = "archive-alert-isolation",
                    storeId = "shop",
                    price = 19.99,
                    currency = "EUR",
                    observedAt = now - 5_000L,
                    sourceUrl = "https://shop.example/product",
                    sourceName = "Google Shopping",
                    isVerified = true,
                    productTitle = "Test product",
                    storeName = "Shop",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = now,
                    evidenceType = "live"
                )
            )

            assertEquals(
                "The newest archived timestamp must not replace the latest live quote",
                "live",
                dao.getLatestVerified("archive-alert-isolation")?.evidenceType
            )
            val alertCandidates = dao.getVerifiedSince("archive-alert-isolation", now - 60_000L)
            assertEquals(1, alertCandidates.size)
            assertTrue(alertCandidates.all { it.evidenceType == "live" })
            assertEquals(19.99, alertCandidates.single().price, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidVerifiedLiveRowsCannotDriveCurrentPriceOrPriceAlerts() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.priceObservationDao()
            val now = System.currentTimeMillis()
            val valid = PriceObservationEntity(
                productId = "valid-live-query-gate",
                storeId = "shop",
                price = 19.99,
                currency = "EUR",
                observedAt = now - 5_000L,
                sourceUrl = "https://shop.example/product",
                sourceName = "Google Shopping",
                isVerified = true,
                productTitle = "Test product",
                storeName = "Shop",
                sourceType = "Google Shopping",
                linkType = "directo",
                collectedAt = now,
                evidenceType = "live"
            )
            dao.insert(valid)

            database.productDao().insert(
                ProductEntity(
                    id = "invalid-live-query-gate",
                    title = "Test product",
                    brand = "Test",
                    category = "TV_AUDIO",
                    currentPrice = 19.99,
                    athMinPrice = 19.99,
                    athMinDate = "",
                    athMinStore = "Shop",
                    athMaxPrice = 19.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "shop",
                    storeUrl = "https://shop.example/product",
                    ean = "",
                    isPriceVerified = true
                )
            )

            // Simulate old/corrupt rows whose cached isVerified flag must not make them current.
            val invalidRows = listOf(
                valid.copy(productId = "invalid-live-query-gate", price = 0.01),
                valid.copy(productId = "invalid-live-query-gate", currency = "USD", price = 3.00),
                valid.copy(
                    productId = "invalid-live-query-gate",
                    observedAt = now + 2L * 60L * 60L * 1000L,
                    price = 2.00
                ),
                valid.copy(productId = "invalid-live-query-gate", productTitle = "", price = 4.00),
                valid.copy(productId = "invalid-live-query-gate", storeName = "", price = 5.00),
                valid.copy(
                    productId = "invalid-live-query-gate",
                    sourceUrl = "http://shop.example/product",
                    price = 6.00
                )
            )
            dao.insertAll(invalidRows)

            database.productDao().markProductsWithVerifiedObservations(now)

            assertEquals(19.99, dao.getLatestVerified("valid-live-query-gate", now)?.price ?: 0.0, 0.001)
            assertEquals(1, dao.getVerifiedSince("valid-live-query-gate", now - 60_000L, now).size)
            assertEquals(null, dao.getLatestVerified("invalid-live-query-gate", now))
            assertTrue(dao.getVerifiedSince("invalid-live-query-gate", now - 60_000L, now).isEmpty())
            assertFalse(
                "Invalid stored rows must not set the product's cached current-price flag",
                database.productDao().getProductById("invalid-live-query-gate")!!.isPriceVerified
            )
            assertEquals(
                "Invalid legacy rows are excluded from valid counts but remain stored",
                1,
                dao.getVerifiedObservationCount(now)
            )
            assertEquals(
                "No stored history is deleted while it is reclassified as invalid",
                6,
                dao.getObservationCount("invalid-live-query-gate")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun googleHistoricalInsightCannotReplaceOrBlockLiveObservationOnSameDay() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.priceObservationDao()
            val now = System.currentTimeMillis()
            val dayStart = now - 60_000L
            val dayEnd = now + 60_000L
            val insight = PriceObservationEntity(
                productId = "insight-live-isolation",
                storeId = "shop",
                price = 79.99,
                currency = "EUR",
                observedAt = now - 20_000L,
                sourceUrl = "https://www.google.com/shopping/product/123456789",
                sourceName = "Google Product API · price_insights",
                isVerified = true,
                productTitle = "Test product",
                storeName = "Shop",
                sourceType = "Google Product API · price_insights",
                linkType = "Historial Google Shopping",
                collectedAt = now,
                evidenceType = "google_insights",
                sourcePageUrl = "https://www.google.com/shopping/product/123456789"
            )
            assertTrue(dao.saveGoogleInsightObservationIfLower(insight, dayStart, dayEnd))

            val live = PriceObservationEntity(
                productId = "insight-live-isolation",
                storeId = "shop",
                price = 99.99,
                currency = "EUR",
                observedAt = now - 10_000L,
                sourceUrl = "https://shop.example/product",
                sourceName = "Google Shopping",
                isVerified = true,
                productTitle = "Test product",
                storeName = "Shop",
                sourceType = "Google Shopping",
                linkType = "directo",
                collectedAt = now,
                evidenceType = "live"
            )
            assertTrue(dao.saveDailyObservationIfNewer(live, dayStart, dayEnd))

            val lowerInsight = insight.copy(price = 69.99, observedAt = now - 30_000L)
            assertTrue(dao.saveGoogleInsightObservationIfLower(lowerInsight, dayStart, dayEnd))

            val all = dao.observeVerifiedHistory("insight-live-isolation").first()
            assertEquals("Live and historical insight rows must remain separate", 2, all.size)
            assertEquals(69.99, all.single { it.evidenceType == "google_insights" }.price, 0.001)
            assertEquals(99.99, all.single { it.evidenceType == "live" }.price, 0.001)
            assertEquals("live", dao.getLatestVerified("insight-live-isolation")?.evidenceType)
            assertEquals(1, dao.getVerifiedSince("insight-live-isolation", now - 60_000L).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyArchiveUrlMislabelledLiveCannotVerifyCurrentPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.productDao().insert(
                ProductEntity(
                    id = "legacy-archive-live-label",
                    title = "Sony WH-1000XM5",
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 199.99,
                    athMinPrice = 199.99,
                    athMinDate = "",
                    athMinStore = "",
                    athMaxPrice = 199.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "shop",
                    storeUrl = "https://shop.example/sony-wh1000xm5",
                    ean = "",
                    isPriceVerified = true
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "legacy-archive-live-label",
                    storeId = "shop",
                    price = 89.99,
                    currency = "EUR",
                    observedAt = System.currentTimeMillis(),
                    sourceUrl = "https://web.archive.org/web/20251010120000id_/https://shop.example/sony-wh1000xm5",
                    sourceName = "Legacy migrated row",
                    isVerified = true,
                    productTitle = "Sony WH-1000XM5",
                    storeName = "Shop",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = System.currentTimeMillis(),
                    evidenceType = "live"
                )
            )

            database.productDao().markProductsWithVerifiedObservations()

            assertFalse(
                "A legacy row with an archive URL must not mark the product's current price verified",
                database.productDao().getProductById("legacy-archive-live-label")!!.isPriceVerified
            )
            assertEquals(
                "Archive URLs must not qualify as the latest current quote",
                null,
                database.priceObservationDao().getLatestVerified("legacy-archive-live-label")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun laterArchivedCaptureMislabelledLiveCannotOverrideSameDayLiveQuote() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            val directUrl = "https://shop.example/sony-wh1000xm5"
            val title = "Sony WH-1000XM5"
            val liveAt = now - 80_000L
            val archivedAt = now - 20_000L
            val captureTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(archivedAt))
            val snapshotUrl = "https://web.archive.org/web/${captureTimestamp}id_/${directUrl}"

            database.productDao().insert(
                ProductEntity(
                    id = "live-archive-same-day-test",
                    title = title,
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 199.99,
                    athMinPrice = 199.99,
                    athMinDate = "",
                    athMinStore = "",
                    athMaxPrice = 199.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "shop",
                    storeUrl = directUrl,
                    ean = "",
                    isPriceVerified = true
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "live-archive-same-day-test",
                    storeId = "shop",
                    price = 199.99,
                    currency = "EUR",
                    observedAt = liveAt,
                    sourceUrl = directUrl,
                    sourceName = "Google Shopping",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Shop",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = now,
                    evidenceType = "live"
                )
            )
            // Simulate a legacy DB row whose source is clearly an archive even though the label is wrong.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "live-archive-same-day-test",
                    storeId = "shop",
                    price = 10.00,
                    currency = "EUR",
                    observedAt = archivedAt,
                    sourceUrl = snapshotUrl,
                    sourceName = "Internet Archive",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Shop",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "live",
                    sourcePageUrl = directUrl
                )
            )
            database.productDao().markProductsWithVerifiedObservations()

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val product = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { it.id == "live-archive-same-day-test" && it.priceHistory.isNotEmpty() }
                }.first { it.id == "live-archive-same-day-test" }
            }

            assertTrue(product.isPriceVerified)
            assertEquals("The live quote must remain the current price", 199.99, product.currentPrice, 0.001)
            assertEquals(
                "The current/live series must contain only the live quote for the day",
                1,
                product.priceHistory.count { !it.isArchiveEvidence }
            )
            assertEquals(
                199.99,
                product.priceHistory.single { !it.isArchiveEvidence }.price,
                0.001
            )
            assertEquals(
                "The archive capture remains available as separate historical evidence",
                1,
                product.priceHistory.count { it.isArchiveEvidence }
            )
            assertTrue(
                product.priceHistory.any { it.isArchiveEvidence && it.price == 10.00 }
            )
        } finally {
            database.close()
        }
    }


    @Test
    fun plausibleLegacySavedPriceIsShownAsPendingRevalidationNotAsCurrent() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val title = "Anker Prime 27K Power Bank"
        val productId = "legacy-saved-price-recovery"
        val savedAt = System.currentTimeMillis() - 45L * 24L * 60L * 60L * 1000L

        try {
            database.productDao().insert(
                ProductEntity(
                    id = productId,
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
                    storeUrl = "https://www.pccomponentes.com/anker-prime-27k",
                    ean = "",
                    isPriceVerified = false
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "pccomponentes",
                    price = 89.99,
                    currency = "EUR",
                    observedAt = savedAt,
                    sourceUrl = "https://www.pccomponentes.com/anker-prime-27k",
                    sourceName = "Legacy saved quote",
                    isVerified = false,
                    verificationNote = "Legacy record awaiting revalidation",
                    productTitle = title,
                    storeName = "PcComponentes",
                    sourceType = "Legacy saved price",
                    linkType = "directo",
                    collectedAt = System.currentTimeMillis(),
                    evidenceType = "live"
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val product = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { it.id == productId && it.savedHistoricalPrice == 89.99 }
                }.first { it.id == productId }
            }

            assertEquals(89.99, product.savedHistoricalPrice!!, 0.001)
            assertEquals(savedAt, product.savedHistoricalPriceObservedAt)
            assertFalse("Legacy record must not become a verified current price", product.isPriceVerified)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION,
                product.priceDisplayStatus
            )
            assertTrue("Recovery must not overwrite the catalogue's placeholder/current field", product.currentPrice == 499.99)
            assertTrue("Unverified legacy record must not feed deal history", product.priceHistory.isEmpty())
        } finally {
            database.close()
        }
    }


    @Test
    fun archivedObservationsDoNotAppearAsSavedStorePrices() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val now = System.currentTimeMillis()
            val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
            val timestampFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val rawTimestamp = timestampFormat.format(Date(now - 60L * 24L * 60L * 60L * 1000L))
            val archivedAt = timestampFormat.parse(rawTimestamp)!!.time
            val snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"

            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "archive-only-store-test",
                    storeId = "pccomponentes",
                    price = 89.99,
                    currency = "EUR",
                    observedAt = archivedAt,
                    sourceUrl = snapshotUrl,
                    sourceName = "Internet Archive",
                    isVerified = true,
                    productTitle = "Anker Prime 27K Power Bank",
                    storeName = "PcComponentes",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "archive",
                    sourcePageUrl = originalUrl
                )
            )
            // Legacy rows can be mislabeled "live" despite pointing at Wayback.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "archive-only-store-legacy-test",
                    storeId = "pccomponentes",
                    price = 79.99,
                    currency = "EUR",
                    observedAt = archivedAt + 1L,
                    sourceUrl = snapshotUrl,
                    sourceName = "Internet Archive",
                    isVerified = true,
                    productTitle = "Anker Prime 27K Power Bank",
                    storeName = "PcComponentes",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "live",
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

            val archiveOnlyStores = repository.storesWithSavedPricesStream.first()
            assertTrue(
                "Archived history must not create a current saved-store entry",
                archiveOnlyStores.none { it.savedPriceCount > 0 }
            )

            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "live-store-test",
                    storeId = "pccomponentes",
                    price = 99.99,
                    currency = "EUR",
                    observedAt = now,
                    sourceUrl = originalUrl,
                    sourceName = "Google Shopping",
                    isVerified = true,
                    productTitle = "Anker Prime 27K Power Bank",
                    storeName = "PcComponentes",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = now,
                    evidenceType = "live"
                )
            )

            val storesWithLivePrice = repository.storesWithSavedPricesStream.first { stores ->
                stores.any { it.savedPriceCount > 0 }
            }
            assertEquals(
                "Only the live quote should count toward the current saved-store directory",
                1,
                storesWithLivePrice.sumOf { it.savedPriceCount }
            )
        } finally {
            database.close()
        }
    }


    @Test
    fun archivePersistenceAcceptsOlderCapturesWithinEightYearLookupWindow() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val productId = "anker-eight-year-history"
        val title = "Anker Prime 27K Power Bank"
        val originalUrl = "https://www.pccomponentes.com/anker-prime-27k"
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            database.productDao().insert(
                ProductEntity(
                    id = productId,
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

            fun quoteFromDaysAgo(days: Long): ArchivedPriceQuote {
                val approximateTimestamp = System.currentTimeMillis() -
                    days * 24L * 60L * 60L * 1000L
                val rawTimestamp = formatter.format(Date(approximateTimestamp))
                val exactTimestamp = formatter.parse(rawTimestamp)!!.time
                return ArchivedPriceQuote(
                    observedAt = exactTimestamp,
                    price = 89.99,
                    currency = "EUR",
                    productTitle = title,
                    originalUrl = originalUrl,
                    snapshotUrl = "https://web.archive.org/web/${rawTimestamp}id_/${originalUrl}"
                )
            }

            val sixYearsNineMonthsOld = quoteFromDaysAgo(2_500L)
            assertEquals(
                "A verified archive quote inside the configured 2,920-day lookup window must persist",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(sixYearsNineMonthsOld),
                    targetProductId = productId
                )
            )

            val outsideEightYears = quoteFromDaysAgo(2_950L)
            assertEquals(
                "A capture older than the configured eight-year window must still be rejected",
                0,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "PcComponentes",
                    quotes = listOf(outsideEightYears),
                    targetProductId = productId
                )
            )

            val restored = withTimeout(10_000L) {
                repository.allProductsStream.first { rows ->
                    rows.any { it.id == productId && it.priceHistory.any { point -> point.isArchiveEvidence } }
                }.first { it.id == productId }
            }
            assertEquals("Only the in-window historical capture should be visible", 1,
                restored.priceHistory.count { it.isArchiveEvidence })
            assertEquals(sixYearsNineMonthsOld.observedAt,
                restored.priceHistory.single { it.isArchiveEvidence }.timestamp)
        } finally {
            database.close()
        }
    }

}
