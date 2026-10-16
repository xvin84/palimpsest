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
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.data.settings.VisualizerMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class RenderMode { COVER, LYRICS }
enum class RenderAspect(val width: Int, val height: Int) { VERTICAL(1080, 1920), HORIZONTAL(1920, 1080) }

/** Everything a frame needs; times in milliseconds. */
class FrameSpec(
    val aspect: RenderAspect,
    val mode: RenderMode,
    val visualizer: VisualizerMode,
    val accent: Int,
    val cover: Bitmap?,
    val title: String,
    val artist: String,
    val lyrics: ParsedLyrics?,
    val spectrum: SpectrumTimeline,
    val durationMs: Long,
    val serif: Typeface,
    val sans: Typeface,
)

/**
 * Draws one video frame at a given time onto a Canvas, in the app's look: the blurred cover as
 * the backdrop, the cover itself, the visualiser and either the title or the synced lyrics.
 */
class FrameRenderer(private val s: FrameSpec) {
    private val w = s.aspect.width.toFloat()
    private val h = s.aspect.height.toFloat()
    private val vertical = s.aspect == RenderAspect.VERTICAL
    private val unit = min(w, h) / 1080f

    /** Static backdrop, drawn once: blurred, darkened cover tinted with the accent. */
    val background: Bitmap = Bitmap.createBitmap(s.aspect.width, s.aspect.height, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val cover = s.cover
        if (cover != null) {
            // Shrinking to a few dozen pixels and scaling back up with filtering is a cheap, very soft blur.
            val tiny = Bitmap.createScaledBitmap(cover, 24, 24, true)
            val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 96, 96, true), 24, 24, true)
            val side = maxOf(w, h)
            c.drawBitmap(soft, null, RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.drawColor(withAlpha(s.accent, 0.12f))
        // Dark enough for white text on any cover, even a bright yellow one.
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Color.argb(150, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        })
    }

    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; setShadowLayer(48 * unit, 0f, 18 * unit, Color.argb(150, 0, 0, 0)) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.serif; color = Color.WHITE }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.sans; color = Color.argb(215, 255, 255, 255) }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.sans; color = Color.argb(170, 255, 255, 255) }
    private val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.serif }
    private val coverShader = s.cover?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val layouts = HashMap<Int, StaticLayout>()

    fun draw(c: Canvas, tMs: Long) {
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val levels = s.spectrum.at(tMs * 1000)
        when {
            s.mode == RenderMode.LYRICS && vertical -> lyricsVertical(c, tMs, levels)
            s.mode == RenderMode.LYRICS -> lyricsHorizontal(c, tMs, levels)
            vertical -> coverVertical(c, tMs, levels)
            else -> coverHorizontal(c, tMs, levels)
        }
        watermark(c)
        // Fade in from black and out to black at the edges of the track.
        val fade = minOf(tMs / 700f, (s.durationMs - tMs) / 900f, 1f).coerceAtLeast(0f)
        if (fade < 1f) c.drawColor(Color.argb(((1 - fade) * 255).toInt(), 0, 0, 0))
    }

    // ---- Cover mode ------------------------------------------------------------------------------

    private fun coverVertical(c: Canvas, t: Long, levels: FloatArray) {
        val ring = s.visualizer == VisualizerMode.RING
        val size = if (ring) 640 * unit else 860 * unit
        val cx = w / 2; val cy = if (ring) 760 * unit else 720 * unit
        val rect = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        if (ring) ring(c, cx, cy, size / 2 + 26 * unit, 150 * unit, levels)
        cover(c, rect, if (ring) size / 2 else 40 * unit)
        val textTop = if (ring) 1250 * unit else 1250 * unit
        titleBlock(c, cx, textTop, w - 140 * unit, center = true)
        val vizTop = 1460 * unit
        if (!ring) spectrum(c, RectF(90 * unit, vizTop, w - 90 * unit, vizTop + 230 * unit), levels)
        progress(c, RectF(90 * unit, h - 150 * unit, w - 90 * unit, h - 150 * unit), t)
    }

    private fun coverHorizontal(c: Canvas, t: Long, levels: FloatArray) {
        val ring = s.visualizer == VisualizerMode.RING
        val size = if (ring) 600f else 780f
        val cx = 140f + 780f / 2; val cy = h / 2
        if (ring) ring(c, cx, cy, size / 2 + 24, 130f, levels)
        cover(c, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), if (ring) size / 2 else 36f)
        val left = 1040f; val right = w - 120f
        titleBlock(c, left, 300f, right - left, center = false)
        if (!ring) spectrum(c, RectF(left, 520f, right, 800f), levels)
        progress(c, RectF(left, 900f, right, 900f), t)
    }

    // ---- Lyrics mode -----------------------------------------------------------------------------

    private fun lyricsVertical(c: Canvas, t: Long, levels: FloatArray) {
        val coverSize = 200 * unit
        cover(c, RectF(80 * unit, 150 * unit, 80 * unit + coverSize, 150 * unit + coverSize), 24 * unit)
        titleBlock(c, 80 * unit + coverSize + 40 * unit, 175 * unit, w - coverSize - 200 * unit, center = false, small = true)
        lyricsColumn(c, t, RectF(80 * unit, 470 * unit, w - 80 * unit, 1500 * unit), center = true)
        spectrum(c, RectF(80 * unit, 1560 * unit, w - 80 * unit, 1720 * unit), levels)
        progress(c, RectF(80 * unit, h - 140 * unit, w - 80 * unit, h - 140 * unit), t)
    }

    private fun lyricsHorizontal(c: Canvas, t: Long, levels: FloatArray) {
        val coverSize = 520f
        cover(c, RectF(120f, 150f, 120f + coverSize, 150f + coverSize), 30f)
        titleBlock(c, 120f, 730f, coverSize, center = false, small = true)
        spectrum(c, RectF(120f, 880f, 120f + coverSize, 980f), levels)
        lyricsColumn(c, t, RectF(760f, 90f, w - 110f, h - 150f), center = false)
        progress(c, RectF(760f, h - 90f, w - 110f, h - 90f), t)
    }

    /**
     * The current line sits in the middle of [area], bigger and in full white; the neighbours fade
     * out. A line change scrolls the column smoothly instead of jumping.
     */
    private fun lyricsColumn(c: Canvas, t: Long, area: RectF, center: Boolean) {
        val lyr = s.lyrics ?: return
        val lines = lyr.lines
        if (lines.isEmpty()) return
        val idx = lyr.indexAt(t).coerceAtLeast(-1)
        val width = area.width().toInt()
        val big = (if (vertical) 66 else 60) * unit
        val normal = (if (vertical) 46 else 42) * unit
        val gap = 34 * unit

        // Scroll position: eases from the previous line to the current one over 450 ms.
        val since = if (idx >= 0) t - lines[idx].timeMs else 0L
        val k = ease((since / 450f).coerceIn(0f, 1f))
        val focus = if (idx <= 0) idx.toFloat() else (idx - 1) + k

        c.save()
        c.clipRect(area)
        val anchorY = area.top + area.height() * 0.42f
        // Heights for lines around the focus, measured with the size each one would have.
        val first = (idx - 6).coerceAtLeast(0)
        val last = (idx + 8).coerceAtMost(lines.lastIndex)
        val heights = FloatArray(last - first + 1) { i -> layoutFor(first + i, lines[first + i].text, if (first + i == idx) big else normal, width, center).height + gap }
        // y of the focused line's top, then lay the others out above and below it.
        var yFocusTop = anchorY
        val fi = focus.toInt().coerceIn(first, last)
        val frac = focus - focus.toInt()
        yFocusTop -= heights[fi - first] * frac
        var y = yFocusTop
        for (i in fi - 1 downTo first) y -= heights[i - first]
        for (i in first..last) {
            val active = i == idx
            val dist = kotlin.math.abs(i - focus)
            val alpha = if (active) 255 else (215 - dist * 40).coerceIn(55f, 175f).toInt()
            val layout = layoutFor(i, lines[i].text, if (active) big else normal, width, center)
            layout.paint.color = if (active) Color.WHITE else Color.argb(alpha, 255, 255, 255)
            c.save()
            c.translate(area.left, y)
            layout.draw(c)
            c.restore()
            y += heights[i - first]
        }
        c.restore()
    }

    private fun layoutFor(i: Int, text: String, size: Float, width: Int, center: Boolean): StaticLayout {
        val key = i * 2 + if (size > 50 * unit) 1 else 0
        return layouts.getOrPut(key) {
            val p = TextPaint(lyricPaint).apply { textSize = size; isFakeBoldText = false }
            StaticLayout.Builder.obtain(text.ifBlank { "♪" }, 0, text.ifBlank { "♪" }.length, p, width)
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .build()
        }
    }

    // ---- Pieces ----------------------------------------------------------------------------------

    private fun cover(c: Canvas, r: RectF, radius: Float) {
        c.drawRoundRect(r, radius, radius, shadowPaint)
        val shader = coverShader
        val bmp = s.cover
        if (shader == null || bmp == null) {
            c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.accent })
            return
        }
        val m = Matrix()
        val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
        m.setScale(scale, scale)
        m.postTranslate(r.left + (r.width() - bmp.width * scale) / 2, r.top + (r.height() - bmp.height * scale) / 2)
        shader.setLocalMatrix(m)
        coverPaint.shader = shader
        c.drawRoundRect(r, radius, radius, coverPaint)
    }

    private fun titleBlock(c: Canvas, x: Float, top: Float, width: Float, center: Boolean, small: Boolean = false) {
        titlePaint.textSize = (if (small) 52 else 76) * unit
        artistPaint.textSize = (if (small) 36 else 44) * unit
        val title = TextUtils.ellipsize(s.title, titlePaint, width, TextUtils.TruncateAt.END).toString()
        val artist = TextUtils.ellipsize(s.artist, artistPaint, width, TextUtils.TruncateAt.END).toString()
        titlePaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        artistPaint.textAlign = titlePaint.textAlign
        val ty = top - titlePaint.ascent()
        c.drawText(title, x, ty, titlePaint)
        c.drawText(artist, x, ty + titlePaint.descent() + 14 * unit - artistPaint.ascent(), artistPaint)
    }

    private fun spectrum(c: Canvas, r: RectF, levels: FloatArray) {
        when (s.visualizer) {
            VisualizerMode.BARS, VisualizerMode.RING -> bars(c, r, levels)
            VisualizerMode.MIRROR -> mirror(c, r, levels)
            VisualizerMode.OFF -> Unit
        }
    }

    private fun bars(c: Canvas, r: RectF, levels: FloatArray) {
        val n = levels.size
        val step = r.width() / n
        val gap = step * 0.28f
        val bw = step - gap
        barPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, s.accent, withAlpha(s.accent, 0.3f), Shader.TileMode.CLAMP)
        for (i in 0 until n) {
            val bh = (levels[i] * r.height()).coerceAtLeast(bw * 0.6f)
            val x = r.left + i * step + gap / 2
            c.drawRoundRect(x, r.bottom - bh, x + bw, r.bottom, bw / 2, bw / 2, barPaint)
        }
    }

    private fun mirror(c: Canvas, r: RectF, levels: FloatArray) {
        val n = levels.size
        val step = r.width() / n
        val mid = r.centerY()
        linePaint.strokeWidth = step * 0.55f
        for (i in 0 until n) {
            val hh = (levels[i] * r.height() / 2).coerceAtLeast(2f)
            val x = r.left + i * step + step / 2
            linePaint.color = withAlpha(s.accent, 0.35f + levels[i] * 0.65f)
            c.drawLine(x, mid - hh, x, mid + hh, linePaint)
        }
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r0: Float, maxLen: Float, levels: FloatArray) {
        val n = levels.size
        linePaint.strokeWidth = 7 * unit
        for (k in 0 until n * 2) {
            val i = if (k < n) k else 2 * n - 1 - k
            val a = (k.toFloat() / (n * 2)) * 2 * PI.toFloat() - PI.toFloat() / 2
            val len = levels[i] * maxLen + 4 * unit
            val dx = cos(a); val dy = sin(a)
            linePaint.color = withAlpha(s.accent, 0.45f + levels[i] * 0.55f)
            c.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * (r0 + len), cy + dy * (r0 + len), linePaint)
        }
    }

    private fun progress(c: Canvas, r: RectF, t: Long) {
        val p = (t.toFloat() / s.durationMs).coerceIn(0f, 1f)
        linePaint.strokeWidth = 6 * unit
        linePaint.color = Color.argb(70, 255, 255, 255)
        c.drawLine(r.left, r.top, r.right, r.top, linePaint)
        linePaint.color = s.accent
        c.drawLine(r.left, r.top, r.left + (r.width() * p).coerceAtLeast(1f), r.top, linePaint)
        smallPaint.textSize = 30 * unit
        smallPaint.textAlign = Paint.Align.LEFT
        c.drawText(time(t), r.left, r.top + 50 * unit, smallPaint)
        smallPaint.textAlign = Paint.Align.RIGHT
        c.drawText(time(s.durationMs), r.right, r.top + 50 * unit, smallPaint)
    }

    private fun watermark(c: Canvas) {
        smallPaint.textSize = 26 * unit
        smallPaint.textAlign = Paint.Align.RIGHT
        val old = smallPaint.typeface
        smallPaint.typeface = s.serif
        smallPaint.color = Color.argb(90, 255, 255, 255)
        c.drawText("Palimpsest", w - 40 * unit, h - 36 * unit, smallPaint)
        smallPaint.typeface = old
        smallPaint.color = Color.argb(170, 255, 255, 255)
    }

    private fun time(ms: Long): String { val sec = ms / 1000; return "%d:%02d".format(sec / 60, sec % 60) }
    private fun ease(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)
    private fun withAlpha(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
