package io.talkcan.ui

import io.talkcan.model.BootstrapStage
import io.talkcan.model.BootstrapState
import io.talkcan.model.ModelAcquisitionProgress

/**
 * Root surface derived from [BootstrapState] — the authoritative source
 * for loading, setup, recovery, and dashboard routing.
 *
 * Extracted from [io.talkcan.MainActivity] so the mapping
 * is testable without the Compose runtime.
 */
enum class BootstrapRootSurface { Loading, Setup, Dashboard }

/**
 * Maps a [BootstrapState] to the root surface the activity should render.
 *
 * - [BootstrapState.ConnectingService], [BootstrapState.CheckingPrerequisites],
 *   [BootstrapState.AcquiringModels], [BootstrapState.PreparingCore], and
 *   [BootstrapState.Failed] route to [BootstrapRootSurface.Loading]
 *   (the loading/recovery surface renders `Failed` with a retry action).
 * - [BootstrapState.NeedsSetup] routes to [BootstrapRootSurface.Setup].
 * - [BootstrapState.Ready] routes to [BootstrapRootSurface.Dashboard].
 */
fun bootstrapRootSurface(state: BootstrapState): BootstrapRootSurface = when (state) {
    is BootstrapState.ConnectingService,
    is BootstrapState.CheckingPrerequisites,
    is BootstrapState.AcquiringModels,
    is BootstrapState.PreparingCore,
    is BootstrapState.Failed -> BootstrapRootSurface.Loading

    is BootstrapState.NeedsSetup -> BootstrapRootSurface.Setup
    is BootstrapState.Ready -> BootstrapRootSurface.Dashboard
}

/**
 * The human-readable stage name shown on the loading surface for [state].
 */
fun bootstrapStageText(state: BootstrapState): String = when (state) {
    is BootstrapState.ConnectingService -> "Connecting to service"
    is BootstrapState.CheckingPrerequisites -> when (state.stage) {
        BootstrapStage.CheckingPermissions -> "Checking permissions"
        BootstrapStage.CheckingModels -> "Verifying model assets"
        BootstrapStage.ProbingNavigationVoice -> "Probing offline navigation voice"
        else -> "Checking prerequisites"
    }
    is BootstrapState.AcquiringModels -> "Downloading speech packages"
    is BootstrapState.PreparingCore -> when (state.stage) {
        BootstrapStage.InitializingStt -> "Initializing speech-to-text engine"
        BootstrapStage.InitializingTts -> "Initializing text-to-speech engine"
        BootstrapStage.ConstructingControllers -> "Constructing controllers"
        BootstrapStage.VerifyingReadiness -> "Verifying core readiness"
        else -> "Preparing core systems"
    }
    is BootstrapState.NeedsSetup -> "Setup required"
    is BootstrapState.Ready -> "Ready"
    is BootstrapState.Failed -> "Failed: ${state.stage.name}"
}

/**
 * Measurable progress detail for [state], or `null` when the current stage
 * reports no real count/bytes.
 *
 * - [BootstrapState.AcquiringModels] with [modelProgress] `totalBytes > 0`
 *   produces byte progress text.
 * - All other stages produce `null` (stage name only).
 */
fun bootstrapProgressDetail(
    state: BootstrapState,
    modelProgress: ModelAcquisitionProgress = ModelAcquisitionProgress(),
): String? {
    if (state is BootstrapState.AcquiringModels && modelProgress.totalBytes > 0) {
        val bytesRead = modelProgress.bytesRead
        val totalBytes = modelProgress.totalBytes
        val pct = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        return "${formatBytes(bytesRead)} / ${formatBytes(totalBytes)} (${(pct * 100).toInt()}%)"
    }


    return null
}

/**
 * Formats a byte count as a human-readable string: MB for >= 1 MiB, KB otherwise.
 */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) {
        "${String.format("%.1f", mb)} MB"
    } else {
        "${bytes / 1024} KB"
    }
}
