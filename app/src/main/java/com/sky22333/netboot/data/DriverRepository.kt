package com.sky22333.netboot.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Mutations and USB preparation are serialized by RuntimeRepository. */
@Singleton
class DriverRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    private val root = File(context.filesDir, "drivers")

    suspend fun recover() = withContext(Dispatchers.IO) {
        val selected = database.isoDao().all().associate { it.id to it.driverHash }
        root.listFiles().orEmpty().forEach { directory ->
            val hash = selected[directory.name]
            if (hash.isNullOrEmpty()) remove(directory)
            else directory.listFiles().orEmpty().filter { it.name != hash }.forEach(::remove)
        }
    }

    suspend fun replace(asset: IsoAssetEntity, uri: Uri?, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val directory = File(root, asset.id)
        if (uri == null) {
            withContext(NonCancellable) {
                database.isoDao().setDrivers(asset.id, "", "")
                remove(directory)
            }
            return@withContext
        }
        try {
            ParcelFileDescriptor.open(File(asset.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use {
                if (!NativeMedia.isWindows(it.fd)) throw IOException("drivers_unsupported_image")
            }
        } catch (error: IOException) {
            if (error.message?.substringBefore(':') in setOf("media_udf_unreadable", "media_windows_layout")) {
                throw IOException("drivers_unsupported_image", error)
            }
            throw error
        }
        val work = File(directory, "importing")
        remove(work)
        if (!work.mkdirs()) throw IOException("media_write_failed")
        val coroutine = currentCoroutineContext()
        val files = DriverFiles(work, { coroutine.ensureActive() }, progress)
        try {
            val resolver = context.contentResolver
            val isTree = DocumentsContract.isTreeUri(uri)
            val source = if (isTree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
            val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Drivers"
            if (isTree) {
                suspend fun copyDirectory(parent: Uri, prefix: String, depth: Int) {
                    if (depth > 32) throw IOException("drivers_invalid")
                    coroutine.ensureActive()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(parent))
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            coroutine.ensureActive()
                            val child = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                            val path = prefix + cursor.getString(1)
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) copyDirectory(child, "$path/", depth + 1)
                            else resolver.openInputStream(child)?.use { files.add(path, it) } ?: throw IOException("source_unavailable")
                        }
                    } ?: throw IOException("source_unavailable")
                }
                copyDirectory(source, "", 0)
            } else {
                resolver.openInputStream(uri)?.use(files::unzip) ?: throw IOException("source_unavailable")
            }
            val hash = files.finish()
            coroutine.ensureActive()
            val destination = File(directory, hash)
            withContext(NonCancellable) {
                if (!destination.exists()) Files.move(work.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                database.isoDao().setDrivers(asset.id, hash, name)
                directory.listFiles()?.filter { it != destination }?.forEach(::remove)
            }
        } finally {
            remove(work)
        }
    }

    fun files(asset: IsoAssetEntity): List<File> {
        if (asset.driverHash.isEmpty()) return emptyList()
        if (!asset.driverHash.matches(Regex("[a-f0-9]{64}"))) throw IOException("drivers_invalid")
        val directory = File(root, "${asset.id}/${asset.driverHash}")
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw IOException("drivers_missing")
        val files = directory.walkTopDown().onEnter {
            if (Files.isSymbolicLink(it.toPath())) throw IOException("drivers_invalid")
            true
        }.filter { !it.isDirectory }.toList()
        if (files.isEmpty()) throw IOException("drivers_missing")
        files.forEach { if (!it.isFile || Files.isSymbolicLink(it.toPath())) throw IOException("drivers_invalid") }
        return files.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
    }

    fun relativePath(asset: IsoAssetEntity, file: File): String =
        file.relativeTo(File(root, "${asset.id}/${asset.driverHash}")).invariantSeparatorsPath

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { remove(File(root, id)) }

    private fun remove(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("media_cleanup_failed")
    }
}
