package io.talkcan.protocol

import io.talkcan.model.ClickButtonState
import io.talkcan.model.HardwareMode
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SosButtonState
import io.talkcan.model.TwoStateButton
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonStateMachineTest {
    @Test
    fun groupPressedEntersControlMode() {
        val machine = ButtonStateMachine()

        val snapshot = machine.apply(RawButtonEvent.GroupPressed, nowMillis = 0)

        assertEquals(HardwareMode.Control, snapshot.hardwareMode)
        assertEquals(TwoStateButton.Pressed, snapshot.buttons.group)
    }

    @Test
    fun pttPressedReturnsFromControlToActiveMode() {
        val machine = ButtonStateMachine()

        machine.apply(RawButtonEvent.GroupPressed, nowMillis = 0)
        val snapshot = machine.apply(RawButtonEvent.PttPressed, nowMillis = 10)

        assertEquals(HardwareMode.Active, snapshot.hardwareMode)
        assertEquals(TwoStateButton.Pressed, snapshot.buttons.ptt)
    }

    @Test
    fun sosLongPressIsDisplayedUntilRelease() {
        val machine = ButtonStateMachine()

        assertEquals(
            SosButtonState.LongPressed,
            machine.apply(RawButtonEvent.SosLongPressed, nowMillis = 0).buttons.sos,
        )
        assertEquals(
            SosButtonState.Released,
            machine.apply(RawButtonEvent.SosReleased, nowMillis = 1).buttons.sos,
        )
    }

    @Test
    fun volumeClicksExpireAfterConfiguredDuration() {
        val machine = ButtonStateMachine(clickDurationMs = 300)

        assertEquals(
            ClickButtonState.Clicked,
            machine.apply(RawButtonEvent.VolumeUpClicked, nowMillis = 1_000).buttons.volumeUp,
        )
        assertEquals(
            ClickButtonState.Clicked,
            machine.expireClicks(nowMillis = 1_299).buttons.volumeUp,
        )
        assertEquals(
            ClickButtonState.Idle,
            machine.expireClicks(nowMillis = 1_300).buttons.volumeUp,
        )
    }
}
