package com.example

import com.example.data.model.DealVerdict
import com.example.data.model.PricePoint
import com.example.data.model.sanitizePriceHistory
import com.example.data.model.summarizePriceHistory
import com.example.data.model.Product
import com.example.data.model.Store
import com.example.data.model.VerdictLevel
import com.example.data.repository.SpanishMarketData
import com.example.ui.components.ChartRange
import com.example.ui.components.filterPricePointsForRange
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.isIrrelevantMultiProductBundle
import com.example.data.remote.onlineProductIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun groupedOnlineSearchCombinesRetailersButKeepsModelsVariantsAndAccessoriesSeparate() {
        fun result(title: String, price: Double, store: String) = SerpApiShoppingResult(
            title = title,
            price = price,
            storeName = store,
            sourceUrl = "https://${store.lowercase().replace(" ", "")}.example/product",
            observedAt = 1_800_000_000_000L
        )

        val groups = groupOnlineShoppingResults(
            listOf(
                result("Smartphone Xiaomi Redmi 17 4GB/256GB Negro", 189.99, "Amazon.es"),
                result("Xiaomi Redmi 17 4G 4+256GB Azul", 179.99, "PcComponentes"),
                result("Xiaomi Redmi A5 4GB/128GB", 99.99, "Tienda A"),
                result("Funda Xiaomi Redmi 17 4GB/256GB con tapa", 7.99, "Tienda B"),
                result("Samsung Galaxy S26 FE 256GB", 699.99, "Tienda C"),
                result("Samsung Galaxy S26 Ultra 256GB", 1199.99, "Tienda D"),
                result("Nike Air Force 1 Gris - 47", 99.99, "Tienda Nike"),
                result("Zapatillas Nike Air Force 1 Gris - 40.5 Hombre", 94.99, "Tienda Sports")
            )
        )

        assertEquals(
            "Model editions, colours and accessories remain distinct; shoe sizes are ignored",
            7,
            groups.size
        )
        val redmi17Colours = groups.filter {
            it.identityKey.startsWith("xiaomi|redmi17|") &&
                it.identityKey.contains("memory:4gb+256gb") &&
                !it.identityKey.contains("accessory:")
        }
        assertEquals("Black and blue are separate SKU cards", 2, redmi17Colours.size)
        assertTrue(redmi17Colours.any { it.identityKey.contains("color:black") })
        assertTrue(redmi17Colours.any { it.identityKey.contains("color:blue") })
        assertTrue(redmi17Colours.all { it.offers.size == 1 })
        assertEquals(179.99, redmi17Colours.minOf { it.minPrice }, 0.001)
        assertTrue(groups.any { it.identityKey.contains("redmia5") })
        assertTrue(groups.any { it.identityKey.contains("accessory:funda") })
        assertTrue(groups.any { it.identityKey.contains("variant:fe") })
        assertTrue(groups.any { it.identityKey.contains("variant:ultra") })
        val airForceOne = groups.single { it.title.contains("Air Force 1", ignoreCase = true) }
        assertEquals("Different shoe sizes and gender words must merge into one product card", 2, airForceOne.offers.size)
        assertEquals(94.99, airForceOne.minPrice, 0.001)
    }

    @Test
    fun compactRedmiNoteEditionTitlesShareFamilyButKeepVariantsAndStorageDistinct() {
        val compact = onlineProductIdentityKey("Xiaomi Redmi Note 15Pro 8GB/256GB")
        val spacedSameVariant = onlineProductIdentityKey("XIAOMI Redmi Note 15 Pro 8GB/256GB")
        val baseSameMemory = onlineProductIdentityKey("Xiaomi Redmi Note 15 8GB/256GB")
        val spaced = onlineProductIdentityKey("XIAOMI Redmi Note 15 Pro 12GB/512GB 5G")
        val max = onlineProductIdentityKey("Xiaomi Redmi Note 15 Pro Max 12GB/512GB")

        fun familyKey(key: String) = key.split('|').filterNot {
            it.startsWith("variant:") || it.startsWith("memory:") ||
                it.startsWith("color:") || it.startsWith("specs:")
        }.joinToString("|")

        assertEquals("Compact and spaced edition names must resolve to the same Redmi Note family",
            familyKey(compact), familyKey(spaced))
        assertEquals("Compact and spaced titles of the same variant/configuration share exact identity",
            compact, spacedSameVariant)
        assertTrue("Compact Pro remains distinct from the base edition", compact != baseSameMemory)
        assertEquals("Pro Max must stay under the same model family",
            familyKey(compact), familyKey(max))
        assertTrue("Pro and Pro Max must remain separate variants",
            compact != max)
        assertTrue("Different storage capacities must remain separate exact identities",
            compact != spaced)
        assertTrue(compact.startsWith("xiaomi|redminote15|"))
        assertTrue(spaced.startsWith("xiaomi|redminote15|"))
    }

    @Test
    fun powerBankCapacityAndChargerWattageStayDistinctWhileColourVariantsMerge() {
        fun result(title: String, price: Double, store: String) = SerpApiShoppingResult(
            title = title,
            price = price,
            storeName = store,
            sourceUrl = "https://${store.lowercase().replace(" ", "")}.example/product",
            observedAt = 1_800_000_000_000L
        )

        val groups = groupOnlineShoppingResults(
            listOf(
                result("Anker Power Bank 20000mAh 65W Black", 59.99, "Tienda A"),
                result("Anker Power Bank 20.000 mAh 65 W Negro", 54.99, "Tienda B"),
                result("Anker Power Bank 20000mAh 100W", 79.99, "Tienda C"),
                result("Anker Power Bank 10000mAh 65W", 39.99, "Tienda D")
            )
        )

        assertEquals("Capacity and wattage change the product variant, colour does not", 3, groups.size)
        val twentyK65W = groups.single { it.identityKey.contains("20000mah") && it.identityKey.contains("65w") }
        assertEquals(2, twentyK65W.offers.size)
        assertEquals(54.99, twentyK65W.minPrice, 0.001)
        assertTrue(groups.any { it.identityKey.contains("100w") })
        assertTrue(groups.any { it.identityKey.contains("10000mah") })
    }

    @Test
    fun sonyHeadphoneModelFormattingDoesNotCreateDuplicateCards() {
        val formatted = onlineProductIdentityKey("Sony WH-1000XM5 Wireless Headphones")
        val compact = onlineProductIdentityKey("Sony WH1000XM5 Wireless Noise Canceling Headphones")
        val withoutPrefix = onlineProductIdentityKey("Sony 1000XM5 Wireless Headphones")

        assertEquals(formatted, compact)
        assertEquals(formatted, withoutPrefix)
    }

    @Test
    fun chartRangesCoverThirtyDaysThroughFourYearsAndAllTime() {
        assertEquals(
            listOf("30D", "90D", "6M", "1A", "2A", "3A", "4A", "Todo"),
            ChartRange.values().map { it.label }
        )
        assertEquals(
            listOf(30, 90, 183, 365, 730, 1095, 1460, null),
            ChartRange.values().map { it.days }
        )
    }

    @Test
    fun oneQuoteCannotBePresentedAsAnObservedHistoricalMinimum() {
        val summary = summarizePriceHistory(
            listOf(
                PricePoint(
                    timestamp = 1_797_000_000_000L,
                    dateLabel = "13 oct 2026",
                    price = 41.63,
                    storeName = "Tienda consultada"
                )
            )
        )

        assertEquals(1, summary.observationCount)
        assertNull("One record is not a comparable historical minimum", summary.minimumObservedPoint)
    }

    @Test
    fun minimumIsDerivedFromPointsShownAndKeepsExactCents() {
        val summary = summarizePriceHistory(
            listOf(
                PricePoint(1_796_900_000_000L, "12 oct 2026", 42.49, "Tienda A"),
                PricePoint(1_797_000_000_000L, "13 oct 2026", 41.63, "Tienda B"),
                PricePoint(1_797_000_000_000L, "13 oct 2026", 43.10, "Tienda C")
            )
        )

        assertEquals(2, summary.observationCount)
        assertEquals(1_796_900_000_000L, summary.firstObservedAt)
        assertEquals(41.63, summary.minimumObservedPoint?.price ?: -1.0, 0.001)
    }

    @Test
    fun duplicateTimestampOrInvalidPricesCannotFabricateASecondObservation() {
        val points = listOf(
            PricePoint(1_797_000_000_000L, "13 oct 2026", 41.63, "Tienda A"),
            PricePoint(1_797_000_000_000L, "13 oct 2026", 40.00, "Tienda B"),
            PricePoint(1_797_000_000_001L, "13 oct 2026", Double.NaN, "Tienda C"),
            PricePoint(0L, "sin fecha", 1.0, "Tienda D")
        )

        val sanitized = sanitizePriceHistory(points)
        val summary = summarizePriceHistory(points)
        assertEquals("Only one dated snapshot should remain", 1, sanitized.size)
        assertEquals(1, summary.observationCount)
        assertNull(summary.minimumObservedPoint)
        assertEquals(40.00, sanitized.single().price, 0.001)
    }

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun bundledProductsAreNeverPresentedAsVerifiedPrices() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue(products.isNotEmpty())
        products.forEach { product ->
            assertFalse("Bundled sample must not be marked verified: ${product.title}", product.isPriceVerified)
            assertFalse("Bundled PVP must remain unverified: ${product.title}", product.isOfficialPvpVerified)
            assertFalse("Bundled ratings must remain unverified: ${product.title}", product.isRatingVerified)
            assertFalse("Bundled EAN must remain unverified: ${product.title}", product.isEanVerified)
            assertFalse("Bundled sample must not claim an all-time low: ${product.title}", product.isAtAth)
            assertEquals(VerdictLevel.UNVERIFIED, product.verdict.level)
            assertEquals(0, product.verdict.confidenceScore)
            assertTrue("Example catalogue entries must not include generated history points: ${product.title}", product.priceHistory.isEmpty())
        }
    }

    @Test
    fun unverifiedReferencePvpCannotCreateApparentSavings() {
        val seed = SpanishMarketData.getInitialProducts().first()
        val now = System.currentTimeMillis()
        val unverified = seed.copy(
            currentPrice = 100.0,
            officialPvp = 300.0,
            isPriceVerified = true,
            isOfficialPvpVerified = false,
            priceObservedAt = now
        )

        assertEquals(0.0, unverified.realSavingsAmount, 0.001)
        assertEquals(0, unverified.realSavingsPercent)

        val independentlyVerified = unverified.copy(isOfficialPvpVerified = true)
        assertEquals(200.0, independentlyVerified.realSavingsAmount, 0.001)
        assertEquals(66, independentlyVerified.realSavingsPercent)
    }

    @Test
    fun archiveAndGoogleInsightPointsCannotSubstituteForLivePriceHistory() {
        val seed = SpanishMarketData.getInitialProducts().first()
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        val product = seed.copy(
            isPriceVerified = true,
            priceObservedAt = now,
            currentPrice = 100.0,
            priceHistory = listOf(
                PricePoint(now - 100L * day, "hace 100 días", 200.0, "Tienda", evidenceType = "live"),
                PricePoint(now - 50L * day, "hace 50 días", 1.0, "Internet Archive",
                    sourceUrl = "https://web.archive.org/web/20260701000000/https://tienda.example/product",
                    evidenceType = "archive"),
                PricePoint(now - 20L * day, "hace 20 días", 2.0, "Internet Archive",
                    sourceUrl = "https://web.archive.org/web/20260920000000/https://tienda.example/product",
                    evidenceType = "archive"),
                PricePoint(now, "hoy", 100.0, "Tienda", evidenceType = "live")
            )
        )

        assertFalse("Archive captures must never satisfy the three-point live-history threshold", product.hasSufficientHistory)
    }

    @Test
    fun testDealVerdictAthRecord() {
        val verdict = DealVerdict.analyze(
            currentPrice = 279.0,
            athMinPrice = 279.0,
            avg90dPrice = 345.0,
            athMaxPrice = 419.0
        )
        assertEquals(VerdictLevel.ATH_RECORD, verdict.level)
        assertTrue(verdict.badgeText.contains("MÍNIMO OBSERVADO"))
    }

    @Test
    fun livePriceNeedsNinetyDayHistoryBeforeHistoricalClaims() {
        val seed = SpanishMarketData.getInitialProducts().first()
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        fun point(daysAgo: Long, price: Double) = PricePoint(
            timestamp = now - daysAgo * day,
            dateLabel = "$daysAgo días",
            price = price,
            storeName = "Comercio consultado",
            eventLabel = "Consulta de prueba"
        )

        val oneSnapshot: Product = seed.copy(
            currentPrice = 199.0,
            athMinPrice = 199.0,
            officialPvp = 0.0,
            priceObservedAt = now,
            isPriceVerified = true,
            priceHistory = listOf(point(0, 199.0))
        )
        assertFalse("One snapshot must not count as historical evidence", oneSnapshot.hasSufficientHistory)
        assertFalse("One snapshot must not be labelled a historic low", oneSnapshot.isAtAth)
        assertEquals(VerdictLevel.UNVERIFIED, oneSnapshot.verdict.level)

        val enoughHistory = oneSnapshot.copy(
            currentPrice = 189.0,
            priceObservedAt = now,
            priceHistory = listOf(
                point(92, 229.0),
                point(60, 209.0),
                point(30, 199.0),
                point(0, 189.0)
            )
        )
        assertTrue("History requires coverage across 90 days plus recent samples", enoughHistory.hasSufficientHistory)

        assertTrue("A just-observed price should be fresh", oneSnapshot.isPriceFresh)
        assertEquals(
            com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT,
            oneSnapshot.priceDisplayStatus
        )
        val stale = oneSnapshot.copy(priceObservedAt = now - 2L * day)
        assertFalse("A price older than 24 hours must not be considered current", stale.isPriceFresh)
        assertEquals(
            "A real saved quote must remain visible but be marked for revalidation",
            com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION,
            stale.priceDisplayStatus
        )
        assertEquals(
            "A bundled sample price must remain hidden when it has no verified source",
            com.example.data.model.PriceDisplayStatus.DEMONSTRATION,
            seed.priceDisplayStatus
        )
        assertTrue("Stale prices need an explicit age label", stale.priceFreshnessLabel.contains("2 días"))
        val staleWithHistory = enoughHistory.copy(priceObservedAt = now - 2L * day)
        assertEquals(
            "Old current quotes must not carry an active deal verdict even with enough historical points",
            DealVerdict.insufficientHistory().level,
            staleWithHistory.verdict.level
        )
        assertFalse("Stale current prices must not receive a current historic-low badge", staleWithHistory.isAtAth)
        assertEquals(
            "Savings cannot be presented as current when the quote is stale",
            0,
            staleWithHistory.realSavingsPercent
        )
    }

    @Test
    fun testDealVerdictInflatedFakeDeal() {
        // EJEMPLO sintético del algoritmo de clasificación; no representa ni persiste precios reales.
        val verdict = DealVerdict.analyze(
            currentPrice = 429.0,
            athMinPrice = 329.0,
            avg90dPrice = 369.0,
            athMaxPrice = 649.0,
            wasRecentlyInflated = true,
            fakePriorPrice = 649.0
        )
        assertEquals(VerdictLevel.INFLATED_FAKE_DEAL, verdict.level)
        assertTrue(verdict.badgeText.contains("OJO"))
    }

    @Test
    fun expandedCatalogueHasAtLeastSixtyUniqueUnverifiedFixtures() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue("The discovery catalogue should contain 60+ products", products.size >= 60)
        assertEquals("Product IDs must be unique", products.size, products.map { it.id }.toSet().size)
        assertTrue("Seeded items must remain unverified", products.none { it.isPriceVerified })
    }

    @Test
    fun storeDirectoryContainsThirtyPlusOfficialWebsitesWithoutFalsePriceIntegration() {
        assertTrue("At least 30 stores should be listed", Store.ALL_STORES.size >= 30)
        assertEquals("Store IDs must be unique", Store.ALL_STORES.size, Store.ALL_STORES.map { it.id }.toSet().size)
        Store.ALL_STORES.forEach { store ->
            assertTrue("Missing official HTTPS URL for ${store.name}", store.officialUrl.startsWith("https://"))
            assertFalse("No live price connector configured for ${store.name}", store.hasLivePriceIntegration)
            val searchUrl = store.searchUrlFor("test")
            assertTrue("Search URL must use HTTPS for ${store.name}", searchUrl.startsWith("https://"))
            assertFalse(
                "Retailer search must not route through a Google site: query for ${store.name}",
                searchUrl.contains("site%3A", ignoreCase = true) ||
                    searchUrl.contains("google.com/search?q=", ignoreCase = true)
            )
            if (store.supportsProductSearch) {
                val expectedHost = store.domain.substringBefore("/").removePrefix("www.")
                assertTrue(
                    "Known search for ${store.name} should stay on its first-party domain",
                    searchUrl.contains(expectedHost, ignoreCase = true)
                )
            } else {
                assertEquals(
                    "Stores without a verified search URL should fall back to their official homepage",
                    store.officialUrl,
                    searchUrl
                )
            }
        }
    }

    @Test
    fun testSpanishMarketCatalogIntegrity() {
        val products = SpanishMarketData.getInitialProducts()
        assertTrue("Market products catalog should not be empty", products.isNotEmpty())
        for (prod in products) {
            assertTrue("Price should be positive for ${prod.title}", prod.currentPrice > 0)
            assertTrue("ATH price should be <= currentPrice for ${prod.title}", prod.athMinPrice <= prod.currentPrice + 0.1)
        }
    }

    @Test
    fun aliExpressSellerAliasesResolveToOneCanonicalMarketplace() {
        val aliases = listOf(
            "AliExpress - AliExpress-239640258",
            "AliExpress - AliExpress-249437169",
            "retailer_aliexpress_239640258"
        )

        aliases.forEach { alias ->
            assertEquals(
                "All AliExpress marketplace seller aliases should use one canonical store",
                Store.ALIEXPRESS_PLAZA.id,
                Store.findByName(alias).id
            )
        }
        assertEquals(
            Store.ALIEXPRESS_PLAZA.id,
            Store.findKnownByName("AliExpress - AliExpress-239640258")?.id
        )
        assertEquals(
            Store.ALIEXPRESS_PLAZA.id,
            Store.findKnownByName("Marketplace", "https://www.aliexpress.com/item/example")?.id
        )
    }

    @Test
    fun testSpanishStoresDatabaseIntegrity() {
        val storeEntities = SpanishMarketData.getInitialStoreEntities()
        assertTrue("Store entities should contain at least 30 stores", storeEntities.size >= 30)
        val names = storeEntities.map { it.name }
        assertTrue(names.any { it.contains("Amazon", ignoreCase = true) })
        assertTrue(names.any { it.contains("PcComponentes", ignoreCase = true) })
        assertTrue(names.any { it.contains("MediaMarkt", ignoreCase = true) })
        assertTrue(names.any { it.contains("El Corte Inglés", ignoreCase = true) })
        assertTrue(names.any { it.contains("Carrefour", ignoreCase = true) })
        assertTrue(names.any { it.contains("Decathlon", ignoreCase = true) })
        assertTrue(names.any { it.contains("Leroy Merlin", ignoreCase = true) })
        assertTrue(names.any { it.contains("Apple Store", ignoreCase = true) })
        assertTrue(names.any { it.contains("Samsung Shop", ignoreCase = true) })
        assertTrue(names.any { it.contains("Xiaomi", ignoreCase = true) })
        assertTrue(names.any { it.contains("Fnac", ignoreCase = true) })
        assertTrue(names.any { it.contains("Alcampo", ignoreCase = true) })
    }
    @Test
    fun exactModelSearchRejectsUnrequestedMultiProductBundles() {
        val bundle =
            "Teclados Logitech Pack G213 Prodigy Teclado Gaming + Logitech G203 Lightsync 2nd Gen Ratón Gaming"

        assertTrue(isIrrelevantMultiProductBundle("Logitech G203", bundle))
        assertTrue(
            "Spaced models and a different product type should expose mixed-product bundles",
            isIrrelevantMultiProductBundle(
                "Xiaomi Redmi 17",
                "Xiaomi Redmi 17 Smartphone + Apple MacBook Air Laptop"
            )
        )
        assertFalse(
            isIrrelevantMultiProductBundle(
                "Logitech G203",
                "Logitech G203 Lightsync Gaming Mouse"
            )
        )
        assertFalse(
            "Explicit pack searches must still allow combo listings",
            isIrrelevantMultiProductBundle("Logitech G203 pack", bundle)
        )
    }

    @Test
    fun groupedProductKeepsRepresentativeImageAndPrefersDirectPurchaseLink() {
        val lowest = SerpApiShoppingResult(
            title = "Smartphone Sony WH-1000XM5 Wireless Headphones",
            price = 11.95,
            storeName = "Tienda barata",
            sourceUrl = "https://www.google.com/shopping/product/123",
            thumbnailUrl = "https://images.example/other-colour.jpg",
            observedAt = 1_800_000_000_000L
        )
        val representative = SerpApiShoppingResult(
            title = "Sony WH-1000XM5 Wireless Headphones",
            price = 24.99,
            storeName = "Tienda oficial",
            sourceUrl = "https://shop.example.com/sony-wh1000xm5",
            thumbnailUrl = "https://images.example/sony-black.jpg",
            observedAt = 1_800_000_000_000L
        )

        val group = groupOnlineShoppingResults(listOf(lowest, representative)).single()

        assertEquals(representative.title, group.title)
        assertEquals(representative.thumbnailUrl, group.representativeImageUrl)
        assertEquals("The lowest observed price remains available for comparison", 11.95, group.minPrice, 0.001)
        assertEquals("The default purchase action should prefer a direct retailer URL", representative, group.bestPurchaseOffer)
    }

    @Test
    fun groupedProductFallsBackToAnotherThumbnailWithinTheSameProductGroup() {
        val representativeWithoutImage = SerpApiShoppingResult(
            title = "Sony WH-1000XM5 Wireless Headphones",
            price = 199.99,
            storeName = "Tienda barata",
            sourceUrl = "https://cheap.example/sony",
            observedAt = 1_800_000_000_000L
        )
        val otherListing = representativeWithoutImage.copy(
            title = "Smartphone Sony WH-1000XM5 Wireless Headphones",
            price = 219.99,
            storeName = "Tienda con imagen",
            sourceUrl = "https://retailer.example/sony",
            thumbnailUrl = "https://images.example/sony.jpg"
        )

        val group = groupOnlineShoppingResults(listOf(representativeWithoutImage, otherListing)).single()

        assertEquals(otherListing.thumbnailUrl, group.representativeImageUrl)
    }

    @Test
    fun chartRangesFilterByActualDateWindowsNotObservationCounts() {
        val now = 2_000_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val points = listOf(
            PricePoint(now - 200L * day, "hace 200 días", 200.0, "Tienda"),
            PricePoint(now - 100L * day, "hace 100 días", 180.0, "Tienda"),
            PricePoint(now - 45L * day, "hace 45 días", 150.0, "Tienda"),
            PricePoint(now - 20L * day, "hace 20 días", 120.0, "Tienda"),
            PricePoint(now, "hoy", 100.0, "Tienda"),
            PricePoint(now + day, "mañana", 90.0, "Tienda")
        )
        assertEquals(
            listOf(points[3], points[4]),
            filterPricePointsForRange(points, ChartRange.DAYS_30, now)
        )
        assertEquals(
            listOf(points[2], points[3], points[4]),
            filterPricePointsForRange(points, ChartRange.DAYS_90, now)
        )
        assertEquals(
            listOf(points[1], points[2], points[3], points[4]),
            filterPricePointsForRange(points, ChartRange.MONTHS_6, now)
        )
        assertEquals(
            listOf(points[0], points[1], points[2], points[3], points[4]),
            filterPricePointsForRange(points, ChartRange.ALL, now)
        )
    }

}
