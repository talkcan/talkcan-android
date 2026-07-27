package io.talkcan.service

import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.audio.ROUTE_LOG_TAG
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.routeDebugString
import io.talkcan.model.InputMode
import io.talkcan.model.PttSource
import io.talkcan.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class PttCancellationCaller {
    RsmSerialSessionEnded,
    ExplicitSerialDisconnect,
    CarSetupFailure,
    TelecomRouteTimeout,
    TelecomConnectionEnded,
    ChannelRuntimeShutdown,
    CaptureAdmissionLost,
    ServiceTeardown,
}

/**
 * Encapsulates PTT press/release dispatch and session tracking.
 *
 * Admission is decided from the provider-neutral runtime registry projection.
 */
internal class PttDispatcher(
    private val serviceScope: CoroutineScope,
    private val inputModeController: InputModeController,
    private val audioSessionManager: PttAudioSessionManager,
    private val audioCoordinator: HostAudioCoordinator = HostAudioCoordinator(),
    private val preemptPreviewForOperationalAudio: () -> Unit = {},
    private val resolvePttAudioRoute: (InputMode) -> ResolvedAudioRoute,
    private val publishInputMode: () -> Unit,
    private val cancelIdleTimer: () -> Unit,
    private val decidePttDispatch: () -> PttDispatchDecision?,
    private val logAudioRouteSnapshot: (String) -> Unit,
    private val updateCarMediaState: () -> Unit,
) {
    private val captureLeaseLock = Any()
    private val captureLeases = mutableMapOf<Long, HostCaptureLease>()

    val activePttSession: PttAudioSessionManager.SessionSnapshot?
        get() = audioSessionManager.activeSession

    fun reserveCaptureAdmission(): HostCaptureAdmission {
        // Operational capture preempts lower-priority editor preview before admission so a
        // preview playback can never reject a PTT press or car-telecom setup.
        preemptPreviewForOperationalAudio()
        return audioCoordinator.reserveCapture()
    }

    fun abandonCaptureAdmission(lease: HostCaptureLease): Boolean = audioCoordinator.releaseCapture(lease)

    fun reservePendingPtt(source: PttSource, channelId: String): Boolean = when (val admission = reserveCaptureAdmission()) {
        is HostCaptureAdmission.Granted -> reservePendingPtt(source, channelId, admission.lease)
        HostCaptureAdmission.RejectedByPlayback,
        HostCaptureAdmission.Busy,
        HostCaptureAdmission.Closed,
        -> false
    }

    fun reservePendingPtt(source: PttSource, channelId: String, lease: HostCaptureLease): Boolean {
        if (!audioSessionManager.reservePending(source, channelId, inputModeController.mode)) {
            abandonCaptureAdmission(lease)
            return false
        }
        val sessionId = activePttSession?.id
        if (sessionId == null || !attachCaptureLease(sessionId, lease)) {
            cancelPttBySource(
                source = source,
                caller = PttCancellationCaller.CaptureAdmissionLost,
                reason = "Host capture admission lost",
                eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
            )
            abandonCaptureAdmission(lease)
            return false
        }
        return true
    }

    fun dispatchPttPressed(source: PttSource): Boolean {
        val pendingSameSource = activePttSession?.source == source
        val captureLease = if (pendingSameSource) {
            null
        } else {
            preemptPreviewForOperationalAudio()
            when (val admission = audioCoordinator.reserveCapture()) {
                is HostCaptureAdmission.Granted -> admission.lease
                HostCaptureAdmission.RejectedByPlayback -> {
                    Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=playback-active")
                    return false
                }
                HostCaptureAdmission.Busy -> {
                    Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=host-audio-busy")
                    return false
                }
                HostCaptureAdmission.Closed -> return false
            }
        }
        fun reject() {
            captureLease?.let(audioCoordinator::releaseCapture)
        }
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_PRESS_BEGIN source=$source activeSession=${audioSessionManager.isActive} " +
                "modeBefore=${inputModeController.mode} availability=${inputModeController.availability}",
        )
        logAudioRouteSnapshot("ptt-press-begin-$source")
        if (audioSessionManager.isActive && !pendingSameSource) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=active-session")
            reject()
            return false
        }
        val transitioned = inputModeController.autoTransitionFor(source)
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_AUTO_TRANSITION source=$source ok=$transitioned modeAfter=${inputModeController.mode}",
        )
        logAudioRouteSnapshot("ptt-after-transition-$source")
        if (!transitioned) {
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP_SKIP source=$source reason=transition-failed mode=${inputModeController.mode}",
            )
            reject()
            return false
        }
        publishInputMode()
        cancelIdleTimer()
        val decision = decidePttDispatch()

        Log.d(ROUTE_LOG_TAG, "PTT_DECIDE decision=${decision?.let { it::class.simpleName }} channel=${decision?.channelId}")
        if (decision == null) {
            reject()
            return false
        }
        val activeChannelId = decision.channelId

        if (decision is PttDispatchDecision.ErrorBeep) {
            reject()
            val route = resolvePttAudioRoute(inputModeController.mode)
            Log.d(
                ROUTE_LOG_TAG,
                "PTT_ERROR_BEEP source=$source reason=dispatch-decision route=${route.routeDebugString()}",
            )
            serviceScope.launch {
                playRouteErrorBeepIfAcquired(route)
            }
            return false
        }

        val started = audioSessionManager.start(
            source = source,
            channelId = activeChannelId,
            mode = inputModeController.mode,
        )
        if (!started) {
            Log.d(ROUTE_LOG_TAG, "PTT_PRESS_SKIP source=$source reason=session-start-rejected")
            reject()
            return false
        }
        val sessionId = activePttSession?.id
        if (sessionId == null || (captureLease != null && !attachCaptureLease(sessionId, captureLease))) {
            cancelPttBySource(
                source = source,
                caller = PttCancellationCaller.CaptureAdmissionLost,
                reason = "Host capture admission lost",
            )
            reject()
            return false
        }
        Log.d(ROUTE_LOG_TAG, "PTT_DISPATCH channel=$activeChannelId mode=${inputModeController.mode}")
        updateCarMediaState()
        return true
    }

    private fun attachCaptureLease(sessionId: Long, lease: HostCaptureLease): Boolean {
        if (!audioCoordinator.commitCapture(lease)) return false
        synchronized(captureLeaseLock) { captureLeases[sessionId] = lease }
        return true
    }

    fun dispatchPttReleased(source: PttSource): Boolean {
        if (audioCoordinator.consumeRejectedPttRelease()) return false
        val released = audioSessionManager.release(source)
        if (released) updateCarMediaState()
        return released
    }

    /** Observes the existing terminal publication; it never performs input terminal cleanup. */
    fun onTerminalCompleted(completion: PttAudioSessionManager.TerminalCompletion) {
        val lease = synchronized(captureLeaseLock) { captureLeases.remove(completion.sessionId) } ?: return
        audioCoordinator.releaseCapture(lease)
    }

    fun cancelPttBySource(
        source: PttSource,
        caller: PttCancellationCaller,
        reason: String,
        eligibility: PttAudioSessionManager.CancellationEligibility =
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
    ): PttAudioSessionManager.CancellationOutcome {
        return audioSessionManager.cancelBySource(source, eligibility, reason) { decision ->
            logCancellationRequest(scope = "source", caller = caller, outcome = decision)
            handleAcceptedCancellation(decision)
        }
    }

    suspend fun cancelAnyActivePttForServiceTeardown(
        caller: PttCancellationCaller,
        reason: String,
    ): PttAudioSessionManager.CancellationOutcome {
        require(
            caller == PttCancellationCaller.ServiceTeardown ||
                caller == PttCancellationCaller.ChannelRuntimeShutdown,
        ) { "All-source cancellation is reserved for service teardown" }
        return audioSessionManager.shutdownAndAwait(reason) { decision ->
            logCancellationRequest(scope = "global", caller = caller, outcome = decision)
            handleAcceptedCancellation(decision)
        }
    }

    private fun handleAcceptedCancellation(outcome: PttAudioSessionManager.CancellationOutcome) {
        if (outcome.disposition != PttAudioSessionManager.CancellationDisposition.Accepted) return
        cancelIdleTimer()
        if (outcome.sessionSource == PttSource.CarTelecom) {
            TelecomCarPttCoordinator.forceAbort()
        }
        updateCarMediaState()
    }

    private fun logCancellationRequest(
        scope: String,
        caller: PttCancellationCaller,
        outcome: PttAudioSessionManager.CancellationOutcome,
    ) {
        Log.d(
            ROUTE_LOG_TAG,
            "PTT_CANCELLATION_REQUEST scope=$scope caller=$caller " +
                "requestedSource=${outcome.requestedSource ?: "Any"} " +
                "currentSessionId=${outcome.sessionId ?: "None"} " +
                "currentSource=${outcome.sessionSource ?: "None"} " +
                "currentPhase=${outcome.sessionPhase ?: "None"} eligibility=${outcome.eligibility} " +
                "disposition=${outcome.disposition} reason=${outcome.reason.toCancellationLogValue()}",
        )
    }

    fun isTerminalCarSource(): Boolean =
        activePttSession?.source == PttSource.CarTelecom

}

internal fun String.toCancellationLogValue(): String {
    val normalized = buildString(length.coerceAtMost(64)) {
        var separatorPending = false
        for (character in this@toCancellationLogValue) {
            when {
                character.isLetterOrDigit() -> {
                    if (separatorPending && isNotEmpty()) append('-')
                    append(character.lowercaseChar())
                    separatorPending = false
                }
                isNotEmpty() -> separatorPending = true
            }
            if (length == 64) break
        }
    }.ifEmpty { "unspecified" }
    return normalized.takeIf { it in semanticDiagnosticValues } ?: "unspecified"
}

private val semanticDiagnosticValues = setOf(
    "host-capture-admission-lost",
    "explicit-rsm-serial-disconnect",
    "rsm-serial-session-ended",
    "telecom-route-timeout",
    "telecom-connection-ended",
    "work-route-release-failed",
    "car-hfp-prime-failed",
    "telecom-account-disabled",
    "telecom-manager-unavailable",
    "telecom-connection-prepare-failed",
    "channel-runtime-shutdown",
    "service-teardown",
    "setup-cancellation",
    "problem-feedback",
    "playback-resolution",
    "playback",
    "playback-completion",
    "capture-stop",
    "capture-cancellation",
    "target-release",
    "target-failure",
    "target-cancellation",
    "route-release",
    "committed-target-lease-release",
    "late-target-cancellation",
    "late-capture-cancellation",
    "late-route-release",
    "terminal-completion-publication",
)
