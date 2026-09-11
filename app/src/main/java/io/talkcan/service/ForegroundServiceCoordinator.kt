package io.talkcan.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Exclusive owner of the logical foreground flag and the readiness-refresh-loop
 * job, plus the deferred serial-disconnect shutdown gate.
 *
 * The coordinator never touches the Android [android.app.Service], the
 * notification channel/ID/content, or [io.talkcan.model.AppState]
 * directly. Every Android-edge effect (channel creation, notification
 * construction, `startForeground`/`stopForeground`/`stopSelf`) is invoked
 * through narrow function-injected callbacks supplied by the service shell.
 * Observations (monitoring requested, readiness-for-monitor, active PTT
 * session) are read through injected providers so the coordinator holds no
 * reference to the service or its collaborators.
 *
 * Behavioral contracts preserved from the baseline service implementation:
 * - foreground start/stop is idempotent (single foreground path);
 * - exactly one readiness-refresh-loop job exists at a time;
 * - the loop polls at the existing 5-second interval and self-syncs when the
 *   device becomes ready-for-monitor or foreground is lost;
 * - the serial-disconnect shutdown predicate
 *   ([shouldStopAfterSerialDisconnect]) gates teardown against monitoring
 *   retention and active PTT terminal work;
 * - repeated events do not duplicate notification transitions or loop
 *   ownership.
 */
internal class ForegroundServiceCoordinator(
    private val scope: CoroutineScope,
    private val startForeground: () -> Boolean,
    private val stopForeground: () -> Unit,
    private val stopSelf: (Int?) -> Unit,
    private val refreshReadiness: () -> Unit,
    private val monitoringRequested: () -> Boolean,
    private val readyForMonitor: () -> Boolean,
    private val hasActivePttSession: () -> Boolean,
    private val refreshIntervalMs: Long = 5_000L,
) {
    private var foreground = false
    val isForeground: Boolean get() = foreground
    private var readinessRefreshJob: Job? = null
    private var stopWhenPttIdleAfterSerialDisconnect = false

    /**
     * Handles `onStartCommand` for the monitoring action. Preserves the
     * baseline ordering: if monitoring is requested, ensure foreground; if
     * not, acknowledge the `startForegroundService` contract by entering
     * foreground, then immediately tearing it down and stopping the service
     * with [startId] to suppress restart.
     */
    fun onStartCommand(monitoringRequested: Boolean, startId: Int) {
        if (monitoringRequested) {
            ensureForeground()
        } else {
            // A startForegroundService request must still be acknowledged
            // before terminally suppressing this service-lifetime restart.
            ensureForeground()
            stopForegroundIfNeeded()
            stopSelf(startId)
        }
    }

    /**
     * Requests foreground operation once. Idempotent: returns immediately if
     * already foreground. On success, sets the flag and syncs the readiness
     * refresh loop. On failure, clears the flag and stops the service.
     */
    fun ensureForeground() {
        if (foreground) return
        val started = runCatching { startForeground() }.getOrDefault(false)
        if (started) {
            foreground = true
            syncReadinessRefreshLoop()
        } else {
            foreground = false
            stopSelf(null)
        }
    }

    /**
     * Requests foreground teardown once. Idempotent: returns immediately if
     * not foreground. Clears the flag and syncs the readiness refresh loop
     * (which stops the loop when refresh is no longer allowed).
     */
    fun stopForegroundIfNeeded() {
        if (!foreground) return
        stopForeground()
        foreground = false
        syncReadinessRefreshLoop()
    }

    /**
     * Requests foreground teardown and unconditional service stop. Used by the
     * serial coordinator's `RequestStopForegroundAndSelf` event.
     */
    fun requestStopForegroundAndSelf() {
        stopForegroundIfNeeded()
        stopSelf(null)
    }

    /**
     * Reevaluates the deferred serial-disconnect shutdown. Preserves the
     * baseline [shouldStopAfterSerialDisconnect] predicate: only stops when
     * disconnect is pending, monitoring is no longer requested, and no PTT
     * session remains active. Clears the pending flag before tearing down to
     * guarantee exactly-once shutdown.
     */
    fun reevaluateSerialDisconnectShutdown() {
        if (!shouldStopAfterSerialDisconnect(
                serialDisconnectPending = stopWhenPttIdleAfterSerialDisconnect,
                monitoringRequested = monitoringRequested(),
                hasActivePttSession = hasActivePttSession(),
            )
        ) return
        stopWhenPttIdleAfterSerialDisconnect = false
        stopForegroundIfNeeded()
        stopSelf(null)
    }

    /**
     * Records a change to the deferred serial-disconnect pending flag.
     */
    fun onSerialDisconnectPendingChanged(pending: Boolean) {
        stopWhenPttIdleAfterSerialDisconnect = pending
    }

    /**
     * Notifies the coordinator that a PTT terminal session completed. Preserves
     * the baseline post-completion reevaluation of the deferred serial-disconnect
     * shutdown gate.
     */
    fun onPttTerminalCompleted() {
        reevaluateSerialDisconnectShutdown()
    }

    /**
     * Synchronizes the readiness refresh loop against current foreground,
     * monitoring, and readiness-for-monitor state. Preserves the baseline
     * [ReadinessRefreshLoopPolicy] decision: start, stop, or keep the loop.
     */
    fun syncReadinessRefreshLoop() {
        when (
            ReadinessRefreshLoopPolicy.decide(
                refreshAllowed = foreground && monitoringRequested(),
                readyForMonitor = readyForMonitor(),
                refreshLoopActive = readinessRefreshJob?.isActive == true,
            )
        ) {
            ReadinessRefreshLoopDecision.KeepCurrentLoop -> Unit
            ReadinessRefreshLoopDecision.StartRefreshLoop -> startReadinessRefreshLoop()
            ReadinessRefreshLoopDecision.StopRefreshLoop -> stopReadinessRefreshLoop()
        }
    }

    /**
     * Stops the readiness refresh loop if one is active. Used by the serial
     * coordinator's `RequestStopReadinessRefreshLoop` event and by service
     * teardown.
     */
    fun stopReadinessRefreshLoop() {
        readinessRefreshJob?.cancel()
        readinessRefreshJob = null
    }

    private fun startReadinessRefreshLoop() {
        if (readinessRefreshJob?.isActive == true) return
        readinessRefreshJob = scope.launch {
            while (true) {
                delay(refreshIntervalMs)
                if (!foreground || readyForMonitor()) {
                    syncReadinessRefreshLoop()
                    return@launch
                }
                refreshReadiness()
            }
        }
    }
}