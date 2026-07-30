package io.talkcan.ui

import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.PttAudioOperationPhase
import io.talkcan.model.PttAudioOperationState
import io.talkcan.model.PttSource
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePttDockSemanticsTest {

    private val gestureIdle = PhonePttGestureState.Idle

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
    fun `idle ready dock provides correct content and state description`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, Ready", semantics.stateDescription)
        assertTrue(semantics.enabled)
    }

    @Test
    fun `no-selection dock is disabled and provides choose-channel content description`() {
        val semantics = phonePttDockSemantics(
            activeChannel = null,
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(),
        )

        assertEquals("choose-channel", semantics.contentDescription)
        assertEquals("No active channel, No active channel, Disabled: No active channel", semantics.stateDescription)
        assertFalse(semantics.enabled)
    }

    @Test
    fun `playback active disables the dock and announces playback state`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                isPlaybackActive = true,
            ),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, Playback Active, Disabled: Playback Active", semantics.stateDescription)
        assertFalse(semantics.enabled)
    }

    @Test
    fun `RSM recording disables dock and announces RSM ownership`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                source = PttSource.Rsm,
                phase = PttAudioOperationPhase.RECORDING,
            ),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, RSM recording, Disabled: Session owned by RSM", semantics.stateDescription)
        assertFalse(semantics.enabled)
    }

    @Test
    fun `Car pending disables dock and announces Car ownership`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(
                source = PttSource.CarTelecom,
                phase = PttAudioOperationPhase.PENDING,
            ),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, Car pending, Disabled: Session owned by Car", semantics.stateDescription)
        assertFalse(semantics.enabled)
    }

    @Test
    fun `Phone recording is enabled and announces Phone recording`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot("chan-1", "General"),
            phonePttGesture = gestureIdle, // gesture state doesn't affect semantics enablement
            phonePttTargetChannelId = "chan-1",
            pttAudioState = PttAudioOperationState(
                source = PttSource.Phone,
                phase = PttAudioOperationPhase.RECORDING,
            ),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, Phone recording", semantics.stateDescription)
        assertTrue(semantics.enabled)
    }

    @Test
    fun `active channel unavailable remains touch-enabled but announces channel unavailable`() {
        val semantics = phonePttDockSemantics(
            activeChannel = snapshot(
                id = "chan-1",
                name = "General",
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeReadiness("Not ready")),
            ),
            phonePttGesture = gestureIdle,
            phonePttTargetChannelId = null,
            pttAudioState = PttAudioOperationState(),
        )

        assertEquals("Talk to General", semantics.contentDescription)
        assertEquals("Channel: General, Channel Unavailable", semantics.stateDescription)
        assertTrue(semantics.enabled)
    }
}
