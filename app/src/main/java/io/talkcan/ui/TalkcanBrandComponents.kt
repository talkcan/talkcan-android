package io.talkcan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.talkcan.R
import io.talkcan.ui.theme.Graphite
import io.talkcan.ui.theme.MutedSteel
import io.talkcan.ui.theme.NearBlack
import io.talkcan.ui.theme.SignalAmber
import io.talkcan.ui.theme.StatusCyan
import io.talkcan.ui.theme.TalkcanError
import io.talkcan.ui.theme.WarmAluminum

@Composable
internal fun TalkcanInstrumentBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(Graphite)
            .drawBehind {
                val gridSize = 32.dp.toPx()
                val lineColor = Color.White.copy(alpha = 0.04f)
                var x = 0f
                while (x < size.width) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += gridSize
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += gridSize
                }
            }
    ) {
        content()
    }
}

@Composable
internal fun TalkcanInstrumentPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    containerColor: Color = NearBlack,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(2.dp))
            .drawBehind {
                val tickLength = 6.dp.toPx()
                val tickColor = borderColor
                drawLine(tickColor, Offset(0f, 0f), Offset(tickLength, 0f), 1f)
                drawLine(tickColor, Offset(0f, 0f), Offset(0f, tickLength), 1f)
                drawLine(tickColor, Offset(size.width, 0f), Offset(size.width - tickLength, 0f), 1f)
                drawLine(tickColor, Offset(size.width, 0f), Offset(size.width, tickLength), 1f)
                drawLine(tickColor, Offset(0f, size.height), Offset(tickLength, size.height), 1f)
                drawLine(tickColor, Offset(0f, size.height), Offset(0f, size.height - tickLength), 1f)
                drawLine(tickColor, Offset(size.width, size.height), Offset(size.width - tickLength, size.height), 1f)
                drawLine(tickColor, Offset(size.width, size.height), Offset(size.width, size.height - tickLength), 1f)
            }
            .padding(contentPadding)
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}
@Composable
internal fun TalkcanBrandLabel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TalkcanMark(
            modifier = Modifier.size(if (compact) 52.dp else 60.dp),
        )
        Text(
            text = "TALKCAN",
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.displaySmall
            },
            color = color,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
internal fun TalkcanMark(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.talkcan_mark),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
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
    val toneColor = when (tone) {
        TalkcanStatusTone.Neutral -> WarmAluminum.copy(alpha = 0.72f)
        TalkcanStatusTone.Active -> SignalAmber
        TalkcanStatusTone.Ready -> StatusCyan
        TalkcanStatusTone.Recording -> SignalAmber
        TalkcanStatusTone.Attention -> WarmAluminum
        TalkcanStatusTone.Error -> TalkcanError
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(NearBlack)
            .border(1.dp, toneColor, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(toneColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = toneColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
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
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .background(SignalAmber)
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
        ) {
            Text(
                text = title,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = if (compact) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                shape = RoundedCornerShape(2.dp)
            ) {
                Text(
                    text = actionLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
