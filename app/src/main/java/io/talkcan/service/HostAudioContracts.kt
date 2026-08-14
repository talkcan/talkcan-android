package io.talkcan.service

/**
 * Opaque identity for one host-owned half-duplex audio operation.
 *
 * This identity deliberately has no relationship to Android routes, devices, capture sessions,
 * or channel-runtime capability leases. It is used only by host coordination and diagnostics.
 */
@JvmInline
value class HostAudioOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Host audio operation ID must not be blank" }
    }
}

/** Every state that owns the process-wide conversational-audio admission. */
enum class HostAudioPhase {
    IDLE,
    CAPTURE_RESERVED,
    CAPTURE_OWNED,
    PLAYBACK_RESERVED,
    PLAYING,
    RELEASING,
}

/** Durable scheduling policy for a channel's inbound-response FIFO. */
enum class PlaybackDrainState {
    ENABLED,
    PAUSED_BY_USER,
}

/** Why the host ended an admitted playback operation. */
enum class HostPlaybackTermination {
    COMPLETED,
    EXPLICIT_SKIP,
    INTERRUPTED,
    ROUTE_FAILURE,
    CANCELLED,
}

/** Result of attempting to reserve host audio before any physical route work begins. */
sealed interface HostAudioAdmission {
    data class Granted(val operationId: HostAudioOperationId) : HostAudioAdmission
    data object Busy : HostAudioAdmission
    data object Closed : HostAudioAdmission
}

/** Result of a host playback control action. */
sealed interface HostPlaybackControlResult {
    data object Applied : HostPlaybackControlResult
    data object NoActivePlayback : HostPlaybackControlResult
    data object AlreadyTerminating : HostPlaybackControlResult
}

/** Result of contextual SOS dispatch at the host audio boundary. */
sealed interface HostSosDisposition {
    data object ConsumedByPlayback : HostSosDisposition
    data object DispatchToChannel : HostSosDisposition
}

/** Opaque lease returned only to host capture integration. */
data class HostCaptureLease internal constructor(
    val operationId: HostAudioOperationId,
)

sealed interface HostCaptureAdmission {
    data class Granted(val lease: HostCaptureLease) : HostCaptureAdmission
    data object RejectedByPlayback : HostCaptureAdmission
    data object Busy : HostCaptureAdmission
    data object Closed : HostCaptureAdmission
}

sealed interface HostPlaybackResult {
    data object Busy : HostPlaybackResult
    data object Completed : HostPlaybackResult
    data object ExplicitlySkipped : HostPlaybackResult
    data object Interrupted : HostPlaybackResult
    data class Unavailable(val reason: String) : HostPlaybackResult
    data class Failed(val reason: String) : HostPlaybackResult
    data object Closed : HostPlaybackResult
}

/**
 * Priority class of a host-owned playback operation.
 *
 * [OPERATIONAL] playback — PTT responses, announcements, navigation, diagnostics — keeps the
 * existing admission semantics: a PTT press during operational playback is rejected. [PREVIEW]
 * playback is the lower-priority voice-profile editor preview; operational work preempts it
 * through [HostAudioCoordinator.preemptPreviewPlayback] and never preempts operational playback.
 */
enum class HostPlaybackKind {
    OPERATIONAL,
    PREVIEW,
}

/** Tones emitted by the host for recording limits or session feedback. */
enum class CaptureFeedbackTone {
    RecordingLimitWarning,
    RecordingLimitFinal,
}
