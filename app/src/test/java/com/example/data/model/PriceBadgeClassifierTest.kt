package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceBadgeClassifierTest {

    @Test
    fun unverifiedPriceNeverReceivesADealBadge() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = false,
            currentPrice = 50.0,
            priorObservedPrices = listOf(100.0),
            averagePrice = 80.0,
            hasValidAverage = true
        )
        assertEquals(PriceBadgeType.UNVERIFIED, state.type)
        assertEquals("PRECIO NO VERIFICADO", state.label)
    }

    @Test
    fun savedPricePendingRevalidationNeverReceivesADealBadge() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = false,
            currentPrice = 50.0,
            priorObservedPrices = listOf(40.0),
            averagePrice = 45.0,
            hasValidAverage = true,
            isSavedPendingRevalidation = true
        )
        assertEquals(PriceBadgeType.SAVED_PENDING_REVALIDATION, state.type)
        assertEquals("PRECIO GUARDADO · PENDIENTE", state.label)
    }

    @Test
    fun oneRealObservationIsOnlyTheFirstRecordedPrice() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 369.0,
            priorObservedPrices = emptyList(),
            averagePrice = 0.0,
            hasValidAverage = false
        )
        assertEquals(PriceBadgeType.FIRST_OBSERVATION, state.type)
        assertEquals("PRIMER PRECIO REGISTRADO", state.label)
    }

    @Test
    fun currentPriceEqualToPreviousMinimumIsClassifiedAsObservedMinimum() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 90.0,
            priorObservedPrices = listOf(90.0, 110.0, 100.0),
            averagePrice = 100.0,
            hasValidAverage = true
        )
        assertEquals(PriceBadgeType.HISTORICAL_MINIMUM, state.type)
        assertEquals("🔥 MÍNIMO OBSERVADO", state.label)
    }

    @Test
    fun previousMinimumIsNotClaimedWhenNinetyDayHistoryIsInsufficient() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 90.0,
            priorObservedPrices = listOf(90.0, 110.0),
            averagePrice = 0.0,
            hasValidAverage = false
        )
        assertEquals(PriceBadgeType.HISTORY_IN_PROGRESS, state.type)
        assertEquals("HISTORIAL EN CONSTRUCCIÓN", state.label)
    }

    @Test
    fun priceBelowValidAverageButAbovePreviousMinimumIsGoodPrice() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 95.0,
            priorObservedPrices = listOf(90.0, 110.0),
            averagePrice = 100.0,
            hasValidAverage = true
        )
        assertEquals(PriceBadgeType.GOOD_PRICE, state.type)
        assertEquals("🟢 BUEN PRECIO", state.label)
    }

    @Test
    fun priceAboveValidAverageIsHighPriceWhenNotANewMinimum() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 110.0,
            priorObservedPrices = listOf(90.0, 100.0),
            averagePrice = 100.0,
            hasValidAverage = true
        )
        assertEquals(PriceBadgeType.HIGH_PRICE, state.type)
        assertEquals("🔴 PRECIO ALTO", state.label)
    }

    @Test
    fun missingOrZeroAverageNeverCreatesAHighPriceBadge() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 110.0,
            priorObservedPrices = listOf(90.0, 100.0),
            averagePrice = 0.0,
            hasValidAverage = false
        )
        assertEquals(PriceBadgeType.HISTORY_IN_PROGRESS, state.type)
        assertEquals("HISTORIAL EN CONSTRUCCIÓN", state.label)
    }

    @Test
    fun priceExactlyAtValidAverageGetsNeutralClassification() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 100.0,
            priorObservedPrices = listOf(90.0, 110.0),
            averagePrice = 100.0,
            hasValidAverage = true
        )
        assertEquals(PriceBadgeType.AT_AVERAGE, state.type)
        assertEquals("PRECIO EN LA MEDIA", state.label)
    }
}
