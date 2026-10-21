package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceHistoryChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun waybackSelectorShowsArchiveSeriesSeparatelyFromLiveHistory() {
        val now = System.currentTimeMillis()
        val live = PricePoint(
            timestamp = now,
            dateLabel = "hoy",
            price = 99.99,
            storeName = "Tienda actual",
            sourceUrl = "https://shop.example/products/item",
            evidenceType = "live"
        )
        val archives = listOf(
            PricePoint(
                timestamp = now - 220L * 24L * 60L * 60L * 1000L,
                dateLabel = "hace 220 días",
                price = 79.99,
                storeName = "Tienda archivada",
                sourceUrl = "https://web.archive.org/web/20260201000000id_/https://shop.example/products/item",
                evidenceType = "archive"
            ),
            PricePoint(
                timestamp = now - 120L * 24L * 60L * 60L * 1000L,
                dateLabel = "hace 120 días",
                price = 69.99,
                storeName = "Tienda archivada",
                sourceUrl = "https://web.archive.org/web/20260501000000id_/https://shop.example/products/item",
                evidenceType = "archive"
            )
        )

        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    // Two live observations preserve the explicit live-first selector behavior.
                    priceHistory = listOf(
                        live.copy(timestamp = now - 10L * 24L * 60L * 60L * 1000L),
                        live
                    ),
                    avg90dPrice = 0.0,
                    archiveHistory = archives
                )
            }
        }

        composeRule.onNodeWithText("Evolución del precio observado").assertIsDisplayed()
        composeRule.onNodeWithText("Wayback").performClick()
        composeRule.onNodeWithText("Histórico externo · Wayback").assertIsDisplayed()
        composeRule.onNodeWithText("Mín. del rango:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Capturas externas de Internet Archive; no son ofertas actuales."
        ).assertIsDisplayed()
    }

    @Test
    fun archiveSeriesIsSelectedByDefaultWhenOnlyOneLivePointExists() {
        val now = System.currentTimeMillis()
        val live = PricePoint(
            timestamp = now,
            dateLabel = "hoy",
            price = 859.93,
            storeName = "PcComponentes",
            sourceUrl = "https://www.pccomponentes.com/apple-iphone-15-plus-256gb",
            evidenceType = "live"
        )
        val archived = PricePoint(
            timestamp = now - 90L * 24L * 60L * 60L * 1000L,
            dateLabel = "hace 90 días",
            price = 799.99,
            storeName = "PcComponentes",
            sourceUrl = "https://web.archive.org/web/20260701000000id_/https://www.pccomponentes.com/apple-iphone-15-plus-256gb",
            evidenceType = "archive"
        )

        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = listOf(live),
                    avg90dPrice = 0.0,
                    archiveHistory = listOf(archived)
                )
            }
        }

        composeRule.onNodeWithText("Histórico externo · Wayback").assertIsDisplayed()
        composeRule.onNodeWithText("Mín. del rango:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("price_history_chart_container").assertIsDisplayed()
    }

    @Test
    fun oneSavedArchiveObservationIsShownAsItsOwnSeriesWhenNoLiveHistoryExists() {
        val archivedPoint = PricePoint(
            timestamp = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L,
            dateLabel = "hace 90 días",
            price = 799.99,
            storeName = "PcComponentes",
            sourceUrl = "https://web.archive.org/web/20260701000000id_/https://www.pccomponentes.com/apple-iphone-15-plus-256gb",
            evidenceType = "archive"
        )

        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = emptyList(),
                    avg90dPrice = 0.0,
                    archiveHistory = listOf(archivedPoint)
                )
            }
        }

        composeRule.onNodeWithText("Histórico externo · Wayback").assertIsDisplayed()
        composeRule.onNodeWithText("Mín. del rango:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("price_history_chart_container").assertIsDisplayed()
    }

    @Test
    fun emptyHistoryShowsHelpfulEmptyStateWithoutArchiveSelector() {
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = emptyList(),
                    avg90dPrice = 0.0,
                    archiveHistory = emptyList()
                )
            }
        }

        composeRule.onNodeWithText("Evolución del precio observado").assertIsDisplayed()
        composeRule.onNodeWithText("Sin datos todavía").assertIsDisplayed()
        composeRule.onNodeWithTag("price_history_chart_container").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Wayback").fetchSemanticsNodes().size)
    }
}
