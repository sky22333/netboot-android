package com.sky22333.netboot.root

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootBrokerProtocolTest {
    @Test
    fun `protocol exposes no arbitrary command operation`() {
        assertEquals(7, BrokerOperation.allowed.size)
        assertFalse(BrokerOperation.allowed.any { it.contains("command", true) || it.contains("shell", true) || it.contains("file", true) })
    }

    @Test
    fun `shell quoting cannot terminate argument`() {
        assertEquals("'safe'", RootBrokerClient.shellQuote("safe"))
        assertEquals("'a'\\''b'", RootBrokerClient.shellQuote("a'b"))
    }

    @Test
    fun `privileged source contains no forbidden mutation primitive`() {
        val source = privilegedSource()
        // Every one of these would cross the immutable safety boundary in AGENTS.md.
        val forbidden = listOf(
            "/dev/block",
            "mkfs",
            "setenforce",
            "mount -o rw",
            "resetprop",
            "persist.",
            "dd if=",
            "fastboot",
            "flash_image",
            "init_boot",
            "vendor_boot",
            "/data/adb",
            "/system/bin/su",
        )
        forbidden.forEach { token ->
            assertFalse(source.contains(token, ignoreCase = true), "forbidden token in privileged source: $token")
        }
    }

    @Test
    fun `all configfs mutations use the checked IO boundary`() {
        val source = privilegedSource()
        assertTrue(source.contains("ConfigfsGuard.requireNode(node, \"USB mutation\", guardRoots)"))
        assertFalse(source.contains("deleteTree("))
        assertFalse(source.contains("copyLunTemplate("))
    }

    @Test
    fun `injecting the gadget roots also narrows the guard`() {
        // If the guard used a hardcoded root while the controller used an injected one, tests would
        // exercise a different boundary than production.
        assertTrue(privilegedSource().contains("candidateRoots.map { it.absolutePath }"))
    }

    private fun privilegedSource(): String =
        locateSourceRoot().walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

    private fun locateSourceRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        repeat(4) {
            val candidate = File(current, "src/main/java/com/sky22333/netboot/root")
            if (candidate.isDirectory) return candidate
            current = requireNotNull(current.parentFile) { "root source directory not found" }
        }
        error("root source directory not found")
    }
}
