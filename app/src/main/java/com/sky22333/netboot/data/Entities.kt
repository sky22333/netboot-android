package com.sky22333.netboot.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "iso_assets", indices = [Index(value = ["filePath"], unique = true)])
data class IsoAssetEntity(
    @PrimaryKey val id: String,
    val product: String,
    val edition: String,
    val language: String,
    val architecture: String,
    val source: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val sha256: String,
    val createdAt: Long,
    val state: String,
    val driverHash: String = "",
    val driverName: String = "",
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = IsoAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["isoAssetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("isoAssetId")],
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val isoAssetId: String,
    val temporaryUrl: String,
    val expiresAt: Long?,
    val etag: String?,
    val lastModified: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val connectionCount: Int,
    val state: String,
    val errorCode: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "download_segments",
    primaryKeys = ["taskId", "segmentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class DownloadSegmentEntity(
    val taskId: String,
    val segmentIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val currentByte: Long,
)

/** Blank DHCP pool bounds derive from the selected network; proxy mode ignores them. */
@Entity(tableName = "boot_profiles")
data class BootProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    val interfaceName: String,
    val listenAddress: String,
    val advertiseAddress: String,
    val httpPort: Int,
    val bootFile: String,
    val menuJson: String,
    val dhcpPoolStart: String,
    val dhcpPoolEnd: String,
    val updatedAt: Long,
)

@Entity(tableName = "runtime_events", indices = [Index("timestamp")])
data class RuntimeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val source: String,
    val eventCode: String,
    val argumentsJson: String,
)

object IsoState {
    const val Importing = "importing"
    const val Downloading = "downloading"
    const val Verifying = "verifying"
    const val Ready = "ready"
    const val Failed = "failed"
}

object DownloadState {
    const val Queued = "queued"
    const val Running = "running"
    const val Paused = "paused"
    const val Verifying = "verifying"
    const val Completed = "completed"
    const val Failed = "failed"
    const val Cancelled = "cancelled"
}

