package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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
    fun disabledWaybackMakesZeroHttpRequestsToInternetArchive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("minimo_wayback_backfill", Context.MODE_PRIVATE)
        val requestCount = AtomicInteger(0)
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                throw AssertionError("An HTTP request must not be attempted when Wayback is disabled")
            }
            .build()
        preferences.edit().putBoolean("enabled", false).commit()
        try {
            val result = WaybackPriceHistoryClient(context, http).fetchHistory(
                originalUrl = "https://shop.example/product/anker-prime",
                expectedTitle = "Anker Prime 27K Power Bank"
            )
            assertFalse(result.available)
            assertTrue(result.quotes.isEmpty())
            assertEquals("Disabled Wayback must not make even a single HTTP request", 0, requestCount.get())
        } finally {
            preferences.edit().remove("enabled").commit()
        }
    }
}
