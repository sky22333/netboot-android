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
import com.sky22333.netboot.data.IsoArchitecture
import com.sky22333.netboot.data.IsoLanguage
import com.sky22333.netboot.data.IsoRepository
import com.sky22333.netboot.data.IsoRequest
import com.sky22333.netboot.data.PxeFileRepository
import com.sky22333.netboot.data.isBootFileUsable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val networkAdapters = runtimeRepository.observeInterfaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    val profiles = database.bootProfileDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = database.runtimeEventDao().observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runtime = runtimeRepository.state
    val pxeFiles = pxeFileRepository.files
    val defaultIpxeScript = flow { emit(pxeFileRepository.defaultScript()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, "")
    private val mutableDownloadCreationBusy = MutableStateFlow(false)
    val downloadCreationBusy = mutableDownloadCreationBusy.asStateFlow()
    val importProgress = isoRepository.importProgress
    data class DriverImport(val assetId: String, val bytes: Long = 0)
    private val mutableDriverImport = MutableStateFlow<DriverImport?>(null)
    val driverImport = mutableDriverImport.asStateFlow()
    private var driverJob: Job? = null
    private val imports = ConcurrentHashMap<String, Job>()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    init {
        // Probe on launch so the home screen reflects root and USB availability.
        refreshCapabilities()
        launch {
            // Recover downloads from persisted state after process death.
            downloadRepository.recoverInterrupted().forEach { DownloadService.start(context, it) }
        }
    }

    fun importIso(uri: Uri) = viewModelScope.launch {
        var assetId: String? = null
        val job = currentCoroutineContext()[Job] ?: return@launch
        try {
            isoRepository.import(uri) { id -> assetId = id; imports[id] = job }
            mutableMessages.emit("import_complete")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            mutableMessages.emit(error.message ?: "import_failed")
        } catch (error: IllegalArgumentException) {
            mutableMessages.emit(error.message ?: "import_failed")
        } catch (_: SecurityException) {
            mutableMessages.emit("source_unavailable")
        } finally {
            assetId?.let(imports::remove)
        }
    }

    fun cancelImport(id: String) { imports[id]?.cancel() }

    fun changeDrivers(id: String, uri: Uri?) {
        if (driverJob?.isActive == true) return
        driverJob = viewModelScope.launch {
            mutableDriverImport.value = DriverImport(id)
            var lastUpdate = 0L
            try {
                runtimeRepository.changeDrivers(id, uri) { bytes ->
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 250_000_000L) {
                        mutableDriverImport.value = DriverImport(id, bytes)
                        lastUpdate = now
                    }
                }
                mutableMessages.emit("drivers_saved")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: java.io.IOException) {
                mutableMessages.emit(error.message ?: "drivers_invalid")
            } catch (_: SecurityException) {
                mutableMessages.emit("source_unavailable")
            } catch (error: IllegalArgumentException) {
                mutableMessages.emit("drivers_invalid")
            } catch (error: IllegalStateException) {
                mutableMessages.emit(error.message ?: "operation_failed")
            } finally {
                mutableDriverImport.value = null
            }
        }
    }

    fun cancelDriverImport() { driverJob?.cancel() }

    fun download(version: WindowsVersion, language: IsoLanguage, architecture: IsoArchitecture) {
        if (downloadCreationBusy.value) return
        viewModelScope.launch {
            mutableDownloadCreationBusy.value = true
            try {
                val taskId = downloadRepository.create(IsoRequest(version, language, architecture), settings.value.downloadConnections)
                DownloadService.start(context, taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableMessages.emit(error.message ?: "operation_failed")
            } finally {
                mutableDownloadCreationBusy.value = false
            }
        }
    }

    fun pauseDownload(id: String) = DownloadService.pause(context, id)
    fun resumeDownload(id: String) = DownloadService.start(context, id)
    fun cancelDownload(id: String, deletePartial: Boolean) = DownloadService.cancel(context, id, deletePartial)
    fun deleteIso(id: String) = launch { check(runtimeRepository.deleteIso(id)) { "image_in_use" } }
    fun importPxeFiles(uris: List<Uri>) = launch { pxeFileRepository.import(uris) }
    suspend fun imageVolumeLabel(id: String): String? = isoRepository.volumeLabel(id)
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
        require(isBootFileUsable(bootFile, pxeFiles.value)) { "boot_file_not_imported" }
        require(ipxeScript.startsWith("#!ipxe")) { "invalid_ipxe_script" }
        require(adapter in interfaces()) { "network_interface_changed" }
        if (mode == BootMode.Dhcp) {
            // Validate the pool before starting the privileged session.
            runCatching {
                DhcpPoolAllocator.allocate(dhcpPoolStart, dhcpPoolEnd, adapter.address, adapter.subnetMask)
            }.getOrElse { throw IllegalArgumentException("invalid_dhcp_pool") }
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
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                for (event in events) {
                    currentCoroutineContext().ensureActive()
                    writer.append(formatLog(event))
                    writer.newLine()
                }
            } ?: error("log_export_failed")
        }
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

    private suspend fun interfaces(): List<NetworkAdapter> =
        try { runtimeRepository.interfaces() } catch (_: java.net.SocketException) { emptyList() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableMessages.emit(error.message ?: "operation_failed") }
        }
    }

    private companion object {
        const val DefaultProfileId = "default"
    }
}
