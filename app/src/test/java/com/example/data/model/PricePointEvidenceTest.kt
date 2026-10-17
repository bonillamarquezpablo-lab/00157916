package com.example.data.model

import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.PricePoint
import com.example.data.model.Store
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricePointEvidenceTest {

    @Test
    fun externalSourcesAreExplicitlySeparatedFromLiveObservations() {
        val now = System.currentTimeMillis()
        val live = point(now, 499.99, evidenceType = "live")
        val wayback = point(
            now - DAY_MS,
            199.99,
            eventLabel = "Internet Archive",
            sourceUrl = "https://web.archive.org/web/20260101000000id_/https://shop.example.test/ps5",
            evidenceType = "archive"
        )
        val googleInsight = point(
            now - DAY_MS / 2,
            399.99,
            eventLabel = "price_insights",
            evidenceType = "google_insights"
        )

        assertFalse(live.isExternalHistoryEvidence)
        assertTrue(wayback.isArchiveEvidence)
        assertTrue(wayback.isExternalHistoryEvidence)
        assertTrue(googleInsight.isGoogleInsightEvidence)
        assertTrue(googleInsight.isExternalHistoryEvidence)
    }

    @Test
    fun externalPointsCannotManufactureSufficientLiveHistory() {
        val now = System.currentTimeMillis()
        val history = listOf(
            point(now, 499.99, evidenceType = "live"),
            point(
                now - 120L * DAY_MS,
                99.99,
                eventLabel = "Internet Archive",
                sourceUrl = "https://web.archive.org/web/20260101000000id_/https://shop.example.test/ps5",
                evidenceType = "archive"
            ),
            point(
                now - 45L * DAY_MS,
                149.99,
                eventLabel = "price_insights",
                evidenceType = "google_insights"
            )
        )

        assertFalse(product(history, now).hasSufficientHistory)
    }

    @Test
    fun realLiveTimelineRemainsEligibleWithAdditionalExternalHistory() {
        val now = System.currentTimeMillis()
        val history = listOf(
            point(now - 100L * DAY_MS, 549.99, evidenceType = "live"),
            point(now - 80L * DAY_MS, 529.99, evidenceType = "live"),
            point(now - 40L * DAY_MS, 509.99, evidenceType = "live"),
            point(now, 499.99, evidenceType = "live"),
            point(
                now - 150L * DAY_MS,
                299.99,
                eventLabel = "Internet Archive",
                sourceUrl = "https://web.archive.org/web/20260101000000id_/https://shop.example.test/ps5",
                evidenceType = "archive"
            )
        )

        assertTrue(product(history, now).hasSufficientHistory)
    }

    private fun point(
        timestamp: Long,
        price: Double,
        eventLabel: String? = null,
        sourceUrl: String? = null,
        evidenceType: String = "live"
    ) = PricePoint(
        timestamp = timestamp,
        dateLabel = "test",
        price = price,
        storeName = "Tienda de prueba",
        eventLabel = eventLabel,
        sourceUrl = sourceUrl,
        evidenceType = evidenceType
    )

    private fun product(history: List<PricePoint>, now: Long) = Product(
        id = "ps5-test",
        title = "Sony PlayStation 5 Slim",
        brand = "Sony",
        category = ProductCategory.ALL,
        currentPrice = 499.99,
        athMinPrice = 499.99,
        athMinDate = "test",
        athMinStore = "Tienda de prueba",
        athMaxPrice = 549.99,
        avg90dPrice = 0.0,
        officialPvp = 0.0,
        currentStore = Store.GAME,
        storeUrl = "https://www.game.es/",
        ean = "",
        priceHistory = history,
        offers = emptyList(),
        isPriceVerified = true,
        priceObservedAt = now
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
