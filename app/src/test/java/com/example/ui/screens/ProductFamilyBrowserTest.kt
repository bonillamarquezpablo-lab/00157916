package com.example.ui.screens

import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductFamilyBrowserTest {

    @Test
    fun broadPs5SearchBuildsFatSlimAndProBucketsWithoutMergingStorageOrEditions() {
        val results = listOf(
            offer(
                title = "Sony PlayStation 5 Fat Disc Edition 825 GB",
                price = 499.99,
                store = "GAME España",
                path = "ps5-fat-disc-game"
            ),
            offer(
                title = "Sony PlayStation 5 Fat Disc Edition 825 GB",
                price = 509.99,
                store = "Amazon España",
                path = "ps5-fat-disc-amazon"
            ),
            offer(
                title = "Sony PS5 Digital Edition 825GB",
                price = 449.99,
                store = "GAME España",
                path = "ps5-digital-game"
            ),
            offer(
                title = "Sony PlayStation 5 Slim Disc Edition 1 TB",
                price = 549.99,
                store = "GAME España",
                path = "ps5-slim-1tb"
            ),
            offer(
                title = "Sony PlayStation 5 Pro 2 TB",
                price = 699.99,
                store = "GAME España",
                path = "ps5-pro-2tb"
            )
        )

        val exactProductGroups = groupOnlineShoppingResults(results)
        val buckets = buildConsoleModelBuckets("PS5", exactProductGroups)

        assertEquals(
            listOf(
                "PlayStation 5 estándar (original / Fat)",
                "PlayStation 5 Slim",
                "PlayStation 5 Pro"
            ),
            buckets.map { it.label }
        )
        val standard = buckets.first { it.key == "ps5-original" }
        assertEquals("Different edition and storage identities stay selectable", 2, standard.groups.size)
        assertEquals("Two sellers for the same exact product stay together", 2,
            standard.groups.first { it.offers.size == 2 }.offers.size)
        assertEquals(2, standard.storeCount)
        assertEquals(449.99, standard.cheapestPrice, 0.001)
        assertEquals(1, buckets.first { it.key == "ps5-slim" }.groups.size)
        assertEquals(1, buckets.first { it.key == "ps5-pro" }.groups.size)
    }

    @Test
    fun broadPs5SearchKeepsKnownModelsSelectableWithoutInventingMissingPrices() {
        val onlyStandard = listOf(
            offer(
                title = "Sony PlayStation 5 Fat Disc Edition 825 GB",
                price = 499.99,
                store = "GAME España",
                path = "ps5-fat-only"
            )
        )
        val buckets = buildConsoleModelBuckets("PS5", groupOnlineShoppingResults(onlyStandard))
        val pro = buckets.first { it.key == "ps5-pro" }
        val slim = buckets.first { it.key == "ps5-slim" }

        assertEquals(3, buckets.size)
        assertTrue(pro.groups.isEmpty())
        assertTrue(slim.groups.isEmpty())
        assertTrue(pro.cheapestPrice.isInfinite())
        assertEquals(0, pro.storeCount)
        assertEquals("PlayStation 5 Pro", pro.label)
        assertEquals("PlayStation 5 Pro", pro.searchQuery)
    }

    @Test
    fun broadConsoleSearchDoesNotOverrideSpecificVariantOrAccessorySearches() {
        assertTrue(isConsoleFamilyOverviewQuery("PS5"))
        assertTrue(isConsoleFamilyOverviewQuery("PlayStation 5"))
        assertFalse(isConsoleFamilyOverviewQuery("PS5 Slim"))
        assertFalse(isConsoleFamilyOverviewQuery("PS5 1 TB"))
        assertFalse(isConsoleFamilyOverviewQuery("PS5 controller"))
        assertFalse(isConsoleFamilyOverviewQuery("Nintendo Switch Zelda game"))
    }

    private fun offer(title: String, price: Double, store: String, path: String) =
        SerpApiShoppingResult(
            title = title,
            price = price,
            storeName = store,
            sourceUrl = "https://www.example.test/$path",
            observedAt = 1_800_000_000_000L,
            condition = "new"
        )
}
