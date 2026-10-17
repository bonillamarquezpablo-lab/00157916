package com.example.notifications

import com.example.data.local.TrackedProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceHistoryCollectionPolicyTest {

    @Test
    fun includesTrackedProductsWithoutAlertTargetsOrNotificationPreference() {
        val now = 1_800_000_000_000L
        val noAlerts = TrackedProductEntity(
            productId = "no-alert",
            targetAlertPrice = null,
            isNotificationEnabled = false,
            trackedAtTimestamp = 10L
        )
        val alertEnabled = TrackedProductEntity(
            productId = "has-alert",
            targetAlertPrice = 50.0,
            isNotificationEnabled = true,
            trackedAtTimestamp = 20L
        )

        val selected = PriceHistoryCollectionPolicy.selectDueProducts(
            tracked = listOf(noAlerts, alertEnabled),
            availableProductIds = setOf("no-alert", "has-alert"),
            lastCheckedAt = emptyMap(),
            now = now
        )

        assertEquals(listOf("no-alert", "has-alert"), selected.map { it.productId })
    }

    @Test
    fun selectsOldestChecksFirstAndHonoursCooldownAndDailyLimit() {
        val now = 1_800_000_000_000L
        val watches = listOf("recent", "older", "never", "oldest", "missing")
            .mapIndexed { index, id ->
                TrackedProductEntity(
                    productId = id,
                    targetAlertPrice = null,
                    isNotificationEnabled = false,
                    trackedAtTimestamp = index.toLong()
                )
            }
        val checkedAt = mapOf(
            "recent" to now - 2L * 60L * 60L * 1000L,
            "older" to now - 30L * 60L * 60L * 1000L,
            "oldest" to now - 60L * 60L * 60L * 1000L
        )

        val selected = PriceHistoryCollectionPolicy.selectDueProducts(
            tracked = watches,
            availableProductIds = setOf("recent", "older", "never", "oldest"),
            lastCheckedAt = checkedAt,
            now = now,
            maxProducts = 3
        )

        assertEquals(listOf("never", "oldest", "older"), selected.map { it.productId })
        assertTrue(selected.none { it.productId == "recent" || it.productId == "missing" })
    }

    @Test
    fun noProductsAreSelectedWhenQuotaIsExhausted() {
        val watch = TrackedProductEntity(productId = "one")
        val selected = PriceHistoryCollectionPolicy.selectDueProducts(
            tracked = listOf(watch),
            availableProductIds = setOf("one"),
            lastCheckedAt = emptyMap(),
            now = 1_800_000_000_000L,
            maxProducts = 0
        )
        assertTrue(selected.isEmpty())
    }
}
