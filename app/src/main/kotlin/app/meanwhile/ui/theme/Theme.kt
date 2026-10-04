package app.meanwhile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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

private val TealLight = lightColorScheme(
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

private val TealDark = darkColorScheme(
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

/** One selectable color palette: a full light and dark Material scheme. */
@Immutable
data class Palette(val id: String, val label: String, val light: ColorScheme, val dark: ColorScheme) {
    /** Color shown on the picker swatch. */
    fun swatch(dark: Boolean): Color = if (dark) this.dark.primary else light.primary
}

/**
 * The palette list (Settings → Appearance). "Teal" is the hand-tuned default; the rest are derived
 * from a hue so all of them stay readable in light and dark. Glucose colors (in range / high / low)
 * are deliberately NOT part of the palette — they always mean the same thing.
 */
object Palettes {
    val all: List<Palette> = listOf(
        Palette("teal", "Teal", TealLight, TealDark),
        hue("ocean", "Ocean", 215f),
        hue("sky", "Sky", 197f),
        hue("indigo", "Indigo", 245f),
        hue("violet", "Violet", 268f),
        hue("lavender", "Lavender", 285f, sat = 0.38f),
        hue("magenta", "Magenta", 322f),
        hue("rose", "Rose", 345f, sat = 0.45f),
        hue("crimson", "Crimson", 2f, sat = 0.48f),
        hue("sunset", "Sunset", 25f),
        hue("amber", "Amber", 42f),
        hue("olive", "Olive", 78f, sat = 0.40f),
        hue("forest", "Forest", 132f),
        hue("mint", "Mint", 160f),
        hue("slate", "Slate", 210f, sat = 0.16f),
        hue("espresso", "Espresso", 22f, sat = 0.28f),
        hue("mono", "Mono", 0f, sat = 0f),
    )

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: all.first()

    /** Light + dark Material schemes derived from one hue (tertiary shifted 60° for contrast). */
    private fun hue(id: String, label: String, h: Float, sat: Float = 0.55f): Palette {
        fun c(hh: Float, s: Float, l: Float) = Color.hsl((hh % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l)
        val t = h + 60f
        val light = lightColorScheme(
            primary = c(h, sat, 0.33f),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = c(h, sat * 1.2f, 0.88f),
            onPrimaryContainer = c(h, sat * 1.45f, 0.12f),
            secondary = c(h, sat * 0.4f, 0.35f),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = c(h, sat * 0.55f, 0.86f),
            onSecondaryContainer = c(h, sat * 0.7f, 0.11f),
            tertiary = c(t, sat * 0.55f, 0.38f),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = c(t, sat * 0.8f, 0.88f),
            onTertiaryContainer = c(t, sat * 0.9f, 0.12f),
            background = c(h, 0.14f, 0.98f),
            onBackground = c(h, 0.10f, 0.10f),
            surface = c(h, 0.14f, 0.98f),
            onSurface = c(h, 0.10f, 0.10f),
            surfaceVariant = c(h, 0.18f, 0.88f),
            onSurfaceVariant = c(h, 0.10f, 0.28f),
            surfaceContainer = c(h, 0.14f, 0.94f),
            surfaceContainerHigh = c(h, 0.14f, 0.91f),
            outline = c(h, 0.08f, 0.47f),
            error = Color(0xFFBA1A1A),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
        )
        val dark = darkColorScheme(
            primary = c(h, sat * 0.85f, 0.72f),
            onPrimary = c(h, sat, 0.15f),
            primaryContainer = c(h, sat * 0.85f, 0.25f),
            onPrimaryContainer = c(h, sat, 0.85f),
            secondary = c(h, sat * 0.30f, 0.72f),
            onSecondary = c(h, sat * 0.4f, 0.17f),
            secondaryContainer = c(h, sat * 0.35f, 0.26f),
            onSecondaryContainer = c(h, sat * 0.45f, 0.86f),
            tertiary = c(t, sat * 0.55f, 0.74f),
            onTertiary = c(t, sat * 0.6f, 0.17f),
            tertiaryContainer = c(t, sat * 0.45f, 0.28f),
            onTertiaryContainer = c(t, sat * 0.6f, 0.88f),
            background = c(h, 0.12f, 0.07f),
            onBackground = c(h, 0.06f, 0.88f),
            surface = c(h, 0.12f, 0.07f),
            onSurface = c(h, 0.06f, 0.88f),
            surfaceVariant = c(h, 0.12f, 0.28f),
            onSurfaceVariant = c(h, 0.08f, 0.78f),
            surfaceContainer = c(h, 0.12f, 0.11f),
            surfaceContainerHigh = c(h, 0.12f, 0.15f),
            outline = c(h, 0.06f, 0.57f),
            error = Color(0xFFFFB4AB),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
        return Palette(id, label, light, dark)
    }
}

@Composable
fun MeanwhileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    paletteId: String = "teal",
    content: @Composable () -> Unit,
) {
    val palette = Palettes.byId(paletteId)
    CompositionLocalProvider(LocalGlucoseColors provides if (darkTheme) DarkGlucose else LightGlucose) {
        MaterialTheme(
            colorScheme = if (darkTheme) palette.dark else palette.light,
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
