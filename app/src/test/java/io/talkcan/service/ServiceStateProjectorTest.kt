package io.talkcan.service

import io.talkcan.model.AppState
import io.talkcan.model.CarHfpCandidate
import io.talkcan.model.CarHfpConfigurationState
import io.talkcan.model.CarHfpInspectionStatus
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ConfiguredCarPresentation
import io.talkcan.model.ConfiguredCarStatus
import io.talkcan.model.ConnectionState
import io.talkcan.model.DevicePresence
import io.talkcan.model.HeadsetAudioState
import io.talkcan.model.HardwareMode
import io.talkcan.model.InputMode
import io.talkcan.model.InputModeAvailability
import io.talkcan.model.InputModeSelection
import io.talkcan.model.PermissionState
import io.talkcan.model.ScoState
import io.talkcan.model.SppState
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateProjectorTest {
    @Test
    fun `connection state is emitted before dependent readiness and car-media input state`() = runTest {
        lateinit var projector: ServiceStateProjector
        val connectionCallbackStates = mutableListOf<AppState>()
        val carMediaCallbackStates = mutableListOf<AppState>()
        projector = ServiceStateProjector(
            onConnectionUpdated = { connectionCallbackStates += projector.snapshot() },
            onInputModePublished = { carMediaCallbackStates += projector.snapshot() },
        )
        val observedStates = mutableListOf<AppState>()
        val collector = launch { projector.state.take(3).toList(observedStates) }
        runCurrent()

        val readyConnection = readyConnection()
        val carCompatibleAvailability = InputModeAvailability(
            work = true,
            onTheRoad = true,
            onAPinch = true,
        )
        projector.updateConnection { readyConnection }
        runCurrent()
        projector.publishInputMode(
            mode = InputMode.OnTheRoad,
            selectedBy = InputModeSelection.System,
            availability = carCompatibleAvailability,
        )
        runCurrent()
        collector.join()

        assertEquals(readyConnection, observedStates[1].connection)
        assertTrue(observedStates[1].readyForMonitor)
        assertEquals(readyConnection, observedStates[2].connection)
        assertEquals(InputMode.OnTheRoad, observedStates[2].inputMode)
        assertEquals(InputModeSelection.System, observedStates[2].inputModeSelectedBy)
        assertEquals(carCompatibleAvailability, observedStates[2].inputModeAvailability)

        assertEquals(readyConnection, connectionCallbackStates.single().connection)
        assertTrue(connectionCallbackStates.single().readyForMonitor)
        assertEquals(InputMode.OnTheRoad, carMediaCallbackStates.single().inputMode)
        assertEquals(InputModeSelection.System, carMediaCallbackStates.single().inputModeSelectedBy)
        assertEquals(carCompatibleAvailability, carMediaCallbackStates.single().inputModeAvailability)
    }

    @Test
    fun `runtime catalogue keeps provider identity order active channel status and pending work`() = runTest {
        val projector = ServiceStateProjector(onConnectionUpdated = {}, onInputModePublished = {})
        val catalogue = listOf(
            ChannelRuntimeSnapshot(
                id = "dispatch",
                name = "Dispatch",
                implementationId = ChannelImplementationId("openai:dispatch"),
                enabled = true,
                preparation = ChannelPreparationAvailability.Available,
                executionStatus = ChannelExecutionStatus.PROCESSING,
                summary = "Summarising incident",
                pendingCount = 3,
                playbackPaused = false,
            ),
            ChannelRuntimeSnapshot(
                id = "journal",
                name = "Journal",
                implementationId = ChannelImplementationId("builtin:journal"),
                enabled = true,
                preparation = ChannelPreparationAvailability.Recoverable(
                    ChannelPreparationReason.ProviderInitialising,
                ),
                executionStatus = ChannelExecutionStatus.RECORDING,
                summary = "Capturing note",
                pendingCount = 1,
                playbackPaused = true,
            ),
        )
        val observedStates = mutableListOf<AppState>()
        val collector = launch { projector.state.take(2).toList(observedStates) }
        runCurrent()

        projector.publishChannelRuntime(catalogue, activeChannelId = "journal")
        runCurrent()
        collector.join()

        val projected = observedStates.last()
        assertEquals(listOf("dispatch", "journal"), projected.channels.map(ChannelRuntimeSnapshot::id))
        assertEquals(
            listOf("openai:dispatch", "builtin:journal"),
            projected.channels.map { it.implementationId.value },
        )
        assertEquals(
            listOf(ChannelExecutionStatus.PROCESSING, ChannelExecutionStatus.RECORDING),
            projected.channels.map(ChannelRuntimeSnapshot::executionStatus),
        )
        assertEquals(listOf(3, 1), projected.channels.map(ChannelRuntimeSnapshot::pendingCount))
        assertEquals("journal", projected.activeChannelId)
        assertEquals(catalogue, projected.channels)
    }

    @Test
    fun `monitor input mode and car HFP mutations publish their complete slices in order`() = runTest {
        var connectionCallbackCount = 0
        val carMediaCallbackStates = mutableListOf<AppState>()
        lateinit var projector: ServiceStateProjector
        projector = ServiceStateProjector(
            onConnectionUpdated = { connectionCallbackCount += 1 },
            onInputModePublished = { carMediaCallbackStates += projector.snapshot() },
        )
        val selectedCar = CarHfpConfigurationState(
            configuredCar = ConfiguredCarPresentation("Field vehicle", ConfiguredCarStatus.Connected),
            candidates = listOf(
                CarHfpCandidate(selectionId = "vehicle-42", label = "Field vehicle", selected = true),
                CarHfpCandidate(selectionId = "vehicle-7", label = "Backup vehicle", selected = false),
            ),
            inspectionStatus = CarHfpInspectionStatus.Available,
            selectionFailure = null,
        )
        val availability = InputModeAvailability(work = true, onTheRoad = true, onAPinch = true)
        val observedStates = mutableListOf<AppState>()
        val collector = launch { projector.state.take(4).toList(observedStates) }
        runCurrent()

        projector.updateMonitor {
            it.copy(
                hardwareMode = HardwareMode.Control,
                echoEnabled = true,
                scoState = ScoState.Active,
            )
        }
        runCurrent()
        projector.publishInputMode(InputMode.Work, InputModeSelection.User, availability)
        runCurrent()
        projector.publishCarHfpConfiguration(selectedCar)
        runCurrent()
        collector.join()

        assertEquals(HardwareMode.Control, observedStates[1].monitor.hardwareMode)
        assertTrue(observedStates[1].monitor.echoEnabled)
        assertEquals(ScoState.Active, observedStates[1].monitor.scoState)
        assertEquals(InputMode.Work, observedStates[2].inputMode)
        assertEquals(InputModeSelection.User, observedStates[2].inputModeSelectedBy)
        assertEquals(availability, observedStates[2].inputModeAvailability)
        assertEquals(selectedCar, observedStates[3].carHfpConfiguration)

        assertEquals(0, connectionCallbackCount)
        assertEquals(1, carMediaCallbackStates.size)
        assertEquals(InputMode.Work, carMediaCallbackStates.single().inputMode)
        assertEquals(InputModeSelection.User, carMediaCallbackStates.single().inputModeSelectedBy)
        assertEquals(availability, carMediaCallbackStates.single().inputModeAvailability)
    }

    @Test
    fun `bootstrap channel reset preserves every previously published non-channel slice`() = runTest {
        val projector = ServiceStateProjector(onConnectionUpdated = {}, onInputModePublished = {})
        val channel = ChannelRuntimeSnapshot(
            id = "dispatch",
            name = "Dispatch",
            implementationId = ChannelImplementationId("openai:dispatch"),
            enabled = true,
            preparation = ChannelPreparationAvailability.Available,
            executionStatus = ChannelExecutionStatus.SUCCESS,
            summary = "Completed",
            pendingCount = 0,
            playbackPaused = false,
        )
        val carConfiguration = CarHfpConfigurationState(
            configuredCar = ConfiguredCarPresentation("Field vehicle", ConfiguredCarStatus.Connected),
            candidates = listOf(CarHfpCandidate("vehicle-42", "Field vehicle", selected = true)),
            inspectionStatus = CarHfpInspectionStatus.Available,
            selectionFailure = null,
        )
        projector.updateConnection { readyConnection() }
        projector.publishInputMode(
            mode = InputMode.Work,
            selectedBy = InputModeSelection.User,
            availability = InputModeAvailability(work = true, onTheRoad = false, onAPinch = true),
        )
        projector.publishChannelRuntime(listOf(channel), activeChannelId = channel.id)
        projector.publishCarHfpConfiguration(carConfiguration)
        val beforeBootstrapReset = projector.snapshot()
        val observedStates = mutableListOf<AppState>()
        val collector = launch { projector.state.take(2).toList(observedStates) }
        runCurrent()

        projector.publishChannels(emptyList())
        runCurrent()
        collector.join()

        assertEquals(beforeBootstrapReset.copy(channels = emptyList()), observedStates.last())
    }

    private fun readyConnection(): ConnectionState = ConnectionState(
        permissions = PermissionState.Granted,
        missingPermissions = emptyList(),
        bluetoothEnabled = true,
        devicePresence = DevicePresence.Bonded,
        spp = SppState.Connected,
        sppError = null,
        headsetAudio = HeadsetAudioState.Available,
        textOutputTransportState = io.talkcan.model.TextOutputTransportState.Connected,
    )

}
