package com.example.ui.screens

import com.example.data.model.ProductCategory
import com.example.data.repository.SpanishMarketData
import com.example.ui.viewmodel.SortOption
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

    @Test
    fun priceSortIsPreservedAcrossFamiliesAndInsideSelectedCapacity() {
        val template = SpanishMarketData.getInitialProducts().first()
        fun product(id: String, title: String, price: Double) = template.copy(
            id = id,
            title = title,
            brand = "Apple",
            category = ProductCategory.TV_AUDIO,
            currentPrice = price,
            isPriceVerified = true,
            priceObservedAt = System.currentTimeMillis(),
            priceHistory = emptyList()
        )

        val rows = listOf(
            product("iphone15-yellow", "Apple iPhone 15 256 GB Amarillo", 700.0),
            product("iphone15-black", "Apple iPhone 15 256 GB Negro", 1_200.0),
            product("iphone16-blue", "Apple iPhone 16 256 GB Azul", 900.0)
        )

        val highToLow = buildSavedProductFamilyBuckets(rows, SortOption.PRICE_HIGH_LOW)
        assertEquals(
            "Families must follow price order instead of reverting to alphabetical order after grouping",
            listOf("Apple iPhone 15", "Apple iPhone 16"),
            highToLow.map { it.label }
        )
        assertEquals(
            "Products inside one selected capacity must also respect price order",
            listOf("iphone15-black", "iphone15-yellow"),
            highToLow.first().variants.single().storages.single().products.map { it.id }
        )

        val lowToHigh = buildSavedProductFamilyBuckets(rows, SortOption.PRICE_LOW_HIGH)
        assertEquals(
            listOf("Apple iPhone 15", "Apple iPhone 16"),
            lowToHigh.map { it.label }
        )
        assertEquals(
            listOf("iphone15-yellow", "iphone15-black"),
            lowToHigh.first().variants.single().storages.single().products.map { it.id }
        )
    }

    @Test
    fun priceSortOrdersVersionsAndCapacitiesInsideOneFamily() {
        val template = SpanishMarketData.getInitialProducts().first()
        fun product(id: String, title: String, price: Double) = template.copy(
            id = id,
            title = title,
            brand = "Apple",
            category = ProductCategory.TV_AUDIO,
            currentPrice = price,
            isPriceVerified = true,
            priceObservedAt = System.currentTimeMillis(),
            priceHistory = emptyList()
        )
        val rows = listOf(
            product("iphone15-128", "Apple iPhone 15 128 GB Amarillo", 680.0),
            product("iphone15-256", "Apple iPhone 15 256 GB Amarillo", 1_000.0),
            product("iphone15-pro-256", "Apple iPhone 15 Pro 256 GB Negro", 1_500.0)
        )

        val highToLow = buildSavedProductFamilyBuckets(rows, SortOption.PRICE_HIGH_LOW).single()
        assertEquals(
            "The most expensive edition must appear first when sorting high to low",
            listOf("Pro", "Estándar"),
            highToLow.variants.map { it.label }
        )
        val standardHighToLow = highToLow.variants.single { it.label == "Estándar" }
        assertEquals(
            "Storage options must follow price order, not always capacity order",
            listOf("256 GB", "128 GB"),
            standardHighToLow.storages.map { it.label }
        )

        val lowToHigh = buildSavedProductFamilyBuckets(rows, SortOption.PRICE_LOW_HIGH).single()
        assertEquals(
            listOf("Estándar", "Pro"),
            lowToHigh.variants.map { it.label }
        )
        val standardLowToHigh = lowToHigh.variants.single { it.label == "Estándar" }
        assertEquals(
            listOf("128 GB", "256 GB"),
            standardLowToHigh.storages.map { it.label }
        )
    }

    @Test
    fun redmiNoteFamiliesNestEditionNetworkStorageAndCollapseDuplicateSellerRows() {
        val template = SpanishMarketData.getInitialProducts().first()
        fun product(id: String, title: String) = template.copy(
            id = id,
            title = title,
            brand = "Xiaomi",
            category = ProductCategory.SMARTPHONES,
            priceHistory = emptyList()
        )

        val rows = listOf(
            product("redmi15-standard-128", "Xiaomi Redmi Note 15 6GB/128GB"),
            product("redmi15-pro5g-256-a", "Xiaomi Redmi Note 15 Pro 5G 8GB/256GB Titanio"),
            // A second row for the same exact variant must be displayed as one product card.
            product("redmi15-pro5g-256-b", "Xiaomi Redmi Note 15 Pro 5G 8GB/256GB Titanio"),
            product("redmi15-pro5g-6gb-256", "Xiaomi Redmi Note 15 Pro 5G 6GB/256GB Titanio"),
            product("redmi15-pro5g-black-256", "Xiaomi Redmi Note 15 Pro 5G 8GB/256GB Negro"),
            product("redmi15-pro4g-512", "Xiaomi Redmi Note 15 Pro 4G 12GB + 512GB Titanio"),
            product("redmi10-pro5g", "Xiaomi Redmi Note 10 Pro 5G 8GB/256GB")
        )

        val families = buildSavedProductFamilyBuckets(rows)
        assertEquals(
            "Redmi Note 15 variants should share one family, while Redmi Note 10 remains separate",
            2,
            families.size
        )
        val note15 = families.single { it.label == "Xiaomi Redmi Note 15" }
        assertEquals(
            "Base, Pro 4G and Pro 5G are distinct versions nested under the same family",
            setOf("Estándar", "Pro 4G", "Pro 5G"),
            note15.variants.map { it.label }.toSet()
        )
        assertEquals(
            "The duplicate exact Pro 5G listing must collapse without merging its 4G sibling",
            5,
            note15.productCount
        )
        assertEquals(
            setOf("6 GB RAM · 128 GB"),
            note15.variants.single { it.label == "Estándar" }.storages.map { it.label }.toSet()
        )
        assertEquals(
            setOf("6 GB RAM · 256 GB", "8 GB RAM · 256 GB"),
            note15.variants.single { it.label == "Pro 5G" }.storages.map { it.label }.toSet()
        )
        assertEquals(
            setOf("12 GB RAM · 512 GB"),
            note15.variants.single { it.label == "Pro 4G" }.storages.map { it.label }.toSet()
        )
        assertEquals(
            "Different RAM sizes remain separate",
            1,
            note15.variants.single { it.label == "Pro 5G" }.storages
                .single { it.label == "6 GB RAM · 256 GB" }.products.size
        )
        assertEquals(
            "Different colours remain separate under the same RAM/storage",
            2,
            note15.variants.single { it.label == "Pro 5G" }.storages
                .single { it.label == "8 GB RAM · 256 GB" }.products.size
        )
        assertEquals("Xiaomi Redmi Note 10", families.single { it.label == "Xiaomi Redmi Note 10" }.label)
    }

}
