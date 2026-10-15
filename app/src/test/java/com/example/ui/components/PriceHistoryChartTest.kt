package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.PricePoint
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
                    priceHistory = listOf(live),
                    avg90dPrice = 0.0,
                    archiveHistory = archives
                )
            }
        }

        composeRule.onNodeWithText("Evolución Real del Precio").assertIsDisplayed()
        composeRule.onNodeWithText("Wayback (2)").performClick()
        composeRule.onNodeWithText("Historial antiguo (Wayback)").assertIsDisplayed()
        composeRule.onNodeWithText("Mín. del archivo: 69.99 €").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Precios anunciados en páginas archivadas. No confirman stock, envío ni precio final y no se mezclan con el historial real."
        ).assertIsDisplayed()
    }

    @Test
    fun waybackOptionRemainsVisibleWhenNoCapturesHaveBeenVerified() {
        composeRule.setContent {
            MaterialTheme {
                PriceHistoryChart(
                    priceHistory = emptyList(),
                    avg90dPrice = 0.0,
                    archiveHistory = emptyList()
                )
            }
        }

        composeRule.onNodeWithText("Evolución Real del Precio").assertIsDisplayed()
        composeRule.onNodeWithText("Wayback (0)").assertIsDisplayed()
        composeRule.onNodeWithText("Sin datos todavía").assertIsDisplayed()
    }
}
