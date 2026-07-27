package io.talkcan.service

import io.talkcan.model.CarHfpCandidate
import io.talkcan.model.CarHfpConfigurationState
import io.talkcan.model.CarHfpInspectionStatus
import io.talkcan.model.CarHfpSelectionFailure
import io.talkcan.model.ConfiguredCarPresentation
import io.talkcan.model.ConfiguredCarStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal class CarHfpConfigurationController<D : Any>(
    private val store: CarHfpConfigurationStore,
    private val hasBluetoothConnect: () -> Boolean,
    private val profileDevicesProvider: () -> List<D>?,
    private val targetRsmProvider: () -> D?,
    private val addressOf: (D) -> String,
    private val displayNameOf: (D) -> String?,
    private val isConnected: (D) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    var state: CarHfpConfigurationState = unavailableState(CarHfpInspectionStatus.ProfileUnavailable)
        private set

    fun refresh(): CarHfpConfigurationState {
        val inspection = inspect()
        state = when (inspection) {
            is ControllerInspection.Failed -> unavailableState(inspection.status)
            is ControllerInspection.Available -> project(inspection)
        }
        log(
            "CAR_HFP_CONFIG_REFRESH inspection=${state.inspectionStatus} " +
                "configured=${state.configuredCar != null} candidates=${state.candidates.size} " +
                "configuredStatus=${state.configuredCar?.status ?: "unconfigured"}",
        )
        return state
    }

    /** Revalidates the opaque row token against a fresh profile snapshot before replacing storage. */
    fun select(selectionId: String): CarHfpConfigurationState {
        val inspection = inspect()
        if (inspection is ControllerInspection.Failed) {
            state = unavailableState(
                status = inspection.status,
                selectionFailure = inspection.selectionFailure,
            )
            log(
                "CAR_HFP_CONFIG_SELECT outcome=${inspection.selectionFailure.name.lowercase().replace('_', '-')} " +
                    "configuredPreserved=${state.configuredCar != null}",
            )
            return state
        }
        inspection as ControllerInspection.Available
        val selected = inspection.devices.firstOrNull { selectionIdFor(it.canonicalAddress) == selectionId }
        val failure = when {
            selected == null -> CarHfpSelectionFailure.CandidateUnavailable
            inspection.targetRsmAddress == selected.canonicalAddress -> CarHfpSelectionFailure.TargetRsmConflict
            !selected.connected -> CarHfpSelectionFailure.CandidateDisconnected
            !store.replace(selected.canonicalAddress, selected.displayName) -> CarHfpSelectionFailure.PersistenceFailed
            else -> null
        }
        state = if (failure == null) refresh() else project(inspection, failure)
        log(
            "CAR_HFP_CONFIG_SELECT outcome=${failure?.name?.lowercase()?.replace('_', '-') ?: "selected"} " +
                "configured=${state.configuredCar != null}",
        )
        return state
    }

    private fun inspect(): ControllerInspection<D> {
        if (!hasBluetoothConnect()) {
            return ControllerInspection.Failed(
                CarHfpInspectionStatus.PermissionUnavailable,
                CarHfpSelectionFailure.PermissionUnavailable,
            )
        }
        val devices = try {
            profileDevicesProvider()
                ?: return ControllerInspection.Failed(
                    CarHfpInspectionStatus.ProfileUnavailable,
                    CarHfpSelectionFailure.ProfileUnavailable,
                )
        } catch (_: SecurityException) {
            return ControllerInspection.Failed(
                CarHfpInspectionStatus.PermissionUnavailable,
                CarHfpSelectionFailure.PermissionUnavailable,
            )
        } catch (_: RuntimeException) {
            return ControllerInspection.Failed(
                CarHfpInspectionStatus.InspectionFailed,
                CarHfpSelectionFailure.InspectionFailed,
            )
        }
        return try {
            val targetRsmAddress = targetRsmProvider()?.let { target ->
                canonicalBluetoothAddress(addressOf(target))
                    ?: return ControllerInspection.Failed(
                        CarHfpInspectionStatus.InspectionFailed,
                        CarHfpSelectionFailure.InspectionFailed,
                    )
            }
            val inspected = devices.map { device ->
                InspectedDevice(
                    device = device,
                    canonicalAddress = canonicalBluetoothAddress(addressOf(device))
                        ?: return ControllerInspection.Failed(
                            CarHfpInspectionStatus.InspectionFailed,
                            CarHfpSelectionFailure.InspectionFailed,
                        ),
                    displayName = displayNameOf(device)?.trim()?.takeIf(String::isNotEmpty),
                    connected = isConnected(device),
                )
            }
            ControllerInspection.Available(inspected, targetRsmAddress)
        } catch (_: SecurityException) {
            ControllerInspection.Failed(
                CarHfpInspectionStatus.PermissionUnavailable,
                CarHfpSelectionFailure.PermissionUnavailable,
            )
        } catch (_: RuntimeException) {
            ControllerInspection.Failed(
                CarHfpInspectionStatus.InspectionFailed,
                CarHfpSelectionFailure.InspectionFailed,
            )
        }
    }

    private fun project(
        inspection: ControllerInspection.Available<D>,
        selectionFailure: CarHfpSelectionFailure? = null,
    ): CarHfpConfigurationState {
        val configured = store.configuredCar.value
        val resolution = resolveConfiguredCarHfpDevice(
            configuredCar = configured,
            inspection = CarHfpProfileInspection.Available(inspection.devices),
            targetRsmAddress = inspection.targetRsmAddress,
            addressOf = { it.canonicalAddress },
            isConnected = { it.connected },
        )
        val configuredPresentation = configured?.let {
            ConfiguredCarPresentation(
                label = it.displayLabel ?: UNNAMED_DEVICE_LABEL,
                status = when (resolution) {
                    is ConfiguredCarResolution.Resolved -> ConfiguredCarStatus.Connected
                    ConfiguredCarResolution.TargetRsmConflict -> ConfiguredCarStatus.TargetRsmConflict
                    else -> ConfiguredCarStatus.Unavailable
                },
            )
        }
        val eligible = inspection.devices
            .filter { it.connected && it.canonicalAddress != inspection.targetRsmAddress }
            .sortedWith(compareBy<InspectedDevice<D>>({ candidateBaseLabel(it).lowercase(Locale.US) }, { it.canonicalAddress }))
        val duplicateCounts = eligible.groupingBy(::candidateBaseLabel).eachCount()
        val duplicateIndexes = mutableMapOf<String, Int>()
        val candidates = eligible.map { device ->
            val baseLabel = candidateBaseLabel(device)
            val label = if (duplicateCounts.getValue(baseLabel) > 1) {
                val index = duplicateIndexes.getOrDefault(baseLabel, 0) + 1
                duplicateIndexes[baseLabel] = index
                "$baseLabel · $index"
            } else {
                baseLabel
            }
            CarHfpCandidate(
                selectionId = selectionIdFor(device.canonicalAddress),
                label = label,
                selected = configured?.canonicalAddress == device.canonicalAddress,
            )
        }
        return CarHfpConfigurationState(
            configuredCar = configuredPresentation,
            candidates = candidates,
            inspectionStatus = CarHfpInspectionStatus.Available,
            selectionFailure = selectionFailure,
        )
    }

    private fun unavailableState(
        status: CarHfpInspectionStatus,
        selectionFailure: CarHfpSelectionFailure? = null,
    ): CarHfpConfigurationState = CarHfpConfigurationState(
        configuredCar = store.configuredCar.value?.let {
            ConfiguredCarPresentation(
                label = it.displayLabel ?: UNNAMED_DEVICE_LABEL,
                status = ConfiguredCarStatus.Unavailable,
            )
        },
        inspectionStatus = status,
        selectionFailure = selectionFailure,
    )

    private fun candidateBaseLabel(device: InspectedDevice<D>): String = device.displayName ?: UNNAMED_DEVICE_LABEL

    private sealed interface ControllerInspection<out D> {
        data class Available<D>(
            val devices: List<InspectedDevice<D>>,
            val targetRsmAddress: String?,
        ) : ControllerInspection<D>

        data class Failed(
            val status: CarHfpInspectionStatus,
            val selectionFailure: CarHfpSelectionFailure,
        ) : ControllerInspection<Nothing>
    }

    private data class InspectedDevice<D>(
        val device: D,
        val canonicalAddress: String,
        val displayName: String?,
        val connected: Boolean,
    )

    private companion object {
        private const val UNNAMED_DEVICE_LABEL = "Unnamed HFP device"
    }
}

private fun selectionIdFor(canonicalAddress: String): String = MessageDigest.getInstance("SHA-256")
    .digest(canonicalAddress.toByteArray(StandardCharsets.US_ASCII))
    .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
