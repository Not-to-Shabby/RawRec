@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.rawrec.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Slate = Color(0xFF0E1118)
private val SlateHigh = Color(0xFF161C26)
private val SlateHighest = Color(0xFF222B3A)
private val SlateHighestUp = Color(0xFF2D384D)

val CinemaDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFB8F6FF),
    secondary = Color(0xFFB2C8D2),
    onSecondary = Color(0xFF1C333C),
    secondaryContainer = Color(0xFF334A54),
    onSecondaryContainer = Color(0xFFCEE5EF),
    tertiary = Color(0xFFFFB4A9),
    onTertiary = Color(0xFF5F1610),
    tertiaryContainer = Color(0xFF7D2B22),
    onTertiaryContainer = Color(0xFFFFDAD4),
    error = Color(0xFFFF2D55),
    onError = Color(0xFF690013),
    errorContainer = Color(0xFF93001F),
    onErrorContainer = Color(0xFFFFDAD9),
    background = Slate,
    onBackground = Color(0xFFE2E2E9),
    surface = Slate,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = SlateHigh,
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainer = SlateHigh,
    surfaceContainerHigh = SlateHighest,
    surfaceContainerHighest = SlateHighestUp,
    surfaceDim = Color(0xFF090C12),
    surfaceBright = Color(0xFF323B4C),
    outline = Color(0xFF8690A2),
    outlineVariant = Color(0xFF3D4657),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2D3038),
    inversePrimary = Color(0xFF006876),
    scrim = Color(0xFF000000)
)

val CinemaLightScheme: ColorScheme = expressiveLightColorScheme()

// MD3 shape tokens (extraSmall 4, small 8, medium 12, large 16, extraLarge 28).
// Cards map to medium, chips/menus to small, dialogs/sheets to extra-large.
val ExpressiveShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// MD3 baseline type scale — standard sizes/weights/line-heights; no
// typeface baked into any scale role.
val CinemaTypography: Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Sensor/telemetry readouts (fps counters, file names, byte stats, debug
 * lines) — the app's ONE intentional monospace, kept out of the MD3 scale
 * so scale roles stay typeface-neutral. Size/weight match labelMedium.
 */
val TelemetryStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp
)

/** Emphasized telemetry for active/recording states (MD3 emphasized pattern). */
val TelemetryEmphasizedStyle: TextStyle = TelemetryStyle.copy(
    fontWeight = FontWeight.Bold
)

/** Micro-label style for studio deck parameter tiles (e.g. "SHUTTER", "ISO", "WB"). */
val DeckLabelStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 1.0.sp
)

/** High-legibility parameter readout style for studio deck parameter tiles. */
val DeckValueStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.5.sp
)

/** Monospaced timecode display for studio recording headers. */
val DeckTimecodeStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 22.sp,
    letterSpacing = 1.2.sp
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RawRecTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) CinemaDarkScheme else CinemaLightScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = CinemaTypography,
        content = content
    )
}
