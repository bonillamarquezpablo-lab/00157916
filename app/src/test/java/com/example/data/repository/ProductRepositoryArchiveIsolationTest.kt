package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.remote.SerpApiShoppingResult
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
            val sameDayArchiveAt = todayStart - 12L * 60L * 60L * 1000L
            val sameDayArchiveStamp = java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.ROOT
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(sameDayArchiveAt))
            val sameDayArchiveUrl =
                "https://web.archive.org/web/${sameDayArchiveStamp}id_/https://shop.example/product/anker-prime"
            val observations = listOf(
                live(220.0, todayStart - 36L * 60L * 60L * 1000L, "old"),
                live(190.0, sameDayArchiveAt, "middle"),
                live(200.0, now, "current"),
                PriceObservationEntity(
                    productId = productId,
                    storeId = "sample-store-archive-same-day",
                    price = 175.0,
                    currency = "EUR",
                    observedAt = sameDayArchiveAt,
                    sourceUrl = sameDayArchiveUrl,
                    sourceName = "Internet Archive (Wayback Machine)",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Tienda de prueba",
                    sourceType = "Internet Archive",
                    linkType = "Archivo histórico",
                    collectedAt = now,
                    evidenceType = "archive",
                    sourcePageUrl = "https://shop.example/product/anker-prime"
                ),
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
            assertEquals(
                "archive rows remain in their own series, including a day that also has a live quote",
                3,
                product.priceHistory.count { it.isArchiveEvidence }
            )
            assertTrue(
                "a same-day archived observation must not be filtered out by a live observation",
                product.priceHistory.any {
                    it.isArchiveEvidence && it.sourceUrl == sameDayArchiveUrl && it.price == 175.0
                }
            )
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

            // Keep one point per product/store/day, but improve it when another validated
            // capture on that day proves a lower price for the same model/capacity.
            val sameDayLater = mismatchedCaptureTime + 60_000L
            val sameDayTimestamp = java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.ROOT
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(sameDayLater))
            val sameDayLowerQuote = validQuote.copy(
                observedAt = sameDayLater,
                price = 15.99,
                snapshotUrl = "https://web.archive.org/web/${sameDayTimestamp}id_/https://shop.example/product/anker-prime"
            )
            assertEquals(
                "a lower verified capture on the same day must improve the single daily point",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = "https://shop.example/product/anker-prime",
                    storeName = "Tienda de prueba",
                    quotes = listOf(sameDayLowerQuote)
                )
            )
            val savedSameDayRows = database.priceObservationDao().observeVerifiedHistory(productId).first()
                .filter {
                    it.sourcePageUrl == "https://shop.example/product/anker-prime" &&
                        kotlin.math.abs(it.observedAt - mismatchedCaptureTime) < 12L * 60L * 60L * 1000L
                }
            assertEquals("same-day imports must not create or replace the existing point", 1, savedSameDayRows.size)
            assertEquals(15.99, savedSameDayRows.single().price, 0.0)
            assertEquals(
                "The cheaper archived capture becomes the retained daily evidence",
                sameDayLowerQuote.snapshotUrl,
                savedSameDayRows.single().sourceUrl
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
    fun startupReclassifiesXiaomiMiPhoneWithoutTouchingHistoricalPrices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val productId = "saved-xiaomi-mi-10-pro"
            val title = "Xiaomi Mi 10 Pro Gris"
            val observedAt = java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.ROOT
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse("20250101120000")!!.time
            val now = System.currentTimeMillis()
            val snapshotUrl =
                "https://web.archive.org/web/20250101120000id_/https://www.phonehouse.es/movil/xiaomi/mi-10-pro.html"

            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Xiaomi",
                    category = ProductCategory.ALL.name,
                    currentPrice = 499.99,
                    athMinPrice = 499.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "",
                    athMaxPrice = 499.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "phonehouse",
                    storeUrl = "https://www.phonehouse.es/movil/xiaomi/mi-10-pro.html",
                    ean = "",
                    isPriceVerified = false
                )
            )
            database.priceObservationDao().insertAll(
                listOf(
                    PriceObservationEntity(
                        productId = productId,
                        storeId = "phonehouse",
                        price = 499.99,
                        currency = "EUR",
                        observedAt = observedAt,
                        sourceUrl = snapshotUrl,
                        sourceName = "Internet Archive (Wayback Machine)",
                        isVerified = true,
                        productTitle = title,
                        storeName = "Phone House",
                        sourceType = "Internet Archive",
                        linkType = "Archivo histórico",
                        collectedAt = now,
                        evidenceType = "archive",
                        sourcePageUrl = "https://www.phonehouse.es/movil/xiaomi/mi-10-pro.html"
                    )
                )
            )

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            // Wait until Room emits the repaired category, not just the first pre-repair value.
            val mobiles = repository.productsForCategoryStream(ProductCategory.SMARTPHONES).first { products ->
                products.any { it.id == productId }
            }
            val persisted = database.productDao().getProductById(productId)
            assertEquals(
                "An old fallback category should be repaired to the shared mobiles category",
                ProductCategory.SMARTPHONES.name,
                persisted?.category
            )
            assertEquals(
                "Category repair must not delete or rewrite the saved historical observation",
                1,
                database.priceObservationDao().getObservationCount(productId)
            )

            val xiaomi = mobiles.single { it.id == productId }
            assertEquals(title, xiaomi.title)
            assertEquals(499.99, xiaomi.priceHistory.single { it.evidenceType == "archive" }.price, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun startupMovesMisclassifiedScooterOutOfSmartphonesAndPhoneSearchFiltersIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val productId = "online_candidate_misclassified_scooter"
            val title = "Xiaomi 2 Pro Scooter eléctrica 1 Asiento 25 kmh 600 W Negro"
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Xiaomi",
                    category = ProductCategory.SMARTPHONES.name,
                    currentPrice = 472.04,
                    athMinPrice = 472.04,
                    athMinDate = "Pendiente de verificación",
                    athMinStore = "",
                    athMaxPrice = 472.04,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "voltnation",
                    storeUrl = "https://voltnation.gr/xiaomi-2-pro-scooter",
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
            val others = repository.productsForCategoryStream(ProductCategory.OTHER).first { products ->
                products.any { it.id == productId }
            }
            assertTrue(others.any { it.id == productId })
            assertEquals(ProductCategory.OTHER.name, database.productDao().getProductById(productId)?.category)
            assertTrue(repository.productsForCategoryStream(ProductCategory.SMARTPHONES).first()
                .none { it.id == productId })

            val result = SerpApiShoppingResult(
                title = title,
                price = 472.04,
                storeName = "voltnation.gr",
                sourceUrl = "https://voltnation.gr/xiaomi-2-pro-scooter",
                observedAt = System.currentTimeMillis()
            )
            assertTrue(repository.filterVisibleLiveSearchResults(listOf(result), "móviles").isEmpty())

            // A later provider update must also repair a legacy category mismatch, not just startup cleanup.
            val staleMobileCategory = database.productDao().getProductById(productId)!!
                .copy(category = ProductCategory.SMARTPHONES.name)
            database.productDao().insert(staleMobileCategory)
            repository.recordOnlineSearchResults(
                results = listOf(result),
                sourceType = "GOOGLE_SHOPPING",
                searchQuery = title
            )
            assertEquals(
                "A scooter result must not retain a stale Móviles category after an update",
                ProductCategory.OTHER.name,
                database.productDao().getProductById(productId)?.category
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun implausiblyCheapHuaweiNovaDoesNotRemainAsAnObservedOrPendingPrice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val productId = "huawei-nova-14-pro-implausible-price"
            val title = "Huawei Nova 14 Pro"
            val now = System.currentTimeMillis()
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Huawei",
                    category = ProductCategory.SMARTPHONES.name,
                    currentPrice = 5.62,
                    athMinPrice = 5.62,
                    athMinDate = "Historial en construcción",
                    athMinStore = "",
                    athMaxPrice = 5.62,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "phonehouse",
                    storeUrl = "https://www.phonehouse.es/movil/huawei-nova-14-pro.html",
                    ean = "",
                    isPriceVerified = true
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "phonehouse",
                    price = 5.62,
                    currency = "EUR",
                    observedAt = now,
                    sourceUrl = "https://www.phonehouse.es/movil/huawei-nova-14-pro.html",
                    sourceName = "SerpApi · Google Shopping",
                    isVerified = true,
                    productTitle = title,
                    storeName = "Phone House",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = now,
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
            val huawei = repository.productsForCategoryStream(ProductCategory.SMARTPHONES)
                .first { products -> products.any { it.id == productId } }
                .single { it.id == productId }

            assertEquals(0.0, huawei.currentPrice, 0.001)
            assertTrue(huawei.priceHistory.isEmpty())
            assertTrue(huawei.savedHistoricalPrice == null)
            assertTrue(!huawei.isPriceVerified)
            assertEquals(1, database.priceObservationDao().getObservationCount(productId))
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
