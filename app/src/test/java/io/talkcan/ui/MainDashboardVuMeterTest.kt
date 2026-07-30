package io.talkcan.ui

import org.junit.Assert.assertEquals
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

}
