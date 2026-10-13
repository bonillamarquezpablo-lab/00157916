package com.example.data.remote

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SerpApiAccountInfoTest {

    @Test
    fun parsesCurrentMonthlyBalanceAndHourlyThroughputFromAccountApi() {
        val json = JSONObject(
            """
            {
              "account_status": "Active",
              "plan_name": "Free",
              "searches_per_month": 250,
              "plan_searches_left": 143,
              "extra_credits": 0,
              "total_searches_left": 143,
              "this_month_usage": 107,
              "this_hour_searches": 17,
              "last_hour_searches": 21,
              "account_rate_limit_per_hour": 50,
              "plan_renewal_date": "2026-11-01"
            }
            """.trimIndent()
        )

        val info = SerpApiClient().parseAccountInfo(json)

        assertEquals("Free", info.planName)
        assertEquals(250, info.monthlyLimit)
        assertEquals(107, info.monthlySearchesUsed)
        assertEquals(143, info.searchesRemaining)
        assertEquals(50, info.hourlyLimit)
        assertEquals(17, info.searchesThisHour)
        assertEquals(33, info.remainingThisHour)
        assertEquals("2026-11-01", info.renewalDate)
        assertTrue(info.quotaSummary().contains("143 de 250"))
    }

    @Test
    fun usesPlanBalancePlusExtraCreditsWhenTotalIsNotReturned() {
        val json = JSONObject(
            """
            {
              "plan_name": "Custom",
              "plan_searches_left": 12,
              "extra_credits": 3,
              "account_rate_limit_per_hour": 20,
              "this_hour_searches": 19
            }
            """.trimIndent()
        )

        val info = SerpApiClient().parseAccountInfo(json)

        assertEquals(15, info.searchesRemaining)
        assertEquals(1, info.remainingThisHour)
    }
}
