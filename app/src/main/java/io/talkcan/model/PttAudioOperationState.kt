package io.talkcan.model

enum class PttAudioOperationPhase {
    IDLE,
    PENDING,
    RECORDING,
    FINALIZING
}

data class PttAudioOperationState(
    val source: PttSource? = null,
    val channelId: String? = null,
    val mode: InputMode? = null,
    val phase: PttAudioOperationPhase = PttAudioOperationPhase.IDLE,
    val isPlaybackActive: Boolean = false
)
