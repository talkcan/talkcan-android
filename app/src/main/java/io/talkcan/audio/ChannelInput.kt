package io.talkcan.audio

import io.talkcan.channel.capability.OpaqueAudioOperation

import kotlinx.coroutines.flow.Flow

interface ChannelAudioInputSession {
    val frames: Flow<ShortArray>
    val sampleRate: Int
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
    fun onInputStarted(session: ChannelAudioInputSession)
    suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult
    fun onInputPlaybackCompleted() {}
    fun onInputCancelled(reason: String)
    fun onInputFailed(reason: String)
}

internal class CaptureChannelAudioInputSession(
    private val delegate: CaptureSession,
) : ChannelAudioInputSession {
    override val frames: Flow<ShortArray> = delegate.frames
    override val sampleRate: Int = delegate.sampleRate
}
