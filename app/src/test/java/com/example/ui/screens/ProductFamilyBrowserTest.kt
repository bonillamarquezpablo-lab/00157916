package com.example.ui.screens

import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.onlineProductIdentityKey
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
    fun phoneColourAndCapacityRemainDistinctProductIdentities() {
        val yellow = onlineProductIdentityKey("Apple iPhone 15 256 GB Amarillo")
        val translatedYellow = onlineProductIdentityKey("Apple iPhone 15 256GB Yellow")
        val black = onlineProductIdentityKey("Apple iPhone 15 256GB Negro")
        val plus = onlineProductIdentityKey("Apple iPhone 15 Plus 256GB Amarillo")

        assertEquals("Colour aliases should normalize to one product identity", yellow, translatedYellow)
        assertTrue("Different colours are separate purchasable variants", yellow != black)
        assertTrue("Base and Plus editions are separate variants", yellow != plus)
    }

    @Test
    fun redmiNoteModelIdentityUnifiesFamilyButSeparatesEditionStorageAndNetwork() {
        val base = onlineProductIdentityKey("Xiaomi Redmi Note 17 6GB/256GB")
        val proMax5g = onlineProductIdentityKey("XIAOMI Redmi Note 17 Pro Max 5G 8GB + 256GB")
        val note10 = onlineProductIdentityKey("Xiaomi Redmi Note 10 Pro 5G 8GB/256GB")
        val pro4g = onlineProductIdentityKey("Xiaomi Redmi Note 15 Pro 4G 12GB + 512GB")
        val pro5g = onlineProductIdentityKey("Xiaomi Redmi Note 15 Pro 5G 8GB/256GB")
        val compactPro = onlineProductIdentityKey("Xiaomi Redmi Note 15Pro 8GB/256GB")
        val compactProMax = onlineProductIdentityKey("XIAOMI Redmi Note 15Pro Max 12GB/512GB")

        fun modelFamily(key: String) = key.split('|').take(2)

        assertEquals(
            "Base, Pro and Pro Max configurations of Redmi Note 17 must share the same family identifier",
            modelFamily(base),
            modelFamily(proMax5g)
        )
        assertTrue(
            "Redmi Note 10 and Redmi Note 17 must remain distinct models",
            modelFamily(note10) != modelFamily(proMax5g)
        )
        assertEquals(
            "A compact retailer title such as Redmi Note 15Pro must share the same family as the spaced edition",
            modelFamily(pro4g),
            modelFamily(compactPro)
        )
        assertEquals(
            "Compact Pro Max names must also share the model family",
            modelFamily(pro4g),
            modelFamily(compactProMax)
        )
        assertTrue("Pro and Pro Max remain separate exact variants", compactPro != compactProMax)
        assertTrue(
            "The 4G and 5G editions must not collapse into one exact product identity",
            pro4g != pro5g
        )
        assertTrue(
            "Different RAM/storage configurations remain distinct purchasable identities",
            base != proMax5g
        )

        val exactGroups = groupOnlineShoppingResults(
            listOf(
                offer("Xiaomi Redmi Note 15 Pro 4G 12GB + 512GB", 309.99, "Amazon España", "redmi15-pro-4g"),
                offer("Xiaomi Redmi Note 15 Pro 5G 8GB/256GB", 319.90, "El Corte Inglés", "redmi15-pro-5g")
            )
        )
        assertEquals("4G and 5G listings remain two exact product cards under the same family", 2, exactGroups.size)
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
