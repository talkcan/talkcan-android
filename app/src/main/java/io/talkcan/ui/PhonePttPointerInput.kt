package io.talkcan.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

internal fun Modifier.phonePttInput(
    channelId: String,
    stateProvider: () -> PhonePttGestureState,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
): Modifier = pointerInput(channelId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()

        val startTransition = startPhonePttGesture(channelId = channelId)
        onPhonePttTransition(startTransition)

        while (stateProvider().isActive) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val currentState = stateProvider()
            if (!currentState.isActive) {
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                val transition = currentState.cancelPhonePttGesture()
                onPhonePttTransition(transition)
                break
            }

            change.consume()
            val transition = if (change.pressed) {
                PhonePttGestureTransition(currentState)
            } else {
                currentState.releasePhonePttGesture()
            }
            if (transition.state != currentState || transition.commands.isNotEmpty()) {
                onPhonePttTransition(transition)
            }
            if (!change.pressed) {
                break
            }
        }
    }
}
