package com.example.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceAlertRulesTest {
    @Test
    fun notifiesWhenPriceCrossesTargetFromAbove() {
        assertTrue(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = 125.0,
                currentPrice = 99.99,
                targetPrice = 100.0,
                previousTargetPrice = 100.0
            )
        )
    }

    @Test
    fun notifiesAgainWhenPriceDropsFurtherBelowTarget() {
        assertTrue(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = 95.0,
                currentPrice = 89.0,
                targetPrice = 100.0,
                previousTargetPrice = 100.0
            )
        )
    }

    @Test
    fun doesNotRepeatForTheSamePriceAndTarget() {
        assertFalse(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = 89.0,
                currentPrice = 89.0,
                targetPrice = 100.0,
                previousTargetPrice = 100.0
            )
        )
    }

    @Test
    fun notifiesWhenTheUserChangesTheTargetToCoverCurrentPrice() {
        assertTrue(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = 89.0,
                currentPrice = 89.0,
                targetPrice = 90.0,
                previousTargetPrice = 100.0
            )
        )
    }

    @Test
    fun neverNotifiesAboveTheTargetOrForInvalidValues() {
        assertFalse(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = 80.0,
                currentPrice = 101.0,
                targetPrice = 100.0,
                previousTargetPrice = 100.0
            )
        )
        assertFalse(
            PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = null,
                currentPrice = Double.NaN,
                targetPrice = 100.0,
                previousTargetPrice = null
            )
        )
    }
}
