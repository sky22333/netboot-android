package com.sky22333.netboot.data

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MicrosoftHostsTest {
    @Test
    fun acceptsMicrosoftHttpsHosts() {
        assertDoesNotThrow {
            MicrosoftHosts.requireOfficial("https://software.download.prss.microsoft.com/file.iso")
        }
    }

    @Test
    fun rejectsNonMicrosoftAndNonHttpsHosts() {
        assertThrows(CatalogException::class.java) {
            MicrosoftHosts.requireOfficial("https://microsoft.com.attacker.example/file.iso")
        }
        assertThrows(CatalogException::class.java) {
            MicrosoftHosts.requireOfficial("http://www.microsoft.com/file.iso")
        }
    }
}

