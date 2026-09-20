package com.sky22333.netboot.runtime

/** Inclusive DHCP range, separate from the server address. */
data class DhcpPool(val start: String, val end: String) {
    val size: Int = (DhcpPoolAllocator.toUInt(end) - DhcpPoolAllocator.toUInt(start) + 1).toInt()
}

/** Derive a pool from the selected subnet, excluding server, network and broadcast addresses. */
object DhcpPoolAllocator {
    /** Usable hosts are capped so a phone never tries to track a huge lease table. */
    private const val MaxPoolSize = 254

    fun allocate(
        requestedStart: String?,
        requestedEnd: String?,
        serverAddress: String,
        subnetMask: String? = null,
    ): DhcpPool {
        val server = parse(serverAddress) ?: error("invalid_server_address")
        val prefix = maskPrefix(subnetMask)
        val network = server and (if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL)
        val broadcast = network or (0xFFFFFFFFL ushr prefix)
        require(server > network && server < broadcast) { "invalid_server_address" }
        if (!requestedStart.isNullOrBlank() || !requestedEnd.isNullOrBlank()) {
            val start = parse(requestedStart) ?: error("invalid_dhcp_pool")
            val end = parse(requestedEnd) ?: error("invalid_dhcp_pool")
            require(start > network && end < broadcast && start <= end &&
                end - start + 1 <= MaxPoolSize && server !in start..end) { "invalid_dhcp_pool" }
            return DhcpPool(format(start), format(end))
        }

        // Use the larger contiguous host range on either side of the server, capped at 254.
        // This excludes reserved addresses; it cannot establish whether other hosts use the range.
        val firstHost = network + 1
        val lastHost = broadcast - 1
        if (lastHost <= firstHost) error("invalid_server_address")
        val afterServer = lastHost - server
        val beforeServer = server - firstHost
        val start = if (afterServer >= beforeServer) server + 1 else firstHost
        val end = if (afterServer >= beforeServer) lastHost else server - 1
        require(start <= end) { "invalid_dhcp_pool" }
        return DhcpPool(format(start), format(minOf(end, start + MaxPoolSize - 1)))
    }

    internal fun toUInt(address: String): Long = parse(address) ?: error("invalid_address: $address")

    private fun parse(address: String?): Long? {
        val parts = address?.trim()?.split('.') ?: return null
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || (part.length > 1 && part.startsWith('0'))) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun format(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }

    private fun maskPrefix(mask: String?): Int {
        if (mask == null) return 24
        val parsed = parse(mask) ?: error("invalid_subnet_mask")
        val bits = parsed.toString(2)
        val prefix = bits.count { it == '1' }
        require(bits.trimEnd('0').length == prefix && prefix in 1..30) { "invalid_subnet_mask" }
        return prefix
    }
}
