package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaybackSettingsTest {

    @Test
    fun disabledWaybackReturnsWithoutMakingArchiveRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("enabled", false).commit()
        try {
            val result = WaybackPriceHistoryClient(context).fetchHistory(
                originalUrl = "https://shop.example/product/anker-prime",
                expectedTitle = "Anker Prime 27K Power Bank"
            )
            assertFalse(result.available)
            assertTrue(result.quotes.isEmpty())
        } finally {
            preferences.edit().remove("enabled").commit()
        }
    }
}
