package io.talkcan.telecom

internal class TelecomCarPttLifecycle(
    private val callbacks: Callbacks,
    private val routeTimeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
) {
    private var state: State = State.Idle
    private var deadlineAtMs: Long = 0L

    val currentState: State
        get() = state

    fun startRequest(nowMs: Long): Boolean {
        if (state != State.Idle) return false
        state = State.WaitingForRoute
        deadlineAtMs = nowMs + routeTimeoutMs
        return true
    }

    fun routeChanged(acceptable: Boolean) {
        routeChanged(
            ReadinessFacts(
                telecomRouteAcceptable = acceptable,
                nonTelecomRouteReady = true,
                hfpPrimeReady = true,
            ),
        )
    }

    fun routeChanged(facts: ReadinessFacts) {
        if (state != State.WaitingForRoute || !facts.isReady) return
        state = State.Recording
        callbacks.onCaptureStart()
    }

    fun checkTimeout(nowMs: Long) {
        if (state == State.WaitingForRoute && nowMs >= deadlineAtMs) {
            state = State.Released
            callbacks.onRouteTimeout()
        }
    }

    fun disconnect() {
        val previous = state
        if (previous == State.Idle || previous == State.Released) return
        state = State.Released
        if (previous == State.Recording) callbacks.onCaptureStop()
        callbacks.onDisconnected()
    }

    fun abort() {
        val previous = state
        if (previous == State.Idle || previous == State.Released) return
        state = State.Released
        if (previous == State.Recording) callbacks.onCaptureStop()
        callbacks.onAborted()
    }

    fun releaseAfterTeardown() {
        state = State.Idle
    }

    enum class State { Idle, WaitingForRoute, Recording, Released }

    data class ReadinessFacts(
        val telecomRouteAcceptable: Boolean,
        val nonTelecomRouteReady: Boolean,
        val hfpPrimeReady: Boolean,
    ) {
        val isReady: Boolean
            get() = telecomRouteAcceptable && nonTelecomRouteReady && hfpPrimeReady
    }

    interface Callbacks {
        fun onCaptureStart()
        fun onCaptureStop()
        fun onRouteTimeout()
        fun onDisconnected()
        fun onAborted()
    }

    companion object {
        const val DEFAULT_ROUTE_TIMEOUT_MS = 8_000L
    }
}