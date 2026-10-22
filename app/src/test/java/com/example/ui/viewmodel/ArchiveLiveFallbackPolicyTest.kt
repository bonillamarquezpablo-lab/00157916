package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLiveFallbackPolicyTest {

    @Test
    fun noVerifiedSelectedProductQuoteTriggersCurrentPriceFallback() {
        assertTrue(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 0,
            selectedProductHistoricalQuotesSaved = 0
        ))
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 1,
            selectedProductHistoricalQuotesSaved = 0
        ))
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesFound = 0,
            selectedProductHistoricalQuotesSaved = 1
        ))
    }

    @Test
    fun archiveExceptionTriggersFallbackOnlyWhenProductIsAvailableAndNoVerifiedQuoteExists() {
        assertTrue(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = true,
            selectedProductHistoricalQuoteFound = false
        ))
        assertFalse(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = false,
            selectedProductHistoricalQuoteFound = false
        ))
        assertFalse(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = true,
            selectedProductHistoricalQuoteFound = true
        ))
    }

    @Test
    fun failedFallbackUsesShortRetryCooldownInsteadOfBlockingForTwentyFourHours() {
        val now = 1_800_000_000_000L
        val fiveMinutesAgo = now - 5L * 60L * 1000L
        val twentyMinutesAgo = now - 20L * 60L * 1000L

        assertEquals(
            10L * 60L * 1000L,
            archiveLiveFallbackCooldownRemainingMs(
                nowMillis = now,
                previousAttemptAtMillis = fiveMinutesAgo,
                previousSuccessAtMillis = 0L
            )
        )
        assertEquals(
            0L,
            archiveLiveFallbackCooldownRemainingMs(
                nowMillis = now,
                previousAttemptAtMillis = twentyMinutesAgo,
                previousSuccessAtMillis = 0L
            )
        )
    }

    @Test
    fun successfulFallbackKeepsTwentyFourHourCooldown() {
        val now = 1_800_000_000_000L
        val tenMinutesAgo = now - 10L * 60L * 1000L

        assertEquals(
            ARCHIVE_LIVE_FALLBACK_SUCCESS_COOLDOWN_MS - 10L * 60L * 1000L,
            archiveLiveFallbackCooldownRemainingMs(
                nowMillis = now,
                previousAttemptAtMillis = tenMinutesAgo,
                previousSuccessAtMillis = tenMinutesAgo
            )
        )
    }

    @Test
    fun anExistingSerpApiActionIsQueuedButTheCurrentHistoricalScanDoesNotBlockItsOwnFallback() {
        assertTrue(hasConflictingSerpApiWork(TrackerUiState(isOnlineSearching = true)))
        assertTrue(hasConflictingSerpApiWork(TrackerUiState(isRefreshingPrices = true)))
        assertFalse(hasConflictingSerpApiWork(TrackerUiState(isSearchingFreeHistory = true)))
        assertFalse(hasConflictingSerpApiWork(TrackerUiState()))
    }
}
