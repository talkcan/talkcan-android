package io.talkcan.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomCarPttLifecycleTest {
    @Test
    fun startRequestWaitsForRoute() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        val started = lifecycle.startRequest(nowMs = 1_000)

        assertTrue(started)
        assertEquals(TelecomCarPttLifecycle.State.WaitingForRoute, lifecycle.currentState)
        assertEquals(emptyList<String>(), callbacks.events)
    }

    @Test
    fun bluetoothRouteStartsCapture() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.routeChanged(acceptable = true)

        assertEquals(TelecomCarPttLifecycle.State.Recording, lifecycle.currentState)
        assertEquals(listOf("start"), callbacks.events)
    }

    @Test
    fun telecomRouteAloneDoesNotStartCaptureBeforeNonTelecomReadiness() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.routeChanged(
            TelecomCarPttLifecycle.ReadinessFacts(
                telecomRouteAcceptable = true,
                nonTelecomRouteReady = false,
                hfpPrimeReady = true,
            ),
        )

        assertEquals(TelecomCarPttLifecycle.State.WaitingForRoute, lifecycle.currentState)
        assertEquals(emptyList<String>(), callbacks.events)

        lifecycle.routeChanged(
            TelecomCarPttLifecycle.ReadinessFacts(
                telecomRouteAcceptable = true,
                nonTelecomRouteReady = true,
                hfpPrimeReady = true,
            ),
        )

        assertEquals(TelecomCarPttLifecycle.State.Recording, lifecycle.currentState)
        assertEquals(listOf("start"), callbacks.events)
    }

    @Test
    fun carHfpPrimeFailureDoesNotStartCapture() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.routeChanged(
            TelecomCarPttLifecycle.ReadinessFacts(
                telecomRouteAcceptable = true,
                nonTelecomRouteReady = true,
                hfpPrimeReady = false,
            ),
        )

        assertEquals(TelecomCarPttLifecycle.State.WaitingForRoute, lifecycle.currentState)
        assertEquals(emptyList<String>(), callbacks.events)
    }

    @Test
    fun routeTimeoutDisconnectsWithoutStartingCapture() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks, routeTimeoutMs = 500)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.checkTimeout(nowMs = 1_500)

        assertEquals(TelecomCarPttLifecycle.State.Released, lifecycle.currentState)
        lifecycle.routeChanged(
            TelecomCarPttLifecycle.ReadinessFacts(
                telecomRouteAcceptable = true,
                nonTelecomRouteReady = true,
                hfpPrimeReady = true,
            ),
        )
        assertEquals(TelecomCarPttLifecycle.State.Released, lifecycle.currentState)
        assertEquals(listOf("timeout"), callbacks.events)
    }

    @Test
    fun activeCallDisconnectStopsRecordingBeforeConnectionEnded() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.routeChanged(acceptable = true)
        lifecycle.disconnect()

        assertEquals(TelecomCarPttLifecycle.State.Released, lifecycle.currentState)
        assertEquals(
            listOf("start", "stop", "disconnect"),
            callbacks.events,
        )
    }

    @Test
    fun preCaptureDisconnectCancelsWithoutTerminalRecording() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.disconnect()

        assertEquals(TelecomCarPttLifecycle.State.Released, lifecycle.currentState)
        assertEquals(
            listOf("disconnect"),
            callbacks.events,
        )
    }


    @Test
    fun abortReleasesActiveCapture() {
        val callbacks = RecordingCallbacks()
        val lifecycle = TelecomCarPttLifecycle(callbacks)

        lifecycle.startRequest(nowMs = 1_000)
        lifecycle.routeChanged(acceptable = true)
        lifecycle.abort()

        assertEquals(TelecomCarPttLifecycle.State.Released, lifecycle.currentState)
        assertEquals(listOf("start", "stop", "abort"), callbacks.events)
    }

    @Test
    fun secondStartRequestIsRejectedWhileWaiting() {
        val lifecycle = TelecomCarPttLifecycle(RecordingCallbacks())

        assertTrue(lifecycle.startRequest(nowMs = 1_000))
        assertFalse(lifecycle.startRequest(nowMs = 1_100))
    }

    private class RecordingCallbacks : TelecomCarPttLifecycle.Callbacks {
        val events = mutableListOf<String>()
        override fun onCaptureStart() { events += "start" }
        override fun onCaptureStop() { events += "stop" }
        override fun onRouteTimeout() { events += "timeout" }
        override fun onDisconnected() { events += "disconnect" }
        override fun onAborted() { events += "abort" }
    }
}
