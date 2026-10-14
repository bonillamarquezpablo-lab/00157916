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
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiShoppingResult
import com.example.ui.viewmodel.OnlineSearchProvider
import com.example.ui.viewmodel.TrackerUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun allFourFilterCombinationsUseLoadedResultsAndKeepCategoriesSeparate() {
        val query = "Samsung Galaxy S26 Ultra 512 GB"
        val results = exampleShoppingResponse(query)
        var searchInvocations = 0

        composeRule.setContent {
            val showSecondHand = remember { mutableStateOf(false) }
            val amazonOnly = remember { mutableStateOf(false) }
            val state = TrackerUiState(
                onlineSearchQuery = query,
                onlineSearchResults = results,
                onlineSearchAmazonOnly = amazonOnly.value,
                onlineSearchShowSecondHand = showSecondHand.value,
                onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
            )
            MaterialTheme {
                OnlineSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onSearch = { searchInvocations++ },
                    onAmazonSearch = { searchInvocations++ },
                    onAmazonOnlyChange = { amazonOnly.value = it },
                    onShowSecondHandChange = { showSecondHand.value = it },
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = {},
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        val searchList = composeRule.onNodeWithTag("online-search-list")
        fun assertVisibleText(text: String) {
            searchList.performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
        fun clickFilter(text: String) {
            searchList.performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).performClick()
        }

        // OFF + Todos: new shops and Amazon remain; Wallapop and Warehouse are hidden.
        assertVisibleText("Todos (5)")
        assertVisibleText("Solo Amazon (1)")
        assertEquals(5, results.size)
        assertTrue(results.any { it.storeName == "Wallapop" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Amazon Warehouse" && it.isSecondHand })

        // OFF + Solo Amazon.
        clickFilter("Solo Amazon (1)")
        assertVisibleText("Todos (5)")
        assertVisibleText("Solo Amazon (1)")
        assertTrue(
            composeRule.onAllNodesWithText("precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Solo Amazon: Amazon Warehouse is appended as a separate labelled card.
        clickFilter("Mostrar segunda mano")
        assertVisibleText("Todos (6)")
        assertVisibleText("Solo Amazon (2)")
        assertVisibleText("Segunda mano")
        assertVisibleText("precio más bajo en Amazon Warehouse")
        assertTrue(
            composeRule.onAllNodesWithText("precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Todos: Wallapop is added after the new-shop card, not instead of it.
        clickFilter("Todos (6)")
        assertVisibleText("Todos (6)")
        assertVisibleText("Solo Amazon (2)")
        assertVisibleText("precio más bajo en Wallapop")
        assertVisibleText("Accesorio")
        assertVisibleText("Póster Samsung Galaxy S26 Ultra 512 GB")
        assertEquals("Changing either filter must not start another SerpApi search", 0, searchInvocations)
    }

    private fun exampleShoppingResponse(query: String): List<SerpApiShoppingResult> {
        val response = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"1.299,99 €","extracted_price":1299.99,"product_link":"https://tienda-uno.example/s26","source":"Tienda Uno","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"1.279,99 €","extracted_price":1279.99,"product_link":"https://tienda-dos.example/s26","source":"Tienda Dos","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"1.249,99 €","extracted_price":1249.99,"product_link":"https://www.amazon.es/dp/B0NEW","source":"Amazon.es","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"899,99 €","extracted_price":899.99,"product_link":"https://es.wallapop.com/item/s26","source":"Wallapop","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"999,99 €","extracted_price":999.99,"product_link":"https://www.amazon.es/dp/B0WAREHOUSE","source":"Amazon Warehouse","second_hand_condition":"new"},
                {"title":"Póster Samsung Galaxy S26 Ultra 512 GB","price":"19,99 €","extracted_price":19.99,"product_link":"https://posterstore.example/s26","source":"Poster Store","second_hand_condition":"new"},
                {"title":"Funda Samsung Galaxy S26 Ultra 512 GB","price":"14,99 €","extracted_price":14.99,"product_link":"https://fundas.example/s26","source":"Tienda de fundas","second_hand_condition":"new"},
                {"title":"Cargador Samsung Galaxy S26 Ultra 512 GB","price":"24,99 €","extracted_price":24.99,"product_link":"https://cargadores.example/s26","source":"Tienda de cargadores","second_hand_condition":"new"},
                {"title":"Protector Samsung Galaxy S26 Ultra 512 GB","price":"9,99 €","extracted_price":9.99,"product_link":"https://protectores.example/s26","source":"Tienda de protectores","second_hand_condition":"new"}
              ]
            }
            """.trimIndent()
        )
        return SerpApiClient().parseShoppingResultsDetailed(
            root = response,
            query = query,
            observedAt = System.currentTimeMillis()
        ).results
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
