package com.example.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePriceFallbackPolicyTest {

    @Test
    fun archiveHttp429OrEmptyCaptureResultTriggersCurrentPriceFallback() {
        assertTrue(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 0,
            selectedProductHistoricalQuotesSaved = 0
        ))
    }

    @Test
    fun alreadyExistingVerifiedHistoricalPointDoesNotTriggerDuplicateLiveLookup() {
        // A price can be found in Wayback but not inserted because today's historical row already
        // exists. That is a successful historical lookup, so the paid fallback is not needed.
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 1,
            selectedProductHistoricalQuotesSaved = 0
        ))
    }

    @Test
    fun newlySavedHistoricalPriceDoesNotTriggerCurrentPriceFallback() {
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 2,
            selectedProductHistoricalQuotesSaved = 1
        ))
    }

    @Test
    fun fallbackIsStillTriggeredWhenOnlySecondaryVariantHadArchiveQuotes() {
        // The policy receives counts for the selected product only. A related model's history
        // must not prevent looking for a current price for the product the user actually selected.
        assertTrue(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 0,
            selectedProductHistoricalQuotesSaved = 0
        ))
    }
}
