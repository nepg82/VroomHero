package com.example.vroomhero

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RoadData::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roadDataDao(): RoadDataDao
}