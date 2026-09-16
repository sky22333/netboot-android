package com.sky22333.netboot.root

import kotlinx.serialization.Serializable

@Serializable
data class BrokerRequest(
    val id: Long,
    val operation: String,
    val payload: String = "",
)

@Serializable
data class BrokerMessage(
    val id: Long? = null,
    val kind: String,
    val ok: Boolean = true,
    val payload: String = "",
    val errorCode: String? = null,
)

@Serializable
data class AttachIsoRequest(val isoPath: String, val cdrom: Boolean)

/**
 * Broker-originated runtime event. Mirrors the Go core's event shape so RuntimeRepository can
 * persist both through one path; [code] plus [arguments] are the only localization input.
 */
@Serializable
data class BrokerEvent(
    val timestamp: Long,
    val level: String,
    val source: String,
    val code: String,
    val arguments: Map<String, String>? = null,
)

@Serializable
data class BrokeredNetworkStatus(
    val running: Boolean = false,
    val mode: String? = null,
    val listenIp: String? = null,
    val httpPort: Int = 0,
    val startedAt: Long = 0,
)

/** Payload of [BrokerOperation.Status]; mirrors what `RootBrokerMain` reports. */
@Serializable
data class BrokerStatus(
    val network: BrokeredNetworkStatus = BrokeredNetworkStatus(),
    val usbAttached: Boolean = false,
    val usbHostConnected: Boolean = false,
)

/**
 * Result of the read-only USB probe: whether this app can add a read-only optical drive to the
 * device's live USB gadget, plus a stable code explaining why not when it cannot.
 */
@Serializable
data class UsbCapability(
    val supported: Boolean,
    val reason: String? = null,
)

object BrokerOperation {
    const val Probe = "probe"
    const val StartNetwork = "startNetwork"
    const val StopNetwork = "stopNetwork"
    const val AttachReadOnlyIso = "attachReadOnlyIso"
    const val DetachIso = "detachIso"
    const val Status = "status"
    const val Shutdown = "shutdown"

    val allowed = setOf(Probe, StartNetwork, StopNetwork, AttachReadOnlyIso, DetachIso, Status, Shutdown)
}
