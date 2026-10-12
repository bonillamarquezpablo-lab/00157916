package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.compose.ui.graphics.Color
import com.example.data.model.Store

@Entity(tableName = "spanish_stores")
data class StoreEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val domain: String,
    val brandColorHex: String,
    val category: String,
    val physicalStoresInSpain: Boolean,
    val storeCountApprox: Int,
    val headquartersInSpain: String,
    val description: String
) {
    fun toDomainStore(): Store {
        val color = try {
            Color(android.graphics.Color.parseColor(brandColorHex))
        } catch (e: Exception) {
            Color(0xFF047857)
        }
        return Store(
            id = id,
            name = name,
            domain = domain,
            brandColor = color,
            isVerifiedSpanish = true
        )
    }
}
