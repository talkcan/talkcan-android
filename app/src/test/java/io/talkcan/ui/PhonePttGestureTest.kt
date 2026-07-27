package io.talkcan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePttGestureTest {

    @Test
    fun `sanity - held phone PTT can lock inward and stop exactly once`() {
        // The gesture core models the phone-only PTT actuator. It is pure: pointer coordinates
        // enter, a new state plus service-edge commands come out. Compose pointer handling and
        // PttForegroundService dispatch stay in the imperative shell.
        val armed = armPhonePttGesture(
            channelId = "journal",
            initialX = 80f,
            surfaceWidth = 320f,
        )

        // Starting on the left side chooses an inward rightward lock direction and emits the
        // single Press edge that starts phone PTT for the channel.
        assertEquals(listOf(PhonePttGestureCommand.Press), armed.commands)
        val armedState = armed.state as PhonePttGestureState.Armed
        assertEquals("journal", armedState.channelId)
        assertEquals(PhonePttLockDirection.Right, armedState.lockDirection)
        assertTrue(armedState.isActive)
        assertFalse(armedState.isLocked)

        // Incidental drift below the lock threshold keeps recording held and emits no service edge.
        val drift = armedState.movePhonePttGesture(currentX = 120f, lockThresholdPx = 88f)
        assertEquals(emptyList<PhonePttGestureCommand>(), drift.commands)
        assertEquals(armedState, drift.state)

        // Crossing the inward threshold changes only UI gesture state: locked recording continues,
        // and finger release is no longer allowed to emit Release.
        val locked = armedState.movePhonePttGesture(currentX = 180f, lockThresholdPx = 88f)
        assertEquals(emptyList<PhonePttGestureCommand>(), locked.commands)
        assertTrue(locked.state is PhonePttGestureState.Locked)
        val releaseWhileLocked = locked.state.releasePhonePttGesture()
        assertEquals(emptyList<PhonePttGestureCommand>(), releaseWhileLocked.commands)
        assertTrue(releaseWhileLocked.state is PhonePttGestureState.Locked)

        // The explicit stop affordance is the release edge for locked PTT. Re-applying stop to the
        // finalized state is inert, which prevents duplicate release calls during recomposition or
        // repeated pointer cleanup.
        val stopped = releaseWhileLocked.state.stopPhonePttGesture()
        assertEquals(listOf(PhonePttGestureCommand.Release), stopped.commands)
        assertEquals(PhonePttFinishReason.Stop, (stopped.state as PhonePttGestureState.Finalized).finishReason)
        val duplicateStop = stopped.state.stopPhonePttGesture()
        assertEquals(emptyList<PhonePttGestureCommand>(), duplicateStop.commands)
        assertEquals(stopped.state, duplicateStop.state)
    }

    @Test
    fun `left-side press locks by sliding right`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state as PhonePttGestureState.Armed

        assertEquals(PhonePttLockDirection.Right, armed.lockDirection)
        assertTrue(armed.movePhonePttGesture(currentX = 128f, lockThresholdPx = 88f).state is PhonePttGestureState.Locked)
    }

    @Test
    fun `right-side press locks by sliding left`() {
        val armed = armPhonePttGesture("debug", initialX = 260f, surfaceWidth = 300f).state as PhonePttGestureState.Armed

        assertEquals(PhonePttLockDirection.Left, armed.lockDirection)
        assertTrue(armed.movePhonePttGesture(currentX = 172f, lockThresholdPx = 88f).state is PhonePttGestureState.Locked)
    }

    @Test
    fun `movement below threshold keeps active PTT held without release`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state as PhonePttGestureState.Armed

        val moved = armed.movePhonePttGesture(currentX = 127f, lockThresholdPx = 88f)

        assertEquals(emptyList<PhonePttGestureCommand>(), moved.commands)
        assertEquals(armed, moved.state)
        assertTrue(moved.state.isActive)
    }

    @Test
    fun `unlocked release finalizes exactly once`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state

        val released = armed.releasePhonePttGesture()
        val duplicateRelease = released.state.releasePhonePttGesture()

        assertEquals(listOf(PhonePttGestureCommand.Release), released.commands)
        assertEquals(PhonePttFinishReason.PointerRelease, (released.state as PhonePttGestureState.Finalized).finishReason)
        assertEquals(emptyList<PhonePttGestureCommand>(), duplicateRelease.commands)
        assertEquals(released.state, duplicateRelease.state)
    }

    @Test
    fun `locked release persists until explicit stop`() {
        val locked = (armPhonePttGesture("debug", initialX = 260f, surfaceWidth = 300f).state as PhonePttGestureState.Armed)
            .movePhonePttGesture(currentX = 160f, lockThresholdPx = 88f)
            .state

        val released = locked.releasePhonePttGesture()
        val stopped = released.state.stopPhonePttGesture()

        assertEquals(emptyList<PhonePttGestureCommand>(), released.commands)
        assertTrue(released.state is PhonePttGestureState.Locked)
        assertEquals(listOf(PhonePttGestureCommand.Release), stopped.commands)
        assertEquals(PhonePttFinishReason.Stop, (stopped.state as PhonePttGestureState.Finalized).finishReason)
    }

    @Test
    fun `capture end after observed capture emits one max-duration release`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state
        val capturing = armed.captureChangedPhonePttGesture(isCapturing = true).state

        val maxDuration = capturing.captureChangedPhonePttGesture(isCapturing = false)
        val duplicate = maxDuration.state.captureChangedPhonePttGesture(isCapturing = false)

        assertEquals(listOf(PhonePttGestureCommand.Release), maxDuration.commands)
        assertEquals(PhonePttFinishReason.MaxDuration, (maxDuration.state as PhonePttGestureState.Finalized).finishReason)
        assertEquals(emptyList<PhonePttGestureCommand>(), duplicate.commands)
    }

    @Test
    fun `capture false before recording does not release during ready beep`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state

        val stillArmed = armed.captureChangedPhonePttGesture(isCapturing = false)

        assertEquals(emptyList<PhonePttGestureCommand>(), stillArmed.commands)
        assertEquals(armed, stillArmed.state)
    }

    @Test
    fun `focus loss releases active phone PTT once`() {
        val armed = armPhonePttGesture("journal", initialX = 40f, surfaceWidth = 300f).state

        val focusLost = armed.focusLostPhonePttGesture()
        val duplicate = focusLost.state.focusLostPhonePttGesture()

        assertEquals(listOf(PhonePttGestureCommand.Release), focusLost.commands)
        assertEquals(PhonePttFinishReason.FocusLost, (focusLost.state as PhonePttGestureState.Finalized).finishReason)
        assertEquals(emptyList<PhonePttGestureCommand>(), duplicate.commands)
    }

    @Test
    fun `config button target does not start lock stop or release phone PTT`() {
        val configStart = startPhonePttGesture(
            target = PhonePttGestureTarget.ConfigButton,
            channelId = "journal",
            initialX = 280f,
            surfaceWidth = 320f,
        )

        assertEquals(PhonePttGestureState.Idle, configStart.state)
        assertEquals(emptyList<PhonePttGestureCommand>(), configStart.commands)
        assertEquals(emptyList<PhonePttGestureCommand>(), configStart.state.movePhonePttGesture(0f, 88f).commands)
        assertEquals(emptyList<PhonePttGestureCommand>(), configStart.state.stopPhonePttGesture().commands)
        assertEquals(emptyList<PhonePttGestureCommand>(), configStart.state.releasePhonePttGesture().commands)
    }
}
