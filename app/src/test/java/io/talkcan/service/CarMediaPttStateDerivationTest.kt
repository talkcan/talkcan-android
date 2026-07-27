package io.talkcan.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CarMediaPttStateDerivationTest {
    @Test
    fun activeSessionPhaseDeterminesRecordingAndFinalizingState() {
        val cases = listOf(
            PttAudioSessionManager.SessionPhase.PttHeld to CarMediaPttState.Recording,
            PttAudioSessionManager.SessionPhase.TerminalWork to CarMediaPttState.Finalizing,
        )

        cases.forEach { (phase, expected) ->
            assertEquals(
                phase.name,
                expected,
                deriveCarMediaPttState(
                    phase = phase,
                    onTheRoadAvailable = true,
                ),
            )
        }
    }

    @Test
    fun idleRetentionWithNoActiveSessionRemainsReady() {
        assertEquals(
            CarMediaPttState.Ready,
            deriveCarMediaPttState(
                phase = null,
                onTheRoadAvailable = true,
            ),
        )
    }
}
