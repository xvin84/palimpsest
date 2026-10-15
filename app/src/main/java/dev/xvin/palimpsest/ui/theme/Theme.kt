package dev.xvin.palimpsest.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
    val Paper = Color(0xFF0F0E0C)
    val Paper2 = Color(0xFF171513)
    val Paper3 = Color(0xFF211E1B)
    val Line = Color(0xFF2E2A26)
    val Text = Color(0xFFEDE6DA)
    val Muted = Color(0xFF9C9488)
    val Faint = Color(0xFF6A635A)
    val Vermilion = Color(0xFFE4572E)
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
fun PalimpsestTheme(accent: Color = Ink.Vermilion, content: @Composable () -> Unit) {
    val a by animateColorAsState(readableAccent(accent), tween(600), label = "accent")
    val scheme = darkColorScheme(
        primary = a,
        onPrimary = if (a.luminance() > 0.45f) Ink.Paper else Color.White,
        primaryContainer = a.copy(alpha = 0.22f).compositeOver(Ink.Paper2),
        onPrimaryContainer = Ink.Text,
        secondary = Ink.Muted,
        onSecondary = Ink.Paper,
        secondaryContainer = Ink.Paper3,
        onSecondaryContainer = Ink.Text,
        tertiary = a,
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
        surfaceContainerHighest = Color(0xFF2A2622),
        outline = Ink.Line,
        outlineVariant = Ink.Line,
        error = Color(0xFFFF6B6B),
    )
    CompositionLocalProvider(LocalAccent provides a) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Covers can be nearly black or grey; keep the accent vivid and legible on ink. */
fun readableAccent(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()),
        hsv,
    )
    if (hsv[1] < 0.18f) return Ink.Vermilion
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.78f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
