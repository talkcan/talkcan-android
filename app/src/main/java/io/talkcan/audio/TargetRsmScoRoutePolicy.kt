package io.talkcan.audio

internal data class TargetRsmScoRouteState(
    val activeClients: Int,
    val selectedDeviceId: Int?,
    val targetRsmAudioOwned: Boolean,
    val targetRsmHfpAudioConnected: Boolean,
)

internal fun acceptsWorkScoTransport(
    targetRsmAudioOwned: Boolean,
    targetRsmHfpAudioConnected: Boolean,
    transportIsBluetoothSco: Boolean,
): Boolean = targetRsmAudioOwned && targetRsmHfpAudioConnected && transportIsBluetoothSco

internal fun shouldClearTargetRsmRoute(state: TargetRsmScoRouteState): Boolean =
    state.targetRsmAudioOwned ||
        state.selectedDeviceId != null ||
        state.activeClients > 0 ||
        state.targetRsmHfpAudioConnected
