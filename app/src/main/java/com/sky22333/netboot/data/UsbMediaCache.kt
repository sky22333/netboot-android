package com.sky22333.netboot.data

import java.io.IOException

/** Shared filename contract for media preparation and the privileged backing-file allowlist. */
object UsbMediaCache {
    private const val HashPattern = "[a-f0-9]{64}"
    private const val Version = "v2"
    private val hash = Regex(HashPattern)
    private val filename = Regex("$HashPattern(?:-$HashPattern)?-$Version\\.img")

    fun fileName(sourceHash: String, driverHash: String = ""): String {
        val source = sourceHash.lowercase()
        if (!hash.matches(source)) throw IOException("media_source_changed")
        if (driverHash.isNotEmpty() && !hash.matches(driverHash)) throw IOException("drivers_invalid")
        return "$source${if (driverHash.isEmpty()) "" else "-$driverHash"}-$Version.img"
    }

    fun isPreparedFileName(name: String): Boolean = filename.matches(name)
}
