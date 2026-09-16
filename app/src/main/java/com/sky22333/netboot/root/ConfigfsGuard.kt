package com.sky22333.netboot.root

import java.io.File

/**
 * Enforces the immutable safety boundary from the project charter: the only kernel-provided paths
 * this app may write are USB gadget nodes under configfs. Everything else — most importantly any
 * block-device partition node under the device tree — is refused before a single byte is written.
 *
 * The guard exists because the recovery path trusts a JSON state file. A corrupted, hand-edited or
 * stale file must not be able to redirect a privileged write at a block device.
 */
internal object ConfigfsGuard {
    val allowedRoots = listOf("/config/usb_gadget", "/sys/kernel/config/usb_gadget")

    /**
     * Set of canonical prefixes that any privileged path must live under.
     *
     * [roots] is injectable so the allow-list itself can be exercised against temporary
     * directories; production call sites always use the default and therefore the real configfs.
     */
    private fun rootPrefixes(roots: List<String>): List<String> = roots.mapNotNull { allowed ->
        File(allowed).takeIf { it.isDirectory }?.canonicalPath
    }

    /**
     * Canonicalises [target] and asserts it is a configfs gadget node.
     *
     * Canonicalisation is what makes this robust: a path that walks up out of the gadget root with
     * dot segments collapses to its real location and is then rejected by the prefix check.
     */
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
