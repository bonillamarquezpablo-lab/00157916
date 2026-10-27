package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ProductEntity
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
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
class LiveSearchMarketPersistenceTest {

    @Test
    fun googleShoppingFallbackCreatesMarketFichaAndOpensWithoutSavingAConfirmedPrice() = runBlocking<Unit> {
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
            val title = "Victoria Hyde New English Bandolera Mujer"
            val fallback = SerpApiShoppingResult(
                title = title,
                price = 49.95,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Victoria+Hyde+New+English+Bandolera+Mujer",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )

            assertTrue("The fixture must reproduce a generic Shopping-search destination", fallback.isSearchFallbackDestination)
            assertEquals(1, repository.ensureVisibleOnlineSearchProducts(listOf(fallback), title))

            val savedRow = database.productDao().getAllProductsSnapshot().single()
            assertTrue("A catalogue-only row must have a stable candidate identity", savedRow.id.startsWith("online_candidate_"))
            assertEquals(title, savedRow.title)
            assertEquals(49.95, savedRow.currentPrice, 0.001)
            assertFalse("A Google search page must never be promoted to a verified current price", savedRow.isPriceVerified)
            assertEquals(
                "Keep the generic search URL only so the product ficha can reopen the same Shopping results",
                fallback.sourceUrl,
                savedRow.storeUrl
            )
            assertEquals(0, database.priceObservationDao().getObservationCount(savedRow.id))

            val openedFicha = repository.awaitProductForOnlineIdentity(onlineProductIdentityKey(title))
            assertEquals("The same Room row must be resolvable when the result card is tapped", savedRow.id, openedFicha.id)
            assertFalse(openedFicha.isPriceVerified)
            assertTrue(openedFicha.isUnverifiedLiveListing)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING,
                openedFicha.priceDisplayStatus
            )
            assertTrue(openedFicha.priceHistory.isEmpty())

            assertEquals("Repeated rendering/tapping must not create duplicates", 0,
                repository.ensureVisibleOnlineSearchProducts(listOf(fallback), title))
            assertEquals(1, database.productDao().getProductCount())
            assertEquals(0, database.priceObservationDao().getObservationCount(savedRow.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun liveSearchRecoversAnUnverifiedLegacyCatalogueShellWithoutCreatingADuplicate() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Victoria Hyde New English Bandolera Mujer"
            val legacyShell = ProductEntity(
                id = "legacy-victoria-hyde-shell",
                title = title,
                brand = "Victoria Hyde",
                category = "ACCESSORIES",
                currentPrice = 0.0,
                athMinPrice = 0.0,
                athMinDate = "Historial en construcción",
                athMinStore = "",
                athMaxPrice = 0.0,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "unknown_store",
                storeUrl = "",
                ean = "",
                isPriceVerified = false
            )
            database.productDao().insert(legacyShell)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val fallback = SerpApiShoppingResult(
                title = title,
                price = 49.95,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Victoria+Hyde+New+English+Bandolera+Mujer",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )

            assertEquals(1, repository.ensureVisibleOnlineSearchProducts(listOf(fallback), title))
            val updated = database.productDao().getProductById(legacyShell.id)!!
            assertEquals("The existing Room identity must be reused", legacyShell.id, updated.id)
            assertEquals(49.95, updated.currentPrice, 0.001)
            assertEquals("Pendiente de verificación", updated.athMinDate)
            assertEquals(fallback.sourceUrl, updated.storeUrl)
            assertFalse(updated.isPriceVerified)
            assertEquals(1, database.productDao().getProductCount())
            assertEquals(0, database.priceObservationDao().getObservationCount(updated.id))

            val ficha = repository.awaitProductForOnlineIdentity(onlineProductIdentityKey(title))
            assertEquals(legacyShell.id, ficha.id)
            assertTrue(ficha.isUnverifiedLiveListing)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING,
                ficha.priceDisplayStatus
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun liveSearchClassifiesAToyByItsTitleInsteadOfTheGenericDysonQuery() = runBlocking<Unit> {
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
            val title = "Juguete Dyson Supersonic secador de pelo para niños"
            val listing = SerpApiShoppingResult(
                title = title,
                price = 24.99,
                storeName = "Juguetes Ejemplo",
                sourceUrl = "https://juguetes.example/producto/juguete-dyson-supersonic",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )

            assertEquals(1, repository.ensureVisibleOnlineSearchProducts(listOf(listing), "Dyson"))
            val saved = database.productDao().getAllProductsSnapshot().single()
            assertEquals(ProductCategory.TOYS.name, saved.category)
            assertFalse(saved.isPriceVerified)
            assertEquals(0, database.priceObservationDao().getObservationCount(saved.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun liveSearchClassifiesAVisibleDysonSparePartAsAccessoriesWithoutVerifyingItsPrice() = runBlocking<Unit> {
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
            val title = "Boquilla de repuesto para secador Dyson Supersonic"
            val listing = SerpApiShoppingResult(
                title = title,
                price = 19.99,
                storeName = "Repuestos Ejemplo",
                sourceUrl = "https://repuestos.example/dyson/supersonic/boquilla",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )

            assertEquals(1, repository.ensureVisibleOnlineSearchProducts(listOf(listing), "boquilla Dyson", allowAccessories = true))
            val saved = database.productDao().getAllProductsSnapshot().single()
            assertEquals(ProductCategory.REPLACEMENT_PARTS.name, saved.category)
            assertFalse(saved.isPriceVerified)
            assertEquals(0, database.priceObservationDao().getObservationCount(saved.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun catalogueFallbackDoesNotOverwriteAnAlreadySavedTrustedProduct() = runBlocking<Unit> {
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
            val title = "Sony WH-1000XM5 Wireless Headphones"
            val now = System.currentTimeMillis()
            val direct = SerpApiShoppingResult(
                title = title,
                price = 199.99,
                storeName = "PcComponentes",
                sourceUrl = "https://www.pccomponentes.com/auriculares-sony-wh-1000xm5",
                observedAt = now,
                condition = "new"
            )
            val fallback = direct.copy(
                price = 10.00,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Sony+WH-1000XM5",
                observedAt = now - 1_000L
            )

            repository.recordOnlineSearchResults(listOf(direct), "GOOGLE_SHOPPING", title)
            val before = database.productDao().getAllProductsSnapshot().single()
            assertTrue(before.isPriceVerified)
            assertEquals(199.99, before.currentPrice, 0.001)

            assertEquals(0, repository.ensureVisibleOnlineSearchProducts(listOf(direct, fallback), title))
            val after = database.productDao().getProductById(before.id)!!
            assertTrue(after.isPriceVerified)
            assertEquals(199.99, after.currentPrice, 0.001)
            assertEquals(direct.sourceUrl, after.storeUrl)
            assertEquals(1, database.productDao().getProductCount())
            assertEquals(1, database.priceObservationDao().getObservationCount(before.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun repeatedLiveSearchReflectsTheNewestValidatedPriceInMarketAndDailyHistory() = runBlocking<Unit> {
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
            val title = "Apple iPhone 15 Plus 256GB Amarillo"
            val sourceUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb-amarillo"
            val firstObservedAt = System.currentTimeMillis() - 5_000L
            val first = SerpApiShoppingResult(
                title = title,
                price = 899.99,
                storeName = "PcComponentes",
                sourceUrl = sourceUrl,
                observedAt = firstObservedAt,
                condition = "new"
            )

            assertEquals(1, repository.recordOnlineSearchResults(listOf(first), "GOOGLE_SHOPPING", title))
            val before = database.productDao().getAllProductsSnapshot().single()
            assertEquals(899.99, before.currentPrice, 0.001)
            assertTrue(before.isPriceVerified)

            val refreshed = first.copy(
                price = 749.99,
                observedAt = System.currentTimeMillis()
            )
            assertEquals(
                "A newer valid search must update the stored daily price instead of being discarded as a duplicate",
                1,
                repository.recordOnlineSearchResults(listOf(refreshed), "GOOGLE_SHOPPING", title)
            )

            val after = database.productDao().getProductById(before.id)!!
            assertEquals(749.99, after.currentPrice, 0.001)
            assertTrue(after.isPriceVerified)
            assertEquals("There must be one current-day row per product/store", 1,
                database.priceObservationDao().getObservationCount(before.id))
            val savedHistory = database.priceObservationDao().observeVerifiedHistory(before.id).first()
            assertEquals(1, savedHistory.size)
            assertEquals(749.99, savedHistory.single().price, 0.001)

            val displayed = repository.allProductsStream.first { rows ->
                rows.any { it.id == before.id && it.currentPrice == 749.99 }
            }.single { it.id == before.id }
            assertEquals(749.99, displayed.currentPrice, 0.001)
            assertEquals("The ficha must expose the refreshed price immediately", 749.99,
                displayed.offers.minOf { it.price }, 0.001)
        } finally {
            database.close()
        }
    }


    @Test
    fun awaitProductForOnlineIdentityPrefersLiveSearchCandidateOverStaleDuplicateFicha() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Apple iPhone 15 Plus 256GB"
            val stale = ProductEntity(
                id = "legacy-iphone-stale",
                title = title,
                brand = "Apple",
                category = ProductCategory.SMARTPHONES.name,
                currentPrice = 624.00,
                athMinPrice = 624.00,
                athMinDate = "Historial en construcción",
                athMinStore = "Google Shopping",
                athMaxPrice = 624.00,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "unknown_store",
                storeUrl = "https://www.google.com/search?tbm=shop&q=Apple+iPhone+15+Plus+256GB",
                ean = "",
                isPriceVerified = true
            )
            val liveCandidate = stale.copy(
                id = "online_candidate_iphone_live",
                currentPrice = 899.00,
                athMinPrice = 0.0,
                athMinDate = "Pendiente de verificación",
                athMinStore = "",
                athMaxPrice = 0.0,
                currentStoreId = "unknown_store",
                storeUrl = "https://www.google.com/search?tbm=shop&q=Apple+iPhone+15+Plus+256GB",
                isPriceVerified = false
            )
            database.productDao().insert(stale)
            database.productDao().insert(liveCandidate)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val selected = repository.awaitProductForOnlineIdentity(onlineProductIdentityKey(title))
            assertEquals("A stale duplicate must not win merely because Room returns it first", liveCandidate.id, selected.id)
            assertEquals(899.00, selected.currentPrice, 0.001)
            assertTrue(selected.isUnverifiedLiveListing)
        } finally {
            database.close()
        }
    }

    @Test
    fun latestSearchListingCanBeShownInFichaWithoutChangingThePersistedPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Victoria Hyde New English Bandolera Mujer"
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val originalListing = SerpApiShoppingResult(
                title = title,
                price = 49.95,
                storeName = "Google Shopping",
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Victoria+Hyde",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )
            repository.ensureVisibleOnlineSearchProducts(listOf(originalListing), title)
            val savedBeforeOverlay = repository.awaitProductForOnlineIdentity(onlineProductIdentityKey(title))

            val transientFicha = savedBeforeOverlay.withUnverifiedLiveSearchListing(
                listedPrice = 39.95,
                observedAt = System.currentTimeMillis(),
                sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Victoria+Hyde",
                sourceName = "Google Shopping",
                sourceStore = Store.findByName("Google Shopping")
            )

            assertEquals(39.95, transientFicha.currentPrice, 0.001)
            assertFalse(transientFicha.isPriceVerified)
            assertTrue(transientFicha.isUnverifiedLiveListing)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING,
                transientFicha.priceDisplayStatus
            )
            val savedAfterOverlay = repository.awaitProductForOnlineIdentity(onlineProductIdentityKey(title))
            assertEquals("Showing a new search price must not write it over the Room row", 49.95, savedAfterOverlay.currentPrice, 0.001)
            assertFalse(savedAfterOverlay.isPriceVerified)
        } finally {
            database.close()
        }
    }

}
