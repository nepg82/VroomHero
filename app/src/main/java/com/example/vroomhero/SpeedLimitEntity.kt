package com.example.vroomhero

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_limits")
data class SpeedLimitEntity(
    @PrimaryKey val roadId: String,
    val speedLimit: Int
)