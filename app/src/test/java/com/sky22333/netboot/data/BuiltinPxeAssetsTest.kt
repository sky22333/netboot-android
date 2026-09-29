package com.sky22333.netboot.data

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuiltinPxeAssetsTest {
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
}
