package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.remote.ArchivedPriceQuote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductDiscoveryRepositoryTest {
    private val observationTime = 1_735_732_800_000L // 2025-01-01 12:00 UTC

    private fun quote(
        title: String = "Sony WH-1000XM5 Auriculares Inalámbricos Negro",
        productUrl: String = "https://shop.example/products/sony-wh-1000xm5-negro"
    ) = ArchivedPriceQuote(
        observedAt = observationTime,
        price = 249.99,
        currency = "EUR",
        productTitle = title,
        originalUrl = "https://shop.example/products/phones-family",
        snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://shop.example/products/phones-family",
        productUrl = productUrl,
        isDiscoveryCandidate = true
    )

    private fun newDatabase(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun newProductStaysInQuarantineUntilUserAcceptsIt() = runBlocking {
        val database = newDatabase()
        try {
            val discoveries = ProductDiscoveryRepository(database)
            assertTrue(discoveries.stageArchivedQuote(quote(), "Tienda Ejemplo"))

            val pending = database.productDiscoveryDao().observePending().first()
            assertEquals(1, pending.size)
            assertEquals(
                "Incidental products must not leak into the visible product table",
                0,
                database.productDao().getProductCount()
            )
            assertEquals(
                "Historical evidence stays in its shadow table before acceptance",
                1,
                database.productDiscoveryDao().getObservations(pending.single().id).size
            )

            val imported = discoveries.accept(pending.single().id)
            assertNotNull(imported)
            assertTrue(imported!!.createdProduct)
            assertEquals(1, imported.savedObservations)
            assertEquals(1, database.productDao().getProductCount())
            val product = database.productDao().getAllProductsSnapshot().single()
            assertEquals("Sony WH-1000XM5 Auriculares Inalámbricos Negro", product.title)
            assertFalse("Archive evidence must not be marked as a verified current/live price", product.isPriceVerified)
            assertEquals(1, database.priceObservationDao().observeAllStored().first().size)
            assertEquals("archive", database.priceObservationDao().observeAllStored().first().single().evidenceType)
            assertTrue(database.productDiscoveryDao().observePending().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun accessoryAndConflictingVariantDoNotEnterDiscoveryQuarantine() = runBlocking {
        val database = newDatabase()
        try {
            val discoveries = ProductDiscoveryRepository(database)
            assertFalse(
                discoveries.stageArchivedQuote(
                    quote(
                        title = "Sony WH-1000XM5 Funda protectora y carcasa",
                        productUrl = "https://shop.example/products/sony-wh-1000xm5-funda"
                    ),
                    "Tienda Ejemplo"
                )
            )
            assertFalse(
                discoveries.stageArchivedQuote(
                    quote(
                        title = "Samsung Galaxy S25 Ultra 256 GB Titanium Gray",
                        productUrl = "https://shop.example/products/samsung-galaxy-s25-ultra-128gb-titanium-gray"
                    ),
                    "Tienda Ejemplo"
                )
            )
            assertTrue(database.productDiscoveryDao().observePending().first().isEmpty())
            assertEquals(0, database.productDao().getProductCount())
            assertTrue(database.priceObservationDao().observeAllStored().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun repeatedSnapshotDoesNotCreateDuplicateDiscoveryObservation() = runBlocking {
        val database = newDatabase()
        try {
            val discoveries = ProductDiscoveryRepository(database)
            assertTrue(discoveries.stageArchivedQuote(quote(), "Tienda Ejemplo"))
            assertFalse(discoveries.stageArchivedQuote(quote(), "Tienda Ejemplo"))
            val pending = database.productDiscoveryDao().observePending().first().single()
            assertEquals(1, pending.captureCount)
            assertEquals(1, database.productDiscoveryDao().getObservations(pending.id).size)
        } finally {
            database.close()
        }
    }
}
