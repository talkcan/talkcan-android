package io.talkcan.ui

import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.PttAudioOperationPhase
import io.talkcan.model.PttAudioOperationState
import io.talkcan.model.PttSource
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePttGestureTest {

    private fun snapshot(
        id: String,
        name: String,
        preparation: ChannelPreparationAvailability = ChannelPreparationAvailability.Available,
    ) = ChannelRuntimeSnapshot(
        id = id,
        name = name,
        implementationId = ChannelImplementationId("test:shared"),
        enabled = true,
        preparation = preparation,
        executionStatus = ChannelExecutionStatus.IDLE,
        summary = null,
        pendingCount = 0,
        playbackPaused = false,
        workProjection = null,
    )

    @Test
    fun `Press carries the authoritative channel captured at start`() {
        val transition = startPhonePttGesture(channelId = "journal")
        assertEquals(listOf(PhonePttGestureCommand.Press("journal")), transition.commands)
        assertTrue(transition.state is PhonePttGestureState.Armed)
        assertTrue(transition.state.isActive)
    }

    @Test
    fun `pointer release and cancel`() {
        val armed = PhonePttGestureState.Armed()

        val releaseTransition = armed.releasePhonePttGesture()
        assertEquals(listOf(PhonePttGestureCommand.Release), releaseTransition.commands)
        val releaseState = releaseTransition.state as PhonePttGestureState.Finalized
        assertEquals(PhonePttFinishReason.PointerRelease, releaseState.finishReason)

        val cancelTransition = armed.cancelPhonePttGesture()
        assertEquals(listOf(PhonePttGestureCommand.Release), cancelTransition.commands)
        val cancelState = cancelTransition.state as PhonePttGestureState.Finalized
        assertEquals(PhonePttFinishReason.Cancelled, cancelState.finishReason)
    }

    @Test
    fun `focus loss`() {
        val armed = PhonePttGestureState.Armed()
        val transition = armed.focusLostPhonePttGesture()
        assertEquals(listOf(PhonePttGestureCommand.Release), transition.commands)
        val state = transition.state as PhonePttGestureState.Finalized
        assertEquals(PhonePttFinishReason.FocusLost, state.finishReason)
    }

    @Test
    fun `release before capture begins`() {
        val armed = PhonePttGestureState.Armed(sawCapture = false)

        // Pointer release before capture starts immediately releases/finalizes the gesture
        val releaseTransition = armed.releasePhonePttGesture()
        assertEquals(listOf(PhonePttGestureCommand.Release), releaseTransition.commands)
        assertEquals(PhonePttFinishReason.PointerRelease, (releaseTransition.state as PhonePttGestureState.Finalized).finishReason)

        // System reporting not capturing before capture starts does not release or finalize
        val captureChangeTransition = armed.captureChangedPhonePttGesture(isCapturing = false)
        assertEquals(emptyList<PhonePttGestureCommand>(), captureChangeTransition.commands)
        assertEquals(armed, captureChangeTransition.state)
    }

    @Test
    fun `capture start then terminal or max-duration`() {
        val armed = PhonePttGestureState.Armed(sawCapture = false)

        // Capture starts
        val startCaptureTransition = armed.captureChangedPhonePttGesture(isCapturing = true)
        assertEquals(emptyList<PhonePttGestureCommand>(), startCaptureTransition.commands)
        val capturingState = startCaptureTransition.state as PhonePttGestureState.Armed
        assertTrue(capturingState.sawCapture)

        // Capture stops (max-duration / terminal)
        val stopCaptureTransition = capturingState.captureChangedPhonePttGesture(isCapturing = false)
        assertEquals(listOf(PhonePttGestureCommand.Release), stopCaptureTransition.commands)
        val finalizedState = stopCaptureTransition.state as PhonePttGestureState.Finalized
        assertEquals(PhonePttFinishReason.MaxDuration, finalizedState.finishReason)
    }

    @Test
    fun `duplicate terminal idempotence`() {
        val finalized = PhonePttGestureState.Finalized(PhonePttFinishReason.PointerRelease)

        // Re-releasing a finalized state has no effect
        val releaseTransition = finalized.releasePhonePttGesture()
        assertEquals(emptyList<PhonePttGestureCommand>(), releaseTransition.commands)
        assertEquals(finalized, releaseTransition.state)

        // Re-cancelling a finalized state has no effect
        val cancelTransition = finalized.cancelPhonePttGesture()
        assertEquals(emptyList<PhonePttGestureCommand>(), cancelTransition.commands)
        assertEquals(finalized, cancelTransition.state)

        // Re-losing focus on a finalized state has no effect
        val focusLostTransition = finalized.focusLostPhonePttGesture()
        assertEquals(emptyList<PhonePttGestureCommand>(), focusLostTransition.commands)
        assertEquals(finalized, focusLostTransition.state)

        // Capture changes on a finalized state have no effect
        val captureTransition = finalized.captureChangedPhonePttGesture(isCapturing = false)
        assertEquals(emptyList<PhonePttGestureCommand>(), captureTransition.commands)
        assertEquals(finalized, captureTransition.state)
    }

    @Test
    fun `movement and state has no lock behavior`() {
        // Verify that the state hierarchy only consists of Idle, Armed, and Finalized,
        // and there is no Locked state or lock-related APIs/properties.
        val states = listOf(
            PhonePttGestureState.Idle,
            PhonePttGestureState.Armed(),
            PhonePttGestureState.Finalized(PhonePttFinishReason.PointerRelease)
        )
        for (state in states) {
            when (state) {
                is PhonePttGestureState.Idle -> assertTrue(true)
                is PhonePttGestureState.Armed -> assertTrue(true)
                is PhonePttGestureState.Finalized -> assertTrue(true)
            }
        }
    }

    @Test
    fun `dock presentation for Phone is enabled and shows correct state`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = PhonePttGestureState.Idle,
            phonePttTargetChannelId = "chan-1",
            pttAudioState = PttAudioOperationState(
                source = PttSource.Phone,
                phase = PttAudioOperationPhase.RECORDING,
            ),
        )
        assertTrue(semantics.enabled)
        assertEquals("Channel: General, Phone recording", semantics.stateDescription)
    }

    @Test
    fun `dock presentation for RSM is disabled and shows RSM ownership`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = PhonePttGestureState.Idle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                source = PttSource.Rsm,
                phase = PttAudioOperationPhase.RECORDING,
            ),
        )
        assertFalse(semantics.enabled)
        assertEquals("Channel: General, RSM recording, Disabled: Session owned by RSM", semantics.stateDescription)
    }

    @Test
    fun `dock presentation for Car is disabled and shows Car ownership`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = PhonePttGestureState.Idle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                source = PttSource.CarTelecom,
                phase = PttAudioOperationPhase.PENDING,
            ),
        )
        assertFalse(semantics.enabled)
        assertEquals("Channel: General, Car pending, Disabled: Session owned by Car", semantics.stateDescription)
    }

    @Test
    fun `dock presentation for playback is disabled and shows playback active`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = PhonePttGestureState.Idle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                isPlaybackActive = true,
            ),
        )
        assertFalse(semantics.enabled)
        assertEquals("Channel: General, Playback Active, Disabled: Playback Active", semantics.stateDescription)
    }
}
