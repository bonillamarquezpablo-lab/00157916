package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY title ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getProductCount(): Int

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

    @Query("UPDATE products SET currentPrice = :newPrice, athMinPrice = :athMin, athMinDate = :athDate, athMinStore = :athStore WHERE id = :productId")
    suspend fun updatePriceDrop(productId: String, newPrice: Double, athMin: Double, athDate: String, athStore: String)

    @Query("UPDATE products SET isPriceVerified = :isVerified WHERE id = :productId")
    suspend fun updatePriceVerification(productId: String, isVerified: Boolean)
}
