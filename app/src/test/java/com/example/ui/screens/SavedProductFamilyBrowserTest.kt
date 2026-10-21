package com.example.ui.screens

import com.example.data.model.ProductCategory
import com.example.data.repository.SpanishMarketData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedProductFamilyBrowserTest {
    @Test
    fun iphoneVariantsAndStorageCapacitiesAreGroupedWithoutGuessingMissingCapacity() {
        val template = SpanishMarketData.getInitialProducts().first()
        fun product(id: String, title: String) = template.copy(
            id = id,
            title = title,
            brand = "Apple",
            category = ProductCategory.SMARTPHONES,
            priceHistory = emptyList()
        )
        val rows = listOf(
            product("iphone15-base-yellow-256", "Apple iPhone 15 256 GB Amarillo"),
            product("iphone15-plus-unknown", "Apple iPhone 15 Plus"),
            product("iphone15-plus-yellow-256", "Apple iPhone 15 Plus 256GB Amarillo"),
            product("iphone15-plus-black-256", "Apple iPhone 15 Plus 256GB Negro"),
            // A second Room row for the exact same colour/SKU should join the same display card.
            product("iphone15-plus-yellow-256-second-row", "Apple iPhone 15 Plus 256GB Amarillo")
        )

        val families = buildSavedProductFamilyBuckets(rows)
        assertEquals("All editions belong to one iPhone 15 family", 1, families.size)
        val family = families.single()
        assertEquals("Apple iPhone 15", family.label)
        assertEquals("Duplicate exact-SKU rows collapse into one display product", 4, family.productCount)

        val base = family.variants.single { it.label == "Estándar" }
        assertEquals("256 GB", base.storages.single().label)
        assertEquals(1, base.storages.single().products.size)

        val plus = family.variants.single { it.label == "Plus" }
        assertEquals(
            setOf("256 GB", "Capacidad no indicada"),
            plus.storages.map { it.label }.toSet()
        )
        assertEquals("Same colour/capacity entries combine; different colours stay separate", 2, plus.storages.single { it.label == "256 GB" }.products.size)
        assertEquals(
            "iphone15-plus-unknown",
            plus.storages.single { it.label == "Capacidad no indicada" }.products.single().id
        )
        assertEquals(3, plus.products.size)
        assertTrue(family.storeCount >= 1)
    }
}
