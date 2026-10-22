package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDiscoveryDao {
    @Query(
        "SELECT * FROM product_discoveries WHERE status = 'pending' " +
            "ORDER BY lastObservedAt DESC, title COLLATE NOCASE ASC"
    )
    fun observePending(): Flow<List<ProductDiscoveryEntity>>

    @Query("SELECT * FROM product_discoveries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProductDiscoveryEntity?

    @Query("SELECT * FROM product_discoveries WHERE identityKey = :identityKey LIMIT 1")
    suspend fun getByIdentityKey(identityKey: String): ProductDiscoveryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscovery(discovery: ProductDiscoveryEntity): Long

    @Update
    suspend fun updateDiscovery(discovery: ProductDiscoveryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservation(observation: DiscoveryPriceObservationEntity): Long

    @Query(
        "SELECT * FROM discovery_price_observations WHERE discoveryId = :discoveryId " +
            "ORDER BY observedAt ASC, id ASC"
    )
    suspend fun getObservations(discoveryId: String): List<DiscoveryPriceObservationEntity>

    @Query("SELECT COUNT(*) FROM discovery_price_observations WHERE discoveryId = :discoveryId")
    suspend fun getObservationCount(discoveryId: String): Int

    @Query("UPDATE product_discoveries SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int
}
