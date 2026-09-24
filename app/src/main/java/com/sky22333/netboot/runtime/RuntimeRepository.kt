package com.sky22333.netboot.runtime

import android.content.Context
import android.net.Uri
import com.sky22333.netboot.data.DriverRepository
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import java.net.SocketException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import android.util.Log
import com.sky22333.netboot.data.AppDatabase
import com.sky22333.netboot.data.BootMode
import com.sky22333.netboot.data.BootProfileEntity
import com.sky22333.netboot.data.RuntimeEventEntity
import com.sky22333.netboot.data.IsoRepository
import com.sky22333.netboot.data.IsoState
import com.sky22333.netboot.data.UsbMediaRepository
import com.sky22333.netboot.data.UsbPreparationStage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import com.sky22333.netboot.root.BrokerException
import com.sky22333.netboot.root.RootBrokerClient
import com.sky22333.netboot.root.UsbCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RuntimeState(
    val rootAvailable: Boolean? = null,
    val usbCapability: UsbCapability? = null,
    val networkRunning: Boolean = false,
    val usbAttached: Boolean = false,
    val usbHostConnected: Boolean = false,
    val activeIsoId: String? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val usbRecoveryRequired: Boolean = false,
    val usbPreparing: Boolean = false,
    val preparedBytes: Long = 0,
    val preparationTotal: Long = 0,
    val preparationStage: UsbPreparationStage = UsbPreparationStage.CopyFiles,
    val usbDiskMode: Boolean = false,
) {
    val usbUnsupported: Boolean get() = usbCapability?.supported == false
}

data class NetworkAdapter(val name: String, val address: String, val prefixLength: Int = 24) {
    val subnetMask: String get() = (0..3).joinToString(".") { index ->
        ((0xffffffffL shl (32 - prefixLength)) ushr (24 - index * 8) and 255).toString()
    }
}

@Singleton
class RuntimeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val broker: RootBrokerClient,
    private val isoRepository: IsoRepository,
    private val usbMedia: UsbMediaRepository,
    private val drivers: DriverRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    // Driver imports must not hold up PXE controls.
    private val mediaMutex = Mutex()
    private val eventsSincePrune = AtomicInteger()
    @Volatile private var preparationJob: Job? = null
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch { broker.events.collect(::recordEvent) }
        scope.launch { pruneEvents() }
    }

    suspend fun probe() = mediaMutex.withLock {
        runOperation("probe") {
            drivers.recover()
            try {
                val capability = broker.probe()
                mutableState.value = mutableState.value.copy(rootAvailable = true, usbCapability = capability)
            } catch (error: BrokerException) {
                if (error.code != "root_unavailable") throw error
                mutableState.value = mutableState.value.copy(rootAvailable = false, usbCapability = null)
            }
        }
    }

    suspend fun startNetwork(profileId: String) = runOperation("start_network") {
        if (mutableState.value.networkRunning) throw BrokerException("network_already_running")
        val profile = database.bootProfileDao().find(profileId) ?: error("profile_not_found")
        val mode = BootMode.fromWireValue(profile.mode) ?: error("unsupported_boot_mode")
        val adapter = interfaces().firstOrNull { it.name == profile.interfaceName && it.address == profile.listenAddress }
            ?: error("network_interface_changed")
        val root = File(context.filesDir, "pxe").apply { mkdirs() }
        broker.startNetwork(json.encodeToString(profile.toCoreConfig(mode, root, adapter)))
        mutableState.value = mutableState.value.copy(networkRunning = true)
    }

    suspend fun stopNetwork() = runOperation("stop_network") {
        broker.stopNetwork()
        mutableState.value = mutableState.value.copy(networkRunning = false)
    }

    suspend fun attachIso(isoId: String) = mediaMutex.withLock {
        runOperation("attach_usb") {
            check(!mutableState.value.usbAttached && !mutableState.value.usbRecoveryRequired) { "image_in_use" }
            val asset = database.isoDao().find(isoId) ?: error("asset_not_found")
            check(asset.state == IsoState.Ready) { "image_in_use" }
            insertEvent(severity = "info", source = "media", code = "media_inspection", argumentsJson = json.encodeToString(
                mapOf("source" to asset.source, "bytes" to asset.fileSize.toString(), "sha256" to asset.sha256),
            ))
            mutableState.value = mutableState.value.copy(activeIsoId = isoId)
            val media = try {
                preparationJob = currentCoroutineContext()[Job]
                mutableState.value = mutableState.value.copy(usbPreparing = true, preparedBytes = 0, preparationTotal = 0, preparationStage = UsbPreparationStage.CopyFiles)
                usbMedia.prepare(asset) { stage, done, total ->
                    mutableState.value = mutableState.value.copy(preparedBytes = done, preparationTotal = total, preparationStage = stage)
                }
            } finally {
                preparationJob = null
                mutableState.value = mutableState.value.copy(usbPreparing = false)
            }
            broker.attachIso(media.file, media.cdrom)
            mutableState.value = mutableState.value.copy(
                usbAttached = true,
                usbHostConnected = runCatching { broker.usbHostConnected() }.getOrDefault(false),
                activeIsoId = isoId,
                usbDiskMode = !media.cdrom,
            )
        }
    }

    fun cancelUsbPreparation() { preparationJob?.cancel() }

    suspend fun changeDrivers(id: String, uri: Uri?, progress: (Long) -> Unit) = mediaMutex.withLock {
        check(!mutableState.value.usbAttached && !mutableState.value.usbRecoveryRequired) { "image_in_use" }
        val asset = database.isoDao().find(id) ?: error("asset_not_found")
        check(asset.state == IsoState.Ready) { "image_in_use" }
        drivers.replace(asset, uri, progress)
    }

    suspend fun detachIso() = runOperation("detach_usb") {
        broker.detachIso()
        mutableState.value = mutableState.value.copy(
            usbAttached = false,
            usbHostConnected = false,
            activeIsoId = null,
            usbRecoveryRequired = false,
        )
    }

    suspend fun refreshUsbConnection() {
        if (!mutableState.value.usbAttached) return
        val connected = runCatching { broker.usbHostConnected() }.getOrDefault(false)
        if (connected != mutableState.value.usbHostConnected) {
            mutableState.value = mutableState.value.copy(usbHostConnected = connected)
        }
    }

    suspend fun shutdown() = runOperation("shutdown") {
        broker.shutdown()
        mutableState.value = RuntimeState(rootAvailable = mutableState.value.rootAvailable)
    }

    fun shutdownAfterServiceDestroyed() { scope.launch { shutdown() } }

    suspend fun deleteIso(id: String): Boolean = mediaMutex.withLock {
        operationMutex.withLock {
            val active = mutableState.value.activeIsoId?.let { database.isoDao().find(it) }
            val target = database.isoDao().find(id)
            if (mutableState.value.activeIsoId == id || mutableState.value.usbRecoveryRequired || (active != null && active.sha256 == target?.sha256)) false else isoRepository.delete(id)
        }
    }

    fun observeInterfaces() = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        trySend(Unit)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate().map {
        try { interfaces() } catch (_: SocketException) { emptyList() }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun interfaces(): List<NetworkAdapter> = withContext(Dispatchers.IO) { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { network ->
            network.interfaceAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
                .map { NetworkAdapter(network.name, it.address.hostAddress.orEmpty(), it.networkPrefixLength.toInt()) }
        }
        .sortedBy { it.name }
    }

    private suspend fun runOperation(operation: String, block: suspend () -> Unit) = operationMutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true, errorCode = null)
        runCatching { block() }.onFailure { error ->
            if (error is CancellationException) {
                mutableState.value = mutableState.value.copy(busy = false, activeIsoId = if (operation == "attach_usb" && !mutableState.value.usbAttached) null else mutableState.value.activeIsoId)
                throw error
            }
            val code = normalizeError(error)
            mutableState.value = mutableState.value.copy(
                errorCode = code,
                usbRecoveryRequired = mutableState.value.usbRecoveryRequired || code == "usb_restore_failed",
                activeIsoId = if (operation == "attach_usb" && code != "usb_restore_failed" && !mutableState.value.usbAttached) null else mutableState.value.activeIsoId,
            )
            // Log device-specific details; the UI displays only the localized error code.
            Log.w(LogTag, "$operation failed: code=$code detail=${error.message}")
            insertEvent(
                severity = "error",
                source = "runtime",
                code = "operation_failed",
                argumentsJson = json.encodeToString(mapOf("operation" to operation, "code" to code, "detail" to (error.message ?: ""))),
            )
        }
        mutableState.value = mutableState.value.copy(busy = false)
    }

    /** Preserve broker error codes for the UI and full failure details for logs. */
    private fun normalizeError(error: Throwable): String {
        val brokerCode = (error as? BrokerException)?.code
        val message = brokerCode?.takeIf { it.isNotBlank() } ?: error.message ?: return "runtime_failure"
        return when {
            message.startsWith("media_") || message == "insufficient_storage" -> message.substringBefore(':')
            message.contains("listen HTTP", ignoreCase = true) -> "http_port_unavailable"
            message.contains("listen TFTP", ignoreCase = true) -> "tftp_port_unavailable"
            message.contains("listen DHCP", ignoreCase = true) -> "dhcp_port_unavailable"
            KnownCodes.any { message.startsWith(it, ignoreCase = true) } -> message.substringBefore(':').lowercase()
            brokerCode != null -> brokerCode.substringBefore(':')
            else -> "runtime_failure"
        }
    }

    private suspend fun recordEvent(raw: String) {
        val event = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val code = event["code"]?.jsonPrimitive?.content ?: "unknown"
        insertEvent(
            timestamp = event["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis(),
            severity = event["level"]?.jsonPrimitive?.content ?: "info",
            source = event["source"]?.jsonPrimitive?.content ?: "core",
            code = code,
            argumentsJson = event["arguments"]?.toString() ?: "{}",
        )
        // Expose recovery failures in UI state so the user can restore USB.
        if (code == "usb_restore_failed") {
            mutableState.value = mutableState.value.copy(errorCode = code, usbRecoveryRequired = true, usbHostConnected = false)
        }
    }

    private suspend fun insertEvent(
        severity: String,
        source: String,
        code: String,
        argumentsJson: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        database.runtimeEventDao().insert(RuntimeEventEntity(timestamp = timestamp, severity = severity, source = source, eventCode = code, argumentsJson = argumentsJson))
        if (eventsSincePrune.incrementAndGet() >= 25) {
            eventsSincePrune.set(0)
            pruneEvents()
        }
    }

    private suspend fun pruneEvents() {
        database.runtimeEventDao().prune(System.currentTimeMillis() - EventRetentionMillis, MaxStoredEvents)
    }

    private fun BootProfileEntity.toCoreConfig(mode: BootMode, root: File, adapter: NetworkAdapter): CoreConfig {
        val pool = if (mode == BootMode.Dhcp) DhcpPoolAllocator.allocate(
            requestedStart = dhcpPoolStart,
            requestedEnd = dhcpPoolEnd,
            serverAddress = advertiseAddress,
            subnetMask = adapter.subnetMask,
        ) else null
        return CoreConfig(
            listenIp = listenAddress,
            advertiseIp = advertiseAddress,
            mode = mode.wireValue,
            root = root.canonicalPath,
            httpPort = httpPort,
            bootFile = bootFile,
            ipxeScript = menuJson.takeIf { it.startsWith("#!ipxe") }.orEmpty(),
            maxTransfers = 16,
            dhcp = DhcpConfig(
                poolStart = pool?.start.orEmpty(),
                poolEnd = pool?.end.orEmpty(),
                subnetMask = adapter.subnetMask,
                router = "",
                dns = "",
                leaseSeconds = DefaultLeaseSeconds,
            ),
        )
    }

    companion object {
        private const val LogTag = "NetBootRuntime"

        private const val MaxStoredEvents = 5000

        /** Prune on insert to avoid idle background work. */
        private const val EventRetentionMillis = 7L * 24 * 60 * 60 * 1000
        private const val DefaultLeaseSeconds = 86400

        private val KnownCodes = setOf(
            "root_unavailable",
            "broker_start_failed",
            "network_interface_changed",
            "network_already_running",
            "profile_not_found",
            "asset_not_found",
            "unsupported_boot_mode",
            "usb_restore_failed",
            "usb_attach_failed",
            "usb_unbind_failed",
            "lun_node_missing",
            "backing_file_mismatch",
        )
    }
}

@Serializable
private data class CoreConfig(
    val listenIp: String,
    val advertiseIp: String,
    val mode: String,
    val root: String,
    val httpPort: Int,
    val bootFile: String,
    val ipxeScript: String,
    val maxTransfers: Int,
    val dhcp: DhcpConfig,
)

@Serializable
private data class DhcpConfig(
    val poolStart: String,
    val poolEnd: String,
    val subnetMask: String,
    val router: String,
    val dns: String,
    val leaseSeconds: Int,
)
