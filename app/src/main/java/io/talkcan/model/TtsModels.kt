package io.talkcan.model

const val DEFAULT_TTS_TEXT = "This is a Talkcan TTS test."
const val DEFAULT_TTS_VOICE_STYLE = "M1"
const val DEFAULT_TTS_LANG = "en"
const val DEFAULT_TTS_TOTAL_STEPS = 8
const val DEFAULT_TTS_SPEED = 1.05f
val TTS_VOICE_STYLES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
val TTS_LANGS = listOf(
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
    "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
)

enum class TtsModelStatus { Idle, Loading, Ready, Failed }

sealed interface TtsStatus {
    data object Idle : TtsStatus
    data object WaitingForModel : TtsStatus
    data object Synthesizing : TtsStatus
    data object Playing : TtsStatus
    data object EmptyText : TtsStatus
    data object Cancelled : TtsStatus
    data class Error(val reason: String) : TtsStatus
}

sealed interface SttTtsStatus {
    data object Idle : SttTtsStatus
    data object WaitingForAudio : SttTtsStatus
    data object Beeping : SttTtsStatus
    data object Recording : SttTtsStatus
    data object MaxDurationReached : SttTtsStatus
    data object Transcribing : SttTtsStatus
    data object WaitingForSttModel : SttTtsStatus
    data object WaitingForTtsModel : SttTtsStatus
    data object Synthesizing : SttTtsStatus
    data object Playing : SttTtsStatus
    data class Transcript(val text: String) : SttTtsStatus
    data object EmptyAudio : SttTtsStatus
    data object EmptyTranscript : SttTtsStatus
    data object Cancelled : SttTtsStatus
    data class Error(val reason: String) : SttTtsStatus
}

fun TtsModelStatus.displayText(): String = when (this) {
    TtsModelStatus.Idle -> "TTS model idle"
    TtsModelStatus.Loading -> "TTS model loading"
    TtsModelStatus.Ready -> "TTS model ready"
    TtsModelStatus.Failed -> "TTS model failed"
}

fun TtsStatus.displayText(): String = when (this) {
    TtsStatus.Idle -> "Idle"
    TtsStatus.WaitingForModel -> "Waiting for model"
    TtsStatus.Synthesizing -> "Synthesizing"
    TtsStatus.Playing -> "Playing"
    TtsStatus.EmptyText -> "Empty text"
    TtsStatus.Cancelled -> "Cancelled"
    is TtsStatus.Error -> "Error: $reason"
}

fun SttTtsStatus.displayText(): String = when (this) {
    SttTtsStatus.Idle -> "Idle"
    SttTtsStatus.WaitingForAudio -> "Waiting for audio"
    SttTtsStatus.Beeping -> "Beeping"
    SttTtsStatus.Recording -> "Recording"
    SttTtsStatus.MaxDurationReached -> "Max duration reached"
    SttTtsStatus.Transcribing -> "Transcribing"
    SttTtsStatus.WaitingForSttModel -> "Waiting for STT model"
    SttTtsStatus.WaitingForTtsModel -> "Waiting for TTS model"
    SttTtsStatus.Synthesizing -> "Synthesizing"
    SttTtsStatus.Playing -> "Playing"
    is SttTtsStatus.Transcript -> text
    SttTtsStatus.EmptyAudio -> "Empty audio"
    SttTtsStatus.EmptyTranscript -> "Empty transcript"
    SttTtsStatus.Cancelled -> "Cancelled"
    is SttTtsStatus.Error -> "Error: $reason"
}
