package dev.xvin.palimpsest.desktop.tray

import dev.xvin.palimpsest.desktop.data.TrayStyle
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.sin

/**
 * The tray icon, drawn by hand so it can live: the dot breathes, the bars follow the music, the
 * record spins — while something plays. Paused, it holds still and greys out.
 */
object TrayArt {
    /** What to draw: [levels] are four bar heights 0..1, [phase] an animation clock 0..1. */
    data class Look(
        val style: TrayStyle,
        val playing: Boolean,
        val accent: Int,
        val cover: Int,
        val levels: List<Float> = REST,
        val phase: Float = 0f,
    )

    /** The bars' shape at rest (paused, or nothing playing yet). */
    val REST = listOf(0.45f, 0.8f, 0.6f, 0.3f)

    private val INK = Color(0x0F0E0C)
    private val PAPER = Color(0xEDE6DA)
    private val MUTED = Color(0x9C9488)
    private val FAINT = Color(0x6A635A)

    private fun font(name: String): Font? = runCatching {
        TrayArt::class.java.getResourceAsStream("/font/$name")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }.getOrNull()
    private val serif by lazy { font("playfair_display.ttf") ?: Font(Font.SERIF, Font.BOLD, 12) }
    private val sans by lazy { font("manrope.ttf") ?: Font(Font.SANS_SERIF, Font.BOLD, 12) }

    fun draw(look: Look, size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size.toFloat()
        val accent = Color(look.accent)
        when (look.style) {
            // The app's own mark: a serif P on ink, and a dot that breathes in the cover's colour while playing.
            TrayStyle.LETTER -> {
                g.color = INK
                g.fill(RoundRectangle2D.Float(0f, 0f, s, s, s * 0.46f, s * 0.46f))
                letter(g, serif.deriveFont(Font.BOLD, s * 0.8f), PAPER, s, x = 0.42f)
                val pulse = if (look.playing) 0.5f + 0.5f * sin(look.phase * 2 * PI).toFloat() else 0f
                val r = s * (0.12f + 0.025f * pulse)
                if (look.playing) {
                    g.color = Color(accent.red, accent.green, accent.blue, (60 + 70 * pulse).toInt())
                    g.fill(Ellipse2D.Float(s * 0.77f - r * 1.6f, s * 0.75f - r * 1.6f, r * 3.2f, r * 3.2f))
                }
                g.color = if (look.playing) accent else FAINT
                g.fill(Ellipse2D.Float(s * 0.77f - r, s * 0.75f - r, r * 2, r * 2))
            }
            // Four bars that follow the music; at rest a still, quieter shape.
            TrayStyle.BARS -> {
                val w = s * 0.17f
                val gap = (s - 4 * w) / 5
                look.levels.take(4).forEachIndexed { i, v ->
                    val h = s * (0.16f + 0.74f * v.coerceIn(0f, 1f))
                    g.color = if (look.playing) accent else MUTED
                    g.fill(RoundRectangle2D.Float(gap + i * (w + gap), s * 0.95f - h, w, h, w, w))
                }
            }
            // A record with a label in the cover's colour and a glint that goes round while it plays.
            TrayStyle.VINYL -> {
                val c = s / 2
                g.color = Color(0x0A0807)
                g.fill(Ellipse2D.Float(s * 0.02f, s * 0.02f, s * 0.96f, s * 0.96f))
                g.color = Color(0x34302C)
                g.stroke = BasicStroke(s * 0.025f)
                for (k in listOf(0.40f, 0.32f)) g.draw(Ellipse2D.Float(c - s * k, c - s * k, s * 2 * k, s * 2 * k))
                val start = if (look.playing) -look.phase * 360f else 40f
                g.color = Color(255, 255, 255, if (look.playing) 120 else 60)
                g.stroke = BasicStroke(s * 0.06f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Arc2D.Float(c - s * 0.36f, c - s * 0.36f, s * 0.72f, s * 0.72f, start, 38f, Arc2D.OPEN))
                val label = Color(look.cover)
                g.color = if (look.playing) label else label.darker()
                g.fill(Ellipse2D.Float(c - s * 0.19f, c - s * 0.19f, s * 0.38f, s * 0.38f))
                g.color = Color(0x0A0807)
                g.fill(Ellipse2D.Float(c - s * 0.04f, c - s * 0.04f, s * 0.08f, s * 0.08f))
            }
            // Plain and quiet: a light P with a thin dark edge (readable on light and dark panels) and a dot, solid while playing.
            TrayStyle.MONO -> {
                val glyph = Color(0xE8E8E8)
                letter(g, sans.deriveFont(Font.BOLD, s * 0.9f), glyph, s, x = 0.4f, outline = Color(0, 0, 0, 110))
                val r = s * 0.11f
                val cx = s * 0.8f
                val cy = s * 0.78f
                if (look.playing) {
                    g.color = glyph
                    g.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
                } else {
                    g.color = glyph
                    g.stroke = BasicStroke(s * 0.05f)
                    g.draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
                }
            }
        }
        g.dispose()
        return img
    }

    /** A centred capital P, optionally with an outline around it. */
    private fun letter(g: java.awt.Graphics2D, font: Font, color: Color, s: Float, x: Float, outline: Color? = null) {
        val layout = java.awt.font.TextLayout("P", font, g.fontRenderContext)
        val b = layout.bounds
        val shape = layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(
            (s * x - b.width / 2 - b.x), (s / 2 - b.height / 2 - b.y),
        ))
        if (outline != null) {
            g.color = outline
            g.stroke = BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(shape)
        }
        g.color = color
        g.fill(shape)
    }

    /** The image as StatusNotifierItem wants it: ARGB32, big-endian. */
    fun argb(img: BufferedImage): ByteArray {
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val out = ByteArray(px.size * 4)
        for (i in px.indices) {
            val p = px[i]
            out[i * 4] = (p ushr 24).toByte(); out[i * 4 + 1] = (p ushr 16).toByte()
            out[i * 4 + 2] = (p ushr 8).toByte(); out[i * 4 + 3] = p.toByte()
        }
        return out
    }
}
