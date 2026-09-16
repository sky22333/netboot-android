package com.sky22333.netboot.root

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UsbGadgetControllerTest {
    @TempDir lateinit var temporary: Path
    private lateinit var gadget: File
    private lateinit var config: File
    private lateinit var state: File
    private lateinit var managed: File
    private lateinit var udcs: File

    /** Models kernel commands, synchronous default groups, and binding constraints. */
    private inner class KernelIo : GadgetIo() {
        var failBind = false
        var failLink = false
        var unsupported = false
        var ignoreUnbind = false
        override fun write(node: File, value: String) {
            if (node.name == "UDC") {
                if (value.isEmpty() && ignoreUnbind) return
                if (value.isNotEmpty() && failBind) throw IOException("bind refused")
                if (value.isNotEmpty() && node.readText().trim().isNotEmpty()) throw IOException("busy")
            }
            super.write(node, value)
        }
        override fun createFunction(directory: File) {
            if (unsupported) throw IOException("unknown function")
            super.createFunction(directory)
            File(directory, "lun.0").mkdir()
            listOf("ro", "cdrom", "removable", "file").forEach { File(directory, "lun.0/$it").writeText("") }
        }
        override fun link(link: File, function: File) {
            if (failLink || File(gadget, "UDC").readText().trim().isNotEmpty()) throw IOException("bound or rejected")
            assertTrue(function.isAbsolute)
            super.link(link, function)
        }
        override fun unlink(link: File) {
            check(File(gadget, "UDC").readText().isBlank())
            super.unlink(link)
        }
        override fun removeFunction(directory: File) {
            check(!Files.isSymbolicLink(File(config, directory.name).toPath()))
            check(File(directory, "lun.0/file").readText().isBlank())
            // Only the test double removes synthetic attributes; production invokes one rmdir.
            directory.deleteRecursively()
        }
    }

    private fun fixture(io: KernelIo): UsbGadgetController {
        val root = temporary.resolve("usb_gadget").toFile().apply { mkdirs() }
        gadget = File(root, "g1").apply { mkdir() }
        config = File(gadget, "configs/b.1").apply { mkdirs() }
        File(gadget, "functions/ffs.adb").mkdirs()
        Files.createSymbolicLink(File(config, "adb").toPath(), File(gadget, "functions/ffs.adb").toPath())
        File(gadget, "UDC").writeText("controller")
        udcs = temporary.resolve("udc").toFile().apply { mkdir() }
        File(udcs, "controller").mkdir()
        File(udcs, "controller/state").writeText("addressed")
        managed = temporary.resolve("iso").toFile().apply { mkdir() }
        state = temporary.resolve("state.json").toFile()
        return UsbGadgetController(managed, state, listOf(root), udcs, io)
    }
    private fun iso() = File(managed, "test.iso").apply {
        java.io.RandomAccessFile(this, "rw").use {
            it.setLength(300L * 2048)
            it.seek(16L * 2048)
            it.write(byteArrayOf(1) + "CD001".toByteArray() + byteArrayOf(1))
        }
    }

    @Test fun `empty command is a nonzero length write`() {
        val attribute = temporary.resolve("attribute").toFile()
        GadgetIo().write(attribute, "")
        assertArrayEquals(byteArrayOf(10), attribute.readBytes())
    }
    @Test fun `attach needs no template and restore preserves platform functions`() {
        val controller = fixture(KernelIo())
        assertTrue(controller.probe().supported)
        assertFalse(state.exists())
        controller.attach(iso().path)
        assertTrue(controller.isAttached())
        assertFalse(controller.isHostConnected())
        File(udcs, "controller/state").writeText("configured")
        assertTrue(controller.isHostConnected())
        assertTrue(controller.restore().complete)
        assertTrue(File(config, "adb").exists())
        assertEquals("controller", File(gadget, "UDC").readText().trim())
        assertFalse(state.exists())
        assertTrue(controller.restore().complete)
    }
    @Test fun `failed link restores original state`() {
        val io = KernelIo(); val controller = fixture(io); io.failLink = true
        assertThrows(UsbException::class.java) { controller.attach(iso().path) }
        assertEquals("controller", File(gadget, "UDC").readText().trim())
        assertFalse(state.exists())
        assertFalse(File(gadget, "functions/mass_storage.netboot").exists())
    }
    @Test fun `failed bind retains journal for retry`() {
        val io = KernelIo(); val controller = fixture(io); io.failBind = true
        val error = assertThrows(UsbException::class.java) { controller.attach(iso().path) }
        assertTrue(error.code.startsWith("usb_restore_failed"))
        assertTrue(state.exists())
        io.failBind = false
        assertTrue(controller.restore().complete)
        assertFalse(state.exists())
    }
    @Test fun `ignored unbind cannot succeed`() {
        val io = KernelIo(); val controller = fixture(io); io.ignoreUnbind = true
        assertThrows(UsbException::class.java) { controller.attach(iso().path) }
        assertFalse(File(config, "mass_storage.netboot").exists())
        assertEquals("controller", File(gadget, "UDC").readText().trim())
    }
    @Test fun `unsupported function preserves original controller`() {
        val io = KernelIo(); val controller = fixture(io); io.unsupported = true
        assertThrows(UsbException::class.java) { controller.attach(iso().path) }
        assertFalse(state.exists())
        assertEquals("controller", File(gadget, "UDC").readText().trim())
    }
    @Test fun `corrupt journal is not successful recovery`() {
        val controller = fixture(KernelIo()); state.writeText("broken")
        assertFalse(controller.restore().complete)
        assertTrue(state.exists())
    }
    @Test fun `journal cannot redirect recovery outside the allowed root`() {
        val controller = fixture(KernelIo()); controller.attach(iso().path)
        val outside = temporary.resolve("outside").toFile().apply { mkdir() }
        val sentinel = File(outside, "UDC").apply { writeText("untouched") }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(state.readText()).let {
            (it as kotlinx.serialization.json.JsonObject).toMutableMap()
        }
        json["gadget"] = kotlinx.serialization.json.JsonPrimitive(outside.path)
        state.writeText(kotlinx.serialization.json.JsonObject(json).toString())
        assertFalse(controller.restore().complete)
        assertEquals("untouched", sentinel.readText())
    }
    @Test fun `foreign link is rejected before restore writes`() {
        val controller = fixture(KernelIo()); controller.attach(iso().path)
        val link = File(config, "mass_storage.netboot")
        Files.delete(link.toPath())
        Files.createSymbolicLink(link.toPath(), File(gadget, "functions/ffs.adb").toPath())
        assertFalse(controller.restore().complete)
        assertTrue(File(config, "adb").exists())
        assertTrue(state.exists())
    }
    @Test fun `backing file rejects external files and symlinks`() {
        fixture(KernelIo())
        assertEquals(iso().canonicalFile, UsbGadgetController.validateBackingFile(managed, iso()))
        val outside = temporary.resolve("outside.iso").toFile().apply { writeText("x") }
        assertThrows(UsbException::class.java) { UsbGadgetController.validateBackingFile(managed, outside) }
        val alias = File(managed, "alias.iso")
        Files.createSymbolicLink(alias.toPath(), iso().toPath())
        assertThrows(UsbException::class.java) { UsbGadgetController.validateBackingFile(managed, alias) }
    }

    @Test fun `oversized optical image is rejected before USB is changed`() {
        val controller = fixture(KernelIo())
        val image = iso()
        java.io.RandomAccessFile(image, "rw").use { it.setLength(com.sky22333.netboot.data.UsbMediaLayout.OpticalLimit + 2048) }
        assertThrows(IOException::class.java) { controller.attach(image.path) }
        assertFalse(state.exists())
        assertEquals("controller", File(gadget, "UDC").readText().trim())
        assertFalse(File(gadget, "functions/mass_storage.netboot").exists())
    }

    @Test fun `hybrid disk sets cdrom zero but remains read only and restores`() {
        val controller = fixture(KernelIo())
        val image = iso()
        val mbr = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        mbr.put(450, 0x0c); mbr.putInt(454, 1); mbr.putInt(458, (image.length()/512-1).toInt())
        mbr.put(510, 0x55); mbr.put(511, 0xaa.toByte())
        java.io.RandomAccessFile(image, "rw").use { it.write(mbr.array()) }
        controller.attach(image.path, false)
        assertEquals("0", File(gadget, "functions/mass_storage.netboot/lun.0/cdrom").readText().trim())
        assertEquals("1", File(gadget, "functions/mass_storage.netboot/lun.0/ro").readText().trim())
        assertTrue(controller.isAttached())
        assertTrue(controller.restore().complete)
    }
}
