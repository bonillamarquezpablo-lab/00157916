package com.example

import com.example.data.model.DealVerdict
import com.example.data.model.PricePoint
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.model.VerdictLevel
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun groupedOnlineSearchCombinesRetailersButKeepsModelsVariantsAndAccessoriesSeparate() {
        fun result(title: String, price: Double, store: String) = SerpApiShoppingResult(
            title = title,
            price = price,
            storeName = store,
            sourceUrl = "https://${store.lowercase().replace(" ", "")}.example/product",
            observedAt = 1_800_000_000_000L
        )

        val groups = groupOnlineShoppingResults(
            listOf(
                result("Smartphone Xiaomi Redmi 17 4GB/256GB Negro", 189.99, "Amazon.es"),
                result("Xiaomi Redmi 17 4G 4+256GB Azul", 179.99, "PcComponentes"),
                result("Xiaomi Redmi A5 4GB/128GB", 99.99, "Tienda A"),
                result("Funda Xiaomi Redmi 17 4GB/256GB con tapa", 7.99, "Tienda B"),
                result("Samsung Galaxy S26 FE 256GB", 699.99, "Tienda C"),
                result("Samsung Galaxy S26 Ultra 256GB", 1199.99, "Tienda D"),
                result("Nike Air Force 1 Gris - 47", 99.99, "Tienda Nike"),
                result("Zapatillas Nike Air Force 1 Gris - 40.5 Hombre", 94.99, "Tienda Sports")
            )
        )

        assertEquals(
            "Models, variants and accessories stay separate while identical offers group together",
            6,
            groups.size
        )
        val redmi17 = groups.single { it.identityKey == "xiaomi|redmi17|memory:4gb+256gb" }
        assertEquals(2, redmi17.offers.size)
        assertEquals(179.99, redmi17.minPrice, 0.001)
        assertTrue(groups.any { it.identityKey.contains("redmia5") })
        assertTrue(groups.any { it.identityKey.contains("accessory:funda") })
        assertTrue(groups.any { it.identityKey.contains("variant:fe") })
        assertTrue(groups.any { it.identityKey.contains("variant:ultra") })
        val airForceOne = groups.single { it.title.contains("Air Force 1", ignoreCase = true) }
        assertEquals("Different shoe sizes and gender words must merge", 2, airForceOne.offers.size)
        assertEquals(94.99, airForceOne.minPrice, 0.001)
    }

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testDealVerdictAthRecord() {
        val verdict = DealVerdict.analyze(
            currentPrice = 279.0,
            athMinPrice = 279.0,
            avg90dPrice = 345.0,
            athMaxPrice = 419.0
        )
        assertEquals(VerdictLevel.ATH_RECORD, verdict.level)
        assertTrue(verdict.badgeText.contains("MÍNIMO OBSERVADO"))
        assertEquals("Confidence must not be fabricated when no confidence model ran", 0, verdict.confidenceScore)
    }

    @Test
    fun livePriceNeedsNinetyDayHistoryBeforeHistoricalClaims() {
        val store = Store.ALL_STORES.first()
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        fun point(daysAgo: Long, price: Double) = PricePoint(
            timestamp = now - daysAgo * day,
            dateLabel = "$daysAgo días",
            price = price,
            storeName = "Comercio consultado",
            eventLabel = "Consulta real"
        )

        val oneSnapshot = Product(
            id = "unit_test_online_product",
            title = "Producto consultado para prueba",
            brand = "Marca de prueba",
            category = ProductCategory.SMARTPHONES,
            currentPrice = 199.0,
            athMinPrice = 199.0,
            athMinDate = "Hoy",
            athMinStore = store.name,
            athMaxPrice = 199.0,
            avg90dPrice = 0.0,
            officialPvp = 0.0,
            currentStore = store,
            storeUrl = store.officialUrl,
            ean = "",
            priceHistory = listOf(point(0, 199.0)),
            offers = emptyList(),
            isPriceVerified = true,
            priceObservedAt = now
        )
        assertFalse("One snapshot is not sufficient historical evidence", oneSnapshot.hasSufficientHistory)
        assertFalse("One snapshot must not be labelled a historic low", oneSnapshot.isAtAth)
        assertEquals(VerdictLevel.UNVERIFIED, oneSnapshot.verdict.level)

        val enoughHistory = oneSnapshot.copy(
            currentPrice = 189.0,
            priceObservedAt = now,
            priceHistory = listOf(
                point(92, 229.0),
                point(60, 209.0),
                point(30, 199.0),
                point(0, 189.0)
            )
        )
        assertTrue("History requires coverage across 90 days plus recent samples", enoughHistory.hasSufficientHistory)
    }

    @Test
    fun testDealVerdictInflatedFakeDeal() {
        val verdict = DealVerdict.analyze(
            currentPrice = 429.0,
            athMinPrice = 329.0,
            avg90dPrice = 369.0,
            athMaxPrice = 649.0,
            wasRecentlyInflated = true,
            fakePriorPrice = 649.0
        )
        assertEquals(VerdictLevel.INFLATED_FAKE_DEAL, verdict.level)
        assertTrue(verdict.badgeText.contains("OJO"))
    }

    @Test
    fun discountVerdictDoesNotClaimRetailerPriceOrStockIsConfirmed() {
        val verdict = DealVerdict.analyze(
            currentPrice = 90.0,
            athMinPrice = 80.0,
            avg90dPrice = 100.0,
            athMaxPrice = 120.0
        )
        assertEquals(VerdictLevel.FAIR_DISCOUNT, verdict.level)
        assertTrue(verdict.badgeText.contains("MEDIA"))
        assertTrue(verdict.explanation.contains("no confirma", ignoreCase = true))
    }

    @Test
    fun storeDirectoryContainsThirtyPlusOfficialWebsitesWithoutFakePrices() {
        assertTrue("The store directory is metadata, not a product catalogue", Store.ALL_STORES.size >= 30)
        assertEquals("Store IDs must be unique", Store.ALL_STORES.size, Store.ALL_STORES.map { it.id }.toSet().size)
        Store.ALL_STORES.forEach { store ->
            assertTrue("Missing official HTTPS URL for ${store.name}", store.officialUrl.startsWith("https://"))
            assertFalse("No live price connector configured for ${store.name}", store.hasLivePriceIntegration)
            assertTrue("Search should be restricted to retailer domain", store.searchUrlFor("test").contains("site%3A"))
        }
    }
}
