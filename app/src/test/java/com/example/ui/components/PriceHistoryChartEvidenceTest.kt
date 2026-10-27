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
    fun archivedHistoryIsShownByDefaultWhenThereAreNoLiveObservations() {
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = emptyList(),
                    avg90dPrice = 0.0,
                    archiveHistory = listOf(
                        point(1_700_000_000_000L, 89.99, "Internet Archive", "archive")
                    )
                )
            }
        }

        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("Histórico")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Histórico").assertIsDisplayed()
        composeRule.onNodeWithText("1 observación").assertIsDisplayed()
        // Allow Compose to finish switching to the recovered series before checking its detail.
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("89,99 €").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("01/01/2026 en Tienda de prueba")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        // A single archived price must be visible with its date and store immediately;
        // a lone tiny dot with no value makes a successful history recovery look empty.
        composeRule.onNodeWithText("89,99 €").assertIsDisplayed()
        composeRule.onNodeWithText("01/01/2026 en Tienda de prueba").assertIsDisplayed()
    }

    @Test
    fun archivedHistoryExtendingTimelineIsShownByDefaultEvenWhenSeveralLivePricesExist() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = listOf(
                        point(now - 2L * 24L * 60L * 60L * 1000L, 599.99, "Tienda anterior", "live"),
                        point(now - 1L * 24L * 60L * 60L * 1000L, 499.99, "Tienda reciente", "live")
                    ),
                    avg90dPrice = 0.0,
                    archiveHistory = listOf(
                        point(now - 150L * 24L * 60L * 60L * 1000L, 399.99, "Internet Archive", "archive")
                    )
                )
            }
        }

        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("Histórico")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("399,99 €").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Histórico").assertIsDisplayed()
        composeRule.onNodeWithText("399,99 €").assertIsDisplayed()
        composeRule.onNodeWithText("01/01/2026 en Tienda de prueba").assertIsDisplayed()
    }

    @Test
    fun multipleSavedLiveObservationsShowLatestExactPriceAndDateImmediately() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = listOf(
                        point(now - 2L * 24L * 60L * 60L * 1000L, 59.99, "Tienda anterior", "live"),
                        point(now - 1L * 24L * 60L * 60L * 1000L, 49.99, "Tienda reciente", "live")
                    ),
                    avg90dPrice = 0.0
                )
            }
        }

        // A real point and its exact value/date should be visible without a first tap.
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("49,99 €").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("01/01/2026 en Tienda de prueba")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("49,99 €").assertIsDisplayed()
        composeRule.onNodeWithText("01/01/2026 en Tienda de prueba").assertIsDisplayed()
    }

    @Test
    fun externalHistoricalSeriesIsSelectableAndDoesNotShowLiveAverage() {
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = listOf(point(System.currentTimeMillis(), 499.99, "Google Shopping", "live")),
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

        // With a single live observation, recovered external history is the useful default view.
        composeRule.onNodeWithText("Histórico").assertIsDisplayed()
        composeRule.onNodeWithText("Mínimo").performClick()
        composeRule.onNodeWithText("Evolución del precio observado").assertIsDisplayed()

        composeRule.onNodeWithText("Histórico").performClick()
        composeRule.onNodeWithText("Histórico").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Capturas históricas verificadas de Internet Archive y Arquivo.pt; no son ofertas actuales."
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
