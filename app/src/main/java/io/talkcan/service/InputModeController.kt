package io.talkcan.service

import io.talkcan.model.InputMode
import io.talkcan.model.InputModeAvailability
import io.talkcan.model.InputModeSelection
import io.talkcan.model.PttSource

/**
 * State machine for [InputMode].
 *
 * Holds the current [mode], [availability], and [selectedBy] (who chose the
 * current mode — user or system auto-switch per `car-input-mode-auto-switch`),
 * and applies the transition rules defined in the `input-mode` specification
 * plus the Android Auto presence rules (D8):
 *
 * 1. Android Auto connects → OnTheRoad when current mode is OnAPinch or
 *    system-selected (so a user-chosen non-OnAPinch mode is preserved).
 * 2. RSM connects+bonds from OnAPinch → Work (stays OnTheRoad if already there);
 *    system-initiated only.
 * 3. OnTheRoad disconnect → Work if RSM bonded, else OnAPinch; reverts only
 *    when the mode was system-selected.
 * 4. Work disconnect → OnTheRoad if AA connected, else OnAPinch; system-initiated only.
 * 5. User selects any available mode → that mode (rules re-assert on next
 *    device event with the [selectedBy] invariant driving whether auto-switch
 *    fires — see spec scenario "Manual user selection is preserved across auto
 *    transitions").
 *
 * Actuator auto-transition:
 * - Rsm → Work, Phone → OnAPinch, CarTelecom → OnTheRoad.
 * - Transition only occurs if the home mode is available.
 *
 * Mode-exclusive gating:
 * - Only one capture at a time is enforced by [PttForegroundService.activePttSession].
 */
internal class InputModeController {
    var mode: InputMode = InputMode.OnAPinch
        private set

    var selectedBy: InputModeSelection = InputModeSelection.System
        private set

    var availability: InputModeAvailability = InputModeAvailability()
        private set

    private var lastReadyForMonitor = false
    private var lastAaConnected = false

    /**
     * Update availability and apply transition rules based on changed inputs.
     *
     * Call this whenever `connection.readyForMonitor` or `AndroidAutoPresenceBus`
     * state changes.
     *
     * @param readyForMonitor true when permissions + BT + RSM bonded + SPP connected + SCO available.
     * @param aaConnected true when at least one media browser client is connected.
     */
    fun updateInputs(readyForMonitor: Boolean, aaConnected: Boolean) {
        availability = InputModeAvailability(
            work = readyForMonitor,
            onTheRoad = aaConnected,
            onAPinch = true,
        )

        val rsmBecameReady = readyForMonitor && !lastReadyForMonitor
        val rsmBecameNotReady = !readyForMonitor && lastReadyForMonitor
        val aaBecameConnected = aaConnected && !lastAaConnected
        val aaBecameDisconnected = !aaConnected && lastAaConnected

        // Apply transition rules on edge events.
        when {
            // Rule 1: AA connects → OnTheRoad (from any mode when current is OnAPinch or system-selected).
            aaBecameConnected && availability.onTheRoad &&
                (mode == InputMode.OnAPinch || selectedBy == InputModeSelection.System) -> {
                mode = InputMode.OnTheRoad
                selectedBy = InputModeSelection.System
            }
            // Rule 3: OnTheRoad disconnect → Work if RSM bonded, else OnAPinch. Reverts only when system-selected.
            aaBecameDisconnected && mode == InputMode.OnTheRoad &&
                selectedBy == InputModeSelection.System -> {
                mode = if (readyForMonitor && availability.work) InputMode.Work else InputMode.OnAPinch
                selectedBy = InputModeSelection.System
            }
            // Rule 2: RSM connects from OnAPinch → Work (stays OnTheRoad if already there). System-initiated only.
            rsmBecameReady && mode == InputMode.OnAPinch && availability.work &&
                selectedBy == InputModeSelection.System -> {
                mode = InputMode.Work
                selectedBy = InputModeSelection.System
            }
            // Rule 4: Work disconnect → OnTheRoad if AA connected, else OnAPinch. System-initiated only.
            rsmBecameNotReady && mode == InputMode.Work &&
                selectedBy == InputModeSelection.System -> {
                mode = if (aaConnected && availability.onTheRoad) InputMode.OnTheRoad else InputMode.OnAPinch
                selectedBy = InputModeSelection.System
            }
        }

        // If current mode became unavailable (e.g. Work lost SCO while in Work,
        // or OnTheRoad lost AA), transition to a safe mode. selectedBy is
        // preserved so a future availability change can re-assert the user's
        // previous choice if still relevant.
        if (!availability.isAvailable(mode)) {
            mode = fallbackMode()
        }

        lastReadyForMonitor = readyForMonitor
        lastAaConnected = aaConnected
    }

    /**
     * User selects a mode. Only transitions if the mode is available. Records
     * [InputModeSelection.User] so future auto-switch events do not override
     * the choice (spec `car-input-mode-auto-switch` "Manual user selection is
     * preserved across auto transitions").
     *
     * @return true if the transition was applied.
     */
    fun setInputMode(requested: InputMode): Boolean =
        setInputMode(requested, InputModeSelection.User)

    /**
     * Variant of [setInputMode] that records the selection source. Used by the
     * AA auto-switch tests and by [autoTransitionFor]; external callers should
     * prefer the [setInputMode] form (which defaults to
     * [InputModeSelection.User]) to mark explicit user selections, while
     * System-set transitions propagate from [updateInputs]/[autoTransitionFor].
     *
     * @return true if the transition was applied.
     */
    fun setInputMode(requested: InputMode, by: InputModeSelection): Boolean {
        if (!availability.isAvailable(requested)) return false
        mode = requested
        selectedBy = by
        return true
    }

    /**
     * Auto-transition to the home mode for the given [source] actuator.
     * Used before dispatching a PTT press so the route resolution matches
     * the actuator's natural mode. Sets [selectedBy] = [InputModeSelection.System]
     * since the source of the transition is the system actuator, not the user.
     *
     * @return true if the transition succeeded (mode is available).
     */
    fun autoTransitionFor(source: PttSource): Boolean {
        val homeMode = when (source) {
            PttSource.Rsm -> InputMode.Work
            PttSource.Phone -> InputMode.OnAPinch
            PttSource.CarTelecom -> InputMode.OnTheRoad
        }
        if (!availability.isAvailable(homeMode)) return false
        mode = homeMode
        selectedBy = InputModeSelection.System
        return true
    }

    private fun fallbackMode(): InputMode = when {
        availability.onTheRoad -> InputMode.OnTheRoad
        availability.work -> InputMode.Work
        availability.onAPinch -> InputMode.OnAPinch
        else -> InputMode.OnAPinch
    }
}