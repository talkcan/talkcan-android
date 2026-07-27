package io.talkcan.service

enum class CarHfpInspectionFailure {
    PermissionUnavailable,
    ProfileUnavailable,
    QueryFailed,
    InvalidConfiguredIdentity,
}

sealed interface CarHfpProfileInspection<out D> {
    data class Available<D>(val devices: List<D>) : CarHfpProfileInspection<D>
    data class Unavailable(val reason: CarHfpInspectionFailure) : CarHfpProfileInspection<Nothing>
}

sealed interface ConfiguredCarResolution<out D> {
    data object Unconfigured : ConfiguredCarResolution<Nothing>
    data object Absent : ConfiguredCarResolution<Nothing>
    data object Disconnected : ConfiguredCarResolution<Nothing>
    data object TargetRsmConflict : ConfiguredCarResolution<Nothing>
    data class InspectionFailed(val reason: CarHfpInspectionFailure) : ConfiguredCarResolution<Nothing>
    data class Resolved<D>(val device: D) : ConfiguredCarResolution<D>
}

/** Resolves only the persisted exact identity; candidate count, names, and ordering are irrelevant. */
internal fun <D : Any> resolveConfiguredCarHfpDevice(
    configuredCar: ConfiguredCar?,
    inspection: CarHfpProfileInspection<D>,
    targetRsmAddress: String?,
    addressOf: (D) -> String,
    isConnected: (D) -> Boolean,
): ConfiguredCarResolution<D> {
    configuredCar ?: return ConfiguredCarResolution.Unconfigured
    val configuredAddress = canonicalBluetoothAddress(configuredCar.canonicalAddress)
        ?: return ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.InvalidConfiguredIdentity)

    val canonicalTargetRsmAddress = targetRsmAddress?.let(::canonicalBluetoothAddress)
    if (targetRsmAddress != null && canonicalTargetRsmAddress == null) {
        return ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed)
    }
    if (canonicalTargetRsmAddress == configuredAddress) return ConfiguredCarResolution.TargetRsmConflict

    val devices = when (inspection) {
        is CarHfpProfileInspection.Available -> inspection.devices
        is CarHfpProfileInspection.Unavailable -> {
            return ConfiguredCarResolution.InspectionFailed(inspection.reason)
        }
    }
    var matched: D? = null
    for (device in devices) {
        val address = runCatching { canonicalBluetoothAddress(addressOf(device)) }
            .getOrElse {
                return ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed)
            }
            ?: return ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed)
        if (address == configuredAddress) {
            matched = device
            break
        }
    }
    val device = matched ?: return ConfiguredCarResolution.Absent
    val connected = runCatching { isConnected(device) }
        .getOrElse {
            return ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed)
        }
    return if (connected) ConfiguredCarResolution.Resolved(device) else ConfiguredCarResolution.Disconnected
}
