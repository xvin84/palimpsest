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
import dev.xvin.palimpsest.core.render.VideoFrame
import dev.xvin.palimpsest.core.render.VideoLook
import dev.xvin.palimpsest.core.render.VideoSpec
import androidx.compose.ui.graphics.toArgb
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

    /** What every frame of a track's video shares, read once: the cover (and its blur), colours, the file's facts. */
    class Look(val cover: Image?, val blurred: Image?, val accent: Int, val mood: Int, val facts: List<Pair<String, Boolean>>)

    suspend fun look(t: Track): Look = withContext(Dispatchers.IO) {
        val bytes = when (val m = graph.covers.model(t)) {
            is File -> m.readBytes()
            is String -> if (m.startsWith("http")) graph.covers.bytes(m) else File(m).takeIf(File::isFile)?.readBytes()
            else -> null
        }
        val cover = bytes?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }
        val colors = runCatching { dev.xvin.palimpsest.desktop.ui.CoverColors.of(graph, t) }.getOrNull()
        fun argb(c: androidx.compose.ui.graphics.Color) = c.toArgb()
        val accent = colors?.accent?.let { argb(dev.xvin.palimpsest.desktop.ui.readable(it, light = false)) } ?: 0xFFE4572E.toInt()
        val lossless = t.codec?.lowercase()?.let { c -> listOf("flac", "alac", "wav", "aiff", "ape", "wv").any { c.contains(it) } } == true
        val facts = listOfNotNull(
            t.codec?.uppercase()?.let { it to lossless },
            t.bitrateKbps?.let { "$it kbps" to false },
            t.rgTrackDb?.let { "RG %+.1f dB".format(it) to false },
        )
        Look(cover, SkiaPen.blur(cover), accent, colors?.mood?.let(::argb) ?: dev.xvin.palimpsest.core.covers.CoverTone.NEUTRAL, facts)
    }

    fun spec(t: Track, look: Look, style: VideoLook, aspect: RenderAspect, lyrics: ParsedLyrics?, durationMs: Long, envelope: FloatArray?) = VideoSpec(
        style, aspect == RenderAspect.VERTICAL, t.title, t.artist, t.album, t.year, lyrics, durationMs, look.accent, look.mood, look.facts, envelope,
    )

    /** A still of the video for the dialog, [width] pixels wide, with a made-up spectrum. */
    fun preview(spec: VideoSpec, look: Look, width: Int): Image {
        val frame = VideoFrame(spec)
        val k = width / frame.width
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, (frame.height * k).toInt())
        surface.canvas.scale(k, k)
        val pen = SkiaPen(surface.canvas, look.cover) { look.blurred }
        frame.background(pen)
        frame.draw(pen, VideoFrame.previewTime(spec), VideoFrame.previewLevels())
        return surface.makeImageSnapshot()
    }

    fun start(t: Track, mode: RenderMode, aspect: RenderAspect, quality: RenderQuality, style: VideoLook, onDone: (Result<File>) -> Unit) {
        if (running?.isActive == true) return
        running = graph.scope.launch(Dispatchers.Default) {
            val r = runCatching { render(t, mode, aspect, quality, style) }
            _job.value = null
            if (r.exceptionOrNull() !is CancellationException) withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun cancel() { running?.cancel() }

    private suspend fun render(t: Track, mode: RenderMode, aspect: RenderAspect, quality: RenderQuality, style: VideoLook): File {
        fun stage(p: Float, s: String) { _job.value = RenderJob(t.title, p, s) }
        stage(0f, "audio")
        val source = graph.player.audioFileOf(t) { stage(it * 0.05f, "audio") }
        val s = graph.settings.value
        val rg = when (s.replayGain) { Rg.OFF -> null; Rg.TRACK -> t.rgTrackDb ?: t.rgAlbumDb; Rg.ALBUM -> t.rgAlbumDb ?: t.rgTrackDb }
        val eq = Equalizer().apply {
            setProfile((EqProfile.PRESETS + s.customEq).firstOrNull { it.name == s.eqProfile } ?: EqProfile.FLAT)
            enabled = s.eqEnabled
            setGainDb(if (rg == null) 0.0 else (rg + s.preampDb).toDouble())
        }
        val tmp = File(Paths.cache, "render").apply { mkdirs() }
        val pcm = File(tmp, "audio.f32")
        val fps = quality.fps
        val timeline = analyze(source, pcm, eq, fps) { stage(0.05f + it * 0.15f, "audio") }
        val look = look(t)
        val lyrics = if (mode == RenderMode.LYRICS) syncedLyrics(t) else null
        val durationMs = timeline.size * 1000L / fps
        val frame = VideoFrame(spec(t, look, style, aspect, lyrics, durationMs, envelope(timeline)))

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
        // The still part once, then each frame is that plus what moves.
        val background = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).let { sf ->
            sf.canvas.scale(quality.scale, quality.scale)
            frame.background(SkiaPen(sf.canvas, look.cover) { look.blurred })
            sf.makeImageSnapshot()
        }
        val pen = SkiaPen(canvas, look.cover) { look.blurred }
        try {
            proc.outputStream.buffered(1 shl 20).use { video ->
                for (i in 0 until timeline.size) {
                    currentCoroutineContext().ensureActive()
                    canvas.drawImage(background, 0f, 0f)
                    canvas.save()
                    canvas.scale(quality.scale, quality.scale)
                    frame.draw(pen, i * 1000L / fps, timeline[i])
                    canvas.restore()
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

    /** «Пульт»'s waveform: the track's loudness in 140 steps, from the spectrum already sampled per frame. */
    private fun envelope(timeline: List<FloatArray>, n: Int = 140): FloatArray {
        val raw = FloatArray(n) { i ->
            val from = i * timeline.size / n
            val to = maxOf(from + 1, (i + 1) * timeline.size / n)
            (from until minOf(to, timeline.size)).map { timeline[it].average().toFloat() }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        }
        val peak = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return FloatArray(n) { (raw[it] / peak).coerceIn(0.04f, 1f) }
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

