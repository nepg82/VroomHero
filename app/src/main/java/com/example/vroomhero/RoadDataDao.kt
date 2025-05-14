package com.example.vroomhero

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RoadDataDao {
    @Insert
    suspend fun insert(roadData: RoadData)

    @Query("SELECT * FROM road_data WHERE latitude BETWEEN :lat - 0.0001 AND :lat + 0.0001 AND longitude BETWEEN :lon - 0.0001 AND :lon + 0.0001 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getRoadDataByLocation(lat: Double, lon: Double): RoadData?
}