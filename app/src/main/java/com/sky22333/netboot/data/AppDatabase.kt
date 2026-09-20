package com.sky22333.netboot.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IsoAssetEntity::class,
        DownloadTaskEntity::class,
        DownloadSegmentEntity::class,
        BootProfileEntity::class,
        RuntimeEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun isoDao(): IsoDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bootProfileDao(): BootProfileDao
    abstract fun runtimeEventDao(): RuntimeEventDao
}

