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

    /** Remove legacy catalogue rows which never acquired a real, source-backed observation. */
    @Query("DELETE FROM products WHERE id NOT IN (SELECT DISTINCT productId FROM price_observations WHERE isVerified = 1)")
    suspend fun deleteProductsWithoutVerifiedObservations(): Int

    /** Remove legacy mock rows by explicit demo markers, even if old code marked their history verified. */
    @Query("DELETE FROM products WHERE id LIKE 'demo_%' OR lower(athMinDate) LIKE '%demostración%' OR lower(athMinStore) LIKE '%demostración%'")
    suspend fun deleteExplicitDemoProducts(): Int

    /** Reconcile legacy flags from the authoritative verified-observation table. */
    @Query("UPDATE products SET isPriceVerified = 1 WHERE id IN (SELECT DISTINCT productId FROM price_observations WHERE isVerified = 1)")
    suspend fun markProductsWithVerifiedObservations(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity)

    @Query("UPDATE products SET currentPrice = :newPrice, athMinPrice = :athMin, athMinDate = :athDate, athMinStore = :athStore WHERE id = :productId")
    suspend fun updatePriceDrop(productId: String, newPrice: Double, athMin: Double, athDate: String, athStore: String)

    @Query("UPDATE products SET isPriceVerified = :isVerified WHERE id = :productId")
    suspend fun updatePriceVerification(productId: String, isVerified: Boolean)
}
