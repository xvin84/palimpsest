package dev.xvin.palimpsest.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import java.util.Locale

/** The chosen language: "ru", "en", or "" for the system's. Compose state, so switching it redraws every screen. */
var language by mutableStateOf("")
private val systemRu = Locale.getDefault().language == "ru"
/** Russian or English: the chosen language, else the system's. */
val ru: Boolean get() = when (language) { "ru" -> true; "en" -> false; else -> systemRu }
fun t(ruText: String, enText: String) = if (ru) ruText else enText

/** Background layers, rules and three strengths of text — the same roles as on Android. */
data class Pal(
    val paper: Color, val paper2: Color, val paper3: Color, val line: Color,
    val text: Color, val muted: Color, val faint: Color, val light: Boolean,
)

val LocalPal = staticCompositionLocalOf { palimpsest(DesktopTheme.DARK) }
val LocalAccent = staticCompositionLocalOf { Color(0xFFE4572E) }
val LocalTint = staticCompositionLocalOf { Color(0xFF2A2A30) }
val LocalStyle = staticCompositionLocalOf { DesktopStyle.PALIMPSEST }

val Vermilion = Color(0xFFE4572E)

fun palimpsest(theme: DesktopTheme) = when (theme) {
    DesktopTheme.DARK -> Pal(Color(0xFF0F0E0C), Color(0xFF171513), Color(0xFF211E1B), Color(0xFF2E2A26), Color(0xFFEDE6DA), Color(0xFF9C9488), Color(0xFF6A635A), false)
    DesktopTheme.AMOLED -> Pal(Color(0xFF000000), Color(0xFF0B0A09), Color(0xFF161412), Color(0xFF242120), Color(0xFFEDE6DA), Color(0xFF9C9488), Color(0xFF66605A), false)
    DesktopTheme.LIGHT -> Pal(Color(0xFFF7F2E8), Color(0xFFEFE8DA), Color(0xFFE5DCCB), Color(0xFFD3C8B4), Color(0xFF1E1A15), Color(0xFF675F53), Color(0xFF948A7C), true)
}

/** Lane: near-black with a breath of the cover's colour, white text. */
fun lane(theme: DesktopTheme, tint: Color): Pal = when (theme) {
    DesktopTheme.LIGHT -> Pal(lerp(Color(0xFFF7F7F8), tint, 0.06f), lerp(Color(0xFFEDEDEF), tint, 0.08f), lerp(Color(0xFFE2E2E5), tint, 0.1f), Color(0xFFD0D0D5), Color(0xFF0E0E10), Color(0xFF5E5E66), Color(0xFF8E8E96), true)
    else -> Pal(
        if (theme == DesktopTheme.AMOLED) Color.Black else lerp(Color(0xFF0B0B0D), tint, 0.13f),
        lerp(Color(0xFF151518), tint, 0.15f), lerp(Color(0xFF1E1E22), tint, 0.17f), lerp(Color(0xFF2E2E33), tint, 0.12f),
        Color.White, Color(0xFFA7A7AF), Color(0xFF6E6E76), false,
    )
}

val Serif = FontFamily(Font(resource = "font/playfair_display.ttf", weight = FontWeight.Bold))
val Sans = FontFamily(
    Font(resource = "font/manrope.ttf", weight = FontWeight.Normal),
    Font(resource = "font/manrope.ttf", weight = FontWeight.Bold),
    Font(resource = "font/manrope.ttf", weight = FontWeight.ExtraBold),
)

private fun typography(style: DesktopStyle): Typography {
    val t = Typography()
    val display = if (style == DesktopStyle.LANE) Sans else Serif
    val w = if (style == DesktopStyle.LANE) FontWeight.ExtraBold else FontWeight.Bold
    val ls = if (style == DesktopStyle.LANE) (-0.8).sp else (-0.3).sp
    return t.copy(
        displayLarge = TextStyle(fontFamily = display, fontWeight = w, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = ls),
        displayMedium = TextStyle(fontFamily = display, fontWeight = w, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = ls),
        headlineLarge = TextStyle(fontFamily = display, fontWeight = w, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = ls),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = w, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = TextStyle(fontFamily = display, fontWeight = w, fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Keeps a cover's colour vivid and legible as an accent on ink (or darker on paper). */
fun readable(c: Color, light: Boolean): Color {
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    if (hsv[1] < 0.18f) return if (light) Color(0xFFC2410C) else Vermilion
    val s = hsv[1].coerceIn(if (light) 0.55f else 0.45f, if (light) 0.95f else 0.85f)
    val b = if (light) hsv[2].coerceIn(0.45f, 0.66f) else hsv[2].coerceIn(0.78f, 0.95f)
    return Color(java.awt.Color.HSBtoRGB(hsv[0], s, b))
}

@Composable
fun PalimpsestDesktopTheme(style: DesktopStyle, theme: DesktopTheme, accent: Color, tint: Color, content: @Composable () -> Unit) {
    val animTint by animateColorAsState(tint, tween(700))
    val pal = if (style == DesktopStyle.LANE) lane(theme, animTint) else palimpsest(theme)
    val a by animateColorAsState(readable(accent, pal.light), tween(600))
    val primary = if (style == DesktopStyle.LANE) pal.text else a
    val onPrimary = if (style == DesktopStyle.LANE) (if (pal.light) Color.White else Color.Black) else if (a.luminance() > 0.45f) Color.Black else Color.White
    val base = if (pal.light) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = primary, onPrimary = onPrimary, secondary = pal.muted, background = pal.paper, onBackground = pal.text,
        surface = pal.paper2, onSurface = pal.text, surfaceVariant = pal.paper3, onSurfaceVariant = pal.muted,
        surfaceContainer = pal.paper2, surfaceContainerHigh = pal.paper3, surfaceContainerHighest = pal.paper3, surfaceContainerLow = pal.paper2,
        outline = pal.line, outlineVariant = pal.line, tertiary = a, primaryContainer = a.copy(alpha = 0.22f),
    )
    CompositionLocalProvider(LocalPal provides pal, LocalAccent provides a, LocalTint provides animTint, LocalStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = typography(style)) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides pal.text, content = content)
        }
    }
}
