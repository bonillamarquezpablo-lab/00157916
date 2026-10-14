package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedProductDao {
    @Query("SELECT * FROM tracked_products ORDER BY trackedAtTimestamp DESC")
    fun getAllTracked(): Flow<List<TrackedProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: TrackedProductEntity)

    @Query("DELETE FROM tracked_products WHERE productId = :productId")
    suspend fun delete(productId: String)

    @Query("SELECT * FROM tracked_products WHERE productId = :productId LIMIT 1")
    suspend fun getById(productId: String): TrackedProductEntity?

    @Query("UPDATE tracked_products SET targetAlertPrice = :targetPrice WHERE productId = :productId")
    suspend fun updateTargetPrice(productId: String, targetPrice: Double?)
 
    /** Remove tracking rows left behind by deleted products. */
    @Query("DELETE FROM tracked_products WHERE productId NOT IN (SELECT id FROM products)")
    suspend fun deleteTrackingForMissingProducts(): Int

    /** Remove tracking attached to explicit legacy demo rows. */
    @Query("DELETE FROM tracked_products WHERE productId IN (SELECT id FROM products WHERE id LIKE 'demo_%' OR lower(athMinDate) LIKE '%demostración%' OR lower(athMinStore) LIKE '%demostración%')")
    suspend fun deleteTrackingForDemoProducts(): Int
}
