package io.talkcan.ui

enum class PhonePttLockDirection {
    Left,
    Right,
}

enum class PhonePttGestureCommand {
    Press,
    Release,
}

enum class PhonePttFinishReason {
    PointerRelease,
    Stop,
    Cancelled,
    FocusLost,
    MaxDuration,
}

enum class PhonePttGestureTarget {
    MainSurface,
    ConfigButton,
}

data class PhonePttGestureTransition(
    val state: PhonePttGestureState,
    val commands: List<PhonePttGestureCommand> = emptyList(),
)

sealed interface PhonePttGestureState {
    data object Idle : PhonePttGestureState

    data class Armed(
        val channelId: String,
        val initialX: Float,
        val surfaceWidth: Float,
        val lockDirection: PhonePttLockDirection,
        val sawCapture: Boolean = false,
    ) : PhonePttGestureState

    data class Locked(
        val channelId: String,
        val initialX: Float,
        val surfaceWidth: Float,
        val lockDirection: PhonePttLockDirection,
        val sawCapture: Boolean = false,
    ) : PhonePttGestureState

    data class Finalized(
        val channelId: String,
        val finishReason: PhonePttFinishReason,
    ) : PhonePttGestureState
}

val PhonePttGestureState.activeChannelId: String?
    get() = when (this) {
        PhonePttGestureState.Idle -> null
        is PhonePttGestureState.Armed -> channelId
        is PhonePttGestureState.Locked -> channelId
        is PhonePttGestureState.Finalized -> null
    }

val PhonePttGestureState.lockDirection: PhonePttLockDirection?
    get() = when (this) {
        PhonePttGestureState.Idle -> null
        is PhonePttGestureState.Armed -> lockDirection
        is PhonePttGestureState.Locked -> lockDirection
        is PhonePttGestureState.Finalized -> null
    }

val PhonePttGestureState.isLocked: Boolean
    get() = this is PhonePttGestureState.Locked

val PhonePttGestureState.isActive: Boolean
    get() = this is PhonePttGestureState.Armed || this is PhonePttGestureState.Locked

fun startPhonePttGesture(
    target: PhonePttGestureTarget,
    channelId: String,
    initialX: Float,
    surfaceWidth: Float,
): PhonePttGestureTransition =
    if (target == PhonePttGestureTarget.MainSurface) {
        armPhonePttGesture(channelId, initialX, surfaceWidth)
    } else {
        PhonePttGestureTransition(PhonePttGestureState.Idle)
    }

fun armPhonePttGesture(
    channelId: String,
    initialX: Float,
    surfaceWidth: Float,
): PhonePttGestureTransition {
    val direction = if (initialX < surfaceWidth / 2f) PhonePttLockDirection.Right else PhonePttLockDirection.Left
    return PhonePttGestureTransition(
        state = PhonePttGestureState.Armed(
            channelId = channelId,
            initialX = initialX,
            surfaceWidth = surfaceWidth,
            lockDirection = direction,
        ),
        commands = listOf(PhonePttGestureCommand.Press),
    )
}

fun PhonePttGestureState.movePhonePttGesture(
    currentX: Float,
    lockThresholdPx: Float,
): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> {
        val crossed = when (lockDirection) {
            PhonePttLockDirection.Right -> currentX - initialX >= lockThresholdPx
            PhonePttLockDirection.Left -> initialX - currentX >= lockThresholdPx
        }
        if (crossed) {
            PhonePttGestureTransition(
                state = PhonePttGestureState.Locked(
                    channelId = channelId,
                    initialX = initialX,
                    surfaceWidth = surfaceWidth,
                    lockDirection = lockDirection,
                    sawCapture = sawCapture,
                ),
            )
        } else {
            PhonePttGestureTransition(this)
        }
    }
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.releasePhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.PointerRelease)
    is PhonePttGestureState.Locked -> PhonePttGestureTransition(this)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.stopPhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.Stop)
    is PhonePttGestureState.Locked -> finishPhonePttGesture(PhonePttFinishReason.Stop)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.cancelPhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.Cancelled)
    is PhonePttGestureState.Locked -> finishPhonePttGesture(PhonePttFinishReason.Cancelled)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.focusLostPhonePttGesture(): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> finishPhonePttGesture(PhonePttFinishReason.FocusLost)
    is PhonePttGestureState.Locked -> finishPhonePttGesture(PhonePttFinishReason.FocusLost)
    else -> PhonePttGestureTransition(this)
}

fun PhonePttGestureState.captureChangedPhonePttGesture(isCapturing: Boolean): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> when {
        isCapturing -> PhonePttGestureTransition(copy(sawCapture = true))
        sawCapture -> finishPhonePttGesture(PhonePttFinishReason.MaxDuration)
        else -> PhonePttGestureTransition(this)
    }
    is PhonePttGestureState.Locked -> when {
        isCapturing -> PhonePttGestureTransition(copy(sawCapture = true))
        sawCapture -> finishPhonePttGesture(PhonePttFinishReason.MaxDuration)
        else -> PhonePttGestureTransition(this)
    }
    else -> PhonePttGestureTransition(this)
}

private fun PhonePttGestureState.finishPhonePttGesture(reason: PhonePttFinishReason): PhonePttGestureTransition = when (this) {
    is PhonePttGestureState.Armed -> PhonePttGestureTransition(
        state = PhonePttGestureState.Finalized(channelId, reason),
        commands = listOf(PhonePttGestureCommand.Release),
    )
    is PhonePttGestureState.Locked -> PhonePttGestureTransition(
        state = PhonePttGestureState.Finalized(channelId, reason),
        commands = listOf(PhonePttGestureCommand.Release),
    )
    else -> PhonePttGestureTransition(this)
}
