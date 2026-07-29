package io.talkcan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.ui.theme.CanRed
import io.talkcan.ui.theme.Ink
import io.talkcan.ui.theme.RadioBlue
import io.talkcan.ui.theme.SignalGreen
import io.talkcan.ui.theme.StringYellow

@Composable
internal fun TalkcanBrandLabel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TalkcanMark(modifier = Modifier.size(42.dp))
        Text(
            text = "talkcan",
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
internal fun TalkcanMark(
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.onBackground
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.055f
        val canTopLeft = Offset(size.width * 0.05f, size.height * 0.18f)
        val canSize = Size(size.width * 0.34f, size.height * 0.54f)
        val radius = size.minDimension * 0.08f

        drawRoundRect(
            color = CanRed,
            topLeft = canTopLeft,
            size = canSize,
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = outline,
            topLeft = canTopLeft,
            size = canSize,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = outline,
            start = Offset(size.width * 0.09f, size.height * 0.27f),
            end = Offset(size.width * 0.35f, size.height * 0.27f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = StringYellow,
            start = Offset(size.width * 0.34f, size.height * 0.66f),
            end = Offset(size.width * 0.59f, size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val branch = Offset(size.width * 0.59f, size.height * 0.62f)
        val endpoints = listOf(
            Offset(size.width * 0.88f, size.height * 0.28f) to RadioBlue,
            Offset(size.width * 0.92f, size.height * 0.60f) to SignalGreen,
            Offset(size.width * 0.82f, size.height * 0.84f) to CanRed,
        )
        endpoints.forEach { (endpoint, endpointColor) ->
            drawLine(
                color = StringYellow,
                start = branch,
                end = endpoint,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = endpointColor,
                radius = size.minDimension * 0.075f,
                center = endpoint,
            )
            drawCircle(
                color = Ink,
                radius = size.minDimension * 0.075f,
                center = endpoint,
                style = Stroke(width = stroke * 0.6f),
            )
        }
    }
}

internal enum class TalkcanStatusTone {
    Neutral,
    Active,
    Ready,
    Recording,
    Attention,
    Error,
}

@Composable
internal fun TalkcanStatusBadge(
    label: String,
    tone: TalkcanStatusTone,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (tone) {
        TalkcanStatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
        TalkcanStatusTone.Active -> MaterialTheme.colorScheme.primaryContainer
        TalkcanStatusTone.Ready -> MaterialTheme.colorScheme.tertiaryContainer
        TalkcanStatusTone.Recording -> StringYellow
        TalkcanStatusTone.Attention -> MaterialTheme.colorScheme.secondaryContainer
        TalkcanStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        TalkcanStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        TalkcanStatusTone.Active -> MaterialTheme.colorScheme.onPrimaryContainer
        TalkcanStatusTone.Ready -> MaterialTheme.colorScheme.onTertiaryContainer
        TalkcanStatusTone.Recording -> Ink
        TalkcanStatusTone.Attention -> MaterialTheme.colorScheme.onSecondaryContainer
        TalkcanStatusTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val signalColor = when (tone) {
        TalkcanStatusTone.Neutral -> MaterialTheme.colorScheme.outline
        TalkcanStatusTone.Active -> RadioBlue
        TalkcanStatusTone.Ready -> SignalGreen
        TalkcanStatusTone.Recording -> CanRed
        TalkcanStatusTone.Attention -> StringYellow
        TalkcanStatusTone.Error -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(signalColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun TalkcanSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
