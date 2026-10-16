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
import com.example.data.remote.groupOnlineShoppingResults
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
                    onForceRefresh = {},
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
    fun catalogOnlyGoogleLinkOpensProductDetailsWhenMerchantUrlIsMissing() {
        var openedProductIdentity: String? = null
        val query = "Anker Prime 27K Power Bank"
        val result = SerpApiShoppingResult(
            title = query,
            price = 89.99,
            storeName = "Google Shopping",
            sourceUrl = "https://www.google.com/shopping/product/123456789",
            observedAt = 1_800_000_000_000L,
            googleProductId = "123456789"
        )

        composeRule.setContent {
            val state = TrackerUiState(
                onlineSearchQuery = query,
                onlineSearchResults = listOf(result),
                onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
            )
            MaterialTheme {
                OnlineSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onForceRefresh = {},
                    onAmazonOnlyChange = {},
                    onShowSecondHandChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = { openedProductIdentity = it },
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        // Grouping runs on Dispatchers.Default; wait for the asynchronous display groups
        // before asserting the result card's navigation label.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ver tiendas y enlaces directos")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ver tiendas y enlaces directos").assertIsDisplayed()
        composeRule.onNodeWithText("Ver tiendas y enlaces directos").performClick()
        assertEquals(
            "A catalog-only Google URL with a product ID should route to the in-app comparison flow",
            result.let { groupOnlineShoppingResults(listOf(it)).single().identityKey },
            openedProductIdentity
        )
    }

    @Test
    fun secondHandOffersJoinTheSamePriceOrderWhenEnabled() {
        // Model-first searches still contain an explicit diameter + PCD and must share one price order.
        val query = "RIAL Milano 14\" 5x100"
        val rows = listOf(
            SerpApiShoppingResult(
                title = "Magnetto MW R1-1672 llanta de acero 14 pulgadas 5x100",
                price = 59.00,
                storeName = "Tienda nueva",
                sourceUrl = "https://shop.example.test/magnetto",
                observedAt = 123L,
                condition = "new"
            ),
            SerpApiShoppingResult(
                title = "Llantas Seat Ibiza 165/70/14 81T neumáticos 5x100",
                price = 25.00,
                storeName = "Wallapop",
                sourceUrl = "https://es.wallapop.com/item/seat-ibiza",
                observedAt = 123L,
                condition = "used"
            ),
            SerpApiShoppingResult(
                title = "Llantas 14 5x100 Volkswagen",
                price = 40.00,
                storeName = "Vendedor particular",
                sourceUrl = "https://particular.example.test/llantas-vw",
                observedAt = 123L,
                condition = "used"
            ),
            SerpApiShoppingResult(
                title = "Nueva llanta de hierro de 14 pulgadas 5x100",
                price = 60.00,
                storeName = "Otra tienda",
                sourceUrl = "https://shop.example.test/steel-wheel",
                observedAt = 123L,
                condition = "new"
            )
        )

        val allVisible = buildOnlineSearchDisplayGroups(
            results = rows,
            query = query,
            showSecondHand = true,
            amazonOnly = false
        )
        assertEquals(listOf(25.0, 40.0, 59.0, 60.0), allVisible.map { it.group.minPrice })
        assertTrue(allVisible.first().group.offers.all { it.isSecondHand })

        val newOnly = buildOnlineSearchDisplayGroups(
            results = rows,
            query = query,
            showSecondHand = false,
            amazonOnly = false
        )
        assertEquals(listOf(59.0, 60.0), newOnly.map { it.group.minPrice })
        assertTrue(newOnly.none { display -> display.group.offers.any { it.isSecondHand } })
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
                    onForceRefresh = {},
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
        assertEquals(14, results.size)
        assertTrue(results.any { it.storeName == "Wallapop" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Amazon Warehouse" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Milanuncios" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "eBay" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Vinted" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Vendedor particular" && it.isSecondHand })
        assertTrue(results.any { it.condition == "refurbished" && it.isSecondHand })

        // OFF + Solo Amazon.
        clickFilter("Solo Amazon (1)")
        assertVisibleText("Todos (5)")
        assertVisibleText("Solo Amazon (1)")
        assertTrue(
            composeRule.onAllNodesWithText("7 tiendas · precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Solo Amazon: Amazon Warehouse is appended as a separate labelled card.
        clickFilter("Mostrar segunda mano")
        assertVisibleText("Todos (6)")
        assertVisibleText("Solo Amazon (2)")
        assertVisibleText("Segunda mano")
        assertVisibleText("1 tiendas · precio más bajo en Amazon Warehouse")
        assertTrue(
            composeRule.onAllNodesWithText("7 tiendas · precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Todos: Wallapop is added after the new-shop card, not instead of it.
        clickFilter("Todos (6)")
        assertVisibleText("Todos (6)")
        assertVisibleText("Solo Amazon (2)")
        assertVisibleText("3 tiendas · precio más bajo en Amazon.es")
        assertVisibleText("7 tiendas · precio más bajo en Wallapop")
        assertEquals(4, results.count { it.isAccessoryOffer })
        assertVisibleText("Póster Samsung Galaxy S26 Ultra 512 GB")
        assertTrue(
            "The accessory badge is visible on the poster card",
            composeRule.onAllNodesWithText("Accesorio").fetchSemanticsNodes().isNotEmpty()
        )
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
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"929,99 €","extracted_price":929.99,"product_link":"https://www.milanuncios.com/moviles/s26.htm","source":"Milanuncios","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"949,99 €","extracted_price":949.99,"product_link":"https://www.ebay.es/itm/s26","source":"eBay","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"969,99 €","extracted_price":969.99,"product_link":"https://www.vinted.es/items/s26","source":"Vinted","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"979,99 €","extracted_price":979.99,"product_link":"https://particular.example/s26","source":"Vendedor particular","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"989,99 €","extracted_price":989.99,"product_link":"https://reacondicionados.example/s26","source":"Tienda reacondicionados","second_hand_condition":"refurbished"},
                {"title":"Samsung Galaxy S26 Ultra 512 GB","price":"999,99 €","extracted_price":999.99,"product_link":"https://www.amazon.es/dp/B0WAREHOUSE","source":"Amazon Warehouse","second_hand_condition":"new"},
                {"title":"Póster Samsung Galaxy S26 Ultra 512 GB","price":"19,99 €","extracted_price":19.99,"product_link":"https://posterstore.example/s26","source":"Poster Store","second_hand_condition":"new"},
                {"title":"Funda Samsung Galaxy S26 Ultra","price":"14,99 €","extracted_price":14.99,"product_link":"https://fundas.example/s26","source":"Tienda de fundas","second_hand_condition":"new"},
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
    fun wheelSetsDisplayPerWheelPriceAndExplicitSetBadge() {
        val query = "autec zenit 14 5x100"
        val rows = listOf(
            SerpApiShoppingResult(
                title = "4 Autec Zenit Felgen 5.5x14 5x100",
                price = 432.19,
                storeName = "Tienda juego",
                sourceUrl = "https://shop.example/four-wheel-set",
                observedAt = 123L
            )
        )
        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(
                        onlineSearchQuery = query,
                        onlineSearchResults = rows,
                        onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
                    ),
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onForceRefresh = {},
                    onAmazonOnlyChange = {},
                    onToggleDiscarded = {},
                    onShowSecondHandChange = {},
                    onOpenSettings = {},
                    onOpenProduct = {},
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }
        val searchList = composeRule.onNodeWithTag("online-search-list")
        searchList.performScrollToNode(hasText("Juego de 4"))
        composeRule.onNodeWithText("Juego de 4").assertIsDisplayed()
        searchList.performScrollToNode(hasText(" / llanta", substring = true))
        assertTrue(composeRule.onAllNodesWithText(" / llanta", substring = true).fetchSemanticsNodes().isNotEmpty())
        searchList.performScrollToNode(hasText("Precio total del juego de 4", substring = true))
        assertTrue(composeRule.onAllNodesWithText("Precio total del juego de 4", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun explicitRefreshIsAvailableForExistingResultsAndExplainsQuotaCost() {
        val result = SerpApiShoppingResult(
            title = "Autec Zenit",
            price = 133.12,
            storeName = "Tienda",
            sourceUrl = "https://shop.example/autec-zenit",
            observedAt = 123L,
            isFitmentUnverified = true
        )
        var refreshInvocations = 0
        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(
                        onlineSearchQuery = "Autec Zenit 5.5x14 5x100",
                        onlineSearchResults = listOf(result),
                        onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
                    ),
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onForceRefresh = { refreshInvocations++ },
                    onAmazonOnlyChange = {},
                    onShowSecondHandChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = {},
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        val searchList = composeRule.onNodeWithTag("online-search-list")
        searchList.performScrollToNode(hasText("Actualizar resultados ahora (puede consumir cuota)"))
        composeRule.onNodeWithText("Actualizar resultados ahora (puede consumir cuota)").assertIsDisplayed()
        composeRule.onNodeWithText("Actualizar resultados ahora (puede consumir cuota)").performClick()
        assertEquals(1, refreshInvocations)
        searchList.performScrollToNode(hasText("Comprueba diámetro, anclaje, ancho y ET", substring = true))
        assertTrue(composeRule.onAllNodesWithText("Comprueba diámetro, anclaje, ancho y ET", substring = true).fetchSemanticsNodes().isNotEmpty())
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
                    onForceRefresh = {},
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
