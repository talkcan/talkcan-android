package io.talkcan.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

internal val PhonePttLockThreshold: Dp = 88.dp

private enum class PreArmGestureResult {
    Tap,
    Cancel,
}

internal fun Modifier.phonePttInput(
    channelId: String,
    lockThresholdPx: Float,
    onSelect: (String) -> Unit,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
): Modifier = pointerInput(channelId, lockThresholdPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val initialX = down.position.x
        val initialY = down.position.y
        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop
        var movedBeforeArmed = false

        val preArmResult = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull PreArmGestureResult.Cancel
                val dx = change.position.x - initialX
                val dy = change.position.y - initialY
                if (dx * dx + dy * dy > touchSlopSquared) {
                    movedBeforeArmed = true
                }
                if (!change.pressed) {
                    return@withTimeoutOrNull if (movedBeforeArmed) {
                        PreArmGestureResult.Cancel
                    } else {
                        PreArmGestureResult.Tap
                    }
                }
            }
        }

        when (preArmResult) {
            PreArmGestureResult.Tap -> {
                onSelect(channelId)
                return@awaitEachGesture
            }
            PreArmGestureResult.Cancel -> return@awaitEachGesture
            null -> Unit
        }

        down.consume()
        var localState = startPhonePttGesture(
            target = PhonePttGestureTarget.MainSurface,
            channelId = channelId,
            initialX = initialX,
            surfaceWidth = size.width.toFloat(),
        ).also(onPhonePttTransition).state

        while (localState.isActive) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                val transition = localState.cancelPhonePttGesture()
                onPhonePttTransition(transition)
                localState = transition.state
                break
            }

            change.consume()
            val transition = if (change.pressed) {
                localState.movePhonePttGesture(
                    currentX = change.position.x,
                    lockThresholdPx = lockThresholdPx,
                )
            } else {
                localState.releasePhonePttGesture()
            }
            if (transition.state != localState || transition.commands.isNotEmpty()) {
                onPhonePttTransition(transition)
            }
            localState = transition.state
            if (!change.pressed) break
        }
    }
}
