package dev.xvin.palimpsest.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.xvin.palimpsest.desktop.data.DesktopStyle
import dev.xvin.palimpsest.desktop.data.DesktopTheme
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.playback.AudioEngine
import dev.xvin.palimpsest.desktop.ui.CoverColors
import dev.xvin.palimpsest.desktop.ui.LocalGraph
import dev.xvin.palimpsest.desktop.ui.LocalNav
import dev.xvin.palimpsest.desktop.ui.Nav
import dev.xvin.palimpsest.desktop.ui.PalimpsestDesktopTheme
import dev.xvin.palimpsest.desktop.ui.Screen
import dev.xvin.palimpsest.desktop.ui.Shell
import dev.xvin.palimpsest.desktop.ui.TrackDialog
import dev.xvin.palimpsest.desktop.ui.Vermilion
import dev.xvin.palimpsest.desktop.ui.language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import java.io.File

/**
 * A recorded tour of the app without a window: the same script for any version (only where to click
 * differs), frames piped into ffmpeg at 30 fps. Nothing really plays: the engine is told a
 * position that moves with the video's clock. Captions go to a .srt next to the video.
 *   XDG_CONFIG_HOME=… ./gradlew :desktop:demo -Pout=/dir/new.mp4 -Pvariant=new|old
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args[0])
    val variant = args.getOrElse(1) { "new" }
    val w = 1280
    val h = 820
    val fps = 30
    val graph = Graph().also { language = it.settings.value.language }
    val engine = graph.player.engine.state as MutableStateFlow<AudioEngine.State>
    // The music plays from the first frame, from here; the video's sound is cut from the same spot.
    val startMs = System.getenv("DEMO_POS")?.toLongOrNull() ?: 20_000L
    engine.value = AudioEngine.State(playing = true, positionMs = startMs)
    val current = graph.player.state.value.current
    val colors = runBlocking { current?.let { CoverColors.of(graph, it) } } ?: (Vermilion to Color(0xFF2A2A30))

    val nav = Nav()
    var cursor by mutableStateOf(Offset(640f, 400f))
    val scene = ImageComposeScene(w, h, Density(1f)) {
        setSingletonImageLoaderFactory { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                .diskCache { DiskCache.Builder().directory(File(Paths.cache, "coil").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                .build()
        }
        val settings by graph.settings.state.collectAsState()
        PalimpsestDesktopTheme(settings.style, settings.theme, colors.first, colors.second) {
            CompositionLocalProvider(LocalGraph provides graph, LocalNav provides nav) { Shell() }
        }
        // The pointer, drawn: a plain arrow with a dark edge, readable on any background.
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val p = Path().apply {
                    moveTo(cursor.x, cursor.y); lineTo(cursor.x, cursor.y + 18f); lineTo(cursor.x + 4.5f, cursor.y + 14f)
                    lineTo(cursor.x + 8f, cursor.y + 21f); lineTo(cursor.x + 10.5f, cursor.y + 20f); lineTo(cursor.x + 7f, cursor.y + 13f)
                    lineTo(cursor.x + 12.5f, cursor.y + 13f); close()
                }
                drawPath(p, Color.White)
                drawPath(p, Color.Black, style = Stroke(1.4f))
            }
        }
    }

    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${w}x$h", "-framerate", "$fps", "-i", "-",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", out.absolutePath,
    ).redirectErrorStream(true).start()
    val sink = ffmpeg.outputStream.buffered(1 shl 22)
    val bitmap = Bitmap().apply { allocN32Pixels(w, h) }
    var clock = 0L // ns, the video's
    var frames = 0
    val captions = mutableListOf<Triple<Double, Double, String>>()
    var captionStart = 0.0
    var caption: String? = null

    fun frame() {
        clock += 1_000_000_000L / fps
        engine.value = AudioEngine.State(playing = true, positionMs = startMs + frames * 1000L / fps)
        val img = scene.render(clock)
        img.readPixels(bitmap)
        sink.write(bitmap.readPixels()!!)
        img.close()
        frames++
        // Real time too, for covers, lyrics and pages that load in the background.
        Thread.sleep(6)
    }
    fun hold(seconds: Double) = repeat((seconds * fps).toInt()) { frame() }
    fun say(text: String) {
        val now = frames.toDouble() / fps
        caption?.let { captions += Triple(captionStart, now, it) }
        caption = text
        captionStart = now
    }
    fun pointer(type: PointerEventType, at: Offset, button: PointerButton? = null, pressed: Boolean = false, scroll: Offset = Offset.Zero) =
        scene.sendPointerEvent(
            type, at, scrollDelta = scroll, timeMillis = clock / 1_000_000,
            buttons = if (pressed) PointerButtons(isPrimaryPressed = button == PointerButton.Primary, isSecondaryPressed = button == PointerButton.Secondary) else PointerButtons(),
            button = button,
        )
    fun move(x: Number, y: Number, seconds: Double = 0.5) {
        val from = cursor
        val to = Offset(x.toFloat(), y.toFloat())
        val n = (seconds * fps).toInt().coerceAtLeast(1)
        for (i in 1..n) {
            val f = i.toFloat() / n
            val e = if (f < 0.5f) 2 * f * f else 1 - (-2 * f + 2).let { it * it } / 2
            cursor = from + (to - from) * e
            pointer(PointerEventType.Move, cursor)
            frame()
        }
    }
    fun click(button: PointerButton = PointerButton.Primary) {
        pointer(PointerEventType.Press, cursor, button, pressed = true)
        frame()
        pointer(PointerEventType.Release, cursor, button)
        frame()
    }
    fun wheel(notches: Float, times: Int) = repeat(times) { pointer(PointerEventType.Scroll, cursor, scroll = Offset(0f, notches)); hold(0.12) }
    fun type(text: String) = text.forEach { ch ->
        val awt = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch)
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyDown, codePoint = ch.code, nativeEvent = awt))
        hold(0.1)
    }

    // Where things are: found on screen by their words (or icon labels) in the semantics tree, so
    // every step is a visible click and the same script runs on any version. A few icons in the old
    // player bar have no label: those few by position.
    fun nodes(): List<List<androidx.compose.ui.semantics.SemanticsNode>> = scene.semanticsOwners.map { o ->
        val out = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(o.unmergedRootSemanticsNode)
        out
    }
    fun words(n: androidx.compose.ui.semantics.SemanticsNode): List<String> {
        val c = n.config
        return c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
            c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty()
    }
    /** Where [text] is: in the topmost layer that has it (a menu or dialog over the window), the first or [lowest] match inside [where]. */
    fun find(text: String, lowest: Boolean = false, where: (androidx.compose.ui.geometry.Rect) -> Boolean = { true }): Offset? {
        for (layer in nodes().reversed()) {
            val hits = layer.filter { n -> words(n).any { it.equals(text, ignoreCase = true) } }.map { it.boundsInWindow }
                .filter { it.width > 0f && it.height > 0f && where(it) && it.center.y in 0f..h.toFloat() }
            if (hits.isNotEmpty()) return (if (lowest) hits.maxBy { it.top } else hits.minBy { it.top }).center
        }
        return null
    }
    fun clickOn(text: String, seconds: Double = 0.6, button: PointerButton = PointerButton.Primary, lowest: Boolean = false, where: (androidx.compose.ui.geometry.Rect) -> Boolean = { true }) {
        val p = find(text, lowest, where)
        if (p == null) { System.err.println("demo: no «$text» on screen"); return }
        move(p.x, p.y, seconds); hold(0.15); click(button)
    }
    val sidebar = { r: androidx.compose.ui.geometry.Rect -> r.center.x < 240f }
    val page = { r: androidx.compose.ui.geometry.Rect -> r.center.x > 240f && r.center.y < 730f }
    val top = { r: androidx.compose.ui.geometry.Rect -> r.center.x > 240f && r.center.y < 220f }
    val bar = { r: androidx.compose.ui.geometry.Rect -> r.center.y > 735f }
    val old = variant == "old"
    fun at(oldXY: Pair<Int, Int>, newXY: Pair<Int, Int>) = if (old) oldXY else newXY
    val barHeart = at(359 to 773, 145 to 781)
    val barCover = at(46 to 773, 40 to 781)
    val barLyrics = at(993 to 773, 1052 to 781)
    val barQueue = at(1040 to 773, 1086 to 781)
    fun move(p: Pair<Int, Int>, seconds: Double = 0.5) = move(p.first, p.second, seconds)
    fun press(p: Pair<Int, Int>, seconds: Double = 0.6) { move(p, seconds); hold(0.15); click() }

    // --- The tour ---
    hold(0.8)
    say("Медиатека: таблица треков, наведение на строки")
    move(700, 300, 0.7); move(700, 360, 0.5); move(700, 470, 0.6); hold(0.4)
    wheel(3f, 6); hold(0.5); wheel(-3f, 6); hold(0.4)
    say("Сортировка по колонкам")
    clickOn("Исполнитель", where = { page(it) && it.center.y < 260f }); hold(1.4)
    clickOn("Название", where = { page(it) && it.center.y < 260f }); hold(1.0)
    say("Вкладки: альбомы и исполнители")
    clickOn("Альбомы", where = top); hold(0.8)
    move(420, 330, 0.6); hold(0.6); move(620, 330, 0.5); hold(0.6)
    clickOn("Исполнители", where = top); hold(1.2)
    clickOn("Треки", where = top); hold(0.8)
    say("Меню трека по правому клику")
    move(620, 335, 0.6); hold(0.2); click(PointerButton.Secondary); hold(0.5)
    move(cursor.x + 60, cursor.y + 70, 0.5); move(cursor.x, cursor.y + 120, 0.6); hold(0.6)
    clickOn("Медиатека", where = top); hold(0.5)
    say("Лайк")
    press(barHeart, 0.8); hold(0.8); click(); hold(1.0)
    say("Большой плеер: синхронный текст")
    press(barCover, 0.8); hold(5.5)
    say("Только обложка")
    press(barLyrics, 0.7); hold(2.2); click(); hold(1.0)
    say("Назад в медиатеку")
    press(barCover, 0.6); hold(1.4)
    say("Боковая панель: текст и очередь")
    press(barLyrics, 0.6); hold(2.8)
    press(barQueue, 0.5); hold(2.2)
    click(); hold(0.6)
    say("Поиск по медиатеке")
    clickOn("Поиск", where = sidebar); hold(0.6)
    clickOn("Исполнитель, альбом, трек", where = page); hold(0.2)
    type("оля ля"); hold(1.6)
    say("Страница исполнителя")
    current?.artists?.firstOrNull()?.let { a -> clickOn(a, where = { page(it) && it.center.y > 120f }); hold(2.6) }
    move(640, 450, 0.5); wheel(4f, 8); hold(1.0)
    say("Альбом — из меню играющего трека")
    clickOn("Действия", where = bar); hold(0.6)
    current?.album?.let { al -> clickOn(al, lowest = true); hold(2.2) }
    say("Сервисы и «Моя волна»")
    clickOn("Сервисы", where = sidebar); hold(1.6)
    clickOn("Настроить волну", where = sidebar); hold(2.2)
    clickOn("Готово"); hold(0.5)
    say("История прослушиваний")
    clickOn("История", where = sidebar); hold(1.2); move(700, 500, 0.5); wheel(4f, 6); hold(0.8)
    say("Настройки")
    clickOn("Настройки", where = sidebar); hold(1.0)
    clickOn("Звук", where = page); hold(1.4)
    clickOn("Горячие клавиши", where = page); hold(1.6)
    if (current?.path != null) {
        say("Теги файла — из меню трека")
        clickOn("Действия", where = bar); hold(0.5)
        clickOn("Изменить теги…"); hold(2.4)
        clickOn("Отмена"); hold(0.4)
    }
    say("Видео из трека — из меню трека")
    clickOn("Действия", where = bar); hold(0.5)
    clickOn("Видео из трека…"); hold(2.2)
    clickOn("Отмена"); hold(0.4)
    say("Светлая тема — в настройках")
    clickOn("Оформление", where = page); hold(0.8)
    clickOn("Светлая", where = page); hold(1.4)
    press(barCover, 0.7); hold(2.2); press(barCover, 0.5); hold(0.8)
    say("Стиль Lane")
    clickOn("Тёмная", where = page); hold(0.6)
    clickOn("Lane", where = page); hold(1.4)
    press(barCover, 0.7); hold(2.8); press(barCover, 0.5); hold(1.0)
    say("")
    hold(0.4)

    caption?.let { if (it.isNotEmpty()) captions += Triple(captionStart, frames.toDouble() / fps, it) }
    sink.flush(); sink.close()
    ffmpeg.waitFor()
    scene.close()
    fun ts(s: Double) = "%02d:%02d:%02d,%03d".format((s / 3600).toInt(), (s / 60 % 60).toInt(), (s % 60).toInt(), ((s * 1000) % 1000).toInt())
    // For the sound: which file, from where.
    File(out.path.substringBeforeLast('.') + ".audio").writeText("${current?.path.orEmpty()}\n${startMs / 1000.0}\n")
    File(out.path.substringBeforeLast('.') + ".srt").writeText(
        captions.filter { it.third.isNotEmpty() }.mapIndexed { i, (a, b, text) -> "${i + 1}\n${ts(a)} --> ${ts(b)}\n$text\n" }.joinToString("\n"),
    )
    println("demo: $frames frames → $out")
    kotlin.system.exitProcess(0)
}
