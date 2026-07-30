package io.talkcan.ui

sealed interface PhonePttGestureCommand {
    data class Press(val channelId: String) : PhonePttGestureCommand
    data object Release : PhonePttGestureCommand
}

enum class PhonePttFinishReason {
    PointerRelease,
    Cancelled,
    FocusLost,
    MaxDuration,
}

data class PhonePttGestureTransition(
    val state: PhonePttGestureState,
    val commands: List<PhonePttGestureCommand> = emptyList(),
)

sealed interface PhonePttGestureState {
    data object Idle : PhonePttGestureState

    data class Armed(
        val sawCapture: Boolean = false,
    ) : PhonePttGestureState

    data class Finalized(
        val finishReason: PhonePttFinishReason,
    ) : PhonePttGestureState
}

val PhonePttGestureState.isActive: Boolean
    get() = this is PhonePttGestureState.Armed

fun startPhonePttGesture(
    channelId: String,
): PhonePttGestureTransition {
    return PhonePttGestureTransition(
        state = PhonePttGestureState.Armed(),
        commands = listOf(PhonePttGestureCommand.Press(channelId)),
    )
}

fun PhonePttGestureState.releasePhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.PointerRelease)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.cancelPhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.Cancelled)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.focusLostPhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.FocusLost)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.captureChangedPhonePttGesture(isCapturing: Boolean): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> when {
        isCapturing -> PhonePttGestureTransition(copy(sawCapture = true))
        sawCapture -> finishPhonePttGesture(PhonePttFinishReason.MaxDuration)
        else -> PhonePttGestureTransition(this)
    }
    else -> PhonePttGestureTransition(this)
}

private fun PhonePttGestureState.finishPhonePttGesture(reason: PhonePttFinishReason): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> PhonePttGestureTransition(
        state = PhonePttGestureState.Finalized(reason),
        commands = listOf(PhonePttGestureCommand.Release),
    )
    else -> PhonePttGestureTransition(this)
}
