package com.sky22333.netboot.root

import java.io.File

/** Restricts privileged writes to canonical configfs gadget paths, including restored state. */
internal object ConfigfsGuard {
    val allowedRoots = listOf("/config/usb_gadget", "/sys/kernel/config/usb_gadget")

    /** Inject roots for tests; production uses the configfs allow-list. */
    private fun rootPrefixes(roots: List<String>): List<String> = roots.mapNotNull { allowed ->
        File(allowed).takeIf { it.isDirectory }?.canonicalPath
    }

    /** Resolve symlinks and dot segments before checking the configfs boundary. */
    fun requireNode(target: File, description: String, roots: List<String> = allowedRoots) {
        val canonical = target.canonicalPath
        val prefixes = rootPrefixes(roots)
        check(prefixes.isNotEmpty()) { "configfs is unavailable; refusing to touch $canonical" }
        check(prefixes.any { canonical == it || canonical.startsWith(it + File.separator) }) {
            "refusing $description at $canonical: outside ${roots.joinToString(" and ")}"
        }
        // Defence in depth: no gadget attribute is ever a block device.
        check(!canonical.startsWith(DeviceTreePrefix)) {
            "refusing $description at $canonical: block devices are never touched"
        }
    }

    fun requireRoot(target: File, roots: List<String> = allowedRoots) = requireNode(target, "gadget root", roots)

    fun requireGadget(target: File, roots: List<String> = allowedRoots) = requireNode(target, "gadget directory", roots)

    fun requireFunction(target: File, roots: List<String> = allowedRoots) =
        requireNode(target, "gadget function", roots)

    private const val DeviceTreePrefix = "/dev/"
}
