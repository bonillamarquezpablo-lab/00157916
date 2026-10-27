package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLiveFallbackPolicyTest {

    @Test
    fun fallbackRunsWhenArchiveDidNotSaveOrConfirmAValidPoint() {
        // No quote, or quotes extracted but rejected during persistence: search the current price.
        assertTrue(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesSaved = 0,
            selectedProductHistoricalDuplicatesConfirmed = 0
        ))
        // A valid existing point is enough to avoid a duplicate paid lookup.
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesSaved = 0,
            selectedProductHistoricalDuplicatesConfirmed = 1
        ))
        assertFalse(shouldTriggerLivePriceFallback(
            selectedProductHistoricalQuotesSaved = 1,
            selectedProductHistoricalDuplicatesConfirmed = 0
        ))
    }

    @Test
    fun archiveExceptionTriggersFallbackOnlyWhenProductWasNotPersisted() {
        assertTrue(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = true,
            selectedProductHistoricalPointPersisted = false
        ))
        assertFalse(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = false,
            selectedProductHistoricalPointPersisted = false
        ))
        assertFalse(shouldTriggerLiveFallbackAfterArchiveException(
            hasFallbackProduct = true,
            selectedProductHistoricalPointPersisted = true
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
