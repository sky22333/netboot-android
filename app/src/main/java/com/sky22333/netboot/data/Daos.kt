package com.sky22333.netboot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface IsoDao {
    @Query("SELECT * FROM iso_assets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IsoAssetEntity>>

    @Query("SELECT * FROM iso_assets WHERE id = :id")
    suspend fun find(id: String): IsoAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: IsoAssetEntity)

    @Query("UPDATE iso_assets SET state = :state, fileSize = :size, sha256 = :sha256 WHERE id = :id")
    suspend fun complete(id: String, state: String, size: Long, sha256: String)

    @Query("UPDATE iso_assets SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("DELETE FROM iso_assets WHERE id = :id AND state NOT IN ('downloading', 'verifying')")
    suspend fun deleteIfIdle(id: String): Int
}

@Dao
abstract class DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    abstract suspend fun findTask(id: String): DownloadTaskEntity?

    @Query("""
        SELECT download_tasks.* FROM download_tasks
        INNER JOIN iso_assets ON iso_assets.id = download_tasks.isoAssetId
        WHERE iso_assets.product = :product AND iso_assets.edition = :edition
          AND iso_assets.language = :language AND iso_assets.architecture = :architecture
          AND download_tasks.state IN ('queued', 'running', 'paused', 'verifying')
        LIMIT 1
    """)
    abstract suspend fun findActive(product: String, edition: String, language: String, architecture: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_segments WHERE taskId = :taskId ORDER BY segmentIndex")
    abstract suspend fun segments(taskId: String): List<DownloadSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTask(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSegments(segments: List<DownloadSegmentEntity>)

    @Query("UPDATE download_segments SET currentByte = :current WHERE taskId = :taskId AND segmentIndex = :index")
    abstract suspend fun updateSegment(taskId: String, index: Int, current: Long)

    @Query("UPDATE download_tasks SET downloadedBytes = :downloaded, state = :state, errorCode = :error, updatedAt = :updatedAt WHERE id = :taskId")
    abstract suspend fun updateProgress(taskId: String, downloaded: Long, state: String, error: String?, updatedAt: Long)

    @Query("UPDATE download_tasks SET temporaryUrl = :url, expiresAt = :expiresAt, etag = :etag, lastModified = :lastModified, totalBytes = :totalBytes, connectionCount = :connections, state = :state, errorCode = NULL, updatedAt = :updatedAt WHERE id = :taskId")
    abstract suspend fun prepare(
        taskId: String,
        url: String,
        expiresAt: Long?,
        etag: String?,
        lastModified: String?,
        totalBytes: Long,
        connections: Int,
        state: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_segments WHERE taskId = :taskId")
    abstract suspend fun deleteSegments(taskId: String)

    @Transaction
    open suspend fun replaceSegments(taskId: String, segments: List<DownloadSegmentEntity>) {
        deleteSegments(taskId)
        upsertSegments(segments)
    }
}

@Dao
interface BootProfileDao {
    @Query("SELECT * FROM boot_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BootProfileEntity>>

    @Query("SELECT * FROM boot_profiles WHERE id = :id")
    suspend fun find(id: String): BootProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: BootProfileEntity)
}

@Dao
interface RuntimeEventDao {
    @Query("SELECT * FROM runtime_events ORDER BY id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 1000): Flow<List<RuntimeEventEntity>>

    @Query("SELECT * FROM (SELECT * FROM runtime_events ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    suspend fun latestChronological(limit: Int = 5000): List<RuntimeEventEntity>

    @Insert
    suspend fun insert(event: RuntimeEventEntity)

    @Query("DELETE FROM runtime_events WHERE timestamp < :oldest OR id NOT IN (SELECT id FROM runtime_events ORDER BY id DESC LIMIT :maxRows)")
    suspend fun prune(oldest: Long, maxRows: Int)

    @Query("DELETE FROM runtime_events")
    suspend fun clear()
}
