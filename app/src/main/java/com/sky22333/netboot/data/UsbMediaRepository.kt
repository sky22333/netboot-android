package com.sky22333.netboot.data

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class PreparedUsbMedia(val file: File, val cdrom: Boolean)
enum class UsbPreparationStage { CopyFiles, ExtractWim, NormalizeWim, SplitWim, CopyParts, Verify, Finalize }

/** Preparation runs unprivileged. The broker only receives a completed, read-only backing file. */
@Singleton
class UsbMediaRepository @Inject constructor(private val isoRepository: IsoRepository, private val drivers: DriverRepository) {
    suspend fun prepare(asset: IsoAssetEntity, progress: (UsbPreparationStage, Long, Long) -> Unit): PreparedUsbMedia = withContext(Dispatchers.IO) {
        val source = File(asset.filePath)
        val root = isoRepository.managedDirectory().canonicalFile
        if (Files.isSymbolicLink(source.toPath()) || source.canonicalFile.parentFile != root || !source.isFile) throw IOException("media_not_regular")
        if (source.length() != asset.fileSize) throw IOException("media_source_changed")
        val hasDrivers = asset.driverHash.isNotEmpty()
        if (!hasDrivers && UsbMediaLayout.isDisk(source)) return@withContext PreparedUsbMedia(source, false)
        UsbMediaLayout.requireIso(source)
        if (!hasDrivers && source.length() <= UsbMediaLayout.OpticalLimit) {
            UsbMediaLayout.requireOptical(source)
            return@withContext PreparedUsbMedia(source, true)
        }
        val fileName = UsbMediaCache.fileName(asset.sha256, asset.driverHash)
        val directory = File(root, "media").apply { mkdirs() }
        if (directory.canonicalFile.parentFile != root || Files.isSymbolicLink(directory.toPath())) throw IOException("media_not_regular")
        val target = File(directory, fileName)
        // Reuse completed images by source hash, without reopening UDF or loading JNI.
        if (target.isFile && !Files.isSymbolicLink(target.toPath()) && UsbMediaLayout.isDisk(target)) return@withContext PreparedUsbMedia(target, false)
        val driverFiles = drivers.files(asset)
        val coroutine = currentCoroutineContext()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            if (!NativeMedia.isWindows(input.fd)) throw IOException(if (driverFiles.isNotEmpty()) "drivers_unsupported_image" else if (asset.source == "microsoft") "media_windows_layout" else "media_large_nonhybrid")
            // Native WIM normalization and split output coexist with the sparse destination.
            if (isoRepository.availableBytes() < source.length() * 5 + driverFiles.sumOf { it.length() } + 512L * 1024 * 1024) throw IOException("media_storage_required")
            val work = File(directory, "${target.nameWithoutExtension}-work")
            require(!Files.isSymbolicLink(work.toPath()) && work.canonicalFile.parentFile == directory.canonicalFile)
            if (work.exists() && !work.deleteRecursively()) throw IOException("media_cleanup_failed")
            if (!work.mkdir()) throw IOException("media_write_failed")
            val partial = File(work, "installer.img")
            try {
                ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                    NativeMedia.buildWindows(input.fd, output.fd, work.canonicalPath, object : MediaProgress {
                        override fun openDriver(index: Int): Int {
                            coroutine.ensureActive()
                            return ParcelFileDescriptor.open(driverFiles[index], ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                        }
                        override fun onProgress(stage: Int, done: Long, total: Long): Boolean {
                            progress(UsbPreparationStage.entries[stage], done, total)
                            return coroutine.isActive
                        }
                    }, driverFiles.map { drivers.relativePath(asset, it) }.toTypedArray(), driverFiles.map { it.length() }.toLongArray())
                }
                coroutine.ensureActive()
                if (!UsbMediaLayout.isDisk(partial)) throw IOException("media_verify_failed")
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                PreparedUsbMedia(target, false)
            } catch (error: IOException) {
                coroutine.ensureActive()
                throw error
            } finally {
                progress(UsbPreparationStage.Finalize, 0, 0)
                if (!work.deleteRecursively()) throw IOException("media_cleanup_failed")
            }
        }
    }
}

@Keep
interface MediaProgress {
    fun onProgress(stage: Int, done: Long, total: Long): Boolean
    fun openDriver(index: Int): Int = -1
}

@Keep
internal object NativeMedia {
    init { System.loadLibrary("netboot_media") }
    external fun volumeLabel(input: Int): String?
    external fun isWindows(input: Int): Boolean
    external fun buildWindows(input: Int, output: Int, directory: String, progress: MediaProgress, driverPaths: Array<String> = emptyArray(), driverSizes: LongArray = longArrayOf())
}
