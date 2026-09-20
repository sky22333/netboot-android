package com.sky22333.netboot.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class RootBrokerClient @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<BrokerMessage>>()
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events = mutableEvents.asSharedFlow()

    @Volatile private var socket: LocalSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var brokerProcess: java.lang.Process? = null

    private suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0 && process.inputStream.bufferedReader().use { it.readText().trim() == "0" }
            }
        }.getOrDefault(false)
    }

    suspend fun probe(): UsbCapability = json.decodeFromString(request(BrokerOperation.Probe).payload)

    /** True after the host enumerates the exposed LUN. */
    suspend fun usbHostConnected(): Boolean =
        json.decodeFromString<BrokerStatus>(request(BrokerOperation.Status).payload).usbHostConnected

    suspend fun startNetwork(configJson: String): String = request(BrokerOperation.StartNetwork, configJson).payload

    suspend fun stopNetwork(): String = request(BrokerOperation.StopNetwork).payload

    suspend fun attachIso(file: File, cdrom: Boolean) {
        request(BrokerOperation.AttachReadOnlyIso, json.encodeToString(AttachIsoRequest(file.canonicalPath, cdrom)))
    }

    suspend fun detachIso() {
        request(BrokerOperation.DetachIso)
    }

    suspend fun shutdown() {
        if (socket == null) return
        request(BrokerOperation.Shutdown)
        closeConnection()
    }

    private suspend fun request(operation: String, payload: String = ""): BrokerMessage = withContext(Dispatchers.IO) {
        ensureConnected()
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<BrokerMessage>()
        pending[id] = result
        try {
            writeMutex.withLock {
                val bytes = json.encodeToString(BrokerRequest(id, operation, payload)).encodeToByteArray()
                require(bytes.size <= MaxMessageBytes) { "message_too_large" }
                val stream = output ?: throw IOException("broker_disconnected")
                stream.writeInt(bytes.size)
                stream.write(bytes)
                stream.flush()
            }
            val message = withTimeout(RequestTimeoutMillis) { result.await() }
            if (!message.ok) throw BrokerException(message.errorCode ?: "broker_failure")
            message
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureConnected() {
        connectionMutex.withLock {
            if (socket?.isConnected == true) return@withLock
            closeConnection()
            if (!rootAvailable()) throw BrokerException("root_unavailable")

            val socketName = "netboot_${randomHex(16)}"
            val application = context.applicationInfo
            val managedDirectory = File(context.filesDir, "iso").apply { mkdirs() }
            val stateFile = File(context.filesDir, "runtime/usb-state.json").apply { parentFile?.mkdirs() }
            val command = listOf(
            "CLASSPATH=${shellQuote(application.sourceDir)}",
            "LD_LIBRARY_PATH=${shellQuote(application.nativeLibraryDir)}",
            "app_process",
            "/system/bin",
            "--nice-name=netboot-root",
            RootBrokerMain::class.java.name,
            shellQuote(socketName),
            Process.myUid().toString(),
            shellQuote(managedDirectory.canonicalPath),
            shellQuote(stateFile.canonicalPath),
            ).joinToString(" ")
            brokerProcess = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()

            val connected = LocalSocket()
            var lastError: Exception? = null
            for (attempt in 0 until 30) {
                if (brokerProcess?.isAlive == false) break
                try {
                    connected.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    lastError = null
                    break
                } catch (error: IOException) {
                    lastError = error
                    delay(100)
                }
            }
            if (lastError != null || !connected.isConnected) {
                val detail = brokerProcess?.takeIf { !it.isAlive }?.inputStream?.bufferedReader()?.use { it.readText() }
                if (!detail.isNullOrBlank()) Log.e(LogTag, "Root broker exited during startup: $detail")
                brokerProcess?.destroy()
                throw BrokerException("broker_start_failed", lastError)
            }
            socket = connected
            output = DataOutputStream(connected.outputStream.buffered())
            scope.launch { readMessages(connected, DataInputStream(connected.inputStream.buffered())) }
        }
    }

    private suspend fun readMessages(connection: LocalSocket, stream: DataInputStream) {
        try {
            while (true) {
                val size = stream.readInt()
                if (size !in 1..MaxMessageBytes) throw IOException("invalid_message_size")
                val bytes = ByteArray(size)
                stream.readFully(bytes)
                val message = json.decodeFromString<BrokerMessage>(bytes.decodeToString())
                if (message.kind == "event") mutableEvents.emit(message.payload)
                else message.id?.let { pending.remove(it)?.complete(message) }
            }
        } catch (_: EOFException) {
            failPending()
        } catch (error: Exception) {
            failPending(error)
        } finally {
            if (socket === connection) closeConnection()
        }
    }

    private fun failPending(cause: Throwable = IOException("broker_disconnected")) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun closeConnection() {
        runCatching { socket?.close() }
        socket = null
        output = null
        // EOF lets the broker finish USB recovery before exiting. Killing it here races cleanup.
        brokerProcess = null
    }

    companion object {
        private const val MaxMessageBytes = 1024 * 1024
        private const val RequestTimeoutMillis = 20_000L
        private const val LogTag = "NetBootRoot"
        private val random = SecureRandom()

        internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}

class BrokerException(val code: String, cause: Throwable? = null) : IOException(code, cause)
