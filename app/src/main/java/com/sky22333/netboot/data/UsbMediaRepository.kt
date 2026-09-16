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
class UsbMediaRepository @Inject constructor(private val isoRepository: IsoRepository) {
    suspend fun prepare(asset: IsoAssetEntity, progress: (UsbPreparationStage, Long, Long) -> Unit): PreparedUsbMedia = withContext(Dispatchers.IO) {
        val source = File(asset.filePath)
        val root = isoRepository.managedDirectory().canonicalFile
        if (Files.isSymbolicLink(source.toPath()) || source.canonicalFile.parentFile != root || !source.isFile) throw IOException("media_not_regular")
        if (source.length() != asset.fileSize) throw IOException("media_source_changed")
        if (UsbMediaLayout.isDisk(source)) return@withContext PreparedUsbMedia(source, false)
        UsbMediaLayout.requireIso(source)
        if (source.length() <= UsbMediaLayout.OpticalLimit) {
            UsbMediaLayout.requireOptical(source)
            return@withContext PreparedUsbMedia(source, true)
        }
        val coroutine = currentCoroutineContext()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            if (!NativeMedia.isWindows(input.fd)) throw IOException(if (asset.source == "microsoft") "media_windows_layout" else "media_large_nonhybrid")
            if (!asset.sha256.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("media_source_changed")
            val directory = File(root, "media").apply { mkdirs() }
            if (directory.canonicalFile.parentFile != root || Files.isSymbolicLink(directory.toPath())) throw IOException("media_not_regular")
            val target = File(directory, "${asset.sha256.lowercase()}-v1.img")
            if (target.isFile && !Files.isSymbolicLink(target.toPath()) && UsbMediaLayout.isDisk(target)) return@withContext PreparedUsbMedia(target, false)
            // Native WIM normalization and split output coexist with the sparse destination.
            if (isoRepository.availableBytes() < source.length() * 5 + 512L * 1024 * 1024) throw IOException("media_storage_required")
            val work = File(directory, "${asset.sha256.lowercase()}-work")
            require(!Files.isSymbolicLink(work.toPath()) && work.canonicalFile.parentFile == directory.canonicalFile)
            if (work.exists() && !work.deleteRecursively()) throw IOException("media_cleanup_failed")
            if (!work.mkdir()) throw IOException("media_write_failed")
            val partial = File(work, "installer.img")
            var lastUpdate = 0L
            var lastStage = -1
            try {
                ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                    NativeMedia.buildWindows(input.fd, output.fd, work.canonicalPath, object : MediaProgress {
                        override fun onProgress(stage: Int, done: Long, total: Long): Boolean {
                            val now = System.nanoTime()
                            if (stage != lastStage || done == total || now - lastUpdate >= 250_000_000L) {
                                progress(UsbPreparationStage.entries[stage], done, total)
                                lastUpdate = now
                                lastStage = stage
                            }
                            return coroutine.isActive
                        }
                    })
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
interface MediaProgress { fun onProgress(stage: Int, done: Long, total: Long): Boolean }

@Keep
internal object NativeMedia {
    init { System.loadLibrary("netboot_media") }
    external fun isWindows(input: Int): Boolean
    external fun buildWindows(input: Int, output: Int, directory: String, progress: MediaProgress)
}
