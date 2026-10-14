package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import com.example.data.remote.RejectedOnlineOffer
import com.example.ui.viewmodel.TrackerUiState
import org.junit.Assert.assertTrue
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
                    onShowSecondHandChange = {},
                    onToggleDiscarded = { expanded.value = !expanded.value },
                    onOpenSettings = {},
                    onOpenProduct = {},
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        val searchList = composeRule.onNodeWithTag("online-search-list")
        searchList.performScrollToNode(hasText("Ver descartados (1)"))
        composeRule.onNodeWithText("Ver descartados (1)").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(rejectedOffer.title).fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("Ver descartados (1)").performClick()
        searchList.performScrollToNode(hasText(rejectedOffer.title))
        composeRule.onNodeWithText(rejectedOffer.title).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.priceLabel).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.reason).assertIsDisplayed()
        composeRule.onNodeWithText(rejectedOffer.provider).assertIsDisplayed()

        searchList.performScrollToNode(hasText("Ocultar descartados"))
        composeRule.onNodeWithText("Ocultar descartados").performClick()
        assertTrue(composeRule.onAllNodesWithText(rejectedOffer.title).fetchSemanticsNodes().isEmpty())
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
                    onShowSecondHandChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = {},
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("Ver descartados (0)").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Ocultar descartados").fetchSemanticsNodes().isEmpty())
    }
}
