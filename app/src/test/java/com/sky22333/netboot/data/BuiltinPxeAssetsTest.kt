package com.sky22333.netboot.data

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuiltinPxeAssetsTest {
    @Test
    fun `bundled boot files match pinned release assets`() {
        val directory = locateAssets()
        val expected = mapOf(
            "ipxe-arm64.efi" to "a25ec1e57caf215108b92eeb22ccc081a2b3c238019af86e1f47bf2e8d043347",
            "ipxe-x86_64.efi" to "9767ac1ab11b612c5e97db7a6c5a57267a6378a3eb174752ea5ff4f5974d19d8",
            "undionly.kpxe" to "f0c1c2f07a15f6a8e987f61ec8835bdc85b5557754348be8fbbdb08af4dfcd30",
        )
        expected.forEach { (name, hash) -> assertEquals(hash, sha256(File(directory, name)), name) }
    }

    @Test
    fun `default script is a complete ipxe menu`() {
        val script = File(locateAssets(), "autoexec.ipxe").readText()
        assertTrue(script.startsWith("#!ipxe\n"))
        assertTrue(script.contains(":main_menu"))
        assertTrue(script.contains(":failed"))
    }

    @Test
    fun `every menu entry and goto resolves to a label`() {
        val script = File(locateAssets(), "autoexec.ipxe").readText()
        val labels = Regex("(?m)^:([A-Za-z0-9_]+)").findAll(script).map { it.groupValues[1] }.toSet()
        val entries = Regex("(?m)^item\\s+(?!--gap)(\\S+)").findAll(script).map { it.groupValues[1] }
        val jumps = Regex("\\bgoto\\s+(\\S+)").findAll(script).map { it.groupValues[1] }.filterNot { it.startsWith("\$") }
        val targets = (entries + jumps).toList()
        assertTrue(targets.isNotEmpty())
        targets.forEach { target -> assertTrue(target in labels) { "menu target $target has no label" } }
    }

    private fun locateAssets(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        repeat(4) {
            File(current, "src/main/assets/pxe").takeIf(File::isDirectory)?.let { return it }
            current = requireNotNull(current.parentFile) { "assets directory not found" }
        }
        error("assets directory not found")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
