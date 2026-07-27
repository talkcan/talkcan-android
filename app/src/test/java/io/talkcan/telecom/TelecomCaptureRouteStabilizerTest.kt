package io.talkcan.telecom

import android.telecom.CallAudioState
import org.junit.Assert.assertEquals
import org.junit.Test

class TelecomCaptureRouteStabilizerTest {
    @Test
    fun acceptableRoutePublishesReadinessOnlyAfterStabilityWindow() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)

        assertEquals(1, scheduler.pendingCount)
        assertEquals(emptyList<Boolean>(), routeChanges)

        scheduler.advanceBy(STABILITY_DELAY_MS - 1)
        assertEquals(emptyList<Boolean>(), routeChanges)

        scheduler.advanceBy(1)
        assertEquals(listOf(true), routeChanges)
    }

    @Test
    fun unacceptableRouteCancelsPendingReadiness() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(STABILITY_DELAY_MS / 2)
        stabilizer.routeChanged(acceptable = false)
        scheduler.advanceBy(STABILITY_DELAY_MS)

        assertEquals(listOf(false), routeChanges)
    }

    @Test
    fun duplicateAcceptableRouteDoesNotRestartStabilityWindow() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(STABILITY_DELAY_MS - 1)
        stabilizer.routeChanged(acceptable = true)
        scheduler.advanceBy(1)

        assertEquals(listOf(true), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun cancelSuppressesPendingReadiness() {
        val scheduler = ManualScheduler()
        val routeChanges = mutableListOf<Boolean>()
        val stabilizer = createStabilizer(scheduler, routeChanges)

        stabilizer.routeChanged(acceptable = true)
        stabilizer.cancel()
        scheduler.advanceBy(STABILITY_DELAY_MS)

        assertEquals(emptyList<Boolean>(), routeChanges)
    }

    @Test
    fun captureRoutePredicateRequiresBluetoothRouteAndExpectedDeviceIdentity() {
        data class Case(
            val name: String,
            val route: Int,
            val activeBluetoothDeviceMatchesExpected: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                name = "Expected device is active but call route is not Bluetooth",
                route = CallAudioState.ROUTE_EARPIECE,
                activeBluetoothDeviceMatchesExpected = true,
                expected = false,
            ),
            Case(
                name = "Bluetooth call route uses a different active device",
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeBluetoothDeviceMatchesExpected = false,
                expected = false,
            ),
            Case(
                name = "Bluetooth call route uses the exact expected device",
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeBluetoothDeviceMatchesExpected = true,
                expected = true,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isAcceptableTelecomCaptureRoute(
                    route = case.route,
                    activeBluetoothDeviceMatchesExpected = case.activeBluetoothDeviceMatchesExpected,
                ),
            )
        }
    }

    @Test
    fun differentActiveBluetoothDeviceIsRejectedRegardlessOfDeviceName() {
        data class Case(
            val name: String,
            val expectedDeviceName: String?,
            val activeDeviceName: String?,
        )

        val cases = listOf(
            Case(
                name = "Different devices expose the same name",
                expectedDeviceName = "Shared HFP name",
                activeDeviceName = "Shared HFP name",
            ),
            Case(
                name = "Different active device name is unreadable",
                expectedDeviceName = "Vehicle HFP",
                activeDeviceName = null,
            ),
        )

        cases.forEach { case ->
            val expectedDevice = TestBluetoothDevice(case.expectedDeviceName)
            val activeDevice = TestBluetoothDevice(case.activeDeviceName)
            val requestedDevices = mutableListOf<TestBluetoothDevice>()
            val routeChanges = mutableListOf<Boolean>()
            val scheduler = ManualScheduler()
            val controller = createRouteController(
                expectedDevice = expectedDevice,
                scheduler = scheduler,
                requestedDevices = requestedDevices,
                routeChanges = routeChanges,
            )

            controller.routeChanged(
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeDevice = activeDevice,
                supportedDevices = listOf(expectedDevice, activeDevice),
            )

            assertEquals(case.name, listOf(expectedDevice), requestedDevices)
            assertEquals(case.name, listOf(false), routeChanges)
            assertEquals(case.name, 1, scheduler.pendingCount)
        }
    }

    @Test
    fun unsupportedExpectedDeviceFailsClosedWithoutRequestingAnotherDevice() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(otherDevice),
        )

        assertEquals(emptyList<TestBluetoothDevice>(), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun ignoredExactCarRequestIsRetriedAfterConfiguredDelay() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(expectedDevice, otherDevice),
        )

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advanceBy(ROUTE_RETRY_DELAY_MS - 1)

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advanceBy(1)

        assertEquals(listOf(expectedDevice, expectedDevice), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun exactCarActivationCancelsPendingRetries() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(expectedDevice, otherDevice),
        )
        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = expectedDevice,
            supportedDevices = listOf(expectedDevice, otherDevice),
        )

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false, true), routeChanges)
        assertEquals(0, scheduler.pendingCount)

        scheduler.advanceBy(ROUTE_RETRY_DELAY_MS * 2)

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false, true), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun expectedDeviceRemovalCancelsRetriesWithoutRequestingSubstitute() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(expectedDevice, otherDevice),
        )
        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(otherDevice),
        )

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false, false), routeChanges)
        assertEquals(0, scheduler.pendingCount)

        scheduler.advanceBy(ROUTE_RETRY_DELAY_MS * 2)

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false, false), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun repeatedWrongRouteCallbacksKeepSingleRetryLoop() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        repeat(3) {
            controller.routeChanged(
                route = CallAudioState.ROUTE_BLUETOOTH,
                activeDevice = otherDevice,
                supportedDevices = listOf(expectedDevice, otherDevice),
            )
        }

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false, false, false), routeChanges)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advanceBy(ROUTE_RETRY_DELAY_MS)

        assertEquals(listOf(expectedDevice, expectedDevice), requestedDevices)
        assertEquals(listOf(false, false, false), routeChanges)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun cancelStopsAllFutureRouteRequests() {
        val expectedDevice = TestBluetoothDevice("Vehicle HFP")
        val otherDevice = TestBluetoothDevice("RSM")
        val requestedDevices = mutableListOf<TestBluetoothDevice>()
        val routeChanges = mutableListOf<Boolean>()
        val scheduler = ManualScheduler()
        val controller = createRouteController(
            expectedDevice = expectedDevice,
            scheduler = scheduler,
            requestedDevices = requestedDevices,
            routeChanges = routeChanges,
        )

        controller.routeChanged(
            route = CallAudioState.ROUTE_BLUETOOTH,
            activeDevice = otherDevice,
            supportedDevices = listOf(expectedDevice, otherDevice),
        )
        controller.cancel()

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(0, scheduler.pendingCount)

        scheduler.advanceBy(ROUTE_RETRY_DELAY_MS * 3)

        assertEquals(listOf(expectedDevice), requestedDevices)
        assertEquals(listOf(false), routeChanges)
        assertEquals(0, scheduler.pendingCount)
    }

    private fun createRouteController(
        expectedDevice: TestBluetoothDevice,
        scheduler: ManualScheduler,
        requestedDevices: MutableList<TestBluetoothDevice>,
        routeChanges: MutableList<Boolean>,
    ) = TelecomBluetoothRouteController(
        expectedDevice = expectedDevice,
        requestBluetoothAudio = requestedDevices::add,
        retryDelayMs = ROUTE_RETRY_DELAY_MS,
        postDelayed = scheduler::postDelayed,
        removeCallbacks = scheduler::removeCallbacks,
        onRouteChanged = routeChanges::add,
    )

    private fun createStabilizer(
        scheduler: ManualScheduler,
        routeChanges: MutableList<Boolean>,
    ) = TelecomCaptureRouteStabilizer(
        stabilityDelayMs = STABILITY_DELAY_MS,
        postDelayed = scheduler::postDelayed,
        removeCallbacks = scheduler::removeCallbacks,
        onRouteChanged = routeChanges::add,
    )

    private class TestBluetoothDevice(
        val name: String?,
    )

    private class ManualScheduler {
        private data class ScheduledCallback(
            val callback: Runnable,
            val dueAtMs: Long,
        )

        private val callbacks = mutableListOf<ScheduledCallback>()
        private var nowMs = 0L

        val pendingCount: Int
            get() = callbacks.size

        fun postDelayed(callback: Runnable, delayMs: Long) {
            callbacks += ScheduledCallback(callback, nowMs + delayMs)
        }

        fun removeCallbacks(callback: Runnable) {
            callbacks.removeAll { it.callback === callback }
        }

        fun advanceBy(durationMs: Long) {
            val targetMs = nowMs + durationMs
            while (true) {
                val next = callbacks
                    .filter { it.dueAtMs <= targetMs }
                    .minByOrNull { it.dueAtMs }
                    ?: break
                callbacks.remove(next)
                nowMs = next.dueAtMs
                next.callback.run()
            }
            nowMs = targetMs
        }
    }

    private companion object {
        const val STABILITY_DELAY_MS = 200L
        const val ROUTE_RETRY_DELAY_MS = 250L
    }
}
