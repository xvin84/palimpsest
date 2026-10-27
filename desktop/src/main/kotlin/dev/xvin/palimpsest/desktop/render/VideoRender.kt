package dev.xvin.palimpsest.desktop.render

import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.dsp.Equalizer
import dev.xvin.palimpsest.core.dsp.SpectrumAnalyzer
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.data.Rg
import dev.xvin.palimpsest.desktop.data.Track
import dev.xvin.palimpsest.desktop.playback.AudioEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.Shadow
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class RenderMode { COVER, LYRICS }
enum class RenderAspect(val width: Int, val height: Int) { VERTICAL(1080, 1920), HORIZONTAL(1920, 1080) }
enum class RenderViz { BARS, MIRROR, RING }

/** Frames are laid out on a 1080-based canvas and scaled, so [scale] only changes the pixel count. */
enum class RenderQuality(val scale: Float, val fps: Int, val videoKbps: Int, val audioKbps: Int) {
    STANDARD(1f, 30, 6_000, 256), HIGH(1f, 60, 8_000, 320), MAX(4f / 3, 60, 12_000, 320);

    fun width(a: RenderAspect) = (a.width * scale).toInt() and 1.inv()
    fun height(a: RenderAspect) = (a.height * scale).toInt() and 1.inv()
}

/** What's being rendered now: the track, the stage and 0..1. */
data class RenderJob(val title: String, val progress: Float, val stage: String)

/**
 * «Видео из трека» on the computer, the phone's look frame for frame: the track's audio through
 * the same EQ and ReplayGain as playback, the spectrum sampled once per video frame like the
 * player's, frames drawn with Skia and handed to ffmpeg (x264 + AAC). One video at a time; the
 * result goes to Videos/Palimpsest (Movies on a Mac).
 */
class VideoRenders(private val graph: Graph) {
    private val _job = MutableStateFlow<RenderJob?>(null)
    val job: StateFlow<RenderJob?> = _job
    private var running: Job? = null

    /** The folder picked in Settings, or the system's videos folder. */
    val folder: File get() = graph.settings.value.videoDir?.let(::File) ?: defaultFolder

    /** Synced lyrics for a lyrics video, or null (then it's the cover). */
    suspend fun syncedLyrics(t: Track): ParsedLyrics? =
        runCatching { graph.lyrics.best(t) }.getOrNull()?.synced?.let(Lrc::parse)?.takeIf { it.isSynced && it.lines.isNotEmpty() }

    fun start(t: Track, mode: RenderMode, aspect: RenderAspect, quality: RenderQuality, viz: RenderViz, onDone: (Result<File>) -> Unit) {
        if (running?.isActive == true) return
        running = graph.scope.launch(Dispatchers.Default) {
            val r = runCatching { render(t, mode, aspect, quality, viz) }
            _job.value = null
            if (r.exceptionOrNull() !is CancellationException) withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun cancel() { running?.cancel() }

    private suspend fun render(t: Track, mode: RenderMode, aspect: RenderAspect, quality: RenderQuality, viz: RenderViz): File {
        fun stage(p: Float, s: String) { _job.value = RenderJob(t.title, p, s) }
        stage(0f, "audio")
        val source = graph.player.audioFileOf(t) { stage(it * 0.05f, "audio") }
        val s = graph.settings.value
        val rg = when (s.replayGain) { Rg.OFF -> null; Rg.TRACK -> t.rgTrackDb ?: t.rgAlbumDb; Rg.ALBUM -> t.rgAlbumDb ?: t.rgTrackDb }
        val eq = Equalizer().apply {
            setProfile(EqProfile.PRESETS.firstOrNull { it.name == s.eqProfile } ?: EqProfile.FLAT)
            enabled = s.eqEnabled
            setGainDb(if (rg == null) 0.0 else (rg + s.preampDb).toDouble())
        }
        val tmp = File(Paths.cache, "render").apply { mkdirs() }
        val pcm = File(tmp, "audio.f32")
        val fps = quality.fps
        val timeline = analyze(source, pcm, eq, fps) { stage(0.05f + it * 0.15f, "audio") }
        val cover = withContext(Dispatchers.IO) {
            when (val m = graph.covers.model(t)) {
                is File -> m.readBytes()
                is String -> if (m.startsWith("http")) graph.covers.bytes(m) else File(m).takeIf(File::isFile)?.readBytes()
                else -> null
            }
        }
        val coverImage = cover?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }
        val accent = runCatching { dev.xvin.palimpsest.desktop.ui.CoverColors.of(graph, t)?.first?.let { c -> argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()) } }.getOrNull()
            ?: 0xFFE4572E.toInt()
        val lyrics = if (mode == RenderMode.LYRICS) syncedLyrics(t) else null
        val durationMs = timeline.size * 1000L / fps
        val frame = FrameDrawer(aspect, if (lyrics != null) mode else RenderMode.COVER, viz, accent, coverImage, t.title, t.artist, lyrics, timeline, durationMs, quality.scale)

        folder.mkdirs()
        val name = "${t.artist} - ${t.title}".replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        val out = generateSequence(0) { it + 1 }.map { File(folder, if (it == 0) "$name.mp4" else "$name ($it).mp4") }.first { !it.exists() }
        val w = quality.width(aspect)
        val h = quality.height(aspect)
        val cmd = listOf(
            AudioEngine.ffmpeg, "-y", "-v", "error",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${w}x$h", "-r", "$fps", "-i", "-",
            "-f", "f32le", "-ar", "$RATE", "-ac", "2", "-i", pcm.absolutePath,
        ) + videoCodec(quality) + listOf(
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "${quality.audioKbps}k", "-movflags", "+faststart", "-shortest", out.absolutePath,
        )
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { synchronized(log) { log.appendLine(it) } } } }.apply { isDaemon = true; start() }
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap().apply { allocPixels(info) }
        val canvas = Canvas(bmp)
        try {
            proc.outputStream.buffered(1 shl 20).use { video ->
                for (i in 0 until timeline.size) {
                    currentCoroutineContext().ensureActive()
                    frame.draw(canvas, i * 1000L / fps)
                    video.write(bmp.readPixels(info, w * 4, 0, 0) ?: throw IOException("frame $i"))
                    if (i % 15 == 0) stage(0.2f + 0.8f * i / timeline.size, "video")
                }
            }
            val code = withContext(Dispatchers.IO) { proc.waitFor() }
            reader.join(2000)
            if (code != 0) throw IOException("ffmpeg: " + synchronized(log) { log.toString().lines().lastOrNull { it.isNotBlank() } ?: "exit $code" })
            return out
        } catch (e: Throwable) {
            proc.destroyForcibly()
            out.delete()
            throw if (e is IOException && e.message?.contains("Broken pipe", true) == true) IOException("ffmpeg: " + synchronized(log) { log.toString().trim().lines().lastOrNull() ?: "stopped" }) else e
        } finally {
            pcm.delete()
            bmp.close()
        }
    }

    /** x264 when this ffmpeg has it (the bundled and most system ones do), else plain MPEG-4. */
    private fun videoCodec(q: RenderQuality): List<String> {
        val hasX264 = runCatching {
            ProcessBuilder(AudioEngine.ffmpeg, "-hide_banner", "-encoders").redirectErrorStream(true).start().inputStream.bufferedReader().readText().contains("libx264")
        }.getOrDefault(false)
        return if (hasX264) listOf("-c:v", "libx264", "-preset", "medium", "-b:v", "${q.videoKbps}k", "-maxrate", "${q.videoKbps * 3 / 2}k", "-bufsize", "${q.videoKbps * 2}k")
        else listOf("-c:v", "mpeg4", "-q:v", "3")
    }

    /**
     * Decodes [source] with ffmpeg, runs it through [eq] into [pcm] (what the video's sound will be)
     * and samples the spectrum once per video frame with the player's smoothing.
     */
    private suspend fun analyze(source: File, pcm: File, eq: Equalizer, fps: Int, onProgress: (Float) -> Unit): List<FloatArray> = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(AudioEngine.ffmpeg, "-v", "quiet", "-i", source.absolutePath, "-f", "f32le", "-ar", "$RATE", "-ac", "2", "-").start()
        val total = (probeDurationMs(source) * fps / 1000).coerceAtLeast(1)
        val bands = 48
        val analyzer = SpectrumAnalyzer(2048, bands).apply { sampleRate = RATE }
        eq.configure(RATE, 2)
        val smooth = FloatArray(bands)
        var peak = 0.3f
        val out = ArrayList<FloatArray>()
        var framesDone = 0L
        val bytes = ByteArray(4096 * 8)
        val floats = FloatArray(4096 * 2)
        val bb = java.nio.ByteBuffer.allocate(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        try {
            pcm.outputStream().buffered(1 shl 20).use { sink ->
                val input = java.io.DataInputStream(proc.inputStream.buffered(1 shl 16))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.readNBytes(bytes, 0, bytes.size)
                    if (n <= 0) break
                    val frames = n / 8
                    bb.clear(); bb.put(bytes, 0, frames * 8); bb.flip()
                    for (k in 0 until frames * 2) floats[k] = bb.getFloat()
                    eq.process(floats, frames)
                    bb.clear(); for (k in 0 until frames * 2) bb.putFloat(floats[k])
                    sink.write(bb.array(), 0, frames * 8)
                    var pos = 0
                    while (pos < frames) {
                        val nextAt = ((out.size + 1).toLong() * RATE / fps) - framesDone
                        val take = minOf(frames - pos, nextAt.toInt().coerceAtLeast(1))
                        analyzer.push(floats.copyOfRange(pos * 2, (pos + take) * 2), take, 2)
                        pos += take
                        framesDone += take
                        if (framesDone * fps >= (out.size + 1).toLong() * RATE) {
                            val target = analyzer.compute()
                            peak = maxOf(peak * 0.997f, target.max(), 0.15f)
                            for (b in 0 until bands) {
                                val v = (target[b] / peak).coerceIn(0f, 1f).let { it * it }
                                smooth[b] = if (v > smooth[b]) smooth[b] + (v - smooth[b]) * 0.6f else smooth[b] * 0.90f + v * 0.10f
                            }
                            out += smooth.copyOf()
                            if (out.size % 60 == 0) onProgress((out.size.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
            }
            proc.waitFor()
        } finally {
            proc.destroy()
        }
        if (out.isEmpty()) throw IOException("No audio in ${source.name}")
        out
    }

    /** The length ffmpeg reports, for the progress bar only. */
    private fun probeDurationMs(f: File): Long = runCatching {
        val text = ProcessBuilder(AudioEngine.ffmpeg, "-hide_banner", "-i", f.absolutePath).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val m = Regex("""Duration: (\d+):(\d+):(\d+)\.(\d+)""").find(text) ?: return@runCatching 180_000L
        val (hh, mm, ss, cs) = m.destructured
        ((hh.toLong() * 3600 + mm.toLong() * 60 + ss.toLong()) * 1000 + cs.padEnd(2, '0').take(2).toLong() * 10)
    }.getOrDefault(180_000L)

    companion object {
        /** The system's videos folder, in «Palimpsest». */
        val defaultFolder: File get() = File(System.getProperty("user.home"), if (Paths.isMac) "Movies/Palimpsest" else "Videos/Palimpsest")

        const val RATE = 44_100
    }
}

private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
private fun alpha(c: Int) = (c ushr 24) and 0xFF
/** A two-colour linear gradient (Skia's gradient API takes Color4f now). */
private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int): Shader = Shader.makeLinearGradient(
    x0, y0, x1, y1,
    org.jetbrains.skia.Gradient(
        org.jetbrains.skia.Gradient.Colors(arrayOf(org.jetbrains.skia.Color4f(from), org.jetbrains.skia.Color4f(to)), floatArrayOf(0f, 1f), FilterTileMode.CLAMP, org.jetbrains.skia.ColorSpace.sRGB),
        org.jetbrains.skia.Gradient.Interpolation(),
    ),
)
private fun withAlpha(c: Int, a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0xFFFFFF)

/** One frame at a time, in the phone's layout (the same numbers, on a 1080-based canvas). */
private class FrameDrawer(
    val aspect: RenderAspect, val mode: RenderMode, val viz: RenderViz, val accent: Int, val cover: Image?,
    val title: String, val artist: String, val lyrics: ParsedLyrics?, val spectrum: List<FloatArray>, val durationMs: Long, val scale: Float,
) {
    private val w = aspect.width.toFloat()
    private val h = aspect.height.toFloat()
    private val vertical = aspect == RenderAspect.VERTICAL
    private val unit = min(w, h) / 1080f

    private val serif: Typeface = font("font/playfair_display.ttf").let { runCatching { it.makeClone(FontVariation("wght", 760f)) }.getOrDefault(it) }
    private val sans: Typeface = font("font/manrope.ttf")
    private val sansBold: Typeface = runCatching { sans.makeClone(FontVariation("wght", 650f)) }.getOrDefault(sans)
    private val fonts = FontCollection().apply {
        setDefaultFontManager(FontMgr.default)
        setAssetFontManager(TypefaceFontProvider().apply { registerTypeface(serif, "PalSerif") })
    }

    /** The backdrop, drawn once: the blurred cover, darkened and tinted with the accent. */
    private val background: Image = run {
        val info = ImageInfo(w.toInt(), h.toInt(), ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap().apply { allocPixels(info) }
        val c = Canvas(bmp)
        c.clear(0xFF000000.toInt())
        if (cover != null) {
            val side = maxOf(w, h) * 1.15f
            c.drawImageRect(cover, Rect.makeWH(cover.width.toFloat(), cover.height.toFloat()).square(), Rect.makeXYWH((w - side) / 2, (h - side) / 2, side, side),
                SamplingMode.LINEAR, Paint().apply { imageFilter = ImageFilter.makeBlur(60f * unit, 60f * unit, FilterTileMode.CLAMP) }, true)
        }
        c.drawRect(Rect.makeWH(w, h), Paint().apply { color = withAlpha(accent, 0.12f) })
        c.drawRect(Rect.makeWH(w, h), Paint().apply { shader = linear(0f, 0f, 0f, h, argb(150, 0, 0, 0), argb(215, 0, 0, 0)) })
        Image.makeFromBitmap(bmp.apply { setImmutable() })
    }

    fun draw(c: Canvas, t: Long) {
        c.clear(0xFF000000.toInt())
        c.save()
        c.scale(scale, scale)
        c.drawImage(background, 0f, 0f)
        val levels = spectrum.getOrNull((t * spectrumFps() / 1000).toInt()) ?: FloatArray(48)
        when {
            mode == RenderMode.LYRICS && vertical -> lyricsVertical(c, t, levels)
            mode == RenderMode.LYRICS -> lyricsHorizontal(c, t, levels)
            vertical -> coverVertical(c, t, levels)
            else -> coverHorizontal(c, t, levels)
        }
        watermark(c)
        c.restore()
        // Fade in from black and out to black at the edges of the track.
        val fade = minOf(t / 700f, (durationMs - t) / 900f, 1f).coerceAtLeast(0f)
        if (fade < 1f) c.drawRect(Rect.makeWH(w * scale, h * scale), Paint().apply { color = argb(((1 - fade) * 255).toInt(), 0, 0, 0) })
    }

    private fun spectrumFps() = (spectrum.size * 1000L / durationMs.coerceAtLeast(1)).coerceAtLeast(1)

    // ---- Cover mode ------------------------------------------------------------------------------

    private fun coverVertical(c: Canvas, t: Long, levels: FloatArray) {
        val ring = viz == RenderViz.RING
        val size = if (ring) 640 * unit else 860 * unit
        val cx = w / 2
        val cy = if (ring) 760 * unit else 720 * unit
        if (ring) ring(c, cx, cy, size / 2 + 26 * unit, 150 * unit, levels)
        cover(c, Rect.makeLTRB(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), if (ring) size / 2 else 40 * unit)
        titleBlock(c, cx, 1250 * unit, w - 140 * unit, center = true)
        if (!ring) spectrum(c, Rect.makeLTRB(90 * unit, 1460 * unit, w - 90 * unit, 1690 * unit), levels)
        progress(c, 90 * unit, w - 90 * unit, h - 150 * unit, t)
    }

    private fun coverHorizontal(c: Canvas, t: Long, levels: FloatArray) {
        val ring = viz == RenderViz.RING
        val size = if (ring) 600f else 780f
        val cx = 140f + 780f / 2
        val cy = h / 2
        if (ring) ring(c, cx, cy, size / 2 + 24, 130f, levels)
        cover(c, Rect.makeLTRB(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), if (ring) size / 2 else 36f)
        val left = 1040f
        val right = w - 120f
        titleBlock(c, left, 300f, right - left, center = false)
        if (!ring) spectrum(c, Rect.makeLTRB(left, 520f, right, 800f), levels)
        progress(c, left, right, 900f, t)
    }

    // ---- Lyrics mode -----------------------------------------------------------------------------

    private fun lyricsVertical(c: Canvas, t: Long, levels: FloatArray) {
        val coverSize = 200 * unit
        cover(c, Rect.makeXYWH(80 * unit, 150 * unit, coverSize, coverSize), 24 * unit)
        titleBlock(c, 80 * unit + coverSize + 40 * unit, 175 * unit, w - coverSize - 200 * unit, center = false, small = true)
        lyricsColumn(c, t, Rect.makeLTRB(80 * unit, 470 * unit, w - 80 * unit, 1500 * unit), center = true)
        spectrum(c, Rect.makeLTRB(80 * unit, 1560 * unit, w - 80 * unit, 1720 * unit), levels)
        progress(c, 80 * unit, w - 80 * unit, h - 140 * unit, t)
    }

    private fun lyricsHorizontal(c: Canvas, t: Long, levels: FloatArray) {
        val coverSize = 520f
        cover(c, Rect.makeXYWH(120f, 150f, coverSize, coverSize), 30f)
        titleBlock(c, 120f, 730f, coverSize, center = false, small = true)
        spectrum(c, Rect.makeLTRB(120f, 880f, 120f + coverSize, 980f), levels)
        lyricsColumn(c, t, Rect.makeLTRB(760f, 90f, w - 110f, h - 150f), center = false)
        progress(c, 760f, w - 110f, h - 90f, t)
    }

    /**
     * As on the phone: the current line sits a little above the middle, bigger and in the track's
     * colour; the neighbours fade with distance, and everything moves continuously between lines.
     */
    private fun lyricsColumn(c: Canvas, t: Long, area: Rect, center: Boolean) {
        val lyr = lyrics ?: return
        val lines = lyr.lines
        if (lines.isEmpty()) return
        val idx = lyr.indexAt(t).coerceAtLeast(-1)
        val width = area.width
        val big = (if (vertical) 66 else 60) * unit
        val small = (if (vertical) 46 else 42) * unit
        val gap = 30 * unit
        val since = if (idx >= 0) t - lines[idx].timeMs else 0L
        val k = smooth((since / TRANSITION_MS).coerceIn(0f, 1f))
        val focus = if (idx >= 0) idx - 1 + k else -1f
        fun activation(i: Int) = when (i) { idx -> k; idx - 1 -> 1 - k; else -> 0f }
        val first = (idx - 7).coerceAtLeast(0)
        val last = (idx + 9).coerceAtMost(lines.lastIndex)
        val scales = FloatArray(last - first + 1) { j -> lerp(small / big, 1f, activation(first + j)) }
        val heights = FloatArray(last - first + 1) { j -> paragraph(lines[first + j].text, big, width, center, -1).height * scales[j] + gap }
        val tops = FloatArray(heights.size)
        for (j in 1 until heights.size) tops[j] = tops[j - 1] + heights[j - 1]
        val focusY = if (focus < first) tops[0] - heights[0] * (first - focus) else {
            val fi = focus.toInt().coerceIn(first, last)
            tops[fi - first] + heights[fi - first] * (focus - fi)
        }
        val anchorY = area.top + area.height * 0.40f
        c.save()
        c.clipRect(area)
        for (i in first..last) {
            val j = i - first
            val y = anchorY + tops[j] - focusY
            if (y > area.bottom || y + heights[j] < area.top) continue
            val dist = abs(i - focus)
            val base = (205 - dist * 38).coerceIn(50f, 170f)
            val color = blend(argb(base.toInt(), 255, 255, 255), accent, activation(i))
            val p = paragraph(lines[i].text, big, width, center, color)
            val sc = scales[j]
            c.save()
            val px = if (center) area.left + width * (1 - sc) / 2 else area.left
            c.translate(px, y)
            c.scale(sc, sc)
            p.paint(c, 0f, 0f)
            c.restore()
        }
        c.restore()
    }

    private val measured = HashMap<String, org.jetbrains.skia.paragraph.Paragraph>()

    /** A wrapped lyric line; [color] -1 means «only to measure» (cached). */
    private fun paragraph(text: String, size: Float, width: Float, center: Boolean, color: Int): org.jetbrains.skia.paragraph.Paragraph {
        val shown = text.ifBlank { "♪" }
        if (color == -1) measured[shown]?.let { return it }
        val style = ParagraphStyle().apply { alignment = if (center) Alignment.CENTER else Alignment.LEFT }
        val ts = TextStyle().apply {
            fontFamilies = arrayOf("PalSerif")
            fontSize = size
            this.color = if (color == -1) 0xFFFFFFFF.toInt() else color
            height = 1.05f
            if (color != -1) addShadow(Shadow(argb(alpha(color) * 2 / 3, 0, 0, 0), 0f, 3 * unit, 4.5 * unit))
        }
        val p = ParagraphBuilder(style, fonts).pushStyle(ts).addText(shown).popStyle().build().layout(width)
        if (color == -1) measured[shown] = p
        return p
    }

    // ---- Pieces ----------------------------------------------------------------------------------

    private fun cover(c: Canvas, r: Rect, radius: Float) {
        val rr = RRect.makeLTRB(r.left, r.top, r.right, r.bottom, radius)
        c.drawRRect(rr, Paint().apply { color = argb(255, 0, 0, 0); imageFilter = ImageFilter.makeDropShadowOnly(0f, 18 * unit, 24 * unit, 24 * unit, argb(150, 0, 0, 0)) })
        val img = cover
        if (img == null) { c.drawRRect(rr, Paint().apply { isAntiAlias = true; color = accent }); return }
        c.save()
        c.clipRRect(rr, true)
        c.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()).square(), r, SamplingMode.LINEAR, null, true)
        c.restore()
    }

    private fun titleBlock(c: Canvas, x: Float, top: Float, width: Float, center: Boolean, small: Boolean = false) {
        val tf = Font(serif, (if (small) 52 else 76) * unit)
        val af = Font(sansBold, (if (small) 36 else 44) * unit)
        val ty = top - tf.metrics.ascent
        text(c, ellipsize(title, tf, width), x, ty, tf, argb(255, 255, 255, 255), center)
        text(c, ellipsize(artist, af, width), x, ty + tf.metrics.descent + 14 * unit - af.metrics.ascent, af, argb(225, 255, 255, 255), center)
    }

    /** Text with a soft shadow, like good subtitles. */
    private fun text(c: Canvas, s: String, x: Float, y: Float, f: Font, color: Int, center: Boolean, shadow: Boolean = true) {
        val dx = if (center) -f.measureTextWidth(s) / 2 else 0f
        if (shadow) c.drawString(s, x + dx, y + 3 * unit, f, Paint().apply { isAntiAlias = true; this.color = argb(170, 0, 0, 0); maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 4.5f * unit) })
        c.drawString(s, x + dx, y, f, Paint().apply { isAntiAlias = true; this.color = color })
    }

    private fun ellipsize(s: String, f: Font, width: Float): String {
        if (f.measureTextWidth(s) <= width) return s
        var n = s.length
        while (n > 1 && f.measureTextWidth(s.take(n) + "…") > width) n--
        return s.take(n).trimEnd() + "…"
    }

    private fun spectrum(c: Canvas, r: Rect, levels: FloatArray) = if (viz == RenderViz.MIRROR) mirror(c, r, levels) else bars(c, r, levels)

    private fun bars(c: Canvas, r: Rect, levels: FloatArray) {
        val n = levels.size
        val step = r.width / n
        val gap = step * 0.28f
        val bw = step - gap
        val paint = Paint().apply { isAntiAlias = true; shader = linear(0f, r.top, 0f, r.bottom, accent, withAlpha(accent, 0.3f)) }
        for (i in 0 until n) {
            val bh = (levels[i] * r.height).coerceAtLeast(bw * 0.6f)
            val x = r.left + i * step + gap / 2
            c.drawRRect(RRect.makeLTRB(x, r.bottom - bh, x + bw, r.bottom, bw / 2), paint)
        }
    }

    private fun mirror(c: Canvas, r: Rect, levels: FloatArray) {
        val n = levels.size
        val step = r.width / n
        val mid = (r.top + r.bottom) / 2
        for (i in 0 until n) {
            val hh = (levels[i] * r.height / 2).coerceAtLeast(2f)
            val x = r.left + i * step + step / 2
            c.drawLine(x, mid - hh, x, mid + hh, stroke(step * 0.55f, withAlpha(accent, 0.35f + levels[i] * 0.65f)))
        }
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r0: Float, maxLen: Float, levels: FloatArray) {
        val n = levels.size
        for (k in 0 until n * 2) {
            val i = if (k < n) k else 2 * n - 1 - k
            val a = (k.toFloat() / (n * 2)) * 2 * PI.toFloat() - PI.toFloat() / 2
            val len = levels[i] * maxLen + 4 * unit
            val dx = cos(a)
            val dy = sin(a)
            c.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * (r0 + len), cy + dy * (r0 + len), stroke(7 * unit, withAlpha(accent, 0.45f + levels[i] * 0.55f)))
        }
    }

    private fun progress(c: Canvas, left: Float, right: Float, y: Float, t: Long) {
        val p = (t.toFloat() / durationMs).coerceIn(0f, 1f)
        c.drawLine(left, y, right, y, stroke(6 * unit, argb(70, 255, 255, 255)))
        c.drawLine(left, y, left + ((right - left) * p).coerceAtLeast(1f), y, stroke(6 * unit, accent))
        val f = Font(sans, 30 * unit)
        val grey = argb(170, 255, 255, 255)
        text(c, time(t), left, y + 50 * unit, f, grey, center = false, shadow = false)
        val total = time(durationMs)
        text(c, total, right - f.measureTextWidth(total), y + 50 * unit, f, grey, center = false, shadow = false)
    }

    private fun watermark(c: Canvas) {
        val f = Font(serif, 26 * unit)
        c.drawString("Palimpsest", w - 40 * unit - f.measureTextWidth("Palimpsest"), h - 36 * unit, f, Paint().apply { isAntiAlias = true; color = argb(90, 255, 255, 255) })
    }

    private fun stroke(width: Float, color: Int) = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = width; strokeCap = PaintStrokeCap.ROUND; this.color = color }
    private fun time(ms: Long): String { val sec = ms / 1000; return "%d:%02d".format(sec / 60, sec % 60) }
    private fun smooth(x: Float) = x * x * x * (x * (x * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = lerp(ch(from, s).toFloat(), ch(to, s).toFloat(), t).toInt()
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
    private fun Rect.square(): Rect { val s = min(width, height); return Rect.makeXYWH(left + (width - s) / 2, top + (height - s) / 2, s, s) }

    companion object {
        const val TRANSITION_MS = 650f
        private fun font(resource: String): Typeface {
            val bytes = FrameDrawer::class.java.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
            return FontMgr.default.makeFromData(Data.makeFromBytes(bytes))!!
        }
    }
}
