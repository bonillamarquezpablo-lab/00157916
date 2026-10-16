package com.example

import com.example.data.model.PricePoint
import com.example.data.model.summarizeInAppObservedPrices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppObservedPriceMinimumTest {
    @Test
    fun minimumUsesOnlyPricesCapturedByMinimo() {
        val summary = summarizeInAppObservedPrices(
            listOf(
                PricePoint(1_800_000_000_000L, "día 1", 19.99, "Tienda archivada",
                    eventLabel = "Internet Archive", linkType = "Archivo histórico"),
                PricePoint(1_800_086_400_000L, "día 2", 9.99, "Tienda histórica",
                    eventLabel = "Google Product API · price_insights", linkType = "Historial Google Shopping"),
                PricePoint(1_800_172_800_000L, "día 3", 45.99, "Tienda A",
                    eventLabel = "Google Shopping"),
                PricePoint(1_800_259_200_000L, "día 4", 39.49, "Tienda B",
                    eventLabel = "Amazon.es")
            )
        )

        assertEquals(2, summary.observationCount)
        assertEquals(39.49, summary.lowestPoint?.price ?: -1.0, 0.001)
        assertEquals("Tienda B", summary.lowestPoint?.storeName)
    }

    @Test
    fun oneInAppObservationIsShownAsAFirstPriceButArchiveOnlyIsNotInAppData() {
        val firstPrice = PricePoint(
            timestamp = 1_800_000_000_000L,
            dateLabel = "día 1",
            price = 12.50,
            storeName = "Tienda consultada",
            eventLabel = "Google Shopping"
        )
        val single = summarizeInAppObservedPrices(listOf(firstPrice))
        assertEquals(1, single.observationCount)
        assertEquals(12.50, single.lowestPoint?.price ?: -1.0, 0.001)

        val archiveOnly = summarizeInAppObservedPrices(
            listOf(firstPrice.copy(eventLabel = "Internet Archive", linkType = "Archivo histórico"))
        )
        assertEquals(0, archiveOnly.observationCount)
        assertNull(archiveOnly.lowestPoint)
    }
}
