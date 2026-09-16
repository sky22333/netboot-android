package com.sky22333.netboot.root

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The guard is the last line of defence for the "never touch a partition" rule: every privileged
 * configfs write goes through it, including the paths read back from the on-device state file.
 */
class ConfigfsGuardTest {
    @TempDir
    lateinit var temporary: Path

    private fun fixture(): Pair<File, List<String>> {
        val root = temporary.resolve("usb_gadget").toFile().apply { mkdirs() }
        return root to listOf(root.absolutePath)
    }

    @Test
    fun `accepts nodes inside the gadget root`() {
        val (root, roots) = fixture()
        val gadget = File(root, "netboot_android_app")

        assertDoesNotThrow { ConfigfsGuard.requireGadget(gadget, roots) }
        assertDoesNotThrow { ConfigfsGuard.requireFunction(File(gadget, "functions/mass_storage.netboot"), roots) }
        assertDoesNotThrow { ConfigfsGuard.requireRoot(root, roots) }
    }

    @Test
    fun `rejects a block device node`() {
        val (_, roots) = fixture()

        listOf("/dev/block/sda", "/dev/block/by-name/boot", "/dev/block/mmcblk0p1").forEach { path ->
            assertThrows(IllegalStateException::class.java) { ConfigfsGuard.requireGadget(File(path), roots) }
        }
    }

    @Test
    fun `rejects a path that escapes the gadget root through dot segments`() {
        val (root, roots) = fixture()
        val escape = File(root, "../../dev/block/sda")

        assertThrows(IllegalStateException::class.java) { ConfigfsGuard.requireNode(escape, "gadget", roots) }
    }

    @Test
    fun `rejects a sibling directory that merely shares a prefix`() {
        val (_, roots) = fixture()
        val sibling = File(temporary.toFile(), "usb_gadget_evil/gadget")

        assertThrows(IllegalStateException::class.java) { ConfigfsGuard.requireGadget(sibling, roots) }
    }

    @Test
    fun `refuses everything when configfs is not available`() {
        // An empty allow-list models a device whose kernel provides no gadget configfs at all.
        assertThrows(IllegalStateException::class.java) {
            ConfigfsGuard.requireGadget(File(temporary.toFile(), "anything"), emptyList())
        }
    }
}
