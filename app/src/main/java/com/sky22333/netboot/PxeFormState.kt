package com.sky22333.netboot

import androidx.compose.runtime.saveable.listSaver
import com.sky22333.netboot.data.BootMode
import com.sky22333.netboot.data.BootProfileEntity
import com.sky22333.netboot.runtime.DhcpPool
import com.sky22333.netboot.runtime.DhcpPoolAllocator
import com.sky22333.netboot.runtime.NetworkAdapter

/** The editable PXE configuration; interface and pool move together in one state transition. */
data class PxeFormState(
    val adapter: NetworkAdapter? = null,
    val mode: BootMode = BootMode.Proxy,
    val port: String = "8080",
    val bootFile: String = "",
    val poolStart: String = "",
    val poolEnd: String = "",
    val script: String = "",
    val profileRevision: Long? = null,
) {
    val portValid: Boolean get() = port.toIntOrNull()?.let { it in 1024..65535 } == true
    val scriptValid: Boolean get() = script.startsWith("#!ipxe")
    val poolValid: Boolean get() = poolStart.isNotBlank() && poolEnd.isNotBlank() &&
        allocate(poolStart, poolEnd) != null

    fun selectAdapter(next: NetworkAdapter?): PxeFormState =
        if (next == adapter) this else copy(adapter = next).resetPool()

    private fun resetPool(): PxeFormState {
        val pool = allocate("", "")
        return copy(poolStart = pool?.start.orEmpty(), poolEnd = pool?.end.orEmpty())
    }

    fun reconcile(profile: BootProfileEntity?, adapters: List<NetworkAdapter>): PxeFormState {
        if (profile != null && profileRevision != profile.updatedAt) {
            val selected = matchingAdapter(adapters, profile.interfaceName, profile.listenAddress)
            val restored = copy(
                adapter = selected,
                mode = BootMode.fromWireValue(profile.mode) ?: BootMode.Proxy,
                port = profile.httpPort.toString(),
                bootFile = profile.bootFile,
                poolStart = profile.dhcpPoolStart,
                poolEnd = profile.dhcpPoolEnd,
                script = profile.menuJson.ifBlank { script },
                profileRevision = if (selected == null) profileRevision else profile.updatedAt,
            )
            // A saved pool belongs to its saved interface/address, never the fallback interface.
            return if (selected?.name == profile.interfaceName && selected.address == profile.listenAddress && restored.poolValid) {
                restored
            } else restored.resetPool()
        }
        return selectAdapter(matchingAdapter(adapters, adapter?.name, adapter?.address))
    }

    private fun allocate(start: String, end: String): DhcpPool? {
        val current = adapter ?: return null
        return try {
            DhcpPoolAllocator.allocate(start, end, current.address, current.subnetMask)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    companion object {
        private fun matchingAdapter(adapters: List<NetworkAdapter>, name: String?, address: String?): NetworkAdapter? =
            adapters.firstOrNull { it.name == name && it.address == address }
                ?: adapters.firstOrNull { it.name == name }
                ?: adapters.firstOrNull()

        val Saver = listSaver<PxeFormState, Any>(
            save = { listOf(it.adapter?.name.orEmpty(), it.adapter?.address.orEmpty(), it.adapter?.prefixLength ?: -1,
                it.mode.wireValue, it.port, it.bootFile, it.poolStart, it.poolEnd, it.script, it.profileRevision ?: -1L) },
            restore = {
                PxeFormState(
                    adapter = if ((it[0] as String).isEmpty()) null else NetworkAdapter(it[0] as String, it[1] as String, it[2] as Int),
                    mode = BootMode.fromWireValue(it[3] as String) ?: BootMode.Proxy,
                    port = it[4] as String, bootFile = it[5] as String,
                    poolStart = it[6] as String, poolEnd = it[7] as String, script = it[8] as String,
                    profileRevision = (it[9] as Long).takeUnless { revision -> revision == -1L },
                )
            },
        )
    }
}
