package com.sky22333.netboot.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DhcpPoolAllocatorTest {
    @Test fun `default pool excludes every possible server address`() {
        for (last in 1..254) {
            val server = "192.168.1.$last"
            val pool = DhcpPoolAllocator.allocate("", "", server)
            val range = DhcpPoolAllocator.toUInt(pool.start)..DhcpPoolAllocator.toUInt(pool.end)
            assertFalse(DhcpPoolAllocator.toUInt(server) in range)
            assertFalse(DhcpPoolAllocator.toUInt("192.168.1.0") in range)
            assertFalse(DhcpPoolAllocator.toUInt("192.168.1.255") in range)
            assertTrue(pool.size in 1..254)
        }
    }
    @Test fun `explicit range is never silently changed`() {
        assertEquals(DhcpPool("192.168.1.150", "192.168.1.180"), DhcpPoolAllocator.allocate("192.168.1.150", "192.168.1.180", "192.168.1.100"))
        for ((start, end) in listOf(".100" to ".150", ".50" to ".100", ".50" to ".150", ".0" to ".20", ".240" to ".255")) {
            assertThrows(IllegalArgumentException::class.java) { DhcpPoolAllocator.allocate("192.168.1$start", "192.168.1$end", "192.168.1.100") }
        }
    }
    @Test fun `invalid and cross subnet inputs are rejected`() {
        assertThrows(IllegalStateException::class.java) { DhcpPoolAllocator.allocate("bad", "", "192.168.1.100") }
        assertThrows(IllegalArgumentException::class.java) { DhcpPoolAllocator.allocate("192.168.9.10", "192.168.9.20", "192.168.1.100") }
    }
    @Test fun `network prefix determines the mask and pool`() {
        val adapter = NetworkAdapter("test", "10.0.0.1", 29)
        assertEquals("255.255.255.248", adapter.subnetMask)
        assertEquals(DhcpPool("10.0.0.2", "10.0.0.6"), DhcpPoolAllocator.allocate("", "", adapter.address, adapter.subnetMask))
    }
}
