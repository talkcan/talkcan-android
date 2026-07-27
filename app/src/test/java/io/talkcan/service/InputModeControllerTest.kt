package io.talkcan.service

import io.talkcan.model.InputMode
import io.talkcan.model.InputModeAvailability
import io.talkcan.model.PttSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModeControllerTest {

    @Test
    fun aaConnectTransitionsToOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
        assertTrue(controller.availability.onTheRoad)
    }

    @Test
    fun rsmConnectFromOnAPinchTransitionsToWork() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun rsmConnectFromOnTheRoadStaysOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun logicalRsmReadinessKeepsWorkSelectableAlongsideCarAvailability() {
        val controller = InputModeController()

        controller.updateInputs(readyForMonitor = true, aaConnected = true)

        assertEquals(
            InputModeAvailability(work = true, onTheRoad = true, onAPinch = true),
            controller.availability,
        )
        assertTrue(controller.setInputMode(InputMode.Work))
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun rsmAutoTransitionRejectedWhenLogicalHfpReadinessAbsent() {
        val controller = InputModeController()

        controller.updateInputs(readyForMonitor = false, aaConnected = true)

        assertFalse(controller.availability.work)
        assertTrue(controller.availability.onTheRoad)
        assertFalse(controller.autoTransitionFor(PttSource.Rsm))
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun aaDisconnectFromOnTheRoadWithRsmGoesToWork() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun aaDisconnectFromOnTheRoadWithoutRsmGoesToOnAPinch() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun rsmDisconnectFromWorkWithAaGoesToOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
        controller.updateInputs(readyForMonitor = false, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun rsmDisconnectFromWorkWithoutAaGoesToOnAPinch() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun userSelectsAvailableMode() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertTrue(controller.setInputMode(InputMode.Work))
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun userSelectsUnavailableModeRejected() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertFalse(controller.setInputMode(InputMode.Work))
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun ruleReassertsAfterUserOverrideOnDeviceEvent() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
        assertTrue(controller.setInputMode(InputMode.OnAPinch))
        assertEquals(InputMode.OnAPinch, controller.mode)
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun autoTransitionRsmToWork() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        controller.setInputMode(InputMode.OnAPinch)
        assertTrue(controller.autoTransitionFor(PttSource.Rsm))
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun autoTransitionPhoneToOnAPinch() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertTrue(controller.autoTransitionFor(PttSource.Phone))
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun autoTransitionCarTelecomToOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        controller.setInputMode(InputMode.Work)
        assertTrue(controller.autoTransitionFor(PttSource.CarTelecom))
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun autoTransitionRejectedWhenHomeModeUnavailable() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertFalse(controller.autoTransitionFor(PttSource.Rsm))
        assertFalse(controller.autoTransitionFor(PttSource.CarTelecom))
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun autoTransitionPhoneAlwaysAvailable() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertTrue(controller.autoTransitionFor(PttSource.Phone))
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun currentModeUnavailableFallsBackToSafeMode() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
        controller.updateInputs(readyForMonitor = false, aaConnected = false)
        assertEquals(InputMode.OnAPinch, controller.mode)
    }

    @Test
    fun initialLaunchWithRsmConnectedSetsWork() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = false)
        assertEquals(InputMode.Work, controller.mode)
    }

    @Test
    fun initialLaunchWithAaConnectedSetsOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = false, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
    }

    @Test
    fun initialLaunchWithBothConnectedPrefersOnTheRoad() {
        val controller = InputModeController()
        controller.updateInputs(readyForMonitor = true, aaConnected = true)
        assertEquals(InputMode.OnTheRoad, controller.mode)
        assertTrue(controller.availability.work)
    }
}