package com.sky22333.netboot.data

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMediaTest {
    private external fun buildWithSmallLimit(input: Int, output: Int, directory: String, progress: MediaProgress)

    private fun fixture(test: (File, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "media-test-${System.nanoTime()}").apply { mkdir() }
        try {
            val iso = File(root, "fixture.iso")
            instrumentation.context.assets.open("windows-layout.udf").use { input -> iso.outputStream().use(input::copyTo) }
            test(root, iso)
        } finally { root.deleteRecursively() }
    }

    @Test fun udfToReadOnlyInstallationDiskPreservesFiles() = fixture { root, iso ->
        val image = File(root, "disk.img")
        ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            assertTrue(NativeMedia.isWindows(input.fd))
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                NativeMedia.buildWindows(input.fd, output.fd, root.path, object : MediaProgress {
                    override fun onProgress(stage: Int, done: Long, total: Long) = true
                })
            }
        }
        assertTrue(UsbMediaLayout.isDisk(image))
        FatReader(image).use {
            assertEquals("test fixture: setup.exe", it.read("SETUP.EXE").decodeToString())
            assertEquals("test fixture: efi/boot/bootx64.efi", it.read("EFI/BOOT/BOOTX64.EFI").decodeToString())
            assertEquals("MSWIM", it.read("SOURCES/INSTALL.WIM").take(5).toByteArray().decodeToString())
        }
    }

    @Test fun oversizedWimBecomesMultipleSetupReadableSwmFiles() = fixture { root, iso ->
        val image = File(root, "disk.img")
        val stages = mutableListOf<Int>()
        ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            assertTrue(NativeMedia.isWindows(input.fd)) // Load the packaged library.
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                buildWithSmallLimit(input.fd, output.fd, root.path, object : MediaProgress {
                    override fun onProgress(stage: Int, done: Long, total: Long): Boolean {
                        if (stages.lastOrNull() != stage) stages += stage
                        assertTrue("stage $stage: $done/$total", total == 0L || done in 0..total)
                        return true
                    }
                })
            }
        }
        assertEquals(listOf(0, 1, 3, 4, 5), stages)
        FatReader(image).use {
            assertEquals("MSWIM", it.read("SOURCES/INSTALL.SWM").take(5).toByteArray().decodeToString())
            assertEquals("MSWIM", it.read("SOURCES/INSTALL2.SWM").take(5).toByteArray().decodeToString())
            assertThrows(IOException::class.java) { it.read("SOURCES/INSTALL.WIM") }
        }
    }

    @Test fun cancellationStopsPreparationAndDoesNotChangeSource() = fixture { root, iso ->
        val hash = IsoRepository.sha256(iso)
        ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(File(root, "partial.img"), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                assertThrows(IOException::class.java) {
                    NativeMedia.buildWindows(input.fd, output.fd, root.path, object : MediaProgress {
                        override fun onProgress(stage: Int, done: Long, total: Long) = false
                    })
                }
            }
        }
        assertEquals(hash, IsoRepository.sha256(iso))
    }

    @Test fun cdimageNonzeroPartitionNumberIsRecognizedAndConverted() = fixture { root, iso ->
        // Reproduce official Windows ISO layout without distributing Microsoft files.
        RandomAccessFile(iso, "rw").use { file ->
            val anchor = ByteArray(2048)
            file.seek(256L * 2048); file.readFully(anchor)
            val a = ByteBuffer.wrap(anchor).order(ByteOrder.LITTLE_ENDIAN)
            for (extent in listOf(16, 24)) {
                val start = a.getInt(extent + 4)
                val blocks = a.getInt(extent) / 2048
                repeat(blocks) { index ->
                    val offset = (start + index).toLong() * 2048
                    val bytes = ByteArray(2048)
                    file.seek(offset); file.readFully(bytes)
                    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    when (b.getShort(0).toInt()) {
                        5 -> b.putShort(22, 2989)
                        6 -> { assertEquals(1, bytes[440].toInt()); b.putShort(444, 2989) }
                        else -> return@repeat
                    }
                    val length = b.getShort(10).toInt() and 65535
                    var crc = 0
                    for (n in 16 until 16 + length) {
                        crc = crc xor ((bytes[n].toInt() and 255) shl 8)
                        repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 65535 }
                    }
                    b.putShort(8, crc.toShort())
                    bytes[4] = (0..15).filter { it != 4 }.sumOf { bytes[it].toInt() and 255 }.toByte()
                    file.seek(offset); file.write(bytes)
                }
            }
        }
        ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            assertTrue(NativeMedia.isWindows(input.fd))
            val image = File(root, "cdimage.img")
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                NativeMedia.buildWindows(input.fd, output.fd, root.path, object : MediaProgress {
                    override fun onProgress(stage: Int, done: Long, total: Long) = true
                })
            }
            FatReader(image).use { assertEquals("test fixture: setup.exe", it.read("SETUP.EXE").decodeToString()) }
        }
    }

    @Test fun cancellationDuringSplitStopsBeforePublishingParts() = fixture { root, iso ->
        val hash = IsoRepository.sha256(iso)
        ParcelFileDescriptor.open(iso, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            assertTrue(NativeMedia.isWindows(input.fd))
            ParcelFileDescriptor.open(File(root, "partial.img"), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                val error = assertThrows(IOException::class.java) {
                    buildWithSmallLimit(input.fd, output.fd, root.path, object : MediaProgress {
                        override fun onProgress(stage: Int, done: Long, total: Long) = stage != 3 || total == 0L
                    })
                }
                assertEquals("media_cancelled", error.message)
            }
        }
        assertEquals(hash, IsoRepository.sha256(iso))
    }

    /** Independent FAT32 reader for fixture assertions; never uses the production writer. */
    private class FatReader(file: File) : AutoCloseable {
        private val input = RandomAccessFile(file, "r")
        private val partition = block(0).getInt(454).toLong() and 0xffffffffL
        private val bpb = block(partition * 512)
        private val clusterBytes = (bpb.get(13).toInt() and 255) * 512
        private val reserved = bpb.getShort(14).toInt() and 65535
        private val fatStart = (partition + reserved) * 512
        private val dataStart = (partition + reserved + (bpb.get(16).toInt() and 255) * bpb.getInt(36)) * 512
        private fun block(offset: Long): ByteBuffer {
            input.seek(offset)
            return ByteBuffer.wrap(ByteArray(512).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        }
        private fun chain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var cluster = start
            repeat(4096) {
                if (cluster >= 0x0ffffff8) return out.toByteArray()
                require(cluster >= 2)
                input.seek(dataStart + (cluster - 2L) * clusterBytes)
                out.write(ByteArray(clusterBytes).also(input::readFully))
                cluster = block(fatStart + cluster * 4L).getInt(0) and 0x0fffffff
            }
            error("cyclic cluster chain")
        }
        fun read(path: String): ByteArray {
            var directory = chain(bpb.getInt(44))
            val components = path.split('/')
            for ((index, name) in components.withIndex()) {
                var entry: ByteBuffer? = null
                for (offset in directory.indices step 32) {
                    val e = directory.copyOfRange(offset, offset + 32)
                    if (e[0] == 0.toByte()) break
                    if (e[11] == 15.toByte() || e[0] == 0xe5.toByte()) continue
                    val base = e.copyOfRange(0, 8).decodeToString().trim()
                    val ext = e.copyOfRange(8, 11).decodeToString().trim()
                    if ((base + if (ext.isEmpty()) "" else ".$ext") == name) { entry = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN); break }
                }
                val e = entry ?: throw IOException("missing $path")
                val cluster = ((e.getShort(20).toInt() and 65535) shl 16) or (e.getShort(26).toInt() and 65535)
                val content = chain(cluster)
                if (index == components.lastIndex) return content.copyOf(e.getInt(28))
                directory = content
            }
            error("empty path")
        }
        override fun close() = input.close()
    }
}
