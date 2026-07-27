package io.talkcan.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRsmScoRoutePolicyTest {
    @Test
    fun acceptsAnonymousScoTransportAfterTargetOwnershipProof() {
        assertTrue(
            "generic Bluetooth SCO transport is accepted only after target RSM audio ownership is proven",
            acceptsWorkScoTransport(
                targetRsmAudioOwned = true,
                targetRsmHfpAudioConnected = true,
                transportIsBluetoothSco = true,
            ),
        )
    }

    @Test
    fun rejectsAnonymousScoTransportWhenAnyOwnershipProofOrTransportInputIsMissing() {
        val rejectedInputs = listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, false),
            Triple(false, true, true),
            Triple(true, false, false),
            Triple(true, false, true),
            Triple(true, true, false),
        )

        rejectedInputs.forEach { (targetRsmAudioOwned, targetRsmHfpAudioConnected, transportIsBluetoothSco) ->
            assertFalse(
                "owned=$targetRsmAudioOwned connected=$targetRsmHfpAudioConnected sco=$transportIsBluetoothSco must fail closed",
                acceptsWorkScoTransport(
                    targetRsmAudioOwned = targetRsmAudioOwned,
                    targetRsmHfpAudioConnected = targetRsmHfpAudioConnected,
                    transportIsBluetoothSco = transportIsBluetoothSco,
                ),
            )
        }
    }

    @Test
    fun doesNotClearTargetRsmRouteWhenNoRouteOwnershipOrTransportStateExists() {
        val untouchedRoute = TargetRsmScoRouteState(
            activeClients = 0,
            selectedDeviceId = null,
            targetRsmAudioOwned = false,
            targetRsmHfpAudioConnected = false,
        )

        assertFalse(shouldClearTargetRsmRoute(untouchedRoute))
    }

    @Test
    fun clearsTargetRsmRouteWhenOwnershipTransportClientOrTargetAudioStateExists() {
        val clearRequiredStates = listOf(
            TargetRsmScoRouteState(
                activeClients = 0,
                selectedDeviceId = null,
                targetRsmAudioOwned = true,
                targetRsmHfpAudioConnected = false,
            ) to "target-owned route",
            TargetRsmScoRouteState(
                activeClients = 0,
                selectedDeviceId = 42,
                targetRsmAudioOwned = false,
                targetRsmHfpAudioConnected = false,
            ) to "selected Work SCO transport",
            TargetRsmScoRouteState(
                activeClients = 1,
                selectedDeviceId = null,
                targetRsmAudioOwned = false,
                targetRsmHfpAudioConnected = false,
            ) to "active Work client",
            TargetRsmScoRouteState(
                activeClients = 0,
                selectedDeviceId = null,
                targetRsmAudioOwned = false,
                targetRsmHfpAudioConnected = true,
            ) to "target RSM HFP audio connected",
        )

        clearRequiredStates.forEach { (state, reason) ->
            assertTrue(
                "$reason requires target RSM route clear/release",
                shouldClearTargetRsmRoute(state),
            )
        }
    }
}
