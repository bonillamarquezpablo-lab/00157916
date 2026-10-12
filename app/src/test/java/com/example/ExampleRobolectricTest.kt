package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import com.example.data.model.Product
import com.example.data.repository.ProductRepository
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

            // Even a mistakenly set verification flag must not make an unrelated domain trusted.
            database.priceObservationDao().insert(
                PriceObservationEntity(
                    productId = "sony_wh1000xm5",
                    storeId = "amazon_es",
                    price = 2.00,
                    observedAt = System.currentTimeMillis() + 2000,
                    sourceUrl = "https://example.com/invalid-source",
                    sourceName = "Invalid source fixture",
                    isVerified = true
                )
            )
            database.productDao().updatePriceVerification("sony_wh1000xm5", true)

            val sourceBackedProducts: List<Product> = withTimeout<List<Product>>(10_000) {
                repository.allProductsStream.first { rows ->
                    rows.firstOrNull { row -> row.id == "sony_wh1000xm5" }?.isPriceVerified == true
                }
            }
            val sourceBackedSony = sourceBackedProducts.first { it.id == "sony_wh1000xm5" }
            assertEquals("Invalid retailer host must be ignored", 222.50, sourceBackedSony.currentPrice, 0.001)
            assertEquals(222.50, sourceBackedSony.athMinPrice, 0.001)
            assertEquals("Only the valid Amazon observation should enter history", 1, sourceBackedSony.priceHistory.size)
            assertEquals(222.50, sourceBackedSony.priceHistory.single().price, 0.001)
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
    fun `migration 2 to 5 preserves data and defaults metadata verification flags to false`() {
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
                assertFalse("Existing PVP must not be treated as independently verified", preservedProduct?.isOfficialPvpVerified ?: true)
                assertFalse("Existing rating must not be treated as independently verified", preservedProduct?.isRatingVerified ?: true)
                assertFalse("Existing EAN must not be treated as independently verified", preservedProduct?.isEanVerified ?: true)

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
}
