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
                testProduct("other", "OTHER")
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
        assertEquals("All visible rows must be accounted for exactly once", 4, counts.values.sumOf { it.productCount })

        val otherProducts = database.productDao().getProductsByCategory("OTHER")
            .first()
            .map { it.id }
            .toSet()
        assertEquals(setOf("legacy", "old-all", "other"), otherProducts)
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
