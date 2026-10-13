package com.example

import com.example.data.model.DealVerdict
import com.example.data.model.PricePoint
import com.example.data.model.Product
import com.example.data.model.Store
import com.example.data.model.VerdictLevel
import com.example.data.repository.SpanishMarketData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun bundledProductsAreNeverPresentedAsVerifiedPrices() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue(products.isNotEmpty())
        products.forEach { product ->
            assertFalse("Bundled sample must not be marked verified: ${product.title}", product.isPriceVerified)
            assertFalse("Bundled sample must not claim an all-time low: ${product.title}", product.isAtAth)
            assertEquals(VerdictLevel.UNVERIFIED, product.verdict.level)
            assertEquals(0, product.verdict.confidenceScore)
        }
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
    }

    @Test
    fun livePriceNeedsNinetyDayHistoryBeforeHistoricalClaims() {
        val seed = SpanishMarketData.getInitialProducts().first()
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        fun point(daysAgo: Long, price: Double) = PricePoint(
            timestamp = now - daysAgo * day,
            dateLabel = "$daysAgo días",
            price = price,
            storeName = "Comercio consultado",
            eventLabel = "Consulta de prueba"
        )

        val oneSnapshot: Product = seed.copy(
            currentPrice = 199.0,
            athMinPrice = 199.0,
            officialPvp = 0.0,
            priceObservedAt = now,
            isPriceVerified = true,
            priceHistory = listOf(point(0, 199.0))
        )
        assertFalse("One snapshot must not count as historical evidence", oneSnapshot.hasSufficientHistory)
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
    fun expandedCatalogueHasAtLeastSixtyUniqueUnverifiedFixtures() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue("The discovery catalogue should contain 60+ products", products.size >= 60)
        assertEquals("Product IDs must be unique", products.size, products.map { it.id }.toSet().size)
        assertTrue("Seeded items must remain unverified", products.none { it.isPriceVerified })
    }

    @Test
    fun storeDirectoryContainsThirtyPlusOfficialWebsitesWithoutFalsePriceIntegration() {
        assertTrue("At least 30 stores should be listed", Store.ALL_STORES.size >= 30)
        assertEquals("Store IDs must be unique", Store.ALL_STORES.size, Store.ALL_STORES.map { it.id }.toSet().size)
        Store.ALL_STORES.forEach { store ->
            assertTrue("Missing official HTTPS URL for ${store.name}", store.officialUrl.startsWith("https://"))
            assertFalse("No live price connector configured for ${store.name}", store.hasLivePriceIntegration)
            assertTrue("Search should be restricted to retailer domain", store.searchUrlFor("test").contains("site%3A"))
        }
    }

    @Test
    fun testSpanishMarketCatalogIntegrity() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue("Market products catalog should not be empty", products.isNotEmpty())
        for (prod in products) {
            assertTrue("Price should be positive for ${prod.title}", prod.currentPrice > 0)
            assertTrue("ATH price should be <= currentPrice for ${prod.title}", prod.athMinPrice <= prod.currentPrice + 0.1)
        }
    }

    @Test
    fun testProductsHaveMoreThan4YearsOfPriceHistory() {
        val products = SpanishMarketData.getInitialProducts()
        val fourYearsMillis = 4L * 365L * 86400000L // 1460 days
        val now = System.currentTimeMillis()

        for (prod in products) {
            val oldestPoint = prod.priceHistory.minByOrNull { it.timestamp }
            assertTrue("Product ${prod.title} must have price history", oldestPoint != null)
            val spanMillis = now - oldestPoint!!.timestamp
            assertTrue(
                "Product ${prod.title} price history should cover more than 4 years (found ${spanMillis / 86400000L} days)",
                spanMillis >= fourYearsMillis
            )
        }
    }

    @Test
    fun testSpanishStoresDatabaseIntegrity() {
        val storeEntities = SpanishMarketData.getInitialStoreEntities()
        assertTrue("Store entities should contain at least 30 stores", storeEntities.size >= 30)
        val names = storeEntities.map { it.name }
        assertTrue(names.any { it.contains("Amazon", ignoreCase = true) })
        assertTrue(names.any { it.contains("PcComponentes", ignoreCase = true) })
        assertTrue(names.any { it.contains("MediaMarkt", ignoreCase = true) })
        assertTrue(names.any { it.contains("El Corte Inglés", ignoreCase = true) })
        assertTrue(names.any { it.contains("Carrefour", ignoreCase = true) })
        assertTrue(names.any { it.contains("Decathlon", ignoreCase = true) })
        assertTrue(names.any { it.contains("Leroy Merlin", ignoreCase = true) })
        assertTrue(names.any { it.contains("Apple Store", ignoreCase = true) })
        assertTrue(names.any { it.contains("Samsung Shop", ignoreCase = true) })
        assertTrue(names.any { it.contains("Xiaomi", ignoreCase = true) })
        assertTrue(names.any { it.contains("Fnac", ignoreCase = true) })
        assertTrue(names.any { it.contains("Alcampo", ignoreCase = true) })
    }
}
