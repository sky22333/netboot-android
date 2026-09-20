package com.sky22333.netboot.root

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.sky22333.netboot.data.UsbMediaLayout

data class RestoreResult(val hadState: Boolean, val failures: List<String>) {
    val complete: Boolean get() = failures.isEmpty()
}

/** Kernel attributes are commands, not ordinary files. Tests emulate their store/rmdir semantics. */
open class GadgetIo {
    open fun write(node: File, value: String) {
        FileOutputStream(node).use { it.write((value + "\n").toByteArray(Charsets.UTF_8)) }
    }
    open fun createFunction(directory: File) { Files.createDirectory(directory.toPath()) }
    open fun removeFunction(directory: File) { Files.delete(directory.toPath()) }
    open fun link(link: File, function: File) { Files.createSymbolicLink(link.toPath(), function.toPath()) }
    open fun unlink(link: File) { Files.delete(link.toPath()) }
}

/** Owns one function on an existing gadget. Never modifies platform descriptors or functions. */
class UsbGadgetController(
    private val managedIsoDirectory: File,
    private val stateFile: File,
    private val candidateRoots: List<File> = listOf(File("/config/usb_gadget"), File("/sys/kernel/config/usb_gadget")),
    private val udcRoot: File = File("/sys/class/udc"),
    private val io: GadgetIo = GadgetIo(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val guardRoots = candidateRoots.map { it.absolutePath }

    fun probe(): UsbCapability = try {
        resolve()
        UsbCapability(true)
    } catch (error: UsbException) {
        UsbCapability(false, error.code.substringBefore(':'))
    }

    private fun resolve(): UsbState {
        val root = candidateRoots.firstOrNull { it.isDirectory } ?: throw UsbException("configfs_unavailable")
        if (!isCreatable(root)) throw UsbException("configfs_not_writable")
        if (udcControllerNames().isEmpty()) throw UsbException("udc_unavailable")
        val gadgets = root.listFiles().orEmpty().filter { it.isDirectory && File(it, "UDC").isFile && read(File(it, "UDC")).isNotBlank() }
        val gadget = gadgets.singleOrNull() ?: throw UsbException("active_gadget_unavailable")
        // Multiple configurations require a device-specific selection policy; guessing is unsafe.
        val config = File(gadget, "configs").listFiles().orEmpty().filter { it.isDirectory }.singleOrNull()
            ?: throw UsbException("gadget_read_only")
        if (!isCreatable(config) || !isCreatable(File(gadget, "functions"))) throw UsbException("gadget_read_only")
        return UsbState(root.canonicalPath, gadget.canonicalPath, config.canonicalPath, read(File(gadget, "UDC")))
            .also(::validateState)
    }

    fun udcControllerNames(): List<String> = udcRoot.listFiles().orEmpty().map { it.name }

    fun isHostConnected(): Boolean {
        val state = readState() ?: return false
        validateState(state)
        return isAttached() && read(File(udcRoot, "${state.udc}/state")) == "configured"
    }

    fun attach(isoPath: String, cdrom: Boolean = true) {
        val iso = validateBackingFile(managedIsoDirectory, File(isoPath))
        // Validate media layout before changing configfs.
        if (cdrom) UsbMediaLayout.requireOptical(iso)
        else if (!UsbMediaLayout.isDisk(iso)) throw UsbException("media_invalid_disk")
        if (stateFile.exists()) {
            val recovery = restore()
            if (!recovery.complete) throw UsbException("usb_restore_failed:${recovery.failures.joinToString()}")
        }
        val state = resolve().copy(isoPath = iso.canonicalPath, cdrom = cdrom)
        val function = function(state)
        if (function.exists() || link(state).exists()) throw UsbException("usb_restore_failed:unowned_function")
        // Journal ownership before the first mutation, including partial function creation.
        writeState(state)
        try {
            checked(function) { io.createFunction(function) }
            val lun = File(function, "lun.0")
            if (!lun.isDirectory) throw UsbException("lun_node_missing")
            write(File(lun, "ro"), "1")
            write(File(lun, "cdrom"), if (cdrom) "1" else "0")
            write(File(lun, "removable"), "1")
            write(File(lun, "file"), iso.canonicalPath)
            verifyLun(state)
            unbind(state)
            checked(link(state)) { io.link(link(state), function) }
            bind(state)
            if (!isAttached()) throw UsbException("usb_attach_failed:verification_failed")
        } catch (error: Exception) {
            val recovery = restore()
            val code = if (recovery.complete) "usb_attach_failed" else "usb_restore_failed"
            throw UsbException("$code:${error.message}; recovery=${recovery.failures.joinToString()}", error)
        }
    }

    /** Retains the journal on any failure so a subsequent attempt can safely complete cleanup. */
    fun restore(): RestoreResult {
        if (!stateFile.exists()) return RestoreResult(false, emptyList())
        val state = try {
            (readState() ?: throw UsbException("usb_restore_failed:missing_state")).also(::validateState)
        } catch (error: Exception) {
            return RestoreResult(true, listOf("invalid_state:${error.message}"))
        }
        val failures = mutableListOf<String>()
        fun step(action: () -> Unit): Boolean = try {
            action(); true
        } catch (error: Exception) {
            failures += error.message ?: error.javaClass.simpleName
            false
        }
        val function = function(state)
        val link = link(state)
        val hasLink = Files.isSymbolicLink(link.toPath())
        if (hasLink) {
            if (step { unbind(state) }) step { checked(link) { io.unlink(link) } }
        } else if (link.exists()) {
            failures += "unexpected_link_node"
        }
        if (!Files.isSymbolicLink(link.toPath()) && !link.exists() && function.exists()) {
            val backing = File(function, "lun.0/file")
            if (backing.exists()) step { write(backing, "") }
            // rmdir releases default configfs groups and attributes; never recursively unlink them.
            step { checked(function) { io.removeFunction(function) } }
        }
        step { bind(state) }
        if (failures.isEmpty()) step { Files.delete(stateFile.toPath()) }
        return RestoreResult(true, failures)
    }

    fun isAttached(): Boolean {
        val state = readState() ?: return false
        validateState(state)
        if (!Files.isSymbolicLink(link(state).toPath()) || read(File(state.gadget, "UDC")) != state.udc) return false
        verifyLun(state)
        return true
    }

    private fun verifyLun(state: UsbState) {
        val lun = File(function(state), "lun.0")
        if (read(File(lun, "ro")) != "1" || read(File(lun, "cdrom")) != if (state.cdrom) "1" else "0") throw UsbException("usb_attach_failed:read_only_verification")
        val backing = read(File(lun, "file"))
        if (backing.isBlank() || (state.isoPath.isNotBlank() && backing != state.isoPath)) throw UsbException("backing_file_mismatch")
    }

    private fun unbind(state: UsbState) {
        val node = File(state.gadget, "UDC")
        val current = read(node)
        if (current.isEmpty()) return
        if (current != state.udc) throw UsbException("usb_restore_failed:controller_changed")
        write(node, "")
        if (read(node).isNotEmpty()) throw UsbException("usb_unbind_failed")
    }

    private fun bind(state: UsbState) {
        val node = File(state.gadget, "UDC")
        val current = read(node)
        if (current == state.udc) return
        if (current.isNotBlank()) throw UsbException("usb_restore_failed:controller_changed")
        write(node, state.udc)
        if (read(node) != state.udc) throw UsbException("usb_restore_failed:rebind_failed")
    }

    private fun validateState(state: UsbState) {
        val root = File(state.configRoot).canonicalFile
        val gadget = File(state.gadget).canonicalFile
        val config = File(state.configDir).canonicalFile
        if (candidateRoots.none { it.canonicalFile == root } || gadget.parentFile != root ||
            config.parentFile != File(gadget, "configs") || state.udc !in udcControllerNames()) {
            throw UsbException("usb_restore_failed:invalid_state_paths")
        }
        listOf(gadget, config, function(state), link(state), File(gadget, "UDC")).forEach {
            ConfigfsGuard.requireNode(it, "USB session", guardRoots)
        }
        if (Files.isSymbolicLink(function(state).toPath())) throw UsbException("usb_restore_failed:invalid_function")
        if (Files.isSymbolicLink(link(state).toPath()) && link(state).canonicalFile != function(state).canonicalFile) {
            throw UsbException("usb_restore_failed:foreign_link")
        }
    }

    private fun function(state: UsbState) = File(state.gadget, "functions/$FunctionName")
    private fun link(state: UsbState) = File(state.configDir, FunctionName)
    private fun checked(node: File, action: () -> Unit) {
        ConfigfsGuard.requireNode(node, "USB mutation", guardRoots)
        action()
    }
    private fun write(node: File, value: String) = checked(node) { io.write(node, value) }
    private fun read(node: File): String = node.readText().trim()

    private fun writeState(state: UsbState) {
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, stateFile.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(UsbState.serializer(), state).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(stateFile)) throw UsbException("state_write_failed")
    }
    private fun readState(): UsbState? = if (stateFile.exists()) {
        json.decodeFromString(UsbState.serializer(), stateFile.readText())
    } else null

    @Serializable
    private data class UsbState(val configRoot: String, val gadget: String, val configDir: String, val udc: String, val isoPath: String = "", val cdrom: Boolean = true)

    companion object {
        private const val FunctionName = "mass_storage.netboot"
        fun validateBackingFile(managedDirectory: File, requested: File): File {
            val root = managedDirectory.canonicalFile
            val file = requested.canonicalFile
            if (Files.isSymbolicLink(requested.toPath())) throw UsbException("iso_symlink_rejected")
            if (file == root || !file.path.startsWith(root.path + File.separator)) throw UsbException("iso_outside_private_storage")
            if (!file.isFile || file.length() == 0L) throw UsbException("iso_not_regular_file")
            val source = file.parentFile == root && file.extension.equals("iso", true)
            val prepared = file.parentFile == File(root, "media") && file.name.matches(Regex("[a-f0-9]{64}-v1\\.img"))
            if (!source && !prepared) throw UsbException("not_iso")
            return file
        }
        internal fun isCreatable(directory: File): Boolean = directory.isDirectory && Files.isWritable(directory.toPath())
    }
}

class UsbException(val code: String, cause: Throwable? = null) : IOException(code, cause)
