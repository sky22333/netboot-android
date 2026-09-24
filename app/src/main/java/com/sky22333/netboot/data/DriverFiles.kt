package com.sky22333.netboot.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Preserves INF-relative paths; rejects names that collide on the FAT destination. */
internal class DriverFiles(private val root: File, private val checkCancelled: () -> Unit, private val progress: (Long) -> Unit) {
    private val hashes = sortedMapOf<String, String>()
    private val paths = mutableMapOf<String, Boolean>()
    var bytes: Long = 0
        private set

    fun add(path: String, input: InputStream) {
        checkCancelled()
        val parts = path.replace('\\', '/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') || it.any { c -> c < ' ' || c in ":*?\"<>|" } }) invalid()
        for (i in parts.indices) {
            val key = parts.take(i + 1).joinToString("/").lowercase(Locale.ROOT)
            val directory = i < parts.lastIndex
            val previous = paths.putIfAbsent(key, directory)
            if (previous != null && (!directory || !previous)) invalid()
        }
        val relative = parts.joinToString("/")
        val target = File(root, relative)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) invalid()
        val parent = requireNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("media_write_failed")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > 0xffffffffL) throw IOException("media_file_too_large")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                bytes += count
                progress(bytes)
            }
        }
        hashes[relative] = digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun unzip(input: InputStream) = ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            checkCancelled()
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) add(entry.name, zip)
            zip.closeEntry()
        }
    }

    fun finish(): String {
        if (hashes.keys.none { it.endsWith(".inf", ignoreCase = true) }) throw IOException("drivers_no_inf")
        val digest = MessageDigest.getInstance("SHA-256")
        hashes.forEach { (path, hash) -> digest.update("$path\u0000$hash\n".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(): Nothing = throw IOException("drivers_invalid")
}
