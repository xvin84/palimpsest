package dev.xvin.palimpsest.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xvin.palimpsest.data.settings.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.R

/*
 * Palimpsest look: warm ink-black "paper", ivory text, one vivid accent. The accent follows the
 * current cover art when dynamic colour is on, like ink of a different colour on the same page.
 */
object Ink {
    /** The palette in effect; a snapshot state, so switching themes recolours every screen at once. */
    internal var palette by mutableStateOf(Palette.Dark)

    val Paper get() = palette.paper
    val Paper2 get() = palette.paper2
    val Paper3 get() = palette.paper3
    val Line get() = palette.line
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Faint get() = palette.faint
    val isLight get() = palette.isLight
    val Vermilion = Color(0xFFE4572E)
}

/** Background layers (paper → paper4), rules and three text strengths. */
data class Palette(
    val paper: Color, val paper2: Color, val paper3: Color, val paper4: Color,
    val line: Color, val text: Color, val muted: Color, val faint: Color,
    val isLight: Boolean = false,
) {
    companion object {
        /** Warm ink-black, the original look. */
        val Dark = Palette(
            Color(0xFF0F0E0C), Color(0xFF171513), Color(0xFF211E1B), Color(0xFF2A2622),
            Color(0xFF2E2A26), Color(0xFFEDE6DA), Color(0xFF9C9488), Color(0xFF6A635A),
        )
        /** True black for OLED screens: pixels off, battery saved. */
        val Amoled = Palette(
            Color(0xFF000000), Color(0xFF0B0A09), Color(0xFF161412), Color(0xFF1E1C19),
            Color(0xFF242120), Color(0xFFEDE6DA), Color(0xFF9C9488), Color(0xFF66605A),
        )
        /** Old paper: warm off-white with brown-black ink. */
        val Light = Palette(
            Color(0xFFF7F2E8), Color(0xFFEFE8DA), Color(0xFFE5DCCB), Color(0xFFDBD1BE),
            Color(0xFFD3C8B4), Color(0xFF1E1A15), Color(0xFF675F53), Color(0xFF948A7C),
            isLight = true,
        )
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun serif(weight: Int) = Font(
    R.font.playfair_display,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Serif = FontFamily(serif(400), serif(600), serif(700), serif(800))

private val typography = Typography().let { t ->
    t.copy(
        displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
        displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp),
        headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 30.sp, lineHeight = 36.sp),
        headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** The accent currently in effect (default vermilion, or one picked from the cover). */
val LocalAccent = staticCompositionLocalOf { Ink.Vermilion }

@Composable
fun PalimpsestTheme(accent: Color = Ink.Vermilion, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val palette = when (theme) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
        ThemeMode.DARK -> Palette.Dark
        ThemeMode.AMOLED -> Palette.Amoled
        ThemeMode.LIGHT -> Palette.Light
    }
    // Set before any child reads Ink, so the first frame is already in the right colours.
    if (Ink.palette != palette) Ink.palette = palette
    val a by animateColorAsState(readableAccent(accent, palette.isLight), tween(600), label = "accent")
    val base = if (palette.isLight) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = a,
        onPrimary = if (a.luminance() > 0.45f) Ink.Paper else Color.White,
        primaryContainer = a.copy(alpha = 0.22f).compositeOver(Ink.Paper2),
        onPrimaryContainer = Ink.Text,
        secondary = Ink.Muted,
        onSecondary = Ink.Paper,
        secondaryContainer = Ink.Paper3,
        onSecondaryContainer = Ink.Text,
        tertiary = a,
        // Snackbar buttons sit on the inverse surface: the accent, made legible on it.
        inversePrimary = readableAccent(a, light = !palette.isLight),
        background = Ink.Paper,
        onBackground = Ink.Text,
        surface = Ink.Paper,
        onSurface = Ink.Text,
        surfaceVariant = Ink.Paper3,
        onSurfaceVariant = Ink.Muted,
        surfaceContainerLowest = Ink.Paper,
        surfaceContainerLow = Ink.Paper2,
        surfaceContainer = Ink.Paper2,
        surfaceContainerHigh = Ink.Paper3,
        surfaceContainerHighest = palette.paper4,
        outline = Ink.Line,
        outlineVariant = Ink.Line,
        error = if (palette.isLight) Color(0xFFC62828) else Color(0xFFFF6B6B),
        scrim = Color.Black,
    )
    CompositionLocalProvider(LocalAccent provides a) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // Text drawn outside a Surface/Scaffold must still be ivory, not the default black.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ink.Text, content = content)
        }
    }
}

/** Covers can be nearly black or grey; keep the accent vivid and legible on ink (or on paper, darker). */
fun readableAccent(c: Color, light: Boolean = false): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()),
        hsv,
    )
    if (hsv[1] < 0.18f) return if (light) Color(0xFFC2410C) else Ink.Vermilion
    hsv[1] = hsv[1].coerceIn(if (light) 0.55f else 0.45f, if (light) 0.95f else 0.85f)
    hsv[2] = if (light) hsv[2].coerceIn(0.45f, 0.66f) else hsv[2].coerceIn(0.78f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
