package com.example.notifications

import kotlin.math.abs

/** Decision rule that avoids repeating alerts for an unchanged price and target. */
object PriceAlertRules {
    fun shouldNotifyPriceDrop(
        previousPrice: Double?,
        currentPrice: Double,
        targetPrice: Double,
        previousTargetPrice: Double?
    ): Boolean {
        if (!currentPrice.isFinite() || currentPrice <= 0.0 ||
            !targetPrice.isFinite() || targetPrice <= 0.0 ||
            currentPrice > targetPrice
        ) return false

        val targetChanged = previousTargetPrice == null ||
            !previousTargetPrice.isFinite() ||
            abs(previousTargetPrice - targetPrice) >= 0.01
        return targetChanged ||
            previousPrice == null ||
            !previousPrice.isFinite() ||
            previousPrice > targetPrice ||
            currentPrice < previousPrice - 0.009
    }
}
