package com.sky22333.netboot.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky22333.netboot.download.DownloadRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferRecoveryTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var images: IsoRepository
    private lateinit var drivers: DriverRepository
    private lateinit var downloads: DownloadRepository
    private lateinit var source: File

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "transfer-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        drivers = DriverRepository(context, database)
        images = IsoRepository(context, database, drivers)
        val offline = OkHttpClient.Builder().addInterceptor { throw AssertionError("Recovery must not request an expired URL") }.build()
        downloads = DownloadRepository(database, images, MicrosoftIsoCatalog(offline), offline)
        source = File(directory, "source.iso")
        InstrumentationRegistry.getInstrumentation().context.assets.open("windows-layout.udf").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun cleanup() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun driverReplacementCancellationRecoveryAndRemovalPreserveSelection() = runBlocking {
        val id = images.import(Uri.fromFile(source))
        val zip = File(directory, "drivers.zip")
        fun packageWith(content: String) {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("Storage/driver.inf"))
                it.write(content.toByteArray())
                it.closeEntry()
            }
        }
        packageWith("original")
        drivers.replace(requireNotNull(database.isoDao().find(id)), Uri.fromFile(zip)) {}
        val selected = requireNotNull(database.isoDao().find(id))
        assertEquals("original", drivers.files(selected).single().readText())
        packageWith("replacement")
        val job = launch {
            val coroutine = currentCoroutineContext()
            drivers.replace(selected, Uri.fromFile(zip)) { coroutine.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(selected.driverHash, database.isoDao().find(id)?.driverHash)
        assertEquals("original", drivers.files(selected).single().readText())
        zip.writeText("not a ZIP")
        try {
            drivers.replace(selected, Uri.fromFile(zip)) {}
            fail("Invalid replacement accepted")
        } catch (_: IOException) {
            assertEquals(selected.driverHash, database.isoDao().find(id)?.driverHash)
            assertEquals("original", drivers.files(selected).single().readText())
        }
        val abandoned = File(directory, "drivers/$id/importing").apply { mkdirs() }
        File(abandoned, "partial").writeText("interrupted")
        drivers.recover()
        assertFalse(abandoned.exists())
        assertEquals("original", drivers.files(selected).single().readText())
        drivers.replace(selected, null) {}
        assertEquals("", database.isoDao().find(id)?.driverHash)
        assertFalse(File(directory, "drivers/$id").exists())
    }

    @Test
    fun importsWithTheSameDisplayNameRemainIndependent() = runBlocking {
        val bothCreated = CountDownLatch(2)
        val ids = (1..2).map {
            async {
                images.import(Uri.fromFile(source)) {
                    bothCreated.countDown()
                    check(bothCreated.await(5, TimeUnit.SECONDS))
                }
            }
        }.awaitAll()
        val (first, second) = ids
        val a = requireNotNull(database.isoDao().find(first))
        val b = requireNotNull(database.isoDao().find(second))
        assertEquals(a.fileName, b.fileName)
        assertNotEquals(a.filePath, b.filePath)
        assertEquals(IsoState.Ready, a.state)
        assertEquals(a.sha256, b.sha256)
        assertTrue(images.delete(first))
        assertTrue(File(b.filePath).isFile)
    }

    @Test
    fun invalidIsoDoesNotLeaveAnImportInProgress() = runBlocking {
        source.writeText("not an ISO")
        try {
            images.import(Uri.fromFile(source))
            fail("Invalid media accepted")
        } catch (_: IOException) {
            val asset = images.observeAll().first().single()
            assertEquals(IsoState.Failed, asset.state)
            assertFalse(File(asset.filePath + ".importing").exists())
            assertFalse(File(asset.filePath).exists())
        }
    }

    @Test
    fun cancelledImportCleansItsTemporaryFileAndState() = runBlocking {
        val job = launch {
            val coroutine = currentCoroutineContext()
            images.import(Uri.fromFile(source)) { coroutine.cancel() }
        }
        job.join()
        val asset = images.observeAll().first().single()
        assertEquals(IsoState.Failed, asset.state)
        assertFalse(File(asset.filePath + ".importing").exists())
        assertTrue(images.importProgress.value.isEmpty())
    }

    @Test
    fun recoveryReconcilesInterruptedCopyAndPublishedImport() = runBlocking {
        val incomplete = asset("copy", "import", IsoState.Importing)
        val published = asset("published", "import", IsoState.Importing)
        database.isoDao().upsert(incomplete)
        database.isoDao().upsert(published)
        File(incomplete.filePath + ".importing").writeText("partial")
        source.copyTo(File(published.filePath))
        images.recoverInterruptedImports()
        assertEquals(IsoState.Failed, database.isoDao().find(incomplete.id)?.state)
        assertFalse(File(incomplete.filePath + ".importing").exists())
        val ready = requireNotNull(database.isoDao().find(published.id))
        assertEquals(IsoState.Ready, ready.state)
        assertEquals(IsoRepository.sha256(source), ready.sha256)
    }

    @Test
    fun downloadRecoveryFinishesBothSidesOfTheRenameWithoutNetwork() = runBlocking {
        for (published in listOf(false, true)) {
            val id = "download-$published"
            val asset = asset(id, "microsoft", IsoState.Verifying)
            database.isoDao().upsert(asset)
            database.downloadDao().upsertTask(task(id, DownloadState.Verifying))
            database.downloadDao().upsertSegments(listOf(DownloadSegmentEntity(id, 0, 0, source.length() - 1, source.length())))
            source.copyTo(File(asset.filePath + if (published) "" else ".part"))
            assertTrue(downloads.recoverInterrupted().contains(id))
            downloads.run(id)
            assertEquals(DownloadState.Completed, database.downloadDao().findTask(id)?.state)
            assertEquals(IsoState.Ready, database.isoDao().find(id)?.state)
            assertEquals(IsoRepository.sha256(source), database.isoDao().find(id)?.sha256)
            assertFalse(File(asset.filePath + ".part").exists())
        }
    }

    @Test
    fun cancelledVerificationCanResumeFromTheCompletePartialOffline() = runBlocking {
        val asset = asset("verify-cancel", "microsoft", IsoState.Verifying)
        database.isoDao().upsert(asset)
        database.downloadDao().upsertTask(task(asset.id, DownloadState.Verifying))
        database.downloadDao().upsertSegments(listOf(DownloadSegmentEntity(asset.id, 0, 0, source.length() - 1, source.length())))
        val partial = File(asset.filePath + ".part")
        source.copyTo(partial)
        val job = launch {
            val coroutine = currentCoroutineContext()
            downloads.run(asset.id) { if (it.verifying) coroutine.cancel() }
        }
        job.join()
        assertEquals(DownloadState.Paused, database.downloadDao().findTask(asset.id)?.state)
        assertTrue(partial.isFile)
        assertFalse(File(asset.filePath).exists())
        downloads.run(asset.id)
        assertEquals(DownloadState.Completed, database.downloadDao().findTask(asset.id)?.state)
        assertEquals(IsoState.Ready, database.isoDao().find(asset.id)?.state)
    }

    @Test
    fun pausedDownloadsStayPausedAndDeletionRemovesPartialFiles() = runBlocking {
        val asset = asset("paused", "microsoft", IsoState.Downloading)
        database.isoDao().upsert(asset)
        database.downloadDao().upsertTask(task(asset.id, DownloadState.Running))
        val partial = File(asset.filePath + ".part").apply { writeText("partial") }
        downloads.markPaused(asset.id)
        assertFalse(downloads.recoverInterrupted().contains(asset.id))
        assertEquals(DownloadState.Paused, database.downloadDao().findTask(asset.id)?.state)
        downloads.cancel(asset.id, deletePartial = true)
        assertFalse(partial.exists())
        assertTrue(images.delete(asset.id))
        assertNull(database.downloadDao().findTask(asset.id))
    }

    private fun asset(id: String, source: String, state: String): IsoAssetEntity {
        images.managedDirectory().mkdirs()
        return IsoAssetEntity(id, "Windows 11", "Windows11", "English", "X64", source,
            "$id.iso", File(images.managedDirectory(), "$id.iso").absolutePath, this.source.length(), "", 0, state)
    }

    private fun task(id: String, state: String) = DownloadTaskEntity(
        id, id, "", 0, null, null, source.length(), source.length(), 1, state, null, 0,
    )
}
