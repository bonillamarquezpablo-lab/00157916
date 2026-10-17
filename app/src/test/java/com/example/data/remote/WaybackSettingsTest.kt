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
                throw AssertionError("HTTP must not be attempted when Wayback is disabled")
            }
            .build()
        preferences.edit().putBoolean("enabled", false).commit()
        try {
            val client = WaybackPriceHistoryClient(context, http)
            val direct = client.fetchHistory(
                originalUrl = "https://shop.example/product/anker-prime",
                expectedTitle = "Anker Prime 27K Power Bank"
            )
            val merchant = client.fetchHistoryByMerchantDomain(
                domain = "shop.example",
                expectedTitle = "Anker Prime 27K Power Bank"
            )
            assertFalse(direct.available)
            assertTrue(direct.quotes.isEmpty())
            assertFalse(merchant.available)
            assertTrue(merchant.quotes.isEmpty())
            assertEquals("Disabled Wayback must not make any HTTP requests", 0, requestCount.get())
        } finally {
            preferences.edit().remove("enabled").commit()
        }
    }
}
