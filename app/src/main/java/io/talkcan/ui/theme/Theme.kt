package io.talkcan.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val TalkcanDarkScheme = darkColorScheme(
    primary = WarmAluminum,
    onPrimary = Graphite,
    primaryContainer = NearBlack,
    onPrimaryContainer = WarmAluminum,
    secondary = SignalAmber,
    onSecondary = Graphite,
    secondaryContainer = NearBlack,
    onSecondaryContainer = SignalAmber,
    tertiary = StatusCyan,
    onTertiary = Graphite,
    tertiaryContainer = NearBlack,
    onTertiaryContainer = StatusCyan,
    background = Graphite,
    onBackground = WarmAluminum,
    surface = NearBlack,
    onSurface = WarmAluminum,
    surfaceVariant = NearBlack,
    onSurfaceVariant = WarmAluminum.copy(alpha = 0.72f),
    outline = MutedSteel,
    outlineVariant = MutedSteel,
    error = TalkcanError,
    onError = Graphite,
    errorContainer = NearBlack,
    onErrorContainer = TalkcanError,
)

private val TalkcanShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

@Composable
fun TalkcanTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TalkcanDarkScheme,
        typography = TalkcanTypography,
        shapes = TalkcanShapes,
        content = content,
    )
}
