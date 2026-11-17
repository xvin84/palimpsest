package dev.xvin.palimpsest.core.render

import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.core.render.Colors.alpha
import dev.xvin.palimpsest.core.render.Colors.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** «Видео из трека» comes in the app's three looks. */
enum class VideoLook { NOTEBOOK, COVER, CONSOLE }

/** Everything a video frame shows besides the time and the spectrum. */
class VideoSpec(
    val look: VideoLook,
    val vertical: Boolean,
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
    /** Synced lyrics make it a lyrics video; null — the cover one. */
    val lyrics: ParsedLyrics? = null,
    val durationMs: Long,
    /** The cover's vivid colour, already legible on dark. */
    val accent: Int,
    /** What the cover reads as overall ([dev.xvin.palimpsest.core.covers.CoverTone]): «Обложка» floods the frame with it. */
    val mood: Int,
    /** «Пульт»'s chips: FLAC, 24/96, 1024 kbps, RG… — true for lossless, shown in cyan. */
    val facts: List<Pair<String, Boolean>> = emptyList(),
    /** Loudness over the whole track, 0..1, for «Пульт»'s waveform; null — a plain line. */
    val envelope: FloatArray? = null,
)

/**
 * One frame of the video, the same on the phone and the computer: [background] is drawn once and
 * stays (the paper, the cover's colour, the grid), [draw] is everything that moves, on a clear
 * layer over it. The three looks follow the app's styles:
 *
 *  - «Тетрадь»: a ruled notebook page with a margin, the cover pasted in as a photo, ink-stroke spectrum;
 *  - «Обложка»: the cover's colour over the whole frame, a big breathing cover, Unbounded headlines, white bars;
 *  - «Пульт»: an instrument panel — meters, an LED spectrum, the file's facts, the track's waveform with a playhead.
 */
class VideoFrame(private val s: VideoSpec) {
    val width = if (s.vertical) 1080f else 1920f
    val height = if (s.vertical) 1920f else 1080f
    private val v = s.vertical
    private val lyricsMode = s.lyrics != null && s.lyrics.lines.isNotEmpty()

    fun background(p: Pen) = when (s.look) {
        VideoLook.NOTEBOOK -> notebookPaper(p)
        VideoLook.COVER -> coverWash(p)
        VideoLook.CONSOLE -> consolePanel(p)
    }

    fun draw(p: Pen, tMs: Long, levels: FloatArray) {
        when (s.look) {
            VideoLook.NOTEBOOK -> if (lyricsMode) notebookLyrics(p, tMs, levels) else notebookCover(p, tMs, levels)
            VideoLook.COVER -> if (lyricsMode) coverLyrics(p, tMs, levels) else coverCover(p, tMs, levels)
            VideoLook.CONSOLE -> if (lyricsMode) consoleLyrics(p, tMs, levels) else consoleCover(p, tMs, levels)
        }
        // In from black and out to black at the ends of the track.
        val fade = minOf(tMs / 700f, (s.durationMs - tMs) / 900f, 1f).coerceAtLeast(0f)
        if (fade < 1f) p.rect(0f, 0f, width, height, Colors.argb(((1 - fade) * 255).toInt(), 0, 0, 0))
    }

    // ---- «Тетрадь» ---------------------------------------------------------------------------------

    private object Ink {
        const val PAPER = 0xFF15120F.toInt()
        const val TEXT = 0xFFEDE6D8.toInt()
        const val MUTED = 0xFFA39A8C.toInt()
        const val FAINT = 0xFF6F675C.toInt()
        const val RULE = 0x0FFFFFFF
        const val MARGIN = 0x66D9774B
        const val PHOTO = 0xFFEFE8DA.toInt()
        const val PHOTO_INK = 0xFF3A322A.toInt()
    }
    private val marginX get() = if (v) 120f else 150f

    private fun notebookPaper(p: Pen) {
        p.rect(0f, 0f, width, height, Ink.PAPER)
        // A ruled page: faint lines, and the red margin of a school notebook (doubled, like the real one).
        var y = 96f
        while (y < height) { p.line(0f, y, width, y, Ink.RULE, 2f); y += 72f }
        p.line(marginX, 0f, marginX, height, Ink.MARGIN, 2.5f)
        p.line(marginX + 10, 0f, marginX + 10, height, alpha(Ink.MARGIN, 0.5f), 2f)
        p.glow(width * 0.8f, height * 0.12f, max(width, height) * 0.7f, alpha(s.accent, 0.06f), alpha(s.accent, 0f))
    }

    /** The cover as a photo pasted in at a slight angle: a paper border, the album written under it. */
    private fun polaroid(p: Pen, cx: Float, top: Float, side: Float, tilt: Float) {
        val pad = side * 0.035f
        val foot = side * 0.15f
        val l = cx - side / 2 - pad; val r = cx + side / 2 + pad
        val b = top + pad + side + foot
        p.save()
        p.rotate(tilt, cx, (top + b) / 2)
        p.shadow(l, top, r, b, 6f, 42f, 0xAA000000.toInt())
        p.rect(l, top, r, b, Ink.PHOTO, 6f)
        p.cover(l + pad, top + pad, r - pad, top + pad + side, 2f, s.accent)
        val caption = listOfNotNull(s.album, s.year?.toString()).joinToString(", ")
        if (caption.isNotEmpty()) {
            val f = Font(Face.SERIF, side * 0.048f, 500)
            p.text(ellipsize(p, caption, f, side), cx, top + pad + side + foot * 0.62f, f, Ink.PHOTO_INK, Align.CENTER)
        }
        p.restore()
    }

    private fun notebookCover(p: Pen, t: Long, levels: FloatArray) {
        val left = marginX + 46f
        if (v) {
            p.text("Palimpsest", left, 150f, Font(Face.SERIF, 34f, 500), Ink.FAINT)
            p.text(time(t), width - 70f, 150f, Font(Face.SANS, 30f, 500), Ink.FAINT, Align.END)
            polaroid(p, (left + width - 70f) / 2, 230f, 740f, -2.2f)
            var y = titleBlock(p, left, 1250f, width - left - 80f, Font(Face.SERIF, 82f, 620), Ink.TEXT, 2)
            y += 18f
            p.text(ellipsize(p, s.artist, Font(Face.SANS, 40f, 600), width - left - 80f), left, y + 40f, Font(Face.SANS, 40f, 600), s.accent)
            inkStrokes(p, left, 1530f, width - 80f, 1700f, levels)
            notebookProgress(p, left, width - 80f, 1790f, t)
        } else {
            polaroid(p, 600f, 150f, 640f, -2.2f)
            val l = 1060f; val r = width - 120f
            p.text("Palimpsest", l, 200f, Font(Face.SERIF, 34f, 500), Ink.FAINT)
            var y = titleBlock(p, l, 290f, r - l, Font(Face.SERIF, 80f, 620), Ink.TEXT, 2)
            y += 18f
            p.text(ellipsize(p, s.artist, Font(Face.SANS, 40f, 600), r - l), l, y + 40f, Font(Face.SANS, 40f, 600), s.accent)
            inkStrokes(p, l, 640f, r, 800f, levels)
            notebookProgress(p, l, r, 900f, t)
        }
        p.text("Palimpsest", width - 50f, height - 40f, Font(Face.SERIF, 26f, 500), alpha(Ink.FAINT, 0.6f), Align.END)
    }

    private fun notebookLyrics(p: Pen, t: Long, levels: FloatArray) {
        val left = marginX + 46f
        val head = if (v) 140f else 110f
        val coverSide = if (v) 170f else 150f
        p.shadow(left, head, left + coverSide, head + coverSide, 4f, 24f, 0x99000000.toInt())
        p.cover(left, head, left + coverSide, head + coverSide, 4f, s.accent)
        val tl = left + coverSide + 34f
        val maxW = width - tl - 70f
        p.text(ellipsize(p, s.title, Font(Face.SERIF, 50f, 620), maxW), tl, head + 70f, Font(Face.SERIF, 50f, 620), Ink.TEXT)
        p.text(ellipsize(p, s.artist, Font(Face.SANS, 34f, 600), maxW), tl, head + 124f, Font(Face.SANS, 34f, 600), s.accent)
        val area = if (v) Box(left, 420f, width - 80f, 1560f) else Box(left, 330f, width - 140f, 900f)
        lyricsColumn(p, t, area, Font(Face.SERIF, if (v) 70f else 62f, 620), 0.66f, center = false) { act, dist ->
            lerp(alpha(Ink.TEXT, (0.78f - dist * 0.16f).coerceIn(0.2f, 0.66f)), s.accent, act)
        }
        if (v) inkStrokes(p, left, 1620f, width - 80f, 1720f, levels)
        notebookProgress(p, left, width - 80f, if (v) 1800f else 990f, t)
    }

    /** The spectrum as pen strokes: thin, round, the louder ones in the cover's colour. */
    private fun inkStrokes(p: Pen, l: Float, t: Float, r: Float, b: Float, levels: FloatArray) {
        val n = levels.size
        val step = (r - l) / n
        val mid = (t + b) / 2
        for (i in 0 until n) {
            val hh = max(levels[i] * (b - t) / 2, 3f)
            val x = l + i * step + step / 2
            p.line(x, mid - hh, x, mid + hh, lerp(alpha(Ink.TEXT, 0.55f), s.accent, levels[i]), step * 0.38f)
        }
    }

    private fun notebookProgress(p: Pen, l: Float, r: Float, y: Float, t: Long) {
        val k = progress(t)
        p.line(l, y, r, y, alpha(Ink.TEXT, 0.18f), 3f)
        p.line(l, y, l + (r - l) * k, y, s.accent, 4f)
        p.circle(l + (r - l) * k, y, 9f, s.accent)
        val f = Font(Face.SANS, 28f, 500)
        p.text(time(t), l, y + 50f, f, Ink.FAINT)
        p.text(time(s.durationMs), r, y + 50f, f, Ink.FAINT, Align.END)
    }

    // ---- «Обложка» --------------------------------------------------------------------------------

    private val coverTop get() = lerp(0xFF120A0E.toInt(), s.mood, 0.62f)
    private val coverBottom get() = lerp(0xFF0C080A.toInt(), s.mood, 0.20f)

    private fun coverWash(p: Pen) {
        p.verticalGradient(0f, 0f, width, height, coverTop, coverBottom)
        p.blurredCover(0f, 0f, width, height, 0.38f)
        p.verticalGradient(0f, height * 0.35f, width, height, alpha(coverBottom, 0f), alpha(coverBottom, 0.92f))
        p.glow(width * 0.25f, height * 0.18f, max(width, height) * 0.75f, alpha(s.mood, 0.35f), alpha(s.mood, 0f))
    }

    private fun bass(levels: FloatArray) = levels.take(6).average().toFloat().takeIf { !it.isNaN() } ?: 0f

    /** The cover breathes with the bass: a percent or two, enough to feel the beat. */
    private fun breathingCover(p: Pen, l: Float, t: Float, side: Float, radius: Float, levels: FloatArray) {
        val k = 1f + 0.022f * bass(levels)
        val cx = l + side / 2; val cy = t + side / 2
        p.save()
        p.translate(cx, cy); p.scale(k); p.translate(-cx, -cy)
        p.shadow(l, t + 24f, l + side, t + side + 24f, radius, 80f, 0xB0000000.toInt())
        p.cover(l, t, l + side, t + side, radius, s.mood)
        p.restore()
    }

    /** A white glass pill: album · year above the cover. */
    private fun pill(p: Pen, text: String, x: Float, y: Float, align: Align) {
        val f = Font(Face.SANS, 30f, 500)
        val w = p.width(text, f) + 56f
        val l = when (align) { Align.CENTER -> x - w / 2; Align.END -> x - w; Align.START -> x }
        p.rect(l, y, l + w, y + 64f, 0x24FFFFFF, 32f)
        p.stroke(l, y, l + w, y + 64f, 0x30FFFFFF, 2f, 32f)
        p.text(text, l + w / 2, y + 43f, f, 0xE6FFFFFF.toInt(), Align.CENTER)
    }

    private fun coverCover(p: Pen, t: Long, levels: FloatArray) {
        val caption = listOfNotNull(s.album, s.year?.toString()).joinToString(" · ")
        if (v) {
            if (caption.isNotEmpty()) pill(p, ellipsize(p, caption, Font(Face.SANS, 30f, 500), 760f), width / 2, 150f, Align.CENTER)
            breathingCover(p, 100f, 270f, 880f, 56f, levels)
            val y = titleBlock(p, 100f, 1240f, 880f, Font(Face.DISPLAY, 72f, 700), 0xFFFFFFFF.toInt(), 2)
            p.text(ellipsize(p, s.artist, Font(Face.SANS, 42f, 500), 880f), 100f, y + 60f, Font(Face.SANS, 42f, 500), 0xD0FFFFFF.toInt())
            whiteBars(p, 100f, 1520f, 980f, 1680f, levels)
            coverProgress(p, 100f, 980f, 1770f, t)
        } else {
            breathingCover(p, 130f, 170f, 740f, 48f, levels)
            val l = 1000f; val r = width - 130f
            if (caption.isNotEmpty()) pill(p, ellipsize(p, caption, Font(Face.SANS, 30f, 500), r - l - 60f), l, 180f, Align.START)
            val y = titleBlock(p, l, 300f, r - l, Font(Face.DISPLAY, 70f, 700), 0xFFFFFFFF.toInt(), 2)
            p.text(ellipsize(p, s.artist, Font(Face.SANS, 40f, 500), r - l), l, y + 58f, Font(Face.SANS, 40f, 500), 0xD0FFFFFF.toInt())
            whiteBars(p, l, 600f, r, 760f, levels)
            coverProgress(p, l, r, 860f, t)
        }
        p.text("Palimpsest", width - 50f, height - 40f, Font(Face.SANS, 26f, 600), 0x55FFFFFF, Align.END)
    }

    private fun coverLyrics(p: Pen, t: Long, levels: FloatArray) {
        val side = if (v) 150f else 140f
        val l0 = if (v) 90f else 120f
        val top = if (v) 130f else 90f
        p.shadow(l0, top + 10f, l0 + side, top + side + 10f, 26f, 40f, 0x90000000.toInt())
        p.cover(l0, top, l0 + side, top + side, 26f, s.mood)
        val tl = l0 + side + 30f
        val maxW = width - tl - 80f
        p.text(ellipsize(p, s.title, Font(Face.DISPLAY, 44f, 700), maxW), tl, top + 68f, Font(Face.DISPLAY, 44f, 700), 0xFFFFFFFF.toInt())
        p.text(ellipsize(p, s.artist, Font(Face.SANS, 34f, 500), maxW), tl, top + 120f, Font(Face.SANS, 34f, 500), 0xC8FFFFFF.toInt())
        val area = if (v) Box(90f, 380f, width - 90f, 1560f) else Box(220f, 290f, width - 220f, 880f)
        lyricsColumn(p, t, area, Font(Face.DISPLAY, if (v) 62f else 58f, 700), 0.72f, center = true) { act, dist ->
            alpha(0xFFFFFFFF.toInt(), lerp01((0.5f - dist * 0.1f).coerceIn(0.14f, 0.42f), 1f, act))
        }
        if (v) whiteBars(p, 90f, 1610f, width - 90f, 1720f, levels)
        coverProgress(p, l0, width - l0, if (v) 1810f else 980f, t)
    }

    private fun whiteBars(p: Pen, l: Float, t: Float, r: Float, b: Float, levels: FloatArray) {
        val n = levels.size
        val step = (r - l) / n
        val bw = step * 0.62f
        for (i in 0 until n) {
            val bh = max(levels[i] * (b - t), bw)
            val x = l + i * step + (step - bw) / 2
            p.rect(x, b - bh, x + bw, b, alpha(0xFFFFFFFF.toInt(), 0.45f + levels[i] * 0.5f), bw / 2)
        }
    }

    private fun coverProgress(p: Pen, l: Float, r: Float, y: Float, t: Long) {
        val k = progress(t)
        p.rect(l, y - 6f, r, y + 6f, 0x40FFFFFF, 6f)
        p.rect(l, y - 6f, l + max((r - l) * k, 12f), y + 6f, 0xFFFFFFFF.toInt(), 6f)
        p.circle(l + (r - l) * k, y, 15f, 0xFFFFFFFF.toInt())
        val f = Font(Face.SANS, 30f, 500)
        p.text(time(t), l, y + 58f, f, 0xB0FFFFFF.toInt())
        p.text("-" + time(s.durationMs - t), r, y + 58f, f, 0xB0FFFFFF.toInt(), Align.END)
    }

    // ---- «Пульт» ----------------------------------------------------------------------------------

    private object Panel {
        const val BG = 0xFF0B0D0C.toInt()
        const val CARD = 0xFF121614.toInt()
        const val LINE = 0xFF232826.toInt()
        const val TEXT = 0xFFE6ECE8.toInt()
        const val MUTED = 0xFF9AA59F.toInt()
        const val FAINT = 0xFF5E6A64.toInt()
        const val OFF = 0xFF1C221F.toInt()
        const val LIME = 0xFFC6F432.toInt()
        const val CYAN = 0xFF59D6E6.toInt()
    }

    private fun consolePanel(p: Pen) {
        p.rect(0f, 0f, width, height, Panel.BG)
        var x = 0f
        while (x < width) { p.line(x, 0f, x, height, 0x08FFFFFF, 1.5f); x += 60f }
        var y = 0f
        while (y < height) { p.line(0f, y, width, y, 0x08FFFFFF, 1.5f); y += 60f }
    }

    /** The top line: a lit dot, the app, the timecode. */
    private fun statusBar(p: Pen, l: Float, r: Float, y: Float, t: Long, levels: FloatArray) {
        val mono = Font(Face.MONO, 28f, 500)
        p.circle(l + 10f, y - 10f, 10f, alpha(Panel.LIME, 0.5f + bass(levels) * 0.5f))
        p.text("PALIMPSEST", l + 36f, y, mono, Panel.MUTED)
        p.text("${clock(t)} / ${clock(s.durationMs)}", r, y, mono, Panel.LIME, Align.END)
        p.line(l, y + 30f, r, y + 30f, Panel.LINE, 2f)
    }

    /** The cover as an exhibit: a frame with corner ticks. */
    private fun exhibit(p: Pen, l: Float, t: Float, side: Float) {
        p.cover(l, t, l + side, t + side, 6f, Panel.CARD)
        p.stroke(l - 14f, t - 14f, l + side + 14f, t + side + 14f, Panel.LINE, 2f, 10f)
        val k = 36f
        for ((x, y, dx, dy) in listOf(Quad(l - 14f, t - 14f, 1f, 1f), Quad(l + side + 14f, t - 14f, -1f, 1f), Quad(l - 14f, t + side + 14f, 1f, -1f), Quad(l + side + 14f, t + side + 14f, -1f, -1f))) {
            p.line(x, y, x + k * dx, y, Panel.LIME, 4f)
            p.line(x, y, x, y + k * dy, Panel.LIME, 4f)
        }
    }

    /** Three LED meters: lows, mids, highs. */
    private fun meters(p: Pen, l: Float, t: Float, r: Float, b: Float, levels: FloatArray) {
        val bands = listOf(
            "LO" to levels.take(levels.size / 6), "MID" to levels.drop(levels.size / 6).take(levels.size / 3), "HI" to levels.drop(levels.size / 2),
        ).map { (n, xs) -> n to (xs.average().toFloat().takeIf { !it.isNaN() } ?: 0f) }
        val colW = (r - l) / bands.size
        val segs = 22
        val labelH = 50f
        val segH = (b - t - labelH) / segs
        bands.forEachIndexed { i, (name, level) ->
            val x0 = l + i * colW + colW * 0.2f
            val x1 = l + (i + 1) * colW - colW * 0.2f
            val lit = (min(1f, level * 1.15f) * segs).toInt()
            for (k in 0 until segs) {
                val y1 = b - labelH - k * segH
                val color = when {
                    k >= lit -> Panel.OFF
                    k >= segs - 4 -> Panel.CYAN
                    else -> Panel.LIME
                }
                p.rect(x0, y1 - segH + 5f, x1, y1, color, 2f)
            }
            p.text(name, (x0 + x1) / 2, b - 6f, Font(Face.MONO, 24f, 500), Panel.FAINT, Align.CENTER)
        }
    }

    private fun factChips(p: Pen, l: Float, y: Float, maxR: Float) {
        val f = Font(Face.MONO, 26f, 500)
        var x = l
        for ((text, hi) in s.facts) {
            val w = p.width(text, f) + 32f
            if (x + w > maxR) break
            p.stroke(x, y, x + w, y + 52f, if (hi) alpha(Panel.CYAN, 0.6f) else Panel.LINE, 2f, 6f)
            p.text(text, x + 16f, y + 36f, f, if (hi) Panel.CYAN else Panel.MUTED)
            x += w + 12f
        }
    }

    /** The spectrum as columns of LED blocks. */
    private fun ledSpectrum(p: Pen, l: Float, t: Float, r: Float, b: Float, levels: FloatArray) {
        p.rect(l - 20f, t - 20f, r + 20f, b + 20f, Panel.CARD, 12f)
        p.stroke(l - 20f, t - 20f, r + 20f, b + 20f, Panel.LINE, 2f, 12f)
        val n = levels.size
        val step = (r - l) / n
        val bw = step * 0.7f
        val block = 12f
        val rows = ((b - t) / block).toInt()
        for (i in 0 until n) {
            val lit = (levels[i] * rows).toInt().coerceAtLeast(1)
            val x = l + i * step + (step - bw) / 2
            for (k in 0 until lit) {
                val y1 = b - k * block
                p.rect(x, y1 - block + 4f, x + bw, y1, if (k >= rows - 2) Panel.CYAN else alpha(Panel.LIME, 0.55f + 0.45f * k / rows), 1.5f)
            }
        }
    }

    /** The whole track's loudness, the played part lit, a cyan playhead with its timecode. */
    private fun waveform(p: Pen, l: Float, t: Float, r: Float, b: Float, tMs: Long) {
        val k = progress(tMs)
        val env = s.envelope
        val mid = (t + b) / 2
        if (env == null || env.isEmpty()) {
            p.line(l, mid, r, mid, Panel.LINE, 4f)
            p.line(l, mid, l + (r - l) * k, mid, Panel.LIME, 4f)
        } else {
            val step = (r - l) / env.size
            for (i in env.indices) {
                val x = l + i * step + step / 2
                val hh = max(env[i] * (b - t) / 2, 2f)
                p.line(x, mid - hh, x, mid + hh, if ((i + 0.5f) / env.size <= k) Panel.LIME else Panel.LINE, step * 0.55f)
            }
        }
        val px = l + (r - l) * k
        p.line(px, t - 16f, px, b + 16f, Panel.CYAN, 3f)
        val f = Font(Face.MONO, 24f, 500)
        val label = clock(tMs)
        val lw = p.width(label, f)
        val lx = (px - lw / 2).coerceIn(l, r - lw)
        p.text(label, lx, t - 28f, f, Panel.CYAN)
    }

    private fun consoleCover(p: Pen, t: Long, levels: FloatArray) {
        if (v) {
            statusBar(p, 80f, width - 80f, 120f, t, levels)
            exhibit(p, 94f, 224f, 640f)
            meters(p, 790f, 210f, width - 80f, 880f, levels)
            var y = titleBlock(p, 80f, 970f, width - 160f, Font(Face.SANS, 66f, 700), Panel.TEXT, 2)
            p.text(ellipsize(p, listOfNotNull(s.artist, s.album).joinToString(" · "), Font(Face.SANS, 38f, 400), width - 160f), 80f, y + 50f, Font(Face.SANS, 38f, 400), Panel.MUTED)
            y += 90f
            factChips(p, 80f, y, width - 80f)
            ledSpectrum(p, 100f, 1300f, width - 100f, 1500f, levels)
            waveform(p, 80f, 1640f, width - 80f, 1760f, t)
        } else {
            statusBar(p, 80f, width - 80f, 90f, t, levels)
            exhibit(p, 94f, 184f, 560f)
            meters(p, 700f, 170f, 900f, 760f, levels)
            val l = 980f; val r = width - 80f
            var y = titleBlock(p, l, 190f, r - l, Font(Face.SANS, 62f, 700), Panel.TEXT, 2)
            p.text(ellipsize(p, listOfNotNull(s.artist, s.album).joinToString(" · "), Font(Face.SANS, 36f, 400), r - l), l, y + 48f, Font(Face.SANS, 36f, 400), Panel.MUTED)
            y += 86f
            factChips(p, l, y, r)
            ledSpectrum(p, l + 20f, 520f, r - 20f, 720f, levels)
            waveform(p, 80f, 890f, width - 80f, 990f, t)
        }
    }

    private fun consoleLyrics(p: Pen, t: Long, levels: FloatArray) {
        val l = 80f; val r = width - 80f
        statusBar(p, l, r, if (v) 120f else 90f, t, levels)
        val top = if (v) 200f else 160f
        val side = 140f
        p.cover(l, top, l + side, top + side, 6f, Panel.CARD)
        p.stroke(l - 8f, top - 8f, l + side + 8f, top + side + 8f, Panel.LINE, 2f, 8f)
        val tl = l + side + 34f
        p.text(ellipsize(p, s.title, Font(Face.SANS, 48f, 700), r - tl), tl, top + 62f, Font(Face.SANS, 48f, 700), Panel.TEXT)
        p.text(ellipsize(p, s.artist, Font(Face.SANS, 34f, 400), r - tl), tl, top + 114f, Font(Face.SANS, 34f, 400), Panel.MUTED)
        val gutter = 170f
        val area = if (v) Box(l + gutter, 460f, r, 1480f) else Box(l + gutter + 300f, 380f, r - 200f, 860f)
        val lines = s.lyrics!!.lines
        lyricsColumn(p, t, area, Font(Face.MONO, if (v) 50f else 46f, 600), 0.76f, center = false, gutter = { i, y, act ->
            // Each line's timestamp in the gutter, the sung one marked.
            val f = Font(Face.MONO, 26f, 500)
            p.text("[${clock(lines[i].timeMs)}]", area.l - gutter, y + 34f, f, lerp(alpha(Panel.CYAN, 0.45f), Panel.CYAN, act))
            if (act > 0.5f) p.text("▶", area.l - 44f, y + 40f, Font(Face.MONO, 30f, 600), alpha(Panel.LIME, act))
        }) { act, dist ->
            lerp(alpha(Panel.MUTED, (0.8f - dist * 0.16f).coerceIn(0.22f, 0.7f)), Panel.LIME, act)
        }
        if (v) ledSpectrum(p, 100f, 1560f, r - 20f, 1660f, levels)
        waveform(p, l, if (v) 1760f else 950f, r, if (v) 1840f else 1010f, t)
    }

    // ---- Lyrics ------------------------------------------------------------------------------------

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) { val w get() = r - l; val h get() = b - t }
    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
    private val wrapped = HashMap<Int, List<String>>()

    /**
     * The sung line sits a little above the middle of [area], at full size and colour; the others
     * are smaller and fade with distance. Everything moves continuously: a line grows and lights up
     * while the previous one shrinks and dims, and the column glides to the new line. Before the
     * first line, it waits just below the centre as "what's coming".
     */
    private fun lyricsColumn(
        p: Pen, t: Long, area: Box, font: Font, smallScale: Float, center: Boolean,
        gutter: ((Int, Float, Float) -> Unit)? = null,
        color: (activation: Float, distance: Float) -> Int,
    ) {
        val lines = s.lyrics?.lines ?: return
        if (lines.isEmpty()) return
        val idx = s.lyrics.indexAt(t).coerceAtLeast(-1)
        val lineH = font.size * 1.2f
        val gap = font.size * 0.42f
        val since = if (idx >= 0) t - lines[idx].timeMs else 0L
        val k = smooth((since / TRANSITION_MS).coerceIn(0f, 1f))
        val focus = if (idx >= 0) idx - 1 + k else -1f
        fun activation(i: Int) = when (i) { idx -> k; idx - 1 -> 1 - k; else -> 0f }
        fun rows(i: Int) = wrapped.getOrPut(i) { wrap(p, lines[i].text.ifBlank { "♪" }, font, area.w, 3) }

        val first = (idx - 7).coerceAtLeast(0)
        val last = (idx + 9).coerceAtMost(lines.lastIndex)
        val n = last - first + 1
        val scales = FloatArray(n) { j -> lerp01(smallScale, 1f, activation(first + j)) }
        val heights = FloatArray(n) { j -> rows(first + j).size * lineH * scales[j] + gap }
        val tops = FloatArray(n)
        for (j in 1 until n) tops[j] = tops[j - 1] + heights[j - 1]
        val focusY = if (focus < first) tops[0] - heights[0] * (first - focus) else {
            val fi = focus.toInt().coerceIn(first, last)
            tops[fi - first] + heights[fi - first] * (focus - fi)
        }
        val anchorY = area.t + area.h * 0.38f
        val ascent = p.ascent(font)
        p.save()
        p.clip(area.l - (if (gutter != null) 200f else 0f), area.t, area.r, area.b)
        for (i in first..last) {
            val j = i - first
            val y = anchorY + tops[j] - focusY
            if (y > area.b || y + heights[j] < area.t) continue
            val act = activation(i)
            val c = color(act, abs(i - focus))
            gutter?.invoke(i, y, act)
            val sc = scales[j]
            p.save()
            p.translate(if (center) area.l + area.w * (1 - sc) / 2 else area.l, y)
            p.scale(sc)
            rows(i).forEachIndexed { r, text ->
                p.text(text, if (center) area.w / 2 else 0f, r * lineH + ascent + (lineH - ascent - p.descent(font)) / 2, font, c, if (center) Align.CENTER else Align.START)
            }
            p.restore()
        }
        p.restore()
    }

    // ---- Pieces ------------------------------------------------------------------------------------

    /** The title over up to [maxLines] lines from [top]; returns the last baseline. */
    private fun titleBlock(p: Pen, x: Float, top: Float, maxW: Float, font: Font, color: Int, maxLines: Int): Float {
        val rows = wrap(p, s.title, font, maxW, maxLines)
        val lineH = font.size * 1.12f
        var y = top + p.ascent(font)
        rows.forEachIndexed { i, r -> if (i > 0) y += lineH; p.text(r, x, y, font, color) }
        return y
    }

    private fun progress(t: Long) = if (s.durationMs > 0) (t.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        /** How long a lyric line change (glide + highlight) takes. */
        private const val TRANSITION_MS = 650f

        fun time(ms: Long): String { val sec = max(ms, 0L) / 1000; return "%d:%02d".format(sec / 60, sec % 60) }
        fun clock(ms: Long): String { val sec = max(ms, 0L) / 1000; return "%02d:%02d".format(sec / 60, sec % 60) }
        private fun smooth(x: Float) = x * x * x * (x * (x * 6 - 15) + 10)
        private fun lerp01(a: Float, b: Float, t: Float) = a + (b - a) * t

        /** Greedy word wrap into at most [maxLines]; the last line ends in «…» when there's more. */
        fun wrap(p: Pen, text: String, font: Font, maxW: Float, maxLines: Int): List<String> {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return listOf("")
            val out = ArrayList<String>()
            var cur = ""
            var i = 0
            while (i < words.size) {
                val next = if (cur.isEmpty()) words[i] else "$cur ${words[i]}"
                if (p.width(next, font) <= maxW || cur.isEmpty()) { cur = next; i++ } else { out += cur; cur = ""; if (out.size == maxLines) break }
            }
            if (cur.isNotEmpty() && out.size < maxLines) out += cur
            val rest = i < words.size
            return out.mapIndexed { k, line ->
                if (k == out.lastIndex && rest) ellipsize(p, "$line…", font, maxW) else if (p.width(line, font) > maxW) ellipsize(p, line, font, maxW) else line
            }
        }

        fun ellipsize(p: Pen, text: String, font: Font, maxW: Float): String {
            if (p.width(text, font) <= maxW) return text
            var lo = 0; var hi = text.length
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (p.width(text.take(mid).trimEnd() + "…", font) <= maxW) lo = mid else hi = mid - 1 }
            return text.take(lo).trimEnd() + "…"
        }

        /** A believable spectrum for the preview, before any audio is analysed. */
        fun previewLevels(bands: Int = 48, phase: Float = 0f): FloatArray = FloatArray(bands) { i ->
            val x = i.toFloat() / bands
            val shape = 0.85f * (1 - x).let { it * it } + 0.2f
            (shape * (0.75f + 0.25f * sin(i * 1.7f + phase * 6f))).coerceIn(0.05f, 1f)
        }

        /** A believable loudness curve for the preview's waveform. */
        fun previewEnvelope(n: Int = 140): FloatArray = FloatArray(n) { i ->
            val x = i.toFloat() / n
            val build = if (x < 0.08f) x / 0.08f else if (x > 0.92f) (1 - x) / 0.08f else 1f
            (build * (0.55f + 0.3f * sin(x * 19f) * sin(x * 7f) + 0.15f * sin(i * 2.3f))).coerceIn(0.06f, 1f)
        }

        /** A moment worth showing in the preview: a third in, or on a sung line in a lyrics video. */
        fun previewTime(spec: VideoSpec): Long {
            val lines = spec.lyrics?.lines.orEmpty()
            val pick = lines.getOrNull(lines.size / 3)
            return pick?.timeMs?.plus(900) ?: (spec.durationMs / 3)
        }
    }
}
