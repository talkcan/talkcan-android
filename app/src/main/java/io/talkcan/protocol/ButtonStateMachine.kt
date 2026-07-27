package io.talkcan.protocol

import io.talkcan.model.ButtonStates
import io.talkcan.model.ClickButtonState
import io.talkcan.model.HardwareMode
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SosButtonState
import io.talkcan.model.TwoStateButton

class ButtonStateMachine(
    private val clickDurationMs: Long = 300,
) {
    var hardwareMode: HardwareMode = HardwareMode.Active
        private set

    var buttons: ButtonStates = ButtonStates()
        private set

    private var volumeUpExpiresAt: Long? = null
    private var volumeDownExpiresAt: Long? = null

    fun apply(event: RawButtonEvent, nowMillis: Long): Snapshot {
        expireClicks(nowMillis)
        when (event) {
            RawButtonEvent.PttPressed -> {
                if (hardwareMode == HardwareMode.Control) hardwareMode = HardwareMode.Active
                buttons = buttons.copy(ptt = TwoStateButton.Pressed)
            }

            RawButtonEvent.PttReleased -> buttons = buttons.copy(ptt = TwoStateButton.Released)
            RawButtonEvent.SosPressed -> buttons = buttons.copy(sos = SosButtonState.Pressed)
            RawButtonEvent.SosReleased -> buttons = buttons.copy(sos = SosButtonState.Released)
            RawButtonEvent.SosLongPressed -> buttons = buttons.copy(sos = SosButtonState.LongPressed)
            RawButtonEvent.GroupPressed -> {
                hardwareMode = HardwareMode.Control
                buttons = buttons.copy(group = TwoStateButton.Pressed)
            }

            RawButtonEvent.GroupReleased -> buttons = buttons.copy(group = TwoStateButton.Released)
            RawButtonEvent.VolumeUpClicked -> {
                buttons = buttons.copy(volumeUp = ClickButtonState.Clicked)
                volumeUpExpiresAt = nowMillis + clickDurationMs
            }

            RawButtonEvent.VolumeDownClicked -> {
                buttons = buttons.copy(volumeDown = ClickButtonState.Clicked)
                volumeDownExpiresAt = nowMillis + clickDurationMs
            }
        }

        return snapshot()
    }

    fun expireClicks(nowMillis: Long): Snapshot {
        val upExpires = volumeUpExpiresAt
        if (upExpires != null && nowMillis >= upExpires) {
            buttons = buttons.copy(volumeUp = ClickButtonState.Idle)
            volumeUpExpiresAt = null
        }

        val downExpires = volumeDownExpiresAt
        if (downExpires != null && nowMillis >= downExpires) {
            buttons = buttons.copy(volumeDown = ClickButtonState.Idle)
            volumeDownExpiresAt = null
        }

        return snapshot()
    }

    private fun snapshot(): Snapshot = Snapshot(hardwareMode, buttons)

    data class Snapshot(
        val hardwareMode: HardwareMode,
        val buttons: ButtonStates,
    )
}
