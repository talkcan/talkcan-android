package io.talkcan.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundServiceCoordinatorTest {
    @Test
    fun `repeated monitoring start commands use one foreground notification transition and one readiness loop`() = runTest {
        val fixture = Fixture(scope = backgroundScope, refreshIntervalMs = 37L)

        fixture.coordinator.onStartCommand(monitoringRequested = true, startId = 101)
        fixture.coordinator.ensureForeground()
        fixture.coordinator.onStartCommand(monitoringRequested = true, startId = 102)
        runCurrent()

        advanceTimeBy(fixture.refreshIntervalMs)
        runCurrent()
        assertEquals(listOf("start", "refresh"), fixture.effects)

        advanceTimeBy(fixture.refreshIntervalMs)
        runCurrent()
        assertEquals(
            "Repeated foreground requests must retain one loop owner, not add a second refresh per interval",
            listOf("start", "refresh", "refresh"),
            fixture.effects,
        )

        fixture.coordinator.stopReadinessRefreshLoop()
    }

    @Test
    fun `explicit serial disconnect retains monitoring and terminal PTT work then stops after both clear`() = runTest {
        val fixture = Fixture(scope = backgroundScope)
        fixture.coordinator.ensureForeground()
        fixture.coordinator.onSerialDisconnectPendingChanged(pending = true)

        fixture.coordinator.reevaluateSerialDisconnectShutdown()
        assertEquals(
            "Monitoring retention must keep the existing foreground notification active",
            listOf("start"),
            fixture.effects,
        )

        fixture.monitoringRequested = false
        fixture.hasActivePttSession = true
        fixture.coordinator.reevaluateSerialDisconnectShutdown()
        assertEquals(
            "Terminal PTT work must defer explicit-disconnect teardown after monitoring clears",
            listOf("start"),
            fixture.effects,
        )

        fixture.hasActivePttSession = false
        fixture.coordinator.onPttTerminalCompleted()
        fixture.coordinator.reevaluateSerialDisconnectShutdown()
        assertEquals(
            "Terminal completion must remove the notification and stop the service exactly once",
            listOf("start", "stop", "stopSelf"),
            fixture.effects,
        )
    }

    @Test
    fun `non-monitoring start command acknowledges then repeated stops remove the notification once`() = runTest {
        val fixture = Fixture(scope = backgroundScope)

        fixture.coordinator.onStartCommand(monitoringRequested = false, startId = 73)
        fixture.coordinator.stopForegroundIfNeeded()
        fixture.coordinator.stopForegroundIfNeeded()

        assertEquals(
            "A terminal start command and later duplicate stop requests must not create another notification transition",
            listOf("start", "stop", "stopSelf:73"),
            fixture.effects,
        )
    }

    private class Fixture(
        scope: CoroutineScope,
        val refreshIntervalMs: Long = 37L,
    ) {
        val effects = mutableListOf<String>()
        var monitoringRequested = true
        var readyForMonitor = false
        var hasActivePttSession = false

        val coordinator = ForegroundServiceCoordinator(
            scope = scope,
            startForeground = {
                effects += "start"
                true
            },
            stopForeground = { effects += "stop" },
            stopSelf = { startId -> effects += "stopSelf${startId?.let { ":$it" }.orEmpty()}" },
            refreshReadiness = { effects += "refresh" },
            monitoringRequested = { monitoringRequested },
            readyForMonitor = { readyForMonitor },
            hasActivePttSession = { hasActivePttSession },
            refreshIntervalMs = refreshIntervalMs,
        )
    }
}
