package dev.xvin.palimpsest.desktop.tour

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.playback.AudioEngine
import dev.xvin.palimpsest.desktop.ui.CoverColors
import dev.xvin.palimpsest.desktop.ui.LocalGraph
import dev.xvin.palimpsest.desktop.ui.LocalNav
import dev.xvin.palimpsest.desktop.ui.Nav
import dev.xvin.palimpsest.desktop.ui.PalimpsestDesktopTheme
import dev.xvin.palimpsest.desktop.ui.Shell
import dev.xvin.palimpsest.desktop.ui.Vermilion
import dev.xvin.palimpsest.desktop.ui.language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import java.io.File

/** What the cut needs besides the app's frames: where the pointer was, what was said, where to look. */
@Serializable
data class TourTimeline(
    val fps: Int,
    val width: Int,
    val height: Int,
    /** Per frame: pointer x, y and 1 while a button is held. */
    val cursor: List<FloatArray>,
    val clicks: List<Int>,
    val captions: List<Caption>,
    val chapters: List<Chapter>,
    /** Where the camera looks from a frame on (window pixels); null rect — the whole window. */
    val camera: List<Camera>,
    /** From a frame on: «desk» (the window alone) or «side» (the window and the phone beside it). */
    val layouts: List<Layout>,
    val phone: List<PhoneClip>,
    val audio: List<AudioPart>,
    /** The phone's own part of the tour goes in here (a frame of this video). */
    val phonePartAt: Int,
)

@Serializable data class Caption(val from: Int, val to: Int, val ru: String, val en: String)
@Serializable data class Chapter(val at: Int, val ru: String, val en: String, val subRu: String, val subEn: String)
@Serializable data class Camera(val at: Int, val x: Float = 0f, val y: Float = 0f, val w: Float = 0f, val h: Float = 0f, val whole: Boolean = true)
@Serializable data class Layout(val at: Int, val mode: String)
/** A phone screen recording shown from frame [from] to [to]; its first frame is [offset] seconds before [from]. */
@Serializable data class PhoneClip(val file: String, val from: Int, val to: Int, val offset: Double)
/** Sound for frames [from]..[to]: a file from [seconds] on (a track, or the capture of what really played). */
@Serializable data class AudioPart(val from: Int, val to: Int, val file: String, val seconds: Double)

/**
 * The app's sound, taken at exactly the pace of the frames drawn: [tick] after each frame allows
 * one frame's worth of samples more; the engine's writes wait for that. Nothing playing → silence.
 * So the sound lines up with the picture however slow a frame was to draw.
 */
class ClockedPcm(file: File, private val fps: Int) : AudioEngine.Output {
    private val out = java.io.BufferedOutputStream(java.io.FileOutputStream(file), 1 shl 20)
    private val lock = Object()
    private var allowed = 0L
    private var written = 0L
    private val lead = 4096L // frames the engine may run ahead

    fun tick(frame: Int) = synchronized(lock) {
        allowed = frame.toLong() * AudioEngine.RATE / fps
        // Fell well behind (nothing playing, or loading): fill the gap with silence.
        if (written < allowed - 2 * lead) {
            val gap = (allowed - lead - written).toInt()
            out.write(ByteArray(gap * 4)); written += gap
        }
        lock.notifyAll()
    }

    override fun write(bytes: ByteArray, length: Int) {
        val n = length / 4
        synchronized(lock) {
            while (written + n > allowed + lead) lock.wait(50)
            out.write(bytes, 0, length); written += n
        }
    }

    fun close() = synchronized(lock) { out.flush(); out.close() }
}

/**
 * The full tour of the computer app, recorded without a window: every step is a pointer move and a
 * click on what's on screen (found by its words), typing or the wheel — never state set by hand.
 * Light screens are recorded in real time (one frame per 1/30 s of the wall clock), so what really
 * plays — the wave, a station, music handed to the phone — is heard as it was: the app and the
 * emulator play into a silent PipeWire sink that's recorded. The big player is drawn frame by frame
 * (it's slow to draw); its music comes from the file, from the same second.
 *
 *   XDG_CONFIG_HOME=… PIPEWIRE_NODE=palimpsest_demo ./gradlew :desktop:tour -Pout=/dir
 * Then `:desktop:tourCut` puts it together with the phone's part (see TourCut.kt).
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val dir = File(args[0]).apply { mkdirs() }
    // Logical size (what the script and the cut think in); drawn at 1.5× for a crisp 1080p.
    val w = 1280
    val h = 820
    val scale = 1.5f
    val pw = (w * scale).toInt()
    val ph = (h * scale).toInt()
    val fps = 60
    val frameNs = 1_000_000_000L / fps
    val graph = Graph().also { language = "ru" }
    // The app's sound into a file, frame by frame, instead of the sound card.
    val pcm = ClockedPcm(File(dir, "desk.pcm"), fps)
    graph.player.engine.output = pcm
    val current = graph.player.state.value.current
    val colors = runBlocking { current?.let { CoverColors.of(graph, it) } } ?: (Vermilion to Color(0xFF2A2A30))

    // Two fingers up on the emulator's touch screen, through its console (adb can't do multi-touch).
    File(dir, "swipe.sh").writeText(
        """
        ev() { adb emu event send "${'$'}@" >/dev/null; }
        F="EV_ABS:ABS_MT_TOOL_TYPE:0 EV_ABS:ABS_MT_PRESSURE:200 EV_ABS:ABS_MT_TOUCH_MAJOR:8"
        if [ "${'$'}1" = up ]; then Y=21000; S=-900; else Y=10000; S=900; fi
        ev EV_ABS:ABS_MT_SLOT:0 EV_ABS:ABS_MT_TRACKING_ID:51 ${'$'}F EV_ABS:ABS_MT_POSITION_X:11000 EV_ABS:ABS_MT_POSITION_Y:${'$'}Y EV_ABS:ABS_MT_SLOT:1 EV_ABS:ABS_MT_TRACKING_ID:52 ${'$'}F EV_ABS:ABS_MT_POSITION_X:20000 EV_ABS:ABS_MT_POSITION_Y:${'$'}Y EV_SYN:0:0
        for i in 1 2 3 4 5 6 7 8 9 10 11 12; do Y=${'$'}((Y+S)); ev EV_ABS:ABS_MT_SLOT:0 EV_ABS:ABS_MT_POSITION_Y:${'$'}Y EV_ABS:ABS_MT_SLOT:1 EV_ABS:ABS_MT_POSITION_Y:${'$'}Y EV_SYN:0:0; done
        ev EV_ABS:ABS_MT_SLOT:0 EV_ABS:ABS_MT_TRACKING_ID:-1 EV_ABS:ABS_MT_SLOT:1 EV_ABS:ABS_MT_TRACKING_ID:-1 EV_SYN:0:0
        """.trimIndent(),
    )


    val nav = Nav()
    val scene = ImageComposeScene(pw, ph, Density(scale)) {
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
    }

    val video = ProcessBuilder(
        "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${pw}x$ph", "-framerate", "$fps", "-i", "-",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "10", "-pix_fmt", "yuv444p", File(dir, "desk.mkv").absolutePath,
    ).redirectErrorStream(true).start()
    val sink = video.outputStream.buffered(1 shl 22)
    val bitmap = Bitmap().apply { allocN32Pixels(pw, ph) }

    var clock = 0L
    var frames = 0
    var cursor = Offset(640f, 400f)
    var pressed = false
    val cursorTrack = ArrayList<FloatArray>()
    val clicks = ArrayList<Int>()
    val captions = ArrayList<Caption>()
    val chapters = ArrayList<Chapter>()
    val camera = arrayListOf(Camera(0))
    val layouts = arrayListOf(Layout(0, "desk"))
    val phoneClips = ArrayList<PhoneClip>()
    val audio = ArrayList<AudioPart>()
    var caption: Triple<Int, String, String>? = null

    // Never faster than real time (a station's stream must keep up), never a frame written twice:
    // a slow frame (the big player) just takes longer — the sound follows the frames, not the clock.
    val walls = ArrayList<Long>()
    val begin = System.nanoTime()
    var lag = 0L
    // While the phone is being recorded the video must keep to the wall clock like the phone's does:
    // a slow frame is then shown a little longer instead of the phone drifting behind.
    var wallClocked = false

    fun frame() {
        val img = scene.render(clock)
        img.readPixels(bitmap)
        val px = bitmap.readPixels()!!
        img.close()
        var copies = 1
        val now0 = System.nanoTime()
        val due0 = begin + lag + (frames + 1) * frameNs
        if (wallClocked && now0 > due0) copies += ((now0 - due0) / frameNs).toInt()
        repeat(copies) {
            sink.write(px)
            cursorTrack += floatArrayOf(cursor.x, cursor.y, if (pressed) 1f else 0f)
            frames++
            clock += frameNs
            pcm.tick(frames)
            walls += System.nanoTime()
        }
        val due = begin + lag + frames * frameNs
        val now = System.nanoTime()
        if (now < due) Thread.sleep((due - now) / 1_000_000, ((due - now) % 1_000_000).toInt()) else if (!wallClocked) lag += now - due
        walls[walls.size - 1] = System.nanoTime()
    }
    fun hold(seconds: Double) = repeat((seconds * fps).toInt()) { frame() }
    fun holdUntil(maxSeconds: Double, done: () -> Boolean) { var n = 0; while (!done() && n < maxSeconds * fps) { frame(); n++ } }


    fun say(ru: String, en: String = "") {
        caption?.let { (from, r, e) -> if (r.isNotEmpty()) captions += Caption(from, frames, r, e) }
        caption = Triple(frames, ru, en)
    }
    fun chapter(ru: String, en: String, subRu: String, subEn: String, seconds: Double = 2.4) {
        say("")
        chapters += Chapter(frames, ru, en, subRu, subEn)
        hold(seconds)
    }
    fun look(r: Rect?) { camera += if (r == null) Camera(frames) else Camera(frames, r.left, r.top, r.width, r.height, false) }
    fun layout(mode: String) { layouts += Layout(frames, mode) }

    fun pointer(type: PointerEventType, at: Offset, button: PointerButton? = null, down: Boolean = false, scroll: Offset = Offset.Zero) =
        scene.sendPointerEvent(
            type, at * scale, scrollDelta = scroll, timeMillis = clock / 1_000_000,
            buttons = if (down) PointerButtons(isPrimaryPressed = button == PointerButton.Primary, isSecondaryPressed = button == PointerButton.Secondary) else PointerButtons(),
            button = button,
        )
    fun move(x: Number, y: Number, seconds: Double = 0.55) {
        val from = cursor
        val to = Offset(x.toFloat(), y.toFloat())
        val n = (seconds * fps).toInt().coerceAtLeast(1)
        for (i in 1..n) {
            val f = i.toFloat() / n
            val e = if (f < 0.5f) 4 * f * f * f else 1 - (-2 * f + 2).let { it * it * it } / 2
            // A slight arc, like a hand moving a mouse.
            val arc = kotlin.math.sin(f * Math.PI).toFloat() * (to - from).getDistance() * 0.06f
            cursor = from + (to - from) * e + Offset(0f, -arc)
            pointer(PointerEventType.Move, cursor)
            frame()
        }
    }
    fun click(button: PointerButton = PointerButton.Primary) {
        clicks += frames
        pressed = true
        pointer(PointerEventType.Press, cursor, button, down = true)
        frame(); frame()
        pressed = false
        pointer(PointerEventType.Release, cursor, button)
        frame()
    }
    fun wheel(notches: Float, times: Int) = repeat(times) { pointer(PointerEventType.Scroll, cursor, scroll = Offset(0f, notches)); hold(0.1) }
    fun type(text: String) = text.forEach { ch ->
        val awt = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch)
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyDown, codePoint = ch.code, nativeEvent = awt))
        hold(0.09)
    }

    // Things on screen, by their words or icon labels, in the topmost layer that has them.
    fun nodes(): List<List<SemanticsNode>> = scene.semanticsOwners.map { o ->
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(o.unmergedRootSemanticsNode)
        out
    }
    fun words(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    fun findRect(text: String, lowest: Boolean = false, topOnly: Boolean = false, where: (Rect) -> Boolean = { true }): Rect? {
        for (layer in nodes().reversed().let { if (topOnly) it.take(1) else it }) {
            val hits = layer.filter { n -> words(n).any { it.equals(text, ignoreCase = true) } }
                .map { it.boundsInWindow.let { b -> Rect(b.left / scale, b.top / scale, b.right / scale, b.bottom / scale) } }
                .filter { it.width > 0f && it.height > 0f && where(it) && it.center.y in 0f..h.toFloat() && it.center.x in 0f..w.toFloat() }
            if (hits.isNotEmpty()) return if (lowest) hits.maxBy { it.top } else hits.minBy { it.top }
        }
        return null
    }
    fun clickOn(text: String, seconds: Double = 0.6, button: PointerButton = PointerButton.Primary, lowest: Boolean = false, topOnly: Boolean = false, where: (Rect) -> Boolean = { true }): Boolean {
        val r = findRect(text, lowest, topOnly, where) ?: run { System.err.println("tour: no «$text» on screen"); return false }
        move(r.center.x, r.center.y, seconds); hold(0.12); click(button)
        return true
    }
    fun waitFor(text: String, maxSeconds: Double, where: (Rect) -> Boolean = { true }) = holdUntil(maxSeconds) { findRect(text, where = where) != null }

    val sidebar = { r: Rect -> r.center.x < 240f }
    val page = { r: Rect -> r.center.x > 240f && r.center.y < 730f }
    val top = { r: Rect -> r.center.x > 240f && r.center.y < 220f }
    val bar = { r: Rect -> r.center.y > 735f }
    val barCover = Offset(40f, 781f)
    fun press(p: Offset, seconds: Double = 0.6) { move(p.x, p.y, seconds); hold(0.12); click() }
    fun adb(vararg a: String) = ProcessBuilder(listOf("adb") + a).redirectErrorStream(true).start()

    // --- The tour -------------------------------------------------------------------------------

    hold(0.6)
    chapter("Palimpsest", "Palimpsest", "Музыкальный плеер для компьютера и телефона", "A music player for the computer and the phone", 3.0)

    chapter("Медиатека", "The library", "Свои файлы — прежде всего; сервисы — когда не хватает", "Your files first; services fill the gaps")
    // The music starts for real, from where it was: the play button in the bar.
    press(Offset(640f, 773f), 0.8); holdUntil(8.0) { graph.player.state.value.playing }
    say("Таблица треков: наведение, сортировка по колонкам", "The track table: hover, sort by any column")
    move(700, 300, 0.7); move(700, 380, 0.5); move(700, 480, 0.6); hold(0.3)
    clickOn("Исполнитель", where = { page(it) && it.center.y < 260f }); hold(1.2)
    clickOn("Название", where = { page(it) && it.center.y < 260f }); hold(0.8)
    say("Альбомы и исполнители", "Albums and artists")
    clickOn("Альбомы", where = top); hold(0.6); move(560, 360, 0.6); hold(1.0)
    clickOn("Исполнители", where = top); hold(1.2)
    clickOn("Треки", where = top); hold(0.6)
    say("Меню трека — правой кнопкой: теги, обложка, версии, FLAC, видео", "A track's menu on right-click: tags, cover, versions, FLAC, video")
    move(620, 335, 0.6); hold(0.2); click(PointerButton.Secondary); hold(0.4)
    move(cursor.x + 60, cursor.y + 70, 0.5); move(cursor.x, cursor.y + 130, 0.6); hold(0.8)
    clickOn("Медиатека", where = top); hold(0.4)

    chapter("Большой плеер", "The big player", "Текст поётся вместе с песней", "Lyrics sung along with the song")
    press(barCover, 0.8)
    say("Строка заполняется, пока её поют; клик по строке — перемотка", "The sung line fills as it's sung; click a line to jump there")
    hold(1.2)
    look(Rect(560f, 60f, 1270f, 720f)); hold(5.5)
    look(null); hold(0.6)
    say("Только обложка — и обратно", "Just the cover — and back")
    clickOn("Текст", where = bar); hold(2.2)
    clickOn("Текст", where = bar); hold(1.0)
    press(barCover, 0.6); hold(1.0)

    // --- With the phone, side by side ---
    chapter("Телефон ↔ компьютер", "Phone ↔ computer", "Музыка переезжает с той же секунды", "The music moves over from the same second")
    holdUntil(40.0) { graph.handoff.online.value }
    layout("side"); hold(1.2)
    // The phone's screen and its own sound, recorded together at 60 fps.
    val phoneFile = "handoff.mkv"
    val scr = ProcessBuilder(
        // Unbuffered, or «Recording started» only shows when scrcpy exits.
        "stdbuf", "-o0", "-e0", "scrcpy", "--no-playback", "--no-window", "--audio-source=playback", "--max-fps=60", "--video-bit-rate=16M",
        "--record=" + File(dir, phoneFile).absolutePath,
    ).redirectErrorStream(true).start()
    var recStart = 0L
    kotlin.concurrent.thread(isDaemon = true) { scr.inputStream.bufferedReader().forEachLine { if ("Recording started" in it && recStart == 0L) recStart = System.nanoTime() } }
    holdUntil(15.0) { recStart != 0L }
    lag = System.nanoTime() - (begin + frames * frameNs)
    wallClocked = true
    val recFrom = frames
    hold(1.0)
    say("Кнопка с телефоном — «Передать на телефон»", "The phone button — «Send to the phone»")
    clickOn("Музыка на телефон и обратно", where = bar); hold(0.5)
    clickOn("Передать на телефон"); hold(3.5)
    adb("shell", "input", "tap", "351", "1984").waitFor() // the phone opens its player
    hold(2.0)
    say("На телефоне — два пальца вверх: обратно на компьютер", "On the phone — two fingers up: back to the computer")
    hold(1.0)
    ProcessBuilder("bash", File(dir, "swipe.sh").absolutePath, "up").redirectErrorStream(true).start().waitFor()
    hold(4.5)
    say("Два пальца вниз — снова на телефон", "Two fingers down — onto the phone again")
    hold(0.8)
    ProcessBuilder("bash", File(dir, "swipe.sh").absolutePath, "down").redirectErrorStream(true).start().waitFor()
    hold(4.5)
    say("А с компьютера — «Забрать с телефона»", "And from the computer — «Take from the phone»")
    clickOn("Музыка на телефон и обратно", where = bar); hold(0.5)
    clickOn("Забрать с телефона"); hold(5.0)
    phoneClips += PhoneClip(phoneFile, recFrom, frames, (walls[recFrom - 1] - recStart) / 1e9)
    wallClocked = false
    ProcessBuilder("kill", "-INT", scr.pid().toString()).start().waitFor()
    scr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
    layout("desk"); hold(1.2)


    chapter("Моя волна", "My Wave", "Яндекс, YouTube Music, Bandcamp — или всё вместе", "Yandex, YouTube Music, Bandcamp — or all of them mixed")
    say("Источник волны и доли", "Where the wave comes from, and the shares")
    clickOn("Настроить волну", where = sidebar); hold(1.0)
    clickOn("Смешанная", topOnly = true); hold(1.2)
    // The YouTube share: the «2» under its caption (the lower «YouTube Music»; the upper one is the source).
    findRect("YouTube Music", lowest = true, topOnly = true)?.let { yt -> clickOn("2", topOnly = true, where = { it.top > yt.top && it.top < yt.top + 60f }) }; hold(1.0)
    say("Запускаем: свои файлы, Яндекс и YouTube вперемешку", "Start it: your files, Yandex and YouTube, mixed")
    clickOn("Запустить волну", topOnly = true); holdUntil(25.0) { graph.player.state.value.radio != null && graph.player.state.value.playing }
    look(Rect(0f, 700f, 640f, 820f)); hold(3.5)
    say("Закружилась на одних и тех же? «Встряхнуть» — свежая волна сразу", "Going round the same songs? «Shake up» starts a fresh one now")
    clickOn("Встряхнуть волну", where = bar); holdUntil(15.0) { !graph.player.shaking.value }; hold(2.5)
    look(null); hold(0.8)

    chapter("Интернет-радио", "Internet radio", "50 000 станций, и текст песни, что сейчас в эфире", "50,000 stations, and the lyrics of what's on air")
    say("Каталог станций: поиск, жанры, популярное в стране", "The station catalog: search, genres, the country's most listened")
    clickOn("Радио", where = sidebar); waitFor("ROCK FM", 20.0, page); hold(1.2)
    move(700, 420, 0.6); hold(0.6)
    clickOn("ROCK FM", where = page); holdUntil(20.0) { graph.player.state.value.onAir != null }; hold(0.6)
    say("Что сейчас в эфире — прямо в плеере", "What's on air — right in the player")
    look(Rect(0f, 700f, 640f, 820f)); hold(3.0); look(null)
    say("«Текст» — слова этой песни", "«Lyrics» — the words of that song")
    clickOn("Текст", where = bar)
    // Until the words are there (or it's clear there are none).
    holdUntil(10.0) { nodes().flatten().count { n -> n.boundsInWindow.left / scale > 880f && words(n).isNotEmpty() } > 8 || findRect("Текста этой песни не нашлось") != null }
    hold(0.5); look(Rect(880f, 40f, 1280f, 720f)); hold(4.0); look(null); hold(0.5)
    clickOn("Текст", where = bar); hold(0.6)
    // The phone's own part goes here in the cut.
    val phonePartAt = frames
    chapter("И ещё", "And also", "История, настройки, темы и стили", "History, settings, themes and styles")
    say("История прослушиваний", "Listening history")
    clickOn("История", where = sidebar); hold(1.0); move(700, 500, 0.5); wheel(4f, 6); hold(0.6)
    say("Настройки по разделам; горячие клавиши — любые переназначаются", "Settings by section; every shortcut can be changed")
    clickOn("Настройки", where = sidebar); hold(0.8)
    clickOn("Горячие клавиши", where = page); hold(1.8)
    say("Светлая тема", "A light theme")
    clickOn("Оформление", where = page); hold(0.6)
    clickOn("Светлая", where = page); hold(1.8)
    clickOn("Тёмная", where = page); hold(0.6)
    say("Стиль Lane: цвет обложки во всём окне", "The Lane style: the cover's colour all over")
    clickOn("Lane", where = page); hold(1.0)
    clickOn("Медиатека", where = sidebar); hold(2.2)
    clickOn("Настройки", where = sidebar); hold(0.6)
    clickOn("Оформление", where = page); hold(0.4)
    clickOn("Palimpsest", where = page); hold(0.8)
    chapter("Palimpsest 0.6", "Palimpsest 0.6", "github.com/xvin84/palimpsest", "github.com/xvin84/palimpsest", 3.0)
    say("")

    sink.flush(); sink.close()
    video.waitFor()
    graph.player.engine.stop()
    pcm.close()
    audio += AudioPart(0, frames, "desk.pcm", 0.0)
    println("tour: drawing fell behind real time by ${lag / 1_000_000_000} s in all")
    val timeline = TourTimeline(fps, w, h, cursorTrack, clicks, captions, chapters, camera, layouts, phoneClips, audio, phonePartAt)
    File(dir, "desk.json").writeText(Http.json.encodeToString(TourTimeline.serializer(), timeline))
    println("tour: $frames frames, ${frames / fps} s → $dir")
    Runtime.getRuntime().halt(0)
}
