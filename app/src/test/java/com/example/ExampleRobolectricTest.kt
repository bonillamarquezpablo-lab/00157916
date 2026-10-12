package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Resource and SQLite migration tests run on API 35 using the Java 17 CI runtime.
@Config(sdk = [35])
class ExampleRobolectricTest {

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
}
