package io.talkcan.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val TalkcanDarkScheme = darkColorScheme(
    primary = RadioBlue,
    onPrimary = TalkcanWhite,
    primaryContainer = DeepRadioBlue,
    onPrimaryContainer = PaleRadioBlue,
    secondary = StringYellow,
    onSecondary = Ink,
    secondaryContainer = DeepStringYellow,
    onSecondaryContainer = PaleStringYellow,
    tertiary = CanRed,
    onTertiary = Ink,
    tertiaryContainer = DeepCanRed,
    onTertiaryContainer = PaleCanRed,
    background = Ink,
    onBackground = Paper,
    surface = DarkSurface,
    onSurface = Paper,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Tin,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = CanRed,
    onError = Ink,
    errorContainer = DeepError,
    onErrorContainer = PaleError,
)

private val TalkcanLightScheme = lightColorScheme(
    primary = RadioBlue,
    onPrimary = TalkcanWhite,
    primaryContainer = PaleRadioBlue,
    onPrimaryContainer = DeepRadioBlue,
    secondary = StringYellow,
    onSecondary = Ink,
    secondaryContainer = PaleStringYellow,
    onSecondaryContainer = Ink,
    tertiary = CanRed,
    onTertiary = Ink,
    tertiaryContainer = PaleCanRed,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = TalkcanWhite,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = MutedText,
    outline = Tin,
    outlineVariant = SoftOutline,
    error = ErrorRed,
    onError = TalkcanWhite,
    errorContainer = PaleError,
    onErrorContainer = DeepError,
)

private val TalkcanShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun TalkcanTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TalkcanDarkScheme else TalkcanLightScheme,
        typography = TalkcanTypography,
        shapes = TalkcanShapes,
        content = content,
    )
}