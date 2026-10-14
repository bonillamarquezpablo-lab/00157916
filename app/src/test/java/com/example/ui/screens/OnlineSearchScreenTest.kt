package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.remote.RejectedOnlineOffer
import com.example.ui.viewmodel.TrackerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineSearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun discardedOffersCanBeExpandedAndCollapsed() {
        val rejectedOffer = RejectedOnlineOffer(
            title = "Samsung Galaxy S26 Ultra 512 GB 49,99 €/mes",
            priceLabel = "49,99 €/mes",
            reason = "Alquiler o cuota mensual",
            provider = "Google Shopping"
        )

        composeRule.setContent {
            val expanded = remember { mutableStateOf(false) }
            val state = TrackerUiState(
                onlineSearchQuery = "Samsung Galaxy S26 Ultra 512 GB",
                onlineSearchDiscardedOffers = listOf(rejectedOffer),
                showOnlineSearchDiscarded = expanded.value
            )
            MaterialTheme {
                OnlineSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onAmazonOnlyChange = {},
                    onToggleDiscarded = { expanded.value = !expanded.value },
                    onOpenSettings = {},
                    onOpenProduct = {}
                )
            }
        }

        composeRule.onNodeWithText("Ver descartados (1)").assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.title).assertDoesNotExist()

        composeRule.onNodeWithText("Ver descartados (1)").performClick()
        composeRule.onNodeWithText(rejectedOffer.title).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.priceLabel).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.reason).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.provider).assertIsDisplayed()

        composeRule.onNodeWithText("Ocultar descartados").performClick()
        composeRule.onNodeWithText(rejectedOffer.title).assertDoesNotExist()
        composeRule.onNodeWithText("Ver descartados (1)").assertIsDisplayed()
    }

    @Test
    fun discardedToggleIsNotShownWhenThereAreNoRejectedOffers() {
        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(onlineSearchQuery = "Samsung"),
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onAmazonOnlyChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = {}
                )
            }
        }

        composeRule.onNodeWithText("Ver descartados (0)").assertDoesNotExist()
        composeRule.onNodeWithText("Ocultar descartados").assertDoesNotExist()
    }
}
