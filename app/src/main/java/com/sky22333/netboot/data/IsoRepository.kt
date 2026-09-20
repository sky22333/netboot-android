package com.sky22333.netboot.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.storage.StorageManager
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ImportProgress(val bytes: Long, val total: Long, val verifying: Boolean = false)

@Singleton
class IsoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: AppDatabase,
) {
    private val dao = database.isoDao()
    private val isoDirectory = File(context.filesDir, "iso")
    private val recoveryMutex = Mutex()
    private var recovered = false
    private val mutableImportProgress = MutableStateFlow<Map<String, ImportProgress>>(emptyMap())
    val importProgress = mutableImportProgress.asStateFlow()

    fun observeAll(): Flow<List<IsoAssetEntity>> = dao.observeAll()

    /** A SAF source is not retained: an interrupted copy is explicitly retryable by selecting it again. */
    suspend fun recoverInterruptedImports() = recoveryMutex.withLock {
        if (recovered) return@withLock
        withContext(Dispatchers.IO) {
            for (asset in dao.interruptedImports()) {
                val finalFile = File(asset.filePath)
                val temporary = File(asset.filePath + ".importing")
                try {
                    if (finalFile.isFile) {
                        if (asset.fileSize > 0 && finalFile.length() != asset.fileSize) throw IOException("size_mismatch")
                        UsbMediaLayout.requireIso(finalFile)
                        val coroutine = currentCoroutineContext()
                        val hash = sha256(finalFile, { coroutine.ensureActive() })
                        dao.complete(asset.id, IsoState.Ready, finalFile.length(), hash)
                    } else {
                        Files.deleteIfExists(temporary.toPath())
                        dao.setState(asset.id, IsoState.Failed)
                    }
                } catch (_: IOException) {
                    dao.setState(asset.id, IsoState.Failed)
                }
            }
        }
        recovered = true
    }

    suspend fun import(uri: Uri, onCreated: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        recoverInterruptedImports()
        isoDirectory.mkdirs()
        val metadata = queryMetadata(uri)
        if (metadata.size == 0L) throw IllegalArgumentException("empty_source")
        if (metadata.size > 0) require(availableBytes() > metadata.size) { "insufficient_storage" }
        val id = UUID.randomUUID().toString()
        val fileName = metadata.name.substringAfterLast('/').substringAfterLast('\\')
        val finalFile = File(isoDirectory, "$id.iso")
        val temporaryFile = File(isoDirectory, "$id.iso.importing")
        val asset = IsoAssetEntity(
            id = id,
            product = "Local ISO",
            edition = "",
            language = "",
            architecture = "",
            source = "import",
            fileName = fileName,
            filePath = finalFile.absolutePath,
            fileSize = metadata.size.coerceAtLeast(0),
            sha256 = "",
            createdAt = System.currentTimeMillis(),
            state = IsoState.Importing,
        )
        dao.upsert(asset)
        onCreated(id)
        val coroutine = currentCoroutineContext()
        var lastProgress = 0L
        fun report(bytes: Long, total: Long, verifying: Boolean = false) {
            val now = System.nanoTime()
            if (bytes == 0L || bytes == total || now - lastProgress >= 250_000_000L) {
                mutableImportProgress.update { it + (id to ImportProgress(bytes, total, verifying)) }
                lastProgress = now
            }
        }
        suspend fun discardIncomplete() = withContext(NonCancellable) {
            if (!finalFile.exists()) {
                try { Files.deleteIfExists(temporaryFile.toPath()) } finally { dao.setState(id, IsoState.Failed) }
            }
        }
        try {
            report(0, metadata.size)
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporaryFile.outputStream().use { output ->
                    val buffer = ByteArray(BufferSize)
                    var copied = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        report(copied, metadata.size)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("source_unavailable")
            if (temporaryFile.length() == 0L) throw IOException("empty_source")
            if (metadata.size > 0 && temporaryFile.length() != metadata.size) {
                throw IOException("size_mismatch")
            }
            UsbMediaLayout.requireIso(temporaryFile)
            report(0, temporaryFile.length(), true)
            val sha256 = sha256(temporaryFile, { coroutine.ensureActive() }) { bytes, total -> report(bytes, total, true) }
            coroutine.ensureActive()
            // Finish the DB commit after publication; startup reconciles process death.
            withContext(NonCancellable) {
                Files.move(temporaryFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                dao.complete(id, IsoState.Ready, finalFile.length(), sha256)
            }
            id
        } catch (cancelled: CancellationException) {
            discardIncomplete()
            throw cancelled
        } catch (error: IOException) {
            discardIncomplete()
            throw error
        } catch (error: SecurityException) {
            discardIncomplete()
            throw error
        } finally {
            mutableImportProgress.update { it - id }
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val asset = dao.find(id) ?: return@withContext true
        if (asset.state in setOf(IsoState.Downloading, IsoState.Verifying, IsoState.Importing)) {
            return@withContext false
        }
        val file = File(asset.filePath)
        for (suffix in listOf(".part", ".importing")) {
            Files.deleteIfExists(File(asset.filePath + suffix).toPath())
        }
        if (asset.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            val media = File(isoDirectory, "media")
            if (java.nio.file.Files.isSymbolicLink(media.toPath())) throw IOException("media_not_regular")
            val cache = File(media, "${asset.sha256.lowercase()}-v1.img")
            if (cache.exists() && !cache.delete()) return@withContext false
            val work = File(media, "${asset.sha256.lowercase()}-work")
            if (java.nio.file.Files.isSymbolicLink(work.toPath())) throw IOException("media_not_regular")
            if (work.exists() && !work.deleteRecursively()) return@withContext false
        }
        if (file.exists() && !file.delete()) {
            return@withContext false
        }
        dao.deleteIfIdle(id) > 0
    }

    suspend fun volumeLabel(id: String): String? = withContext(Dispatchers.IO) {
        val asset = dao.find(id)?.takeIf { it.state == IsoState.Ready } ?: return@withContext null
        try {
            val file = File(asset.filePath)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != isoDirectory.canonicalFile || !file.isFile) return@withContext null
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                NativeMedia.volumeLabel(it.fd)?.trim()?.takeIf { label -> label.isNotEmpty() }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun managedDirectory(): File = isoDirectory

    fun availableBytes(): Long {
        isoDirectory.mkdirs()
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(isoDirectory))
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var name = "imported.iso"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (!name.endsWith(".iso", ignoreCase = true)) {
            throw IllegalArgumentException("not_iso")
        }
        if (size < 0) {
            size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        }
        return SourceMetadata(name, size)
    }

    private data class SourceMetadata(val name: String, val size: Long)

    companion object {
        private const val BufferSize = 256 * 1024

        fun sha256(file: File, checkCancelled: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var processed = 0L
            val total = file.length()
            FileInputStream(file).buffered(BufferSize).use { input ->
                val buffer = ByteArray(BufferSize)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    processed += read
                    progress(processed, total)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
