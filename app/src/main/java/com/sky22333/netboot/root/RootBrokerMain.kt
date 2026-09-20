package com.sky22333.netboot.root

import android.net.LocalServerSocket
import com.sky22333.netboot.core.mobilecore.Listener
import com.sky22333.netboot.core.mobilecore.Mobilecore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RootBrokerMain {
    private const val MaxMessageBytes = 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "invalid_arguments" }
        val socketName = args[0]
        val expectedUid = args[1].toInt()
        val managedIsoDirectory = File(args[2])
        val stateFile = File(args[3])
        require(socketName.matches(Regex("netboot_[a-f0-9]{32}"))) { "invalid_socket_name" }
        require(expectedUid >= 10_000) { "invalid_uid" }

        val usb = UsbGadgetController(managedIsoDirectory, stateFile)
        val server = LocalServerSocket(socketName)
        try {
            server.accept().use { client ->
                require(client.peerCredentials.uid == expectedUid) { "peer_uid_mismatch" }
                val input = DataInputStream(client.inputStream.buffered())
                val output = DataOutputStream(client.outputStream.buffered())
                val sendLock = Any()
                fun send(message: BrokerMessage) = synchronized(sendLock) {
                    val bytes = json.encodeToString(message).encodeToByteArray()
                    require(bytes.size <= MaxMessageBytes) { "message_too_large" }
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }

                /** Forward event codes and arguments; localization belongs to the app. */
                fun emitUsbEvent(level: String, code: String, arguments: Map<String, String>?) {
                    val payload = BrokerEvent(
                        timestamp = System.currentTimeMillis(),
                        level = level,
                        source = "usb",
                        code = code,
                        arguments = arguments,
                    )
                    runCatching { send(BrokerMessage(kind = "event", payload = json.encodeToString(payload))) }
                }

                fun reportRestore(result: RestoreResult) {
                    if (!result.hadState) return
                    if (result.complete) {
                        emitUsbEvent("info", "usb_restored", null)
                    } else {
                        emitUsbEvent("error", "usb_restore_failed", mapOf("steps" to result.failures.joinToString("; ")))
                    }
                }

                val listener = object : Listener {
                    override fun onEvent(event: String) {
                        runCatching { send(BrokerMessage(kind = "event", payload = event)) }
                    }
                }

                // Restore any previous session before accepting requests.
                runCatching { usb.restore() }.onSuccess(::reportRestore)

                try {
                    while (true) {
                        val size = try {
                            input.readInt()
                        } catch (_: EOFException) {
                            break
                        }
                        require(size in 1..MaxMessageBytes) { "invalid_message_size" }
                        val bytes = ByteArray(size)
                        input.readFully(bytes)
                        val request = json.decodeFromString<BrokerRequest>(bytes.decodeToString())
                        require(request.operation in BrokerOperation.allowed) { "operation_not_allowed" }
                        val response = runCatching {
                            handle(request, usb, listener, ::reportRestore)
                        }.fold(
                            onSuccess = { BrokerMessage(request.id, "response", payload = it) },
                            onFailure = {
                                BrokerMessage(
                                    id = request.id,
                                    kind = "response",
                                    ok = false,
                                    errorCode = (it as? UsbException)?.code ?: it.message ?: "broker_failure",
                                )
                            },
                        )
                        send(response)
                        if (request.operation == BrokerOperation.Shutdown) break
                    }
                } finally {
                    // Restore USB even if stopping the Go core fails.
                    runCatching { Mobilecore.stop() }
                    runCatching { usb.restore() }.onSuccess(::reportRestore)
                }
            }
        } finally {
            server.close()
        }
    }

    private fun handle(
        request: BrokerRequest,
        usb: UsbGadgetController,
        listener: Listener,
        reportRestore: (RestoreResult) -> Unit,
    ): String = when (request.operation) {
        BrokerOperation.Probe -> json.encodeToString(usb.probe())
        BrokerOperation.StartNetwork -> {
            Mobilecore.validateConfig(request.payload)
            Mobilecore.start(request.payload, listener)
            Mobilecore.statusJSON()
        }
        BrokerOperation.StopNetwork -> {
            Mobilecore.stop()
            Mobilecore.statusJSON()
        }
        BrokerOperation.AttachReadOnlyIso -> {
            val input = json.decodeFromString<AttachIsoRequest>(request.payload)
            usb.attach(input.isoPath, input.cdrom)
            "{\"attached\":true}"
        }
        BrokerOperation.DetachIso -> {
            val result = usb.restore()
            reportRestore(result)
            if (!result.complete) throw UsbException("usb_restore_failed")
            "{\"attached\":false}"
        }
        BrokerOperation.Status -> json.encodeToString(
            BrokerStatus(
                network = json.decodeFromString<BrokeredNetworkStatus>(Mobilecore.statusJSON()),
                usbAttached = usb.isAttached(),
                usbHostConnected = usb.isHostConnected(),
            ),
        )
        BrokerOperation.Shutdown -> {
            Mobilecore.stop()
            val result = usb.restore()
            reportRestore(result)
            if (!result.complete) throw UsbException("usb_restore_failed:${result.failures.joinToString()}")
            "{}"
        }
        else -> error("operation_not_allowed")
    }
}
