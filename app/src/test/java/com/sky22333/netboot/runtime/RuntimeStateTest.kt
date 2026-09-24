package com.sky22333.netboot.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeStateTest {
    @Test
    fun disconnectStopsNetworkWithoutInventingUsbRecovery() {
        val state = RuntimeState(networkRunning = true).disconnected()
        assertFalse(state.networkRunning)
        assertFalse(state.usbRecoveryRequired)
        assertEquals("broker_disconnected", state.errorCode)
    }

    @Test
    fun disconnectPreservesUsbIdentityUntilRecovery() {
        val state = RuntimeState(networkRunning = true, usbAttached = true, usbHostConnected = true, activeIsoId = "iso").disconnected()
        assertFalse(state.usbAttached)
        assertFalse(state.usbHostConnected)
        assertTrue(state.usbRecoveryRequired)
        assertEquals("iso", state.activeIsoId)
    }

    @Test
    fun idleProbeDisconnectDoesNotInterruptPreparationOrReportFailure() {
        val state = RuntimeState(usbPreparing = true, activeIsoId = "iso", preparedBytes = 512)
        assertEquals(state, state.disconnected())
        val recovery = RuntimeState(usbRecoveryRequired = true, errorCode = "usb_restore_failed")
        assertEquals(recovery, recovery.disconnected())
    }
}
