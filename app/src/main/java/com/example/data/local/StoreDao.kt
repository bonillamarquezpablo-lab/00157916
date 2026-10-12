package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {
    @Query("SELECT * FROM spanish_stores ORDER BY name ASC")
    fun getAllStores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM spanish_stores WHERE id = :storeId LIMIT 1")
    suspend fun getStoreById(storeId: String): StoreEntity?

    @Query("SELECT COUNT(*) FROM spanish_stores")
    suspend fun getStoreCount(): Int

    @Query("SELECT * FROM spanish_stores")
    suspend fun getAllStoresSnapshot(): List<StoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stores: List<StoreEntity>)
}
