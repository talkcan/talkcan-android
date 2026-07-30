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
        var gestureState = startTransition.state

        while (gestureState.isActive) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val observedState = stateProvider()
            if (observedState is PhonePttGestureState.Armed) {
                gestureState = observedState
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                val transition = gestureState.cancelPhonePttGesture()
                onPhonePttTransition(transition)
                break
            }

            change.consume()
            val previousState = gestureState
            val transition = if (change.pressed) {
                PhonePttGestureTransition(previousState)
            } else {
                previousState.releasePhonePttGesture()
            }
            gestureState = transition.state
            if (transition.state != previousState || transition.commands.isNotEmpty()) {
                onPhonePttTransition(transition)
            }
            if (!change.pressed) {
                break
            }
        }
    }
}
