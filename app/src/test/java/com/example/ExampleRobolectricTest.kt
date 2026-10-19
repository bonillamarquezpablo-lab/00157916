package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.local.StoreEntity
import com.example.data.local.TrackedProductEntity
import com.example.data.model.Product
import com.example.data.repository.ProductRepository
import com.example.data.repository.SpanishMarketData
import com.example.data.remote.SerpApiClient
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.remote.SerpApiQuote
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.wheelSetCountForTitle
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Resource and SQLite migration tests run on API 35 using the Java 17 CI runtime.
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `catalogue streams only persisted products without seeding demo entries`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.productDao().insert(
                ProductEntity(
                    id = "sony_wh1000xm5",
                    title = "Producto Sony preservado por el usuario",
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 888.0,
                    athMinPrice = 888.0,
                    athMinDate = "Sin datos",
                    athMinStore = "Amazon España",
                    athMaxPrice = 888.0,
                    avg90dPrice = 888.0,
                    officialPvp = 888.0,
                    currentStoreId = "amazon_es",
                    storeUrl = "https://www.amazon.es/",
                    ean = "",
                    isPriceVerified = false
                )
            )
            // The startup cleanup must not discard a product just because it is awaiting a verified quote.
            database.trackedProductDao().insertOrUpdate(
                TrackedProductEntity(productId = "sony_wh1000xm5")
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = scope
            )

            val products: List<Product> = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { productRows -> productRows.any { it.id == "sony_wh1000xm5" } }
            }
            assertEquals("A new database must not be populated with 75 demo products", 1, products.size)
            assertEquals("Product IDs must stay unique", products.size, products.map { it.id }.toSet().size)
            val preserved = products.first { it.id == "sony_wh1000xm5" }
            assertEquals("Existing user row must not be overwritten", "Producto Sony preservado por el usuario", preserved.title)
            assertFalse("Unobserved product should stay unverified", preserved.isPriceVerified)
            assertTrue("Unverified product should not display fake history", preserved.priceHistory.isEmpty())
            assertFalse("New catalogue should not seed static demo rows", products.any { it.id == "demo_galaxy_s26_ultra" })

            // Legacy rows from before 13 October 2026 may remain in Room, but must not
            // contribute to current price or history even if they were marked verified.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sony_wh1000xm5",
                    storeId = "amazon_es",
                    price = 179.99,
                    observedAt = 1_780_000_000_000L,
                    sourceUrl = "https://www.amazon.es/dp/LEGACY",
                    sourceName = "Legacy unit-test source",
                    isVerified = true,
                    productTitle = "Sony WH-1000XM5 Wireless Headphones",
                    storeName = "Amazon.es",
                    sourceType = "Google Shopping"
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sony_wh1000xm5",
                    storeId = "amazon_es",
                    price = 222.50,
                    observedAt = System.currentTimeMillis(),
                    sourceUrl = "https://www.amazon.es/dp/TEST",
                    sourceName = "Authorized unit-test source",
                    isVerified = true,
                    productTitle = "Sony WH-1000XM5 Wireless Headphones",
                    storeName = "Amazon.es",
                    sourceType = "Google Shopping"
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sony_wh1000xm5",
                    storeId = "amazon_es",
                    price = 1.00,
                    observedAt = System.currentTimeMillis() + 1000,
                    sourceUrl = "https://example.com/demo",
                    sourceName = "Demo fixture",
                    isVerified = false
                )
            )
            database.productDao().updatePriceVerification("sony_wh1000xm5", true)

            val sourceBackedProducts: List<Product> = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { rows ->
                    rows.firstOrNull { row -> row.id == "sony_wh1000xm5" }?.isPriceVerified == true
                }
            }
            val sourceBackedSony = sourceBackedProducts.first { it.id == "sony_wh1000xm5" }
            assertEquals(222.50, sourceBackedSony.currentPrice, 0.001)
            assertEquals(222.50, sourceBackedSony.athMinPrice, 0.001)
            assertEquals("The legacy verified row must not appear in current history", 1, sourceBackedSony.priceHistory.size)
            assertEquals(222.50, sourceBackedSony.priceHistory.single().price, 0.001)
            assertFalse(sourceBackedSony.priceHistory.any { it.price == 179.99 })
        } finally {
            database.close()
        }
    }

    @Test
    fun impossibleSmartphoneObservationCannotRemainVerifiedCurrentPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val observedAt = System.currentTimeMillis()
            database.productDao().insert(
                ProductEntity(
                    id = "samsung_galaxy_a57",
                    title = "Samsung Galaxy A57 Azul Marino 256GB",
                    brand = "Samsung",
                    category = "SMARTPHONES",
                    currentPrice = 9_300.0,
                    athMinPrice = 9_300.0,
                    athMinDate = "Historial en construcción",
                    athMinStore = "Samsung Shop España",
                    athMaxPrice = 9_300.0,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "samsung_store",
                    storeUrl = "https://www.samsung.com/es/",
                    ean = "",
                    isPriceVerified = false
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = scope
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "samsung_galaxy_a57",
                    storeId = "samsung_store",
                    price = 9_300.0,
                    observedAt = observedAt,
                    sourceUrl = "https://www.samsung.com/es/smartphones/galaxy-a57/",
                    sourceName = "Google Shopping",
                    isVerified = true,
                    productTitle = "Samsung Galaxy A57 Azul Marino 256GB",
                    storeName = "Samsung Shop España",
                    sourceType = "Google Shopping"
                )
            )
            database.productDao().updatePriceVerification("samsung_galaxy_a57", true)

            val product = withTimeout(10_000) {
                repository.allProductsStream.first { rows -> rows.any { it.id == "samsung_galaxy_a57" } }
                    .first { it.id == "samsung_galaxy_a57" }
            }
            assertFalse("A 9,300 € phone observation must not validate a current price", product.isPriceVerified)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.DEMONSTRATION,
                product.priceDisplayStatus
            )
            assertEquals(null, product.savedHistoricalPrice)
            assertTrue("The impossible quote must not create a history point", product.priceHistory.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `followed products appear in the tracked refresh stream and can be unfollowed`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            database.productDao().insert(
                ProductEntity(
                    id = "sample-followed-product",
                    title = "Producto de prueba seguido",
                    brand = "Marca de prueba",
                    category = "TV_AUDIO",
                    currentPrice = 49.99,
                    athMinPrice = 49.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "Amazon.es",
                    athMaxPrice = 49.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon_es",
                    storeUrl = "https://www.amazon.es/dp/TEST",
                    ean = "",
                    isPriceVerified = true
                )
            )
            // Give the tracking-flow fixture a source-backed observation instead of relying only on
            // a potentially stale isPriceVerified flag.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sample-followed-product",
                    storeId = "amazon_es",
                    price = 49.99,
                    currency = "EUR",
                    observedAt = System.currentTimeMillis(),
                    sourceUrl = "https://www.amazon.es/dp/TEST",
                    sourceName = "Amazon.es",
                    isVerified = true,
                    verificationNote = "Verified fixture for tracking flow test",
                    productTitle = "Producto de prueba seguido",
                    storeName = "Amazon.es",
                    sourceType = "Amazon.es",
                    linkType = "directo"
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = scope
            )

            val initiallyTracked = withTimeout<List<Product>>(10_000) {
                repository.trackedProductsStream.first { it.isEmpty() }
            }
            assertTrue(initiallyTracked.isEmpty())

            repository.toggleTracking("sample-followed-product")
            val followed = withTimeout<List<Product>>(10_000) {
                repository.trackedProductsStream.first { products ->
                    products.any { it.id == "sample-followed-product" && it.isTracked }
                }
            }
            assertEquals(1, followed.size)
            assertTrue(followed.single().isTracked)

            repository.toggleTracking("sample-followed-product")
            val unfollowed = withTimeout<List<Product>>(10_000) {
                repository.trackedProductsStream.first { it.isEmpty() }
            }
            assertTrue(unfollowed.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `SerpApi quotes accept direct and Google links while rejecting invalid price currency and model`() {
        val requested = SerpApiRequestProduct(
            id = "demo_galaxy_s26_ultra",
            title = "Samsung Galaxy S26 Ultra 512 GB",
            brand = "Samsung"
        )
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Samsung Galaxy S26 Ultra 512GB negro",
                  "price": "1.299,99 €",
                  "extracted_price": 1299.99,
                  "product_link": "https://www.google.com/shopping/product/123456789?gl=es",
                  "source": "PcComponentes"
                },
                {
                  "title": "Samsung Galaxy S26 Ultra 512GB negro",
                  "price": "1.249,50 €",
                  "extracted_price": 1249.50,
                  "link": "https://www.amazon.es/dp/example",
                  "source": "Amazon España"
                },
                {
                  "title": "Samsung Galaxy S26 Ultra 512GB",
                  "price": "$999.99",
                  "extracted_price": 999.99,
                  "link": "https://shop.example.com/product",
                  "source": "Example USD"
                },
                {
                  "title": "Samsung Galaxy S26 Ultra 512GB",
                  "price": "899,99 €",
                  "extracted_price": 899.99,
                  "link": "http://unsafe.example.com/product",
                  "source": "Unsafe HTTP"
                },
                {
                  "title": "Tostadora de cocina",
                  "price": "19,99 €",
                  "extracted_price": 19.99,
                  "link": "https://shop.example.com/toaster",
                  "source": "Unrelated item"
                }
              ]
            }
            """.trimIndent()
        )

        val quotes = SerpApiClient().parseQuotes(payload, requested, observedAt = 123L)

        assertEquals("A matching Google Shopping URL and an HTTP merchant URL are not rejected by link type", 3, quotes.size)
        assertEquals(899.99, quotes[0].price, 0.001)
        assertEquals("directo", com.example.data.remote.linkTypeForUrl(quotes[0].sourceUrl))
        assertEquals(1249.50, quotes[1].price, 0.001)
        assertEquals("Amazon España", quotes[1].storeName)
        assertEquals(1299.99, quotes[2].price, 0.001)
        assertTrue(quotes[2].sourceUrl.contains("google.com/shopping/product"))
        assertEquals("Google Shopping", com.example.data.remote.linkTypeForUrl(quotes[2].sourceUrl))
        assertTrue(quotes.all { it.currency == "EUR" && it.observedAt == 123L })
        assertTrue(quotes.all { it.productId == "demo_galaxy_s26_ultra" })
    }

    @Test
    fun `googleShopping accepts all valid offers when most links point to Google`() {
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {"title":"Samsung Galaxy S26 Ultra 512GB Negro","price":"1.299,99 €","extracted_price":1299.99,"product_link":"https://www.google.com/shopping/product/gs1","source":"Tienda Uno","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512GB Negro","price":"1.279,99 €","extracted_price":1279.99,"product_link":"https://www.google.com/shopping/product/gs2","source":"Tienda Dos","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512GB Negro","price":"1.249,99 €","extracted_price":1249.99,"link":"https://www.google.es/search?tbm=shop&q=gs3","source":"Tienda Tres","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512GB Negro","price":"1.219,99 €","extracted_price":1219.99,"product_link":"https://www.google.com/shopping/product/gs4","source":"Tienda Cuatro","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512GB Negro","price":"1.199,99 €","extracted_price":1199.99,"product_link":"https://www.google.com/shopping/product/gs5","source":"Tienda Cinco","second_hand_condition":"new"},
                {"title":"Samsung Galaxy S26 Ultra 512GB usado","price":"899,99 €","extracted_price":899.99,"product_link":"https://www.google.com/shopping/product/gs-used","source":"Tienda Segunda Mano","second_hand_condition":"used"},
                {"title":"Samsung Galaxy S26 Ultra 512GB alquiler mensual","price":"49,99 €","extracted_price":49.99,"product_link":"https://www.google.com/shopping/product/gs-rent","source":"Alquiler","second_hand_condition":"new"}
              ]
            }
            """.trimIndent()
        )
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = payload,
            query = "Samsung Galaxy S26 Ultra 512 GB",
            observedAt = 123L
        )
        assertEquals("All six valid new/used offers survive Google destination links", 6, report.results.size)
        assertEquals(5, report.results.count { it.linkType == "Google Shopping" && !it.isSecondHand })
        assertEquals(1, report.results.count { it.isSecondHand })
        assertTrue(report.results.none { it.title.contains("alquiler", ignoreCase = true) })
    }

    @Test
    fun `positive sub-five-euro offer is kept but flagged below thirty percent of median`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val timestamp = System.currentTimeMillis()
            val results = listOf(
                SerpApiShoppingResult("Anker Power Bank 20000mAh", 1.99, "Tienda barata", "https://www.google.com/shopping/product/cheap", observedAt = timestamp),
                SerpApiShoppingResult("Anker Power Bank 20000mAh", 39.99, "Tienda 2", "https://www.google.com/shopping/product/p2", observedAt = timestamp),
                SerpApiShoppingResult("Anker Power Bank 20000mAh", 42.99, "Tienda 3", "https://www.google.com/shopping/product/p3", observedAt = timestamp),
                SerpApiShoppingResult("Anker Power Bank 20000mAh", 44.99, "Tienda 4", "https://www.google.com/shopping/product/p4", observedAt = timestamp),
                SerpApiShoppingResult("Anker Power Bank 20000mAh", 49.99, "Tienda 5", "https://www.google.com/shopping/product/p5", observedAt = timestamp)
            )
            val filtered = repository.filterOnlineSearchResults(results, "Anker Power Bank 20000mAh")
            assertEquals("Suspicious offers remain visible instead of being discarded", 5, filtered.size)
            assertTrue(filtered.single { it.price == 1.99 }.isSuspiciousPrice)
            assertTrue(filtered.filter { it.price > 1.99 }.none { it.isSuspiciousPrice })
            assertEquals("The cheapest trusted offer is used for group best-price calculations", 39.99,
                groupOnlineShoppingResults(filtered).single().cheapestOffer.price, 0.001)

            // The suspicious low quote must remain inspectable but cannot become the saved current price.
            repository.recordOnlineSearchResults(
                results = filtered,
                sourceType = "GOOGLE_SHOPPING",
                searchQuery = "Anker Power Bank 20000mAh"
            )
            val savedProduct = database.productDao().getAllProductsSnapshot().single()
            assertEquals("A suspicious low offer must not contaminate the current price", 39.99, savedProduct.currentPrice, 0.001)
            assertTrue("Only trusted offers determine verified current price", savedProduct.isPriceVerified)
            assertTrue(
                "The 1.99 € anomaly stays in the audit trail, not verified price history",
                database.priceObservationDao().observeVerifiedHistory(savedProduct.id).first()
                    .none { it.price == 1.99 }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `extreme high outlier stays visible but cannot become the best-price or history candidate`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val timestamp = System.currentTimeMillis()
            val offers = listOf(999.99, 1_049.99, 1_099.99, 1_149.99, 4_999.99)
                .mapIndexed { index, price ->
                    SerpApiShoppingResult(
                        title = "Samsung Galaxy S26 Ultra 512 GB",
                        price = price,
                        storeName = "Tienda ${index + 1}",
                        sourceUrl = "https://www.google.com/shopping/product/high-$index",
                        observedAt = timestamp
                    )
                }

            val filtered = repository.filterOnlineSearchResults(
                offers,
                "Samsung Galaxy S26 Ultra 512 GB"
            )
            assertEquals("Outliers stay visible so the user can inspect them", 5, filtered.size)
            assertTrue(
                "A price more than three times the median of the same model is marked suspicious",
                filtered.single { it.price == 4_999.99 }.isSuspiciousPrice
            )
            assertTrue(
                "Normal comparable offers are not flagged by the high-outlier rule",
                filtered.filter { it.price < 4_999.99 }.none { it.isSuspiciousPrice }
            )
            assertEquals(
                "A suspicious high price must not affect the cheapest trusted offer",
                999.99,
                groupOnlineShoppingResults(filtered).single().cheapestOffer.price,
                0.001
            )

            // A lone absurd phone price has no peer median, so the absolute category bound must catch it.
            val loneGalaxyOutlier = repository.filterOnlineSearchResults(
                listOf(
                    SerpApiShoppingResult(
                        title = "Samsung Galaxy A57 Azul Marino 256GB",
                        price = 9_300.0,
                        storeName = "Tienda Samsung",
                        sourceUrl = "https://www.google.com/shopping/product/galaxy-a57",
                        observedAt = timestamp
                    )
                ),
                "Samsung Galaxy A57 Azul Marino 256GB"
            )
            assertEquals("Keep the suspicious offer visible for inspection", 1, loneGalaxyOutlier.size)
            assertTrue("A 9,300 € mid-range phone must never be treated as a verified price", loneGalaxyOutlier.single().isSuspiciousPrice)
            val diagnosis = repository.diagnoseOnlineSearchRejections(
                offers,
                "Samsung Galaxy S26 Ultra 512 GB"
            )
            assertTrue(
                "The diagnostic explains the high outlier",
                diagnosis.any {
                    it.priceLabel == "4999.99" && it.reason.contains("Precio sospechoso")
                }
            )
            assertEquals(
                "Diagnosing outliers must not persist observations",
                0,
                database.priceObservationDao().getVerifiedObservationCount()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `wheel prices compare single rims with four rim sets`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val timestamp = System.currentTimeMillis()
            val offers = listOf(
                "RIAL Milano" to 111.00,
                "RIAL Milano" to 142.80,
                "4x Llantas RIAL MILANO Titanio" to 464.10,
                "4x Llantas RIAL MILANO Titanio" to 516.53,
                "4x Llantas RIAL MILANO Titanio" to 540.00,
                "4x Llantas RIAL MILANO Titanio" to 550.00
            ).mapIndexed { index, (title, price) ->
                SerpApiShoppingResult(
                    title = title,
                    price = price,
                    storeName = "Tienda $index",
                    sourceUrl = "https://www.google.com/shopping/product/wheel-$index",
                    observedAt = timestamp,
                    condition = "new"
                )
            }

            val filtered = repository.filterOnlineSearchResults(offers, "RIAL Milano 14")

            assertEquals("All valid wheel candidates remain visible", offers.size, filtered.size)
            assertTrue(
                "Single-rim offers at 111 and 142.80 EUR should be compared with each other",
                filtered.filter { it.title == "RIAL Milano" }.none { it.isSuspiciousPrice }
            )
            assertTrue(
                "Four-rim set prices must not make a single rim look suspicious",
                filtered.filter { it.title.startsWith("4x Llantas") }.none { it.isSuspiciousPrice }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun brandSearchesTolerateAMinorMilwaukeeTypo() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Milwaukee M18 FUEL taladro percutor brushless",
                  "price": "299,99 €",
                  "extracted_price": 299.99,
                  "link": "https://www.milwaukeetool.eu/product",
                  "source": "Milwaukee Tool"
                }
              ]
            }
            """.trimIndent()
        )

        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = "Milwauke",
            observedAt = 123L
        )

        assertEquals("A one-character misspelling must not discard a relevant brand result", 1, report.results.size)
        assertEquals("Milwaukee Tool", report.results.single().storeName)
        assertEquals(299.99, report.results.single().price, 0.001)
    }

    @Test
    fun `batch keeps valid offers when one product lookup fails`() = runBlocking<Unit> {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val query = request.url.queryParameter("q").orEmpty()
                val isFailure = query.contains("Samsung", ignoreCase = true)
                val code = if (isFailure) 503 else 200
                val body = if (isFailure) {
                    """{"error":"temporary provider failure"}"""
                } else {
                    """
                    {
                      "search_metadata": {"status": "Success"},
                      "shopping_results": [{
                        "title": "Sony WH-1000XM5",
                        "price": "249,99 €",
                        "extracted_price": 249.99,
                        "link": "https://www.sony.es/electronics/headband-headphones/wh-1000xm5",
                        "source": "Sony"
                      }]
                    }
                    """.trimIndent()
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (isFailure) "Service Unavailable" else "OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val batch = SerpApiClient(http).searchProducts(
            token = "test-api-key",
            products = listOf(
                SerpApiRequestProduct(
                    id = "samsung_product",
                    title = "Samsung Galaxy S26 Ultra 512 GB",
                    brand = "Samsung"
                ),
                SerpApiRequestProduct(
                    id = "sony_product",
                    title = "Sony WH-1000XM5",
                    brand = "Sony"
                )
            )
        )

        assertEquals(2, batch.requestedQueries)
        assertEquals(1, batch.failedQueries)
        assertEquals("sony_product", batch.quotes.single().productId)
        assertEquals(249.99, batch.quotes.single().price, 0.001)
    }

    @Test
    fun `manual price refresh displays current seller quotes without fake stock or history`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            database.storeDao().insertAll(SpanishMarketData.getInitialStoreEntities())
            database.productDao().insert(
                ProductEntity(
                    id = "live-test-product",
                    title = "Sony WH-1000XM5",
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 888.0,
                    athMinPrice = 888.0,
                    athMinDate = "Sin datos",
                    athMinStore = "Sin datos",
                    athMaxPrice = 888.0,
                    avg90dPrice = 888.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon_es",
                    storeUrl = "https://www.amazon.es/",
                    ean = "",
                    isPriceVerified = false
                )
            )
            // Keep this unverified product as an explicit user-tracked item until the provider refreshes it.
            database.trackedProductDao().insertOrUpdate(
                TrackedProductEntity(productId = "live-test-product")
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val observedAt = System.currentTimeMillis()
            val changedProducts = repository.recordSerpApiQuotes(
                listOf(
                    SerpApiQuote(
                        productId = "live-test-product",
                        price = 222.50,
                        currency = "EUR",
                        observedAt = observedAt,
                        sourceUrl = "https://www.amazon.es/dp/TEST",
                        storeId = "retailer_amazon_es",
                        storeName = "Amazon España"
                    ),
                    SerpApiQuote(
                        productId = "live-test-product",
                        price = 230.00,
                        currency = "EUR",
                        observedAt = observedAt,
                        sourceUrl = "https://www.pccomponentes.com/TEST",
                        storeId = "retailer_pccomponentes_com",
                        storeName = "PcComponentes"
                    )
                )
            )

            assertEquals("Returns the count of products refreshed", 1, changedProducts)
            val rows: List<Product> = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { items ->
                    items.firstOrNull { item -> item.id == "live-test-product" }?.offers?.size == 2
                }
            }
            val product = rows.first { it.id == "live-test-product" }
            assertTrue(product.isPriceVerified)
            assertEquals(222.50, product.currentPrice, 0.001)
            assertEquals(2, product.offers.size)
            assertTrue("Stock status must be checked at the store", product.offers.none { it.inStock })
            assertTrue(
                "Shipping must not be assumed",
                product.offers.all { it.shippingInfo == "Consultar envío en la tienda" }
            )
            assertFalse("One query cannot establish 90-day history", product.hasSufficientHistory)
            assertFalse("One query cannot establish an all-time low", product.isAtAth)
            assertEquals("Live query must not keep the demo EAN", "", product.ean)
            assertFalse("Live query must not verify demo ratings", product.isRatingVerified)
        } finally {
            database.close()
        }
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Mínimo", appName)
    }


    @Test
    fun `migration from version 6 to 7 preserves preexisting products observations and tracking`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "minimo_v6_preservation_test.db"
        context.deleteDatabase(databaseName)

        // Construct a real version-6 database, including history written before migration starts.
        val oldDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            "CREATE TABLE `spanish_stores` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `domain` TEXT NOT NULL, " +
                "`brandColorHex` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                "`physicalStoresInSpain` INTEGER NOT NULL, `storeCountApprox` INTEGER NOT NULL, " +
                "`headquartersInSpain` TEXT NOT NULL, `description` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        oldDatabase.execSQL(
            "CREATE TABLE `products` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `brand` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `currentPrice` REAL NOT NULL, `athMinPrice` REAL NOT NULL, " +
                "`athMinDate` TEXT NOT NULL, `athMinStore` TEXT NOT NULL, `athMaxPrice` REAL NOT NULL, " +
                "`avg90dPrice` REAL NOT NULL, `officialPvp` REAL NOT NULL, `currentStoreId` TEXT NOT NULL, " +
                "`storeUrl` TEXT NOT NULL, `ean` TEXT NOT NULL, `imageResName` TEXT, `imageUrl` TEXT, " +
                "`rating` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, " +
                "`wasRecentlyInflated` INTEGER NOT NULL, `fakePriorPrice` REAL, " +
                "`isPriceVerified` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
        oldDatabase.execSQL(
            "CREATE TABLE `tracked_products` (" +
                "`productId` TEXT NOT NULL, `targetAlertPrice` REAL, " +
                "`isNotificationEnabled` INTEGER NOT NULL, `trackedAtTimestamp` INTEGER NOT NULL, " +
                "`notes` TEXT NOT NULL, PRIMARY KEY(`productId`))"
        )
        oldDatabase.execSQL(
            "CREATE TABLE `price_observations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` TEXT NOT NULL, " +
                "`storeId` TEXT NOT NULL, `price` REAL NOT NULL, `currency` TEXT NOT NULL, " +
                "`observedAt` INTEGER NOT NULL, `sourceUrl` TEXT NOT NULL, `sourceName` TEXT NOT NULL, " +
                "`isVerified` INTEGER NOT NULL, `verificationNote` TEXT, " +
                "`productTitle` TEXT NOT NULL DEFAULT '', `storeName` TEXT NOT NULL DEFAULT '', " +
                "`sourceType` TEXT NOT NULL DEFAULT 'Google Shopping', `linkType` TEXT NOT NULL DEFAULT 'directo')"
        )
        oldDatabase.execSQL(
            "CREATE INDEX `index_price_observations_productId_observedAt` " +
                "ON `price_observations` (`productId`, `observedAt`)"
        )
        oldDatabase.execSQL(
            "CREATE INDEX `index_price_observations_storeId_observedAt` " +
                "ON `price_observations` (`storeId`, `observedAt`)"
        )
        oldDatabase.execSQL(
            "INSERT INTO `products` (" +
                "`id`, `title`, `brand`, `category`, `currentPrice`, `athMinPrice`, " +
                "`athMinDate`, `athMinStore`, `athMaxPrice`, `avg90dPrice`, `officialPvp`, " +
                "`currentStoreId`, `storeUrl`, `ean`, `imageResName`, `imageUrl`, `rating`, " +
                "`reviewCount`, `wasRecentlyInflated`, `fakePriorPrice`, `isPriceVerified`) " +
                "VALUES ('keep-v6-product', 'Producto conservado v6', 'Marca', 'COMPUTING', 123.45, 120.00, " +
                "'Fecha guardada', 'Amazon.es', 180.00, 145.00, 180.00, 'amazon_es', " +
                "'https://www.amazon.es/dp/V6', '', NULL, NULL, 0.0, 0, 0, NULL, 1)"
        )
        oldDatabase.execSQL(
            "INSERT INTO `tracked_products` " +
                "(`productId`, `targetAlertPrice`, `isNotificationEnabled`, `trackedAtTimestamp`, `notes`) " +
                "VALUES ('keep-v6-product', 89.99, 1, 1700000000000, 'seguimiento previo a v7')"
        )
        oldDatabase.execSQL(
            "INSERT INTO `price_observations` " +
                "(`id`, `productId`, `storeId`, `price`, `currency`, `observedAt`, `sourceUrl`, " +
                "`sourceName`, `isVerified`, `verificationNote`, `productTitle`, `storeName`, `sourceType`, `linkType`) " +
                "VALUES (41, 'keep-v6-product', 'amazon_es', 123.45, 'EUR', 1700000000000, " +
                "'https://www.amazon.es/dp/V6', 'Amazon.es', 1, 'guardada antes de migrar', " +
                "'Producto conservado v6', 'Amazon.es', 'Amazon.es', 'directo')"
        )
        oldDatabase.execSQL(
            "INSERT INTO `price_observations` " +
                "(`id`, `productId`, `storeId`, `price`, `currency`, `observedAt`, `sourceUrl`, " +
                "`sourceName`, `isVerified`, `verificationNote`, `productTitle`, `storeName`, `sourceType`, `linkType`) " +
                "VALUES (42, 'keep-v6-product', 'amazon_es', 127.99, 'EUR', 1700000001000, " +
                "'https://www.google.com/shopping/product/V6', 'Tienda pendiente', 0, 'pendiente de verificar', " +
                "'Producto conservado v6', 'Tienda pendiente', 'Google Shopping', 'Google Shopping')"
        )
        oldDatabase.version = 6
        oldDatabase.close()

        val migratedDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName
        )
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()

        try {
            // Open triggers the 6 -> 7 migration, after both history rows were already stored.
            migratedDatabase.openHelper.writableDatabase
            runBlocking {
                val product = migratedDatabase.productDao().getProductById("keep-v6-product")
                assertNotNull("Product written before migration must survive", product)
                assertEquals(123.45, product?.currentPrice ?: 0.0, 0.001)
                assertEquals("Product schema migration only adds Google catalog ID", null, product?.googleProductId)

                val tracking = migratedDatabase.trackedProductDao().getById("keep-v6-product")
                assertNotNull("Watchlist row written before migration must survive", tracking)
                assertEquals("seguimiento previo a v7", tracking?.notes)
                assertEquals(89.99, tracking?.targetAlertPrice ?: 0.0, 0.001)

                assertEquals("Both pre-migration observations must survive", 2,
                    migratedDatabase.priceObservationDao().getObservationCount("keep-v6-product"))
                assertEquals("The original verification state must be preserved", 1,
                    migratedDatabase.priceObservationDao().getVerifiedObservationCount())
                val verifiedHistory = migratedDatabase.priceObservationDao()
                    .observeVerifiedHistory("keep-v6-product").first()
                assertEquals(1, verifiedHistory.size)
                assertEquals(41L, verifiedHistory.single().id)
                assertEquals(123.45, verifiedHistory.single().price, 0.001)
                assertEquals("Amazon.es", verifiedHistory.single().sourceName)
                assertEquals("directo", verifiedHistory.single().linkType)
                assertEquals("live", verifiedHistory.single().evidenceType)
                assertEquals(1_700_000_000_000L, verifiedHistory.single().collectedAt)
                assertEquals("", verifiedHistory.single().sourcePageUrl)
            }
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `migration from v2 to current schema preserves watchlist and stores link destination types`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "minimo_migration_preservation_test.db"
        context.deleteDatabase(databaseName)

        // Build the schema of the currently installed v2 app, including user tracking.
        val oldDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `spanish_stores` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `domain` TEXT NOT NULL, " +
                "`brandColorHex` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                "`physicalStoresInSpain` INTEGER NOT NULL, `storeCountApprox` INTEGER NOT NULL, " +
                "`headquartersInSpain` TEXT NOT NULL, `description` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        oldDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `products` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `brand` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `currentPrice` REAL NOT NULL, `athMinPrice` REAL NOT NULL, " +
                "`athMinDate` TEXT NOT NULL, `athMinStore` TEXT NOT NULL, `athMaxPrice` REAL NOT NULL, " +
                "`avg90dPrice` REAL NOT NULL, `officialPvp` REAL NOT NULL, `currentStoreId` TEXT NOT NULL, " +
                "`storeUrl` TEXT NOT NULL, `ean` TEXT NOT NULL, `imageResName` TEXT, `imageUrl` TEXT, " +
                "`rating` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, " +
                "`wasRecentlyInflated` INTEGER NOT NULL, `fakePriorPrice` REAL, PRIMARY KEY(`id`))"
        )
        oldDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracked_products` (" +
                "`productId` TEXT NOT NULL, `targetAlertPrice` REAL, " +
                "`isNotificationEnabled` INTEGER NOT NULL, `trackedAtTimestamp` INTEGER NOT NULL, " +
                "`notes` TEXT NOT NULL, PRIMARY KEY(`productId`))"
        )
        oldDatabase.execSQL(
            "INSERT INTO `tracked_products` " +
                "(`productId`, `targetAlertPrice`, `isNotificationEnabled`, `trackedAtTimestamp`, `notes`) " +
                "VALUES ('keep-me', 159.99, 1, 123456789, 'preserve this watch')"
        )
        oldDatabase.execSQL(
            "INSERT INTO `products` " +
                "(`id`, `title`, `brand`, `category`, `currentPrice`, `athMinPrice`, " +
                "`athMinDate`, `athMinStore`, `athMaxPrice`, `avg90dPrice`, `officialPvp`, " +
                "`currentStoreId`, `storeUrl`, `ean`, `imageResName`, `imageUrl`, `rating`, " +
                "`reviewCount`, `wasRecentlyInflated`, `fakePriorPrice`) " +
                "VALUES ('keep-product', 'Producto guardado', 'Marca', 'COMPUTING', 123.45, 120.00, " +
                "'Fecha anterior', 'Amazon España', 180.00, 145.00, 180.00, 'amazon_es', " +
                "'https://www.amazon.es/dp/TEST', '0000000000000', NULL, NULL, 0.0, 0, 0, NULL)"
        )
        oldDatabase.version = 2
        oldDatabase.close()

        val migratedDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName
        )
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()

        try {
            // Opening the helper triggers the migration and Room's schema validation.
            migratedDatabase.openHelper.writableDatabase
            runBlocking {
                val preserved = migratedDatabase.trackedProductDao().getById("keep-me")
                assertNotNull("Existing tracking must survive migration", preserved)
                assertEquals("preserve this watch", preserved?.notes)
                assertEquals(159.99, preserved?.targetAlertPrice ?: 0.0, 0.001)

                val preservedProduct = migratedDatabase.productDao().getProductById("keep-product")
                assertNotNull("Existing product row must survive migration", preservedProduct)
                assertEquals(123.45, preservedProduct?.currentPrice ?: 0.0, 0.001)
                assertFalse("Pre-existing demo product must remain unverified", preservedProduct?.isPriceVerified ?: true)
                assertFalse("Existing PVP cannot become verified through migration", preservedProduct?.isOfficialPvpVerified ?: true)
                assertFalse("Existing ratings cannot become verified through migration", preservedProduct?.isRatingVerified ?: true)
                assertFalse("Existing EAN cannot become verified through migration", preservedProduct?.isEanVerified ?: true)

                migratedDatabase.priceObservationDao().insert(
                    PriceObservationEntity(
                        productId = "sample-product",
                        storeId = "sample-store",
                        price = 42.50,
                        observedAt = 1000L,
                        sourceUrl = "https://www.google.com/shopping/product/123456",
                        sourceName = "Unit-test source",
                        isVerified = true,
                        linkType = "Google Shopping"
                    )
                )
                migratedDatabase.priceObservationDao().insert(
                    PriceObservationEntity(
                        productId = "sample-product",
                        storeId = "sample-store",
                        price = 1.00,
                        observedAt = 2000L,
                        sourceUrl = "https://example.com/demo",
                        sourceName = "Demo fixture",
                        isVerified = false
                    )
                )

                assertEquals(
                    "The migrated rows remain stored but incomplete legacy metadata cannot qualify as a current price",
                    2,
                    migratedDatabase.priceObservationDao().getObservationCount("sample-product")
                )
                assertEquals(0, migratedDatabase.priceObservationDao().getVerifiedObservationCount())
                val storedRows = migratedDatabase.priceObservationDao().observeAllStored().first()
                    .filter { it.productId == "sample-product" }
                assertEquals(2, storedRows.size)
                assertTrue("The legacy verified flag and source row must be preserved for diagnosis",
                    storedRows.any { it.isVerified && it.price == 42.50 })
                val latestVerified = migratedDatabase.priceObservationDao().getLatestVerified("sample-product")
                assertEquals(null, latestVerified)
            }
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun wheelFitmentSearchKeepsPlausibleUnknownsLabeledAndRejectsExplicitMismatches() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Magnetto Wheels MW R1-1672 Llantas de acero 14\" 5x100 negras",
                  "price": "59,00 €",
                  "extracted_price": 59.00,
                  "link": "https://shop.example.test/magnetto",
                  "source": "Tienda nueva",
                  "condition": "new"
                },
                {
                  "title": "RIAL Milano llanta",
                  "price": "94,99 €",
                  "extracted_price": 94.99,
                  "link": "https://shop.example.test/rial-milano",
                  "source": "Tienda de llantas",
                  "condition": "new"
                },
                {
                  "title": "RIAL Kodiak llanta 7x16 5x112",
                  "price": "94,99 €",
                  "extracted_price": 94.99,
                  "link": "https://shop.example.test/rial-kodiak",
                  "source": "Tienda de llantas",
                  "condition": "new"
                },
                {
                  "title": "Tapacubos 14\" 5x100",
                  "price": "19,99 €",
                  "extracted_price": 19.99,
                  "link": "https://shop.example.test/hubcap",
                  "source": "Tienda de accesorios",
                  "condition": "new"
                },
                {
                  "title": "Llantas Seat Ibiza 165/70/14 81T neumáticos 5x100",
                  "price": "25,00 €",
                  "extracted_price": 25.00,
                  "link": "https://es.wallapop.com/item/seat-ibiza",
                  "source": "Wallapop",
                  "second_hand_condition": "used"
                }
              ]
            }
            """.trimIndent()
        )

        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = """llantas 14" 5x100""",
            observedAt = System.currentTimeMillis()
        )

        assertEquals("Titles accepted: ${report.results.joinToString { it.title }}", 3, report.results.size)
        val confirmed = report.results.first { it.title.startsWith("Magnetto") }
        assertFalse(confirmed.isFitmentUnverified)
        val candidate = report.results.first { it.title == "RIAL Milano llanta" }
        assertTrue(candidate.isFitmentUnverified)
        val used = report.results.first { it.storeName == "Wallapop" }
        assertTrue(used.isSecondHand)
        assertTrue(
            report.discardedOffers.any {
                it.title.contains("RIAL Kodiak") && it.reason.contains("Diámetro distinto", ignoreCase = true)
            }
        )
        assertTrue(report.discardedOffers.any { it.title.startsWith("Tapacubos") })

        // Android/search input may preserve a backslash before the inch mark. It must still use
        // wheel-fitment validation, keeping an unverified RIAL Milano candidate while rejecting
        // the explicitly incompatible 7x16 5x112 RIAL Kodiak.
        val escapedQuoteReport = client.parseShoppingResultsDetailed(
            root = payload,
            query = """llantas 14\" 5x100""",
            observedAt = System.currentTimeMillis()
        )
        assertTrue(escapedQuoteReport.results.any { it.title == "RIAL Milano llanta" && it.isFitmentUnverified })
        assertTrue(
            escapedQuoteReport.discardedOffers.any {
                it.title.contains("RIAL Kodiak") && it.reason.contains("Diámetro distinto: 16 pulgadas", ignoreCase = true)
            }
        )
    }

    @Test
    fun modelFirstWheelSearchValidatesFitmentWithoutRequiringTheWordLlantas() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {"title":"RIAL Milano llanta","price":"111,00 €","extracted_price":111.00,"link":"https://oponeo.example/rial-milano","source":"Oponeo.es","condition":"new"},
                {"title":"RIAL Milano llanta 14 pulgadas 5x100","price":"142,80 €","extracted_price":142.80,"link":"https://tienda.example/rial-milano-5x100","source":"Tienda Rial","condition":"new"},
                {"title":"RIAL Kodiak llanta","price":"94,99 €","extracted_price":94.99,"link":"https://tienda.example/rial-kodiak","source":"Tienda Rial","condition":"new"},
                {"title":"RIAL U1","price":"133,78 €","extracted_price":133.78,"link":"https://tienda.example/rial-u1","source":"Tienda Rial","condition":"new"},
                {"title":"VW UP! Llanta Merano 14 pulgadas 5.0Jx14 ET35 OEM","price":"149,99 €","extracted_price":149.99,"link":"https://tienda.example/vw-merano","source":"Tienda VW","condition":"new"},
                {"title":"4x Llantas RIAL MILANO Titanio 5.5Jx14 ET35 4x100","price":"516,53 €","extracted_price":516.53,"link":"https://tienda.example/rial-set-4x100","source":"Tienda Rial","condition":"new"},
                {"title":"RIAL Milano llanta 16 pulgadas 5x112","price":"154,60 €","extracted_price":154.60,"link":"https://tienda.example/rial-milano-5x112","source":"Tienda Rial","condition":"new"},
                {"title":"RIAL Milano llanta usada 14 pulgadas 5x100","price":"65,00 €","extracted_price":65.00,"link":"https://es.wallapop.com/item/rial-milano","source":"Wallapop","second_hand_condition":"used"}
              ]
            }
            """.trimIndent()
        )

        val query = "RIAL Milano 14\" 5x100"
        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = query,
            observedAt = System.currentTimeMillis()
        )

        val singleRimWithoutFitment = report.results.first { it.title == "RIAL Milano llanta" }
        assertTrue(singleRimWithoutFitment.isFitmentUnverified)
        assertTrue(report.results.any { it.title == "RIAL Milano llanta 14 pulgadas 5x100" && !it.isFitmentUnverified })
        assertTrue(report.results.any { it.isSecondHand && it.storeName == "Wallapop" })
        assertTrue(report.results.none { it.title.contains("RIAL Kodiak") || it.title == "RIAL U1" })
        assertTrue(report.results.none { it.title.contains("VW UP!") })
        assertTrue(report.results.none { it.title.contains("4x Llantas") })
        assertTrue(report.results.none { it.title.contains("5x112") })
        assertTrue(report.discardedOffers.any { it.title.contains("RIAL Kodiak") && it.reason.contains("modelo", ignoreCase = true) })
        assertTrue(report.discardedOffers.any { it.title.contains("4x Llantas") && it.reason.contains("anclaje", ignoreCase = true) })

        val escapedQuoteReport = client.parseShoppingResultsDetailed(
            root = payload,
            query = """RIAL Milano 14\" 5x100""",
            observedAt = System.currentTimeMillis()
        )
        assertTrue(escapedQuoteReport.results.any { it.title == "RIAL Milano llanta" && it.isFitmentUnverified })
        assertTrue(escapedQuoteReport.results.none { it.title.contains("RIAL Kodiak") })
    }

    @Test
    fun wheelSearchKeepsMatchingModelWithoutCategoryWordsAndReportsExactFitmentMismatch() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {"title":"Autec Zenit","price":"123,15 €","extracted_price":123.15,"link":"https://shop.example/zenit","source":"Tienda Uno","condition":"new"},
                {"title":"Autec Zenit 5.5x14 Llanta","price":"133,12 €","extracted_price":133.12,"link":"https://shop.example/zenit-55x14","source":"Tienda Dos","condition":"new"},
                {"title":"Autec Xenos","price":"132,81 €","extracted_price":132.81,"link":"https://shop.example/xenos","source":"Tienda Tres","condition":"new"},
                {"title":"Llantas Autec Xenos","price":"540,00 €","extracted_price":540.00,"link":"https://shop.example/xenos-set","source":"Tienda Cuatro","condition":"new"},
                {"title":"4 Autec Zenit Felgen 5,5x14 4x100 SIL für Opel Corsa Aluräder","price":"432,19 €","extracted_price":432.19,"link":"https://shop.example/zenit-4x100","source":"Tienda Cinco","condition":"new"}
              ]
            }
            """.trimIndent()
        )
        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = "autec zenit 14 5x100",
            observedAt = 1_800_000_000_000L
        )

        assertEquals(setOf("Autec Zenit", "Autec Zenit 5.5x14 Llanta"),
            report.results.map { it.title }.toSet())
        val bareModel = report.results.single { it.title == "Autec Zenit" }
        assertTrue(bareModel.isFitmentUnverified)
        assertTrue(bareModel.isCategoryUnverified)
        val partialMeasures = report.results.single { it.title == "Autec Zenit 5.5x14 Llanta" }
        assertTrue(partialMeasures.isFitmentUnverified)
        assertFalse(partialMeasures.isCategoryUnverified)

        assertTrue(report.discardedOffers.any {
            it.title == "Autec Xenos" && it.reason.contains("Otro modelo", ignoreCase = true)
        })
        assertTrue(report.discardedOffers.any {
            it.title == "Llantas Autec Xenos" && it.reason.contains("Otro modelo", ignoreCase = true)
        })
        assertTrue(report.discardedOffers.any {
            it.title.startsWith("4 Autec Zenit Felgen") &&
                it.reason == "Anclaje distinto: 4x100 (buscaste 5x100)"
        })
        assertFalse(report.discardedOffers.any {
            it.title.startsWith("4 Autec Zenit Felgen") && it.reason == "No parece una llanta o rueda"
        })
    }

    @Test
    fun wheelSearchValidatesExplicitWidthAndEtAndLabelsMissingSpecs() {
        val client = SerpApiClient()
        val items = JSONArray()
        val titles = listOf(
            "Autec Zenit 5.5Jx14 ET35 5x100",
            "Autec Zenit 6Jx14 ET35 5x100",
            "Autec Zenit 5.5Jx14 ET38 5x100",
            "Autec Zenit 5.5Jx14 5x100",
            "Autec Zenit 5.5Jx15 ET35 5x100"
        )
        titles.forEachIndexed { index, title ->
            items.put(
                JSONObject()
                    .put("title", title)
                    .put("price", "140,00 €")
                    .put("extracted_price", 140.0 + index)
                    .put("link", "https://shop.example/wheel-fitment-$index")
                    .put("source", "Tienda $index")
                    .put("condition", "new")
            )
        }

        val report = client.parseShoppingResultsDetailed(
            root = JSONObject()
                .put("search_metadata", JSONObject().put("status", "Success"))
                .put("shopping_results", items),
            query = "autec zenit 5.5x14 ET35 5x100",
            observedAt = System.currentTimeMillis()
        )

        assertEquals(listOf(titles[0], titles[3]), report.results.map { it.title })
        assertFalse(report.results.single { it.title == titles[0] }.isFitmentUnverified)
        assertTrue("Missing ET must remain visible but unconfirmed", report.results.single { it.title == titles[3] }.isFitmentUnverified)

        assertTrue(report.discardedOffers.any {
            it.title == titles[1] && it.reason == "Ancho distinto: 6 pulgadas (buscaste 5.5 pulgadas)"
        })
        assertTrue(report.discardedOffers.any {
            it.title == titles[2] && it.reason == "ET distinto: 38 (buscaste ET35)"
        })
        assertTrue(report.discardedOffers.any {
            it.title == titles[4] && it.reason == "Diámetro distinto: 15 pulgadas (buscaste 14 pulgadas)"
        })
    }

    @Test
    fun wheelSearchRecognizesMultilingualCategoryTermsAndSeparatesSetsFromSingleWheels() {
        val client = SerpApiClient()
        val terms = listOf("llanta", "rueda", "aro", "Felgen", "Stahlfelge", "felge", "felgi", "felga", "Aluräder", "Alu", "jante", "cerchi", "wheel", "rim", "alloy")
        val items = org.json.JSONArray()
        terms.forEachIndexed { index, term ->
            items.put(
                JSONObject()
                    .put("title", "Autec Zenit 5.5x14 5x100 " + term)
                    .put("price", "145,00 €")
                    .put("extracted_price", 145.00)
                    .put("link", "https://shop.example/wheel-" + index)
                    .put("source", "Tienda " + index)
                    .put("condition", "new")
            )
        }
        val multilingual = client.parseShoppingResultsDetailed(
            root = JSONObject()
                .put("search_metadata", JSONObject().put("status", "Success"))
                .put("shopping_results", items),
            query = "autec zenit 14 5x100",
            observedAt = 1_800_000_000_000L
        )
        assertEquals("Every requested language term must be recognized", terms.size, multilingual.results.size)
        assertTrue(multilingual.results.none { it.isCategoryUnverified || it.isFitmentUnverified })

        val singleWheel = SerpApiShoppingResult(
            title = "Autec Zenit 5.5x14 5x100 alloy wheel",
            price = 133.12,
            storeName = "Tienda individual",
            sourceUrl = "https://shop.example/single-wheel",
            observedAt = 1_800_000_000_000L
        )
        val fourWheelSet = SerpApiShoppingResult(
            title = "4x Autec Zenit Felgen 5.5x14 5x100",
            price = 432.19,
            storeName = "Tienda juego",
            sourceUrl = "https://shop.example/four-wheel-set",
            observedAt = 1_800_000_000_000L
        )
        assertEquals(4, fourWheelSet.wheelSetCount)
        assertEquals(4, wheelSetCountForTitle("4 Autec Zenit Felgen 5.5x14 5x100"))
        assertEquals(null, wheelSetCountForTitle("4x100 Felgen 5.5x14 5x100"))
        assertEquals(null, singleWheel.wheelSetCount)
        val groups = groupOnlineShoppingResults(listOf(singleWheel, fourWheelSet))
        assertEquals("A set and a single wheel must be separate cards", 2, groups.size)
        val setGroup = groups.single { it.wheelSetCount == 4 }
        assertEquals(108.0475, setGroup.minPrice, 0.0001)
    }

    @Test
    fun genericWheelSearchKeepsGermanPolishAndKnownBrandCandidatesUnconfirmed() {
        val client = SerpApiClient()
        val items = org.json.JSONArray()
        val titles = listOf(
            "Stahlfelge 5x14 ET35 5x100 für VW Polo 1.2 TSI 2011-2014",
            "Komplet felgi 299 14x6 5x100 ET35",
            "OXXO Charon 5x100 57.1",
            "OXXO Novel Black",
            "Tapacubos 14\\\" 5x100",
            "Radkappen 14\\\" 5x100",
            "Kołpaki 14\\\" 5x100"
        )
        titles.forEachIndexed { index, title ->
            items.put(
                JSONObject()
                    .put("title", title)
                    .put("price", "92,29 €")
                    .put("extracted_price", 92.29 + index)
                    .put("link", "https://shop.example/wheel-candidate-$index")
                    .put("source", "Tienda $index")
                    .put("condition", "new")
            )
        }

        val report = client.parseShoppingResultsDetailed(
            root = JSONObject()
                .put("search_metadata", JSONObject().put("status", "Success"))
                .put("shopping_results", items),
            query = """llantas 14" 5x100""",
            observedAt = System.currentTimeMillis()
        )

        val titlesShown = report.results.map { it.title }.toSet()
        assertTrue(titlesShown.contains(titles[0]))
        assertTrue(titlesShown.contains(titles[1]))
        assertTrue(titlesShown.contains(titles[2]))
        assertTrue(titlesShown.contains(titles[3]))
        assertFalse(titlesShown.contains(titles[4]))
        assertFalse("German hubcaps must not be treated as rims", titlesShown.contains(titles[5]))
        assertFalse("Polish hubcaps must not be treated as rims", titlesShown.contains(titles[6]))

        val steelWheel = report.results.single { it.title == titles[0] }
        assertFalse(steelWheel.isCategoryUnverified)
        assertFalse(steelWheel.isFitmentUnverified)

        val polishWheel = report.results.single { it.title == titles[1] }
        assertFalse(polishWheel.isCategoryUnverified)
        assertFalse("Reversed 14x6 notation still confirms the requested 14-inch diameter", polishWheel.isFitmentUnverified)

        val modelOnlyCandidates = report.results.filter { it.title == titles[2] || it.title == titles[3] }
        assertTrue(modelOnlyCandidates.all { it.isCategoryUnverified })
        assertTrue(modelOnlyCandidates.all { it.isFitmentUnverified })
        assertTrue(report.discardedOffers.any {
            it.title == titles[4] && it.reason == "Accesorio que no es una llanta"
        })
    }

    @Test
    fun germanWheelListingsAreRecognizedAndWrongPcdIsReportedAsFitmentMismatch() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "4 Autec Zenit Felgen 5,5x14 4x100 ANTM für Dacia Logan Sandero",
                  "price": "432,19 €",
                  "extracted_price": 432.19,
                  "link": "https://shop.example.test/autec-4x100-set",
                  "source": "Tienda europea",
                  "condition": "new"
                },
                {
                  "title": "Autec Zenit Felgen 5,5x14 ANTM",
                  "price": "133,12 €",
                  "extracted_price": 133.12,
                  "link": "https://shop.example.test/autec-unknown-pcd",
                  "source": "Tienda europea",
                  "condition": "new"
                },
                {
                  "title": "Autec Zenit Aluräder 5,5x14 5x100",
                  "price": "145,00 €",
                  "extracted_price": 145.00,
                  "link": "https://shop.example.test/autec-5x100",
                  "source": "Tienda europea",
                  "condition": "new"
                }
              ]
            }
            """.trimIndent()
        )

        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = """autec zenit 14" 5x100""",
            observedAt = System.currentTimeMillis()
        )

        // A real rim with omitted PCD remains visible but is explicitly marked unconfirmed.
        val unconfirmed = report.results.first { it.title.contains("Felgen") }
        assertTrue(unconfirmed.isFitmentUnverified)

        // The 4x100 set must be rejected for the actual reason (PCD mismatch), not because its
        // German title failed the generic wheel/rim classifier.
        assertTrue(report.results.none { it.title.contains("4x100") })
        assertTrue(
            report.discardedOffers.any {
                it.title.contains("4x100") &&
                    it.reason.contains("Anclaje distinto", ignoreCase = true)
            }
        )
        assertFalse(
            report.discardedOffers.any {
                it.title.contains("4x100") &&
                    it.reason.contains("No parece una llanta o rueda", ignoreCase = true)
            }
        )

        // German "alloy wheels" (Aluräder) with a matching PCD are accepted as confirmed.
        val matching = report.results.first { it.title.contains("Aluräder") }
        assertFalse(matching.isFitmentUnverified)
    }

    @Test
    fun archiveBackfillAddsOldHistoryWithoutReplacingCurrentPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            val title = "Anker Prime 27K Power Bank"
            val originalUrl = "https://shop.example/products/anker-prime-27k"
            val liveOffer = SerpApiShoppingResult(
                title = title,
                price = 99.99,
                storeName = "Tienda directa",
                sourceUrl = originalUrl,
                observedAt = now,
                condition = "new"
            )
            assertTrue(repository.recordOnlineSearchResults(listOf(liveOffer), "GOOGLE_SHOPPING", title) > 0)

            val archivedAt = now - 1_550L * 24L * 60L * 60L * 1000L
            val archiveTimestamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(archivedAt))
            val archiveQuote = ArchivedPriceQuote(
                observedAt = archivedAt,
                price = 79.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = originalUrl,
                snapshotUrl = "https://web.archive.org/web/" + archiveTimestamp + "id_/" + originalUrl
            )
            assertEquals(1, repository.recordArchivedPriceHistory(
                expectedTitle = title,
                originalUrl = originalUrl,
                storeName = "Tienda directa",
                quotes = listOf(archiveQuote)
            ))

            val productId = database.productDao().getAllProductsSnapshot().single().id
            val observations = database.priceObservationDao().observeVerifiedHistory(productId).first()
            assertEquals(2, observations.size)
            val archived = observations.first { it.evidenceType == "archive" }
            assertEquals(archivedAt, archived.observedAt)
            assertTrue(archived.collectedAt >= now)
            assertEquals(originalUrl, archived.sourcePageUrl)
            assertEquals("Internet Archive", archived.sourceType)

            val product = withTimeout<com.example.data.model.Product>(10_000L) {
                repository.allProductsStream.first { list ->
                    list.any { it.id == productId && it.priceHistory.any { point -> point.eventLabel == "Internet Archive" } }
                }.first { it.id == productId }
            }
            assertEquals("Historical archive data must never replace the current quote", 99.99, product.currentPrice, 0.001)
            assertTrue(product.priceHistory.any { it.timestamp == archivedAt && it.price == 79.99 })
        } finally {
            database.close()
        }
    }

    @Test
    fun verifiedLivePriceBeforePolicyCutoffRemainsVisibleAsStaleSavedPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            val savedAt = now - 7L * 24L * 60L * 60L * 1000L
            val title = "Anker Prime 27K Power Bank"
            val productId = "old-saved-live-price"
            val sourceUrl = "https://www.amazon.es/dp/B0OLDPRICE"
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Anker",
                    category = "HOME",
                    currentPrice = 999.99,
                    athMinPrice = 999.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "",
                    athMaxPrice = 999.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon_es",
                    storeUrl = sourceUrl,
                    ean = "",
                    isPriceVerified = false
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "amazon_es",
                    price = 79.99,
                    currency = "EUR",
                    observedAt = savedAt,
                    sourceUrl = sourceUrl,
                    sourceName = "Google Shopping",
                    isVerified = true,
                    verificationNote = "Previously saved source-backed quote; stale and awaiting revalidation.",
                    productTitle = title,
                    storeName = "Amazon España",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = savedAt,
                    evidenceType = "live"
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val product = withTimeout<com.example.data.model.Product>(10_000L) {
                repository.allProductsStream.first { products ->
                    products.any { it.id == productId && it.savedHistoricalPrice == 79.99 }
                }.first { it.id == productId }
            }
            assertFalse("A pre-policy saved quote must not count as a current verified quote", product.isPriceVerified)
            assertEquals("Keep the catalogue placeholder separate from the saved historical quote",
                999.99, product.currentPrice, 0.001)
            assertEquals(79.99, product.savedHistoricalPrice!!, 0.001)
            assertEquals(savedAt, product.savedHistoricalPriceObservedAt)
            assertEquals("Google Shopping", product.savedHistoricalPriceSource)
            assertTrue("A pre-policy row must not affect calculated history", product.priceHistory.isEmpty())
            assertEquals(
                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION,
                product.priceDisplayStatus
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun archiveOnlySavedPriceIsShownAsHistoricalAndNotAsCurrentQuote() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            val title = "Anker Prime 27K Power Bank"
            val originalUrl = "https://shop.example/products/anker-prime-27k"
            val productId = "archive-only-anker"
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Anker",
                    category = "HOME",
                    currentPrice = 999.99,
                    athMinPrice = 999.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "",
                    athMaxPrice = 999.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "unknown_store",
                    storeUrl = originalUrl,
                    ean = "",
                    isPriceVerified = false
                )
            )

            val archivedAt = now - 200L * 24L * 60L * 60L * 1000L
            val archiveTimestamp = java.text.SimpleDateFormat(
                "yyyyMMddHHmmss",
                java.util.Locale.ROOT
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(archivedAt))
            val quote = ArchivedPriceQuote(
                observedAt = archivedAt,
                price = 79.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = originalUrl,
                snapshotUrl = "https://web.archive.org/web/" + archiveTimestamp + "id_/" + originalUrl
            )

            assertEquals(
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = originalUrl,
                    storeName = "Tienda directa",
                    quotes = listOf(quote)
                )
            )

            val product = withTimeout<com.example.data.model.Product>(10_000L) {
                repository.allProductsStream.first { products ->
                    products.any { it.id == productId && it.savedHistoricalPrice == 79.99 }
                }.first { it.id == productId }
            }
            assertFalse(product.isPriceVerified)
            assertEquals("Archive history must not overwrite the catalogue's current-price field",
                999.99, product.currentPrice, 0.001)
            assertEquals(79.99, product.savedHistoricalPrice!!, 0.001)
            assertEquals(archivedAt, product.savedHistoricalPriceObservedAt)
            assertEquals(
                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION,
                product.priceDisplayStatus
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun archiveHistoryDoesNotAttachToDifferentQueryVariant() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Anker Prime 27K Power Bank"
            val storedVariantUrl = "https://shop.example/products/anker-prime-27k?variant=512&color=black&utm_source=affiliate"
            val archivedVariantUrl = "https://shop.example/products/anker-prime-27k?variant=256"
            database.productDao().insert(
                ProductEntity(
                    id = "anker_variant_512",
                    title = title,
                    brand = "Anker",
                    category = "ACCESSORIES",
                    currentPrice = 99.99,
                    athMinPrice = 0.0,
                    athMinDate = "",
                    athMinStore = "",
                    athMaxPrice = 0.0,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "Tienda directa",
                    storeUrl = storedVariantUrl,
                    ean = "",
                    isPriceVerified = false
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            val archivedAt = now - 200L * 24L * 60L * 60L * 1000L
            val archiveTimestamp = java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.ROOT
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(archivedAt))
            val quote = ArchivedPriceQuote(
                observedAt = archivedAt,
                price = 79.99,
                currency = "EUR",
                productTitle = title,
                originalUrl = archivedVariantUrl,
                snapshotUrl = "https://web.archive.org/web/" + archiveTimestamp + "id_/" + archivedVariantUrl
            )

            assertEquals(
                "A 256 GB archive capture must not attach to the saved 512 GB variant",
                0,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = archivedVariantUrl,
                    storeName = "Tienda directa",
                    quotes = listOf(quote)
                )
            )
            assertTrue(
                "A rejected variant mismatch must not persist an observation",
                database.priceObservationDao().observeVerifiedHistory("anker_variant_512").first().isEmpty()
            )

            // Equivalent URLs with reordered parameters and a known tracking key must still match.
            val equivalentVariantUrl = "https://shop.example/products/anker-prime-27k?color=black&variant=512"
            val validQuote = quote.copy(
                price = 89.99,
                originalUrl = equivalentVariantUrl,
                snapshotUrl = "https://web.archive.org/web/" + archiveTimestamp + "id_/" + equivalentVariantUrl
            )
            assertEquals(
                "Query ordering and tracking parameters must not prevent valid history from being attached",
                1,
                repository.recordArchivedPriceHistory(
                    expectedTitle = title,
                    originalUrl = equivalentVariantUrl,
                    storeName = "Tienda directa",
                    quotes = listOf(validQuote)
                )
            )
            assertEquals(
                1,
                database.priceObservationDao().observeVerifiedHistory("anker_variant_512").first().size
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun accessoryOffersForAnkerPrimeRemainVisibleButAreNotSavedAsTheMainBattery() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val timestamp = System.currentTimeMillis()
            val battery = SerpApiShoppingResult(
                title = "Anker Prime 20000mAh Power Bank 200W",
                price = 149.99,
                storeName = "Amazon.es",
                sourceUrl = "https://www.google.com/shopping/product/anker-prime-battery",
                observedAt = timestamp,
                condition = "new"
            )
            val accessory = SerpApiShoppingResult(
                title = "Funda Protectora para Anker Prime 20000mAh Power Bank",
                price = 9.29,
                storeName = "Amazon.es - Seller",
                sourceUrl = "https://www.google.com/shopping/product/anker-prime-case",
                observedAt = timestamp,
                condition = "new"
            )
            assertTrue("The cheap case must be recognized as an accessory", accessory.isAccessoryOffer)

            val saved = repository.recordOnlineSearchResults(
                results = listOf(battery, accessory),
                sourceType = "GOOGLE_SHOPPING",
                searchQuery = "Anker Prime"
            )

            assertEquals("Only the main battery may be added to Mercado", 1, saved)
            val products = database.productDao().getAllProductsSnapshot()
            assertEquals(1, products.size)
            assertEquals("Anker Prime 20000mAh Power Bank 200W", products.single().title)
            assertTrue(
                "The accessory price must never be recorded as a verified battery observation",
                database.priceObservationDao().observeVerifiedHistory(products.single().id).first()
                    .none { it.price == 9.29 }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun fitmentUnconfirmedWheelCandidatesAreNotSavedToMercadoOrHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val result = SerpApiShoppingResult(
                title = "RIAL Milano llanta",
                price = 94.99,
                storeName = "Tienda de llantas",
                sourceUrl = "https://shop.example.test/rial-milano",
                observedAt = System.currentTimeMillis(),
                condition = "new",
                isFitmentUnverified = true
            )

            val saved = repository.recordOnlineSearchResults(
                results = listOf(result),
                sourceType = "GOOGLE_SHOPPING",
                searchQuery = """llantas 14" 5x100"""
            )

            assertEquals(0, saved)
            assertTrue(database.productDao().getAllProductsSnapshot().isEmpty())
            assertEquals(0, database.priceObservationDao().getVerifiedObservationCount())

            // The Google Product API comparison path must enforce the same fitment gate;
            // otherwise a result excluded from live search could still enter the price history.
            val savedProduct = ProductEntity(
                id = "rial-milano-wheel-comparison",
                title = "RIAL Milano llanta",
                brand = "RIAL",
                category = "SPORTS",
                currentPrice = 0.0,
                athMinPrice = 0.0,
                athMinDate = "",
                athMinStore = "",
                athMaxPrice = 0.0,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "Tienda de llantas",
                storeUrl = "https://shop.example.test/rial-milano",
                ean = "",
                isPriceVerified = false
            )
            database.productDao().insert(savedProduct)
            assertEquals(
                "Fitment-unverified wheels must not become persisted comparison offers",
                0,
                repository.recordProductComparisonOffers(
                    productId = savedProduct.id,
                    expectedTitle = savedProduct.title,
                    offers = listOf(result.copy(isFitmentUnverified = true))
                )
            )
            assertEquals(
                0,
                database.priceObservationDao().getObservationCount(savedProduct.id)
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun googleProductOffersKeepValidStoresAndRejectUsedForeignAndMonthlyFixtures() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "product_results": {
                "title": "Sony WH-1000XM5 Wireless Headphones",
                "stores": [
                  {"name":"PcComponentes","title":"Sony WH-1000XM5","price":"299,99 €","extracted_price":299.99,"link":"https://www.pccomponentes.com/sony-wh1000xm5","condition":"new"},
                  {"name":"Amazon.es","title":"Sony WH-1000XM5","price":"309,00 €","extracted_price":309.00,"link":"https://www.google.com/shopping/product/987654321?gl=es"},
                  {"name":"Wallapop","title":"Sony WH-1000XM5","price":"120,00 €","extracted_price":120.00,"link":"https://es.wallapop.com/item/sony","condition":"used"},
                  {"name":"Tienda cuotas","title":"Sony WH-1000XM5 19,99 €/mes","price":"19,99 €","extracted_price":19.99,"link":"https://shop.example.test/monthly"},
                  {"name":"Foreign Shop","title":"Sony WH-1000XM5","price":"$250.00","extracted_price":250.00,"link":"https://shop.example.test/usd"},
                  {"name":"Structured Foreign Shop","title":"Sony WH-1000XM5","price":"99.99","extracted_price":99.99,"currency":"USD","link":"https://shop.example.test/structured-usd"}
                ]
              }
            }
            """.trimIndent()
        )

        val offers = client.parseGoogleProductOffers(
            root = payload,
            expectedTitle = "Sony WH-1000XM5",
            productId = "987654321",
            observedAt = 1234L
        )

        assertEquals("Offers parsed: ${offers.map { "${it.storeName}=${it.price} (${it.linkType})" }}", 2, offers.size)
        assertEquals(listOf("PcComponentes", "Amazon.es"), offers.map { it.storeName })
        assertEquals("directo", offers.first().linkType)
        assertEquals("Google Shopping", offers.last().linkType)
        assertTrue(offers.all { it.price > 0.0 && it.observedAt == 1234L })
    }

    @Test
    fun `Hall-effect keyboard searches accept HE TMR and magnetic keyboards but reject unrelated Hall titles`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Keychron Q1 HE Teclado Inalámbrico",
                      "price": "189,99 €",
                      "extracted_price": 189.99,
                      "link": "https://keychron.com/q1-he",
                      "source": "Keychron"
                    },
                    {
                      "title": "DarkBeacon Flux87 HE Teclado Gaming TKL Rapid Trigger Interruptores Magnéticos",
                      "price": "129,99 €",
                      "extracted_price": 129.99,
                      "link": "https://shop.example.test/flux87",
                      "source": "Tienda gaming"
                    },
                    {
                      "title": "MonsGeek M1 V5 TMR Magnetic Keyboard Black",
                      "price": "159,99 €",
                      "extracted_price": 159.99,
                      "link": "https://monsgeek.com/m1-v5",
                      "source": "MonsGeek"
                    },
                    {
                      "title": "Hall Of Fame - Piano y teclados - Green Musicians",
                      "price": "4,91 €",
                      "extracted_price": 4.91,
                      "link": "https://shop.example.test/hall-of-fame",
                      "source": "Green Musicians"
                    },
                    {
                      "title": "GEON Raw HE Magnetic Linear Switches 50.0gf",
                      "price": "4,27 €",
                      "extracted_price": 4.27,
                      "link": "https://shop.example.test/geon-switches",
                      "source": "Tienda de teclados"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "teclado HAL",
            observedAt = System.currentTimeMillis()
        )

        assertEquals(
            listOf(
                "DarkBeacon Flux87 HE Teclado Gaming TKL Rapid Trigger Interruptores Magnéticos",
                "MonsGeek M1 V5 TMR Magnetic Keyboard Black",
                "Keychron Q1 HE Teclado Inalámbrico"
            ),
            report.results.map { it.title }
        )
        assertTrue(report.discardedOffers.any { it.title.contains("Hall Of Fame") })
        assertTrue(report.discardedOffers.any { it.title.contains("GEON Raw") })
    }

    @Test
    fun `Google Product API parses only dated EUR price insights for a matching catalog item`() {
        val client = SerpApiClient()
        val observedAt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("Oct 15, 2026")!!.time + 12L * 60L * 60L * 1000L
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "product_results": {
                "title": "Sony DualSense Wireless Controller PS5",
                "stores": [
                  {
                    "name": "Amazon España",
                    "title": "Sony DualSense Wireless Controller PS5",
                    "price": "74,99 €",
                    "extracted_price": 74.99,
                    "link": "https://www.amazon.es/dp/DUALSENSETEST"
                  }
                ],
                "price_insights": {
                  "price_history": true,
                  "currency": "EUR",
                  "stores": [
                    {
                      "name": "Amazon España",
                      "prices": [
                        {"date": "Sep 10, 2026", "price": 79.99},
                        {"date": "Oct 14, 2026", "price": 74.99}
                      ]
                    },
                    {
                      "name": "PcComponentes",
                      "prices": [
                        {"date": "Oct 13, 2026", "price": 76.50},
                        {"date": "Oct 16, 2026", "price": 1.00}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val parsed = client.parseGoogleProductDetails(
            root = payload,
            expectedTitle = "Sony DualSense Wireless Controller PS5",
            productId = "123456789",
            observedAt = observedAt
        )

        assertEquals(1, parsed.offers.size)
        assertTrue(parsed.priceHistoryAvailable)
        assertEquals(3, parsed.priceInsights.size)
        assertTrue(parsed.priceInsights.all { it.currency == "EUR" && it.observedAt < observedAt })
        assertEquals(
            listOf("Amazon España", "PcComponentes", "Amazon España"),
            parsed.priceInsights.map { it.storeName }
        )
        assertEquals(79.99, parsed.priceInsights.first().price, 0.001)

        val foreignCurrency = JSONObject(payload.toString()).apply {
            getJSONObject("product_results").getJSONObject("price_insights").put("currency", "USD")
        }
        val rejected = client.parseGoogleProductDetails(
            root = foreignCurrency,
            expectedTitle = "Sony DualSense Wireless Controller PS5",
            productId = "123456789",
            observedAt = observedAt
        )
        assertTrue(rejected.priceInsights.isEmpty())
    }

    @Test
    fun `Google historical insights are saved separately and never replace current price`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            val productId = "sony-dualsense-insight-test"
            val title = "Sony DualSense Wireless Controller PS5"
            database.productDao().insert(
                ProductEntity(
                    id = productId,
                    title = title,
                    brand = "Sony",
                    category = "GAMING",
                    currentPrice = 74.99,
                    athMinPrice = 74.99,
                    athMinDate = "Historial en construcción",
                    athMinStore = "Amazon España",
                    athMaxPrice = 74.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon_es",
                    storeUrl = "https://www.google.com/shopping/product/123456789",
                    ean = "",
                    isPriceVerified = true,
                    googleProductId = "123456789"
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = "amazon_es",
                    price = 74.99,
                    currency = "EUR",
                    observedAt = now,
                    sourceUrl = "https://www.amazon.es/dp/DUALSENSETEST",
                    sourceName = "Google Shopping",
                    isVerified = true,
                    verificationNote = "Precio actual usado como control del test.",
                    productTitle = title,
                    storeName = "Amazon España",
                    sourceType = "Google Shopping",
                    linkType = "directo",
                    collectedAt = now,
                    evidenceType = "live"
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val historicalAt = now - 3L * 24L * 60L * 60L * 1000L
            val saved = repository.recordGooglePriceInsightsHistory(
                productId = productId,
                expectedTitle = title,
                googleCatalogId = "123456789",
                insights = listOf(
                    com.example.data.remote.SerpApiPriceInsight(
                        productTitle = title,
                        storeName = "PcComponentes",
                        price = 69.99,
                        currency = "EUR",
                        observedAt = historicalAt
                    )
                )
            )

            assertEquals(1, saved)
            val product = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { products ->
                    products.any { candidate ->
                        candidate.id == productId &&
                            candidate.priceHistory.any { it.eventLabel?.contains("price_insights", ignoreCase = true) == true }
                    }
                }
            }.first { it.id == productId }

            assertEquals("Historical provider data must never replace the live current price", 74.99, product.currentPrice, 0.001)
            assertTrue(product.priceHistory.any {
                it.eventLabel?.contains("price_insights", ignoreCase = true) == true &&
                    it.timestamp == historicalAt && it.price == 69.99
            })
            assertTrue(product.priceHistory.any { it.eventLabel == "Google Shopping" && it.price == 74.99 })
        } finally {
            database.close()
        }
    }

    @Test
    fun onlineShoppingSearchKeepsSafeEuroOffersAndSortsByPrice() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Auriculares Sony WH-1000XM5",
                  "price": "89,99 €",
                  "extracted_price": 89.99,
                  "link": "https://www.amazon.es/dp/B0TEST123",
                  "source": "Amazon.es",
                  "thumbnail": "https://images.example.test/sony.jpg",
                  "delivery": "Entrega gratis",
                  "rating": 4.5,
                  "reviews": 123
                },
                {
                  "title": "Auriculares Sony WH-1000XM5",
                  "price": "79,99 €",
                  "extracted_price": 79.99,
                  "product_link": "https://www.google.com/shopping/product/987654321?gl=es",
                  "source": "PcComponentes"
                },
                {
                  "title": "Auriculares Sony WH-1000XM5",
                  "price": "$59.99",
                  "extracted_price": 59.99,
                  "link": "https://shop.example.test/item",
                  "source": "Example USD"
                },
                {
                  "title": "Auriculares Sony WH-1000XM5",
                  "price": "49,99 €",
                  "extracted_price": 49.99,
                  "link": "http://insecure.example.test/item",
                  "source": "Unsafe HTTP"
                }
              ]
            }
            """
        )

        val results = client.parseShoppingResults(payload, observedAt = 123L)

        assertEquals("Eligible offers survive whether the URL is direct, HTTP, or Google Shopping", 3, results.size)
        assertEquals("Unsafe HTTP", results[0].storeName)
        assertEquals(49.99, results[0].price, 0.001)
        assertEquals("directo", results[0].linkType)
        assertEquals("PcComponentes", results[1].storeName)
        assertEquals(79.99, results[1].price, 0.001)
        assertTrue(results[1].isGoogleShoppingProductLink)
        assertEquals("Google Shopping", results[1].linkType)
        assertEquals("Amazon.es", results[2].storeName)
        assertEquals(89.99, results[2].price, 0.001)
        assertFalse(results[2].isGoogleShoppingProductLink)
        assertTrue(results[2].isAmazonOffer)
        assertEquals(123L, results[2].observedAt)
        assertEquals("https://images.example.test/sony.jpg", results[2].thumbnailUrl)
    }

    @Test
    fun `manual Google Shopping search filters unrelated products and reports why offers were rejected`() {
        val client = SerpApiClient()
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Sony WH1000XM5 Wireless Noise Canceling Headphones",
                  "price": "249,99 €",
                  "extracted_price": 249.99,
                  "link": "https://www.amazon.es/dp/B0SONYTEST",
                  "source": "Amazon.es"
                },
                {
                  "title": "Anker Solix portable power station",
                  "price": "267,41 €",
                  "extracted_price": 267.41,
                  "product_link": "https://www.google.com/search?prds=anker",
                  "source": "Cctv & Alarmas"
                },
                {
                  "title": "Sony WH-1000XM5 headphones",
                  "price": "239,99 €",
                  "extracted_price": 239.99,
                  "product_link": "https://www.google.com/shopping/product/SONY123",
                  "source": "Fnac"
                },
                {
                  "title": "Sony WH-1000XM5 headphones",
                  "price": "$239.00",
                  "extracted_price": 239.00,
                  "link": "https://shop.example.test/sony",
                  "source": "Foreign currency"
                },
                {
                  "title": "Sony WH-1000XM5 headphones",
                  "price": "229,99 €",
                  "extracted_price": 229.99,
                  "link": "http://unsafe.example.test/sony",
                  "source": "Unsafe HTTP"
                }
              ]
            }
            """.trimIndent()
        )

        val report = client.parseShoppingResultsDetailed(
            root = payload,
            query = "Sony WH-1000XM5",
            observedAt = 123L
        )

        assertEquals(5, report.rawResultCount)
        assertEquals("Matching Google Shopping and HTTP offers remain available", 3, report.results.size)
        val amazonResult = report.results.single { it.storeName == "Amazon.es" }
        assertEquals("Sony WH1000XM5 Wireless Noise Canceling Headphones", amazonResult.title)
        assertEquals(249.99, amazonResult.price, 0.001)
        assertEquals("directo", amazonResult.linkType)

        val googleResult = report.results.single { it.storeName == "Fnac" }
        assertEquals(239.99, googleResult.price, 0.001)
        assertTrue(googleResult.isGoogleShoppingProductLink)
        assertEquals("Google Shopping", googleResult.linkType)

        val httpResult = report.results.single { it.storeName == "Unsafe HTTP" }
        assertEquals(229.99, httpResult.price, 0.001)
        assertEquals("directo", httpResult.linkType)

        assertEquals(1, report.rejectedAsIrrelevant)
        assertEquals(1, report.rejectedForPriceOrCurrency)
        assertEquals(0, report.rejectedForLink)
        assertTrue(report.emptyResultMessage("Sony WH-1000XM5").isNotBlank())
    }

    @Test
    fun `specific product search rejects other brands and different seasonal years`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Calendario de Adviento Académie Scientifique de Beauté 2024",
                      "price": "55,50 €",
                      "extracted_price": 55.50,
                      "link": "https://www.notino.es/example",
                      "source": "Notino.es"
                    },
                    {
                      "title": "Douglas Skin Focus Adventskalender 2026 Frauen Beauty Damen Advent Kalender",
                      "price": "119,99 €",
                      "extracted_price": 119.99,
                      "link": "https://www.douglas.es/example",
                      "source": "Douglas"
                    },
                    {
                      "title": "Galaxy Calendario de Adviento chocolate 110g",
                      "price": "2,80 €",
                      "extracted_price": 2.80,
                      "link": "https://www.example.es/galaxy",
                      "source": "Tienda de chocolate"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Calendario de Adviento Académie Scientifique de Beauté 2024",
            observedAt = 123L
        )

        assertEquals(
            "Only the matching brand/product edition should survive a specific query",
            listOf("Calendario de Adviento Académie Scientifique de Beauté 2024"),
            report.results.map { it.title }
        )
        assertEquals(2, report.rejectedAsIrrelevant)
        assertTrue(report.discardedOffers.any { it.title.contains("Douglas") })
        assertTrue(report.discardedOffers.any { it.title.contains("Galaxy") })
    }

    @Test
    fun `specific model search rejects a different model from the same brand`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Sony WH-1000XM5 auriculares inalámbricos",
                      "price": "249,99 €",
                      "extracted_price": 249.99,
                      "link": "https://www.example.es/sony-xm5",
                      "source": "Tienda A"
                    },
                    {
                      "title": "Sony WH-1000XM4 auriculares inalámbricos",
                      "price": "199,99 €",
                      "extracted_price": 199.99,
                      "link": "https://www.example.es/sony-xm4",
                      "source": "Tienda B"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Sony WH-1000XM5 auriculares inalámbricos",
            observedAt = 123L
        )

        assertEquals(
            "A matching brand must not make a different model acceptable",
            listOf("Sony WH-1000XM5 auriculares inalámbricos"),
            report.results.map { it.title }
        )
        assertEquals(1, report.rejectedAsIrrelevant)
        assertTrue(report.discardedOffers.any { it.title.contains("WH-1000XM4") })
    }

    @Test
    fun `specific Samsung Galaxy search rejects adjacent model numbers`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Samsung Galaxy S26 Ultra 512GB",
                      "price": "1.299,00 €",
                      "extracted_price": 1299.00,
                      "link": "https://www.example.es/s26",
                      "source": "Tienda A"
                    },
                    {
                      "title": "Samsung Galaxy S25 Ultra 512GB",
                      "price": "1.099,00 €",
                      "extracted_price": 1099.00,
                      "link": "https://www.example.es/s25",
                      "source": "Tienda B"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Samsung Galaxy S26 Ultra 512GB",
            observedAt = 123L
        )

        assertEquals(
            "Same brand and family are not enough when the model number differs",
            listOf("Samsung Galaxy S26 Ultra 512GB"),
            report.results.map { it.title }
        )
        assertEquals(1, report.rejectedAsIrrelevant)
        assertTrue(report.discardedOffers.any { it.title.contains("S25") })
    }

    @Test
    fun `condition metadata marks used and refurbished offers for the opt-in filter`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Sony WH-1000XM5 Wireless Headphones",
                      "price": "199,99 €",
                      "extracted_price": 199.99,
                      "second_hand_condition": "used",
                      "source": "Amazon.es",
                      "link": "https://www.amazon.es/dp/USEDTEST"
                    },
                    {
                      "title": "Sony WH-1000XM5 Wireless Headphones",
                      "price": "219,99 €",
                      "extracted_price": 219.99,
                      "condition": "refurbished",
                      "source": "PcComponentes",
                      "link": "https://www.pccomponentes.com/refurbished-test"
                    },
                    {
                      "title": "Sony WH-1000XM5 Wireless Headphones",
                      "price": "249,99 €",
                      "extracted_price": 249.99,
                      "condition": "new",
                      "source": "Amazon.es",
                      "link": "https://www.amazon.es/dp/NEWTEST"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Sony WH-1000XM5",
            observedAt = 123L
        )

        assertEquals(3, report.rawResultCount)
        assertEquals(3, report.results.size)
        assertEquals(2, report.results.count { it.isSecondHand })
        assertEquals(249.99, report.results.single { !it.isSecondHand }.price, 0.001)
        assertEquals(0, report.rejectedOther)
    }

    @Test
    fun `missing shopping links use the known retailer search page instead of Google`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Sony WH-1000XM5 Wireless Headphones",
                      "price": "229,99 €",
                      "extracted_price": 229.99,
                      "source": "Fnac"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Sony WH-1000XM5",
            observedAt = 123L
        )

        assertEquals(1, report.results.size)
        val result = report.results.single()
        assertTrue(result.sourceUrl.startsWith("https://www.fnac.es/SearchResult/ResultList.aspx?Search="))
        assertEquals("Búsqueda en tienda", result.linkType)
        assertEquals("Fnac", result.storeName)
        assertEquals(0, report.rejectedForLink)
    }

    @Test
    fun `Google Shopping intermediary for a known brand falls back to its store search`() {
        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = JSONObject(
                """
                {
                  "search_metadata": {"status": "Success"},
                  "shopping_results": [
                    {
                      "title": "Anker Prime 27K Power Bank",
                      "price": "129,99 €",
                      "extracted_price": 129.99,
                      "source": "Anker",
                      "link": "https://www.google.com/search?tbm=shop&q=Anker+Prime"
                    }
                  ]
                }
                """.trimIndent()
            ),
            query = "Anker Prime 27K",
            observedAt = 123L
        )

        assertEquals(1, report.results.size)
        val result = report.results.single()
        assertTrue(result.sourceUrl.startsWith("https://www.anker.com/eu-en/search?q="))
        assertEquals("Búsqueda en tienda", result.linkType)
        assertEquals(0, report.rejectedForLink)
    }

    @Test
    fun `Google search URL is labelled as an indirect Shopping link`() {
        val result = com.example.data.remote.SerpApiShoppingResult(
            title = "Cargador Anker",
            price = 59.95,
            storeName = "Apple",
            sourceUrl = "https://www.google.com/search?prds=anker",
            observedAt = 123L
        )
        assertTrue(result.isGoogleShoppingProductLink)
        assertEquals("Google Shopping", result.linkType)
    }

    @Test
    fun `retailer destination preserves Google catalog pages and routes generic links`() {
        val catalog = com.example.data.remote.retailerDestinationForOffer(
            "https://www.google.com/shopping/product/123456?gl=es", "Fnac", "Sony WH-1000XM5"
        )
        assertEquals("https://www.google.com/shopping/product/123456?gl=es", catalog)
        val fnac = com.example.data.remote.retailerDestinationForOffer(
            "https://www.google.com/search?tbm=shop&q=Sony", "Fnac", "Sony WH-1000XM5"
        )
        assertTrue(fnac.orEmpty().startsWith("https://www.fnac.es/SearchResult/ResultList.aspx?Search="))
        val anker = com.example.data.remote.retailerDestinationForOffer(
            "https://www.google.com/search?tbm=shop&q=Anker", "Anker", "Anker Prime 27K"
        )
        assertTrue(anker.orEmpty().startsWith("https://www.anker.com/eu-en/search?q="))
    }


    @Test
    fun `insecure retailer links fall back to a safe HTTPS destination`() {
        val insecureRedirect = com.example.data.remote.extractDirectRetailerUrl(
            "https://www.google.com/url?q=http%3A%2F%2Fwww.pccomponentes.com%2Fproducto"
        )
        assertEquals(
            "An embedded HTTP URL must not be accepted as a safe direct destination",
            null,
            insecureRedirect
        )

        val retailerFallback = com.example.data.remote.retailerDestinationForOffer(
            "http://www.pccomponentes.com/producto",
            "PcComponentes",
            "Sony WH-1000XM5"
        )
        assertTrue(
            "Known sellers should use their HTTPS store search page when the supplied link is HTTP",
            retailerFallback.orEmpty().startsWith("https://www.pccomponentes.com/")
        )
    }

    @Test
    fun `online search stores valid offers once per store and day and marks low outliers`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = scope
            )
            val now = System.currentTimeMillis()
            val results = listOf(
                com.example.data.remote.SerpApiShoppingResult(
                    title = "Sony WH-1000XM5 Wireless Headphones",
                    price = 229.99,
                    storeName = "PcComponentes",
                    sourceUrl = "https://www.google.com/shopping/product/SONYWH1000XM5",
                    observedAt = now
                ),
                com.example.data.remote.SerpApiShoppingResult(
                    title = "Sony WH-1000XM5 Wireless Headphones",
                    price = 239.99,
                    storeName = "Amazon.es",
                    sourceUrl = "https://www.amazon.es/dp/B0SONYTEST",
                    observedAt = now
                ),
                com.example.data.remote.SerpApiShoppingResult(
                    title = "Sony WH-1000XM5 Wireless Headphones",
                    price = 12.00,
                    storeName = "Fnac España",
                    sourceUrl = "https://www.fnac.es/sony-wh-1000xm5",
                    observedAt = now
                ),
                com.example.data.remote.SerpApiShoppingResult(
                    title = "Sony WH-1000XM5 segunda mano",
                    price = 119.99,
                    storeName = "MediaMarkt España",
                    sourceUrl = "https://www.mediamarkt.es/sony-wh-1000xm5",
                    observedAt = now
                )
            )

            val suspiciousRedmi = com.example.data.remote.SerpApiShoppingResult(
                title = "Xiaomi Redmi 17 5G 4GB 128GB Negro",
                price = 9.90,
                storeName = "Rentik",
                sourceUrl = "https://www.rentik.com/xiaomi-redmi-17",
                observedAt = now
            )
            val rentalPhoneWithNormalPrice = suspiciousRedmi.copy(
                price = 199.99,
                sourceUrl = "https://www.rentik.com/xiaomi-redmi-17"
            )
            val normalNewPhone = suspiciousRedmi.copy(
                price = 199.99,
                storeName = "Amazon.es",
                sourceUrl = "https://www.amazon.es/dp/REDFEATURETEST"
            )
            val cheapLegitimateAccessory = com.example.data.remote.SerpApiShoppingResult(
                title = "UGREEN USB 2.0 Hub 4 Puertos",
                price = 3.49,
                storeName = "Wasserman.eu",
                sourceUrl = "https://www.wasserman.eu/ugreen-usb-hub",
                observedAt = now
            )
            val suspiciousPowerBank = com.example.data.remote.SerpApiShoppingResult(
                title = "Anker Power Bank 20000mAh 65W",
                price = 2.99,
                storeName = "Amazon.es",
                sourceUrl = "https://www.amazon.es/dp/SUSPICIOUSPOWERBANK",
                observedAt = now
            )
            val filteredResults = repository.filterOnlineSearchResults(
                listOf(
                    suspiciousRedmi,
                    rentalPhoneWithNormalPrice,
                    normalNewPhone,
                    cheapLegitimateAccessory,
                    suspiciousPowerBank
                )
            )
            val prePolicySnapshot = normalNewPhone.copy(observedAt = 1_780_000_000_000L)
            assertFalse(
                "A result stamped before 13 October 2026 must not enter live history",
                repository.filterOnlineSearchResults(listOf(prePolicySnapshot)).isNotEmpty()
            )
            assertTrue(
                "A positive-price listing is not discarded by a hard-coded device price floor",
                filteredResults.any { it.title.contains("Redmi 17", ignoreCase = true) && it.price == 9.90 }
            )
            assertTrue(
                "A seller name alone is not proof of renting; explicit condition/title signals decide",
                filteredResults.any { it.storeName.equals("Rentik", ignoreCase = true) }
            )
            assertTrue(
                "A plausibly priced new phone from an ordinary retailer remains eligible",
                filteredResults.any { it.storeName == "Amazon.es" && it.title.contains("Redmi 17", ignoreCase = true) }
            )
            assertTrue(
                "Low-priced real accessories should remain eligible",
                filteredResults.any { it.title.contains("UGREEN USB 2.0 Hub") }
            )
            assertTrue(
                "A sub-€5 positive price is retained; the relative median rule decides suspicion",
                filteredResults.any { it.title.contains("Power Bank", ignoreCase = true) && it.price == 2.99 }
            )

            val excludedSellerResults = listOf(
                normalNewPhone.copy(storeName = "Wallapop", sourceUrl = "https://www.wallapop.com/xiaomi"),
                normalNewPhone.copy(storeName = "Cash Converters España", sourceUrl = "https://www.cashconverters.es/xiaomi"),
                normalNewPhone.copy(storeName = "Vinted", sourceUrl = "https://www.vinted.es/xiaomi"),
                normalNewPhone.copy(storeName = "CeX España", sourceUrl = "https://www.webuy.com/xiaomi"),
                normalNewPhone.copy(
                    title = "Xiaomi Redmi 17 5G cuota 9,90 €/mes",
                    storeName = "Tienda oficial",
                    sourceUrl = "https://www.example.com/xiaomi-cuota"
                )
            )
            val filteredMarketplaceResults = repository.filterOnlineSearchResults(excludedSellerResults, "Xiaomi Redmi 17")
            assertEquals("Listings are not discarded merely because of seller name", 4, filteredMarketplaceResults.size)
            assertTrue("Monthly payment listings are still excluded", filteredMarketplaceResults.none {
                it.title.contains("cuota", ignoreCase = true)
            })

            val vacuumListings = listOf(
                cheapLegitimateAccessory.copy(
                    title = "Xiaomi Vacuum Cleaner G10",
                    price = 299.00,
                    storeName = "Amazon.es",
                    sourceUrl = "https://www.amazon.es/dp/VACUUMG10"
                ),
                cheapLegitimateAccessory.copy(
                    title = "Xiaomi Vacuum Cleaner G10",
                    price = 279.00,
                    storeName = "PcComponentes",
                    sourceUrl = "https://www.pccomponentes.com/vacuum-g10"
                ),
                cheapLegitimateAccessory.copy(
                    title = "Xiaomi Vacuum Cleaner G10",
                    price = 319.00,
                    storeName = "MediaMarkt",
                    sourceUrl = "https://www.mediamarkt.es/vacuum-g10"
                ),
                cheapLegitimateAccessory.copy(
                    title = "Xiaomi Vacuum Cleaner G10 Mop Kit",
                    price = 12.99,
                    storeName = "Amazon.es",
                    sourceUrl = "https://www.amazon.es/dp/MOPKITG10"
                )
            )
            val filteredVacuumListings = repository.filterOnlineSearchResults(
                vacuumListings,
                "Xiaomi Vacuum Cleaner G10"
            )
            assertTrue(
                "Accessories should remain visible in online results, marked and sorted by the UI",
                filteredVacuumListings.any { it.title.contains("Mop Kit", ignoreCase = true) }
            )
            assertEquals(
                "The three main vacuum offers and the visible accessory should remain",
                4,
                filteredVacuumListings.size
            )
            assertTrue(
                "UGREEN brand searches should retain inexpensive legitimate cables and hubs",
                repository.filterOnlineSearchResults(listOf(cheapLegitimateAccessory), "UGREEN")
                    .any { it.title.contains("UGREEN USB 2.0 Hub") }
            )

            val deviceSearchListings = listOf(
                normalNewPhone.copy(price = 189.99, storeName = "Amazon.es", sourceUrl = "https://www.amazon.es/redmi-17"),
                normalNewPhone.copy(price = 179.99, storeName = "PcComponentes", sourceUrl = "https://www.pccomponentes.com/redmi-17"),
                normalNewPhone.copy(
                    title = "Xiaomi Redmi A5 Smartphone 4GB 128GB",
                    price = 99.99,
                    storeName = "MediaMarkt",
                    sourceUrl = "https://www.mediamarkt.es/redmi-a5"
                ),
                normalNewPhone.copy(
                    title = "Funda Xiaomi Redmi 17 con tapa",
                    price = 7.99,
                    storeName = "Apokin",
                    sourceUrl = "https://www.example.com/redmi-17-case"
                )
            )
            val strictMatches = repository.filterOnlineSearchResults(deviceSearchListings, "Xiaomi Redmi 17")
            assertEquals("A model-specific query keeps matching accessories visible but rejects other models", 3, strictMatches.size)
            assertTrue(strictMatches.all { it.title.contains("Redmi 17", ignoreCase = true) })

            val accessoryWithoutCapacity = normalNewPhone.copy(
                title = "Funda Xiaomi Redmi 17 con tapa",
                price = 7.99,
                storeName = "Tienda de fundas",
                sourceUrl = "https://www.example.com/redmi-17-case"
            )
            assertTrue(
                "A matching accessory remains visible even when it omits the searched storage capacity",
                repository.filterOnlineSearchResults(
                    listOf(accessoryWithoutCapacity),
                    "Xiaomi Redmi 17 5G 4GB 128GB"
                ).any { it.isAccessoryOffer }
            )

            val sonyFormattingVariant = results.first().copy(
                title = "Sony 1000XM5 Wireless Headphones",
                price = 229.99,
                storeName = "Worten",
                sourceUrl = "https://www.worten.es/sony-1000xm5"
            )
            assertTrue(
                "Equivalent retailer formatting of the WH-1000XM5 model should remain eligible",
                repository.filterOnlineSearchResults(
                    listOf(sonyFormattingVariant),
                    "Sony WH-1000XM5"
                ).isNotEmpty()
            )

            val grouped = groupOnlineShoppingResults(strictMatches)
            assertEquals("The main product and its accessory form separate cards", 2, grouped.size)
            val mainProductGroup = grouped.single { group -> group.offers.none { it.isAccessoryOffer } }
            assertEquals(2, mainProductGroup.offers.size)
            assertEquals(179.99, mainProductGroup.minPrice, 0.001)
            assertTrue(grouped.single { group -> group.offers.all { it.isAccessoryOffer } }
                .title.contains("Funda", ignoreCase = true))

            val inserted = repository.recordOnlineSearchResults(results, "GOOGLE_SHOPPING")
            assertEquals("Two normal offers and one suspicious unverified observation should be stored", 3, inserted)
            val stored = withTimeout<List<PriceObservationEntity>>(10_000) {
                database.priceObservationDao().observeAllVerified().first { it.size >= 2 }
            }
            assertEquals("Only the two non-suspicious offers count as verified history", 2, stored.size)
            val sonyProductId = stored.first().productId
            assertTrue("Both retailer offers must attach to one saved product", stored.all { it.productId == sonyProductId })
            assertTrue(stored.all { it.productTitle == "Sony WH-1000XM5 Wireless Headphones" })
            val persistedProduct = database.productDao().getProductById(sonyProductId)
            assertNotNull("Online searches must add a real product row to Room", persistedProduct)
            assertTrue("Searched products must be marked as source-verified", persistedProduct?.isPriceVerified == true)
            assertTrue(stored.all { it.currency == "EUR" && it.sourceType == "Google Shopping" })
            assertEquals(3, database.priceObservationDao().getObservationCount(sonyProductId))
            val savedGoogleOffer = stored.single { it.storeName == "PcComponentes" }
            assertTrue(savedGoogleOffer.sourceUrl.contains("google.com/shopping/product"))
            assertEquals("Google Shopping", savedGoogleOffer.linkType)
            assertEquals("directo", stored.single { it.storeName == "Amazon.es" }.linkType)

            val productAfterGoogleObservation = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { rows ->
                    rows.firstOrNull { it.id == sonyProductId }?.priceHistory?.size == 1
                }
            }.first { it.id == sonyProductId }
            assertEquals("Google Shopping", productAfterGoogleObservation.priceHistory.single().linkType)
            assertEquals("Google Shopping", productAfterGoogleObservation.priceSource)
            assertTrue(productAfterGoogleObservation.offers.any {
                it.store.name == "PcComponentes" && it.linkType == "Google Shopping"
            })

            val duplicate = results.first().copy(price = 245.00, observedAt = now + 60_000L)
            assertEquals("A changed same-day quote updates the existing row, not a second row",
                1, repository.recordOnlineSearchResults(listOf(duplicate), "GOOGLE_SHOPPING"))
            assertEquals(2, database.priceObservationDao().getVerifiedObservationCount())
            val refreshedPcComponentes = database.priceObservationDao().observeAllVerified().first {
                observations -> observations.any { it.storeName == "PcComponentes" && it.price == 245.00 }
            }
            assertEquals(245.00, refreshedPcComponentes.single { it.storeName == "PcComponentes" }.price, 0.001)

            // A separate historical day is kept without using a future timestamp: real future
            // observations are invalid for the price history and must not be used in tests.
            val secondDay = now - 24L * 60L * 60L * 1000L
            val secondDayResults = listOf(
                results[0].copy(price = 229.99, observedAt = secondDay),
                results[1].copy(price = 224.99, observedAt = secondDay)
            )
            assertEquals(2, repository.recordOnlineSearchResults(secondDayResults, "GOOGLE_SHOPPING"))
            val productWithDailyHistory = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { rows ->
                    rows.firstOrNull { it.id == sonyProductId }?.priceHistory?.size == 2
                }
            }.first { it.id == sonyProductId }
            assertEquals("The graph has one real point per day, not one per retailer", 2, productWithDailyHistory.priceHistory.size)
            val firstHistoryDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(java.util.Date(productWithDailyHistory.priceHistory[0].timestamp))
            val secondHistoryDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(java.util.Date(productWithDailyHistory.priceHistory[1].timestamp))
            assertTrue("Daily history points must come from distinct calendar dates", firstHistoryDate != secondHistoryDate)
            assertEquals(
                4,
                database.priceObservationDao().getVerifiedObservationCount(nowMillis = now)
            )
        } finally {
            database.close()
        }
    }



    @Test
    fun `Google Shopping fixture reports accepted offers and a reason for every rejected offer`() {
        val client = SerpApiClient()
        fun parse(query: String, item: JSONObject) = client.parseShoppingResultsDetailed(
            JSONObject().put("search_metadata", JSONObject().put("status", "Success"))
                .put("shopping_results", JSONArray().put(item)),
            query = query,
            observedAt = 123L
        )
        fun item(
            title: String,
            price: String = "1.299,99 €",
            extractedPrice: Double? = 1299.99,
            link: String = "https://www.google.com/shopping/product/123",
            condition: String? = null
        ): JSONObject = JSONObject().put("title", title).put("price", price)
            .put("source", "Tienda de ejemplo").put("product_link", link).apply {
                if (extractedPrice != null) put("extracted_price", extractedPrice)
                if (condition != null) put("second_hand_condition", condition)
            }

        val mostlyGoogle = JSONObject().put("search_metadata", JSONObject().put("status", "Success"))
            .put("shopping_results", JSONArray()
                .put(item("Samsung Galaxy S26 Ultra 512 GB negro", link = "https://www.google.com/shopping/product/one"))
                .put(item("Samsung Galaxy S26 Ultra 512 GB negro", price = "1.279,99 €", extractedPrice = 1279.99,
                    link = "https://www.google.es/search?tbm=shop&q=galaxy"))
                .put(item("Samsung Galaxy S26 Ultra 512 GB negro", price = "1.259,99 €", extractedPrice = 1259.99,
                    link = "https://www.tienda.example/samsung-s26")))
        val validReport = client.parseShoppingResultsDetailed(mostlyGoogle, "Samsung Galaxy S26 Ultra 512 GB", 123L)
        assertEquals("Google product/search links are valid destinations alongside direct links", 3, validReport.results.size)
        assertEquals(2, validReport.results.count { it.linkType == "Google Shopping" })
        assertEquals(1, validReport.results.count { it.linkType == "directo" })

        val used = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", condition = "used"))
        assertEquals("Used offers stay available to the opt-in second-hand filter", 1, used.results.size)
        assertTrue(used.results.single().isSecondHand)
        assertEquals("used", used.results.single().condition)

        val refurbished = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", condition = "refurbished"))
        assertTrue(refurbished.results.single().isSecondHand)
        assertEquals("refurbished", refurbished.results.single().condition)

        val rental = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB alquiler"))
        assertTrue(rental.discardedOffers.single().reason.contains("Alquiler o cuota"))

        val monthly = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB 49,99 €/mes", price = "49,99 €/mes", extractedPrice = 49.99))
        assertEquals("A monthly installment must never become a product offer", 0, monthly.results.size)
        assertEquals("The excluded installment must be visible in diagnostics", 1, monthly.discardedOffers.size)
        assertTrue(monthly.discardedOffers.single().reason.contains("Alquiler o cuota"))

        val pack = parse("Detergente líquido 300 ml",
            item("Detergente líquido 12x300 ml"))
        assertTrue(pack.discardedOffers.single().reason.contains("pack distinto"))

        val wrongVariant = parse("Anker Prime 20.000 mAh 200 W",
            item("Anker Prime 10.000 mAh 100 W"))
        assertTrue(wrongVariant.discardedOffers.single().reason.contains("variante"))

        val noEuroSymbol = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", price = "1299.99"))
        assertEquals("Structured price from Spain's Google Shopping response is EUR even without the € glyph",
            1, noEuroSymbol.results.size)

        val noEuroEvidence = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", price = "1299.99", extractedPrice = null))
        assertTrue("Without the euro symbol or structured price, currency cannot be verified",
            noEuroEvidence.discardedOffers.single().reason.contains("Sin precio válido"))

        val dollars = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", price = "$999.99", extractedPrice = 999.99))
        assertTrue(dollars.discardedOffers.single().reason.contains("moneda extranjera"))

        val missingPrice = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Samsung Galaxy S26 Ultra 512 GB", price = "", extractedPrice = null))
        assertTrue(missingPrice.discardedOffers.single().reason.contains("Sin precio válido"))

        val wrongTitle = parse("Samsung Galaxy S26 Ultra 512 GB",
            item("Tostadora de cocina", price = "19,99 €", extractedPrice = 19.99))
        assertTrue(wrongTitle.discardedOffers.single().reason.contains("Otro modelo"))
    }

    @Test
    fun `Amazon es fixture reports condition currency monthly and model rejection reasons`() {
        val client = SerpApiClient()
        fun parse(query: String, items: JSONArray) = client.parseAmazonResultsDetailed(
            JSONObject().put("search_metadata", JSONObject().put("status", "Success"))
                .put("organic_results", items),
            query = query,
            observedAt = 123L
        )
        fun item(
            title: String,
            price: String = "129,99 €",
            extractedPrice: Double? = 129.99,
            condition: String? = null
        ): JSONObject = JSONObject().put("title", title).put("price", price)
            .put("link", "https://www.amazon.es/dp/B0EXAMPLE").apply {
                if (extractedPrice != null) put("extracted_price", extractedPrice)
                if (condition != null) put("second_hand_condition", condition)
            }

        val query = "Anker Power Bank 20.000 mAh"
        val report = parse(query, JSONArray()
            .put(item("Anker Power Bank 20.000 mAh", price = "39.99", extractedPrice = 39.99))
            .put(item("Anker Power Bank 20.000 mAh", condition = "used"))
            .put(item("Anker Power Bank 20.000 mAh alquiler mensual"))
            .put(item("Anker Power Bank 20.000 mAh cuota 4,99 €/mes", price = "4,99 €/mes", extractedPrice = 4.99))
            .put(item("Anker Power Bank 10.000 mAh", price = "19,99 €", extractedPrice = 19.99))
            .put(item("Tostadora de cocina", price = "12,99 €", extractedPrice = 12.99))
            .put(item("Anker Power Bank 20.000 mAh", price = "$39.99", extractedPrice = 39.99))
            .put(item("Anker Power Bank 20.000 mAh", price = "Consultar precio", extractedPrice = null))
            .put(item("Anker Power Bank 20.000 mAh", price = "39.99", extractedPrice = null)))

        assertEquals("Amazon.es numeric structured prices are accepted without the € glyph", 1, report.results.size)
        assertEquals(8, report.discardedOffers.size)
        assertTrue(report.discardedOffers.any { it.reason.contains("Segunda mano") })
        assertTrue(report.discardedOffers.any { it.reason.contains("Alquiler o cuota") })
        assertTrue(report.discardedOffers.any { it.reason.contains("variante") })
        assertTrue(report.discardedOffers.any { it.reason.contains("Otro modelo") })
        assertTrue(report.discardedOffers.any { it.reason.contains("moneda extranjera") })
        assertTrue(report.discardedOffers.any { it.reason.contains("Sin precio válido") })
    }

    @Test
    fun `repository explains unusually low prices relative to comparable offers without saving them`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val timestamp = System.currentTimeMillis()
            val offers = listOf(999.99, 1049.99, 1099.99, 1149.99, 99.99).mapIndexed { index, price ->
                com.example.data.remote.SerpApiShoppingResult(
                    title = "Samsung Galaxy S26 Ultra 512 GB",
                    price = price,
                    storeName = "Tienda ${index + 1}",
                    sourceUrl = "https://www.google.com/shopping/product/${index + 1}",
                    observedAt = timestamp
                )
            }
            val accepted = repository.filterOnlineSearchResults(offers, "Samsung Galaxy S26 Ultra 512 GB")
            val discarded = repository.diagnoseOnlineSearchRejections(offers, "Samsung Galaxy S26 Ultra 512 GB")
            assertEquals("Low outlier remains visible but is explicitly marked", 5, accepted.size)
            assertTrue(accepted.single { it.price == 99.99 }.isSuspiciousPrice)
            assertTrue(discarded.any { it.priceLabel.contains("99.99") && it.reason.contains("Precio sospechoso") })
            assertEquals("Viewing diagnostics does not persist accepted or rejected prices", 0,
                database.priceObservationDao().getVerifiedObservationCount())
        } finally {
            database.close()
        }
    }


    @Test
    fun `repository startup preserves observations and every followed product`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            fun product(
                id: String,
                title: String,
                minDate: String = "Historial en construcción",
                minStore: String = "Amazon.es"
            ) = ProductEntity(
                id = id,
                title = title,
                brand = "Marca de prueba",
                category = "SMARTPHONES",
                currentPrice = 199.99,
                athMinPrice = 199.99,
                athMinDate = minDate,
                athMinStore = minStore,
                athMaxPrice = 199.99,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "amazon_es",
                storeUrl = "https://www.amazon.es/dp/TEST",
                ean = "",
                isPriceVerified = false
            )

            // This looks like a legacy demo row, but it is explicitly followed by the user.
            database.productDao().insert(
                product(
                    id = "demo_followed_product",
                    title = "Producto seguido con marcador heredado",
                    minDate = "Datos de demostración",
                    minStore = "Datos de demostración"
                )
            )
            database.trackedProductDao().insertOrUpdate(
                TrackedProductEntity(productId = "demo_followed_product", notes = "no borrar seguimiento")
            )
            database.productDao().insert(
                product("followed_pending_product", "Producto seguido pendiente")
            )
            database.trackedProductDao().insertOrUpdate(
                TrackedProductEntity(productId = "followed_pending_product", notes = "seguir esperando precio")
            )
            database.productDao().insert(
                product("online_product_verified", "Producto con observación verificada")
            )
            database.productDao().insert(
                product("online_product_pending", "Producto con observación pendiente")
            )
            database.productDao().insert(
                product("orphan_without_observations", "Fila huérfana sin historial")
            )

            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "followed_pending_product",
                    storeId = "amazon_es",
                    price = 201.00,
                    observedAt = now,
                    sourceUrl = "https://www.amazon.es/dp/FOLLOWED",
                    sourceName = "Amazon.es",
                    isVerified = false,
                    verificationNote = "precio real pendiente de validar",
                    productTitle = "Producto seguido pendiente",
                    storeName = "Amazon.es",
                    sourceType = "Amazon.es",
                    linkType = "directo"
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "online_product_verified",
                    storeId = "amazon_es",
                    price = 199.99,
                    observedAt = now + 1,
                    sourceUrl = "https://www.amazon.es/dp/VERIFIED",
                    sourceName = "Amazon.es",
                    isVerified = true,
                    productTitle = "Producto con observación verificada",
                    storeName = "Amazon.es",
                    sourceType = "Amazon.es",
                    linkType = "directo"
                )
            )
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "online_product_pending",
                    storeId = "tienda_es",
                    price = 205.00,
                    observedAt = now + 2,
                    sourceUrl = "https://shop.example.es/product/PENDING",
                    sourceName = "Tienda española",
                    isVerified = false,
                    verificationNote = "esperando comprobación",
                    productTitle = "Producto con observación pendiente",
                    storeName = "Tienda española",
                    sourceType = "Google Shopping",
                    linkType = "Google Shopping"
                )
            )
            database.storeDao().insertAll(
                listOf(
                    StoreEntity(
                        id = "amazon_es",
                        name = "Amazon.es",
                        domain = "amazon.es",
                        brandColorHex = "#000000",
                        category = "Electrónica",
                        physicalStoresInSpain = false,
                        storeCountApprox = 0,
                        headquartersInSpain = "España",
                        description = "Fila persistida de tienda"
                    )
                )
            )

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val products = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { rows ->
                    rows.size == 5 &&
                        rows.any { it.id == "online_product_verified" && it.isPriceVerified }
                }
            }
            assertEquals(
                setOf(
                    "demo_followed_product",
                    "followed_pending_product",
                    "online_product_verified",
                    "online_product_pending",
                    "orphan_without_observations"
                ),
                products.map { it.id }.toSet()
            )
            assertEquals("Startup must preserve every saved product row", 5,
                database.productDao().getProductCount())
            assertNotNull(database.productDao().getProductById("demo_followed_product"))
            assertNotNull(database.productDao().getProductById("followed_pending_product"))
            assertNotNull(
                "An untracked product without history may still be user-owned data",
                database.productDao().getProductById("orphan_without_observations")
            )

            assertEquals("Real but unverified observations are retained", 1,
                database.priceObservationDao().getObservationCount("followed_pending_product"))
            assertEquals(1, database.priceObservationDao().getObservationCount("online_product_verified"))
            assertEquals(1, database.priceObservationDao().getObservationCount("online_product_pending"))
            assertEquals("Verified status is not changed for pending observations", false,
                database.productDao().getProductById("followed_pending_product")?.isPriceVerified)
            assertEquals(1, database.priceObservationDao().getVerifiedObservationCount())

            val followedIds = database.trackedProductDao().getAllTracked().first().map { it.productId }.toSet()
            assertEquals(setOf("demo_followed_product", "followed_pending_product"), followedIds)
            assertEquals("Stored shops are retained too", 1, database.storeDao().getStoreCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentDailyObservationWritesAreAtomicAndPreferLowerSameTimestampPrice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val observedAt = System.currentTimeMillis()
            val productId = "concurrent-daily-observation-test"
            val dayStart = observedAt - 24L * 60L * 60L * 1000L
            val dayEnd = observedAt + 24L * 60L * 60L * 1000L

            fun observation(price: Double) = PriceObservationEntity(
                productId = productId,
                storeId = "same-store",
                price = price,
                currency = "EUR",
                observedAt = observedAt,
                sourceUrl = "https://shop.example/item?price=$price",
                sourceName = "Google Shopping",
                isVerified = true,
                productTitle = "Test product",
                storeName = "Tienda de prueba",
                evidenceType = "live"
            )

            coroutineScope {
                listOf(49.99, 39.99).map { price ->
                    async(Dispatchers.IO) {
                        database.priceObservationDao().saveDailyObservationIfNewer(
                            observation = observation(price),
                            dayStart = dayStart,
                            dayEnd = dayEnd
                        )
                    }
                }.forEach { it.await() }
            }

            val rows = database.priceObservationDao().observeVerifiedHistory(productId).first()
            assertEquals("Concurrent refreshes must leave one observation for the store/day", 1, rows.size)
            assertEquals("The lower price wins when both quotes share a timestamp", 39.99, rows.single().price, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun googleShoppingRejectsUnrequestedMultiProductBundleForExactModelSearch() {
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "shopping_results": [
                {
                  "title": "Logitech G203 Lightsync Gaming Mouse",
                  "price": "29,99 €",
                  "extracted_price": 29.99,
                  "link": "https://shop.example.com/logitech-g203",
                  "source": "Tienda de ratones"
                },
                {
                  "title": "Teclados Logitech Pack G213 Prodigy Teclado Gaming + Logitech G203 Lightsync 2nd Gen Ratón Gaming",
                  "price": "88,98 €",
                  "extracted_price": 88.98,
                  "link": "https://shop.example.com/g213-g203-pack",
                  "source": "Tienda de packs"
                }
              ]
            }
            """.trimIndent()
        )

        val report = SerpApiClient().parseShoppingResultsDetailed(
            root = payload,
            query = "Logitech G203",
            observedAt = 1_800_000_000_000L
        )

        assertEquals("Only the standalone requested mouse should remain", 1, report.results.size)
        assertEquals("Logitech G203 Lightsync Gaming Mouse", report.results.single().title)
        assertTrue(
            "The diagnostic should explain why the pack was excluded",
            report.discardedOffers.any { it.reason == "Pack con varios productos no solicitado" }
        )
    }

    @Test
    fun amazonRejectsUnrequestedMultiProductBundleForExactModelSearch() {
        val payload = JSONObject(
            """
            {
              "search_metadata": {"status": "Success"},
              "organic_results": [
                {
                  "title": "Logitech G203 Lightsync Gaming Mouse",
                  "price": "29,99 €",
                  "extracted_price": 29.99,
                  "link": "https://www.amazon.es/dp/G203-MOUSE"
                },
                {
                  "title": "Teclados Logitech Pack G213 Prodigy Teclado Gaming + Logitech G203 Lightsync 2nd Gen Ratón Gaming",
                  "price": "88,98 €",
                  "extracted_price": 88.98,
                  "link": "https://www.amazon.es/dp/G213-G203-PACK"
                }
              ]
            }
            """.trimIndent()
        )

        val report = SerpApiClient().parseAmazonResultsDetailed(
            root = payload,
            query = "Logitech G203",
            observedAt = 1_800_000_000_000L
        )

        assertEquals("Only the standalone requested mouse should remain", 1, report.results.size)
        assertEquals("Logitech G203 Lightsync Gaming Mouse", report.results.single().title)
        assertTrue(
            "Amazon bundle rejections should be visible in diagnostics",
            report.discardedOffers.any { it.reason == "Pack con varios productos no solicitado" }
        )
    }

    @Test
    fun knownRetailerDestinationMustAgreeWithSellerLabelButGoogleRedirectsRemainAllowed() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            val pcComponentes = SerpApiShoppingResult(
                title = "Xiaomi Redmi 17 5G 4GB 128GB",
                price = 179.99,
                storeName = "PcComponentes",
                sourceUrl = "https://www.pccomponentes.com/xiaomi-redmi-17",
                observedAt = now
            )
            val mislabeled = pcComponentes.copy(storeName = "Amazon.es - Seller")
            val googleRedirect = pcComponentes.copy(
                storeName = "Amazon.es",
                sourceUrl = "https://www.google.com/shopping/product/123"
            )

            val accepted = repository.filterOnlineSearchResults(
                listOf(mislabeled, pcComponentes, googleRedirect),
                searchQuery = "Xiaomi Redmi 17"
            )

            assertTrue("A known domain with a matching seller name should remain eligible",
                accepted.any { it.sourceUrl == pcComponentes.sourceUrl && it.storeName == "PcComponentes" })
            assertFalse("A known domain must not be attributed to a contradictory seller label",
                accepted.any { it.storeName == "Amazon.es - Seller" })
            assertTrue("Google Shopping links remain valid indirect destinations",
                accepted.any { it.sourceUrl == googleRedirect.sourceUrl })
        } finally {
            database.close()
        }
    }

    @Test
    fun absolutePriceFloorsRejectImplausibleDevicePricesButKeepLegitimateAccessories() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            fun offer(title: String, price: Double, suffix: String) = SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = "Tienda de prueba",
                sourceUrl = "https://merchant-${suffix}.example/product",
                observedAt = now
            )
            val suspiciousPhone = offer("Xiaomi Redmi 17 5G 4GB 128GB", 19.99, "phone")
            val suspiciousLaptop = offer("Apple MacBook Air M3 16GB 256GB", 67.50, "laptop")
            val suspiciousConsole = offer("Sony PlayStation 5 Slim Console", 39.99, "console")
            val legitimateCase = offer("Funda protectora para MacBook Air M3", 19.99, "case")
            val legitimatePhone = offer("Xiaomi Redmi 17 5G 4GB 128GB", 189.99, "phone-valid")

            val accepted = repository.filterOnlineSearchResults(
                listOf(suspiciousPhone, suspiciousLaptop, suspiciousConsole, legitimateCase, legitimatePhone)
            )

            assertTrue("Keep the cheap phone visible for diagnosis, but flag it as suspicious",
                accepted.any { it.sourceUrl == suspiciousPhone.sourceUrl && it.isSuspiciousPrice })
            assertTrue("Keep a very cheap MacBook visible, but do not count it as a trusted price",
                accepted.any { it.sourceUrl == suspiciousLaptop.sourceUrl && it.isSuspiciousPrice })
            assertTrue("Keep the implausibly cheap PS5 visible for diagnosis, but flag it",
                accepted.any { it.sourceUrl == suspiciousConsole.sourceUrl && it.isSuspiciousPrice })
            assertTrue("Legitimate low-price accessories are exempt from device floors",
                accepted.any { it.sourceUrl == legitimateCase.sourceUrl })
            assertTrue("A plausible retail phone offer should remain eligible",
                accepted.any { it.sourceUrl == legitimatePhone.sourceUrl })

            val rejected = repository.diagnoseOnlineSearchRejections(
                listOf(suspiciousPhone, suspiciousLaptop, suspiciousConsole)
            )
            assertTrue(
                "Low-price rejections should explain the reason",
                rejected.all { it.reason.startsWith("Precio sospechoso:") && it.reason.contains("categoría") }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidLegacyVerifiedRowsStayStoredButCannotAffectVisiblePriceHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            val ids = listOf(
                "legacy-cheap-laptop",
                "legacy-promo-battery",
                "legacy-mismatched-seller",
                "legacy-future-live-quote"
            )
            val products = listOf(
                ProductEntity(
                    id = ids[0],
                    title = "Apple MacBook Air M3 16GB 256GB SSD",
                    brand = "Apple",
                    category = "COMPUTERS",
                    currentPrice = 67.50,
                    athMinPrice = 67.50,
                    athMinDate = "legacy",
                    athMinStore = "Tienda de prueba",
                    athMaxPrice = 67.50,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "legacy-store",
                    storeUrl = "https://merchant.example/macbook",
                    ean = "",
                    isPriceVerified = true
                ),
                ProductEntity(
                    id = ids[1],
                    title = "3x Batería portátil 20000mAh impreso con su logotipo",
                    brand = "Promocional",
                    category = "HOME_APPLIANCES",
                    currentPrice = 297.91,
                    athMinPrice = 297.91,
                    athMinDate = "legacy",
                    athMinStore = "Zaprinta.es",
                    athMaxPrice = 297.91,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "zaprinta",
                    storeUrl = "https://www.zaprinta.es/baterias-promocionales",
                    ean = "",
                    isPriceVerified = true
                ),
                ProductEntity(
                    id = ids[2],
                    title = "Sony WH-1000XM5 Wireless Headphones",
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 229.99,
                    athMinPrice = 229.99,
                    athMinDate = "legacy",
                    athMinStore = "Amazon.es",
                    athMaxPrice = 229.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon",
                    storeUrl = "https://www.pccomponentes.com/sony-wh1000xm5",
                    ean = "",
                    isPriceVerified = true
                ),
                ProductEntity(
                    id = ids[3],
                    title = "Sony WH-1000XM5 Wireless Headphones",
                    brand = "Sony",
                    category = "TV_AUDIO",
                    currentPrice = 229.99,
                    athMinPrice = 229.99,
                    athMinDate = "legacy",
                    athMinStore = "Amazon.es",
                    athMaxPrice = 229.99,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "amazon_es",
                    storeUrl = "https://www.amazon.es/dp/B0FUTURE",
                    ean = "",
                    isPriceVerified = true
                )
            )
            database.productDao().insertAll(products)
            database.priceObservationDao().insertAll(
                listOf(
                    PriceObservationEntity(
                        productId = ids[0],
                        storeId = "legacy-store",
                        price = 67.50,
                        observedAt = now,
                        sourceUrl = "https://merchant.example/macbook",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = products[0].title,
                        storeName = "Tienda de prueba",
                        sourceType = "Google Shopping"
                    ),
                    PriceObservationEntity(
                        productId = ids[1],
                        storeId = "zaprinta",
                        price = 297.91,
                        observedAt = now,
                        sourceUrl = "https://www.zaprinta.es/baterias-promocionales",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = products[1].title,
                        storeName = "Zaprinta.es",
                        sourceType = "Google Shopping"
                    ),
                    PriceObservationEntity(
                        productId = ids[2],
                        storeId = "amazon",
                        price = 229.99,
                        observedAt = now,
                        sourceUrl = "https://www.pccomponentes.com/sony-wh1000xm5",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = products[2].title,
                        storeName = "Amazon.es",
                        sourceType = "Google Shopping"
                    ),
                    PriceObservationEntity(
                        productId = ids[3],
                        storeId = "amazon_es",
                        price = 229.99,
                        observedAt = now + 2L * 60L * 60L * 1000L,
                        sourceUrl = "https://www.amazon.es/dp/B0FUTURE",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = products[3].title,
                        storeName = "Amazon.es",
                        sourceType = "Google Shopping"
                    )
                )
            )

            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val visible = repository.allProductsStream.first { rows -> ids.all { id -> rows.any { it.id == id } } }
                .filter { it.id in ids }
            assertEquals(4, visible.size)
            assertTrue(
                "Invalid legacy rows must not be deleted; only their trusted-history projection is filtered",
                ids.all { database.priceObservationDao().getObservationCount(it) == 1 }
            )
            assertTrue(
                "An implausibly cheap legacy laptop must not appear as a verified price",
                visible.filter { it.id == ids[0] }.all { !it.isPriceVerified && it.priceHistory.isEmpty() }
            )
            assertTrue(
                "Promotional bulk merchandise must not appear as trusted price history",
                visible.filter { it.id == ids[1] }.all { !it.isPriceVerified && it.priceHistory.isEmpty() }
            )
            assertTrue(
                "A known retailer domain must not be attributed to a different seller label",
                visible.filter { it.id == ids[2] }.all { !it.isPriceVerified && it.priceHistory.isEmpty() }
            )
            assertTrue(
                "A future-dated legacy quote must not appear as a saved price or historical point",
                visible.filter { it.id == ids[3] }.all {
                    !it.isPriceVerified && it.priceHistory.isEmpty()
                }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun repositoryRejectsUnrequestedMixedProductBundlesBeforePersistence() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            val normal = SerpApiShoppingResult(
                title = "Xiaomi Redmi 17 5G Smartphone",
                price = 189.99,
                storeName = "Tienda de prueba",
                sourceUrl = "https://shop.example/redmi-17",
                observedAt = now
            )
            val bundle = normal.copy(
                title = "Xiaomi Redmi 17 Smartphone + Apple MacBook Air Laptop",
                price = 999.99,
                sourceUrl = "https://shop.example/redmi-macbook-pack"
            )

            val accepted = repository.filterOnlineSearchResults(
                listOf(normal, bundle),
                "Xiaomi Redmi 17"
            )
            assertEquals("Only the standalone queried model is eligible", listOf(normal.title), accepted.map { it.title })
            val reasons = repository.diagnoseOnlineSearchRejections(listOf(normal, bundle), "Xiaomi Redmi 17")
            assertTrue(
                "The repository should explain a mixed-product bundle rejection",
                reasons.any { it.title == bundle.title && it.reason == "Pack con varios productos no solicitado" }
            )
        } finally {
            database.close()
        }
    }


    @Test
    fun `bicycle tyres are not accessories even when titles mention cables`() {
        val result = SerpApiShoppingResult(
            title = "MAXXIS CROSSMARK 2 cables MTB neumáticos de bicicleta 26 27,5 29",
            price = 31.61,
            storeName = "Tienda de ejemplo",
            sourceUrl = "https://shop.example.test/maxxis-crossmark",
            observedAt = 123L
        )

        assertFalse(result.isAccessoryOffer)
        assertEquals("26", com.example.data.remote.requestedBicycleTireDiameter("cubiertas Maxxis MTB 26 pulgadas"))
        assertTrue(com.example.data.remote.hasAmbiguousBicycleTireDiameter("cubiertas Maxxis MTB 26 pulgadas", result.title))
        assertFalse(com.example.data.remote.isWrongBicycleTireDiameter("cubiertas Maxxis MTB 26 pulgadas", result.title))
        assertTrue(com.example.data.remote.isWrongBicycleTireDiameter(
            "cubiertas Maxxis MTB 26 pulgadas",
            "Cubierta Maxxis DTH 29x2.30"
        ))
    }

    @Test
    fun `nine speed mountain bike drivetrain search excludes wheels`() {
        assertTrue(com.example.data.remote.isBicycleDrivetrainSearch("Shimano MTB componentes 9V"))
        assertTrue(com.example.data.remote.isBicycleDrivetrainComponentTitle("Cassette Shimano Sora 9V"))
        assertTrue(com.example.data.remote.isBicycleDrivetrainComponentTitle("Cambio trasero Shimano Altus M2000 9V"))
        assertFalse(com.example.data.remote.isBicycleDrivetrainComponentTitle(
            "Rueda trasera Shimano M475 11-10-9V"
        ))
    }


    @Test
    fun `implausible drivetrain prices are visibly marked unverified`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val offer = SerpApiShoppingResult(
                title = "Cambio trasero Shimano Altus M2000 9V",
                price = 1400.0,
                storeName = "Tienda de ejemplo",
                sourceUrl = "https://www.google.com/shopping/product/123456",
                observedAt = System.currentTimeMillis()
            )

            val accepted = repository.filterOnlineSearchResults(
                listOf(offer),
                "Shimano MTB componentes 9V"
            )
            assertEquals(1, accepted.size)
            assertTrue(accepted.single().isSuspiciousPrice)
        } finally {
            database.close()
        }
    }


    @Test
    fun `legacy verified quote is rechecked against canonical product title`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.productDao().insert(
                ProductEntity(
                    id = "legacy-bike-derailleur",
                    title = "Cambio trasero Shimano Altus M2000 caja larga bicicleta MTB 9V",
                    brand = "Shimano",
                    category = "SPORTS",
                    currentPrice = 1400.0,
                    athMinPrice = 1400.0,
                    athMinDate = "Sin datos",
                    athMinStore = "PcComponentes",
                    athMaxPrice = 1400.0,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = "pc_componentes",
                    storeUrl = "https://www.pccomponentes.com/",
                    ean = "",
                    isPriceVerified = true
                )
            )
            // Simulate a legacy row that was marked verified before the category-specific price
            // guard shipped and whose seller title is too generic to trigger the guard on its own.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "legacy-bike-derailleur",
                    storeId = "pc_componentes",
                    price = 1400.0,
                    observedAt = now,
                    sourceUrl = "https://www.pccomponentes.com/shimano-altus",
                    sourceName = "Google Shopping",
                    isVerified = true,
                    productTitle = "Shimano Altus M2000 9V",
                    storeName = "PcComponentes",
                    evidenceType = "live"
                )
            )
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )

            val product = withTimeout(10_000L) {
                repository.allProductsStream.first { products ->
                    products.any { it.id == "legacy-bike-derailleur" }
                }.first { it.id == "legacy-bike-derailleur" }
            }
            assertFalse(
                "A legacy price implausible for the canonical product must not verify the product",
                product.isPriceVerified
            )
            assertTrue(
                "The implausible legacy quote must not appear as a green best-price store offer",
                product.offers.isEmpty()
            )
            assertTrue(
                "A store supported only by the implausible quote must stay out of the trusted store list",
                repository.storesWithSavedPricesStream.first().isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `main store directory only includes merchants with valid saved prices`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val now = System.currentTimeMillis()
            database.priceObservationDao().insertAll(
                listOf(
                    PriceObservationEntity(
                        productId = "bike-cassette",
                        storeId = "pccomponentes",
                        price = 32.95,
                        observedAt = now,
                        sourceUrl = "https://www.pccomponentes.com/cassette-test",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = "Cassette Shimano Sora 9V",
                        storeName = "PcComponentes"
                    ),
                    PriceObservationEntity(
                        productId = "bike-derailleur",
                        storeId = "pccomponentes",
                        price = 42.50,
                        observedAt = now - 60_000L,
                        sourceUrl = "https://www.pccomponentes.com/derailleur-test",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = "Cambio Shimano 9V",
                        storeName = "PcComponentes"
                    ),
                    PriceObservationEntity(
                        productId = "bike-tyre",
                        storeId = "online_store_bicimarket",
                        price = 31.61,
                        observedAt = now,
                        sourceUrl = "https://bicimarket.example.test/maxxis",
                        sourceName = "Google Shopping",
                        isVerified = true,
                        productTitle = "Cubierta Maxxis MTB 26",
                        storeName = "Bici Market"
                    ),
                    PriceObservationEntity(
                        productId = "untrusted",
                        storeId = "online_store_suspicious",
                        price = 0.99,
                        observedAt = now,
                        sourceUrl = "https://suspicious.example.test/product",
                        sourceName = "Google Shopping",
                        isVerified = false,
                        productTitle = "Producto con precio sospechoso",
                        storeName = "Tienda sin precio válido"
                    )
                )
            )

            val stores = withTimeout(10_000L) {
                repository.storesWithSavedPricesStream.first { it.size == 2 }
            }
            assertEquals(listOf("Bici Market", "PcComponentes"), stores.map { it.name })
            assertEquals(2, stores.single { it.id == "pccomponentes" }.savedPriceCount)
            assertEquals(1, stores.single { it.name == "Bici Market" }.savedPriceCount)
            assertFalse(stores.any { it.name == "Tienda sin precio válido" })
            assertFalse(stores.any { it.id == "amazon_es" })
        } finally {
            database.close()
        }
    }

    @Test
    fun standaloneAccessoryCannotBecomeTheMainProductComparisonPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Sony WH-1000XM5 Wireless Headphones"
            val product = ProductEntity(
                id = "sony-wh1000xm5-accessory-isolation",
                title = title,
                brand = "Sony",
                category = "TV_AUDIO",
                currentPrice = 199.99,
                athMinPrice = 199.99,
                athMinDate = "",
                athMinStore = "Tienda anterior",
                athMaxPrice = 199.99,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "shop",
                storeUrl = "https://shop.example.test/sony-wh1000xm5",
                ean = "",
                isPriceVerified = false
            )
            database.productDao().insert(product)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val accessory = SerpApiShoppingResult(
                title = "Funda para Sony WH-1000XM5 Auriculares",
                price = 5.99,
                storeName = "Tienda",
                sourceUrl = "https://shop.example.test/funda-sony-wh1000xm5",
                observedAt = System.currentTimeMillis(),
                condition = "new"
            )

            assertEquals(
                "A standalone case must not be saved as the headphone's live comparison price",
                0,
                repository.recordProductComparisonOffers(
                    productId = product.id,
                    expectedTitle = title,
                    offers = listOf(accessory)
                )
            )
            assertEquals(0, database.priceObservationDao().getObservationCount(product.id))
            assertFalse(database.productDao().getProductById(product.id)!!.isPriceVerified)
        } finally {
            database.close()
        }
    }

    @Test
    fun suspiciousOnlySearchResultsCannotReplaceSavedProductStoreOrExtrema() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val title = "Anker Power Bank 20000mAh 65W"
            val originalUrl = "https://www.pccomponentes.com/anker-power-bank-20000mah-65w"
            val product = ProductEntity(
                id = "anker-power-bank-suspicious-only",
                title = title,
                brand = "Anker",
                category = "ACCESSORIES",
                currentPrice = 99.99,
                athMinPrice = 89.99,
                athMinDate = "14 oct 2026",
                athMinStore = "PcComponentes",
                athMaxPrice = 129.99,
                avg90dPrice = 105.0,
                officialPvp = 149.99,
                currentStoreId = "pccomponentes",
                storeUrl = originalUrl,
                ean = "",
                isPriceVerified = false,
                googleProductId = "123456789"
            )
            database.productDao().insert(product)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val suspicious = SerpApiShoppingResult(
                title = title,
                price = 1.99,
                storeName = "Vendedor desconocido",
                sourceUrl = "https://unknown-seller.example/anker-power-bank",
                observedAt = System.currentTimeMillis(),
                condition = "new",
                isSuspiciousPrice = true
            )

            repository.recordOnlineSearchResults(
                results = listOf(suspicious),
                sourceType = "GOOGLE_SHOPPING",
                searchQuery = title
            )

            val updated = database.productDao().getProductById(product.id)!!
            assertEquals("A suspicious-only result must not change the saved current price", 99.99, updated.currentPrice, 0.001)
            assertEquals("A suspicious-only result must not replace the direct retailer URL", originalUrl, updated.storeUrl)
            assertEquals("A suspicious-only result must not replace the saved current store", "pccomponentes", updated.currentStoreId)
            assertEquals("A suspicious-only result must not alter the observed minimum", 89.99, updated.athMinPrice, 0.001)
            assertEquals("A suspicious-only result must not alter the observed maximum", 129.99, updated.athMaxPrice, 0.001)
            assertEquals("A suspicious-only result must not replace the catalogue ID", "123456789", updated.googleProductId)
            assertEquals(1, database.priceObservationDao().getObservationCount(product.id))
            assertTrue(
                "The suspicious observation may be retained for audit but must stay unverified",
                database.priceObservationDao().observeVerifiedHistory(product.id).first().isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun suspiciousOnlyProductComparisonDoesNotVerifyCurrentProductPrice() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val product = ProductEntity(
                id = "anker-comparison-suspicious-only",
                title = "Anker Prime 27K Power Bank",
                brand = "Anker",
                category = "ACCESSORIES",
                currentPrice = 0.0,
                athMinPrice = 0.0,
                athMinDate = "",
                athMinStore = "",
                athMaxPrice = 0.0,
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = "unknown_store",
                storeUrl = "",
                ean = "",
                isPriceVerified = false
            )
            database.productDao().insert(product)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val suspicious = SerpApiShoppingResult(
                title = product.title,
                price = 1.99,
                storeName = "Vendedor desconocido",
                sourceUrl = "https://seller.example.test/anker-prime-27k",
                observedAt = System.currentTimeMillis(),
                condition = "new",
                isSuspiciousPrice = true,
                googleProductId = "123456789"
            )

            assertEquals(
                1,
                repository.recordProductComparisonOffers(
                    productId = product.id,
                    expectedTitle = product.title,
                    offers = listOf(suspicious)
                )
            )
            val refreshed = database.productDao().getProductById(product.id)!!
            assertFalse("An unverified diagnostic offer must not verify the current price", refreshed.isPriceVerified)
            assertTrue(
                database.priceObservationDao().observeVerifiedHistory(product.id).first().isEmpty()
            )
            assertEquals("Suspicious catalogue IDs must not be attached to the product", null, refreshed.googleProductId)
        } finally {
            database.close()
        }
    }

}
