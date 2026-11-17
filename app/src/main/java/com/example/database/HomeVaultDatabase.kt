package com.example.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PairedServerEntity::class,
        TransferEntity::class,
        RecentFileEntity::class,
        ServerActivityEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class HomeVaultDatabase : RoomDatabase() {
    abstract fun pairedServerDao(): PairedServerDao
    abstract fun transferDao(): TransferDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun serverActivityDao(): ServerActivityDao

    companion object {
        fun build(context: Context): HomeVaultDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                HomeVaultDatabase::class.java,
                "homevault_remote.db"
            ).fallbackToDestructiveMigration(true).build()
        }
    }
}
