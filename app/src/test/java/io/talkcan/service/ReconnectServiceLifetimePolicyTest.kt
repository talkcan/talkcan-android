package io.talkcan.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the service-lifetime decision introduced by the
 * fix-background-rsm-service-lifetime change: a [ReconnectBlockReason] that
 * reflects a recoverable environmental problem must keep the monitoring
 * service alive (foreground + started), while a reason that signals the user
 * explicitly stopped monitoring must tear it down. The production rule lives
 * in [shouldRetainMonitoringService]; a flip of its single condition would let
 * these tests catch it.
 */
class ReconnectServiceLifetimePolicyTest {
    @Test
    fun missingPermissionsRetainsMonitoringService() {
        assertTrue(
            "MissingPermissions must keep the monitoring service alive so the user can grant the permission and resume without relaunching",
            shouldRetainMonitoringService(ReconnectBlockReason.MissingPermissions),
        )
    }

    @Test
    fun bluetoothDisabledRetainsMonitoringService() {
        assertTrue(
            "BluetoothDisabled must keep the monitoring service alive so re-enabling Bluetooth can resume monitoring",
            shouldRetainMonitoringService(ReconnectBlockReason.BluetoothDisabled),
        )
    }

    @Test
    fun targetUnavailableRetainsMonitoringService() {
        assertTrue(
            "TargetUnavailable must keep the monitoring service alive so a reappearing target can be reconnected to",
            shouldRetainMonitoringService(ReconnectBlockReason.TargetUnavailable),
        )
    }

    @Test
    fun monitoringNotRequestedTearsDownMonitoringService() {
        assertFalse(
            "MonitoringNotRequested is an explicit disconnect and must allow the service to leave foreground and stopSelf",
            shouldRetainMonitoringService(ReconnectBlockReason.MonitoringNotRequested),
        )
    }

    @Test
    fun explicitSerialDisconnectDefersShutdownUntilNoPttSessionRemains() {
        data class Case(
            val name: String,
            val serialDisconnectPending: Boolean,
            val monitoringRequested: Boolean,
            val hasActivePttSession: Boolean,
            val shouldStop: Boolean,
        )

        listOf(
            Case(
                "pending disconnect retains active phone or car input",
                serialDisconnectPending = true,
                monitoringRequested = false,
                hasActivePttSession = true,
                shouldStop = false,
            ),
            Case(
                "active reconnect monitoring retains the service",
                serialDisconnectPending = true,
                monitoringRequested = true,
                hasActivePttSession = false,
                shouldStop = false,
            ),
            Case(
                "no explicit serial disconnect does not stop the service",
                serialDisconnectPending = false,
                monitoringRequested = false,
                hasActivePttSession = false,
                shouldStop = false,
            ),
            Case(
                "terminal completion after explicit disconnect stops the service",
                serialDisconnectPending = true,
                monitoringRequested = false,
                hasActivePttSession = false,
                shouldStop = true,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.shouldStop,
                shouldStopAfterSerialDisconnect(
                    serialDisconnectPending = case.serialDisconnectPending,
                    monitoringRequested = case.monitoringRequested,
                    hasActivePttSession = case.hasActivePttSession,
                ),
            )
        }
    }
}