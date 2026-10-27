package dev.xvin.palimpsest.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
    var positionMs = System.getenv("DEMO_POS")?.toLongOrNull() ?: 36_000L
    var playing = false
    engine.value = AudioEngine.State(positionMs = positionMs)
    val current = graph.player.state.value.current
    val colors = runBlocking { current?.let { CoverColors.of(graph, it) } } ?: (Vermilion to Color(0xFF2A2A30))

    val nav = Nav()
    var theme by mutableStateOf(DesktopTheme.DARK)
    var style by mutableStateOf(DesktopStyle.PALIMPSEST)
    var cursor by mutableStateOf(Offset(640f, 400f))
    val scene = ImageComposeScene(w, h, Density(1f)) {
        setSingletonImageLoaderFactory { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                .diskCache { DiskCache.Builder().directory(File(Paths.cache, "coil").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                .build()
        }
        PalimpsestDesktopTheme(style, theme, colors.first, colors.second) {
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
        if (playing) {
            positionMs += 1000L / fps
            engine.value = AudioEngine.State(playing = true, positionMs = positionMs)
        }
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

    // Where things are in each version (1280 × 820).
    val old = variant == "old"
    fun at(oldXY: Pair<Int, Int>, newXY: Pair<Int, Int>) = if (old) oldXY else newXY
    val tabAlbums = at(380 to 116, 795 to 41)
    val tabArtists = at(495 to 116, 891 to 41)
    val tabTracks = at(292 to 116, 723 to 41)
    val sortArtist = at(478 to 217, 504 to 130)
    val sortTitle = at(385 to 217, 390 to 130)
    val barHeart = at(359 to 773, 145 to 781)
    val barCover = at(46 to 773, 40 to 781)
    val barLyrics = at(993 to 773, 1052 to 781)
    val barQueue = at(1040 to 773, 1086 to 781)
    val setSound = at(330 to 250, 340 to 190)
    val setKeys = at(380 to 440, 366 to 360)
    fun move(p: Pair<Int, Int>, seconds: Double = 0.5) = move(p.first, p.second, seconds)

    // --- The tour ---
    hold(0.8)
    say("Медиатека: таблица треков, наведение на строки")
    move(700, 300, 0.7); move(700, 360, 0.5); move(700, 470, 0.6); hold(0.4)
    wheel(3f, 6); hold(0.5); wheel(-3f, 6); hold(0.4)
    say("Сортировка по колонкам")
    move(sortArtist, 0.7); click(); hold(1.4)
    move(sortTitle, 0.5); click(); hold(1.0)
    say("Вкладки: альбомы и исполнители")
    move(tabAlbums, 0.7); click(); hold(0.8)
    move(420, 330, 0.6); hold(0.6); move(620, 330, 0.5); hold(0.6)
    move(tabArtists, 0.7); click(); hold(1.2)
    move(tabTracks, 0.6); click(); hold(0.8)
    say("Меню трека по правому клику")
    move(620, 335, 0.6); click(PointerButton.Secondary); hold(0.4)
    move(cursor.x + 60, cursor.y + 70, 0.5); move(cursor.x, cursor.y + 120, 0.6); hold(0.6)
    nav.menu = emptyList(); hold(0.4)
    say("Лайк")
    move(barHeart, 0.8); click(); hold(0.8); click(); hold(1.0)
    say("Большой плеер: синхронный текст")
    playing = true
    move(barCover, 0.8); click(); hold(5.5)
    say("Только обложка")
    move(barLyrics, 0.7); click(); hold(2.2); click(); hold(1.0)
    say("Назад в медиатеку")
    move(barCover, 0.6); click(); hold(1.4)
    say("Боковая панель: текст и очередь")
    move(barLyrics, 0.6); click(); hold(2.8)
    move(barQueue, 0.5); click(); hold(2.2)
    nav.rightPanel = null; hold(0.5)
    say("Поиск по медиатеке")
    nav.root(Screen.Search); nav.searchFocus++; hold(0.8)
    type("оля ля"); hold(1.8)
    move(640, 500, 0.6); wheel(3f, 5); hold(1.0)
    current?.artists?.firstOrNull()?.let { a ->
        say("Страница исполнителя")
        nav.go(Screen.Artist(a)); hold(2.6)
        move(640, 450, 0.5); wheel(4f, 8); hold(1.0)
        move(380, 420, 0.6); hold(1.0)
    }
    current?.album?.let { al ->
        say("Альбом")
        nav.go(Screen.LocalAlbum(al, current.albumArtist ?: current.artist)); hold(2.2)
    }
    say("Сервисы и «Моя волна»")
    nav.root(Screen.Services); hold(1.8)
    nav.waveTuning = true; hold(2.4); nav.waveTuning = false; hold(0.5)
    say("История прослушиваний")
    nav.root(Screen.History); hold(1.5); move(700, 500, 0.5); wheel(4f, 6); hold(0.8)
    say("Настройки")
    nav.root(Screen.Settings); hold(1.2)
    move(setSound, 0.7); click(); hold(1.4)
    move(setKeys, 0.6); click(); hold(1.6)
    if (current?.path != null) {
        say("Теги файла")
        nav.dialog = TrackDialog.EditTags(listOf(current)); hold(2.4); nav.dialog = null; hold(0.3)
    }
    if (current != null) {
        say("Видео из трека")
        nav.dialog = TrackDialog.Video(current); hold(2.2); nav.dialog = null; hold(0.3)
    }
    say("Светлая тема")
    theme = DesktopTheme.LIGHT; nav.root(Screen.Library); hold(1.6)
    nav.nowPlaying = true; hold(2.4); nav.nowPlaying = false; hold(0.8)
    say("Стиль Lane")
    theme = DesktopTheme.DARK; style = DesktopStyle.LANE; hold(1.6)
    nav.nowPlaying = true; hold(2.8); nav.nowPlaying = false; hold(1.0)
    say("")
    style = DesktopStyle.PALIMPSEST; hold(0.6)

    caption?.let { if (it.isNotEmpty()) captions += Triple(captionStart, frames.toDouble() / fps, it) }
    sink.flush(); sink.close()
    ffmpeg.waitFor()
    scene.close()
    fun ts(s: Double) = "%02d:%02d:%02d,%03d".format((s / 3600).toInt(), (s / 60 % 60).toInt(), (s % 60).toInt(), ((s * 1000) % 1000).toInt())
    File(out.path.substringBeforeLast('.') + ".srt").writeText(
        captions.filter { it.third.isNotEmpty() }.mapIndexed { i, (a, b, text) -> "${i + 1}\n${ts(a)} --> ${ts(b)}\n$text\n" }.joinToString("\n"),
    )
    println("demo: $frames frames → $out")
    kotlin.system.exitProcess(0)
}
