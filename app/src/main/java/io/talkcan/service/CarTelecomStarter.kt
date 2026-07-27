package io.talkcan.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.ROUTE_LOG_TAG
import io.talkcan.audio.RouteGateResult
import io.talkcan.audio.ScoAudioController
import io.talkcan.audio.routeDebugString
import io.talkcan.model.AppState
import io.talkcan.model.InputMode
import io.talkcan.model.PttSource
import io.talkcan.telecom.TalkcanPhoneAccountRegistrar
import io.talkcan.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pulses voice recognition on one device, then fully relinquishes that HFP
 * audio route before Telecom takes ownership of it.
 */
internal suspend fun <D> primeHfpDeviceForTelecom(
    device: D,
    startVoiceRecognition: (D) -> Boolean,
    isAudioConnected: (D) -> Boolean,
    stopVoiceRecognition: (D) -> Boolean,
    timeoutMs: Long = 1_500L,
    pollMs: Long = 50L,
): Boolean {
    val started = runCatching { startVoiceRecognition(device) }.getOrDefault(false)
    if (!started) return false

    var stopAttempted = false
    return try {
        val connected = withTimeoutOrNull(timeoutMs) {
            while (!runCatching { isAudioConnected(device) }.getOrDefault(false)) {
                delay(pollMs)
            }
            true
        } == true
        if (!connected) return false

        stopAttempted = true
        runCatching { stopVoiceRecognition(device) }
        withTimeoutOrNull(timeoutMs) {
            while (runCatching { isAudioConnected(device) }.getOrDefault(true)) {
                delay(pollMs)
            }
            true
        } == true
    } finally {
        if (!stopAttempted) runCatching { stopVoiceRecognition(device) }
    }
}


/**
 * Encapsulates the car-telecom PTT lifecycle that was previously inlined in
 * [PttForegroundService].
 *
 * Owns [telecomDisconnected] state formerly on the service.
 */
internal class CarTelecomStarter(
    private val context: Context,
    private val serviceScope: CoroutineScope,

    private val sco: ScoAudioController,
    private val audioManager: AudioManager,
    private val headsetProxyProvider: () -> BluetoothHeadset?,
    private val targetRsm: () -> BluetoothDevice?,
    private val inputModeController: InputModeController,
    private val carConfigurationStore: CarHfpConfigurationStore,
    private val telecomRegistrar: TalkcanPhoneAccountRegistrar,
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val isActivePttSession: () -> Boolean,
    private val decidePttDispatch: () -> PttDispatchDecision?,
    private val reserveCaptureAdmission: () -> HostCaptureAdmission,
    private val abandonCaptureAdmission: (HostCaptureLease) -> Boolean,
    private val reservePendingCarPtt: (String, HostCaptureLease) -> Boolean,
    private val cancelPendingCarPtt: (String) -> Unit,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    private data class ResolvedCarHfpEndpoint(
        val proxy: BluetoothHeadset,
        val device: BluetoothDevice,
    )
    /** Tracks whether a telecom disconnect is pending / has completed. */
    var telecomDisconnected = CompletableDeferred<Unit>().apply { complete(Unit) }
        private set

    private var lastDisconnectTime = 0L

    /** Launches the car-PTT coroutine. */
    fun startTelecomCarPtt() {
        serviceScope.launch {
            startTelecomCarPttAfterRouteRelease()
        }
    }

    /** Notify that the telecom connection ended (called from service listener). */
    fun notifyTelecomDisconnected() {
        lastDisconnectTime = android.os.SystemClock.elapsedRealtime()
        if (!telecomDisconnected.isCompleted) telecomDisconnected.complete(Unit)
    }

    /** Reset telecom-disconnected state for a new call. */
    private fun resetTelecomDisconnected() {
        telecomDisconnected = CompletableDeferred()
    }
    @SuppressLint("MissingPermission")
    private suspend fun startTelecomCarPttAfterRouteRelease() {
        val now = android.os.SystemClock.elapsedRealtime()
        val timeSinceLastDisconnect = now - lastDisconnectTime
        if (timeSinceLastDisconnect < 500) {
            val waitMs = 500 - timeSinceLastDisconnect
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_DELAY waitMs=$waitMs")
            delay(waitMs)
        }
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_PTT_START activeSession=${isActivePttSession()} modeBefore=${inputModeController.mode} " +
                "availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("car-ptt-start")
        if (!telecomDisconnected.isCompleted) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=telecom-disconnect-pending")
            return
        }
        if (isActivePttSession()) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=active-session")
            return
        }
        val carEndpoint = resolveConfiguredCarHfpEndpoint() ?: return
        val captureLease = when (val admission = reserveCaptureAdmission()) {
            is HostCaptureAdmission.Granted -> admission.lease
            HostCaptureAdmission.RejectedByPlayback -> {
                Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=playback-active")
                return
            }
            HostCaptureAdmission.Busy -> {
                Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=host-audio-busy")
                return
            }
            HostCaptureAdmission.Closed -> return
        }
        fun abandonAdmission() {
            abandonCaptureAdmission(captureLease)
        }
        val transitioned = inputModeController.autoTransitionFor(PttSource.CarTelecom)
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_AUTO_TRANSITION source=${PttSource.CarTelecom} ok=$transitioned modeAfter=${inputModeController.mode}",
        )
        logAudioRouteSnapshot("car-ptt-after-transition")
        if (!transitioned) {
            Log.d(
                ROUTE_LOG_TAG,
                "CAR_PTT_TRANSITION_FAILED mode=${inputModeController.mode}",
            )
            abandonAdmission()
            playCarErrorBeep()
            return
        }
        publishInputMode()
        val decision = decidePttDispatch()
        if (decision == null) {
            abandonAdmission()
            return
        }
        if (decision is PttDispatchDecision.ErrorBeep) {
            abandonAdmission()
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_ERROR_BEEP reason=dispatch-decision mode=${inputModeController.mode}")
            playCarErrorBeep()
            return
        }

        if (!reservePendingCarPtt(decision.channelId, captureLease)) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=pending-reservation-rejected")
            return
        }
        updateCarMediaState()
        when (val releaseResult = sco.releaseImmediately("car-ptt-start")) {
            is RouteGateResult.Success -> Unit
            is RouteGateResult.Failure,
            is RouteGateResult.Timeout,
            is RouteGateResult.Cancellation -> {
                Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=work-route-release-failed result=$releaseResult")
                cancelPendingCarPtt("work-route-release-failed")
                playCarErrorBeep()
                return
            }
        }
        val car = primeCarHfpForTelecom(carEndpoint)
        if (car == null) {
            Log.d(ROUTE_LOG_TAG, "CAR_PTT_SKIP reason=car-hfp-prime-failed")
            cancelPendingCarPtt("car-hfp-prime-failed")
            playCarErrorBeep()
            return
        }
        telecomRegistrar.register()
        if (!telecomRegistrar.isEnabled()) {
            cancelPendingCarPtt("telecom-account-disabled")
            context.startActivity(telecomRegistrar.setupIntent())
            return
        }

        val telecom = context.getSystemService(android.telecom.TelecomManager::class.java) ?: run {
            cancelPendingCarPtt("telecom-manager-unavailable")
            return
        }
        if (!TelecomCarPttCoordinator.prepareConnection(car)) {
            cancelPendingCarPtt("telecom-connection-prepare-failed")
            playCarErrorBeep()
            return
        }
        val extras = Bundle().apply {
            putParcelable(
                android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                telecomRegistrar.handle,
            )
        }
        runCatching {
            resetTelecomDisconnected()
            telecom.placeCall(telecomRegistrar.callAddress(), extras)
        }.onFailure {
            TelecomCarPttCoordinator.forceAbort()
            playCarErrorBeep()
        }
    }

    fun playCarErrorBeep() {
        Log.d(ROUTE_LOG_TAG, "CAR_ERROR_BEEP mode=${inputModeController.mode}")
        val route = resolvePttAudioRoute(inputModeController.mode)
        serviceScope.launch {
            playRouteErrorBeepIfAcquired(route)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveConfiguredCarHfpEndpoint(): ResolvedCarHfpEndpoint? {
        val configuredCar = carConfigurationStore.configuredCar.value
        if (configuredCar == null) {
            Log.d(
                ROUTE_LOG_TAG,
                "CAR_HFP_RESOLUTION outcome=unconfigured configured=false profileCount=unavailable rsmKnown=false",
            )
            return null
        }
        var profileCount: Int? = null
        val rsm = targetRsm()
        val proxy = headsetProxyProvider()
        val resolution: ConfiguredCarResolution<BluetoothDevice> = when {
            !RequiredPermissions.hasBluetoothConnect(context) -> ConfiguredCarResolution.InspectionFailed(
                CarHfpInspectionFailure.PermissionUnavailable,
            )
            proxy == null -> ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.ProfileUnavailable)
            else -> {
                val devicesResult = runCatching { proxy.connectedDevices }
                val targetAddressResult = runCatching { rsm?.address }
                if (devicesResult.isFailure || targetAddressResult.isFailure) {
                    ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed)
                } else {
                    val devices = devicesResult.getOrThrow()
                    profileCount = devices.size
                    resolveConfiguredCarHfpDevice(
                        configuredCar = configuredCar,
                        inspection = CarHfpProfileInspection.Available(devices),
                        targetRsmAddress = targetAddressResult.getOrThrow(),
                        addressOf = { it.address },
                        isConnected = { device ->
                            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        },
                    )
                }
            }
        }
        val outcome = when (resolution) {
            ConfiguredCarResolution.Unconfigured -> "unconfigured"
            ConfiguredCarResolution.Absent -> "configured-absent"
            ConfiguredCarResolution.Disconnected -> "configured-disconnected"
            ConfiguredCarResolution.TargetRsmConflict -> "target-rsm-conflict"
            is ConfiguredCarResolution.InspectionFailed ->
                "inspection-${resolution.reason.name.lowercase().replace('_', '-')}"
            is ConfiguredCarResolution.Resolved -> "resolved"
        }
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_RESOLUTION outcome=$outcome configured=true " +
                "profileCount=${profileCount ?: "unavailable"} rsmKnown=${rsm != null}",
        )
        return if (resolution is ConfiguredCarResolution.Resolved && proxy != null) {
            ResolvedCarHfpEndpoint(proxy, resolution.device)
        } else {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun primeCarHfpForTelecom(endpoint: ResolvedCarHfpEndpoint): BluetoothDevice? {
        val proxy = endpoint.proxy
        val car = endpoint.device
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_PRIME_BEGIN target='${car.name}' audioBefore=${runCatching { proxy.isAudioConnected(car) }.getOrDefault(false)} " +
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )
        var started = false
        val handedOff = primeHfpDeviceForTelecom(
            device = car,
            startVoiceRecognition = { device ->
                started = runCatching { proxy.startVoiceRecognition(device) }
                    .onFailure {
                        Log.d(
                            ROUTE_LOG_TAG,
                            "CAR_HFP_PRIME_START_THROW target='${device.name}' error=${it.javaClass.simpleName}",
                        )
                    }
                    .getOrDefault(false)
                started
            },
            isAudioConnected = { device -> runCatching { proxy.isAudioConnected(device) }.getOrDefault(false) },
            stopVoiceRecognition = { device ->
                runCatching { proxy.stopVoiceRecognition(device) }
                    .onFailure {
                        Log.d(
                            ROUTE_LOG_TAG,
                            "CAR_HFP_PRIME_STOP_THROW target='${device.name}' error=${it.javaClass.simpleName}",
                        )
                    }
                    .getOrDefault(false)
            },
            timeoutMs = CAR_HFP_PRIME_TIMEOUT_MS,
            pollMs = CAR_HFP_PRIME_POLL_MS,
        )
        Log.d(ROUTE_LOG_TAG, "CAR_HFP_PRIME_START target='${car.name}' returned=$started")
        Log.d(
            ROUTE_LOG_TAG,
            "CAR_HFP_PRIME_END target='${car.name}' handedOff=$handedOff " +
                "audioConnected=${runCatching { proxy.isAudioConnected(car) }.getOrDefault(false)} " +
                "current=${audioManager.communicationDevice.routeDebugString()}",
        )
        return car.takeIf { handedOff }
    }


    companion object {
        private const val CAR_HFP_PRIME_TIMEOUT_MS = 1_500L
        private const val CAR_HFP_PRIME_POLL_MS = 50L
    }
}
