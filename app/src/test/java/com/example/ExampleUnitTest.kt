package com.example

import com.example.data.model.DealVerdict
import com.example.data.model.Store
import com.example.data.model.VerdictLevel
import com.example.data.repository.SpanishMarketData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
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
        assertTrue(verdict.badgeText.contains("MÍNIMO HISTÓRICO"))
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
    fun testSpanishMarketCatalogIntegrity() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue("Market products catalog should not be empty", products.isNotEmpty())
        for (prod in products) {
            assertTrue("Price should be positive for ${prod.title}", prod.currentPrice > 0)
            assertTrue("ATH price should be <= currentPrice for ${prod.title}", prod.athMinPrice <= prod.currentPrice + 0.1)
        }
    }

    @Test
    fun testSpanishStoresDatabaseIntegrity() {
        val storeEntities = SpanishMarketData.getInitialStoreEntities()
        assertTrue("Store entities should contain at least 15 stores", storeEntities.size >= 15)
        val names = storeEntities.map { it.name }
        assertTrue(names.any { it.contains("Amazon", ignoreCase = true) })
        assertTrue(names.any { it.contains("PcComponentes", ignoreCase = true) })
        assertTrue(names.any { it.contains("MediaMarkt", ignoreCase = true) })
        assertTrue(names.any { it.contains("El Corte Inglés", ignoreCase = true) })
        assertTrue(names.any { it.contains("Carrefour", ignoreCase = true) })
        assertTrue(names.any { it.contains("Decathlon", ignoreCase = true) })
        assertTrue(names.any { it.contains("Leroy Merlin", ignoreCase = true) })
        assertTrue(names.any { it.contains("Fnac", ignoreCase = true) })
        assertTrue(names.any { it.contains("Alcampo", ignoreCase = true) })
    }
}
