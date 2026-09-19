package com.sky22333.netboot.data

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PxeFileRepositoryTest {
    private fun fixture(block: suspend (PxeFileRepository, File) -> Unit) = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(application.cacheDir, "pxe-test-${System.nanoTime()}").apply { mkdir() }
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        val repository = PxeFileRepository(context)
        try {
            withTimeout(10_000) { repository.files.first { files -> files.count { it.builtIn } == 3 } }
            block(repository, root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedReplacementPreservesPublishedFileAndRemovesTemporary() = fixture { repository, root ->
        val source = File(root, "custom.efi").apply { writeText("original") }
        repository.import(listOf(Uri.fromFile(source)))
        source.writeBytes(byteArrayOf())
        try {
            repository.import(listOf(Uri.fromFile(source)))
            fail("empty input accepted")
        } catch (expected: IOException) {
            assertEquals("empty_source", expected.message)
        }
        assertEquals("original", File(root, "pxe/custom.efi").readText())
        assertFalse(File(root, "pxe/custom.efi.importing").exists())
    }

    @Test fun batchFailureStillPublishesEarlierSuccessToUi() = fixture { repository, root ->
        val first = File(root, "first.efi").apply { writeText("first") }
        val empty = File(root, "empty.efi").apply { createNewFile() }
        try {
            repository.import(listOf(Uri.fromFile(first), Uri.fromFile(empty)))
            fail("empty input accepted")
        } catch (_: IOException) { }
        assertTrue(repository.files.value.any { it.name == "first.efi" })
        assertFalse(repository.files.value.any { it.name == "empty.efi" })
        assertFalse(File(root, "pxe/empty.efi.importing").exists())
    }

    @Test fun concurrentImportsOfSameNamePublishOneCompleteFile() = fixture { repository, root ->
        val contents = listOf(ByteArray(1024 * 1024) { 1 }, ByteArray(1024 * 1024) { 2 })
        val sources = contents.mapIndexed { index, bytes ->
            File(root, "source$index").mkdir()
            File(root, "source$index/custom.efi").apply { writeBytes(bytes) }
        }
        coroutineScope { sources.map { source -> async { repository.import(listOf(Uri.fromFile(source))) } }.awaitAll() }
        val actual = File(root, "pxe/custom.efi").readBytes()
        assertTrue(contents.any { it.contentEquals(actual) })
        assertFalse(File(root, "pxe/custom.efi.importing").exists())
        assertTrue(repository.defaultScript().startsWith("#!ipxe"))
    }
}
