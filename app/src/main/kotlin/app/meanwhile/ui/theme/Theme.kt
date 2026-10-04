package app.meanwhile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Glucose color coding (spec §15): in range green, high amber/red, low red/purple. */
@Immutable
data class GlucoseColors(
    val inRange: Color,
    val high: Color,
    val veryHigh: Color,
    val low: Color,
    val veryLow: Color,
    val stale: Color,
    val aiProposed: Color,
)

private val LightGlucose = GlucoseColors(
    inRange = Color(0xFF2E7D32),
    high = Color(0xFFB26A00),
    veryHigh = Color(0xFFC62828),
    low = Color(0xFFC62828),
    veryLow = Color(0xFF6A1B9A),
    stale = Color(0xFF757575),
    aiProposed = Color(0xFF5E35B1),
)

private val DarkGlucose = GlucoseColors(
    inRange = Color(0xFF81C784),
    high = Color(0xFFFFCA28),
    veryHigh = Color(0xFFEF5350),
    low = Color(0xFFEF5350),
    veryLow = Color(0xFFCE93D8),
    stale = Color(0xFF9E9E9E),
    aiProposed = Color(0xFFB39DDB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F5E5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4ECEB),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFFAFBF9),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFBF9),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E3),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainer = Color(0xFFEEF1EF),
    surfaceContainerHigh = Color(0xFFE6EAE8),
    outline = Color(0xFF6F7979),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D4),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
    surfaceContainer = Color(0xFF1C2020),
    surfaceContainerHigh = Color(0xFF262B2A),
    outline = Color(0xFF889392),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val LocalGlucoseColors = staticCompositionLocalOf { LightGlucose }

@Composable
fun MeanwhileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGlucoseColors provides if (darkTheme) DarkGlucose else LightGlucose) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography(),
            content = content,
        )
    }
}

/** Color for a glucose value in mg/dL. */
fun GlucoseColors.forMgDl(mgDl: Int?, stale: Boolean = false): Color = when {
    mgDl == null || stale -> this.stale
    mgDl < 54 -> veryLow
    mgDl < 70 -> low
    mgDl <= 180 -> inRange
    mgDl <= 250 -> high
    else -> veryHigh
}
