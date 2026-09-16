package com.sky22333.netboot.data

/**
 * DHCP mode used by the PXE core. The values match `mobilecore.ModeProxy` / `mobilecore.ModeDHCP`.
 *
 * Both modes are offered because they serve mutually exclusive networks:
 * - [Proxy] coexists with the router's DHCP server and never hands out addresses. It is the only
 *   safe choice on a normal home or office LAN.
 * - [Dhcp] runs a complete DHCP server. It is required when there is no other DHCP server at all
 *   (phone cabled straight into a PC, or a fully isolated switch), where ProxyDHCP cannot work
 *   because the client never receives an IP address. On a network that already has a DHCP server
 *   the core refuses to start, so this mode cannot silently cause an address conflict.
 */
enum class BootMode(val wireValue: String) {
    Proxy("proxy"),
    Dhcp("dhcp"),
    ;

    companion object {
        fun fromWireValue(value: String): BootMode? = entries.firstOrNull { it.wireValue == value }
    }
}
