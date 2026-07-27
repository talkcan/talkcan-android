package io.talkcan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.BootstrapState
import io.talkcan.model.ModelAcquisitionProgress
import kotlin.math.sin
import kotlin.math.PI

/**
 * Passive loading and recovery surface. Renders the Analog-to-Routed Wave
 * identity from [VISUAL_IDENTITY.md] using Compose Canvas — no external
 * animation dependency.
 *
 * Shows:
 * - `TALKCAN` header with a concise field-terminal subtitle;
 * - the analog-to-routed wave as the main focal element;
 * - the current observed stage and measurable count/byte progress;
 * - a compact stable stage rail;
 * - a recovery variant with diagnostic and retry action.
 *
 * Does NOT show fictional logs, random values, starfields, spacecraft,
 * CRT flicker, or a fake combined percentage. Completion is never delayed
 * for animation.
 *
 * Uses the theme primary accent (Talkcan Cyan in Night Ops, Command Gold
 * in Daylight) for ordinary progress. Alert Amber is reserved for
 * warnings/failures.
 */
@Composable
fun BootstrapLoadingScreen(
    state: BootstrapState,
    modelProgress: ModelAcquisitionProgress = ModelAcquisitionProgress(),
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isRecovery = state is BootstrapState.Failed
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "TALKCAN",
            subtitle = when (state) {
                is BootstrapState.ConnectingService -> "Starting service…"
                is BootstrapState.CheckingPrerequisites -> "Checking prerequisites…"
                is BootstrapState.NeedsSetup -> "Setup required"
                is BootstrapState.AcquiringModels -> "Downloading speech packages…"
                is BootstrapState.PreparingCore -> "Preparing core systems…"
                is BootstrapState.Ready -> "Ready"
                is BootstrapState.Failed -> "Bootstrap failed"
            },
        )

        // Analog-to-Routed Wave Canvas — the main focal element.
        AnalogToRoutedWave(
            isRecovery = isRecovery,
            primary = primary,
            onSurface = onSurface,
            surfaceColor = surface,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        )

        // Current observed stage and progress.
        StageProgressSection(
            state = state,
            modelProgress = modelProgress,
            primary = primary,
            onSurfaceVariant = onSurfaceVariant,
            onSurface = onSurface,
            error = error,
        )

        // Stable stage rail.
        StageRail(
            state = state,
            primary = primary,
            onSurfaceVariant = onSurfaceVariant,
        )

        // Recovery variant: diagnostic and retry action.
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
                        "DIAGNOSTIC",
                        style = MaterialTheme.typography.labelLarge,
                        color = error,
                    )
                    Spacer(Modifier.height(4.dp))
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

/**
 * The Analog-to-Routed Wave: a continuous horizontal line that forms a
 * smooth analog sine wave on the left and sharpens into structured digital
 * segments on the right. One restrained signal pulse travels across it.
 * Completed real stages illuminate stable segments.
 *
 * In reduced/no-animation mode (controlled by the platform animator
 * duration scale), the wave remains as a legible static routed line.
 */
@Composable
private fun AnalogToRoutedWave(
    isRecovery: Boolean,
    primary: Color,
    onSurface: Color,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
) {
    // The infinite transition naturally respects the platform animator
    // duration scale: when animation is disabled (scale = 0), the pulse
    // stays static and the wave remains a legible static routed line.

    val transition = rememberInfiniteTransition(label = "wave")
    val pulseProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val waveAmplitude = height * 0.15f
        val transitionStart = width * 0.35f
        val transitionEnd = width * 0.65f

        val waveColor = if (isRecovery) {
            androidx.compose.ui.graphics.Color(0xFFFFB300)
        } else {
            primary
        }

        // Draw the analog-to-routed wave.
        val path = Path()
        val segmentCount = 120
        for (i in 0..segmentCount) {
            val x = width * i / segmentCount
            val t = x / width
            val y = when {
                t < 0.35f -> {
                    // Analog sine wave.
                    centerY + waveAmplitude * sin(t * PI * 4).toFloat()
                }
                t < 0.65f -> {
                    // Transition zone: interpolate from sine to routed.
                    val blend = (t - 0.35f) / 0.3f
                    val sineY = centerY + waveAmplitude * sin(t * PI * 4).toFloat()
                    val routedY = centerY
                    sineY * (1 - blend) + routedY * blend
                }
                else -> {
                    // Routed segments — flat with small square steps.
                    val stepIdx = ((t - 0.65f) / 0.35f * 8).toInt()
                    val stepT = ((t - 0.65f) / 0.35f * 8) - stepIdx
                    val stepY = if (stepIdx % 2 == 0) centerY - 8f else centerY + 8f
                    stepY
                }
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = waveColor.copy(alpha = 0.3f),
            style = Stroke(width = 2f, cap = StrokeCap.Round),
        )

        // Draw the signal pulse — one restrained glowing dot.
        val pulseX = pulseProgress * width
        val pulseT = pulseX / width
        val pulseY = when {
            pulseT < 0.35f -> centerY + waveAmplitude * sin(pulseT * PI * 4).toFloat()
            pulseT < 0.65f -> {
                val blend = (pulseT - 0.35f) / 0.3f
                val sineY = centerY + waveAmplitude * sin(pulseT * PI * 4).toFloat()
                sineY * (1 - blend) + centerY * blend
            }
            else -> {
                val stepIdx = ((pulseT - 0.65f) / 0.35f * 8).toInt()
                if (stepIdx % 2 == 0) centerY - 8f else centerY + 8f
            }
        }
        drawCircle(
            color = waveColor,
            radius = 6f,
            center = Offset(pulseX, pulseY),
        )
        // Glow.
        drawCircle(
            color = waveColor.copy(alpha = 0.2f),
            radius = 14f,
            center = Offset(pulseX, pulseY),
        )
    }
}

@Composable
private fun StageProgressSection(
    state: BootstrapState,
    modelProgress: ModelAcquisitionProgress,
    primary: Color,
    onSurfaceVariant: Color,
    onSurface: Color,
    error: Color,
) {
    val stageText = bootstrapStageText(state)

    Text(
        stageText,
        style = MaterialTheme.typography.titleMedium,
        color = if (state is BootstrapState.Failed) error else onSurface,
    )

    // Real progress: bytes or phrase counts — null when the stage reports
    // no measurable total.
    bootstrapProgressDetail(state, modelProgress)?.let { detail ->
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
        )
    }
}

@Composable
private fun StageRail(
    state: BootstrapState,
    primary: Color,
    onSurfaceVariant: Color,
) {
    val stages = listOf(
        "Check" to (state is BootstrapState.CheckingPrerequisites || state is BootstrapState.PreparingCore || state is BootstrapState.Ready),
        "Download" to (state is BootstrapState.AcquiringModels || state is BootstrapState.PreparingCore || state is BootstrapState.Ready),
        "Init" to (state is BootstrapState.PreparingCore || state is BootstrapState.Ready),
        "Ready" to (state is BootstrapState.Ready),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stages.forEach { (label, completed) ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (completed) primary else onSurfaceVariant,
                fontWeight = if (completed) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
