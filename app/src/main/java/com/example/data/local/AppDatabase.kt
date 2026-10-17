package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StoreEntity::class,
        ProductEntity::class,
        TrackedProductEntity::class,
        PriceObservationEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun productDao(): ProductDao
    abstract fun trackedProductDao(): TrackedProductDao

    abstract fun priceObservationDao(): PriceObservationDao

    companion object {
        /**
         * Adds the price-observation table without deleting existing products or watchlists.
         * The migration is deliberately additive so local user tracking survives app updates.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_observations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`productId` TEXT NOT NULL, " +
                        "`storeId` TEXT NOT NULL, " +
                        "`price` REAL NOT NULL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`observedAt` INTEGER NOT NULL, " +
                        "`sourceUrl` TEXT NOT NULL, " +
                        "`sourceName` TEXT NOT NULL, " +
                        "`isVerified` INTEGER NOT NULL, " +
                        "`verificationNote` TEXT)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_price_observations_productId_observedAt` " +
                        "ON `price_observations` (`productId`, `observedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_price_observations_storeId_observedAt` " +
                        "ON `price_observations` (`storeId`, `observedAt`)"
                )
            }
        }

        /**
         * Adds a verification flag to existing product rows. Existing entries remain unverified.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `isPriceVerified` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Stores the source title, merchant name and provider for real online search observations.
         * Existing verified rows are enriched from the existing catalogue without manufacturing
         * timestamps or prices.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `price_observations` ADD COLUMN `productTitle` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `price_observations` ADD COLUMN `storeName` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `price_observations` ADD COLUMN `sourceType` TEXT NOT NULL DEFAULT 'Google Shopping'"
                )
                database.execSQL(
                    "UPDATE price_observations SET productTitle = COALESCE(" +
                        "(SELECT title FROM products WHERE products.id = price_observations.productId), '') " +
                        "WHERE productTitle = ''"
                )
                database.execSQL(
                    "UPDATE price_observations SET storeName = COALESCE(" +
                        "(SELECT name FROM spanish_stores WHERE spanish_stores.id = price_observations.storeId), storeId) " +
                        "WHERE storeName = ''"
                )
                database.execSQL(
                    "UPDATE price_observations SET sourceType = CASE " +
                        "WHEN lower(sourceName) LIKE '%amazon%' THEN 'Amazon.es' " +
                        "ELSE 'Google Shopping' END"
                )
            }
        }

        /** Records whether an observation links directly to a merchant or via Google Shopping. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `price_observations` ADD COLUMN `linkType` TEXT NOT NULL DEFAULT 'directo'"
                )
                database.execSQL(
                    "UPDATE `price_observations` SET `linkType` = 'Google Shopping' " +
                        "WHERE lower(`sourceUrl`) LIKE '%://google.%' " +
                        "OR lower(`sourceUrl`) LIKE '%://%.google.%'"
                )
            }
        }

        /** Keeps existing products and history while adding the Google Shopping catalog ID. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `googleProductId` TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Separates the time a price was acquired from the historical observation date and
         * marks archive-backed rows explicitly so old demo/legacy live rows remain excluded.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE price_observations ADD COLUMN collectedAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "UPDATE price_observations SET collectedAt = observedAt WHERE collectedAt = 0"
                )
                database.execSQL(
                    "ALTER TABLE price_observations ADD COLUMN evidenceType TEXT NOT NULL DEFAULT 'live'"
                )
                database.execSQL(
                    "ALTER TABLE price_observations ADD COLUMN sourcePageUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Marks rows as archive-backed only when their stored source URL is itself an
         * Internet Archive snapshot. No observations or product rows are deleted.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE price_observations SET evidenceType = 'archive' WHERE " +
                        "lower(sourceUrl) LIKE 'https://web.archive.org/web/%' OR " +
                        "lower(sourceUrl) LIKE 'http://web.archive.org/web/%'"
                )
            }
        }

        /**
         * Metadata provenance is independent of a verified current quote.
         * Existing catalog values default to unverified; no rows or observations are deleted.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `isOfficialPvpVerified` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `isRatingVerified` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `isEanVerified` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minimo_spanish_market.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
