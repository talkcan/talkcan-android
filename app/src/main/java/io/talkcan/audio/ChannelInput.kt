package io.talkcan.audio

import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.service.CaptureFeedbackTone
import kotlinx.coroutines.flow.Flow

data class CapturePolicy(val maxDurationMs: Long) {
    init {
        require(maxDurationMs in 60_000L..600_000L) {
            "maxDurationMs must be between 60_000 and 600_000 ms, got $maxDurationMs"
        }
    }
}

interface SemanticFeedbackEmitter {
    suspend fun emit(tone: CaptureFeedbackTone)
}

interface ChannelAudioInputSession {
    val frames: Flow<ShortArray>
    val sampleRate: Int
    val maxDurationMs: Long
    val remainingDurationMs: Long
    val semanticFeedbackEmitter: SemanticFeedbackEmitter
}

sealed interface ChannelInputEvent {
    data class Started(val session: ChannelAudioInputSession) : ChannelInputEvent
    data class Released(val recording: RecordedPcm) : ChannelInputEvent
    data class Cancelled(val reason: String) : ChannelInputEvent
    data class Failed(val reason: String) : ChannelInputEvent
}

sealed interface ChannelInputResult {
    data object None : ChannelInputResult
    data class Playback(val recording: RecordedPcm) : ChannelInputResult
    data class PlaybackOperation(val operation: OpaqueAudioOperation) : ChannelInputResult
}

sealed interface ChannelInputAcceptance {
    data class Accepted(val target: ChannelInputTarget) : ChannelInputAcceptance
    data class Refused(val reason: String) : ChannelInputAcceptance
    data class Unavailable(val reason: String) : ChannelInputAcceptance
}

interface ChannelInputTarget {
    val capturePolicy: CapturePolicy
    suspend fun onInputStarted(session: ChannelAudioInputSession)
    suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult
    fun onInputPlaybackCompleted() {}
    suspend fun onInputCancelled(reason: String)
    suspend fun onInputFailed(reason: String)
}

internal class CaptureChannelAudioInputSession(
    private val delegate: CaptureSession,
) : ChannelAudioInputSession {
    override val frames: Flow<ShortArray> = delegate.frames
    override val sampleRate: Int = delegate.sampleRate
    override val maxDurationMs: Long = delegate.maxDurationMs
    override val remainingDurationMs: Long get() = delegate.remainingDurationMs
    override val semanticFeedbackEmitter: SemanticFeedbackEmitter = delegate.semanticFeedbackEmitter
}
