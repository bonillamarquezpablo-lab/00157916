package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineSearchQuotaGuardTest {
    @Test
    fun sameNormalizedQueryIsCooledDownWithoutStoringTheQueryText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_online_search_quota_guard", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val guard = OnlineSearchQuotaGuard(context)
            val now = 1_800_000_000_000L

            assertEquals(0L, guard.tryStartAttempt("GOOGLE_SHOPPING", "  Samsung   S26 ", now))
            assertTrue(
                guard.cooldownRemainingMs("GOOGLE_SHOPPING", "samsung S26", now + 1L) > 0L
            )
            assertTrue(
                guard.tryStartAttempt("GOOGLE_SHOPPING", "Samsung S26", now + 5_000L) > 0L
            )
            assertEquals(0L, guard.cooldownRemainingMs("AMAZON", "Samsung S26", now + 1L))
            assertEquals(
                0L,
                guard.cooldownRemainingMs(
                    "GOOGLE_SHOPPING",
                    "Samsung S26",
                    now + OnlineSearchQuotaGuard.SAME_QUERY_COOLDOWN_MS
                )
            )
            assertEquals(
                0L,
                guard.tryStartAttempt(
                    "GOOGLE_SHOPPING",
                    "Samsung S26",
                    now + OnlineSearchQuotaGuard.SAME_QUERY_COOLDOWN_MS
                )
            )
            assertTrue("Only a hash should be present as the saved preference key",
                preferences.all.keys.none { it.contains("Samsung", ignoreCase = true) })
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
