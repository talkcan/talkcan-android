package io.talkcan.model


/**
 * Sealed hierarchy of bootstrap states owned by [PttForegroundService] and
 * observed by `MainActivity` to decide which root surface to render.
 *
 * The state machine transitions are:
 *
 * ```text
 * ConnectingService
 *   └─> CheckingPrerequisites(stage)
 *         ├─> NeedsSetup(missingPermissions, invalidModelSets, error?)
 *         │     └─> AcquiringModels(progress)  [user starts download]
 *         │           ├─> NeedsSetup(..., error)  [acquisition failed]
 *         │           └─> CheckingPrerequisites  [acquisition succeeded]
 *         ├─> AcquiringModels(progress)  [autonomous repair during checking]
 *         │     └─> CheckingPrerequisites  [repair complete]
 *         └─> PreparingCore(stage, completedUnits, totalUnits)
 *               ├─> Failed(stage, diagnostic, retryable)
 *               └─> Ready
 * ```
 *
 * `Failed` can arise from any non-`Ready` state; `Ready` is terminal until
 * an explicit retry re-enters `CheckingPrerequisites`.
 */
sealed interface BootstrapState {

    /**
     * The bootstrap-owning service is not yet bound. The activity shows a
     * passive "starting service" loading stage.
     */
    data object ConnectingService : BootstrapState

    /**
     * Bootstrap is checking runtime permissions and model asset validity.
     * [stage] identifies the sub-stage for loading display.
     */
    data class CheckingPrerequisites(
        val stage: BootstrapStage = BootstrapStage.CheckingPermissions,
    ) : BootstrapState

    /**
     * One or more user-resolvable prerequisites are missing: runtime permissions, all-files
     * storage access, model assets that require explicit download or repair, or an installed
     * offline English navigation voice.
     *
     * [missingPermissions] lists the exact missing runtime permission strings.
     * [needsManageExternalStorage] identifies Android's separate all-files settings grant.
     * [invalidModelSets] lists model sets that are absent, version-mismatched, or hash-invalid.
     * [offlineNavigationVoiceIssue] identifies a failed Android TTS prerequisite and the engine
     * package to target when available. [error] carries any setup diagnostic.
     */
    data class NeedsSetup(
        val missingPermissions: List<String> = emptyList(),
        val needsManageExternalStorage: Boolean = false,
        val invalidModelSets: List<String> = emptyList(),
        val error: String? = null,
        val offlineNavigationVoiceIssue: OfflineNavigationVoiceIssue? = null,
    ) : BootstrapState

    /**
     * Model acquisition is in progress. [progress] carries real byte/count
     * progress from the active download operation.
     */
    data class AcquiringModels(
        val progress: ModelAcquisitionProgress = ModelAcquisitionProgress(),
    ) : BootstrapState

    /**
     * Core initialization is in progress: Kotlin ONNX STT/TTS engine loading,
     * controller construction, and announcement rendering.
     *
     * [stage] identifies the current core-preparation sub-stage.
     * [completedUnits] and [totalUnits] carry measurable progress when the
     * underlying operation reports a real count (e.g. announcement phrases).
     */
    data class PreparingCore(
        val stage: BootstrapStage = BootstrapStage.InitializingStt,
        val completedUnits: Int = 0,
        val totalUnits: Int = 0,
    ) : BootstrapState

    /**
     * All core readiness conditions have completed successfully. The
     * dashboard is shown.
     */
    data object Ready : BootstrapState

    /**
     * A bootstrap failure has occurred. [stage] identifies the failed
     * stage. [diagnostic] carries a concrete human-readable diagnostic.
     * [retryable] indicates whether a safe retry is available.
     */
    data class Failed(
        val stage: BootstrapStage,
        val diagnostic: String,
        val retryable: Boolean = true,
    ) : BootstrapState
}

data class OfflineNavigationVoiceIssue(
    val diagnostic: String,
    val enginePackage: String? = null,
)

/**
 * Sub-stages within the bootstrap lifecycle. Used by [BootstrapState]
 * implementations and by the loading surface to report the current
 * observed stage.
 */
enum class BootstrapStage {
    ConnectingService,
    CheckingPermissions,
    CheckingModels,
    AcquiringModels,
    InitializingStt,
    InitializingTts,
    ConstructingControllers,
    ProbingNavigationVoice,
    VerifyingReadiness,
}

/**
 * Real progress from a model acquisition operation. Each [ModelSetProgress]
 * tracks one model set's download progress.
 */
data class ModelAcquisitionProgress(
    val sets: List<ModelSetProgress> = emptyList(),
) {
    val totalBytes: Long get() = sets.sumOf { it.totalBytes }
    val bytesRead: Long get() = sets.sumOf { it.bytesRead }
}

data class ModelSetProgress(
    val dirName: String,
    val currentFile: String = "",
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
)

/**
 * Terminal result of a model asset inspection or acquisition operation.
 */
sealed interface ModelAssetResult {

    /**
     * The model set is present, non-empty, and every file's SHA-256 matches
     * the bundled manifest.
     */
    data class Valid(val dirName: String) : ModelAssetResult

    /**
     * The model set is absent, version-mismatched, or hash-invalid and
     * requires user-initiated download or repair.
     */
    data class UserActionRequired(
        val dirName: String,
        val reason: String,
    ) : ModelAssetResult

    /**
     * Acquisition is actively in progress for this model set. Concurrent
     * callers join the in-flight operation.
     */
    data class Active(
        val dirName: String,
        val progress: ModelSetProgress,
    ) : ModelAssetResult

    /**
     * Acquisition or verification failed. [reason] carries the diagnostic.
     */
    data class Failed(
        val dirName: String,
        val reason: String,
    ) : ModelAssetResult
}
