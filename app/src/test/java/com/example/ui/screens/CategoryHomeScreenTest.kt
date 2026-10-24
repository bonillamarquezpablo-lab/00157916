package com.example.ui.screens

import androidx.compose.ui.graphics.Color
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.model.StoreOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class CategoryHomeScreenTest {

    @Test
    fun categorySummariesExcludeAllAndCountDistinctStoresPerCategory() {
        val pcComponents = testStore("pc", "PcComponentes")
        val amazon = testStore("amazon", "Amazon España")
        val products = listOf(
            testProduct(
                id = "phone-1",
                category = ProductCategory.SMARTPHONES,
                currentStore = pcComponents,
                offers = listOf(
                    StoreOffer(pcComponents, 799.0),
                    StoreOffer(amazon, 789.0)
                )
            ),
            testProduct(
                id = "phone-2",
                category = ProductCategory.SMARTPHONES,
                currentStore = pcComponents,
                offers = listOf(StoreOffer(pcComponents, 699.0))
            ),
            testProduct(
                id = "tv-1",
                category = ProductCategory.TV_AUDIO,
                currentStore = amazon
            )
        )

        val summaries = buildCategorySummaries(products)
        val byCategory = summaries.associateBy { it.category }
        val phones = byCategory[ProductCategory.SMARTPHONES]
        val televisions = byCategory[ProductCategory.TV_AUDIO]
        val computing = byCategory[ProductCategory.COMPUTING]

        assertEquals(ProductCategory.values().size - 1, summaries.size)
        assertFalse(summaries.any { it.category == ProductCategory.ALL })
        assertNotNull(phones)
        assertEquals(2, phones!!.productCount)
        assertEquals("Repeated store IDs must count once", 2, phones.storeCount)
        assertNotNull(televisions)
        assertEquals(1, televisions!!.productCount)
        assertEquals(1, televisions.storeCount)
        assertNotNull(computing)
        assertEquals(0, computing!!.productCount)
        assertEquals(0, computing.storeCount)
    }

    private fun testStore(id: String, name: String) = Store(
        id = id,
        name = name,
        domain = "$id.example",
        brandColor = Color(0xFF336699)
    )

    private fun testProduct(
        id: String,
        category: ProductCategory,
        currentStore: Store,
        offers: List<StoreOffer> = emptyList()
    ) = Product(
        id = id,
        title = "Producto de prueba $id",
        brand = "Marca de prueba",
        category = category,
        currentPrice = 100.0,
        athMinPrice = 90.0,
        athMinDate = "",
        athMinStore = currentStore.name,
        athMaxPrice = 120.0,
        avg90dPrice = 105.0,
        officialPvp = 120.0,
        currentStore = currentStore,
        storeUrl = "https://${currentStore.domain}/producto",
        ean = "",
        priceHistory = emptyList(),
        offers = offers
    )
}
