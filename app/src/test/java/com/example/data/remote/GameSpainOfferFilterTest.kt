package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSpainOfferFilterTest {

    @Test
    fun recognizesGameByStoreNameEvenWhenShoppingProvidesAGoogleLink() {
        val offer = SerpApiShoppingResult(
            title = "Nintendo Switch game",
            price = 49.99,
            storeName = "GAME",
            sourceUrl = "https://www.google.com/shopping/product/123456789",
            observedAt = System.currentTimeMillis()
        )

        assertTrue(isGameSpainOffer(offer))
    }

    @Test
    fun recognizesGameByVerifiedRetailerDomain() {
        val offer = SerpApiShoppingResult(
            title = "Nintendo Switch game",
            price = 49.99,
            storeName = "Tienda online",
            sourceUrl = "https://www.game.es/nintendo-switch-neon-nintendo-switch-v1gc09",
            observedAt = System.currentTimeMillis()
        )

        assertTrue(isGameSpainOffer(offer))
    }

    @Test
    fun doesNotTreatOtherSellersOrLookalikeDomainsAsGame() {
        val amazon = SerpApiShoppingResult(
            title = "Nintendo Switch game",
            price = 49.99,
            storeName = "Amazon.es",
            sourceUrl = "https://www.amazon.es/dp/example",
            observedAt = System.currentTimeMillis()
        )
        val lookalike = amazon.copy(
            storeName = "Tienda online",
            sourceUrl = "https://game.es.example.com/producto"
        )

        assertFalse(isGameSpainOffer(amazon))
        assertFalse(isGameSpainOffer(lookalike))
    }
    @Test
    fun onlyGamingRelatedSearchesUseAutomaticGameSupplementWhenGeneralResultsHaveNoGame() {
        val amazon = SerpApiShoppingResult(
            title = "Sony PlayStation 5 Slim 1 TB",
            price = 449.99,
            storeName = "Amazon España",
            sourceUrl = "https://www.google.com/shopping/product/amazon-ps5",
            observedAt = 1_800_000_000_000L
        )
        val game = SerpApiShoppingResult(
            title = "Sony PlayStation 5 Slim 1 TB",
            price = 459.99,
            storeName = "GAME España",
            sourceUrl = "https://www.google.com/shopping/product/game-ps5",
            observedAt = 1_800_000_000_000L
        )

        assertTrue(shouldSearchGameSupplement("PS5 Slim 1 TB", listOf(amazon)))
        assertFalse(shouldSearchGameSupplement("PS5 Slim", listOf(game)))
        assertFalse(shouldSearchGameSupplement("iPhone 16 128 GB", listOf(amazon)))
    }

    @Test
    fun unifiedGameSupplementAddsOnlyGameOffersAndDeduplicatesSameRetailerPrice() {
        val amazon = SerpApiShoppingResult(
            title = "Sony PlayStation 5 Slim 1 TB",
            price = 449.99,
            storeName = "Amazon España",
            sourceUrl = "https://www.amazon.es/dp/ps5-slim",
            observedAt = 1_800_000_000_000L
        )
        val game = SerpApiShoppingResult(
            title = "Sony PlayStation 5 Slim 1 TB",
            price = 459.99,
            storeName = "GAME España",
            sourceUrl = "https://www.google.com/shopping/product/game-ps5",
            observedAt = 1_800_000_000_000L
        )
        val duplicatedGame = game.copy(sourceUrl = "https://www.google.com/shopping/product/game-ps5-duplicate")
        val unrelatedSeller = game.copy(
            storeName = "Tienda desconocida",
            sourceUrl = "https://www.google.com/shopping/product/other-seller"
        )

        val merged = mergeGoogleShoppingWithGameSupplement(
            primaryOffers = listOf(amazon, game),
            supplementalOffers = listOf(duplicatedGame, unrelatedSeller)
        )

        assertTrue(merged.any { it.storeName == "Amazon España" })
        assertEquals(1, merged.count(::isGameSpainOffer))
        assertEquals(2, merged.size)
    }
}

