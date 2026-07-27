package io.talkcan.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun logicalRsmReadinessDoesNotDependOnNamedScoTransport() {
        val ready = logicallyReadyConnection()

        assertTrue(ready.readyForMonitor)
    }

    @Test
    fun anyMissingLogicalRsmSignalMakesMonitorUnavailable() {
        val ready = logicallyReadyConnection()
        val unavailableCases = listOf(
            "permissions" to ready.copy(permissions = PermissionState.Missing),
            "bluetooth" to ready.copy(bluetoothEnabled = false),
            "bonded-target" to ready.copy(devicePresence = DevicePresence.Found),
            "spp" to ready.copy(spp = SppState.Disconnected),
            "target-hfp" to ready.copy(headsetAudio = HeadsetAudioState.Unavailable),
        )

        unavailableCases.forEach { (signal, state) ->
            assertFalse(signal, state.readyForMonitor)
        }
    }

    private fun logicallyReadyConnection(): ConnectionState = ConnectionState(
        permissions = PermissionState.Granted,
        bluetoothEnabled = true,
        devicePresence = DevicePresence.Bonded,
        spp = SppState.Connected,
        headsetAudio = HeadsetAudioState.Available,
    )
}
