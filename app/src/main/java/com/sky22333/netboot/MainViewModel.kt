package com.sky22333.netboot

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sky22333.netboot.data.AppDatabase
import com.sky22333.netboot.data.AppSettings
import com.sky22333.netboot.data.BootMode
import com.sky22333.netboot.data.BootProfileEntity
import com.sky22333.netboot.data.DownloadState
import com.sky22333.netboot.data.IsoArchitecture
import com.sky22333.netboot.data.IsoLanguage
import com.sky22333.netboot.data.IsoRepository
import com.sky22333.netboot.data.IsoRequest
import com.sky22333.netboot.data.PxeFileRepository
import com.sky22333.netboot.data.SettingsRepository
import com.sky22333.netboot.data.WindowsVersion
import com.sky22333.netboot.data.RuntimeEventEntity
import com.sky22333.netboot.download.DownloadRepository
import com.sky22333.netboot.download.DownloadService
import com.sky22333.netboot.runtime.DhcpPoolAllocator
import com.sky22333.netboot.runtime.NetworkAdapter
import com.sky22333.netboot.runtime.RuntimeRepository
import com.sky22333.netboot.runtime.RuntimeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val isoRepository: IsoRepository,
    private val pxeFileRepository: PxeFileRepository,
    private val downloadRepository: DownloadRepository,
    private val runtimeRepository: RuntimeRepository,
) : ViewModel() {
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val assets = isoRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloads = downloadRepository.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profiles = database.bootProfileDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = database.runtimeEventDao().observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runtime = runtimeRepository.state
    val pxeFiles = pxeFileRepository.files
    val defaultIpxeScript = pxeFileRepository.defaultScript()
    val downloadCreationBusy = MutableStateFlow(false)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    init {
        // Capability detection must not depend on the user finding the button: the home screen shows
        // Root / PXE / USB status on launch, and "not checked" would hide the one answer a locked
        // bootloader device needs first.
        refreshCapabilities()
        viewModelScope.launch {
            // A download interrupted by process death is resumed from persisted state, without
            // replaying any user action.
            downloadRepository.observeTasks().first()
                .filter { it.state == DownloadState.Running || it.state == DownloadState.Queued }
                .forEach { DownloadService.start(context, it.id) }
        }
    }

    fun importIso(uri: Uri) = launch { isoRepository.import(uri) }

    fun download(version: WindowsVersion, language: IsoLanguage, architecture: IsoArchitecture) {
        if (downloadCreationBusy.value) return
        viewModelScope.launch {
            downloadCreationBusy.value = true
            runCatching {
                val taskId = downloadRepository.create(IsoRequest(version, language, architecture), settings.value.downloadConnections)
                DownloadService.start(context, taskId)
            }.onFailure { mutableMessages.emit(it.message ?: "operation_failed") }
            downloadCreationBusy.value = false
        }
    }

    fun pauseDownload(id: String) = DownloadService.pause(context, id)
    fun resumeDownload(id: String) = DownloadService.start(context, id)
    fun cancelDownload(id: String, deletePartial: Boolean) = DownloadService.cancel(context, id, deletePartial)
    fun deleteIso(id: String) = launch { check(runtimeRepository.deleteIso(id)) { "image_in_use" } }
    fun importPxeFiles(uris: List<Uri>) = launch { pxeFileRepository.import(uris) }
    fun deletePxeFile(name: String) = launch { pxeFileRepository.delete(name) }

    fun saveProfile(
        mode: BootMode,
        adapter: NetworkAdapter,
        port: Int,
        bootFile: String,
        ipxeScript: String,
        dhcpPoolStart: String,
        dhcpPoolEnd: String,
    ) = launch {
        saveProfileNow(mode, adapter, port, bootFile, ipxeScript, dhcpPoolStart, dhcpPoolEnd)
        mutableMessages.emit("configuration_saved")
    }

    fun startNetwork(
        mode: BootMode,
        adapter: NetworkAdapter,
        port: Int,
        bootFile: String,
        ipxeScript: String,
        dhcpPoolStart: String,
        dhcpPoolEnd: String,
    ) = launch {
        saveProfileNow(mode, adapter, port, bootFile, ipxeScript, dhcpPoolStart, dhcpPoolEnd)
        RuntimeService.startNetwork(context, DefaultProfileId)
    }

    private suspend fun saveProfileNow(
        mode: BootMode,
        adapter: NetworkAdapter,
        port: Int,
        bootFile: String,
        ipxeScript: String,
        dhcpPoolStart: String,
        dhcpPoolEnd: String,
    ) {
        require(port in 1024..65535) { "invalid_http_port" }
        require(bootFile.isNotBlank()) { "invalid_boot_file" }
        require(pxeFiles.value.any { it.name == bootFile }) { "boot_file_not_imported" }
        require(ipxeScript.startsWith("#!ipxe")) { "invalid_ipxe_script" }
        require(interfaces().any { it.name == adapter.name && it.address == adapter.address }) { "network_interface_changed" }
        if (mode == BootMode.Dhcp) {
            // Validate here so a bad range is reported before the privileged session starts; the
            // allocator additionally guarantees the pool never contains the server's own address.
            val pool = runCatching {
                DhcpPoolAllocator.allocate(dhcpPoolStart, dhcpPoolEnd, adapter.address, adapter.subnetMask)
            }.getOrElse { throw IllegalArgumentException("invalid_dhcp_pool") }
            require(pool.start != adapter.address && pool.end != adapter.address) { "invalid_dhcp_pool" }
        }
        database.bootProfileDao().upsert(
            BootProfileEntity(
                id = DefaultProfileId,
                name = "Default",
                mode = mode.wireValue,
                interfaceName = adapter.name,
                listenAddress = adapter.address,
                advertiseAddress = adapter.address,
                httpPort = port,
                bootFile = bootFile,
                menuJson = ipxeScript,
                dhcpPoolStart = dhcpPoolStart,
                dhcpPoolEnd = dhcpPoolEnd,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun stopNetwork() = RuntimeService.stopNetwork(context)
    fun attachIso(id: String) = RuntimeService.attachIso(context, id)
    fun detachIso() = RuntimeService.detachIso(context)
    fun cancelUsbPreparation() = runtimeRepository.cancelUsbPreparation()
    fun refreshCapabilities() = viewModelScope.launch {
        runtimeRepository.probe()
        runtimeRepository.refreshUsbConnection()
    }
    fun setConnections(value: Int) = launch { settingsRepository.setDownloadConnections(value) }
    fun setTheme(value: Int) = launch { settingsRepository.setThemeMode(value) }
    fun clearLogs() = launch {
        database.runtimeEventDao().clear()
        mutableMessages.emit("logs_cleared")
    }

    fun exportLogs(uri: Uri) = launch {
        val events = database.runtimeEventDao().latestChronological()
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            for (event in events) {
                writer.append(formatLog(event))
                writer.newLine()
            }
        } ?: error("log_export_failed")
        mutableMessages.emit("logs_exported")
    }

    fun copyLogs() = launch {
        val text = database.runtimeEventDao().latestChronological(1000).joinToString("\n", transform = ::formatLog)
        if (text.isEmpty()) return@launch
        // Keep Binder clipboard transactions bounded; file export retains the full log.
        val clipped = text.length > 200_000
        val content = if (clipped) context.getString(R.string.logs_copy_truncated) + "\n" + text.takeLast(200_000) else text
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.runtime_logs), content))
        if (Build.VERSION.SDK_INT < 33 || clipped) mutableMessages.emit(if (clipped) "logs_copy_truncated" else "logs_copied")
    }

    private fun formatLog(event: RuntimeEventEntity): String =
        "${Instant.ofEpochMilli(event.timestamp)} ${event.severity.uppercase()} ${event.source} ${event.eventCode}" +
            if (event.argumentsJson == "{}") "" else " ${event.argumentsJson}"

    fun interfaces(): List<NetworkAdapter> = runCatching { runtimeRepository.interfaces() }.getOrDefault(emptyList())

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { mutableMessages.emit(it.message ?: "operation_failed") }
        }
    }

    private companion object {
        const val DefaultProfileId = "default"
    }
}
