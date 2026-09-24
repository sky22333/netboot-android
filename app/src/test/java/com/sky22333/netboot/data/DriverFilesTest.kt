package com.sky22333.netboot.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DriverFilesTest {
    @TempDir lateinit var temporary: File

    private fun directory(): File = Files.createTempDirectory(temporary.toPath(), "package-").toFile()

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun zipPreservesCompletePackageAndHashDoesNotDependOnOrder() {
        val entries = arrayOf("Storage/driver.inf" to "[Version]", "Storage/driver.sys" to "binary", "Storage/driver.cat" to "catalog")
        fun extract(order: Array<Pair<String, String>>): String {
            val root = directory()
            val progress = mutableListOf<Long>()
            val files = DriverFiles(root, {}, progress::add)
            files.unzip(zip(*order).inputStream())
            entries.forEach { (path, content) -> assertEquals(content, File(root, path).readText()) }
            assertEquals(entries.sumOf { it.second.length }.toLong(), progress.last())
            return files.finish()
        }
        assertEquals(extract(entries), extract(entries.reversedArray()))
    }

    @Test fun changedContentsOrRelativePathsInvalidateCache() {
        fun hash(path: String, content: String): String = DriverFiles(directory(), {}, {}).apply {
            add(path, content.byteInputStream())
        }.finish()
        assertNotEquals(hash("driver.inf", "a"), hash("driver.inf", "b"))
        assertNotEquals(hash("driver.inf", "a"), hash("nested/driver.inf", "a"))
    }

    @Test fun zipRejectsTraversalAndFatNameCollisions() {
        for (path in listOf("../outside.inf", "/outside.inf", "C:/outside.inf", "folder/../outside.inf", "driver.inf.", "driver.inf ")) {
            val files = DriverFiles(directory(), {}, {})
            assertEquals("drivers_invalid", assertThrows(IOException::class.java) { files.unzip(zip(path to "x").inputStream()) }.message)
        }
        for (pair in listOf("a.inf" to "A.INF", "driver.inf" to "driver.inf/child.sys", "folder/a.inf" to "folder")) {
            val files = DriverFiles(directory(), {}, {})
            assertThrows(IOException::class.java) { files.unzip(zip(pair.first to "x", pair.second to "y").inputStream()) }
        }
    }

    @Test fun executableInstallerAloneIsNotADriverPackage() {
        val files = DriverFiles(directory(), {}, {})
        files.unzip(zip("setup.exe" to "exe").inputStream())
        assertEquals("drivers_no_inf", assertThrows(IOException::class.java, files::finish).message)
    }

    @Test fun cancellationInterruptsStreamingCopy() {
        var checks = 0
        val files = DriverFiles(directory(), { if (++checks == 3) throw CancellationException() }, {})
        assertThrows(CancellationException::class.java) { files.add("driver.inf", ByteArray(1024 * 1024).inputStream()) }
        assertTrue(files.bytes < 1024 * 1024)
    }
}
