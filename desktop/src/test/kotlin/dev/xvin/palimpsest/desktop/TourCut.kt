package dev.xvin.palimpsest.desktop.tour

import dev.xvin.palimpsest.core.net.Http
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.Point
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import java.io.DataInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The phone's own part of the tour, recorded by scripts/demo/phone_tour.py: times in seconds from its video's start. */
@Serializable
data class PhonePart(
    val video: String,
    val audio: String? = null,
    /** Where in [audio] the video's first frame is. */
    val audioOffset: Double = 0.0,
    val duration: Double,
    val chapters: List<PhoneChapter> = emptyList(),
    val captions: List<PhoneCaption> = emptyList(),
)

@Serializable data class PhoneChapter(val at: Double, val ru: String, val en: String, val subRu: String, val subEn: String, val length: Double = 2.4)
@Serializable data class PhoneCaption(val from: Double, val to: Double, val ru: String, val en: String)

private const val W = 1920
private const val H = 1080
private const val FPS = 60
/** Above the band at the bottom where the captions go: the app (and the camera) never reach into it. */
private const val STAGE_H = 930f
/** The phone's screen as it's decoded (the emulator records 1080×2400). */
private const val PW = 432
private const val PH = 960

/** Raw frames out of a video, one at a time, through ffmpeg. */
private class Frames(file: File, w: Int, h: Int, from: Double = 0.0, scale: String? = null) {
    private val size = w * h * 4
    private val proc = ProcessBuilder(
        listOf("ffmpeg", "-v", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, from), "-i", file.absolutePath) +
            listOf("-vf", "fps=$FPS" + (scale?.let { ",$it" } ?: ""), "-f", "rawvideo", "-pix_fmt", "bgra", "-"),
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val input = DataInputStream(proc.inputStream.buffered(1 shl 22))
    private val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    private var last: Image? = null

    /** The next frame; the last one again once the video has ended. */
    fun next(): Image? {
        val bytes = ByteArray(size)
        val ok = runCatching { input.readFully(bytes) }.isSuccess
        if (ok) { last?.close(); last = Image.makeRaster(info, bytes, w * 4) }
        return last
    }
    private val w = w

    fun close() = proc.destroy()
}

private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2 }
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Rect, b: Rect, t: Float) = Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
private fun argb(a: Float, r: Int, g: Int, b: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

/**
 * Puts the tour together (see Tour.kt): every frame drawn anew at 1920×1080 — a calm background, the
 * app's window with a shadow, a camera that leans in where the action is, the pointer with a ripple
 * on each click, captions, chapter cards; the phone beside the window while music moves between
 * them, and the phone's own part in the middle. Then the sound: what really played, from the capture,
 * and under the big player the song from its file.
 *   ./gradlew :desktop:tourCut -Pout=<tour dir> -Pphone=<phone part dir>
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val phoneDir = File(args[1])
    val tl = Http.json.decodeFromString(TourTimeline.serializer(), File(dir, "desk.json").readText())
    val part = File(phoneDir, "phone.json").takeIf { it.isFile }?.let { Http.json.decodeFromString(PhonePart.serializer(), it.readText()) }
    val phoneFrames = part?.let { (it.duration * FPS).toInt() } ?: 0
    val deskFrames = tl.cursor.size
    val total = deskFrames + phoneFrames

    val fonts = File("src/main/resources/font")
    val serif = FontMgr.default.makeFromFile(File(fonts, "playfair_display.ttf").absolutePath)!!
    val manrope = FontMgr.default.makeFromFile(File(fonts, "manrope.ttf").absolutePath)!!
    fun manrope(weight: Float): Typeface = manrope.makeClone(arrayOf(FontVariation("wght", weight)))
    val sans = manrope(500f)
    val sansBold = manrope(700f)
    val serifBold = serif.makeClone(arrayOf(FontVariation("wght", 700f)))

    val accent = 0xFFE4572E.toInt()
    val surface = Surface.makeRasterN32Premul(W, H)
    val canvas = surface.canvas
    val bgSurface = Surface.makeRasterN32Premul(W / 4, H / 4)
    val out = ProcessBuilder(
        "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${W}x$H", "-framerate", "$FPS", "-i", "-",
        "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-r", "$FPS", File(dir, "video.mp4").absolutePath,
    ).redirectErrorStream(true).start()
    val sink = out.outputStream.buffered(1 shl 22)
    val pixels = org.jetbrains.skia.Bitmap().apply { allocPixels(ImageInfo(W, H, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }

    val linear = SamplingMode.MITCHELL

    // --- Pieces --------------------------------------------------------------------------------

    /** Slowly drifting soft colour, drawn small and stretched (it's all blur anyway). */
    fun background(t: Float) {
        val c = bgSurface.canvas
        c.drawPaint(Paint().apply { color = 0xFF161216.toInt() })
        val blur = Paint().apply { maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 40f) }
        blur.color = argb(0.32f, 0xE4, 0x57, 0x2E); c.drawCircle(120f + 40f * sin(t * 0.21f), 70f + 20f * sin(t * 0.17f), 90f, blur)
        blur.color = argb(0.22f, 0x6B, 0x4C, 0xC9); c.drawCircle(380f + 30f * sin(t * 0.15f + 2f), 210f + 25f * sin(t * 0.23f), 110f, blur)
        blur.color = argb(0.14f, 0x2E, 0xA6, 0xE4); c.drawCircle(300f + 50f * sin(t * 0.11f + 4f), 40f, 70f, blur)
        bgSurface.makeImageSnapshot().use { img -> canvas.drawImageRect(img, Rect(0f, 0f, W / 4f, H / 4f), Rect(0f, 0f, W.toFloat(), H.toFloat()), linear, null, true) }
    }

    fun shadow(r: Rect, radius: Float, strength: Float = 0.6f) {
        canvas.drawRRect(RRect.makeLTRB(r.left, r.top + 22f, r.right, r.bottom + 22f, radius), Paint().apply {
            color = argb(strength, 0, 0, 0); maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 36f)
        })
    }

    fun picture(img: Image, r: Rect, radius: Float) {
        canvas.save()
        canvas.clipRRect(RRect.makeLTRB(r.left, r.top, r.right, r.bottom, radius), true)
        canvas.drawImageRect(img, Rect(0f, 0f, img.width.toFloat(), img.height.toFloat()), r, linear, null, true)
        canvas.restore()
        canvas.drawRRect(RRect.makeLTRB(r.left, r.top, r.right, r.bottom, radius), Paint().apply { mode = PaintMode.STROKE; strokeWidth = 1.5f; color = argb(0.12f, 255, 255, 255) })
    }

    fun phone(img: Image?, r: Rect, opacity: Float = 1f) {
        if (opacity <= 0f) return
        canvas.saveLayer(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { alpha = (opacity * 255).toInt().coerceIn(0, 255) })
        val bezel = r.height * 0.016f
        val outer = Rect(r.left - bezel, r.top - bezel, r.right + bezel, r.bottom + bezel)
        shadow(outer, r.width * 0.14f, 0.7f)
        canvas.drawRRect(RRect.makeLTRB(outer.left, outer.top, outer.right, outer.bottom, r.width * 0.13f), Paint().apply { color = 0xFF0B0B0D.toInt() })
        canvas.drawRRect(RRect.makeLTRB(outer.left, outer.top, outer.right, outer.bottom, r.width * 0.13f), Paint().apply { mode = PaintMode.STROKE; strokeWidth = 2f; color = argb(0.18f, 255, 255, 255) })
        if (img != null) picture(img, r, r.width * 0.105f)
        canvas.restore()
    }

    fun text(s: String, font: Font, x: Float, y: Float, color: Int, center: Boolean = false) {
        val w = font.measureTextWidth(s)
        canvas.drawString(s, if (center) x - w / 2 else x, y, font, Paint().apply { this.color = color })
    }

    /** Lines of [s] that fit in [width] with [font]. */
    fun wrap(s: String, font: Font, width: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in s.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (font.measureTextWidth(next) > width && line.isNotEmpty()) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    fun caption(ru: String, en: String, appear: Float, cx: Float = W / 2f, y: Float = H - 30f) {
        if (ru.isEmpty() || appear <= 0f) return
        val fRu = Font(sansBold, 30f)
        val fEn = Font(sans, 20f)
        val wRu = fRu.measureTextWidth(ru)
        val wEn = if (en.isEmpty()) 0f else fEn.measureTextWidth(en)
        val pw = max(wRu, wEn) + 64f
        val ph = if (en.isEmpty()) 60f else 92f
        val lift = (1 - ease(appear)) * 14f
        val r = Rect(cx - pw / 2, y - ph + lift, cx + pw / 2, y + lift)
        canvas.saveLayer(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { alpha = (ease(appear) * 255).toInt().coerceIn(0, 255) })
        canvas.drawRRect(RRect.makeLTRB(r.left, r.top, r.right, r.bottom, 22f), Paint().apply { color = argb(0.86f, 16, 13, 18) })
        canvas.drawRRect(RRect.makeLTRB(r.left, r.top, r.right, r.bottom, 22f), Paint().apply { mode = PaintMode.STROKE; strokeWidth = 1.2f; color = argb(0.16f, 255, 255, 255) })
        canvas.drawRRect(RRect.makeLTRB(r.left + 18f, r.top + 18f, r.left + 22f, r.bottom - 18f, 2f), Paint().apply { color = accent })
        text(ru, fRu, cx, r.top + 41f, 0xFFF4EEE6.toInt(), center = true)
        if (en.isNotEmpty()) text(en, fEn, cx, r.top + 74f, argb(0.62f, 244, 238, 230), center = true)
        canvas.restore()
    }

    /** A chapter's card: the screen dims, the title rises, a line of the accent draws itself under it. */
    fun card(c: Chapter, p: Float, length: Float) {
        val inT = (p / 0.28f).coerceIn(0f, 1f)
        val outT = ((length - p) / 0.35f).coerceIn(0f, 1f)
        val a = ease(min(inT, outT))
        if (a <= 0f) return
        // What's behind goes soft and dark, so the title stands alone.
        surface.makeImageSnapshot().use { snap ->
            canvas.drawImage(snap, 0f, 0f, Paint().apply { imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(22f * a, 22f * a, org.jetbrains.skia.FilterTileMode.CLAMP) })
        }
        canvas.drawPaint(Paint().apply { color = argb(0.8f * a, 12, 10, 14) })
        val title = Font(serifBold, 112f)
        val rise = (1 - ease((p / 0.6f).coerceIn(0f, 1f))) * 40f
        canvas.saveLayer(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { alpha = (a * 255).toInt().coerceIn(0, 255) })
        text(c.ru, title, W / 2f, H / 2f - 10f + rise, 0xFFF4EEE6.toInt(), center = true)
        if (c.en != c.ru) text(c.en, Font(sans, 26f), W / 2f, H / 2f + 34f + rise * 0.6f, argb(0.5f, 244, 238, 230), center = true)
        val line = 180f * ease(((p - 0.2f) / 0.7f).coerceIn(0f, 1f))
        canvas.drawRRect(RRect.makeLTRB(W / 2f - line / 2, H / 2f + 62f, W / 2f + line / 2, H / 2f + 67f, 3f), Paint().apply { color = accent })
        val sub = ((p - 0.35f) / 0.5f).coerceIn(0f, 1f)
        canvas.saveLayer(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { alpha = (ease(sub) * 255).toInt().coerceIn(0, 255) })
        text(c.subRu, Font(sans, 36f), W / 2f, H / 2f + 124f, argb(0.9f, 244, 238, 230), center = true)
        if (c.subEn != c.subRu) text(c.subEn, Font(sans, 24f), W / 2f, H / 2f + 164f, argb(0.5f, 244, 238, 230), center = true)
        canvas.restore()
        canvas.restore()
    }

    fun cursorAt(x: Float, y: Float, scale: Float, pressed: Boolean) {
        val k = scale * (if (pressed) 0.88f else 1f)
        val p = org.jetbrains.skia.PathBuilder()
            .moveTo(x, y).lineTo(x, y + 18f * k).lineTo(x + 4.5f * k, y + 14f * k).lineTo(x + 8f * k, y + 21f * k)
            .lineTo(x + 10.5f * k, y + 20f * k).lineTo(x + 7f * k, y + 13f * k).lineTo(x + 12.5f * k, y + 13f * k).closePath().detach()
        canvas.save(); canvas.translate(1.5f * k, 2.5f * k)
        canvas.drawPath(p, Paint().apply { color = argb(0.45f, 0, 0, 0); maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 3f * k) })
        canvas.restore()
        canvas.drawPath(p, Paint().apply { color = 0xFFFFFFFF.toInt() })
        canvas.drawPath(p, Paint().apply { mode = PaintMode.STROKE; strokeWidth = 1.3f * k; color = 0xFF111111.toInt() })
    }

    // --- Where things go -------------------------------------------------------------------------

    val deskWindow = Rect(269f, 26f, 269f + 1382f, 26f + 885f) // 1280×820 × 1.08, above the caption band
    val sideWindow = Rect(56f, 120f, 56f + 1216f, 120f + 779f)
    val sidePhone = Rect(1460f, 40f, 1460f + 387f, 40f + 860f)

    fun layoutAt(f: Int): Pair<Rect, Float> {
        // The window's place and how far the phone has come in (0..1), easing between layouts.
        var from = "desk"; var at = 0; var mode = "desk"
        for (l in tl.layouts) if (l.at <= f) { from = mode; mode = l.mode; at = l.at }
        val t = ease((f - at) / (0.9f * FPS))
        fun rect(m: String) = if (m == "side") sideWindow else deskWindow
        fun phoneIn(m: String) = if (m == "side") 1f else 0f
        return lerp(rect(from), rect(mode), t) to lerp(phoneIn(from), phoneIn(mode), t)
    }

    /** The camera: how much it leans in, and where it looks (output pixels), easing between key frames. */
    fun cameraAt(f: Int, window: Rect): Triple<Float, Float, Float> {
        fun view(c: Camera): Triple<Float, Float, Float> {
            if (c.whole) return Triple(1f, W / 2f, STAGE_H / 2f)
            val s = window.width / tl.width
            val rx = window.left + c.x * s; val ry = window.top + c.y * s
            val rw = c.w * s; val rh = c.h * s
            val k = min(1.9f, min(W * 0.86f / rw, STAGE_H * 0.86f / rh)).coerceAtLeast(1f)
            return Triple(k, rx + rw / 2, ry + rh / 2)
        }
        var prev = tl.camera.first(); var cur = prev
        for (c in tl.camera) if (c.at <= f) { prev = cur; cur = c }
        val t = ease((f - cur.at) / (0.8f * FPS))
        val a = view(prev); val b = view(cur)
        val k = lerp(a.first, b.first, t)
        // Never past the window's edges: what's seen stays the app, not the background beside it.
        val halfW = W / (2 * k); val halfH = STAGE_H / (2 * k)
        fun keep(c: Float, lo: Float, hi: Float, half: Float) = if (hi - lo <= 2 * half) (lo + hi) / 2 else c.coerceIn(lo + half, hi - half)
        val cx = keep(lerp(a.second, b.second, t), window.left - 40f, window.right + 40f, halfW)
        val cy = keep(lerp(a.third, b.third, t), window.top - 30f, window.bottom + 30f, halfH)
        return Triple(k, if (k <= 1.001f) W / 2f else cx, if (k <= 1.001f) STAGE_H / 2f else cy)
    }

    // --- The frames ------------------------------------------------------------------------------

    val deskSize = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", File(dir, "desk.mkv").absolutePath)
        .start().inputStream.bufferedReader().readText().trim().split(',').map { it.toInt() }
    val desk = Frames(File(dir, "desk.mkv"), deskSize[0], deskSize[1])
    var clip: Frames? = null
    var clipIndex = -1
    val partVideo = part?.let { Frames(File(phoneDir, it.video), PW, PH, scale = "scale=$PW:$PH") }

    fun emit() {
        surface.readPixels(pixels, 0, 0)
        sink.write(pixels.readPixels()!!)
    }

    fun deskFrame(f: Int, outF: Int) {
        val img = desk.next() ?: return
        val t = outF.toFloat() / FPS
        background(t)
        val (win, phoneIn) = layoutAt(f)
        val (k, cx, cy) = cameraAt(f, win)
        canvas.save()
        // Lean in: the point looked at moves to the middle of the stage; nothing spills into the caption band.
        if (k > 1.001f) canvas.clipRect(Rect(0f, 0f, W.toFloat(), STAGE_H + 6f))
        canvas.translate(W / 2f, STAGE_H / 2f); canvas.scale(k, k); canvas.translate(-cx, -cy)
        shadow(win, 18f)
        picture(img, win, 14f)
        // The phone beside it.
        val ci = tl.phone.indexOfFirst { f in it.from until it.to }
        if (ci != clipIndex) {
            clip?.close(); clip = null; clipIndex = ci
            if (ci >= 0) { val c = tl.phone[ci]; clip = Frames(File(dir, c.file), PW, PH, from = c.offset, scale = "scale=$PW:$PH") }
        }
        val phoneImg = clip?.next()
        if (phoneIn > 0.01f) {
            val slide = (1 - phoneIn) * 520f
            phone(phoneImg, Rect(sidePhone.left + slide, sidePhone.top, sidePhone.right + slide, sidePhone.bottom), phoneIn)
        }
        // The pointer and the ripples of its clicks.
        val s = win.width / tl.width
        val (px, py, held) = tl.cursor[f].let { Triple(it[0], it[1], it[2]) }
        val x = win.left + px * s; val y = win.top + py * s
        for (c in tl.clicks) {
            val age = (f - c) / (0.5f * FPS)
            if (age !in 0f..1f) continue
            val r = 8f + 46f * ease(age)
            canvas.drawCircle(x, y, r, Paint().apply { mode = PaintMode.STROKE; strokeWidth = 3f * (1 - age) + 1f; color = (accent and 0x00FFFFFF) or (((1 - age) * 200).toInt() shl 24) })
            canvas.drawCircle(x, y, r * 0.5f, Paint().apply { color = (accent and 0x00FFFFFF) or (((1 - age) * 60).toInt() shl 24) })
        }
        cursorAt(x, y, 1.35f, held > 0.5f)
        canvas.restore()
        // Words over everything, unmoved by the camera.
        tl.captions.firstOrNull { f in it.from until it.to }?.let { c ->
            val appear = min((f - c.from) / 8f, (c.to - f) / 8f)
            // Under the window — beside the phone too, never over either.
            caption(c.ru, c.en, appear, cx = (win.left + win.right) / 2)
        }
        tl.chapters.lastOrNull { it.at <= f }?.let { c ->
            val next = tl.chapters.firstOrNull { it.at > c.at }?.at ?: Int.MAX_VALUE
            val len = (if (c === tl.chapters.first() || c === tl.chapters.last()) 3.0f else 2.4f)
            val p = (f - c.at).toFloat() / FPS
            if (p < len && f < next) card(c, p, len)
        }
        emit()
    }

    fun phoneFrame(i: Int, outF: Int) {
        val p = part!!
        val t = outF.toFloat() / FPS
        val sec = i.toFloat() / FPS
        background(t)
        val img = partVideo!!.next()
        // The phone on the right, large; its chapter and what's happening on the left.
        val r = Rect(1180f, 60f, 1180f + PW * 0.99f, 60f + PH * 0.99f)
        val enter = ease(sec / 0.8f)
        phone(img, Rect(r.left + (1 - enter) * 300f, r.top, r.right + (1 - enter) * 300f, r.bottom), enter)
        val ch = p.chapters.lastOrNull { it.at <= sec }
        if (ch != null) {
            val since = sec - ch.at.toFloat()
            val a = ease(since / 0.5f)
            canvas.saveLayer(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { alpha = (a * 255).toInt().coerceIn(0, 255) })
            val left = 180f
            text(ch.ru, Font(serifBold, 76f), left, 340f + (1 - a) * 20f, 0xFFF4EEE6.toInt())
            canvas.drawRRect(RRect.makeLTRB(left, 372f, left + 120f * ease((since - 0.15f) / 0.6f), 377f, 3f), Paint().apply { color = accent })
            var y = 432f
            for (line in wrap(ch.subRu, Font(sans, 32f), 820f)) { text(line, Font(sans, 32f), left, y, argb(0.88f, 244, 238, 230)); y += 42f }
            for (line in wrap(ch.subEn, Font(sans, 22f), 820f)) { text(line, Font(sans, 22f), left, y + 6f, argb(0.5f, 244, 238, 230)); y += 30f }
            canvas.restore()
        }
        p.captions.firstOrNull { sec >= it.from && sec < it.to }?.let { c ->
            val appear = min((sec - c.from.toFloat()) * FPS / 8f, (c.to.toFloat() - sec) * FPS / 8f)
            caption(c.ru, c.en, appear, cx = 620f, y = 780f)
        }
        // Each phone chapter opens with a full card too.
        p.chapters.lastOrNull { it.at <= sec }?.let { c ->
            val cp = sec - c.at.toFloat()
            if (cp < c.length) card(Chapter(0, c.ru, c.en, c.subRu, c.subEn), cp, c.length.toFloat())
        }
        emit()
    }

    var outF = 0
    for (f in 0 until tl.phonePartAt) deskFrame(f, outF++)
    for (i in 0 until phoneFrames) phoneFrame(i, outF++)
    for (f in tl.phonePartAt until deskFrames) deskFrame(f, outF++)
    sink.flush(); sink.close()
    out.waitFor()
    desk.close(); clip?.close(); partVideo?.close()

    // --- The sound -----------------------------------------------------------------------------
    // What the computer played (frame-exact), and what the phone played (its own recording). Beside
    // each other, the computer is on the left and the phone on the right; the pan glides over.
    data class Piece(val file: File, val raw: Boolean, val from: Double, val length: Double, val at: Double, val pan: String, val fadeIn: Double, val fadeOut: Double)
    fun outSec(deskFrame: Int) = (if (deskFrame < tl.phonePartAt) deskFrame else deskFrame + phoneFrames).toDouble() / FPS
    val deskPcm = File(dir, "desk.pcm")
    val side = tl.phone.firstOrNull()
    val glide = 0.4
    val cuts = listOfNotNull(0, side?.from, side?.to, tl.phonePartAt, deskFrames).distinct().sorted()
    val pieces = ArrayList<Piece>()
    for (i in 0 until cuts.size - 1) {
        val a = cuts[i]; val b = cuts[i + 1]
        if (b <= a) continue
        val pan = if (side != null && a == side.from) "left" else "centre"
        // Neighbours of the same sound overlap by [glide] with crossed fades; across the phone's part they just fade.
        val joinBefore = a != 0 && a != tl.phonePartAt
        val joinAfter = b != deskFrames && b != tl.phonePartAt
        val from = a.toDouble() / FPS - if (joinBefore) glide / 2 else 0.0
        val to = b.toDouble() / FPS + if (joinAfter) glide / 2 else 0.0
        pieces += Piece(deskPcm, true, from, to - from, outSec(a) - if (joinBefore) glide / 2 else 0.0, pan, if (joinBefore) glide else 0.3, if (joinAfter) glide else 0.5)
    }
    side?.let { c -> pieces += Piece(File(dir, c.file), false, c.offset, (c.to - c.from).toDouble() / FPS, outSec(c.from), "right", 0.3, 0.6) }
    part?.let { pieces += Piece(File(phoneDir, it.audio ?: it.video), false, it.audioOffset, it.duration, tl.phonePartAt.toDouble() / FPS, "centre", 0.5, 0.8) }
    val cmd = arrayListOf("ffmpeg", "-v", "error", "-y", "-i", File(dir, "video.mp4").absolutePath)
    fun f3(x: Double) = "%.3f".format(java.util.Locale.ROOT, x)
    pieces.forEach { p ->
        if (p.raw) cmd += listOf("-f", "s16le", "-ar", "${dev.xvin.palimpsest.desktop.playback.AudioEngine.RATE}", "-ac", "2")
        cmd += listOf("-ss", f3(p.from.coerceAtLeast(0.0)), "-t", f3(p.length), "-i", p.file.absolutePath)
    }
    val filters = pieces.mapIndexed { i, p ->
        val ms = (p.at.coerceAtLeast(0.0) * 1000).toLong()
        val pan = when (p.pan) {
            "left" -> ",pan=stereo|c0=0.62*c0+0.62*c1|c1=0.1*c0+0.1*c1"
            "right" -> ",pan=stereo|c0=0.1*c0+0.1*c1|c1=0.62*c0+0.62*c1"
            else -> ""
        }
        "[${i + 1}:a]aresample=48000,aformat=channel_layouts=stereo$pan,afade=t=in:d=${f3(p.fadeIn)},afade=t=out:st=${f3((p.length - p.fadeOut).coerceAtLeast(0.0))}:d=${f3(p.fadeOut)},adelay=$ms|$ms[a$i]"
    }
    val mix = pieces.indices.joinToString("") { "[a$it]" } + "amix=inputs=${pieces.size}:normalize=0:duration=longest,loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=${f3(total.toDouble() / FPS - 1.5)}:d=1.5[aout]"
    cmd += listOf("-filter_complex", (filters + mix).joinToString(";"), "-map", "0:v", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-t", f3(total.toDouble() / FPS), File(dir, "tour.mp4").absolutePath)
    val mux = ProcessBuilder(cmd).inheritIO().start().waitFor()
    println("tourCut: $total frames (${total / FPS} s), sound ${if (mux == 0) "ok" else "failed ($mux)"} → ${File(dir, "tour.mp4")}")
    Runtime.getRuntime().halt(0)
}
