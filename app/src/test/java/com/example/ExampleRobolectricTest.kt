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
import com.example.data.remote.groupOnlineShoppingResults
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
    fun `migration 2 to 5 preserves watchlist and old products and stores verified price observations`() {
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
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
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


    @Test
    fun `online search stores real offers once per store and day and rejects used listings and low outliers`() = runBlocking<Unit> {
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
                    price = 249.99,
                    storeName = "PcComponentes",
                    sourceUrl = "https://www.pccomponentes.com/sony-wh-1000xm5",
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
                price = 5.82,
                storeName = "Wasserman.eu",
                sourceUrl = "https://www.wasserman.eu/ugreen-usb-hub",
                observedAt = now
            )
            val filteredResults = repository.filterOnlineSearchResults(
                listOf(suspiciousRedmi, rentalPhoneWithNormalPrice, normalNewPhone, cheapLegitimateAccessory)
            )
            assertFalse(
                "A €9.90 Redmi phone listing is implausible for a new-in-store offer",
                filteredResults.any { it.title.contains("Redmi 17", ignoreCase = true) && it.price < 45.0 }
            )
            assertFalse(
                "Offers from the Rentik renting provider must not be stored as new-in-store offers",
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
            assertTrue(
                "Second-hand marketplaces and subscription listings must be excluded",
                repository.filterOnlineSearchResults(excludedSellerResults, "Xiaomi Redmi 17").isEmpty()
            )

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
            assertFalse(
                "An accessory must not appear as a whole device for a model-specific search",
                filteredVacuumListings.any { it.title.contains("Mop Kit", ignoreCase = true) }
            )
            assertEquals(
                "The three legitimate vacuum offers should remain",
                3,
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
            assertEquals("A model-specific query must exclude other models and cases", 2, strictMatches.size)
            assertTrue(strictMatches.all { it.title.contains("Redmi 17", ignoreCase = true) })
            val grouped = groupOnlineShoppingResults(strictMatches)
            assertEquals("Repeated seller listings of one model make one product card", 1, grouped.size)
            assertEquals(2, grouped.single().offers.size)
            assertEquals(179.99, grouped.single().minPrice, 0.001)

            val inserted = repository.recordOnlineSearchResults(results, "GOOGLE_SHOPPING")
            assertEquals("Only the two plausible new offers should be stored", 2, inserted)
            val stored = withTimeout<List<PriceObservationEntity>>(10_000) {
                database.priceObservationDao().observeAllVerified().first { it.size >= 2 }
            }
            assertEquals(2, stored.size)
            val sonyProductId = stored.first().productId
            assertTrue("Both retailer offers must attach to one saved product", stored.all { it.productId == sonyProductId })
            assertTrue(stored.all { it.productTitle == "Sony WH-1000XM5 Wireless Headphones" })
            val persistedProduct = database.productDao().getProductById(sonyProductId)
            assertNotNull("Online searches must add a real product row to Room", persistedProduct)
            assertTrue("Searched products must be marked as source-verified", persistedProduct?.isPriceVerified == true)
            assertTrue(stored.all { it.currency == "EUR" && it.sourceType == "Google Shopping" })
            assertTrue(stored.any { it.storeName == "PcComponentes" && it.sourceUrl.contains("pccomponentes.com") })
            assertTrue(stored.any { it.storeName == "Amazon.es" && it.sourceUrl.contains("amazon.es") })

            val duplicate = results.first().copy(price = 245.00, observedAt = now + 60_000L)
            assertEquals(0, repository.recordOnlineSearchResults(listOf(duplicate), "GOOGLE_SHOPPING"))
            assertEquals(2, database.priceObservationDao().getVerifiedObservationCount())

            // A following day's query must be retained as a second dated history point.
            val nextDay = now + 24L * 60L * 60L * 1000L
            val tomorrowResults = listOf(
                results[0].copy(price = 229.99, observedAt = nextDay),
                results[1].copy(price = 224.99, observedAt = nextDay)
            )
            assertEquals(2, repository.recordOnlineSearchResults(tomorrowResults, "GOOGLE_SHOPPING"))
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
            assertEquals(4, database.priceObservationDao().getVerifiedObservationCount())
        } finally {
            database.close()
        }
    }

}
