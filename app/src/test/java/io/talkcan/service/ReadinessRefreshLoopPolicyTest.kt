package io.talkcan.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessRefreshLoopPolicyTest {
    @Test
    fun startsRefreshLoopWhenRefreshIsAllowedAndDeviceIsNotReady() {
        val decision = ReadinessRefreshLoopPolicy.decide(
            refreshAllowed = true,
            readyForMonitor = false,
            refreshLoopActive = false,
        )

        assertEquals(ReadinessRefreshLoopDecision.StartRefreshLoop, decision)
    }

    @Test
    fun keepsActiveRefreshLoopWhileDeviceRemainsNotReady() {
        val decision = ReadinessRefreshLoopPolicy.decide(
            refreshAllowed = true,
            readyForMonitor = false,
            refreshLoopActive = true,
        )

        assertEquals(ReadinessRefreshLoopDecision.KeepCurrentLoop, decision)
    }

    @Test
    fun stopsRefreshLoopWhenDeviceBecomesReady() {
        val decision = ReadinessRefreshLoopPolicy.decide(
            refreshAllowed = true,
            readyForMonitor = true,
            refreshLoopActive = true,
        )

        assertEquals(ReadinessRefreshLoopDecision.StopRefreshLoop, decision)
    }

    @Test
    fun stopsRefreshLoopWhenRefreshIsNoLongerAllowed() {
        val decision = ReadinessRefreshLoopPolicy.decide(
            refreshAllowed = false,
            readyForMonitor = false,
            refreshLoopActive = true,
        )

        assertEquals(ReadinessRefreshLoopDecision.StopRefreshLoop, decision)
    }

    @Test
    fun doesNotStartLoopWhenRefreshIsNotAllowed() {
        val decision = ReadinessRefreshLoopPolicy.decide(
            refreshAllowed = false,
            readyForMonitor = false,
            refreshLoopActive = false,
        )

        assertEquals(ReadinessRefreshLoopDecision.KeepCurrentLoop, decision)
    }
}
