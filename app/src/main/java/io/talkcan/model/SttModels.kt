package io.talkcan.model

enum class SttModelStatus { Idle, Loading, Ready, Failed }

sealed interface SttStatus {
    data object Idle : SttStatus
    data object WaitingForAudio : SttStatus
    data object Beeping : SttStatus
    data object Recording : SttStatus
    data object MaxDurationReached : SttStatus
    data object Transcribing : SttStatus
    data class Transcribed(val text: String) : SttStatus
    data object EmptyAudio : SttStatus
    data object Cancelled : SttStatus
    data class Error(val reason: String) : SttStatus
}

fun SttModelStatus.displayText(): String = when (this) {
    SttModelStatus.Idle -> "STT model idle"
    SttModelStatus.Loading -> "STT model loading"
    SttModelStatus.Ready -> "STT model ready"
    SttModelStatus.Failed -> "STT model failed"
}

fun SttStatus.displayText(): String = when (this) {
    SttStatus.Idle -> "Idle"
    SttStatus.WaitingForAudio -> "Waiting for audio"
    SttStatus.Beeping -> "Beeping"
    SttStatus.Recording -> "Recording"
    SttStatus.MaxDurationReached -> "Max duration reached"
    SttStatus.Transcribing -> "Transcribing"
    is SttStatus.Transcribed -> text
    SttStatus.EmptyAudio -> "Empty audio"
    SttStatus.Cancelled -> "Cancelled"
    is SttStatus.Error -> "Error: $reason"
}
