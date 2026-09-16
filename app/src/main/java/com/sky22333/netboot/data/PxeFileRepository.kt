package com.sky22333.netboot.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class PxeFile(val name: String, val size: Long, val builtIn: Boolean)

/** A blank name defers the choice to the client architecture, which the core resolves. */
fun isBootFileUsable(bootFile: String, files: List<PxeFile>): Boolean =
    bootFile.isBlank() || files.any { it.name == bootFile }

@Singleton
class PxeFileRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory = File(context.filesDir, "pxe")
    private val mutableFiles = MutableStateFlow(scan())
    val files = mutableFiles.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            seedBuiltIns()
            mutableFiles.value = scan()
        }
    }

    fun defaultScript(): String = context.assets.open("pxe/autoexec.ipxe").bufferedReader().use { it.readText() }

    suspend fun import(uris: List<Uri>) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        uris.forEach { uri ->
            val name = displayName(uri).substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { throw IOException("invalid_file_name") }
            require(name !in Reserved) { "reserved_file_name" }
            val destination = File(directory, name)
            val temporary = File(directory, "$name.importing")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IOException("source_unavailable")
            if (temporary.length() == 0L) {
                temporary.delete()
                throw IOException("empty_source")
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
        mutableFiles.value = scan()
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        require(name !in BuiltIns) { "built_in_file" }
        val file = File(directory, name).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "invalid_file_name" }
        if (file.exists() && !file.delete()) throw IOException("delete_failed")
        mutableFiles.value = scan()
    }

    private fun scan(): List<PxeFile> = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".importing") }
        ?.map { PxeFile(it.name, it.length(), it.name in BuiltIns) }?.sortedWith(compareByDescending<PxeFile> { it.builtIn }.thenBy { it.name }).orEmpty()

    private fun seedBuiltIns() {
        directory.mkdirs()
        BuiltIns.forEach { (name, expectedHash) ->
            val destination = File(directory, name)
            if (destination.isFile && IsoRepository.sha256(destination) == expectedHash) return@forEach
            val temporary = File(directory, "$name.builtin")
            context.assets.open("pxe/$name").use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "boot-file"
        }
        return uri.lastPathSegment ?: "boot-file"
    }

    private companion object {
        val BuiltIns = mapOf(
            "ipxe-arm64.efi" to "a25ec1e57caf215108b92eeb22ccc081a2b3c238019af86e1f47bf2e8d043347",
            "ipxe-x86_64.efi" to "9767ac1ab11b612c5e97db7a6c5a57267a6378a3eb174752ea5ff4f5974d19d8",
            "undionly.kpxe" to "f0c1c2f07a15f6a8e987f61ec8835bdc85b5557754348be8fbbdb08af4dfcd30",
        )

        // Served from the configured script, so an imported file of the same name would be
        // shadowed; deleting stays allowed so a file imported earlier can still be removed.
        val ScriptNames = setOf("autoexec.ipxe", "boot.ipxe")
        val Reserved = BuiltIns.keys + ScriptNames
    }
}
