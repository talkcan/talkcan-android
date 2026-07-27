package io.talkcan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val NightOpsScheme = darkColorScheme(
    primary = TalkcanCyan,
    onPrimary = NightVoid,
    secondary = AlertAmber,
    onSecondary = NightVoid,
    background = NightVoid,
    onBackground = NightTextPrimary,
    surface = NightHull,
    onSurface = NightTextPrimary,
    surfaceVariant = Color(0xFF222A33),
    onSurfaceVariant = NightTextSecondary,
    outline = Color(0xFF3A4652),
    error = AlertAmber,
    onError = NightVoid,
)

private val DaylightScheme = lightColorScheme(
    primary = CommandGold,
    onPrimary = DayTextPrimary,
    secondary = SciencesBlue,
    onSecondary = Color.White,
    background = HullWhite,
    onBackground = DayTextPrimary,
    surface = DeckWhite,
    onSurface = DayTextPrimary,
    surfaceVariant = Color(0xFFE9E9E2),
    onSurfaceVariant = DayTextSecondary,
    outline = Color(0xFFC8C8C0),
    error = SciencesBlue,
    onError = Color.White,
)

private val TalkcanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun TalkcanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightOpsScheme else DaylightScheme,
        typography = TalkcanTypography,
        shapes = TalkcanShapes,
        content = content,
    )
}
