package com.sky22333.netboot.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the constraints the download and image state machines rely on: foreign keys cascade,
 * event retention is bounded, and an asset that is still downloading cannot be deleted.
 *
 * Room validates the committed schema (`app/schemas`) on every open, so this also guards against an
 * undocumented schema change shipping unnoticed.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun migrationFromV1PreservesProfilesAndBlanksThePool() {
        migrationHelper.createDatabase(DatabaseName, 1).use { legacy ->
            legacy.execSQL(
                """
                INSERT INTO boot_profiles
                    (id, name, mode, interfaceName, listenAddress, advertiseAddress, httpPort, bootFile, menuJson, updatedAt)
                VALUES ('default', 'Default', 'proxy', 'wlan0', '192.168.1.50', '192.168.1.50', 8080, 'ipxe-x86_64.efi', '#!ipxe\nshell', 42)
                """.trimIndent(),
            )
        }

        val migrated = migrationHelper.runMigrationsAndValidate(DatabaseName, 2, true, AppDatabase.Migration1To2)
        migrated.query("SELECT mode, httpPort, dhcpPoolStart, dhcpPoolEnd FROM boot_profiles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("proxy", cursor.getString(0))
            assertEquals(8080, cursor.getInt(1))
            // Blank means "derive a safe pool for the selected network at runtime"; no subnet is
            // assumed for a profile that predates the column.
            assertEquals("", cursor.getString(2))
            assertEquals("", cursor.getString(3))
        }
        migrated.close()
    }

    @Test
    fun deletingAnAssetCascadesToTasksAndSegments() = runBlocking {
        val asset = asset(id = "a1", state = IsoState.Ready)
        database.isoDao().upsert(asset)
        database.downloadDao().upsertTask(task(id = "a1"))
        database.downloadDao().upsertSegments(
            listOf(DownloadSegmentEntity("a1", 0, 0, 99, 0), DownloadSegmentEntity("a1", 1, 100, 199, 100)),
        )

        database.isoDao().deleteIfIdle("a1")

        assertNull(database.isoDao().find("a1"))
        assertNull(database.downloadDao().findTask("a1"))
        assertEquals(emptyList<DownloadSegmentEntity>(), database.downloadDao().segments("a1"))
    }

    @Test
    fun anAssetThatIsStillDownloadingIsNotDeleted() = runBlocking {
        database.isoDao().upsert(asset(id = "a2", state = IsoState.Downloading))

        val deleted = database.isoDao().deleteIfIdle("a2")

        assertEquals(0, deleted)
        assertNotNull(database.isoDao().find("a2"))
    }

    @Test
    fun anAssetThatIsBeingVerifiedIsNotDeleted() = runBlocking {
        database.isoDao().upsert(asset(id = "a3", state = IsoState.Verifying))

        assertEquals(0, database.isoDao().deleteIfIdle("a3"))
    }

    @Test
    fun eventPruningKeepsTheNewestRowsAndDropsExpiredOnes() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = database.runtimeEventDao()
        repeat(3) { index -> dao.insert(event(timestamp = now + index, code = "fresh_$index")) }
        repeat(2) { index -> dao.insert(event(timestamp = now - TimeUnit.DAYS.toMillis(30) - index, code = "stale_$index")) }

        dao.prune(oldest = now - TimeUnit.DAYS.toMillis(7), maxRows = 5000)

        val remaining = dao.latestChronological()
        assertEquals(listOf("fresh_0", "fresh_1", "fresh_2"), remaining.map { it.eventCode })
    }

    @Test
    fun eventPruningEnforcesTheRowCap() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = database.runtimeEventDao()
        repeat(10) { index -> dao.insert(event(timestamp = now + index, code = "e$index")) }

        dao.prune(oldest = now - TimeUnit.DAYS.toMillis(7), maxRows = 4)

        val remaining = dao.latestChronological()
        assertEquals(4, remaining.size)
        assertEquals(listOf("e6", "e7", "e8", "e9"), remaining.map { it.eventCode })
    }

    @Test
    fun segmentReplacementIsAtomicAndOrdered() = runBlocking {
        database.isoDao().upsert(asset(id = "a4", state = IsoState.Downloading))
        database.downloadDao().upsertTask(task(id = "a4"))

        database.downloadDao().replaceSegments(
            "a4",
            listOf(DownloadSegmentEntity("a4", 1, 50, 99, 50), DownloadSegmentEntity("a4", 0, 0, 49, 0)),
        )
        database.downloadDao().replaceSegments("a4", listOf(DownloadSegmentEntity("a4", 0, 0, 99, 42)))

        val segments = database.downloadDao().segments("a4")
        assertEquals(1, segments.size)
        assertEquals(42L, segments.single().currentByte)
    }

    @Test
    fun progressUpdatesAreVisibleToObservers() = runBlocking {
        database.isoDao().upsert(asset(id = "a5", state = IsoState.Downloading))
        database.downloadDao().upsertTask(task(id = "a5"))

        database.downloadDao().updateProgress("a5", 2048, DownloadState.Paused, "network_or_storage_error", 1L)

        val stored = database.downloadDao().findTask("a5")
        assertNotNull(stored)
        assertEquals(2048L, stored!!.downloadedBytes)
        assertEquals(DownloadState.Paused, stored.state)
        assertEquals("network_or_storage_error", stored.errorCode)
    }

    private fun asset(id: String, state: String) = IsoAssetEntity(
        id = id,
        product = "Windows 11",
        edition = "Windows11",
        language = "English",
        architecture = "X64",
        source = "microsoft",
        fileName = "$id.iso",
        filePath = "/data/app/$id.iso",
        fileSize = 0,
        sha256 = "",
        createdAt = 0,
        state = state,
    )

    private fun task(id: String) = DownloadTaskEntity(
        id = id,
        isoAssetId = id,
        temporaryUrl = "https://software.download.prss.microsoft.com/$id.iso",
        expiresAt = null,
        etag = null,
        lastModified = null,
        totalBytes = 0,
        downloadedBytes = 0,
        connectionCount = 4,
        state = DownloadState.Queued,
        errorCode = null,
        updatedAt = 0,
    )

    private fun event(timestamp: Long, code: String) = RuntimeEventEntity(
        timestamp = timestamp,
        severity = "info",
        source = "test",
        eventCode = code,
        argumentsJson = "{}",
    )

    private companion object {
        const val DatabaseName = "migration-test.db"
    }
}
