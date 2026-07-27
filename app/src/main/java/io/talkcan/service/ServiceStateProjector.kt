package io.talkcan.service

import io.talkcan.model.AppState
import io.talkcan.model.CarHfpConfigurationState
import io.talkcan.model.ConnectionState
import io.talkcan.model.InputMode
import io.talkcan.model.InputModeAvailability
import io.talkcan.model.InputModeSelection
import io.talkcan.model.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sole owner of the service-lifetime [MutableStateFlow]<[AppState]> and the
 * authoritative projection of the service state.
 *
 * The projector centralizes every [AppState] mutation so that no collaborator
 * ever receives the mutable flow, a mutable [AppState], or the service. It
 * exposes a read-only [StateFlow] and a [snapshot] convenience for the
 * service-edge read sites, plus explicit typed operations for connection,
 * monitor, input-mode, catalogue/runtime, active-channel, car-HFP, and
 * bootstrap-derived updates.
 *
 * Compound emission order is preserved through two narrow service-edge
 * callbacks: [onConnectionUpdated] runs after a connection write so the
 * service can perform the coupled input-mode, car-media, and readiness-loop
 * side effects in the existing order; [onInputModePublished] runs after an
 * input-mode write so the service can refresh car-media state. Neither
 * callback receives [AppState]; the service re-reads through [snapshot].
 */
internal class ServiceStateProjector(
    private val onConnectionUpdated: () -> Unit,
    private val onInputModePublished: () -> Unit,
) {
    private val _state = MutableStateFlow(AppState())

    /** Read-only projection of the sole [AppState] owner. */
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Current [AppState] snapshot; the single read path for service-edge reads. */
    fun snapshot(): AppState = _state.value

    /**
     * Writes the connection slice via [transform] and invokes
     * [onConnectionUpdated] so the service performs the coupled input-mode,
     * car-media, and readiness-loop side effects in the existing order.
     */
    fun updateConnection(transform: (ConnectionState) -> ConnectionState) {
        val current = _state.value
        _state.value = current.copy(connection = transform(current.connection))
        onConnectionUpdated()
    }

    /**
     * Writes the monitor slice via [transform]. Monitor updates carry no
     * downstream service-side side effects today, so no callback is invoked.
     */
    fun updateMonitor(transform: (MonitorState) -> MonitorState) {
        val current = _state.value
        _state.value = current.copy(monitor = transform(current.monitor))
    }

    /**
     * Publishes the input-mode slice atomically and invokes
     * [onInputModePublished] so the service refreshes car-media state in the
     * existing order.
     */
    fun publishInputMode(
        mode: InputMode,
        selectedBy: InputModeSelection,
        availability: InputModeAvailability,
    ) {
        val current = _state.value
        _state.value = current.copy(
            inputMode = mode,
            inputModeSelectedBy = selectedBy,
            inputModeAvailability = availability,
        )
        onInputModePublished()
    }

    /**
     * Publishes the catalogue/runtime aggregate and the active channel id.
     * The service computes the projected runtime list (it owns the
     * runtime/agent/deferred aggregation); the projector owns the [AppState]
     * write.
     */
    fun publishChannelRuntime(
        channels: List<ChannelRuntimeSnapshot>,
        activeChannelId: String,
    ) {
        _state.update {
            it.copy(channels = channels, activeChannelId = activeChannelId)
        }
    }

    /** Publishes the car-HFP configuration slice. */
    fun publishCarHfpConfiguration(configuration: CarHfpConfigurationState) {
        _state.update { it.copy(carHfpConfiguration = configuration) }
    }

    /**
     * Publishes the channel list only. Used for the bootstrap-era channel
     * reset (e.g. `channels = emptyList()` before the runtime collector
     * starts) and any other channels-only write.
     */
    fun publishChannels(channels: List<ChannelRuntimeSnapshot>) {
        _state.update { it.copy(channels = channels) }
    }
}