package io.talkcan.ui

import io.talkcan.model.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDashboardVuMeterTest {

    @Test
    fun `meter is present with the live level while capturing`() {
        val state = dashboardVuMeterState(isCapturing = true, level = 0.42f)
        assertTrue("meter should be present while capturing", state.isPresent)
        assertEquals(0.42f, state.level, 1e-4f)
    }

    @Test
    fun `meter is present and reports zero level while idle`() {
        val state = dashboardVuMeterState(isCapturing = false, level = 0.42f)
        assertTrue("meter should remain present while idle", state.isPresent)
        assertEquals("idle meter reports zero level (no stale value leaks into standby rendering)",
            0f, state.level, 1e-4f)
    }

    @Test
    fun `meter reflects a changing level while capturing`() {
        val first = dashboardVuMeterState(isCapturing = true, level = 0.1f)
        val second = dashboardVuMeterState(isCapturing = true, level = 0.7f)
        assertTrue(first.isPresent)
        assertTrue(second.isPresent)
        assertEquals(0.1f, first.level, 1e-4f)
        assertEquals(0.7f, second.level, 1e-4f)
    }

    @Test
    fun `standby meter state is the same regardless of the last seen level`() {
        val fromLow = dashboardVuMeterState(isCapturing = false, level = 0.0f)
        val fromHigh = dashboardVuMeterState(isCapturing = false, level = 0.95f)
        assertEquals(fromLow, fromHigh)
        assertTrue(fromLow.isPresent)
    }

    @Test
    fun `available work tile tap selects mode`() {
        assertEquals(
            DashboardModeTileAction.SelectMode,
            dashboardModeTileTapAction(InputMode.Work, isAvailable = true),
        )
    }

    @Test
    fun `unavailable work tile tap opens rsm setup`() {
        assertEquals(
            DashboardModeTileAction.OpenRsmSetup,
            dashboardModeTileTapAction(InputMode.Work, isAvailable = false),
        )
    }

    @Test
    fun `car tap selects only when available while long press always opens car setup`() {
        val cases = listOf(
            true to DashboardModeTileAction.SelectMode,
            false to DashboardModeTileAction.Ignore,
        )

        cases.forEach { (isAvailable, expectedTapAction) ->
            val tapAction = dashboardModeTileTapAction(InputMode.OnTheRoad, isAvailable)
            val longPressAction = dashboardModeTileLongPressAction(InputMode.OnTheRoad)

            assertEquals(expectedTapAction, tapAction)
            assertEquals(DashboardModeTileAction.OpenCarSetup, longPressAction)
            assertNotEquals(
                "CAR long press must not dispatch mode selection when isAvailable=$isAvailable",
                DashboardModeTileAction.SelectMode,
                longPressAction,
            )
            assertNotEquals(
                "CAR long press must remain distinct from its tap action when isAvailable=$isAvailable",
                tapAction,
                longPressAction,
            )
        }
    }

    @Test
    fun `car setup intent dispatches only car navigation`() {
        val dispatched = mutableListOf<String>()

        dispatchDashboardModeTileAction(
            action = DashboardModeTileAction.OpenCarSetup,
            mode = InputMode.OnTheRoad,
            onModeSelected = { dispatched += "select mode" },
            onRsmSetupRequested = { dispatched += "open RSM setup" },
            onCarSetupRequested = { dispatched += "open car setup" },
        )

        assertEquals(listOf("open car setup"), dispatched)
    }

    @Test
    fun `work tile long press opens rsm setup`() {
        assertEquals(
            DashboardModeTileAction.OpenRsmSetup,
            dashboardModeTileLongPressAction(InputMode.Work),
        )
    }

    @Test
    fun `phone tile long press is ignored`() {
        assertEquals(
            DashboardModeTileAction.Ignore,
            dashboardModeTileLongPressAction(InputMode.OnAPinch),
        )
    }
}
