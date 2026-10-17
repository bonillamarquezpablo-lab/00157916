package com.example.data.remote

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
            storeName = "GAME",
            sourceUrl = "https://game.es.example.com/producto"
        )

        assertFalse(isGameSpainOffer(amazon))
        assertFalse(isGameSpainOffer(lookalike))
    }
}
