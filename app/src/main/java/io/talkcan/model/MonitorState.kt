package io.talkcan.model

data class MonitorState(
    val hardwareMode: HardwareMode = HardwareMode.Active,
    val buttons: ButtonStates = ButtonStates(),
    val echoEnabled: Boolean = false,
    val scoState: ScoState = ScoState.Inactive,
    val echoStatus: EchoStatus = EchoStatus.Idle,
    val sttEnabled: Boolean = false,
    val sttModelStatus: SttModelStatus = SttModelStatus.Idle,
    val sttStatus: SttStatus = SttStatus.Idle,
    val sttTranscript: String = "",
    val ttsEnabled: Boolean = false,
    val ttsModelStatus: TtsModelStatus = TtsModelStatus.Idle,
    val ttsStatus: TtsStatus = TtsStatus.Idle,
    val ttsText: String = DEFAULT_TTS_TEXT,
    val ttsVoiceStyle: String = DEFAULT_TTS_VOICE_STYLE,
    val ttsLang: String = DEFAULT_TTS_LANG,
    val ttsTotalSteps: Int = DEFAULT_TTS_TOTAL_STEPS,
    val ttsSpeed: Float = DEFAULT_TTS_SPEED,
    val sttTtsEnabled: Boolean = false,
    val sttTtsStatus: SttTtsStatus = SttTtsStatus.Idle,
    val sttTtsTranscript: String = "",
    val sttTtsVoiceStyle: String = DEFAULT_TTS_VOICE_STYLE,
    val sttTtsLang: String = DEFAULT_TTS_LANG,
    val sttTtsTotalSteps: Int = DEFAULT_TTS_TOTAL_STEPS,
    val sttTtsSpeed: Float = DEFAULT_TTS_SPEED,
    val textOutputTransportState: TextOutputTransportState = TextOutputTransportState.Disconnected,
)

data class ButtonStates(
    val ptt: TwoStateButton = TwoStateButton.Released,
    val sos: SosButtonState = SosButtonState.Released,
    val group: TwoStateButton = TwoStateButton.Released,
    val volumeUp: ClickButtonState = ClickButtonState.Idle,
    val volumeDown: ClickButtonState = ClickButtonState.Idle,
)

enum class TwoStateButton { Released, Pressed }
enum class SosButtonState { Released, Pressed, LongPressed }
enum class ClickButtonState { Idle, Clicked }
enum class EchoTimingMode { RecordAfterBeep }

sealed interface ScoState {
    data object Inactive : ScoState
    data object Starting : ScoState
    data object Active : ScoState
    data object Closing : ScoState
    data class Failed(val reason: String) : ScoState
}

sealed interface EchoStatus {
    data object Idle : EchoStatus
    data object WaitingForAudio : EchoStatus
    data object Beeping : EchoStatus
    data object Recording : EchoStatus
    data object MaxDurationReached : EchoStatus
    data object Playback : EchoStatus
    data object Warm : EchoStatus
    data object Cancelled : EchoStatus
    data class Error(val reason: String) : EchoStatus
}

sealed interface RawButtonEvent {
    data object PttPressed : RawButtonEvent
    data object PttReleased : RawButtonEvent
    data object SosPressed : RawButtonEvent
    data object SosReleased : RawButtonEvent
    data object SosLongPressed : RawButtonEvent
    data object GroupPressed : RawButtonEvent
    data object GroupReleased : RawButtonEvent
    data object VolumeUpClicked : RawButtonEvent
    data object VolumeDownClicked : RawButtonEvent
}

fun ScoState.displayText(): String = when (this) {
    ScoState.Inactive -> "SCO inactive"
    ScoState.Starting -> "SCO starting"
    ScoState.Active -> "SCO active"
    ScoState.Closing -> "SCO closing"
    is ScoState.Failed -> "SCO failed: $reason"
}

fun EchoStatus.displayText(): String = when (this) {
    EchoStatus.Idle -> "Idle"
    EchoStatus.WaitingForAudio -> "Waiting for audio"
    EchoStatus.Beeping -> "Beeping"
    EchoStatus.Recording -> "Recording"
    EchoStatus.MaxDurationReached -> "Max duration reached"
    EchoStatus.Playback -> "Playback"
    EchoStatus.Warm -> "Warm"
    EchoStatus.Cancelled -> "Cancelled"
    is EchoStatus.Error -> "Error: $reason"
}
