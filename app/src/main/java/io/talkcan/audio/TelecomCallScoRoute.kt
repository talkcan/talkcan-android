package io.talkcan.audio

import io.talkcan.model.ScoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TelecomCallScoRoute(
    private val routeReady: () -> Boolean,
) : ScoRoute {
    private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
    override val state: StateFlow<ScoState> = _state.asStateFlow()
    override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Car

    override fun hasAvailableScoDevice(): Boolean = routeReady()

    override suspend fun acquire(): Boolean {
        return if (routeReady()) {
            _state.value = ScoState.Active
            true
        } else {
            _state.value = ScoState.Failed("Telecom car call audio route not available")
            false
        }
    }

    override fun isActive(): Boolean = routeReady()

    override fun release() {}
}
