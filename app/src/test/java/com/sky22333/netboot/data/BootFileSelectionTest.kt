package com.sky22333.netboot.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootFileSelectionTest {
    private val files = listOf(PxeFile("ipxe-x86_64.efi", 1170944, builtIn = true))

    @Test
    fun `a blank boot file defers to the client architecture`() {
        assertTrue(isBootFileUsable("", files))
        assertTrue(isBootFileUsable("", emptyList()))
    }

    @Test
    fun `a named boot file must be present`() {
        assertTrue(isBootFileUsable("ipxe-x86_64.efi", files))
        assertFalse(isBootFileUsable("ipxe.efi", files))
    }
}
