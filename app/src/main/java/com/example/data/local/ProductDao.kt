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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity)

    @Query("UPDATE products SET currentPrice = :newPrice, athMinPrice = :athMin, athMinDate = :athDate, athMinStore = :athStore WHERE id = :productId")
    suspend fun updatePriceDrop(productId: String, newPrice: Double, athMin: Double, athDate: String, athStore: String)

    @Query("UPDATE products SET isPriceVerified = :isVerified WHERE id = :productId")
    suspend fun updatePriceVerification(productId: String, isVerified: Boolean)
}
