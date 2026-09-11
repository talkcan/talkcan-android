package io.talkcan.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.talkcan.audio.RouteGateResult
import io.talkcan.audio.ScoAudioController
import io.talkcan.live.AndroidLiveAudioDevice
import io.talkcan.live.LiveAudioDevice
import io.talkcan.model.InputMode
import io.talkcan.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Captures one mode and exact headset identity for the complete conversation. */
@SuppressLint("MissingPermission")
internal fun createLiveAudioDevice(
    context: Context,
    scope: CoroutineScope,
    audioManager: AudioManager,
    coordinator: HostAudioCoordinator,
    mode: InputMode,
    workSco: ScoAudioController,
    carDevice: BluetoothDevice?,
    headset: BluetoothHeadset?,
): LiveAudioDevice {
    val carSco = if (mode == InputMode.OnTheRoad && carDevice != null && headset != null) ScoAudioController(
        scope = scope,
        audioManager = audioManager,
        rsmHfpConnected = { headset.getConnectionState(carDevice) == BluetoothProfile.STATE_CONNECTED },
        targetRsmName = { carDevice.name },
        startTargetRsmHfpAudio = { headset.startVoiceRecognition(carDevice) },
        stopTargetRsmHfpAudio = { headset.stopVoiceRecognition(carDevice) },
        isTargetRsmHfpAudioConnected = { headset.isAudioConnected(carDevice) },
    ) else null
    var phoneDevice: AudioDeviceInfo? = null
    var previousMode = AudioManager.MODE_NORMAL
    var phoneModeOwned = false
    suspend fun release() {
        when (mode) {
            InputMode.Work -> workSco.releaseImmediately("live-conversation-ended")
            InputMode.OnTheRoad -> carSco?.releaseImmediately("live-car-conversation-ended")
            InputMode.OnAPinch -> {
                if (phoneDevice?.id == audioManager.communicationDevice?.id) {
                    audioManager.clearCommunicationDevice()
                }
                if (phoneModeOwned && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = previousMode
                }
                phoneModeOwned = false
                phoneDevice = null
            }
        }
    }
    return AndroidLiveAudioDevice(
        context = context,
        audioManager = audioManager,
        coordinator = coordinator,
        communicationDevice = {
            when (mode) {
                InputMode.Work -> workSco.selectedCommunicationDevice()
                InputMode.OnTheRoad -> carSco?.selectedCommunicationDevice()
                InputMode.OnAPinch -> phoneDevice?.takeIf { it.id == audioManager.communicationDevice?.id }
            }
        },
        acquireRoute = {
            var acquired = false
            try {
                acquired = withTimeoutOrNull(5_000L) {
                    if (TelecomCarPttCoordinator.isCaptureRouteReady()) return@withTimeoutOrNull false
                    when (mode) {
                        InputMode.Work -> workSco.acquire()
                        InputMode.OnTheRoad -> {
                            if (carSco == null) false else {
                                val released = workSco.releaseImmediately("live-before-car")
                                released is RouteGateResult.Success && carSco.acquire()
                            }
                        }
                        InputMode.OnAPinch -> {
                            if (workSco.releaseImmediately("live-before-phone") !is RouteGateResult.Success) {
                                false
                            } else {
                                val device = audioManager.availableCommunicationDevices.firstOrNull {
                                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                                }
                                if (device == null) false else {
                                    previousMode = audioManager.mode
                                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                    phoneModeOwned = true
                                    phoneDevice = device
                                    audioManager.setCommunicationDevice(device) && audioManager.communicationDevice?.id == device.id
                                }
                            }
                        }
                    }
                } == true
                acquired
            } finally {
                if (!acquired) withContext(NonCancellable) { release() }
            }
        },
        releaseRoute = { release() },
    )
}
