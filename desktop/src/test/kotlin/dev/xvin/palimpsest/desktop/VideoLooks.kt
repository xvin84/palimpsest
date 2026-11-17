package dev.xvin.palimpsest.desktop

import dev.xvin.palimpsest.core.covers.CoverTone
import dev.xvin.palimpsest.core.lyrics.LrcLine
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.core.render.VideoFrame
import dev.xvin.palimpsest.core.render.VideoLook
import dev.xvin.palimpsest.core.render.VideoSpec
import dev.xvin.palimpsest.desktop.render.SkiaPen
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File

/** Every look × format × mode as a PNG, for checking the video design without rendering a video. Args: out dir, cover image. */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/video-looks" }).apply { mkdirs() }
    val cover = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Image.makeFromEncoded(File(it).readBytes()) }
    val blurred = SkiaPen.blur(cover)
    val px = cover?.let { img ->
        val s = Surface.makeRasterN32Premul(48, 48)
        s.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(48f, 48f))
        val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(48, 48) }
        s.readPixels(bmp, 0, 0)
        IntArray(48 * 48) { i -> bmp.getColor(i % 48, i / 48) }
    }
    val mood = px?.let(CoverTone::of) ?: CoverTone.NEUTRAL
    val lyrics = ParsedLyrics(listOf(
        "Я помню этот город в январе", "Где фонари горели до утра", "Мы шли по льду и не боялись упасть",
        "И всё, что было — было не зря", "Припев: и пусть горит, пусть горит", "Всё, что мы не сказали вслух", "♪", "Последний куплет",
    ).mapIndexed { i, s -> LrcLine(10_000L + i * 4_000L, s) }, true)
    for (look in VideoLook.entries) for (vertical in listOf(true, false)) for (withLyrics in listOf(false, true)) {
        val spec = VideoSpec(
            look, vertical, "Вселенная бесконечна? (Live at Stadium Live)", "Noize MC", "Новый альбом", 2012,
            if (withLyrics) lyrics else null, 260_000, 0xFFE8B13A.toInt(), mood,
            listOf("FLAC" to true, "24/96" to false, "1024 kbps" to false, "RG −6.2 dB" to false), VideoFrame.previewEnvelope(),
        )
        val f = VideoFrame(spec)
        val surface = Surface.makeRasterN32Premul(f.width.toInt(), f.height.toInt())
        val pen = SkiaPen(surface.canvas, cover) { blurred }
        f.background(pen)
        f.draw(pen, if (withLyrics) 22_300 else VideoFrame.previewTime(spec), VideoFrame.previewLevels())
        val name = "${look.name.lowercase()}-${if (vertical) "v" else "h"}-${if (withLyrics) "lyrics" else "cover"}.png"
        File(out, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    println("written to $out")
}
