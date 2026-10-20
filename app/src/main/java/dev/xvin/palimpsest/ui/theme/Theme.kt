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
import dev.xvin.palimpsest.data.settings.UiStyle
import androidx.compose.ui.graphics.lerp
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
        /**
         * Lane: near-black with a breath of the cover's colour, white text — the cover is the
         * decoration. On AMOLED the page stays pure black and only the layers above it take the tint.
         */
        fun lane(tint: Color, base: ThemeMode, systemDark: Boolean): Palette {
            val t = tint.copy(alpha = 1f)
            val light = base == ThemeMode.LIGHT || (base == ThemeMode.SYSTEM && !systemDark)
            if (light) {
                val bg = lerp(Color(0xFFF7F7F8), t, 0.06f)
                return Palette(
                    bg, lerp(Color(0xFFEDEDEF), t, 0.08f), lerp(Color(0xFFE2E2E5), t, 0.10f), lerp(Color(0xFFD6D6DA), t, 0.12f),
                    Color(0xFFD0D0D5), Color(0xFF0E0E10), Color(0xFF5E5E66), Color(0xFF8E8E96), isLight = true,
                )
            }
            val black = base == ThemeMode.AMOLED
            val ink = Color(0xFF0B0B0D)
            return Palette(
                if (black) Color.Black else lerp(ink, t, 0.13f),
                lerp(Color(0xFF151518), t, 0.15f), lerp(Color(0xFF1E1E22), t, 0.17f), lerp(Color(0xFF28282D), t, 0.18f),
                lerp(Color(0xFF2E2E33), t, 0.12f), Color(0xFFFFFFFF), Color(0xFFA7A7AF), Color(0xFF6E6E76),
            )
        }

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

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun sans(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Lane's display face: a tight geometric sans (Manrope, OFL). */
val Sans = FontFamily(sans(400), sans(500), sans(600), sans(700), sans(800))

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

private val laneTypography = Typography().let { t ->
    t.copy(
        displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-1.2).sp),
        displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
        headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
        headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(800), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** The accent currently in effect (default vermilion, or one picked from the cover). */
val LocalAccent = staticCompositionLocalOf { Ink.Vermilion }

/** The app-wide style (Palimpsest, Lane, …); screens that look different per style read it. */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.PALIMPSEST }

/** The cover's own (dark-safe) colour in Lane, for backdrops; the accent elsewhere. */
val LocalCoverTint = staticCompositionLocalOf { Ink.Vermilion }

@Composable
fun PalimpsestTheme(
    accent: Color = Ink.Vermilion,
    theme: ThemeMode = ThemeMode.DARK,
    style: UiStyle = UiStyle.PALIMPSEST,
    /** The cover's dominant colour; Lane tints its backgrounds with it. */
    tint: Color = accent,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val lane = style == UiStyle.LANE
    val animatedTint by animateColorAsState(tint, tween(700), label = "tint")
    val palette = if (lane) Palette.lane(animatedTint, theme, systemDark) else when (theme) {
        ThemeMode.SYSTEM -> if (systemDark) Palette.Dark else Palette.Light
        ThemeMode.DARK -> Palette.Dark
        ThemeMode.AMOLED -> Palette.Amoled
        ThemeMode.LIGHT -> Palette.Light
    }
    // Set before any child reads Ink, so the first frame is already in the right colours.
    if (Ink.palette != palette) Ink.palette = palette
    val a by animateColorAsState(readableAccent(accent, palette.isLight), tween(600), label = "accent")
    val base = if (palette.isLight) lightColorScheme() else darkColorScheme()
    // Lane's buttons are plain white (black on light) pills; the cover colour lives in the backdrop.
    val primary = if (lane) palette.text else a
    val scheme = base.copy(
        primary = primary,
        onPrimary = if (lane) palette.paper.let { if (palette.isLight) Color.White else Color.Black } else if (a.luminance() > 0.45f) Ink.Paper else Color.White,
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
    CompositionLocalProvider(LocalAccent provides a, LocalUiStyle provides style, LocalCoverTint provides animatedTint) {
        MaterialTheme(colorScheme = scheme, typography = if (lane) laneTypography else typography) {
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
