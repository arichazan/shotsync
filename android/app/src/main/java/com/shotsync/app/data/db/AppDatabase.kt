package com.shotsync.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun statusToString(s: QueueStatus): String = s.name

    @TypeConverter
    fun stringToStatus(s: String): QueueStatus = QueueStatus.valueOf(s)
}

@Database(entities = [QueueItem::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shotsync.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
