package io.talkcan.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.model.ScoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ScoAudioController(
    private val scope: CoroutineScope,
    private val audioManager: AudioManager,
    private val rsmHfpConnected: () -> Boolean = { false },
    private val targetRsmName: () -> String? = { null },
    private val startTargetRsmHfpAudio: () -> Boolean = { false },
    private val stopTargetRsmHfpAudio: () -> Boolean = { false },
    private val isTargetRsmHfpAudioConnected: () -> Boolean = { false },
) : ScoRoute {
    private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
    override val state: StateFlow<ScoState> = _state.asStateFlow()
    override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Rsm
    private var _coldStart: Boolean = false
    override val coldStart: Boolean get() = _coldStart

    private val mutex = Mutex()
    private var activeClients = 0
    private var keepWarmJob: Job? = null
    private var selectedDeviceId: Int? = null
    private var targetRsmAudioOwned = false
    private var lastFailure: String? = null

    @SuppressLint("MissingPermission")
    override fun hasAvailableScoDevice(): Boolean {
        val available = rsmHfpConnected()
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_AVAILABLE result=$available target='${targetRsmName().orEmpty()}' " +
                "rsmHfpConnected=$available current=${audioManager.communicationDevice.routeDebugString()} " +
                "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",
        )
        return available
    }

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): Boolean = mutex.withLock {
        keepWarmJob?.cancel()
        keepWarmJob = null

        Log.d(
            ROUTE_LOG_TAG,
            "SCO_ACQUIRE_BEGIN activeClients=$activeClients state=${_state.value} selectedId=$selectedDeviceId " +
                "target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned lastFailure=$lastFailure " +
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )

        if (isActive()) {
            activeClients++
            _state.value = ScoState.Active
            _coldStart = false
            lastFailure = null
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_ACQUIRE_END reused=true active=true activeClients=$activeClients selectedId=$selectedDeviceId " +
                    "target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
            return true
        }

        _coldStart = true
        _state.value = ScoState.Starting
        lastFailure = null

        if (!rsmHfpConnected()) {
            return failAcquisition(
                reason = "target-rsm-hfp-disconnected",
                message = "Target RSM HFP not connected",
            )
        }

        val startReturned = runCatching { startTargetRsmHfpAudio() }
            .onFailure {
                Log.d(
                    ROUTE_LOG_TAG,
                    "RSM_HFP_START_THROW target='${targetRsmName().orEmpty()}' error=${it.javaClass.simpleName}",
                )
            }
            .getOrDefault(false)
        Log.d(
            ROUTE_LOG_TAG,
            "RSM_HFP_START target='${targetRsmName().orEmpty()}' returned=$startReturned " +
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )
        if (!startReturned) {
            return failAcquisition(
                reason = "target-rsm-hfp-start-failed",
                message = "Target RSM HFP audio start failed",
                stopTargetAudio = true,
            )
        }

        val owned = withTimeoutOrNull(TARGET_HFP_AUDIO_TIMEOUT_MS) {
            var attempt = 0
            while (!isTargetRsmHfpAudioConnected()) {
                Log.d(
                    ROUTE_LOG_TAG,
                    "RSM_HFP_POLL attempt=$attempt target='${targetRsmName().orEmpty()}' audioConnected=false " +
                        "audioMode=${audioManager.mode.audioModeDebugString()} " +
                        "current=${audioManager.communicationDevice.routeDebugString()}",
                )
                attempt++
                delay(TARGET_HFP_AUDIO_POLL_MS)
            }
            Log.d(
                ROUTE_LOG_TAG,
                "RSM_HFP_AUDIO_CONNECTED target='${targetRsmName().orEmpty()}' true " +
                    "audioMode=${audioManager.mode.audioModeDebugString()} " +
                    "current=${audioManager.communicationDevice.routeDebugString()}",
            )
            true
        } == true
        if (!owned) {
            return failAcquisition(
                reason = "target-rsm-hfp-audio-timeout",
                message = "Timed out waiting for target RSM HFP audio",
                stopTargetAudio = true,
            )
        }
        targetRsmAudioOwned = true

        val device = findOwnedScoTransport()
        Log.d(ROUTE_LOG_TAG, "SCO_ACQUIRE_CANDIDATE owner=Rsm device=${device.routeDebugString()}")
        if (device == null) {
            return failAcquisition(
                reason = "transport-unavailable",
                message = "Bluetooth SCO transport not available",
                stopTargetAudio = true,
            )
        }

        activeClients++
        selectedDeviceId = device.id
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val accepted = audioManager.setCommunicationDevice(device)
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_SET_COMM accepted=$accepted owner=Rsm requested=${device.routeDebugString()} " +
                "current=${audioManager.communicationDevice.routeDebugString()} selectedId=$selectedDeviceId",
        )
        if (!accepted) {
            return failAcquisition(
                reason = "android-rejected",
                message = "Android rejected Bluetooth SCO route",
                stopTargetAudio = true,
            )
        }

        val active = withTimeoutOrNull(5_000) {
            while (!isActive()) delay(50)
            true
        } == true

        if (!active) {
            return failAcquisition(
                reason = "transport-timeout",
                message = "Timed out waiting for SCO route",
                stopTargetAudio = true,
            )
        }

        _state.value = ScoState.Active
        lastFailure = null
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_ACQUIRE_END reused=false active=true activeClients=$activeClients selectedId=$selectedDeviceId " +
                "target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned " +
                "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
        )
        return true
    }

    @SuppressLint("MissingPermission")
    fun selectedCommunicationDevice(): AudioDeviceInfo? {
        val selectedId = selectedDeviceId ?: return null
        val device = audioManager.communicationDevice ?: return null
        return device.takeIf {
            it.id == selectedId &&
                acceptsWorkScoTransport(
                    targetRsmAudioOwned = targetRsmAudioOwned,
                    targetRsmHfpAudioConnected = isTargetRsmHfpAudioConnected(),
                    transportIsBluetoothSco = it.isBluetoothScoEndpoint(),
                )
        }
    }

    override fun isActive(): Boolean = selectedCommunicationDevice() != null

    @SuppressLint("MissingPermission")
    private fun communicationDeviceReleasedFromTargetRsm(selectedIdBeforeRelease: Int?): Boolean {
        val current = audioManager.communicationDevice ?: return true
        val stillSelectedWorkSco = selectedIdBeforeRelease != null &&
            current.id == selectedIdBeforeRelease &&
            current.isBluetoothScoEndpoint()
        return !stillSelectedWorkSco && !current.isTargetRsmScoEndpoint()
    }

    override fun release() {
        scope.launch {
            mutex.withLock {
                Log.d(
                    ROUTE_LOG_TAG,
                    "SCO_RELEASE_REQUEST activeClients=$activeClients selectedId=$selectedDeviceId " +
                        "target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned " +
                        "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
                )
                if (activeClients > 0) {
                    activeClients--
                }

                if (activeClients == 0 && keepWarmJob == null && targetRsmAudioOwned) {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "SCO_RELEASE_WARM_START warmMs=$SCO_WARMUP_MS selectedId=$selectedDeviceId " +
                            "target='${targetRsmName().orEmpty()}' " +
                            "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
                    )
                    keepWarmJob = scope.launch {
                        delay(SCO_WARMUP_MS)
                        mutex.withLock {
                            if (activeClients == 0) {
                                clearOwnedRouteLocked("warm-expired")
                            } else {
                                Log.d(
                                    ROUTE_LOG_TAG,
                                    "SCO_RELEASE_WARM_SKIP activeClients=$activeClients selectedId=$selectedDeviceId " +
                                        "target='${targetRsmName().orEmpty()}' " +
                                        "current=${audioManager.communicationDevice.routeDebugString()}",
                                )
                            }
                        }
                    }
                } else {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "SCO_RELEASE_RETAINED activeClients=$activeClients keepWarm=${keepWarmJob != null} " +
                            "selectedId=$selectedDeviceId target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned " +
                            "current=${audioManager.communicationDevice.routeDebugString()}",
                    )
                }
            }
        }
    }

    suspend fun releaseImmediately(reason: String = "immediate"): RouteGateResult {
        val selectedIdBeforeRelease = mutex.withLock {
            val selectedId = selectedDeviceId
            clearOwnedRouteLocked(reason)
            selectedId
        }
        val released = withTimeoutOrNull(TARGET_HFP_AUDIO_RELEASE_TIMEOUT_MS) {
            while (
                isTargetRsmHfpAudioConnected() ||
                !communicationDeviceReleasedFromTargetRsm(selectedIdBeforeRelease)
            ) {
                delay(TARGET_HFP_AUDIO_POLL_MS)
            }
            true
        } == true
        val facts = listOf(
            "targetHfpAudioConnected=${runCatching { isTargetRsmHfpAudioConnected() }.getOrDefault(false)}",
            "communicationDevice=${audioManager.communicationDevice.routeDebugString()}",
            "selectedIdBeforeRelease=$selectedIdBeforeRelease",
        )
        Log.d(
            ROUTE_LOG_TAG,
            "RSM_HFP_STOP_WAIT target='${targetRsmName().orEmpty()}' released=$released reason=$reason " +
                facts.joinToString(separator = " "),
        )
        return if (released) {
            RouteGateResult.Success(reason, facts)
        } else {
            RouteGateResult.Timeout("Timed out waiting for target RSM route release", facts)
        }
    }

    fun requestImmediateRelease(reason: String) {
        scope.launch {
            releaseImmediately(reason)
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearOwnedRouteLocked(reason: String) {
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_RELEASE_IMMEDIATE_BEGIN reason=$reason activeClients=$activeClients selectedId=$selectedDeviceId " +
                "target='${targetRsmName().orEmpty()}' owned=$targetRsmAudioOwned " +
                "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
        )
        val ownsOrTouchesTargetRsm = shouldClearTargetRsmRoute(
            TargetRsmScoRouteState(
                activeClients = activeClients,
                selectedDeviceId = selectedDeviceId,
                targetRsmAudioOwned = targetRsmAudioOwned,
                targetRsmHfpAudioConnected = isTargetRsmHfpAudioConnected(),
            ),
        )
        if (!ownsOrTouchesTargetRsm) {
            keepWarmJob?.cancel()
            keepWarmJob = null
            _state.value = ScoState.Inactive
            Log.d(
                ROUTE_LOG_TAG,
                "SCO_RELEASE_IMMEDIATE_SKIP reason=$reason target='${targetRsmName().orEmpty()}' " +
                    "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
            )
            return
        }
        activeClients = 0
        keepWarmJob?.cancel()
        keepWarmJob = null
        _state.value = ScoState.Closing
        val stopReturned = if (targetRsmAudioOwned || isTargetRsmHfpAudioConnected()) {
            runCatching { stopTargetRsmHfpAudio() }
                .onFailure {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "RSM_HFP_STOP_THROW target='${targetRsmName().orEmpty()}' error=${it.javaClass.simpleName}",
                    )
                }
                .getOrDefault(false)
        } else {
            false
        }
        Log.d(
            ROUTE_LOG_TAG,
            "RSM_HFP_STOP target='${targetRsmName().orEmpty()}' returned=$stopReturned reason=$reason " +
                "audioAfter=${runCatching { isTargetRsmHfpAudioConnected() }.getOrDefault(false)}",
        )
        targetRsmAudioOwned = false
        selectedDeviceId = null
        _coldStart = false
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        _state.value = ScoState.Inactive
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_RELEASE_IMMEDIATE_DONE reason=$reason current=${audioManager.communicationDevice.routeDebugString()} " +
                "state=${_state.value}",
        )
    }

    private fun failAcquisition(
        reason: String,
        message: String,
        stopTargetAudio: Boolean = false,
    ): Boolean {
        lastFailure = message
        if (activeClients > 0) activeClients--
        _coldStart = false
        if (stopTargetAudio || targetRsmAudioOwned) {
            runCatching { stopTargetRsmHfpAudio() }
                .onFailure {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "RSM_HFP_STOP_THROW target='${targetRsmName().orEmpty()}' error=${it.javaClass.simpleName}",
                    )
                }
                .onSuccess {
                    Log.d(
                        ROUTE_LOG_TAG,
                        "RSM_HFP_STOP target='${targetRsmName().orEmpty()}' returned=$it reason=acquire-failed-$reason " +
                            "audioAfter=${runCatching { isTargetRsmHfpAudioConnected() }.getOrDefault(false)}",
                    )
                }
        }
        targetRsmAudioOwned = false
        selectedDeviceId = null
        if (activeClients == 0) {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        _state.value = ScoState.Failed(message)
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_ACQUIRE_FAIL reason=$reason target='${targetRsmName().orEmpty()}' activeClients=$activeClients " +
                "current=${audioManager.communicationDevice.routeDebugString()} state=${_state.value}",
        )
        return false
    }

    @SuppressLint("MissingPermission")
    private fun findOwnedScoTransport(): AudioDeviceInfo? {
        val current = audioManager.communicationDevice
        val devices = audioManager.availableCommunicationDevices
        Log.d(
            ROUTE_LOG_TAG,
            "SCO_SCAN owner=Rsm target='${targetRsmName().orEmpty()}' rsmHfpConnected=${rsmHfpConnected()} " +
                "targetAudioConnected=${runCatching { isTargetRsmHfpAudioConnected() }.getOrDefault(false)} " +
                "current=${current.routeDebugString()} devices=${devices.routeDebugString()}",
        )
        current?.takeIf {
            acceptsWorkScoTransport(
                targetRsmAudioOwned = targetRsmAudioOwned,
                targetRsmHfpAudioConnected = isTargetRsmHfpAudioConnected(),
                transportIsBluetoothSco = it.isBluetoothScoEndpoint(),
            )
        }?.let {
            Log.d(ROUTE_LOG_TAG, "SCO_TRANSPORT_SELECT owner=Rsm reason=current device=${it.routeDebugString()}")
            return it
        }
        devices.firstOrNull {
            acceptsWorkScoTransport(
                targetRsmAudioOwned = targetRsmAudioOwned,
                targetRsmHfpAudioConnected = isTargetRsmHfpAudioConnected(),
                transportIsBluetoothSco = it.isBluetoothScoEndpoint(),
            )
        }?.let {
            Log.d(ROUTE_LOG_TAG, "SCO_TRANSPORT_SELECT owner=Rsm reason=available device=${it.routeDebugString()}")
            return it
        }
        Log.d(ROUTE_LOG_TAG, "SCO_TRANSPORT_SELECT owner=Rsm reason=none")
        return null
    }

    companion object {
        private const val SCO_WARMUP_MS = 30_000L
        private const val TARGET_HFP_AUDIO_TIMEOUT_MS = 5_000L
        private const val TARGET_HFP_AUDIO_POLL_MS = 50L
        private const val TARGET_HFP_AUDIO_RELEASE_TIMEOUT_MS = 1_500L
    }
}
