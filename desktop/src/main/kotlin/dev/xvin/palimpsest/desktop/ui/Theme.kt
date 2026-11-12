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
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
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
    // «Тетрадь»: warm ink, a touch browner and softer than the first Palimpsest.
    DesktopTheme.DARK -> Pal(Color(0xFF15120F), Color(0xFF1C1814), Color(0xFF241F1A), Color(0xFF2E2822), Color(0xFFEFE7DA), Color(0xFFA99F90), Color(0xFF7D7468), false)
    DesktopTheme.AMOLED -> Pal(Color(0xFF000000), Color(0xFF0B0A09), Color(0xFF161412), Color(0xFF242120), Color(0xFFEDE6DA), Color(0xFF9C9488), Color(0xFF66605A), false)
    DesktopTheme.LIGHT -> Pal(Color(0xFFF7F2E8), Color(0xFFEFE8DA), Color(0xFFE5DCCB), Color(0xFFD3C8B4), Color(0xFF1E1A15), Color(0xFF675F53), Color(0xFF948A7C), true)
}

/** «Обложка»: the cover's colour, vivid, over the whole window; the window paints it as a gradient (see [coverBackdrop]). */
fun cover(theme: DesktopTheme, tint: Color): Pal = when (theme) {
    DesktopTheme.LIGHT -> Pal(lerp(Color(0xFFF4F1F3), tint, 0.16f), lerp(Color(0xFFFFFFFF), tint, 0.10f), lerp(Color(0xFFEAE5E8), tint, 0.16f), Color(0x22000000), Color(0xFF140C10), Color(0xFF5E5258), Color(0xFF8E8288), true)
    else -> Pal(
        if (theme == DesktopTheme.AMOLED) Color.Black else lerp(Color(0xFF120A0E), tint, 0.22f),
        lerp(Color(0xFF1C1218), tint, 0.30f), lerp(Color(0xFF261A20), tint, 0.34f), Color(0x2EFFFFFF), Color.White, Color(0xFFD7CDD2), Color(0xFF9A8E94), false,
    )
}

/** «Пульт»: an instrument panel, green-black with one lime light. */
fun console(theme: DesktopTheme): Pal = when (theme) {
    DesktopTheme.LIGHT -> Pal(Color(0xFFF3F5F1), Color(0xFFE9ECE6), Color(0xFFDDE2DA), Color(0xFFC9CFC5), Color(0xFF0B0D0C), Color(0xFF4F5A54), Color(0xFF7E8A84), true)
    else -> Pal(if (theme == DesktopTheme.AMOLED) Color.Black else Color(0xFF0B0D0C), Color(0xFF121614), Color(0xFF181D1A), Color(0xFF232826), Color(0xFFE6ECE8), Color(0xFF9AA59F), Color(0xFF5E6A64), false)
}

/** «Обложка» paints the window in the cover's colour, falling to the page's further down; the rest just the page. */
@Composable
fun Modifier.coverBackdrop(): Modifier {
    val pal = LocalPal.current
    if (LocalStyle.current != DesktopStyle.COVER) return background(pal.paper)
    val tint = LocalTint.current
    return background(pal.paper).background(
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to lerp(pal.paper, tint, if (pal.light) 0.4f else 0.6f), 0.45f to lerp(pal.paper, tint, if (pal.light) 0.14f else 0.24f), 0.9f to pal.paper,
        ),
    )
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

// Both faces are variable fonts: each weight has to be asked for on the axis, or every weight
// comes out as the file's default instance (Manrope's is ExtraLight) made bold by the renderer.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun face(file: String, weight: Int) =
    Font(file, FontWeight(weight), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(weight)))

val Serif = FontFamily(face("font/literata.ttf", 600), face("font/literata.ttf", 700))
val Sans = FontFamily(listOf(400, 500, 600, 700, 800).map { face("font/manrope.ttf", it) })
/** «Обложка»'s headlines: wide, round and heavy (Unbounded). */
val Wide = FontFamily(listOf(500, 600, 700, 800).map { face("font/unbounded.ttf", it) })
/** «Пульт»'s text (Onest) and its figures and labels (JetBrains Mono). */
val Grotesk = FontFamily(listOf(400, 500, 600, 700, 800).map { face("font/onest.ttf", it) })
val Mono = FontFamily(listOf(400, 500, 600, 700).map { face("font/jetbrains_mono.ttf", it) })

/** The faces of the style in effect, for [Type] (which isn't composable). */
private var faces by mutableStateOf(DesktopStyle.PALIMPSEST)
private val textFace get() = if (faces == DesktopStyle.CONSOLE) Grotesk else Sans
private val figureFace get() = if (faces == DesktopStyle.CONSOLE) Mono else Sans

/** Corners on one scale, so things side by side agree. */
object Radius {
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

/**
 * Text styles for what M3's slots don't name: table rows, numbers that line up, the lyrics.
 *
 * The accent is kept for a few things so it means something: what's playing (its row, title and
 * the seek line), the play button, the screen's one main action and the current place in the
 * sidebar. Likes, volume, tabs and icons stay in the text colours.
 */
object Type {
    val rowTitle get() = TextStyle(fontFamily = textFace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val rowSub get() = TextStyle(fontFamily = textFace, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp)
    val meta get() = TextStyle(fontFamily = figureFace, fontWeight = FontWeight.Medium, fontSize = if (faces == DesktopStyle.CONSOLE) 11.5.sp else 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val caption get() = TextStyle(fontFamily = figureFace, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
    val lyric get() = TextStyle(fontFamily = if (faces == DesktopStyle.CONSOLE) Grotesk else Sans, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
}

private fun typography(style: DesktopStyle): Typography {
    val display = when (style) { DesktopStyle.LANE -> Sans; DesktopStyle.COVER -> Wide; DesktopStyle.CONSOLE -> Grotesk; DesktopStyle.PALIMPSEST -> Serif }
    val w = when (style) { DesktopStyle.LANE, DesktopStyle.COVER -> FontWeight.ExtraBold; else -> FontWeight.Bold }
    val ls = when (style) { DesktopStyle.LANE -> (-0.8).sp; DesktopStyle.COVER -> (-0.6).sp; DesktopStyle.CONSOLE -> (-0.4).sp; DesktopStyle.PALIMPSEST -> (-0.2).sp }
    // Unbounded is wide: the same size takes a third more room.
    val k = if (style == DesktopStyle.COVER) 0.86f else 1f
    fun d(size: Int, line: Int) = TextStyle(fontFamily = display, fontWeight = w, fontSize = (size * k).sp, lineHeight = (line * k).sp, letterSpacing = ls)
    val body = if (style == DesktopStyle.CONSOLE) Grotesk else Sans
    fun s(weight: FontWeight, size: Double, line: Int, spacing: Double = 0.0) =
        TextStyle(fontFamily = body, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)
    val label = if (style == DesktopStyle.CONSOLE) Mono else body
    // Serif only where a page or a song is named; everything that is read or clicked is Manrope.
    return Typography(
        displayLarge = d(40, 46), displayMedium = d(28, 34), displaySmall = d(24, 30),
        headlineLarge = d(26, 32), headlineMedium = d(21, 27), headlineSmall = s(FontWeight.Bold, 18.0, 24),
        titleLarge = s(FontWeight.Bold, 17.0, 22), titleMedium = s(FontWeight.SemiBold, 14.5, 20), titleSmall = s(FontWeight.SemiBold, 13.0, 18),
        bodyLarge = s(FontWeight.Medium, 14.0, 20), bodyMedium = s(FontWeight.Medium, 13.0, 18), bodySmall = s(FontWeight.Medium, 12.0, 16),
        labelLarge = s(FontWeight.SemiBold, 13.0, 18), labelMedium = s(FontWeight.SemiBold, 12.0, 16).copy(fontFamily = label), labelSmall = s(FontWeight.Bold, 10.5, 14, 1.0).copy(fontFamily = label),
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
    val light = theme == DesktopTheme.LIGHT
    // «Обложка» wants the cover's colour itself, vivid, not Lane's dark-safe version of it.
    val vivid by animateColorAsState(lerp(readable(accent, light), tint, 0.25f), tween(700))
    val pal = when (style) {
        DesktopStyle.LANE -> lane(theme, animTint)
        DesktopStyle.COVER -> cover(theme, vivid)
        DesktopStyle.CONSOLE -> console(theme)
        DesktopStyle.PALIMPSEST -> palimpsest(theme)
    }
    // «Пульт» has one lime light, whatever the cover: an instrument's indicator, not a mood.
    val a by animateColorAsState(if (style == DesktopStyle.CONSOLE) (if (pal.light) Color(0xFF4F6B00) else Color(0xFFC6F432)) else readable(accent, pal.light), tween(600))
    val white = style == DesktopStyle.LANE || style == DesktopStyle.COVER
    val primary = if (white) pal.text else a
    val onPrimary = if (white) (if (pal.light) Color.White else Color.Black) else if (a.luminance() > 0.45f) Color.Black else Color.White
    if (faces != style) faces = style
    val base = if (pal.light) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = primary, onPrimary = onPrimary, secondary = pal.muted, background = pal.paper, onBackground = pal.text,
        surface = pal.paper2, onSurface = pal.text, surfaceVariant = pal.paper3, onSurfaceVariant = pal.muted,
        surfaceContainer = pal.paper2, surfaceContainerHigh = pal.paper3, surfaceContainerHighest = pal.paper3, surfaceContainerLow = pal.paper2,
        outline = pal.line, outlineVariant = pal.line, tertiary = a, primaryContainer = a.copy(alpha = 0.22f),
        // Snackbars: the page's colours turned over, not Material's lilac grey.
        inverseSurface = pal.text, inverseOnSurface = pal.paper, inversePrimary = a,
    )
    // What's still Material (menus, snackbars) gets the same corners as the rest.
    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md), small = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg), large = androidx.compose.foundation.shape.RoundedCornerShape(Radius.xl),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(Radius.xl),
    )
    CompositionLocalProvider(LocalPal provides pal, LocalAccent provides a, LocalTint provides if (style == DesktopStyle.COVER) vivid else animTint, LocalStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = typography(style), shapes = shapes) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides pal.text, content = content)
        }
    }
}
