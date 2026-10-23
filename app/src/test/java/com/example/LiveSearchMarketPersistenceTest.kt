package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ProductEntity
import com.example.data.model.ProductCategory
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
}
