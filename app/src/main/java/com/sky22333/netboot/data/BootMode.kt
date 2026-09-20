package com.sky22333.netboot.data

/** Core mode names: proxy uses an existing DHCP server; full DHCP requires an isolated network. */
enum class BootMode(val wireValue: String) {
    Proxy("proxy"),
    Dhcp("dhcp"),
    ;

    companion object {
        fun fromWireValue(value: String): BootMode? = entries.firstOrNull { it.wireValue == value }
    }
}
