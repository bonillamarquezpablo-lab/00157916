package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.isAccessorySearchQuery
import com.example.data.remote.isAccessoryListingTitle
import com.example.data.remote.isToyListingTitle
import com.example.data.remote.isReplacementPartListingTitle
import com.example.ui.viewmodel.OnlineSearchProvider
import com.example.ui.viewmodel.optimizedOnlineShoppingQuery
import com.example.ui.viewmodel.TrackerUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun genericShoppingResultCardStillOpensTheProductFicha() {
        val title = "Sony WH-1000XM5 Wireless Headphones"
        val fallback = SerpApiShoppingResult(
            title = title,
            price = 199.99,
            storeName = "Google Shopping",
            sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Sony+WH-1000XM5",
            observedAt = System.currentTimeMillis(),
            condition = "new"
        )
        var openedIdentity: String? = null

        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(
                        onlineSearchQuery = "wireless headphones",
                        onlineSearchResults = listOf(fallback),
                        onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
                    ),
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onForceRefresh = {},
                    onAmazonOnlyChange = {},
                    onShowSecondHandChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = { openedIdentity = it },
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        val searchList = composeRule.onNodeWithTag("online-search-list")
        searchList.performScrollToNode(hasText(title))
        composeRule.onNodeWithTag("online-search-product-card").assertIsDisplayed()
        composeRule.onNode(hasText("Precio no verificado", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithTag("open-online-product-ficha").assertIsDisplayed().performClick()
        assertEquals(
            "A product card with a fallback seller link must still open its Room-backed ficha",
            com.example.data.remote.onlineProductIdentityKey(title),
            openedIdentity
        )
    }

    @Test
    fun relatedMarketPlaceholderAppearsInSearchAndItsFichaButtonOpensTheSameIdentity() {
        val title = "Dyson Airwrap i.d. Curly + Coily Amber Silk"
        val candidate = Product(
            id = "online_candidate_market_test",
            title = title,
            brand = "Dyson",
            category = ProductCategory.PERSONAL_CARE,
            currentPrice = 249.99,
            athMinPrice = 0.0,
            athMinDate = "Pendiente de verificación",
            athMinStore = "",
            athMaxPrice = 0.0,
            avg90dPrice = 0.0,
            officialPvp = 0.0,
            currentStore = Store.ALL_STORES.first(),
            storeUrl = "https://www.google.com/search?tbm=shop&q=Dyson+Airwrap",
            ean = "",
            priceHistory = emptyList(),
            offers = emptyList(),
            isPriceVerified = false
        )
        var openedIdentity: String? = null

        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(
                        onlineSearchQuery = "plancha Dyson",
                        onlineSearchRelatedMarketCandidates = listOf(candidate),
                        onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
                    ),
                    onQueryChange = {},
                    onSearch = {},
                    onAmazonSearch = {},
                    onForceRefresh = {},
                    onAmazonOnlyChange = {},
                    onShowSecondHandChange = {},
                    onToggleDiscarded = {},
                    onOpenSettings = {},
                    onOpenProduct = { openedIdentity = it },
                    modifier = Modifier.testTag("online-search-list")
                )
            }
        }

        val searchList = composeRule.onNodeWithTag("online-search-list")
        searchList.performScrollToNode(hasText("También en tu Mercado"))
        searchList.performScrollToNode(hasText(title))
        composeRule.onNodeWithText("También en tu Mercado").assertIsDisplayed()
        composeRule.onNodeWithText("PRECIO NO VERIFICADO · Ya existe en Mercado").assertIsDisplayed()
        composeRule.onNodeWithTag("open-related-market-online_candidate_market_test")
            .assertIsDisplayed()
            .performClick()
        assertEquals(com.example.data.remote.onlineProductIdentityKey(title), openedIdentity)
    }

    @Test
    fun dysonSparePartsAreTaggedAsAccessoriesAndToysStaySeparate() {
        assertTrue(isAccessoryListingTitle("Boquilla para secador Dyson Supersonic"))
        assertTrue(isAccessoryListingTitle("Dyson Supersonic diffuser attachment"))
        assertTrue(isAccessoryListingTitle("Accesorio peluquería para Dyson Airwrap Origin Hairstyler"))
        assertTrue(isAccessoryListingTitle("Hair accessories for Dyson Airwrap"))
        assertFalse(isReplacementPartListingTitle("Accesorio peluquería para Dyson Airwrap Origin Hairstyler"))
        assertTrue(isReplacementPartListingTitle("Boquilla para secador Dyson Supersonic"))
        assertTrue(isReplacementPartListingTitle("Recambio de cabezal Dyson"))
        assertFalse(isReplacementPartListingTitle("Dyson Supersonic completo"))
        assertTrue(isToyListingTitle("Juguete Dyson secador de pelo para niños"))
        assertFalse(isAccessoryListingTitle("Juguete Dyson secador de pelo para niños"))
        assertFalse(isToyListingTitle("Boquilla para secador Dyson Supersonic"))
    }

    @Test
    fun onlyKnownAmbiguousBrandQueriesAreRefinedWithoutAddingSearches() {
        assertEquals(
            "Shimano bicycle components cycling",
            optimizedOnlineShoppingQuery("Shimano", OnlineSearchProvider.GOOGLE_SHOPPING)
        )
        assertEquals(
            "Anker Prime power bank",
            optimizedOnlineShoppingQuery("Anker Prime", OnlineSearchProvider.GOOGLE_SHOPPING)
        )
        assertEquals(
            "Samsung Galaxy A57 256GB",
            optimizedOnlineShoppingQuery("Samsung Galaxy A57 256GB", OnlineSearchProvider.GOOGLE_SHOPPING)
        )
        assertEquals(
            "Anker Prime",
            optimizedOnlineShoppingQuery("Anker Prime", OnlineSearchProvider.AMAZON)
        )
    }

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
        searchList.performScrollToNode(hasText(rejectedOffer.priceLabel))
        composeRule.onNodeWithText(rejectedOffer.priceLabel).assertIsDisplayed()
        searchList.performScrollToNode(hasText(rejectedOffer.reason))
        composeRule.onNodeWithText(rejectedOffer.reason).assertIsDisplayed()
        searchList.performScrollToNode(hasText(rejectedOffer.provider))
        composeRule.onNodeWithText(rejectedOffer.provider).assertIsDisplayed()

        searchList.performScrollToNode(hasText("Ocultar descartados"))
        composeRule.onNodeWithText("Ocultar descartados").performClick()
        assertTrue(composeRule.onAllNodesWithText(rejectedOffer.title).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Ver descartados (1)").assertIsDisplayed()
    }


    @Test
    fun adidasSuperstarSearchKeepsSizesAndEditionsUnderOneFamilyWithoutCollapsingOffers() {
        val rows = listOf(
            SerpApiShoppingResult(
                title = "adidas Originals Superstar II Mujer Zapatillas talla 39",
                price = 79.99,
                storeName = "Tienda Adidas",
                sourceUrl = "https://shop.example.test/superstar?size=39",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "adidas Originals Superstar II Mujer Zapatillas talla 39",
                price = 79.99,
                storeName = "Tienda Adidas",
                sourceUrl = "https://shop.example.test/superstar?size=39&utm_source=shopping",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "adidas Originals Superstar II Mujer Zapatillas talla 40",
                price = 79.99,
                storeName = "Tienda Adidas",
                sourceUrl = "https://shop.example.test/superstar?size=40",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "adidas Superstar ADV Hombre Zapatillas talla 42",
                price = 84.99,
                storeName = "Otra tienda",
                sourceUrl = "https://shop.example.test/superstar-adv?size=42",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Zapatillas adidas Originals Superstar II Niños Pokemon talla 33",
                price = 69.99,
                storeName = "Tienda Infantil",
                sourceUrl = "https://shop.example.test/superstar-kids?size=33",
                observedAt = 123L
            )
        )
        val groups = buildOnlineSearchDisplayGroups(
            results = rows,
            query = "Adidas Superstar",
            showSecondHand = false,
            amazonOnly = false
        )

        assertEquals("Exact editions remain distinct groups", 3, groups.size)
        assertEquals("Superstar siblings share one family header", 1, groups.map { it.familyKey }.distinct().size)
        assertTrue(groups.all { it.familyLabel == "Adidas Superstar" })
        assertEquals("Talla 39 · Mujer", com.example.data.remote.onlineOfferSizeAudienceLabel(rows[0].title))
        assertEquals("Talla 40 · Mujer", com.example.data.remote.onlineOfferSizeAudienceLabel(rows[2].title))
        assertEquals("Talla 33 · Infantil", com.example.data.remote.onlineOfferSizeAudienceLabel(rows[4].title))

        val standardIdentity = com.example.data.remote.onlineProductIdentityKey(rows[0].title)
        val standard = groups.single { it.group.identityKey == standardIdentity }
        assertEquals("Same-size duplicates collapse but a second size stays visible", 2, standard.group.offers.size)
    }

    @Test
    fun generalSearchRanksTheExactModelBeforeACheaperSiblingModel() {
        val query = "Samsung Galaxy S26 Ultra 512 GB"
        val rows = listOf(
            SerpApiShoppingResult(
                title = "Samsung Galaxy S26 Plus 512 GB",
                price = 799.0,
                storeName = "Tienda barata",
                sourceUrl = "https://shop.example.test/s26-plus",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Samsung Galaxy S26 Ultra 512 GB",
                price = 999.0,
                storeName = "Tienda exacta",
                sourceUrl = "https://shop.example.test/s26-ultra",
                observedAt = 123L
            )
        )

        val groups = buildOnlineSearchDisplayGroups(
            results = rows,
            query = query,
            showSecondHand = false,
            amazonOnly = false
        )
        assertEquals(
            listOf("Samsung Galaxy S26 Ultra 512 GB", "Samsung Galaxy S26 Plus 512 GB"),
            groups.map { it.group.title }
        )
    }

    @Test
    fun redmiNoteStorageAndEditionVariantsStaySeparateButShareOneFamily() {
        val rows = listOf(
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 17 6GB 256GB",
                price = 222.0,
                storeName = "Tienda estándar",
                sourceUrl = "https://shop.example.test/redmi-note-17",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 17 Pro Max 5G 8GB/256GB",
                price = 449.0,
                storeName = "Tienda Pro Max",
                sourceUrl = "https://shop.example.test/redmi-note-17-pro-max",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 17 5G 8GB/256GB",
                price = 299.0,
                storeName = "Tienda 5G",
                sourceUrl = "https://shop.example.test/redmi-note-17-5g",
                observedAt = 123L
            )
        )

        val groups = buildOnlineSearchDisplayGroups(
            results = rows,
            query = "Xiaomi Redmi Note 17",
            showSecondHand = false,
            amazonOnly = false
        )

        assertEquals("Every purchasable edition/storage variant remains its own price card", 3, groups.size)
        assertEquals("Variants of the same model must be adjacent in the results", 1, groups.map { it.familyKey }.distinct().size)
        assertEquals("Xiaomi Redmi Note 17", groups.first().familyLabel)
        assertTrue(groups.all { it.familyLabel == "Xiaomi Redmi Note 17" })
        assertEquals(
            "Different edition and storage variants must not be merged into a single price identity",
            3,
            groups.map { it.group.identityKey }.distinct().size
        )
    }

    @Test
    fun sameSellerSamePriceDuplicatesCollapseButDifferentPhoneColoursStaySeparate() {
        val rows = listOf(
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 15 Pro 8GB/256GB Negro",
                price = 319.90,
                storeName = "El Corte Inglés",
                sourceUrl = "https://shop.example.test/redmi-note-15-pro-black",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 15 Pro 8GB/256GB Negro",
                price = 319.90,
                storeName = "El Corte Inglés",
                sourceUrl = "https://shop.example.test/redmi-note-15-pro-black?utm_source=shopping",
                observedAt = 123L
            ),
            SerpApiShoppingResult(
                title = "Xiaomi Redmi Note 15 Pro 8GB/256GB Titanio",
                price = 319.90,
                storeName = "El Corte Inglés",
                sourceUrl = "https://shop.example.test/redmi-note-15-pro-titanium",
                observedAt = 123L
            )
        )

        val groups = buildOnlineSearchDisplayGroups(
            results = rows,
            query = "Xiaomi Redmi Note 15 Pro 8GB 256GB",
            showSecondHand = false,
            amazonOnly = false
        )

        assertEquals("Black and titanium are separate product variants", 2, groups.size)
        assertEquals(
            "The repeated URL/listing for the same seller, price and colour is one offer",
            listOf(1, 1),
            groups.map { it.group.offers.size }.sorted()
        )
        assertEquals(2, groups.map { it.group.identityKey }.distinct().size)
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
            if (text.contains("accesorios", ignoreCase = true)) {
                searchList.performScrollToNode(hasTestTag("filter-second-hand-row"))
                composeRule.onNodeWithText(text).performScrollTo().performClick()
            } else {
                searchList.performScrollToNode(hasText(text))
                composeRule.onNodeWithText(text).performClick()
            }
        }

        // OFF + Todos: new shops and Amazon remain; Wallapop and Warehouse are hidden.
        assertVisibleText("Todas las ofertas")
        assertVisibleText("Solo Amazon")
        assertEquals(15, results.size)
        assertTrue(results.any { it.storeName == "Wallapop" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Amazon Warehouse" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Milanuncios" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "eBay" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Vinted" && it.isSecondHand })
        assertTrue(results.any { it.storeName == "Vendedor particular" && it.isSecondHand })
        assertTrue(results.any { it.condition == "refurbished" && it.isSecondHand })

        // OFF + Solo Amazon.
        clickFilter("Solo Amazon")
        assertVisibleText("Todas las ofertas")
        assertVisibleText("Solo Amazon")
        assertTrue(
            composeRule.onAllNodesWithText("7 tiendas · precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Solo Amazon: Amazon Warehouse is appended as a separate labelled card.
        searchList.performScrollToNode(hasTestTag("filter-second-hand-row"))
        composeRule.onNodeWithTag("filter-second-hand").performScrollTo().performClick()
        composeRule.onNodeWithTag("filter-second-hand").assertIsSelected()
        assertVisibleText("Todas las ofertas")
        assertVisibleText("Solo Amazon")
        assertVisibleText("1 tiendas · precio más bajo en Amazon Warehouse")
        assertTrue(
            composeRule.onAllNodesWithText("7 tiendas · precio más bajo en Wallapop")
                .fetchSemanticsNodes().isEmpty()
        )

        // ON + Todos: Wallapop is added after the new-shop card, not instead of it.
        clickFilter("Todas las ofertas")
        assertVisibleText("Todas las ofertas")
        assertVisibleText("Solo Amazon")
        assertVisibleText("3 tiendas · precio más bajo en Amazon.es")
        assertVisibleText("7 tiendas · precio más bajo en Wallapop")
        assertEquals(5, results.count { it.isAccessoryOffer })
        assertTrue(
            "Accessories are hidden from the main product list by default",
            composeRule.onAllNodesWithText("Póster Samsung Galaxy S26 Ultra 512 GB")
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Original replacement screens must not appear as phones by default",
            composeRule.onAllNodesWithText("Original Screen for Samsung Galaxy S26 Ultra 512 GB")
                .fetchSemanticsNodes().isEmpty()
        )
        clickFilter("Mostrar accesorios (5)")
        assertVisibleText("Póster Samsung Galaxy S26 Ultra 512 GB")
        assertVisibleText("Original Screen for Samsung Galaxy S26 Ultra 512 GB")
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
                {"title":"Protector Samsung Galaxy S26 Ultra 512 GB","price":"9,99 €","extracted_price":9.99,"product_link":"https://protectores.example/s26","source":"Tienda de protectores","second_hand_condition":"new"},
                {"title":"Original Screen for Samsung Galaxy S26 Ultra 512 GB","price":"159,99 €","extracted_price":159.99,"product_link":"https://screens.example/samsung-s26-ultra","source":"Tienda de recambios","second_hand_condition":"new"}
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
    fun livePricedOffersWithoutSellerLinksRemainVisibleAcrossProductCategories() {
        val client = SerpApiClient()
        val cases = listOf(
            "Apple iPhone 16" to "574,99 €",
            "Sony WH-1000XM5 Wireless Headphones" to "199,99 €",
            "Alternador VW Polo 9N 1.9 SDI ASY" to "89,90 €",
            "Pantalla Original Xiaomi Mi 10 Pro Versión S" to "42,50 €",
            "Funda para Apple iPhone 16" to "19,99 €",
            "Samsung OLED TV 55 inch" to "799,00 €",
            "Lenovo ThinkPad T14 Gen 5 32GB RAM" to "1.099,00 €",
            "PlayStation 5 Slim Digital Edition" to "399,99 €",
            "Samsung 990 PRO 2TB NVMe SSD" to "169,99 €",
            "Logitech G923 Racing Wheel" to "279,99 €",
            "Batería externa INIU 20000mAh 65W" to "49,99 €",
            "Faro delantero izquierdo Volkswagen Polo 9N" to "39,99 €",
            "Lavavajillas Bosch Serie 6 SMS6" to "699,00 €",
            "Cámara Sony Alpha A6400" to "749,00 €"
        )

        cases.forEachIndexed { index, (title, priceText) ->
            val payload = JSONObject().put(
                "search_metadata",
                JSONObject().put("status", "Success")
            ).put(
                "shopping_results",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("title", title)
                        .put("price", priceText)
                        .put("extracted_price", priceText.replace(".", "").replace(",", ".")
                            .replace(Regex("[^0-9.]"), "").toDouble())
                        .put("source", "Google Shopping")
                        // Intentionally no seller URL: a valid price must remain visible via
                        // an honestly labelled query fallback in every product category.
                )
            )

            val report = client.parseShoppingResultsDetailed(
                root = payload,
                query = title,
                observedAt = System.currentTimeMillis() + index
            )

            assertEquals(
                "A valid priced offer must not be discarded only because its seller URL is absent: $title; " +
                    "discarded=${report.discardedOffers.map { it.reason }}",
                1,
                report.results.size
            )
            val offer = report.results.single()
            assertEquals(title, offer.title)
            assertTrue(offer.isSearchFallbackDestination)
            assertTrue(offer.sourceUrl.startsWith("https://www.google.com/search?"))
            assertTrue(offer.sourceUrl.contains("tbm=shop"))
        }
    }

    @Test
    fun liveIphone16SearchKeepsPricedVariantsAndAccessoriesButExplainsMonthlyDiscard() {
        val now = System.currentTimeMillis()
        val response = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {"title":"Apple iPhone 16","price":"574,99 €","extracted_price":574.99,"source":"Google Shopping"},
                {"title":"Apple iPhone 16 Pro 256 GB","price":"899,00 €","extracted_price":899.0,"link":"http://seller-unknown.example/iphone-16-pro","source":"Google Shopping"},
                {"title":"Apple iPhone 16 Plus","price":"749,00 €","extracted_price":749.0,"source":"Google Shopping"},
                {"title":"Apple iPhone 16 Pro Max 512 GB","price":"999,00 €","extracted_price":999.0,"source":"Google Shopping"},
                {"title":"Funda para Apple iPhone 16","price":"19,99 €","extracted_price":19.99,"source":"Google Shopping"},
                {"title":"Apple iPhone 16 Pro 256 GB","price":"44,99 € al mes","extracted_price":44.99,"link":"https://shop.example/monthly-iphone","source":"Google Shopping"}
              ]
            }
            """.trimIndent()
        )

        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = response,
            query = "iphone 16",
            observedAt = now
        )
        val acceptedTitles = report.results.map { it.title }

        assertEquals("The live parser should explain and reject only the monthly installment", 6, report.rawResultCount)
        assertEquals(5, report.results.size)
        assertEquals(0, report.rejectedForLink)
        assertTrue(acceptedTitles.contains("Apple iPhone 16"))
        assertTrue(acceptedTitles.contains("Apple iPhone 16 Pro 256 GB"))
        assertTrue(acceptedTitles.contains("Apple iPhone 16 Plus"))
        assertTrue(acceptedTitles.contains("Apple iPhone 16 Pro Max 512 GB"))
        assertTrue(acceptedTitles.contains("Funda para Apple iPhone 16"))
        assertTrue(report.results.all { it.isSearchFallbackDestination })
        assertTrue(report.results.single { it.isAccessoryOffer }.title.startsWith("Funda"))
        assertEquals(1, report.discardedOffers.size)
        assertTrue(report.discardedOffers.single().reason.contains("cuota mensual", ignoreCase = true))
        assertTrue(report.discardedOffers.single().priceLabel.contains("al mes", ignoreCase = true))
    }

    @Test
    fun monthlyInstallmentMetadataIsRejectedButASeparateClearTotalPriceIsPreserved() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Apple iPhone 16 Pro 256 GB",
                      "price": "899,00 €",
                      "extracted_price": 899.0,
                      "monthly_payment_amount": 44.99,
                      "monthly_payment_duration": 24,
                      "source": "Google Shopping"
                    },
                    {
                      "title": "Apple iPhone 16 Pro 256 GB",
                      "price": "44,99 €",
                      "extracted_price": 44.99,
                      "monthly_payment_amount": 44.99,
                      "monthly_payment_duration": 24,
                      "source": "Google Shopping"
                    },
                    {
                      "title": "Apple iPhone 16 Pro 256 GB Negro",
                      "price": "899,00 €",
                      "extracted_price": 899.0,
                      "payment_description": "Visa y Mastercard",
                      "source": "Google Shopping"
                    },
                    {
                      "title": "Apple iPhone 16 Pro 256 GB Azul",
                      "price": "49,99",
                      "extracted_price": 49.99,
                      "monthly_payment_duration": 12,
                      "source": "Google Shopping"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Apple iPhone 16 Pro 256 GB",
            observedAt = System.currentTimeMillis()
        )

        assertEquals(4, report.rawResultCount)
        assertEquals("Clear totals and normal card-payment offers remain usable", 2, report.results.size)
        assertTrue(report.results.all { kotlin.math.abs(it.price - 899.0) < 0.001 })
        assertTrue(report.results.any { it.title.endsWith("Negro") })
        assertTrue(report.results.any { it.title == "Apple iPhone 16 Pro 256 GB" })
        assertEquals(2, report.discardedOffers.size)
        assertTrue(report.discardedOffers.all { it.reason == "Alquiler o cuota mensual" })
        assertTrue(report.discardedOffers.any { it.title.contains("Azul") })
        assertTrue(report.discardedOffers.any { it.priceLabel.contains("44,99") })
    }

    @Test
    fun pricedOfferWithoutSafeRetailerLinkRemainsUsableThroughSpecificShoppingSearch() {
        val response = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Xiaomi Mi 10 Pro 5G 8GB/256GB Gris",
                  "price": "180,00 €",
                  "extracted_price": 180.0,
                  "link": "http://seller-unknown.example/xiaomi-mi-10-pro",
                  "source": "Google Shopping",
                  "second_hand_condition": "new"
                }
              ]
            }
            """.trimIndent()
        )

        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = response,
            query = "xiaomi mi 10 pro",
            observedAt = 1_800_000_000_000L
        )

        assertEquals("The valid priced offer should not be lost only due to a missing HTTPS seller link", 1, report.results.size)
        val offer = report.results.single()
        assertEquals("Google Shopping", offer.storeName)
        assertTrue(offer.sourceUrl.startsWith("https://www.google.com/search?"))
        assertTrue(offer.sourceUrl.contains("tbm=shop"))
        assertTrue(offer.sourceUrl.contains("xiaomi", ignoreCase = true))
    }

    @Test
    fun liveOfferWithoutDirectSellerLinkRemainsVisibleAndSaysLinkIsUnavailable() {
        val fallback = SerpApiShoppingResult(
            title = "Apple iPhone 16",
            price = 574.99,
            storeName = "Google Shopping",
            sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=Apple+iPhone+16",
            observedAt = System.currentTimeMillis(),
            condition = "new"
        )

        composeRule.setContent {
            MaterialTheme {
                OnlineSearchScreen(
                    state = TrackerUiState(
                        onlineSearchQuery = "iphone 16",
                        onlineSearchResults = listOf(fallback),
                        onlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING
                    ),
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

        val list = composeRule.onNodeWithTag("online-search-list")
        list.performScrollToNode(hasText("Apple iPhone 16"))
        assertTrue(
            "The iPhone 16 offer must remain visible in live results",
            composeRule.onAllNodesWithText("Apple iPhone 16").fetchSemanticsNodes().isNotEmpty()
        )
        list.performScrollToNode(hasText("Enlace no disponible · buscar en Google Shopping"))
        composeRule.onNodeWithText("Enlace no disponible · buscar en Google Shopping").assertIsDisplayed()
        list.performScrollToNode(hasText("Buscar en Google Shopping"))
        composeRule.onNodeWithText("Buscar en Google Shopping").assertIsDisplayed()
    }

    @Test
    fun replacementScreensAndForeignLanguageProtectorsAreRecognizedAsAccessories() {
        fun offer(title: String) = SerpApiShoppingResult(
            title = title,
            price = 99.0,
            storeName = "Google Shopping",
            sourceUrl = "https://www.google.com/search?tbm=shop&q=test",
            observedAt = 1_800_000_000_000L
        )

        assertFalse(offer("Xiaomi Mi 10 Pro 5G 8GB/256GB Gris").isAccessoryOffer)
        assertTrue(offer("Pantalla Original Xiaomi Mi 10 Pro Versión S").isAccessoryOffer)
        assertTrue(offer("Pantalla Completa con Marco para Google Pixel 10").isAccessoryOffer)
        assertTrue(offer("Original Screen for Xiaomi Mi 10 Pro").isAccessoryOffer)
        assertTrue(offer("Google Pixel 10 Displayschutz & Schutzfolie – Crystal Advanced").isAccessoryOffer)
        assertTrue(offer("Google Pixel 10 Schutzhülle").isAccessoryOffer)
        assertTrue(isAccessorySearchQuery("Pantalla Original Xiaomi Mi 10 Pro"))
        assertTrue(isAccessorySearchQuery("Original Screen for Xiaomi Mi 10 Pro"))
        assertFalse(isAccessorySearchQuery("Xiaomi Mi 10 Pro"))
    }

    @Test
    fun iphone16VariantsStaySeparateAndAccessoriesCanBeShownWithoutHidingPhones() {
        val now = System.currentTimeMillis()
        fun offer(title: String, price: Double) = SerpApiShoppingResult(
            title = title,
            price = price,
            storeName = "Google Shopping",
            sourceUrl = "https://www.google.com/search?tbm=shop&hl=es&gl=es&q=" +
                java.net.URLEncoder.encode(title, "UTF-8"),
            observedAt = now,
            condition = "new"
        )

        val rows = listOf(
            offer("Apple iPhone 16", 574.99),
            offer("Apple iPhone 16 Pro 256 GB", 899.00),
            offer("Apple iPhone 16 Plus", 749.00),
            offer("Apple iPhone 16 Pro Max 512 GB", 999.00),
            offer("Funda para Apple iPhone 16", 19.99),
            offer("Original Screen for Apple iPhone 16", 149.99)
        )
        val phonesOnly = buildOnlineSearchDisplayGroups(
            results = rows,
            query = "iphone 16",
            showSecondHand = false,
            amazonOnly = false,
            showAccessories = false
        )
        val phonesAndAccessories = buildOnlineSearchDisplayGroups(
            results = rows,
            query = "iphone 16",
            showSecondHand = false,
            amazonOnly = false,
            showAccessories = true
        )
        val mainTitles = phonesOnly.flatMap { it.group.offers }.map { it.title }

        assertEquals("Each iPhone 16 model should remain a separate card", 4, phonesOnly.size)
        assertTrue(mainTitles.contains("Apple iPhone 16"))
        assertTrue(mainTitles.contains("Apple iPhone 16 Pro 256 GB"))
        assertTrue(mainTitles.contains("Apple iPhone 16 Plus"))
        assertTrue(mainTitles.contains("Apple iPhone 16 Pro Max 512 GB"))
        assertTrue(mainTitles.none { it.startsWith("Funda") || it.startsWith("Original Screen") })

        val expandedTitles = phonesAndAccessories.flatMap { it.group.offers }.map { it.title }
        assertTrue(expandedTitles.contains("Funda para Apple iPhone 16"))
        assertTrue(expandedTitles.contains("Original Screen for Apple iPhone 16"))
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
        searchList.performScrollToNode(hasText("Actualizar · puede consumir cuota"))
        composeRule.onNodeWithText("Actualizar · puede consumir cuota").assertIsDisplayed()
        composeRule.onNodeWithText("Actualizar · puede consumir cuota").performClick()
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
