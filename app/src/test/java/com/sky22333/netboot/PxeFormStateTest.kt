package com.sky22333.netboot

import androidx.compose.runtime.saveable.SaverScope
import com.sky22333.netboot.data.BootMode
import com.sky22333.netboot.data.BootProfileEntity
import com.sky22333.netboot.runtime.DhcpPoolAllocator
import com.sky22333.netboot.runtime.NetworkAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PxeFormStateTest {
    private val wifi = NetworkAdapter("wlan0", "192.168.1.100", 24)
    private val ethernet = NetworkAdapter("eth0", "10.20.30.1", 29)

    @Test fun `switching interfaces replaces both nonempty pool fields`() {
        val first = PxeFormState(mode = BootMode.Dhcp).selectAdapter(wifi)
        val switched = first.selectAdapter(ethernet)
        assertEquals("10.20.30.2", switched.poolStart)
        assertEquals("10.20.30.6", switched.poolEnd)
        assertTrue(switched.poolValid)
        assertEquals(ethernet, switched.adapter)
    }

    @Test fun `manual edits survive recomposition mode changes and list reordering`() {
        val draft = PxeFormState().selectAdapter(wifi).copy(poolStart = "192.168.1.150", poolEnd = "192.168.1.180")
        assertEquals(draft, draft.reconcile(null, listOf(ethernet, wifi)))
        val dhcp = draft.copy(mode = BootMode.Dhcp)
        assertEquals(dhcp, dhcp.reconcile(null, listOf(wifi, ethernet)))
        assertEquals(draft, dhcp.copy(mode = BootMode.Proxy))
        assertEquals(draft, draft.selectAdapter(wifi))
    }

    @Test fun `same interface with new address or prefix regenerates the pool`() {
        val original = PxeFormState().selectAdapter(wifi)
        for (changed in listOf(wifi.copy(address = "172.16.10.1"), wifi.copy(prefixLength = 25))) {
            val updated = original.reconcile(null, listOf(changed))
            val expected = DhcpPoolAllocator.allocate("", "", changed.address, changed.subnetMask)
            assertEquals(expected.start, updated.poolStart)
            assertEquals(expected.end, updated.poolEnd)
            assertTrue(updated.poolValid)
        }
    }

    @Test fun `removed interfaces cannot leave a stale selection or pool`() {
        val original = PxeFormState().selectAdapter(wifi)
        assertEquals(ethernet, original.reconcile(null, listOf(ethernet)).adapter)
        val disconnected = original.reconcile(null, emptyList())
        assertNull(disconnected.adapter)
        assertEquals("", disconnected.poolStart)
        assertEquals("", disconnected.poolEnd)
        assertFalse(disconnected.poolValid)
        assertTrue(disconnected.reconcile(null, listOf(wifi)).poolValid)
    }

    @Test fun `saved custom pool is restored only on its own network`() {
        val stored = profile()
        val restored = PxeFormState().reconcile(stored, listOf(ethernet, wifi))
        assertEquals(wifi, restored.adapter)
        assertEquals("192.168.1.150", restored.poolStart)
        assertEquals("192.168.1.180", restored.poolEnd)
        val migrated = PxeFormState().reconcile(stored, listOf(ethernet))
        assertEquals("10.20.30.2", migrated.poolStart)
        assertEquals("10.20.30.6", migrated.poolEnd)
        assertEquals(stored.menuJson, migrated.script)
    }

    @Test fun `initially disconnected profile still restores when its network appears`() {
        val stored = profile()
        val disconnected = PxeFormState().reconcile(stored, emptyList())
        val reconnected = disconnected.reconcile(stored, listOf(wifi))
        assertEquals(stored.dhcpPoolStart, reconnected.poolStart)
        assertEquals(stored.dhcpPoolEnd, reconnected.poolEnd)
    }

    @Test fun `refreshing unchanged profile does not overwrite an edited script or pool`() {
        val stored = profile()
        val edited = PxeFormState().reconcile(stored, listOf(wifi)).copy(script = "#!ipxe\necho draft", poolStart = "192.168.1.160")
        assertEquals(edited, edited.reconcile(stored, listOf(ethernet, wifi)))
    }

    @Test fun `invalid partial or reserved ranges remain editable until the network changes`() {
        val form = PxeFormState().selectAdapter(wifi)
        for ((start, end) in listOf("" to "", "192.168.1.150" to "", "192.168.2.1" to "192.168.2.10", "192.168.1.90" to "192.168.1.110")) {
            val invalid = form.copy(poolStart = start, poolEnd = end)
            assertFalse(invalid.poolValid)
            assertEquals(invalid, invalid.reconcile(null, listOf(wifi)))
            assertTrue(invalid.selectAdapter(ethernet).poolValid)
        }
    }

    @Test fun `unsupported subnet clears the former pool`() {
        val form = PxeFormState().selectAdapter(wifi).selectAdapter(NetworkAdapter("test", "10.0.0.1", 31))
        assertEquals("", form.poolStart)
        assertEquals("", form.poolEnd)
        assertFalse(form.poolValid)
        assertEquals(BootMode.Proxy, form.mode)
    }

    @Test fun `saved state preserves the whole configuration and interface identity`() {
        val form = PxeFormState().selectAdapter(wifi).copy(mode = BootMode.Dhcp, port = "9090", bootFile = "boot.ipxe", script = "#!ipxe\nshell", profileRevision = 42)
        val saved = with(PxeFormState.Saver) { SaverScope { true }.save(form) }
        val restored = PxeFormState.Saver.restore(requireNotNull(saved))
        assertEquals(form, restored)
    }

    private fun profile() = BootProfileEntity(
        id = "default", name = "Default", mode = "dhcp", interfaceName = wifi.name,
        listenAddress = wifi.address, advertiseAddress = wifi.address, httpPort = 8080,
        bootFile = "", menuJson = "#!ipxe\nshell", dhcpPoolStart = "192.168.1.150",
        dhcpPoolEnd = "192.168.1.180", updatedAt = 42,
    )
}
