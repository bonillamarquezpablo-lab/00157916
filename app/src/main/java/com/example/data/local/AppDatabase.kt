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
    version = 5,
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
         * Metadata flags default to false: a verified current price does not validate PVP,
         * seller rating or EAN. Existing values must not be treated as independently sourced.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
