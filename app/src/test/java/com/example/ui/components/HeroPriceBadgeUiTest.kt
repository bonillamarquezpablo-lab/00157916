package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.data.model.PriceBadgeClassifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeroPriceBadgeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstObservationBadgeIsVisibleAndNeutral() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 369.0,
            priorObservedPrices = emptyList(),
            averagePrice = 0.0,
            hasValidAverage = false
        )

        composeRule.setContent {
            MaterialTheme {
                HeroPriceBadge(state = state)
            }
        }

        composeRule.onNodeWithText("PRIMER PRECIO REGISTRADO").assertIsDisplayed()
        composeRule.onNodeWithText("🔥 MÍNIMO OBSERVADO").assertDoesNotExist()
    }

    @Test
    fun historicalMinimumBadgeIsVisibleWithHeroLabel() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 90.0,
            priorObservedPrices = listOf(90.0, 110.0),
            averagePrice = 100.0,
            hasValidAverage = true
        )

        composeRule.setContent {
            MaterialTheme {
                HeroPriceBadge(state = state)
            }
        }

        composeRule.onNodeWithText("🔥 MÍNIMO OBSERVADO").assertIsDisplayed()
    }

    @Test
    fun missingAverageDoesNotRenderHighPriceLabel() {
        val state = PriceBadgeClassifier.classify(
            isPriceVerified = true,
            currentPrice = 110.0,
            priorObservedPrices = listOf(90.0, 100.0),
            averagePrice = 0.0,
            hasValidAverage = false
        )

        composeRule.setContent {
            MaterialTheme {
                HeroPriceBadge(state = state)
            }
        }

        composeRule.onNodeWithText("HISTORIAL EN CONSTRUCCIÓN").assertIsDisplayed()
        composeRule.onNodeWithText("🔴 PRECIO ALTO").assertDoesNotExist()
    }
}
