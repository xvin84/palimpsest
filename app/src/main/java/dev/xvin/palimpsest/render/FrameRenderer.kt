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

/** How long a line change (scroll + highlight) takes in lyrics videos. */
private const val TRANSITION_MS = 650f
enum class RenderAspect(val width: Int, val height: Int) { VERTICAL(1080, 1920), HORIZONTAL(1920, 1080) }

/**
 * Output quality. Frames are laid out on a 1080-based canvas and scaled, so [scale] only changes
 * the pixel count; 60 fps makes the lyric glide and the visualiser noticeably smoother.
 */
enum class RenderQuality(val scale: Float, val fps: Int, val videoBitrate: Int, val audioBitrate: Int, val labelRes: Int) {
    STANDARD(1f, 30, 6_000_000, 256_000, dev.xvin.palimpsest.R.string.render_quality_standard),
    HIGH(1f, 60, 10_000_000, 320_000, dev.xvin.palimpsest.R.string.render_quality_high),
    MAX(4f / 3, 60, 16_000_000, 320_000, dev.xvin.palimpsest.R.string.render_quality_max);

    fun width(a: RenderAspect) = (a.width * scale).toInt() and 1.inv()
    fun height(a: RenderAspect) = (a.height * scale).toInt() and 1.inv()
}

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
    val quality: RenderQuality = RenderQuality.STANDARD,
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
    private val scale = s.quality.scale

    /** Static backdrop, drawn once: blurred, darkened cover tinted with the accent. */
    val background: Bitmap = Bitmap.createBitmap(s.quality.width(s.aspect), s.quality.height(s.aspect), Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.scale(scale, scale)
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
    // Bold serif with a soft shadow, like good subtitles: readable on any part of any cover.
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.serif; color = Color.WHITE; fontVariationSettings = "'wght' 760"; textShadow() }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(s.sans, 600, false); color = Color.argb(225, 255, 255, 255); textShadow() }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.sans; color = Color.argb(170, 255, 255, 255) }
    private val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = s.serif; fontVariationSettings = "'wght' 720"; textShadow() }
    private val coverShader = s.cover?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val layouts = HashMap<Int, StaticLayout>()

    private fun Paint.textShadow() = setShadowLayer(9 * unit, 0f, 3 * unit, Color.argb(170, 0, 0, 0))

    fun draw(c: Canvas, tMs: Long) {
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        c.save()
        c.scale(scale, scale)
        val levels = s.spectrum.at(tMs * 1000)
        when {
            s.mode == RenderMode.LYRICS && vertical -> lyricsVertical(c, tMs, levels)
            s.mode == RenderMode.LYRICS -> lyricsHorizontal(c, tMs, levels)
            vertical -> coverVertical(c, tMs, levels)
            else -> coverHorizontal(c, tMs, levels)
        }
        watermark(c)
        c.restore()
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
     * The current line sits a little above the middle of [area], bigger and in full white; the
     * neighbours fade with distance. Everything moves continuously: a line grows and lights up
     * while the previous one shrinks and dims, and the column glides to the new line. Before the
     * first line, it waits just below the centre as "what's coming".
     */
    private fun lyricsColumn(c: Canvas, t: Long, area: RectF, center: Boolean) {
        val lyr = s.lyrics ?: return
        val lines = lyr.lines
        if (lines.isEmpty()) return
        val idx = lyr.indexAt(t).coerceAtLeast(-1)
        val width = area.width().toInt()
        val big = (if (vertical) 66 else 60) * unit
        val small = (if (vertical) 46 else 42) * unit
        val gap = 30 * unit

        // 0 → 1 over the first part of the current line; drives the scroll and the line highlight.
        val since = if (idx >= 0) t - lines[idx].timeMs else 0L
        val k = smooth((since / TRANSITION_MS).coerceIn(0f, 1f))
        val focus = if (idx >= 0) idx - 1 + k else -1f // -1 before the first line, then glides idx-1 → idx

        fun activation(i: Int): Float = when (i) {
            idx -> k
            idx - 1 -> 1 - k
            else -> 0f
        }

        // Each line is laid out once at the big size and drawn scaled, so size changes are smooth.
        val first = (idx - 7).coerceAtLeast(0)
        val last = (idx + 9).coerceAtMost(lines.lastIndex)
        val scales = FloatArray(last - first + 1) { j -> lerp(small / big, 1f, activation(first + j)) }
        val heights = FloatArray(last - first + 1) { j -> layoutFor(first + j, lines[first + j].text, big, width, center).height * scales[j] + gap }
        // y of each line's top relative to line `first`, and of the focus point.
        val tops = FloatArray(heights.size)
        for (j in 1 until heights.size) tops[j] = tops[j - 1] + heights[j - 1]
        val focusY = when {
            focus < first -> tops[0] - heights[0] * (first - focus) // before the first line: it sits lower
            else -> {
                val fi = focus.toInt().coerceIn(first, last)
                tops[fi - first] + heights[fi - first] * (focus - fi)
            }
        }
        val anchorY = area.top + area.height() * 0.40f

        c.save()
        c.clipRect(area)
        for (i in first..last) {
            val j = i - first
            val y = anchorY + tops[j] - focusY
            if (y > area.bottom || y + heights[j] < area.top) continue
            val act = activation(i)
            val dist = kotlin.math.abs(i - focus)
            val base = (205 - dist * 38).coerceIn(50f, 170f)
            val layout = layoutFor(i, lines[i].text, big, width, center)
            // The sung line takes the track's colour, the rest stay white and fade with distance.
            layout.paint.color = blend(Color.argb(base.toInt(), 255, 255, 255), s.accent, act)
            val sc = scales[j]
            c.save()
            // Scale around the line's horizontal anchor: its centre, or the left edge.
            val px = if (center) area.left + width * (1 - sc) / 2 else area.left
            c.translate(px, y)
            c.scale(sc, sc)
            layout.draw(c)
            c.restore()
        }
        c.restore()
    }

    private fun layoutFor(i: Int, text: String, size: Float, width: Int, center: Boolean): StaticLayout =
        layouts.getOrPut(i) {
            val shown = text.ifBlank { "♪" }
            val p = TextPaint(lyricPaint).apply { textSize = size }
            StaticLayout.Builder.obtain(shown, 0, shown.length, p, width)
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .build()
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
    /** Ease-in-out (smootherstep): starts and stops gently, no jolt at either end. */
    private fun smooth(x: Float) = x * x * x * (x * (x * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun blend(from: Int, to: Int, t: Float) = Color.argb(
        lerp(Color.alpha(from).toFloat(), Color.alpha(to).toFloat(), t).toInt(),
        lerp(Color.red(from).toFloat(), Color.red(to).toFloat(), t).toInt(),
        lerp(Color.green(from).toFloat(), Color.green(to).toFloat(), t).toInt(),
        lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat(), t).toInt(),
    )
    private fun withAlpha(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
