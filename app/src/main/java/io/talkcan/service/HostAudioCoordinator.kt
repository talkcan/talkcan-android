package io.talkcan.service

import io.talkcan.audio.AcquiredPlaybackRoute
import io.talkcan.audio.ActivePcmPlayback
import io.talkcan.audio.PlaybackCompletion
import io.talkcan.audio.PlaybackRouteAcquisition
import io.talkcan.audio.PlaybackRouteStrategy
import io.talkcan.audio.RecordedPcm
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opaque lease for one host-owned full-duplex (GPT-Live) audio session. */
data class HostFullDuplexLease internal constructor(
    val operationId: HostAudioOperationId,
)

/** Result of attempting to reserve host audio for full-duplex use. */
sealed interface HostFullDuplexAdmission {
    data class Granted(val lease: HostFullDuplexLease) : HostFullDuplexAdmission
    data object Busy : HostFullDuplexAdmission
    data object Closed : HostFullDuplexAdmission
}

/**
 * The process-wide conversational-audio owner.
 *
 * Capture code remains authoritative for capture mechanics. It receives an opaque lease before
 * entering its existing path and returns that lease only from its existing terminal callback.
 * Playback resolves a physical route only after this coordinator has granted its lease.
 */
internal class HostAudioCoordinator(
    private val newOperationId: () -> HostAudioOperationId = { HostAudioOperationId(UUID.randomUUID().toString()) },
) {
    private val lock = Any()
    private var closed = false
    private var owner: Owner? = null
    private var activePlayback: ActivePcmPlayback? = null
    private var rejectedPressPendingRelease = false
    private var priorityHeld = false

    fun setPriorityHeld(held: Boolean) = synchronized(lock) {
        priorityHeld = held
    }

    private val _isPlaybackActive = MutableStateFlow(false)
    val isPlaybackActive: StateFlow<Boolean> = _isPlaybackActive.asStateFlow()
    /**
     * This is intentionally synchronous: PTT ingress must reject playback before the protected
     * dispatcher can auto-transition the mode or reserve an input session.
     */
    fun reserveCapture(priority: Boolean = false): HostCaptureAdmission {
        val (playback, admission) = synchronized(lock) {
            if (closed) return@synchronized null to HostCaptureAdmission.Closed
            if (priorityHeld && !priority) return@synchronized null to HostCaptureAdmission.Busy
            when (owner) {
                null -> {
                    val lease = HostCaptureLease(newOperationId())
                    owner = Owner.Capture(lease)
                    null to HostCaptureAdmission.Granted(lease)
                }
                is Owner.Playback -> {
                    rejectedPressPendingRelease = true
                    activePlayback to HostCaptureAdmission.RejectedByPlayback
                }
                else -> null to HostCaptureAdmission.Busy
            }
        }
        playback?.rejectPttWithTone()
        return admission
    }

    /** Marks a capture lease as handed to the unchanged capture/session lifecycle. */
    fun commitCapture(lease: HostCaptureLease): Boolean = synchronized(lock) {
        val capture = owner as? Owner.Capture ?: return@synchronized false
        if (capture.lease != lease) return@synchronized false
        capture.committed = true
        true
    }

    /** Called exclusively by the existing PTT terminal-completion observer. */
    fun releaseCapture(lease: HostCaptureLease): Boolean = synchronized(lock) {
        val capture = owner as? Owner.Capture ?: return@synchronized false
        if (capture.lease != lease) return@synchronized false
        owner = null
        true
    }

    /**
     * Reserves the process-wide conversational-audio admission for one full-duplex session.
     *
     * Granted only when no half-duplex capture/playback (and no other full-duplex session)
     * owns admission; never steals an existing lease. While the returned lease is held,
     * [reserveCapture] reports [HostCaptureAdmission.Busy] and [play] reports
     * [HostPlaybackResult.Busy]. The holder must call [releaseFullDuplex] exactly once,
     * after all route/audio cleanup.
     */
    fun reserveFullDuplex(): HostFullDuplexAdmission = synchronized(lock) {
        if (closed) return@synchronized HostFullDuplexAdmission.Closed
        if (owner != null) return@synchronized HostFullDuplexAdmission.Busy
        val lease = HostFullDuplexLease(newOperationId())
        owner = Owner.FullDuplex(lease)
        HostFullDuplexAdmission.Granted(lease)
    }

    /** Releases a full-duplex lease; succeeds only for the current owner. */
    fun releaseFullDuplex(lease: HostFullDuplexLease): Boolean = synchronized(lock) {
        val fullDuplex = owner as? Owner.FullDuplex ?: return@synchronized false
        if (fullDuplex.lease != lease) return@synchronized false
        owner = null
        true
    }

    /** Consumes the release paired with a PTT press rejected during active playback. */
    fun consumeRejectedPttRelease(): Boolean = synchronized(lock) {
        if (!rejectedPressPendingRelease) return@synchronized false
        rejectedPressPendingRelease = false
        true
    }

    suspend fun play(
        recording: RecordedPcm,
        kind: HostPlaybackKind = HostPlaybackKind.OPERATIONAL,
        onAcquired: (AcquiredPlaybackRoute) -> Unit = {},
        strategy: () -> PlaybackRouteStrategy,
    ): HostPlaybackResult {
        val operation = synchronized(lock) {
            if (closed) return HostPlaybackResult.Closed
            if (owner != null || priorityHeld) return HostPlaybackResult.Busy
            Owner.Playback(newOperationId(), kind).also {
                owner = it
                _isPlaybackActive.value = true
            }
        }
        var route: AcquiredPlaybackRoute? = null
        var playback: ActivePcmPlayback? = null
        var completion: PlaybackCompletion? = null
        var result: HostPlaybackResult = HostPlaybackResult.Interrupted
        try {
            when (val acquired = strategy().acquire()) {
                is PlaybackRouteAcquisition.Acquired -> route = acquired.route
                PlaybackRouteAcquisition.Busy -> {
                    result = HostPlaybackResult.Busy
                    return result
                }
                is PlaybackRouteAcquisition.Unavailable -> {
                    result = HostPlaybackResult.Unavailable(acquired.reason)
                    return result
                }
                is PlaybackRouteAcquisition.Failed -> {
                    result = HostPlaybackResult.Failed(acquired.reason)
                    return result
                }
            }

            val acquiredRoute = checkNotNull(route)
            runCatching { onAcquired(acquiredRoute) }
            val startedPlayback = acquiredRoute.start(recording)
            playback = startedPlayback
            val skipOnStart = synchronized(lock) {
                val current = owner as? Owner.Playback
                if (current !== operation || closed) null else {
                    activePlayback = startedPlayback
                    current.terminating
                }
            }
            if (skipOnStart == null || skipOnStart) startedPlayback.skip()

            completion = startedPlayback.awaitCompletion()
            result = if (skipOnStart == null) {
                HostPlaybackResult.Closed
            } else {
                when (val terminal = completion) {
                    PlaybackCompletion.Completed -> HostPlaybackResult.Completed
                    PlaybackCompletion.ExplicitlySkipped -> HostPlaybackResult.ExplicitlySkipped
                    PlaybackCompletion.Interrupted -> HostPlaybackResult.Interrupted
                    is PlaybackCompletion.Failed -> HostPlaybackResult.Failed(terminal.reason)
                    null -> HostPlaybackResult.Failed("Playback completed without a terminal result")
                }
            }
        } catch (error: CancellationException) {
            result = HostPlaybackResult.Interrupted
            throw error
        } catch (error: Exception) {
            result = HostPlaybackResult.Failed(error.message ?: "Unable to play recording")
        } finally {
            withContext(NonCancellable) {
                val active = playback
                if (active != null && completion == null) {
                    active.skip()
                    runCatching { active.awaitCompletion() }
                }
                route?.let { acquired -> runCatching { acquired.release() } }
                releasePlaybackReservation(operation, result)
            }
        }
        return result
    }

    suspend fun consumeSosDuringPlayback(): HostSosDisposition {
        val control = synchronized(lock) {
            val current = owner as? Owner.Playback ?: return@synchronized null
            current.terminating = true
            current to activePlayback
        } ?: return HostSosDisposition.DispatchToChannel
        control.second?.skip()
        return HostSosDisposition.ConsumedByPlayback
    }

    /**
     * Synchronous operational-preemption boundary for lower-priority editor preview playback.
     *
     * When — and only when — a [HostPlaybackKind.PREVIEW] operation currently owns admission,
     * this terminates it, skips its active stream, and releases the admission reservation so a
     * following [reserveCapture] or operational [play] is admitted deterministically. The
     * preempted play call resolves [HostPlaybackResult.ExplicitlySkipped] once its skipped
     * stream terminates — or [HostPlaybackResult.Closed] when the preemption wins before the
     * play call's own start check — and releases its acquired route in its terminal cleanup.
     * [HostPlaybackKind.OPERATIONAL] ownership is never touched. The caller owns inference and
     * controller cancellation; this only owns admission.
     */
    fun preemptPreviewPlayback(): Boolean {
        val skipped = synchronized(lock) {
            val playback = owner as? Owner.Playback ?: return false
            if (playback.kind != HostPlaybackKind.PREVIEW) return false
            playback.terminating = true
            val active = activePlayback
            owner = null
            activePlayback = null
            _isPlaybackActive.value = false
            active
        }
        skipped?.skip()
        return true
    }

    suspend fun close() {
        val playback = synchronized(lock) {
            closed = true
            (owner as? Owner.Playback)?.terminating = true
            _isPlaybackActive.value = false
            activePlayback
        }
        playback?.skip()
    }

    private fun releasePlaybackReservation(
        playback: Owner.Playback,
        result: HostPlaybackResult,
    ): HostPlaybackResult {
        synchronized(lock) {
            if (owner == playback) {
                owner = null
                activePlayback = null
                rejectedPressPendingRelease = false
                _isPlaybackActive.value = false
            }
        }
        return result
    }

    private sealed interface Owner {
        class Capture(val lease: HostCaptureLease, var committed: Boolean = false) : Owner
        class FullDuplex(val lease: HostFullDuplexLease) : Owner
        class Playback(
            val operationId: HostAudioOperationId,
            val kind: HostPlaybackKind,
            var terminating: Boolean = false,
        ) : Owner
    }
}
