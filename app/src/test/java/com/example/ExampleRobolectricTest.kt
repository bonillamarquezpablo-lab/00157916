package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.model.Product
import com.example.data.repository.ProductRepository
import com.example.data.repository.SpanishMarketData
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiQuote
import com.example.data.remote.SerpApiRequestProduct
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CoroutineScope
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
    fun `catalogue streams from Room and seeds missing products without replacing old rows`() = runBlocking<Unit> {
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
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repository = ProductRepository(
                storeDao = database.storeDao(),
                productDao = database.productDao(),
                trackedDao = database.trackedProductDao(),
                priceObservationDao = database.priceObservationDao(),
                externalScope = scope
            )

            val products: List<Product> = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { productRows -> productRows.size >= 60 }
            }
            assertTrue("Catalog should load from Room and include 60+ entries", products.size >= 60)
            assertEquals("Product IDs must stay unique", products.size, products.map { it.id }.toSet().size)
            val preserved = products.first { it.id == "sony_wh1000xm5" }
            assertEquals("Existing row should not be overwritten by seed data", "Producto Sony preservado por el usuario", preserved.title)
            assertFalse("Existing sample should stay unverified", preserved.isPriceVerified)
            assertTrue("Unverified product should not display sample history", preserved.priceHistory.isEmpty())
            assertTrue("New catalogue IDs should be seeded", products.any { it.id == "demo_galaxy_s26_ultra" })

            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sony_wh1000xm5",
                    storeId = "amazon_es",
                    price = 222.50,
                    observedAt = System.currentTimeMillis(),
                    sourceUrl = "https://www.amazon.es/dp/TEST",
                    sourceName = "Authorized unit-test source",
                    isVerified = true
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
            assertEquals(1, sourceBackedSony.priceHistory.size)
            assertEquals(222.50, sourceBackedSony.priceHistory.single().price, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun `SerpApi parser accepts matching euro HTTPS offers and rejects unsafe results`() {
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

        assertEquals("Only matching EUR offers with HTTPS merchant URLs should survive", 2, quotes.size)
        assertEquals(1249.50, quotes.first().price, 0.001)
        assertEquals("EUR", quotes.first().currency)
        assertEquals(123L, quotes.first().observedAt)
        assertTrue(quotes.all { it.productId == "demo_galaxy_s26_ultra" })
        assertTrue(quotes.all { it.sourceUrl.startsWith("https://") })
        val pcComponentesQuote = quotes.first { it.storeName == "PcComponentes" }
        assertEquals("pccomponentes", pcComponentesQuote.storeId)
        assertTrue(pcComponentesQuote.sourceUrl.contains("google.com/shopping/product"))
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
    fun `migration 2 to 4 preserves watchlist and old products and stores verified price observations`() {
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
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
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

                migratedDatabase.priceObservationDao().insert(
                    PriceObservationEntity(
                        productId = "sample-product",
                        storeId = "sample-store",
                        price = 42.50,
                        observedAt = 1000L,
                        sourceUrl = "https://example.com/product",
                        sourceName = "Unit-test source",
                        isVerified = true
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

                assertEquals(1, migratedDatabase.priceObservationDao().getVerifiedObservationCount())
                val latestVerified = migratedDatabase.priceObservationDao().getLatestVerified("sample-product")
                assertNotNull(latestVerified)
                assertEquals(42.50, latestVerified?.price ?: 0.0, 0.001)
            }
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
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

        assertEquals(3, results.size)
        assertEquals("Unsafe HTTP", results[0].storeName)
        assertEquals(49.99, results[0].price, 0.001)
        assertTrue(results[0].isGoogleShoppingProductLink)
        assertTrue(results[0].sourceUrl.startsWith("https://www.google.com/search?"))
        assertTrue(results[0].sourceUrl.contains("tbm=shop"))
        assertTrue(results[0].sourceUrl.contains("q=Auriculares%20Sony%20WH-1000XM5"))

        assertEquals("PcComponentes", results[1].storeName)
        assertEquals(79.99, results[1].price, 0.001)
        assertTrue(results[1].isGoogleShoppingProductLink)
        assertEquals("Amazon.es", results[2].storeName)
        assertTrue(!results[2].isGoogleShoppingProductLink)
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

        assertEquals(4, report.rawResultCount)
        assertEquals(2, report.results.size)
        val amazonResult = report.results.single { it.storeName == "Amazon.es" }
        assertEquals("Sony WH1000XM5 Wireless Noise Canceling Headphones", amazonResult.title)
        assertEquals(249.99, amazonResult.price, 0.001)

        val fallbackResult = report.results.single { it.price == 229.99 }
        assertEquals("Sony WH-1000XM5 headphones", fallbackResult.title)
        assertTrue(fallbackResult.sourceUrl.startsWith("https://www.google.com/search?"))
        assertTrue(fallbackResult.sourceUrl.contains("tbm=shop"))
        assertTrue(fallbackResult.sourceUrl.contains("hl=es"))
        assertTrue(fallbackResult.sourceUrl.contains("gl=es"))
        assertTrue(fallbackResult.sourceUrl.contains("q=Sony%20WH-1000XM5%20headphones"))
        assertTrue(fallbackResult.isGoogleShoppingProductLink)

        assertEquals(1, report.rejectedAsIrrelevant)
        assertEquals(1, report.rejectedForPriceOrCurrency)
        assertEquals(0, report.rejectedForLink)
        assertTrue(report.emptyResultMessage("Sony WH-1000XM5").isNotBlank())
    }

    @Test
    fun `missing shopping links report the received result field names`() {
        val report = com.example.data.remote.SerpApiShoppingSearchReport(
            results = emptyList(),
            rawResultCount = 2,
            rejectedAsIrrelevant = 0,
            rejectedForPriceOrCurrency = 0,
            rejectedForLink = 2,
            rejectedOther = 0,
            firstResultFields = "title, price, link"
        )

        assertTrue(
            report.emptyResultMessage("Sony WH-1000XM5")
                .contains("Campos recibidos: title, price, link")
        )
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
    }

}
