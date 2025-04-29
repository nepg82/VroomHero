package com.example.vroomhero

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SpeedLimitDao {
    @Query("SELECT * FROM speed_limits WHERE roadId = :roadId")
    suspend fun getSpeedLimit(roadId: String): SpeedLimitEntity?

    @Insert
    suspend fun insert(speedLimit: SpeedLimitEntity)
}