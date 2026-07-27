package io.talkcan.audio

/**
 * A host-owned output route acquired after global audio admission.
 *
 * This is intentionally distinct from [ResolvedAudioRoute]: delayed playback has no capture
 * source and must never retain the route that produced its originating recording.
 */
interface AcquiredPlaybackRoute {
    val endpoint: AudioRouteEndpoint

    /** Starts one controllable playback on this already-acquired route. */
    suspend fun start(recording: RecordedPcm): ActivePcmPlayback

    /** Releases this route exactly once after its active playback has terminated. */
    suspend fun release()
}

/** A route strategy is host-only; channels never observe it or select an endpoint. */
fun interface PlaybackRouteStrategy {
    suspend fun acquire(): PlaybackRouteAcquisition
}

sealed interface PlaybackRouteAcquisition {
    data class Acquired(val route: AcquiredPlaybackRoute) : PlaybackRouteAcquisition
    data object Busy : PlaybackRouteAcquisition
    data class Unavailable(val reason: String) : PlaybackRouteAcquisition
    data class Failed(val reason: String) : PlaybackRouteAcquisition
}

/**
 * A controllable stream owned by one acquired playback route.
 *
 * [rejectPttWithTone] never obtains a second route: it modifies this stream's output in place.
 */
interface ActivePcmPlayback {
    suspend fun awaitCompletion(): PlaybackCompletion
    fun rejectPttWithTone(): Boolean
    fun skip(): Boolean
}

sealed interface PlaybackCompletion {
    data object Completed : PlaybackCompletion
    data object ExplicitlySkipped : PlaybackCompletion
    data object Interrupted : PlaybackCompletion
    data class Failed(val reason: String) : PlaybackCompletion
}
