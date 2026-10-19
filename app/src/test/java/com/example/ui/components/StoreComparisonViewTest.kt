package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreComparisonViewTest {
    @Test
    fun singleOfferIsNotPresentedAsTheCheapestAcrossStores() {
        assertEquals("1 oferta · 1 tienda", storeComparisonSummaryLabel(1, 1))
        assertFalse(shouldHighlightLowestCurrentOffer(
            distinctStoreCount = 1,
            offerPrice = 684.01,
            minimumOfferPrice = 684.01
        ))
    }

    @Test
    fun cheapestBadgeRequiresMoreThanOneDistinctSeller() {
        assertEquals("4 ofertas · 3 tiendas", storeComparisonSummaryLabel(4, 3))
        assertTrue(shouldHighlightLowestCurrentOffer(
            distinctStoreCount = 3,
            offerPrice = 600.00,
            minimumOfferPrice = 600.00
        ))
        assertFalse(shouldHighlightLowestCurrentOffer(
            distinctStoreCount = 3,
            offerPrice = 684.01,
            minimumOfferPrice = 600.00
        ))
    }
}
