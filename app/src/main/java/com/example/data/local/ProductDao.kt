package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ProductCategoryCountRow(
    @ColumnInfo(name = "categoryName") val categoryName: String,
    @ColumnInfo(name = "productCount") val productCount: Int,
    @ColumnInfo(name = "storeCount") val storeCount: Int
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY title ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getProductCount(): Int


    /** Load a single category when the user opens it instead of converting the whole catalogue. */
    @Query("SELECT * FROM products WHERE category = :category ORDER BY title ASC")
    fun getProductsByCategory(category: String): Flow<List<ProductEntity>>

    /**
     * Compact dashboard summary computed inside SQLite. It counts only catalogue entries that
     * Mercado can surface (verified products, watched products, products with validated history,
     * or explicit pending live-search candidates). Retailer IDs from saved observations are
     * included and deduplicated, without constructing every domain Product on the home screen.
     */
    @Query(
        "SELECT categoryName, COUNT(DISTINCT productId) AS productCount, " +
            "COUNT(DISTINCT CASE WHEN TRIM(storeId) <> '' THEN storeId END) AS storeCount " +
            "FROM (" +
            "SELECT p.category AS categoryName, p.id AS productId, p.currentStoreId AS storeId " +
            "FROM products p WHERE " + MARKETPLACE_VISIBLE_PRODUCT_SQL +
            " UNION ALL " +
            "SELECT p.category AS categoryName, p.id AS productId, o.storeId AS storeId " +
            "FROM products p INNER JOIN price_observations o ON o.productId = p.id " +
            "WHERE (" + MARKETPLACE_VISIBLE_PRODUCT_SQL + ") AND o.isVerified = 1 " +
            "AND upper(trim(o.currency)) = 'EUR' AND o.price >= 0.5 AND o.price <= 10000.0 " +
            "AND trim(o.productTitle) <> '' AND o.observedAt > 0" +
            ") GROUP BY categoryName"
    )
    fun observeMarketplaceCategoryCounts(): Flow<List<ProductCategoryCountRow>>

    @Query("SELECT * FROM products ORDER BY title ASC")
    suspend fun getAllProductsSnapshot(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :productId LIMIT 1")
    suspend fun getProductById(productId: String): ProductEntity?

    /**
     * Reconcile the cached flag only from minimally valid live observations. Full seller/model
     * provenance is checked in ProductRepository as well; malformed legacy rows remain stored.
     */
    @Query(
        "UPDATE products SET isPriceVerified = CASE WHEN id IN (" +
            "SELECT DISTINCT productId FROM price_observations " +
            "WHERE isVerified = 1 AND lower(evidenceType) = 'live' " +
            "AND lower(trim(sourceUrl)) LIKE 'https://%' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "AND upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND trim(storeName) <> '' " +
            "AND observedAt > 0 AND observedAt <= :nowMillis" +
            ") THEN 1 ELSE 0 END"
    )
    suspend fun markProductsWithVerifiedObservations(
        nowMillis: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity)

    /** Inserts a live-search catalogue placeholder without overwriting a concurrently saved product. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(product: ProductEntity): Long

    /**
     * Atomically update a provisional search ficha only while it is still unverified,
     * untracked and has no price observations. This closes the race between the earlier
     * batched safety checks and a user saving/verifying the same product concurrently.
     */
    @Query(
        "UPDATE products SET title = :title, brand = :brand, category = :category, " +
            "currentPrice = :currentPrice, athMinPrice = :athMinPrice, athMinDate = :athMinDate, " +
            "athMinStore = :athMinStore, athMaxPrice = :athMaxPrice, avg90dPrice = :avg90dPrice, " +
            "currentStoreId = :currentStoreId, storeUrl = :storeUrl, imageUrl = :imageUrl, " +
            "googleProductId = :googleProductId " +
            "WHERE id = :productId AND isPriceVerified = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM tracked_products WHERE productId = :productId) " +
            "AND NOT EXISTS (SELECT 1 FROM price_observations WHERE productId = :productId)"
    )
    suspend fun updateUnverifiedLiveSearchCandidate(
        productId: String,
        title: String,
        brand: String,
        category: String,
        currentPrice: Double,
        athMinPrice: Double,
        athMinDate: String,
        athMinStore: String,
        athMaxPrice: Double,
        avg90dPrice: Double,
        currentStoreId: String,
        storeUrl: String,
        imageUrl: String?,
        googleProductId: String?
    ): Int

    @Query("UPDATE products SET currentPrice = :newPrice, athMinPrice = :athMin, athMinDate = :athDate, athMinStore = :athStore WHERE id = :productId")
    suspend fun updatePriceDrop(productId: String, newPrice: Double, athMin: Double, athDate: String, athStore: String)

    @Query("UPDATE products SET isPriceVerified = :isVerified WHERE id = :productId")
    suspend fun updatePriceVerification(productId: String, isVerified: Boolean)

    companion object {
        private const val MARKETPLACE_VISIBLE_PRODUCT_SQL =
            "p.isPriceVerified = 1 " +
                "OR EXISTS (SELECT 1 FROM tracked_products t WHERE t.productId = p.id) " +
                "OR EXISTS (SELECT 1 FROM price_observations v WHERE v.productId = p.id " +
                "AND v.isVerified = 1 AND upper(trim(v.currency)) = 'EUR' " +
                "AND v.price >= 0.5 AND v.price <= 10000.0 AND trim(v.productTitle) <> '' " +
                "AND v.observedAt > 0) " +
                "OR EXISTS (SELECT 1 FROM price_observations legacy WHERE legacy.productId = p.id " +
                "AND lower(trim(legacy.sourceUrl)) LIKE 'https://%' " +
                "AND lower(legacy.sourceUrl) NOT LIKE '%google.com%' " +
                "AND lower(legacy.sourceUrl) NOT LIKE '%web.archive.org%' " +
                "AND lower(legacy.sourceUrl) NOT LIKE '%arquivo.pt%' " +
                "AND trim(legacy.storeName) <> '' AND trim(legacy.productTitle) <> '' " +
                "AND legacy.observedAt > 0) " +
                "OR ((p.id LIKE 'online_candidate_%' " +
                "OR lower(trim(p.athMinDate)) = 'pendiente de verificación') " +
                "AND p.currentPrice > 0)"
    }
}
