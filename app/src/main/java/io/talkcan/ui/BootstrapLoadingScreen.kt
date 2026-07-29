package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.talkcan.model.BootstrapState
import io.talkcan.model.ModelAcquisitionProgress

/**
 * Loading and recovery surface built around the Talkcan can-and-string
 * identity. Shows a large TalkcanMark, one clear current-stage message,
 * honest measured progress, and an accessible recovery card when
 * bootstrap fails.
 *
 * The LinearProgressIndicator respects the platform animator duration
 * scale: when animation is disabled (scale = 0), progress is shown
 * without motion and the surface remains fully legible.
 *
 * Completion is never delayed for animation. No fictional logs, random
 * values, or fake combined percentages are shown.
 */
@Composable
fun BootstrapLoadingScreen(
    state: BootstrapState,
    modelProgress: ModelAcquisitionProgress = ModelAcquisitionProgress(),
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isRecovery = state is BootstrapState.Failed
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "Starting Talkcan",
            subtitle = when (state) {
                is BootstrapState.ConnectingService ->
                    "Connecting to the Talkcan service"
                is BootstrapState.CheckingPrerequisites ->
                    "Checking permissions and model assets"
                is BootstrapState.NeedsSetup ->
                    "Setup required to continue"
                is BootstrapState.AcquiringModels ->
                    "Downloading speech packages"
                is BootstrapState.PreparingCore ->
                    "Preparing speech and core systems"
                is BootstrapState.Ready ->
                    "Talkcan is ready"
                is BootstrapState.Failed ->
                    "Bootstrap failed — see details below"
            },
        )

        // Large TalkcanMark as the focal element.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TalkcanMark(modifier = Modifier.size(120.dp))
        }

        // Current observed stage message.
        Text(
            bootstrapStageText(state),
            style = MaterialTheme.typography.titleMedium,
            color = if (isRecovery) error else onSurface,
        )

        // Honest measured progress — shown only when the stage reports
        // a real byte total. LinearProgressIndicator respects the
        // platform animator duration scale for reduced-motion safety.
        bootstrapProgressDetail(state, modelProgress)?.let { detail ->
            if (state is BootstrapState.AcquiringModels &&
                modelProgress.totalBytes > 0
            ) {
                val fraction = (
                    modelProgress.bytesRead.toFloat() /
                        modelProgress.totalBytes.toFloat()
                ).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariant,
            )
        }

        // Compact stage rail showing completed stages.
        StageRail(state = state)

        // Recovery card with diagnostic text and retry action.
        if (isRecovery) {
            val failed = state as BootstrapState.Failed
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        failed.diagnostic,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface,
                    )
                    if (failed.retryable) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRail(state: BootstrapState) {
    val stages = listOf(
        "Check" to (
            state is BootstrapState.CheckingPrerequisites ||
                state is BootstrapState.PreparingCore ||
                state is BootstrapState.Ready
            ),
        "Download" to (
            state is BootstrapState.AcquiringModels ||
                state is BootstrapState.PreparingCore ||
                state is BootstrapState.Ready
            ),
        "Init" to (
            state is BootstrapState.PreparingCore ||
                state is BootstrapState.Ready
            ),
        "Ready" to (state is BootstrapState.Ready),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.chunked(2).forEach { stageRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stageRow.forEach { (label, completed) ->
                    TalkcanStatusBadge(
                        label = label,
                        tone = if (completed) TalkcanStatusTone.Ready
                            else TalkcanStatusTone.Neutral,
                    )
                }
            }
        }
    }
}