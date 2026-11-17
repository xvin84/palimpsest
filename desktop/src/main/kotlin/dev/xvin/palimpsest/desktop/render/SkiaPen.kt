package dev.xvin.palimpsest.desktop.render

import dev.xvin.palimpsest.core.render.Align
import dev.xvin.palimpsest.core.render.Face
import dev.xvin.palimpsest.core.render.Font
import dev.xvin.palimpsest.core.render.Pen
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Typeface

/** The typefaces for video frames, loaded once and cut to a weight on demand. */
object VideoFonts {
    private val base = mapOf(
        Face.SERIF to "font/literata.ttf", Face.DISPLAY to "font/unbounded.ttf", Face.SANS to "font/onest.ttf", Face.MONO to "font/jetbrains_mono.ttf",
    ).mapValues { (_, path) ->
        val bytes = VideoFonts::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes))!!
    }
    private val cut = HashMap<Pair<Face, Int>, Typeface>()

    @Synchronized
    fun typeface(face: Face, weight: Int): Typeface = cut.getOrPut(face to weight) {
        val tf = base.getValue(face)
        runCatching { tf.makeClone(FontVariation("wght", weight.toFloat())) }.getOrDefault(tf)
    }
}

/**
 * [Pen] on a Skia canvas. Text falls back to a system font, character by character, for what our
 * fonts lack (♪, ▶, CJK titles): Skia's drawString alone leaves such characters blank.
 */
class SkiaPen(private val c: Canvas, private val cover: Image?, private val blurred: () -> Image?) : Pen {
    private val fill = Paint().apply { isAntiAlias = true }
    private val strokePaint = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE }
    private val fonts = HashMap<Font, org.jetbrains.skia.Font>()
    private val runs = HashMap<Pair<String, Font>, List<Pair<String, org.jetbrains.skia.Font>>>()

    private fun rr(l: Float, t: Float, r: Float, b: Float, radius: Float) = RRect.makeLTRB(l, t, r, b, radius)
    private fun gradient(colors: IntArray) = Gradient(
        Gradient.Colors(colors.map { Color4f(it) }.toTypedArray(), floatArrayOf(0f, 1f), FilterTileMode.CLAMP, ColorSpace.sRGB),
        Gradient.Interpolation(),
    )

    override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {
        fill.color = color
        if (radius > 0f) c.drawRRect(rr(l, t, r, b, radius), fill) else c.drawRect(Rect.makeLTRB(l, t, r, b), fill)
    }

    override fun stroke(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {
        strokePaint.color = color; strokePaint.strokeWidth = width
        c.drawRRect(rr(l, t, r, b, radius), strokePaint)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        strokePaint.color = color; strokePaint.strokeWidth = width; strokePaint.strokeCap = PaintStrokeCap.ROUND
        c.drawLine(x0, y0, x1, y1, strokePaint)
        strokePaint.strokeCap = PaintStrokeCap.BUTT
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) { fill.color = color; c.drawCircle(cx, cy, r, fill) }

    override fun verticalGradient(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        Paint().use { p ->
            p.shader = Shader.makeLinearGradient(l, t, l, b, gradient(intArrayOf(top, bottom)))
            c.drawRect(Rect.makeLTRB(l, t, r, b), p)
        }
    }

    override fun glow(cx: Float, cy: Float, radius: Float, inner: Int, outer: Int) {
        Paint().use { p ->
            p.shader = Shader.makeRadialGradient(cx, cy, radius, gradient(intArrayOf(inner, outer)))
            c.drawCircle(cx, cy, radius, p)
        }
    }

    override fun shadow(l: Float, t: Float, r: Float, b: Float, radius: Float, blur: Float, color: Int) {
        Paint().use { p ->
            p.isAntiAlias = true; p.color = color
            p.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur / 2)
            c.drawRRect(rr(l, t, r, b, radius), p)
        }
    }

    override fun cover(l: Float, t: Float, r: Float, b: Float, radius: Float, fallback: Int) {
        val img = cover ?: return rect(l, t, r, b, fallback, radius)
        c.save()
        c.clipRRect(rr(l, t, r, b, radius), ClipMode.INTERSECT, true)
        c.drawImageRect(img, crop(img, (r - l) / (b - t)), Rect.makeLTRB(l, t, r, b), SamplingMode.LINEAR, null, true)
        c.restore()
    }

    override fun blurredCover(l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val img = blurred() ?: return
        Paint().use { p ->
            p.alpha = (alpha * 255).toInt()
            val side = maxOf(r - l, b - t)
            val cx = (l + r) / 2; val cy = (t + b) / 2
            c.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeLTRB(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2), SamplingMode.LINEAR, p, true)
        }
    }

    override fun text(s: String, x: Float, y: Float, font: Font, color: Int, align: Align, shadow: Boolean) {
        val parts = runsOf(s, font)
        val w = parts.sumOf { (t, f) -> f.measureTextWidth(t).toDouble() }.toFloat()
        var cx = when (align) { Align.START -> x; Align.CENTER -> x - w / 2; Align.END -> x - w }
        fill.color = color
        for ((t, f) in parts) { c.drawString(t, cx, y, f, fill); cx += f.measureTextWidth(t) }
    }

    override fun width(s: String, font: Font) = runsOf(s, font).sumOf { (t, f) -> f.measureTextWidth(t).toDouble() }.toFloat()
    override fun ascent(font: Font) = -skFont(font).metrics.ascent
    override fun descent(font: Font) = skFont(font).metrics.descent

    override fun save() { c.save() }
    override fun restore() { c.restore() }
    override fun clip(l: Float, t: Float, r: Float, b: Float) { c.clipRect(Rect.makeLTRB(l, t, r, b), ClipMode.INTERSECT, true) }
    override fun translate(dx: Float, dy: Float) { c.translate(dx, dy) }
    override fun scale(s: Float) { c.scale(s, s) }
    override fun rotate(degrees: Float, px: Float, py: Float) { c.rotate(degrees, px, py) }

    private fun skFont(f: Font) = fonts.getOrPut(f) { org.jetbrains.skia.Font(VideoFonts.typeface(f.face, f.weight), f.size) }

    /** [s] split into pieces our font can draw and pieces a system font has to. */
    private fun runsOf(s: String, font: Font) = runs.getOrPut(s to font) {
        val main = skFont(font)
        val out = ArrayList<Pair<String, org.jetbrains.skia.Font>>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val f = if (main.getUTF32Glyph(cp) != 0.toShort() || Character.isWhitespace(cp)) main
            else FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, cp)?.let { org.jetbrains.skia.Font(it, font.size) } ?: main
            val ch = s.substring(i, i + n)
            if (out.isNotEmpty() && out.last().second === f) out[out.lastIndex] = out.last().first + ch to f else out += ch to f
            i += n
        }
        out
    }

    private fun crop(img: Image, aspect: Float): Rect {
        val w = img.width.toFloat(); val h = img.height.toFloat()
        return if (w / h > aspect) { val cw = h * aspect; Rect.makeXYWH((w - cw) / 2, 0f, cw, h) } else { val ch = w / aspect; Rect.makeXYWH(0f, (h - ch) / 2, w, ch) }
    }

    companion object {
        /** The cover shrunk and blurred once, for the backdrop. */
        fun blur(cover: Image?): Image? {
            cover ?: return null
            val side = 256
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(side, side)
            Paint().use { p ->
                p.imageFilter = ImageFilter.makeBlur(18f, 18f, FilterTileMode.CLAMP)
                val w = cover.width.toFloat(); val h = cover.height.toFloat(); val m = minOf(w, h)
                surface.canvas.drawImageRect(cover, Rect.makeXYWH((w - m) / 2, (h - m) / 2, m, m), Rect.makeWH(side.toFloat(), side.toFloat()), SamplingMode.LINEAR, p, true)
            }
            return surface.makeImageSnapshot()
        }
    }
}
