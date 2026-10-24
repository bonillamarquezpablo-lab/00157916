package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import com.example.ui.viewmodel.TrackerUiState
import com.example.data.model.Product
import com.example.data.model.MarketplaceCategorySummary
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.model.StoreOffer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoryHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun categorySearchKeepsFocusUntilTheUserSubmitsTheQuery() {
        val state = mutableStateOf(TrackerUiState())
        composeRule.setContent {
            MaterialTheme {
                MarketScreen(
                    productsForCategory = { MutableStateFlow<List<Product>>(emptyList()) },
                    categorySummaries = ProductCategory.values()
                        .filter { it != ProductCategory.ALL }
                        .map { MarketplaceCategorySummary(it, productCount = 0, storeCount = 0) },
                    storesFlow = MutableStateFlow<List<Store>>(emptyList()),
                    uiState = state.value,
                    onSearchChange = { query -> state.value = state.value.copy(searchQuery = query) },
                    onCategorySelect = { category -> state.value = state.value.copy(selectedCategory = category) },
                    onToggleOnlyAth = {},
                    onToggleOnlyHistoricalMinimum = {},
                    onStoreFilterSelect = { store -> state.value = state.value.copy(selectedStoreName = store) },
                    onSortChange = { sort -> state.value = state.value.copy(sortOption = sort) },
                    onProductClick = {},
                    onToggleTrack = {},
                    onPrepareTrackedRefresh = {},
                    modifier = Modifier
                )
            }
        }

        val search = composeRule.onNodeWithTag("search_input")
        search.performTextInput("ip")
        search.performTextInput("hone")
        search.assertTextEquals("iphone")
        composeRule.onNodeWithTag("category_home_screen").assertIsDisplayed()
        assertEquals("Typing should keep the complete query in state", "iphone", state.value.searchQuery)

        search.performImeAction()
        composeRule.onNodeWithText("Productos guardados").assertIsDisplayed()
    }

    @Test
    fun categoryHomeDoesNotRequestProductRowsUntilAListIsOpened() {
        val state = mutableStateOf(TrackerUiState())
        var productFlowRequested = false
        val summaries = ProductCategory.values()
            .filter { it != ProductCategory.ALL }
            .map { MarketplaceCategorySummary(it, productCount = 0, storeCount = 0) }

        composeRule.setContent {
            MaterialTheme {
                MarketScreen(
                    productsForCategory = {
                        productFlowRequested = true
                        MutableStateFlow<List<Product>>(emptyList())
                    },
                    categorySummaries = summaries,
                    storesFlow = MutableStateFlow<List<Store>>(emptyList()),
                    uiState = state.value,
                    onSearchChange = { query -> state.value = state.value.copy(searchQuery = query) },
                    onCategorySelect = { category -> state.value = state.value.copy(selectedCategory = category) },
                    onToggleOnlyAth = {},
                    onToggleOnlyHistoricalMinimum = {},
                    onStoreFilterSelect = { store -> state.value = state.value.copy(selectedStoreName = store) },
                    onSortChange = { sort -> state.value = state.value.copy(sortOption = sort) },
                    onProductClick = {},
                    onToggleTrack = {},
                    onPrepareTrackedRefresh = {},
                    modifier = Modifier
                )
            }
        }

        composeRule.waitForIdle()
        assertFalse("The category home must not subscribe to product rows", productFlowRequested)

        composeRule.onNodeWithTag("category_card_SMARTPHONES").performClick()
        composeRule.waitForIdle()
        assertTrue("Opening a category must request that category's product flow", productFlowRequested)
    }

    @Test
    fun categorySummariesExcludeAllAndCountDistinctStoresPerCategory() {
        val pcComponents = testStore("pc", "PcComponentes")
        val amazon = testStore("amazon", "Amazon España")
        val products = listOf(
            testProduct(
                id = "phone-1",
                category = ProductCategory.SMARTPHONES,
                currentStore = pcComponents,
                offers = listOf(
                    StoreOffer(pcComponents, 799.0),
                    StoreOffer(amazon, 789.0)
                )
            ),
            testProduct(
                id = "phone-2",
                category = ProductCategory.SMARTPHONES,
                currentStore = pcComponents,
                offers = listOf(StoreOffer(pcComponents, 699.0))
            ),
            testProduct(
                id = "tv-1",
                category = ProductCategory.TV_AUDIO,
                currentStore = amazon
            )
        )

        val summaries = buildCategorySummaries(products)
        val byCategory = summaries.associateBy { it.category }
        val phones = byCategory[ProductCategory.SMARTPHONES]
        val televisions = byCategory[ProductCategory.TV_AUDIO]
        val computing = byCategory[ProductCategory.COMPUTING]

        assertEquals(ProductCategory.values().size - 1, summaries.size)
        assertFalse(summaries.any { it.category == ProductCategory.ALL })
        assertNotNull(phones)
        assertEquals(2, phones!!.productCount)
        assertEquals("Repeated store IDs must count once", 2, phones.storeCount)
        assertNotNull(televisions)
        assertEquals(1, televisions!!.productCount)
        assertEquals(1, televisions.storeCount)
        assertNotNull(computing)
        assertEquals(0, computing!!.productCount)
        assertEquals(0, computing.storeCount)
    }

    private fun testStore(id: String, name: String) = Store(
        id = id,
        name = name,
        domain = "$id.example",
        brandColor = Color(0xFF336699)
    )

    private fun testProduct(
        id: String,
        category: ProductCategory,
        currentStore: Store,
        offers: List<StoreOffer> = emptyList()
    ) = Product(
        id = id,
        title = "Producto de prueba $id",
        brand = "Marca de prueba",
        category = category,
        currentPrice = 100.0,
        athMinPrice = 90.0,
        athMinDate = "",
        athMinStore = currentStore.name,
        athMaxPrice = 120.0,
        avg90dPrice = 105.0,
        officialPvp = 120.0,
        currentStore = currentStore,
        storeUrl = "https://${currentStore.domain}/producto",
        ean = "",
        priceHistory = emptyList(),
        offers = offers
    )
}
