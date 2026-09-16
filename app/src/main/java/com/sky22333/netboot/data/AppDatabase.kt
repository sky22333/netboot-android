package com.sky22333.netboot.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IsoAssetEntity::class,
        DownloadTaskEntity::class,
        DownloadSegmentEntity::class,
        BootProfileEntity::class,
        RuntimeEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun isoDao(): IsoDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bootProfileDao(): BootProfileDao
    abstract fun runtimeEventDao(): RuntimeEventDao

    companion object {
        /**
         * Adds the configurable full-DHCP address pool.
         *
         * Blanks mean "derive a safe pool for the selected network at runtime", so no subnet is
         * assumed for profiles saved before this column existed.
         */
        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE boot_profiles ADD COLUMN dhcpPoolStart TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE boot_profiles ADD COLUMN dhcpPoolEnd TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

