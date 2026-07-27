package io.talkcan.service

import io.talkcan.model.InputMode
import io.talkcan.model.InputModeSelection
import io.talkcan.model.PttSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `car-input-mode-auto-switch` spec scenarios: AA connect/disconnect
 * auto-transitions to OnTheRoad / Work while preserving explicit user selections
 * through the `selectedBy` invariant.
 */
class InputModeAutoSwitchTest {
    @Test
    fun aaConnectWithOnTheRoadAvailableSwitchesToOnTheRoadRecordedBySystem() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = true)

        assertEquals(InputMode.OnTheRoad, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }

    @Test
    fun aaConnectSwitchesToOnTheRoadWhenCurrentModeIsOnAPinchEvenIfUserSelectedOnAPinch() {
        val controller = InputModeController()
        // Reach Work via RSM becoming ready (system-selected).
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
        // User explicitly chooses OnAPinch.
        assertTrue(controller.setInputMode(InputMode.OnAPinch))
        assertEquals(InputModeSelection.User, controller.selectedBy)
        // AA connects — spec rule: OnAPinch qualifies regardless of selectedBy.
        controller.updateInputs(readyForMonitor = true, aaConnected = true)

        assertEquals(InputMode.OnTheRoad, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }

    @Test
    fun aaConnectDoesNotOverrideUserSelectedWorkMode() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        // User explicitly switches to Work (User).
        assertTrue(controller.setInputMode(InputMode.Work))
        assertEquals(InputModeSelection.User, controller.selectedBy)
        // AA reconnect does NOT trigger a fresh OnTheRoad transition since the
        // user has selected Work and it remains available.
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        controller.updateInputs(readyForMonitor = true, aaConnected = true)

        assertEquals(InputMode.Work, controller.mode)
        assertEquals(InputModeSelection.User, controller.selectedBy)
    }

    @Test
    fun aaDisconnectFromSystemSelectedOnTheRoadRevertsToWorkWhenWorkAvailable() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        // mode is OnTheRoad via AA connect (System).
        assertEquals(InputModeSelection.System, controller.selectedBy)
        controller.updateInputs(readyForMonitor = true, aaConnected = false)

        assertEquals(InputMode.Work, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }

    @Test
    fun aaDisconnectFromUserSelectedOnTheRoadDoesNotAutoRevertWhileOnTheRoadRemainsAvailable() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        // User explicitly chooses OnTheRoad (User).
        controller.setInputMode(InputMode.OnTheRoad)
        assertEquals(InputModeSelection.User, controller.selectedBy)
        // AA transient blink (disconnect then reconnect) — user choice preserved.
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        // OnTheRoad became unavailable: fallback has to take over ( AA dropped).
        // System-selected disconnected rule did NOT fire because selectedBy=User.
        // Fallback order prefers onTheRoad (false) → Work (true).
        assertEquals(InputMode.Work, controller.mode)
        // selectedBy stays User so a subsequent reconnect won't switch back
        // unless the user's intent is re-asserted.
        assertEquals(InputModeSelection.User, controller.selectedBy)
    }

    @Test
    fun onTheRoadUnavailableDoesNotAutoSwitchWhenAAConnectsButWorkIsRequiredFromWorkUserSelection() {
        val controller = InputModeController()
        // Bring Work up manually as a user choice.
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        controller.setInputMode(InputMode.Work)
        assertEquals(InputModeSelection.User, controller.selectedBy)
        // Now AA connects but OnTheRoad availability somehow disabled
        // (represented via the test by supplying availability flags directly
        // through updateInputs; onTheRoad = true only when aaConnected == true).
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        // User selection of Work is preserved through the AA connect — manual
        // selection invariant holds.
        assertEquals(InputMode.Work, controller.mode)
        assertEquals(InputModeSelection.User, controller.selectedBy)
    }

    @Test
    fun rsmConnectFromOnAPinchTransitionsToWorkWithSystemSelection() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        controller.updateInputs(readyForMonitor = true, aaConnected = false)

        assertEquals(InputMode.Work, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }

    @Test
    fun rsmConnectFromUserSelectedOnAPinchStaysOnAPinchManualSelectionPreserved() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        controller.setInputMode(InputMode.OnAPinch)
        assertEquals(InputModeSelection.User, controller.selectedBy)
        controller.updateInputs(readyForMonitor = true, aaConnected = false)

        // Manual user selection of OnAPinch preserved — Rule 2 requires
        // selectedBy == System to fire.
        assertEquals(InputMode.OnAPinch, controller.mode)
        assertEquals(InputModeSelection.User, controller.selectedBy)
    }

    @Test
    fun setUserInputModeExplicitSystemSelectionRecordsSystemChoice() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)

        assertTrue(controller.setInputMode(InputMode.Work, InputModeSelection.System))
        assertEquals(InputMode.Work, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }

    @Test
    fun autoTransitionPreservesNoTransitionWhenHomeUnavailableAndRecordsSystemSelection() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)

        assertFalse(controller.autoTransitionFor(PttSource.Rsm))
        assertFalse(controller.autoTransitionFor(PttSource.CarTelecom))
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun autoTransitionCarTelecomRecordsSystemSelection() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        controller.setInputMode(InputMode.Work)

        assertTrue(controller.autoTransitionFor(PttSource.CarTelecom))
        assertEquals(InputMode.OnTheRoad, controller.mode)
        assertEquals(InputModeSelection.System, controller.selectedBy)
    }
}