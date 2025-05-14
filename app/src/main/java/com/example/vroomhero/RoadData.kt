package com.example.vroomhero

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "road_data")
data class RoadData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Double?,
    val roadName: String?,
    val timestamp: Long
)