package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductDaoMarketplaceCategoryCountsTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun legacyAndUnclassifiedRowsAreCountedUnderOtherAndRemainBrowsable() = runBlocking {
        database.productDao().insertAll(
            listOf(
                testProduct("phone", "SMARTPHONES"),
                testProduct("legacy", "legacy_mobile_label"),
                testProduct("old-all", "ALL"),
                testProduct("other", "OTHER"),
                testProduct("hidden-phone", "SMARTPHONES").copy(isPriceVerified = false),
                testProduct("hidden-legacy", "old_legacy_mobile_label").copy(isPriceVerified = false)
            )
        )

        database.priceObservationDao().insert(
            PriceObservationEntity(
                productId = "old-all",
                storeId = "second-store",
                price = 9.5,
                currency = "EUR",
                observedAt = System.currentTimeMillis(),
                sourceUrl = "https://second.example/product",
                sourceName = "Test source",
                isVerified = true,
                productTitle = "Producto de prueba old-all",
                storeName = "Second store"
            )
        )

        val counts = database.productDao().observeMarketplaceCategoryCounts()
            .first()
            .associateBy { it.categoryName }

        assertEquals(1, counts["SMARTPHONES"]?.productCount)
        assertEquals(
            "Unknown, legacy, and explicit OTHER rows must not disappear from the home total",
            3,
            counts["OTHER"]?.productCount
        )
        assertEquals(
            "OTHER must include distinct merchants from verified saved price observations",
            2,
            counts["OTHER"]?.storeCount
        )
        assertEquals("All visible rows must be accounted for exactly once", 4, counts.values.sumOf { it.productCount })

        val smartphoneProducts = database.productDao().getProductsByCategory("SMARTPHONES")
            .first()
            .map { it.id }
            .toSet()
        assertEquals("Category rows must use the same visibility rule as the home counts", setOf("phone"), smartphoneProducts)

        val otherProducts = database.productDao().getProductsByCategory("OTHER")
            .first()
            .map { it.id }
            .toSet()
        assertEquals(setOf("legacy", "old-all", "other"), otherProducts)

        val fullCatalogueIds = database.productDao().getAllProducts()
            .first()
            .map { it.id }
            .toSet()
        assertEquals(
            "The explicit ALL catalogue stream must retain all stored products",
            setOf("phone", "legacy", "old-all", "other", "hidden-phone", "hidden-legacy"),
            fullCatalogueIds
        )
    }

    private fun testProduct(id: String, category: String) = ProductEntity(
        id = id,
        title = "Producto de prueba $id",
        brand = "Marca de prueba",
        category = category,
        currentPrice = 10.0,
        athMinPrice = 10.0,
        athMinDate = "",
        athMinStore = "",
        athMaxPrice = 10.0,
        avg90dPrice = 10.0,
        officialPvp = 0.0,
        currentStoreId = "test-store",
        storeUrl = "https://example.com/$id",
        ean = "",
        isPriceVerified = true
    )
}
