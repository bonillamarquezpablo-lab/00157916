package com.example.notifications

import com.example.data.local.TrackedProductEntity

/**
 * Selects which watched products receive a real provider lookup for their daily history.
 *
 * It deliberately does not inspect target-alert prices or notification preferences: every
 * tracked product can contribute to price history; those settings only affect notifications.
 */
internal object PriceHistoryCollectionPolicy {
    const val MAX_PRODUCTS_PER_DAY = 3
    const val MIN_RECHECK_INTERVAL_MS = 20L * 60L * 60L * 1000L

    fun selectDueProducts(
        tracked: List<TrackedProductEntity>,
        availableProductIds: Set<String>,
        lastCheckedAt: Map<String, Long>,
        now: Long,
        maxProducts: Int = MAX_PRODUCTS_PER_DAY
    ): List<TrackedProductEntity> {
        if (maxProducts <= 0) return emptyList()

        return tracked.asSequence()
            .filter { it.productId in availableProductIds }
            .filter { watch ->
                val lastChecked = lastCheckedAt[watch.productId] ?: 0L
                lastChecked <= 0L || (now >= lastChecked &&
                    now - lastChecked >= MIN_RECHECK_INTERVAL_MS)
            }
            .sortedWith(
                compareBy<TrackedProductEntity> { lastCheckedAt[it.productId] ?: 0L }
                    .thenBy { it.trackedAtTimestamp }
                    .thenBy { it.productId }
            )
            .take(maxProducts)
            .toList()
    }
}
