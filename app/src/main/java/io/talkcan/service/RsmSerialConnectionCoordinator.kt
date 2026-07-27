package io.talkcan.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import io.talkcan.model.DevicePresence
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Typed events emitted by [RsmSerialConnectionCoordinator] for service-edge
 * integration. The coordinator never touches [io.talkcan.model.AppState],
 * the Android [android.app.Service], or the foreground/readiness-loop owners
 * directly; it reports every side effect through this sealed hierarchy so the
 * service can preserve the baseline event and cancellation order.
 */
internal sealed interface SerialCoordinatorEvent {
    /** Cancel any active RSM-sourced PTT session with the given caller classification. */
    data class CancelPtt(val caller: PttCancellationCaller, val reason: String) : SerialCoordinatorEvent

    /** Release the TTS controller (cancel and release). */
    data object ReleaseTts : SerialCoordinatorEvent

    /** An SPP state transition observed from the serial session. */
    data class SppStateChanged(val state: SppState, val error: String?) : SerialCoordinatorEvent

    /** A reconnect decision cleared the target device presence. */
    data class DevicePresenceChanged(val presence: DevicePresence) : SerialCoordinatorEvent

    /** Request the service to enter the foreground (start serial session). */
    data object RequestEnsureForeground : SerialCoordinatorEvent

    /** Request the service to stop foreground and stop itself. */
    data object RequestStopForegroundAndSelf : SerialCoordinatorEvent

    /** Request the service to stop the readiness refresh loop. */
    data object RequestStopReadinessRefreshLoop : SerialCoordinatorEvent

    /** Request the service to reevaluate the deferred serial-disconnect shutdown. */
    data object RequestReevaluateSerialDisconnectShutdown : SerialCoordinatorEvent

    /** Request the service to perform a readiness refresh. */
    data object RequestReadinessRefresh : SerialCoordinatorEvent

    /** The deferred serial-disconnect pending flag changed. */
    data class SerialDisconnectPendingChanged(val pending: Boolean) : SerialCoordinatorEvent

    /** A raw button event was received from the RSM serial stream. */
    data class RawButtonReceived(val event: RawButtonEvent) : SerialCoordinatorEvent

    /** Diagnostic log for an RSM SPP session termination. */
    data class LogTermination(
        val automatic: Boolean,
        val everConnected: Boolean,
        val monitoringRequested: Boolean,
        val disposition: RsmReconnectDisposition,
    ) : SerialCoordinatorEvent
}

/**
 * Narrow seam over the bonded-target lookup. The production adapter wraps
 * [io.talkcan.bluetooth.DeviceScanner]; recording fakes return
 * deterministic devices.
 */
internal fun interface RsmSerialScanner {
    fun bondedTarget(): BluetoothDevice?
}

/**
 * Narrow seam over the SPP client. The production adapter wraps
 * [io.talkcan.bluetooth.SppClient]; recording fakes emit
 * deterministic state transitions and button-event flows.
 */
internal interface RsmSppConnection {
    val state: StateFlow<SppState>
    fun events(device: BluetoothDevice): Flow<RawButtonEvent>
    fun disconnect()
}

/** Factory seam for creating an [RsmSppConnection] from a Bluetooth adapter. */
internal fun interface RsmSppConnectionFactory {
    fun create(adapter: BluetoothAdapter): RsmSppConnection
}

/** Handle returned by [RsmReconnectScheduler] for cancelling a scheduled reconnect. */
internal fun interface RsmReconnectHandle {
    fun cancel()
}

/**
 * Schedules a cancellable delayed reconnect attempt. The [schedule] action runs
 * after [delayMs] unless the returned handle is cancelled first. Deterministic
 * fakes control elapsed time and invocation without touching real dispatchers.
 */
internal fun interface RsmReconnectScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): RsmReconnectHandle
}

/**
 * Exclusive owner of the RSM serial connection and automatic-reconnect lifecycle.
 *
 * Owns [targetDevice], the SPP connection, [serialJob], [sppStateJob], the
 * reconnect handle, and the [ReconnectPolicy] as one ownership cut. The
 * coordinator accepts narrow platform seams (adapter, scanner, SPP connection
 * factory, elapsed time, cancellable delay scheduling, prerequisite
 * projection) and emits typed [SerialCoordinatorEvent]s to the service instead
 * of mutating AppState or calling stopSelf directly.
 *
 * Android Bluetooth HEADSET-profile callbacks and permission requests remain at
 * the service edge; the coordinator receives resolved observations through the
 * injected seams. A generation counter guards late SPP state events and
 * serial-session-end callbacks so they cannot revive retired connection state.
 */
internal class RsmSerialConnectionCoordinator(
    private val scope: CoroutineScope,
    private val adapterProvider: () -> BluetoothAdapter?,
    private val scanner: RsmSerialScanner,
    private val sppFactory: RsmSppConnectionFactory,
    private val elapsedRealtime: () -> Long,
    private val reconnectScheduler: RsmReconnectScheduler,
    private val prerequisitesProvider: (BluetoothDevice?) -> ReconnectPrerequisites,
    private val onEvent: (SerialCoordinatorEvent) -> Unit,
) {
    private var targetDevice: BluetoothDevice? = null
    private var sppConnection: RsmSppConnection? = null
    private var serialJob: Job? = null
    private var sppStateJob: Job? = null
    private var reconnectHandle: RsmReconnectHandle? = null
    private val reconnectPolicy = ReconnectPolicy()
    private var sessionGeneration = 0L

    /** Whether automatic reconnect monitoring has been requested. */
    val monitoringRequested: Boolean get() = reconnectPolicy.monitoringRequested

    /** Current resolved target device, or null. */
    fun targetDevice(): BluetoothDevice? = targetDevice

    /**
     * Sets the resolved target device. Used by the service edge after scan/pair
     * and readiness-probe bonded-device resolution.
     */
    fun setTargetDevice(device: BluetoothDevice?) {
        targetDevice = device
    }

    /**
     * Requests a manual serial connection. Preserves the baseline guard:
     * if a serial session is already active, this is a no-op. Otherwise clears
     * the deferred serial-disconnect flag, starts monitoring, and cancels any
     * pending reconnect. The service launches a readiness refresh after this
     * returns; the coordinator does not emit [SerialCoordinatorEvent.RequestReadinessRefresh]
     * here to match the baseline async ordering.
     */
    fun connectSerial() {
        if (serialJob?.isActive == true) return
        onEvent(SerialCoordinatorEvent.SerialDisconnectPendingChanged(pending = false))
        reconnectPolicy.startMonitoring()
        reconnectHandle?.cancel()
        reconnectHandle = null
    }

    /**
     * Performs an explicit serial disconnect in the baseline order:
     * cancel RSM PTT, mark disconnect pending, stop the readiness refresh loop,
     * cancel serial/SPP state/reconnect jobs, disconnect and clear the SPP
     * connection, publish disconnected SPP state, release TTS, reevaluate the
     * deferred shutdown, then request a readiness refresh.
     */
    fun disconnectSerial() {
        onEvent(
            SerialCoordinatorEvent.CancelPtt(
                caller = PttCancellationCaller.ExplicitSerialDisconnect,
                reason = "Explicit RSM serial disconnect",
            ),
        )
        reconnectPolicy.clearMonitoring()
        onEvent(SerialCoordinatorEvent.SerialDisconnectPendingChanged(pending = true))
        reconnectHandle?.cancel()
        reconnectHandle = null
        onEvent(SerialCoordinatorEvent.RequestStopReadinessRefreshLoop)
        retireSession()
        onEvent(SerialCoordinatorEvent.SppStateChanged(state = SppState.Disconnected, error = null))
        onEvent(SerialCoordinatorEvent.ReleaseTts)
        onEvent(SerialCoordinatorEvent.RequestReevaluateSerialDisconnectShutdown)
        onEvent(SerialCoordinatorEvent.RequestReadinessRefresh)
    }

    /**
     * Notifies the coordinator that the service completed a readiness refresh.
     * Preserves the baseline [maybeScheduleAutomaticSerialConnection] guard:
     * only schedules when monitoring is requested, no attempt is in progress,
     * no serial or reconnect job is active, and all prerequisites are met.
     */
    fun onReadinessRefreshed() {
        maybeScheduleAutomaticSerialConnection()
    }

    /**
     * Shuts down all serial/reconnect ownership. Preserves the baseline
     * onDestroy order: clear monitoring, cancel reconnect, cancel serial and
     * SPP state jobs, disconnect the SPP connection. Does not emit readiness or
     * foreground events; the service owns the top-level teardown sequence.
     */
    fun shutdown() {
        reconnectPolicy.clearMonitoring()
        reconnectHandle?.cancel()
        reconnectHandle = null
        retireSession()
    }

    private fun startSerialSession(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        automatic: Boolean,
    ): Boolean {
        if (serialJob?.isActive == true) return false

        onEvent(SerialCoordinatorEvent.RequestEnsureForeground)
        sppStateJob?.cancel()
        sppConnection?.disconnect()

        val generation = ++sessionGeneration
        var connected = false
        val connection = sppFactory.create(adapter)
        sppConnection = connection

        sppStateJob = scope.launch {
            connection.state.collect { state ->
                if (generation != sessionGeneration) return@collect
                if (state == SppState.Connected) {
                    connected = true
                    if (automatic) {
                        reconnectPolicy.finishAttempt(
                            success = true,
                            nowMillis = elapsedRealtime(),
                            prerequisites = prerequisitesProvider(device),
                        )
                    }
                }
                onEvent(
                    SerialCoordinatorEvent.SppStateChanged(
                        state = state,
                        error = if (state == SppState.Failed) "Serial connection failed" else null,
                    ),
                )
                onEvent(SerialCoordinatorEvent.RequestReadinessRefresh)
            }
        }

        serialJob = scope.launch {
            connection.events(device).collect { event ->
                if (generation != sessionGeneration) return@collect
                onEvent(SerialCoordinatorEvent.RawButtonReceived(event = event))
            }
            if (generation == sessionGeneration) {
                handleSerialSessionEnded(automatic = automatic, connected = connected)
            }
        }
        return true
    }

    private fun handleSerialSessionEnded(automatic: Boolean, connected: Boolean) {
        onEvent(
            SerialCoordinatorEvent.CancelPtt(
                caller = PttCancellationCaller.RsmSerialSessionEnded,
                reason = "RSM serial session ended",
            ),
        )
        onEvent(SerialCoordinatorEvent.ReleaseTts)
        onEvent(SerialCoordinatorEvent.RequestReadinessRefresh)

        if (!reconnectPolicy.monitoringRequested) {
            onEvent(
                SerialCoordinatorEvent.LogTermination(
                    automatic = automatic,
                    everConnected = connected,
                    monitoringRequested = false,
                    disposition = RsmReconnectDisposition.Stopped,
                ),
            )
            onEvent(SerialCoordinatorEvent.RequestStopForegroundAndSelf)
            return
        }

        val now = elapsedRealtime()
        val prerequisites = prerequisitesProvider(null)
        val decision = if (automatic && !connected) {
            reconnectPolicy.finishAttempt(success = false, nowMillis = now, prerequisites = prerequisites)
        } else {
            reconnectPolicy.cancelAttempt()
            reconnectPolicy.scheduleAfterUnexpectedLoss(nowMillis = now, prerequisites = prerequisites)
        }
        onEvent(
            SerialCoordinatorEvent.LogTermination(
                automatic = automatic,
                everConnected = connected,
                monitoringRequested = reconnectPolicy.monitoringRequested,
                disposition = decision.toRsmReconnectDisposition(),
            ),
        )
        handleReconnectDecision(decision)
    }

    private fun handleReconnectDecision(decision: ReconnectDecision) {
        when (decision) {
            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            ReconnectDecision.StartAttempt,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> {
                if (decision.reason == ReconnectBlockReason.TargetUnavailable) {
                    onEvent(SerialCoordinatorEvent.DevicePresenceChanged(presence = DevicePresence.NotFound))
                }
                onEvent(SerialCoordinatorEvent.RequestReadinessRefresh)
                if (!shouldRetainMonitoringService(decision.reason)) {
                    onEvent(SerialCoordinatorEvent.RequestStopForegroundAndSelf)
                }
            }
        }
    }

    private fun maybeScheduleAutomaticSerialConnection() {
        if (!reconnectPolicy.monitoringRequested ||
            reconnectPolicy.attemptInProgress ||
            serialJob?.isActive == true ||
            reconnectHandle != null
        ) {
            return
        }
        val prerequisites = prerequisitesProvider(null)
        if (!prerequisites.permissionsGranted ||
            !prerequisites.bluetoothEnabled ||
            !prerequisites.bondedTargetAvailable
        ) {
            return
        }
        handleReconnectDecision(
            reconnectPolicy.scheduleInitialConnection(
                nowMillis = elapsedRealtime(),
                prerequisites = prerequisites,
            ),
        )
    }

    private fun scheduleReconnectAt(attemptAtMillis: Long) {
        reconnectHandle?.cancel()
        val waitMs = (attemptAtMillis - elapsedRealtime()).coerceAtLeast(0)
        reconnectHandle = reconnectScheduler.schedule(waitMs) {
            reconnectHandle = null
            beginAutomaticReconnectAttempt()
        }
    }

    private fun beginAutomaticReconnectAttempt() {
        onEvent(SerialCoordinatorEvent.RequestReadinessRefresh)
        val adapter = adapterProvider() ?: return
        val device = scanner.bondedTarget()
        if (device != null) targetDevice = device

        when (val decision = reconnectPolicy.beginAttempt(elapsedRealtime(), prerequisitesProvider(device))) {
            ReconnectDecision.StartAttempt -> {
                if (device == null) {
                    handleReconnectDecision(ReconnectDecision.Blocked(ReconnectBlockReason.TargetUnavailable))
                    return
                }
                if (!startSerialSession(adapter, device, automatic = true)) {
                    reconnectPolicy.cancelAttempt()
                }
            }

            ReconnectDecision.AlreadyInProgress,
            ReconnectDecision.NoAction,
            -> Unit

            is ReconnectDecision.Schedule -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Wait -> scheduleReconnectAt(decision.attemptAtMillis)
            is ReconnectDecision.Blocked -> handleReconnectDecision(decision)
        }
    }

    /**
     * Retires the current serial session: cancels serial and SPP state jobs,
     * disconnects and clears the SPP connection, and bumps the generation so
     * any in-flight late SPP state or serial-end callback is dropped.
     */
    private fun retireSession() {
        sessionGeneration++
        serialJob?.cancel()
        sppStateJob?.cancel()
        sppConnection?.disconnect()
        sppConnection = null
    }
}