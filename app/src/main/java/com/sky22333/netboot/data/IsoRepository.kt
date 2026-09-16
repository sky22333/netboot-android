package com.sky22333.netboot.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class IsoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: AppDatabase,
) {
    private val dao = database.isoDao()
    private val isoDirectory = File(context.filesDir, "iso")

    fun observeAll(): Flow<List<IsoAssetEntity>> = dao.observeAll()

    suspend fun find(id: String): IsoAssetEntity? = dao.find(id)

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        isoDirectory.mkdirs()
        val metadata = queryMetadata(uri)
        if (metadata.size == 0L) throw IllegalArgumentException("empty_source")
        if (metadata.size > 0) require(availableBytes() > metadata.size) { "insufficient_storage" }
        val id = UUID.randomUUID().toString()
        val fileName = sanitizeFileName(metadata.name, id)
        val finalFile = File(isoDirectory, fileName)
        val temporaryFile = File(isoDirectory, "$fileName.importing")
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
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporaryFile.outputStream().buffered().use { output -> input.copyTo(output, BufferSize) }
            } ?: throw IOException("source_unavailable")
            if (temporaryFile.length() == 0L) throw IOException("empty_source")
            if (metadata.size > 0 && temporaryFile.length() != metadata.size) {
                throw IOException("size_mismatch")
            }
            val sha256 = sha256(temporaryFile)
            if (!temporaryFile.renameTo(finalFile)) {
                throw IOException("rename_failed")
            }
            dao.complete(id, IsoState.Ready, finalFile.length(), sha256)
            id
        } catch (error: Exception) {
            temporaryFile.delete()
            dao.upsert(asset.copy(state = IsoState.Failed))
            throw error
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val asset = dao.find(id) ?: return@withContext true
        if (asset.state == IsoState.Downloading || asset.state == IsoState.Verifying) {
            return@withContext false
        }
        val file = File(asset.filePath)
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

    private fun sanitizeFileName(name: String, id: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(100)
            .ifBlank { "$id.iso" }
        val candidate = File(isoDirectory, clean)
        return if (!candidate.exists()) clean else "${clean.removeSuffix(".iso")}-${id.take(8)}.iso"
    }

    private data class SourceMetadata(val name: String, val size: Long)

    companion object {
        private const val BufferSize = 256 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered(BufferSize).use { input ->
                val buffer = ByteArray(BufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
