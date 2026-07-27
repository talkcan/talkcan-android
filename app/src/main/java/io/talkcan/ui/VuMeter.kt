package io.talkcan.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.ui.theme.AlertAmber
import io.talkcan.ui.theme.ChakraPetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.round

/**
 * VU meter Composable — renders a live audio-level indicator driven by the capture service's
 * `level` signal. Always mounted in the dashboard layout; when not capturing the meter shows a
 * dim empty track and a "STANDBY" label, so the layout does not shift when capture starts/stops
 * (shifting would reflow the channel cards and make the On-a-pinch PTT target unusable).
 *
 * Behavior (see `vu-meter` spec / change design):
 * - Perceptual mapping (`sqrt(level)` after input gain) so quiet input is visible and loud input
 *   does not instantly peg the meter. Input gain (`INPUT_GAIN`) compensates for the low RMS that
 *   real speech produces from the normalized capture signal.
 * - VU ballistics computed locally: fast attack (~30ms), slower release (~200ms), peak-hold
 *   marker (~800ms hold then decay). Implemented in [VuMeterEngine] (pure Kotlin, unit-tested).
 * - Three palette-conformant zones (low dim / good transmit / clip amber); each segment keeps
 *   its zone color so the fill's top indicates the current zone.
 * - Field-terminal segmented bar aesthetic (no off-palette traffic-light gradient). Chakra Petch
 *   for any markings/labels.
 */
@Composable
fun VuMeter(
    level: Float,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
) {
    var engineState by remember { mutableStateOf(VuMeterState(0f, 0f, VuZone.LOW)) }
    val engine = remember { VuMeterEngine(clockMillis = { System.currentTimeMillis() }) }
    // rememberUpdatedState so the ticker coroutine below reads the live `level` on every tick
    // instead of capturing the first chunk's value for the whole capture session (which left
    // the meter pegged at the initial RMS — "the mark never moves").
    val currentLevel by rememberUpdatedState(level)

    LaunchedEffect(isCapturing) {
        if (!isCapturing) {
            engine.reset()
            engineState = VuMeterState(0f, 0f, VuZone.LOW)
            return@LaunchedEffect
        }
        while (isActive) {
            engineState = engine.update(currentLevel)
            delay(TICK_MS)
        }
    }

    val quantizedDisplayed by remember {
        derivedStateOf { round(engineState.displayed / QUANTUM) * QUANTUM }
    }
    val quantizedPeak by remember {
        derivedStateOf { round(engineState.peakHold / QUANTUM) * QUANTUM }
    }

    val effectiveZone = if (isCapturing) engineState.zone else VuZone.LOW
    val zoneLabelColor by animateColorAsState(
        targetValue = if (isCapturing) zoneColor(effectiveZone) else trackColor(),
        animationSpec = tween(LABEL_TRANSITION_MS),
        label = "vu-zone-label",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until VuMeterEngine.SEGMENT_COUNT) {
                val segmentLower = i / VuMeterEngine.SEGMENT_COUNT.toFloat()
                val segmentUpper = (i + 1) / VuMeterEngine.SEGMENT_COUNT.toFloat()
                val zone = zoneForSegment(segmentLower, segmentUpper)
                val isFilled = isCapturing && quantizedDisplayed >= segmentLower
                val isPeak = isCapturing && (quantizedPeak in segmentLower..segmentUpper ||
                    (quantizedPeak >= segmentUpper && i == VuMeterEngine.SEGMENT_COUNT - 1))
                Segment(
                    zone = zone,
                    isFilled = isFilled,
                    isPeak = isPeak,
                    isCapturing = isCapturing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MIC",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isCapturing) zoneLabel(engineState.zone) else "STANDBY",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                color = zoneLabelColor,
            )
        }
    }
}

@Composable
private fun Segment(
    zone: VuZone,
    isFilled: Boolean,
    isPeak: Boolean,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
) {
    // Each segment keeps its zone color so the fill's top indicates the current zone (design D5).
    // Unfilled segments render in the secondary text color at reduced opacity (the "track").
    // When idle the whole bar reads as a dim track, so the meter is visually present but inert.
    val fill = when {
        isFilled || isPeak -> zoneColor(zone)
        isCapturing -> trackColor()
        else -> idleTrackColor()
    }
    Box(
        modifier = modifier
            .height(SEGMENT_HEIGHT)
            .clip(RoundedCornerShape(1.dp))
            .background(fill),
    )
}

private fun zoneForSegment(segmentLower: Float, segmentUpper: Float): VuZone {
    // A segment's zone is the zone its upper bound falls in — so the fill's top indicates the
    // current zone (per design D5). The topmost segment of a fill therefore reads as the live
    // zone; lower filled segments render in their own (lower) zone colors.
    return when {
        segmentUpper <= VuMeterEngine.LOW_THRESHOLD -> VuZone.LOW
        segmentLower >= VuMeterEngine.CLIP_THRESHOLD -> VuZone.CLIP
        else -> VuZone.GOOD
    }
}

@Composable
private fun zoneColor(zone: VuZone): Color = when (zone) {
    VuZone.LOW -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LOW_ZONE_OPACITY)
    VuZone.GOOD -> MaterialTheme.colorScheme.primary
    VuZone.CLIP -> AlertAmber
}

@Composable
private fun trackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TRACK_OPACITY)

@Composable
private fun idleTrackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = IDLE_TRACK_OPACITY)

private fun zoneLabel(zone: VuZone): String = when (zone) {
    VuZone.LOW -> "LOW"
    VuZone.GOOD -> "GOOD"
    VuZone.CLIP -> "CLIP"
}

private val TICK_MS: Long = 16L
private val LABEL_TRANSITION_MS: Int = 120
private val QUANTUM: Float = 0.05f
private val SEGMENT_HEIGHT = 14.dp
private val LOW_ZONE_OPACITY: Float = 0.55f
private val TRACK_OPACITY: Float = 0.18f
private val IDLE_TRACK_OPACITY: Float = 0.10f