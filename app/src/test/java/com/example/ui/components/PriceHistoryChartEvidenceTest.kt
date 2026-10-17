package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.PricePoint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceHistoryChartEvidenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun externalHistoricalSeriesIsSelectableAndDoesNotShowLiveAverage() {
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = listOf(point(1_800_000_000_000L, 499.99, "Google Shopping", "live")),
                    avg90dPrice = 450.0,
                    archiveHistory = listOf(
                        point(1_700_000_000_000L, 399.99, "Internet Archive", "archive")
                    ),
                    googleInsightHistory = listOf(
                        point(1_750_000_000_000L, 429.99, "Google price_insights", "google_insights")
                    )
                )
            }
        }

        composeRule.onNodeWithText("Evolución del precio observado").assertIsDisplayed()
        composeRule.onNodeWithText("Wayback").assertIsDisplayed()

        composeRule.onNodeWithText("Wayback").performClick()
        composeRule.onNodeWithText("Histórico externo · Wayback").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Capturas externas de Internet Archive; no son ofertas actuales."
        ).assertIsDisplayed()
        assertTrue(
            "Archived evidence must not display the live 90-day average",
            composeRule.onAllNodesWithText("Media 90 d:", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        composeRule.onNodeWithText("Google histórico").performClick()
        composeRule.onNodeWithText("Histórico externo · Google").assertIsDisplayed()
        assertTrue(
            "Google historical insights must not display the live 90-day average",
            composeRule.onAllNodesWithText("Media 90 d:", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun point(timestamp: Long, price: Double, source: String, evidence: String) = PricePoint(
        timestamp = timestamp,
        dateLabel = "01/01/2026",
        price = price,
        storeName = "Tienda de prueba",
        eventLabel = source,
        sourceUrl = null,
        evidenceType = evidence
    )
}
