package dev.xvin.palimpsest.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.BlurMaskFilter
import android.graphics.RadialGradient
import dev.xvin.palimpsest.core.render.Align
import dev.xvin.palimpsest.core.render.Face
import dev.xvin.palimpsest.core.render.Font
import dev.xvin.palimpsest.core.render.Pen
import dev.xvin.palimpsest.core.render.VideoFrame
import dev.xvin.palimpsest.data.settings.VisualizerMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class RenderMode { COVER, LYRICS }

enum class RenderAspect(val width: Int, val height: Int) { VERTICAL(1080, 1920), HORIZONTAL(1920, 1080) }

/**
 * Output quality. Frames are laid out on a 1080-based canvas and scaled, so [scale] only changes
 * the pixel count; 60 fps makes the lyric glide and the visualiser noticeably smoother.
 */
enum class RenderQuality(val scale: Float, val fps: Int, val videoBitrate: Int, val audioBitrate: Int, val labelRes: Int) {
    STANDARD(1f, 30, 6_000_000, 256_000, dev.xvin.palimpsest.R.string.render_quality_standard),
    HIGH(1f, 60, 8_000_000, 320_000, dev.xvin.palimpsest.R.string.render_quality_high),
    MAX(4f / 3, 60, 12_000_000, 320_000, dev.xvin.palimpsest.R.string.render_quality_max),
    /** Only for the dialog's still, never offered. */
    PREVIEW(0.5f, 30, 0, 0, dev.xvin.palimpsest.R.string.render_quality_standard);

    fun width(a: RenderAspect) = (a.width * scale).toInt() and 1.inv()
    fun height(a: RenderAspect) = (a.height * scale).toInt() and 1.inv()
}

/**
 * [Pen] on an Android canvas, for the video frames laid out in :core ([VideoFrame]). Text falls
 * back to system fonts by itself for what ours lack (♪, CJK titles).
 */
class AndroidPen(private val c: Canvas, private val cover: Bitmap?, private val fonts: (Face) -> Typeface) : Pen {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaints = HashMap<Font, TextPaint>()
    private val coverShader = cover?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurred by lazy { cover?.let { blurredCover(it) } }
    private val r = RectF()

    override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {
        fill.color = color
        if (radius > 0f) c.drawRoundRect(l, t, r, b, radius, radius, fill) else c.drawRect(l, t, r, b, fill)
    }

    override fun stroke(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {
        strokePaint.color = color; strokePaint.strokeWidth = width; strokePaint.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(l, t, r, b, radius, radius, strokePaint)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        strokePaint.color = color; strokePaint.strokeWidth = width; strokePaint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x0, y0, x1, y1, strokePaint)
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) { fill.color = color; c.drawCircle(cx, cy, r, fill) }

    override fun verticalGradient(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        c.drawRect(l, t, r, b, Paint().apply { shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP) })
    }

    override fun glow(cx: Float, cy: Float, radius: Float, inner: Int, outer: Int) {
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, radius, inner, outer, Shader.TileMode.CLAMP) })
    }

    override fun shadow(l: Float, t: Float, r: Float, b: Float, radius: Float, blur: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; maskFilter = BlurMaskFilter(blur / 2, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(l, t, r, b, radius, radius, p)
    }

    override fun cover(l: Float, t: Float, r: Float, b: Float, radius: Float, fallback: Int) {
        val shader = coverShader
        val bmp = cover
        if (shader == null || bmp == null) return rect(l, t, r, b, fallback, radius)
        val m = Matrix()
        val k = maxOf((r - l) / bmp.width, (b - t) / bmp.height)
        m.setScale(k, k)
        m.postTranslate(l + ((r - l) - bmp.width * k) / 2, t + ((b - t) - bmp.height * k) / 2)
        shader.setLocalMatrix(m)
        coverPaint.shader = shader
        c.drawRoundRect(l, t, r, b, radius, radius, coverPaint)
    }

    override fun blurredCover(l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val img = blurred ?: return
        val side = maxOf(r - l, b - t)
        val cx = (l + r) / 2; val cy = (t + b) / 2
        this.r.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        c.drawBitmap(img, null, this.r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { this.alpha = (alpha * 255).toInt() })
    }

    private fun paint(f: Font) = textPaints.getOrPut(f) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts(f.face); textSize = f.size; fontVariationSettings = "'wght' ${f.weight}" }
    }

    override fun text(s: String, x: Float, y: Float, font: Font, color: Int, align: Align, shadow: Boolean) {
        val p = paint(font)
        p.color = color
        p.textAlign = when (align) { Align.START -> Paint.Align.LEFT; Align.CENTER -> Paint.Align.CENTER; Align.END -> Paint.Align.RIGHT }
        c.drawText(s, x, y, p)
    }

    override fun width(s: String, font: Font) = paint(font).measureText(s)
    override fun ascent(font: Font) = -paint(font).fontMetrics.ascent
    override fun descent(font: Font) = paint(font).fontMetrics.descent
    override fun save() { c.save() }
    override fun restore() { c.restore() }
    override fun clip(l: Float, t: Float, r: Float, b: Float) { c.clipRect(l, t, r, b) }
    override fun translate(dx: Float, dy: Float) { c.translate(dx, dy) }
    override fun scale(s: Float) { c.scale(s, s) }
    override fun rotate(degrees: Float, px: Float, py: Float) { c.rotate(degrees, px, py) }
}

/** The app's fonts for video frames. */
fun videoFonts(context: android.content.Context): (Face) -> Typeface {
    fun load(id: Int) = androidx.core.content.res.ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT
    val map = mapOf(
        Face.SERIF to load(dev.xvin.palimpsest.R.font.literata), Face.DISPLAY to load(dev.xvin.palimpsest.R.font.unbounded),
        Face.SANS to load(dev.xvin.palimpsest.R.font.onest), Face.MONO to load(dev.xvin.palimpsest.R.font.jetbrains_mono),
    )
    return { map.getValue(it) }
}

/**
 * Draws [VideoFrame]s at the output size: [background] once (a bitmap the video track is made
 * of), [draw] for the moving part on a clear overlay above it.
 */
class FrameRenderer(private val frame: VideoFrame, private val cover: Bitmap?, private val fonts: (Face) -> Typeface, private val quality: RenderQuality, aspect: RenderAspect, private val spectrum: SpectrumTimeline?) {
    private val w = quality.width(aspect)
    private val h = quality.height(aspect)
    private val k = w / frame.width

    val background: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.scale(k, k)
        frame.background(AndroidPen(c, cover, fonts))
    }

    private var pen: AndroidPen? = null
    private var penCanvas: Canvas? = null

    fun draw(c: Canvas, tMs: Long, levels: FloatArray = spectrum?.at(tMs * 1000) ?: VideoFrame.previewLevels()) {
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val p = if (penCanvas === c) pen!! else AndroidPen(c, cover, fonts).also { pen = it; penCanvas = c }
        c.save()
        c.scale(k, k)
        frame.draw(p, tMs, levels)
        c.restore()
    }

    /** A still for the dialog: the background with the frame at [tMs] over it. */
    fun still(tMs: Long): Bitmap = background.copy(Bitmap.Config.ARGB_8888, true).also { bmp ->
        val c = Canvas(bmp)
        c.scale(k, k)
        frame.draw(AndroidPen(c, cover, fonts), tMs, VideoFrame.previewLevels())
    }
}

/**
 * The cover as a smooth, heavily blurred square: centre-cropped, shrunk in halving steps (a single
 * big jump skips most source pixels and keeps hard edges), then blurred for real. Blown up to the
 * whole frame it stays smooth, with no visible grid of enlarged pixels.
 */
private fun blurredCover(cover: Bitmap, size: Int = 192, radius: Int = 9): Bitmap {
    val side = min(cover.width, cover.height)
    var b = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
    while (b.width >= size * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    b = Bitmap.createScaledBitmap(b, size, size, true).let { if (it.config == Bitmap.Config.ARGB_8888 && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
    val px = IntArray(size * size)
    b.getPixels(px, 0, size, 0, 0, size, size)
    val tmp = IntArray(px.size)
    // Three box blurs in a row are close to a Gaussian (sigma ≈ radius); each pass blurs the rows
    // and writes them transposed, so two calls cover both directions.
    repeat(3) {
        boxBlurRowsTransposed(px, tmp, size, size, radius)
        boxBlurRowsTransposed(tmp, px, size, size, radius)
    }
    b.setPixels(px, 0, size, 0, 0, size, size)
    return b
}

/** Box-blurs each row of [src] (w × h, edges clamped) into [dst] transposed (h × w). */
private fun boxBlurRowsTransposed(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var a = 0; var red = 0; var g = 0; var b = 0
        for (i in -r..r) {
            val p = src[row + i.coerceIn(0, w - 1)]
            a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (x in 0 until w) {
            dst[x * h + y] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
            val pin = src[row + min(x + r + 1, w - 1)]
            val out = src[row + maxOf(x - r, 0)]
            a += (pin ushr 24) - (out ushr 24)
            red += ((pin shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((pin shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (pin and 0xFF) - (out and 0xFF)
        }
    }
}
