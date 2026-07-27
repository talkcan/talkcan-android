package io.talkcan.model

/** UI-safe projection of one configured car. The persisted hardware identity never crosses this boundary. */
data class ConfiguredCarPresentation(
    val label: String,
    val status: ConfiguredCarStatus,
)

enum class ConfiguredCarStatus {
    Connected,
    Unavailable,
    TargetRsmConflict,
}

/** One live HFP endpoint addressable only through an opaque, process-local selection token. */
data class CarHfpCandidate(
    val selectionId: String,
    val label: String,
    val selected: Boolean,
)

enum class CarHfpInspectionStatus {
    Available,
    PermissionUnavailable,
    ProfileUnavailable,
    InspectionFailed,
}

enum class CarHfpSelectionFailure {
    CandidateUnavailable,
    CandidateDisconnected,
    TargetRsmConflict,
    PermissionUnavailable,
    ProfileUnavailable,
    InspectionFailed,
    PersistenceFailed,
}

data class CarHfpConfigurationState(
    val configuredCar: ConfiguredCarPresentation? = null,
    val candidates: List<CarHfpCandidate> = emptyList(),
    val inspectionStatus: CarHfpInspectionStatus = CarHfpInspectionStatus.ProfileUnavailable,
    val selectionFailure: CarHfpSelectionFailure? = null,
)
