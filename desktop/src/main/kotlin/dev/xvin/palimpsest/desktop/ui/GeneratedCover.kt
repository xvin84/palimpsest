package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cover for music that has none, made from its name: two or three colours that go together, a
 * pattern (soft light, rings or waves), and — when there's room — the title and artist set in the
 * app's type. The same name always gives the same cover, so an album keeps its look everywhere.
 */
@Composable
fun GeneratedCover(title: String, artist: String?, modifier: Modifier = Modifier, withText: Boolean = true) {
    val art = remember(title, artist) { CoverRecipe.of(title + "\u0000" + artist.orEmpty()) }
    BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) { art.draw(this) }
        // Text only where it can be read: small tiles in lists keep just the colours and the pattern.
        if (withText && maxWidth >= 120.dp) {
            val w = maxWidth
            val px = with(LocalDensity.current) { w.toSp() }
            val shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 8f)
            Column(Modifier.align(Alignment.BottomStart).padding(w * 0.08f)) {
                artist?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it.uppercase(), color = Color.White.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = px * 0.045f, letterSpacing = px * 0.004f, shadow = shadow),
                    )
                }
                Text(
                    title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = px * 0.105f, lineHeight = px * 0.115f, fontWeight = FontWeight.Bold, shadow = shadow),
                )
            }
        }
    }
}

/** Everything about one generated cover, decided once from its seed. */
private class CoverRecipe(
    val angle: Float, val base: Color, val mid: Color, val light: Color, val pattern: Int,
    val spots: List<Triple<Offset, Float, Color>>, val rings: Offset, val phase: Float,
) {
    fun draw(d: DrawScope) = with(d) {
        val w = size.width
        val h = size.height
        val dir = Offset(cos(angle) * w, sin(angle) * h)
        drawRect(Brush.linearGradient(listOf(base, mid), start = Offset(w / 2, h / 2) - dir / 2f, end = Offset(w / 2, h / 2) + dir / 2f))
        when (pattern) {
            // Soft light: big blurred spots of the palette's colours.
            0 -> spots.forEach { (c, r, col) ->
                val center = Offset(c.x * w, c.y * h)
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.7f), col.copy(alpha = 0f)), center, r * w), r * w, center)
            }
            // Rings: circles from a point near a corner, like grooves of a record.
            1 -> {
                val center = Offset(rings.x * w, rings.y * h)
                drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.45f), light.copy(alpha = 0f)), center, w * 0.7f), w * 0.7f, center)
                var r = w * 0.12f
                var i = 0
                while (r < w * 1.6f) {
                    drawCircle(light.copy(alpha = if (i % 3 == 0) 0.32f else 0.13f), r, center, style = Stroke(width = if (i % 3 == 0) w * 0.012f else w * 0.005f))
                    r += w * 0.055f
                    i++
                }
            }
            // Waves: bands across, each a little lighter.
            else -> for (k in 0 until 5) {
                val y0 = h * (0.25f + k * 0.16f)
                val amp = h * (0.05f + 0.015f * k)
                val path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, y0)
                    var x = 0f
                    while (x <= w) {
                        lineTo(x, y0 + amp * sin((x / w * 2f * PI.toFloat() * 1.3f) + phase + k * 0.9f))
                        x += w / 48f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path, light.copy(alpha = 0.10f + 0.03f * k))
            }
        }
        // A darker foot, so the white title reads on any palette.
        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)))
    }

    companion object {
        fun of(seed: String): CoverRecipe {
            val rnd = java.util.Random(seed.hashCode().toLong() * 31 + seed.length)
            val hue = rnd.nextFloat() * 360f
            // Neighbouring hues, or one across the wheel for a contrast accent.
            val (h2, h3) = if (rnd.nextBoolean()) (hue + 30f) to (hue + 65f) else (hue + 25f) to (hue + 170f + rnd.nextFloat() * 30f)
            fun hsv(h: Float, s: Float, v: Float) = Color.hsv(((h % 360f) + 360f) % 360f, s, v)
            val base = hsv(hue, 0.55f + rnd.nextFloat() * 0.2f, 0.16f + rnd.nextFloat() * 0.08f)
            val mid = hsv(h2, 0.5f + rnd.nextFloat() * 0.2f, 0.42f + rnd.nextFloat() * 0.14f)
            val light = hsv(h3, 0.35f + rnd.nextFloat() * 0.25f, 0.82f + rnd.nextFloat() * 0.15f)
            val spots = List(4) { i ->
                Triple(Offset(rnd.nextFloat(), rnd.nextFloat() * 0.8f), 0.35f + rnd.nextFloat() * 0.4f, listOf(light, mid, hsv(h3 + 20f, 0.6f, 0.7f), light)[i])
            }
            val corner = Offset(if (rnd.nextBoolean()) 0.1f else 0.9f, if (rnd.nextBoolean()) 0.15f else 0.85f)
            return CoverRecipe(rnd.nextFloat() * 2f * PI.toFloat(), base, mid, light, rnd.nextInt(3), spots, corner, rnd.nextFloat() * 6f)
        }
    }
}
