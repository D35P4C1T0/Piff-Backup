package com.d35p4c1t0.piffbackup.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        StorageBoxProfileEntity::class,
        FolderMappingEntity::class,
        MediaCheckpointEntity::class,
        PendingBackupJobEntity::class,
        PendingRootWorkEntity::class,
        BackupRunEntity::class,
        LocalFileMetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PiffBackupDatabase : RoomDatabase() {
    abstract fun dao(): PiffBackupDao

    companion object {
        const val DATABASE_NAME = "piffbackup.db"
        const val SCHEMA_VERSION = 1

        fun open(context: Context, name: String = DATABASE_NAME): PiffBackupDatabase =
            Room.databaseBuilder(context, PiffBackupDatabase::class.java, name)
                .setQueryCoroutineContext(Dispatchers.IO)
                .setDriver(AndroidSQLiteDriver())
                .build()

        fun inMemory(context: Context): PiffBackupDatabase =
            Room.inMemoryDatabaseBuilder(context, PiffBackupDatabase::class.java)
                .setQueryCoroutineContext(Dispatchers.IO)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
