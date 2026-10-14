package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationPreservationTest {

    @Test
    fun room7To9PreservesExistingRealDataAndTrackingTarget() {
        assertMigrationPreservesRealData(startVersion = 7)
    }

    @Test
    fun room8To9PreservesExistingRealDataAndLeavesAmbiguousRowUnchanged() {
        assertMigrationPreservesRealData(startVersion = 8)
    }

    private fun assertMigrationPreservesRealData(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-preservation-v$startVersion.db"
        context.deleteDatabase(name)
        val originalObservedAt = 1_791_234_567_000L
        val trackedAt = 1_791_000_000_000L

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createLegacySchema(db, startVersion)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Represent an already-installed older app. Production migrations are
                        // deliberately exercised by the Room instance opened below.
                    }
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            seedLegacyRows(db, startVersion, originalObservedAt, trackedAt)
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()

        try {
            val products = migrated.productDao().getAllProductsSnapshot()
            assertEquals("Product must survive Room $startVersion -> 9", 1, products.size)
            val product = products.single()
            assertEquals("real-product-1", product.id)
            assertEquals(349.95, product.currentPrice, 0.001)
            assertEquals("real-store-1", product.currentStoreId)
            assertEquals("https://shop.example/product/real-1", product.storeUrl)
            if (startVersion == 7) {
                assertEquals("real-catalog-777", product.googleProductId)
            } else {
                assertEquals("real-catalog-777", product.googleProductId)
            }

            val stores = migrated.storeDao().getAllStoresSnapshot()
            assertEquals(1, stores.size)
            assertEquals("real-store-1", stores.single().id)
            assertEquals("Comercio Real", stores.single().name)
            assertEquals("shop.example", stores.single().domain)

            val tracked = migrated.trackedProductDao().getById("real-product-1")
            assertNotNull("Followed product must survive the migration", tracked)
            assertEquals(275.0, tracked!!.targetAlertPrice!!, 0.001)
            assertEquals(false, tracked.isNotificationEnabled)
            assertEquals(trackedAt, tracked.trackedAtTimestamp)
            assertEquals("objetivo guardado por el usuario", tracked.notes)

            val observed = runBlocking {
                migrated.priceObservationDao().observeVerifiedHistory("real-product-1").first()
            }
            assertEquals("No genuine observations may be dropped by migration", if (startVersion == 8) 3 else 1, observed.size)
            val expectedLive = observed.first { it.sourceUrl == "https://shop.example/product/real-1" }
            assertEquals(101L, expectedLive.id)
            assertEquals(349.95, expectedLive.price, 0.001)
            assertEquals(originalObservedAt, expectedLive.observedAt)
            assertEquals("REAL · SerpApi", expectedLive.sourceName)
            assertEquals("Producto real guardado", expectedLive.productTitle)
            assertEquals("Comercio Real", expectedLive.storeName)
            assertTrue(expectedLive.isVerified)
            assertEquals("Google Shopping", expectedLive.sourceType)
            assertEquals("live", expectedLive.evidenceType)
            if (startVersion == 7) {
                assertEquals(originalObservedAt, expectedLive.collectedAt)
            } else {
                assertEquals(1_791_234_568_000L, expectedLive.collectedAt)
            }

            if (startVersion == 8) {
                val archive = observed.first { it.id == 102L }
                assertEquals("archive", archive.evidenceType)
                assertEquals(9.99, archive.price, 0.001)

                val ambiguous = observed.first { it.id == 103L }
                assertEquals("A doubtful merchant row must remain live", "live", ambiguous.evidenceType)
                assertEquals(17.49, ambiguous.price, 0.001)
                assertEquals("https://shop.example/product/ambiguous", ambiguous.sourceUrl)
                assertEquals("Wayback partner feed", ambiguous.sourceName)
                assertEquals("Google Shopping", ambiguous.sourceType)
                assertEquals(1_791_234_569_000L, ambiguous.observedAt)
                assertEquals(1_791_234_570_000L, ambiguous.collectedAt)
                assertEquals("", ambiguous.sourcePageUrl)
                assertEquals(
                    PriceObservationEntity(
                        id = 103L,
                        productId = "real-product-1",
                        storeId = "real-store-1",
                        price = 17.49,
                        currency = "EUR",
                        observedAt = 1_791_234_569_000L,
                        sourceUrl = "https://shop.example/product/ambiguous",
                        sourceName = "Wayback partner feed",
                        isVerified = true,
                        verificationNote = "metadata dudosos de proveedor",
                        productTitle = "Producto real guardado",
                        storeName = "Comercio Real",
                        sourceType = "Google Shopping",
                        linkType = "directo",
                        collectedAt = 1_791_234_570_000L,
                        evidenceType = "live",
                        sourcePageUrl = ""
                    ),
                    ambiguous
                )
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun createLegacySchema(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL(
            """CREATE TABLE spanish_stores (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                domain TEXT NOT NULL,
                brandColorHex TEXT NOT NULL,
                category TEXT NOT NULL,
                physicalStoresInSpain INTEGER NOT NULL,
                storeCountApprox INTEGER NOT NULL,
                headquartersInSpain TEXT NOT NULL,
                description TEXT NOT NULL
            )"""
        )
        val googleProductIdColumn = if (version >= 7) ", googleProductId TEXT DEFAULT NULL" else ""
        db.execSQL(
            """CREATE TABLE products (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                brand TEXT NOT NULL,
                category TEXT NOT NULL,
                currentPrice REAL NOT NULL,
                athMinPrice REAL NOT NULL,
                athMinDate TEXT NOT NULL,
                athMinStore TEXT NOT NULL,
                athMaxPrice REAL NOT NULL,
                avg90dPrice REAL NOT NULL,
                officialPvp REAL NOT NULL,
                currentStoreId TEXT NOT NULL,
                storeUrl TEXT NOT NULL,
                ean TEXT NOT NULL,
                imageResName TEXT,
                imageUrl TEXT,
                rating REAL NOT NULL,
                reviewCount INTEGER NOT NULL,
                wasRecentlyInflated INTEGER NOT NULL,
                fakePriorPrice REAL,
                isPriceVerified INTEGER NOT NULL DEFAULT 0$googleProductIdColumn
            )"""
        )
        db.execSQL(
            """CREATE TABLE tracked_products (
                productId TEXT NOT NULL PRIMARY KEY,
                targetAlertPrice REAL,
                isNotificationEnabled INTEGER NOT NULL,
                trackedAtTimestamp INTEGER NOT NULL,
                notes TEXT NOT NULL
            )"""
        )
        val newerColumns = if (version >= 8) {
            ", collectedAt INTEGER NOT NULL DEFAULT 0, evidenceType TEXT NOT NULL DEFAULT 'live', sourcePageUrl TEXT NOT NULL DEFAULT ''"
        } else ""
        db.execSQL(
            """CREATE TABLE price_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                productId TEXT NOT NULL,
                storeId TEXT NOT NULL,
                price REAL NOT NULL,
                currency TEXT NOT NULL,
                observedAt INTEGER NOT NULL,
                sourceUrl TEXT NOT NULL,
                sourceName TEXT NOT NULL,
                isVerified INTEGER NOT NULL,
                verificationNote TEXT,
                productTitle TEXT NOT NULL DEFAULT '',
                storeName TEXT NOT NULL DEFAULT '',
                sourceType TEXT NOT NULL DEFAULT 'Google Shopping',
                linkType TEXT NOT NULL DEFAULT 'directo'$newerColumns
            )"""
        )
        db.execSQL(
            "CREATE INDEX index_price_observations_productId_observedAt ON price_observations(productId, observedAt)"
        )
        db.execSQL(
            "CREATE INDEX index_price_observations_storeId_observedAt ON price_observations(storeId, observedAt)"
        )
    }

    private fun seedLegacyRows(
        db: SupportSQLiteDatabase,
        version: Int,
        observedAt: Long,
        trackedAt: Long
    ) {
        db.execSQL(
            "INSERT INTO spanish_stores VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf("real-store-1", "Comercio Real", "shop.example", "#123456", "Electrónica", 1, 3, "España", "Comercio real guardado")
        )
        val hasGoogleId = version >= 7
        val productColumns = "id, title, brand, category, currentPrice, athMinPrice, athMinDate, athMinStore, athMaxPrice, avg90dPrice, officialPvp, currentStoreId, storeUrl, ean, imageResName, imageUrl, rating, reviewCount, wasRecentlyInflated, fakePriorPrice, isPriceVerified" +
            if (hasGoogleId) ", googleProductId" else ""
        val productValues = if (hasGoogleId) {
            arrayOf<Any?>(
                "real-product-1", "Producto real guardado", "Marca Real", "TV_AUDIO",
                349.95, 329.95, "01/10/2026", "Comercio Real", 399.95, 0.0, 0.0,
                "real-store-1", "https://shop.example/product/real-1", "real-sku-123",
                null, null, 0.0, 0, 0, null, 1, "real-catalog-777"
            )
        } else {
            arrayOf<Any?>(
                "real-product-1", "Producto real guardado", "Marca Real", "TV_AUDIO",
                349.95, 329.95, "01/10/2026", "Comercio Real", 399.95, 0.0, 0.0,
                "real-store-1", "https://shop.example/product/real-1", "real-sku-123",
                null, null, 0.0, 0, 0, null, 1
            )
        }
        db.execSQL(
            "INSERT INTO products ($productColumns) VALUES (${List(productValues.size) { "?" }.joinToString(", ")})",
            productValues
        )
        db.execSQL(
            "INSERT INTO tracked_products VALUES(?, ?, ?, ?, ?)",
            arrayOf("real-product-1", 275.0, 0, trackedAt, "objetivo guardado por el usuario")
        )

        val regularColumns = "id, productId, storeId, price, currency, observedAt, sourceUrl, sourceName, isVerified, verificationNote, productTitle, storeName, sourceType, linkType"
        val baseRegular = arrayOf<Any?>(
            101L, "real-product-1", "real-store-1", 349.95, "EUR", observedAt,
            "https://shop.example/product/real-1", "REAL · SerpApi", 1,
            "Oferta validada antes de actualizar", "Producto real guardado", "Comercio Real",
            "Google Shopping", "directo"
        )
        if (version >= 8) {
            db.execSQL(
                "INSERT INTO price_observations ($regularColumns, collectedAt, evidenceType, sourcePageUrl) VALUES (${List(17) { "?" }.joinToString(", ")})",
                baseRegular + arrayOf<Any?>(1_791_234_568_000L, "live", "")
            )
            db.execSQL(
                "INSERT INTO price_observations ($regularColumns, collectedAt, evidenceType, sourcePageUrl) VALUES (${List(17) { "?" }.joinToString(", ")})",
                arrayOf<Any?>(
                    102L, "real-product-1", "archive-store", 9.99, "EUR", 1_775_000_000_000L,
                    "https://web.archive.org/web/20260401000000id_/https://shop.example/product/real-1",
                    "Internet Archive (Wayback Machine)", 1, "captura validada", "Producto real guardado",
                    "Comercio Real", "Internet Archive", "Archivo histórico",
                    1_791_234_568_500L, "live", "https://shop.example/product/real-1"
                )
            )
            db.execSQL(
                "INSERT INTO price_observations ($regularColumns, collectedAt, evidenceType, sourcePageUrl) VALUES (${List(17) { "?" }.joinToString(", ")})",
                arrayOf<Any?>(
                    103L, "real-product-1", "real-store-1", 17.49, "EUR", 1_791_234_569_000L,
                    "https://shop.example/product/ambiguous", "Wayback partner feed", 1,
                    "metadata dudosos de proveedor", "Producto real guardado", "Comercio Real",
                    "Google Shopping", "directo", 1_791_234_570_000L, "live", ""
                )
            )
        } else {
            db.execSQL(
                "INSERT INTO price_observations ($regularColumns) VALUES (${List(14) { "?" }.joinToString(", ")})",
                baseRegular
            )
        }
    }
}
