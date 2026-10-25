package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.FileOps
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.HotAction
import dev.xvin.palimpsest.desktop.data.Hotkeys
import dev.xvin.palimpsest.desktop.data.Tags
import dev.xvin.palimpsest.desktop.playback.Player
import kotlinx.coroutines.launch
import java.io.File

/** «Ctrl+Shift+K» for a key press (a Russian layout counts as the keys underneath), or null. */
fun strokeOf(e: KeyEvent): String? {
    // The AWT event under it (nativeKeyEvent is Compose's own wrapper, not the AWT event).
    val awt = e.awtEventOrNull ?: return null
    val code = Hotkeys.usCode(awt.keyCode, awt.keyChar.takeIf { it != java.awt.event.KeyEvent.CHAR_UNDEFINED })
    if (Hotkeys.isModifier(code)) return null
    return Hotkeys.stroke(code, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, e.isMetaPressed)
}

fun HotAction.title(): String = when (this) {
    HotAction.PLAY_PAUSE -> t("Играть / пауза", "Play / pause")
    HotAction.NEXT -> t("Следующий трек", "Next track")
    HotAction.PREVIOUS -> t("Предыдущий трек", "Previous track")
    HotAction.SEEK_FORWARD -> t("Вперёд на 5 секунд", "Forward 5 seconds")
    HotAction.SEEK_BACK -> t("Назад на 5 секунд", "Back 5 seconds")
    HotAction.VOLUME_UP -> t("Громче", "Volume up")
    HotAction.VOLUME_DOWN -> t("Тише", "Volume down")
    HotAction.MUTE -> t("Без звука / со звуком", "Mute / unmute")
    HotAction.SHUFFLE -> t("Перемешать", "Shuffle")
    HotAction.REPEAT -> t("Повтор: выкл. → всё → один", "Repeat: off → all → one")
    HotAction.LIKE -> t("Нравится / убрать из любимых", "Like / unlike")
    HotAction.DISLIKE -> t("Не нравится: убрать лайк и дальше", "Dislike: unlike and skip")
    HotAction.TRACK_MENU -> t("Меню текущего трека", "Menu of the current track")
    HotAction.EDIT_TAGS -> t("Теги текущего трека", "Tags of the current track")
    HotAction.TRACK_RADIO -> t("Радио по текущему треку", "Radio from the current track")
    HotAction.SHOW_IN_FOLDER -> t("Показать файл в папке", "Show the file in its folder")
    HotAction.COPY_LINK -> t("Скопировать ссылку на трек", "Copy the track’s link")
    HotAction.GO_ARTIST -> t("К исполнителю", "Go to the artist")
    HotAction.GO_ALBUM -> t("К альбому", "Go to the album")
    HotAction.BIG_PLAYER -> t("Большой плеер", "Big player")
    HotAction.LYRICS -> t("Текст песни", "Lyrics")
    HotAction.QUEUE -> t("Очередь", "Queue")
    HotAction.FULL_SCREEN -> t("Во весь экран", "Full screen")
    HotAction.SEARCH -> t("Поиск", "Search")
    HotAction.LIBRARY -> t("Медиатека", "Library")
    HotAction.FAVOURITES -> t("Любимые", "Favourites")
    HotAction.HISTORY -> t("История", "History")
    HotAction.SETTINGS -> t("Настройки", "Settings")
    HotAction.BACK -> t("Назад / снять выделение", "Back / clear selection")
    HotAction.SELECT_ALL -> t("Выделить все треки", "Select all tracks")
    HotAction.MY_WAVE -> t("Моя волна", "My Wave")
    HotAction.HIDE -> t("Спрятать окно", "Hide the window")
    HotAction.QUIT -> t("Выйти", "Quit")
}

fun HotAction.Group.title(): String = when (this) {
    HotAction.Group.PLAYBACK -> t("Воспроизведение", "Playback")
    HotAction.Group.TRACK -> t("Текущий трек", "Current track")
    HotAction.Group.VIEW -> t("Окно", "View")
    HotAction.Group.NAVIGATION -> t("Переходы", "Navigation")
    HotAction.Group.APP -> t("Приложение", "App")
}

/** What the window lends the keys: hiding, full screen, quitting. */
class WindowControls(val hide: () -> Unit, val toggleFullScreen: () -> Unit, val quit: () -> Unit)

private var volumeBeforeMute = 0.7f

/** Does [a]; false when there's nothing to do it on (so the key goes on to whatever has focus). */
fun runHotAction(a: HotAction, graph: Graph, nav: Nav, window: WindowControls): Boolean {
    val player = graph.player
    val current = player.state.value.current?.let { graph.library.get(it.id) ?: it }
    fun onCurrent(block: (dev.xvin.palimpsest.desktop.data.Track) -> Unit): Boolean { block(current ?: return false); return true }
    when (a) {
        HotAction.PLAY_PAUSE -> player.toggle()
        HotAction.NEXT -> player.next()
        HotAction.PREVIOUS -> player.previous()
        HotAction.SEEK_FORWARD -> player.seek(player.state.value.positionMs + 5000)
        HotAction.SEEK_BACK -> player.seek((player.state.value.positionMs - 5000).coerceAtLeast(0))
        HotAction.VOLUME_UP -> player.setVolume(graph.settings.value.volume + 0.05f)
        HotAction.VOLUME_DOWN -> player.setVolume(graph.settings.value.volume - 0.05f)
        HotAction.MUTE -> {
            val v = graph.settings.value.volume
            if (v > 0f) { volumeBeforeMute = v; player.setVolume(0f) } else player.setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 0.7f)
        }
        HotAction.SHUFFLE -> player.setShuffle(!player.state.value.shuffle)
        HotAction.REPEAT -> player.cycleRepeat()
        HotAction.LIKE -> return onCurrent { t ->
            val like = t.id !in graph.library.liked.value
            graph.library.setLiked(t.id, like)
            likeRemote(graph, t, like)
            nav.toast(if (like) t("♥ «${t.title}» — в любимых", "♥ «${t.title}» liked") else t("«${t.title}» убран из любимых", "«${t.title}» unliked"))
        }
        HotAction.DISLIKE -> return onCurrent { t ->
            if (t.id in graph.library.liked.value) { graph.library.setLiked(t.id, false); likeRemote(graph, t, false) }
            player.next()
        }
        HotAction.TRACK_MENU -> return onCurrent { nav.openMenu(it) }
        HotAction.EDIT_TAGS -> return onCurrent { t -> if (t.path != null && Tags.canEdit(File(t.path))) nav.dialog = TrackDialog.EditTags(listOf(t)) else nav.toast(t("У этого трека нет файла с тегами", "This track has no file to tag")) }
        HotAction.TRACK_RADIO -> return onCurrent { t ->
            if (t.service != Service.YANDEX || t.remoteId == null || !graph.yandex.isReady) { nav.toast(t("Радио есть только для треков Яндекса", "Radio works for Yandex tracks only")); return@onCurrent }
            graph.scope.launch { runCatching { player.startRadio("track:${t.remoteId}", t("Радио: ", "Radio: ") + t.title) }.onFailure { nav.toast(it.message ?: "") } }
        }
        HotAction.SHOW_IN_FOLDER -> return onCurrent { t -> t.path?.let { FileOps.showInFolder(File(it)) } }
        HotAction.COPY_LINK -> return onCurrent { t ->
            val link = FileOps.webLink(t)
            if (link != null) { FileOps.copyToClipboard(link); nav.toast(t("Ссылка скопирована", "Link copied")) } else nav.toast(t("У трека нет ссылки на сервис", "The track has no service link"))
        }
        HotAction.GO_ARTIST -> return onCurrent { t -> t.artists.firstOrNull()?.let { nav.go(Screen.Artist(it)) } }
        HotAction.GO_ALBUM -> return onCurrent { t ->
            val album = t.album ?: return@onCurrent
            if (t.path != null) nav.go(Screen.LocalAlbum(album, t.albumArtist ?: t.artist)) else t.remoteAlbumId?.let { nav.go(Screen.RemoteAlbum(it, t.service)) }
        }
        HotAction.BIG_PLAYER -> { if (current != null) nav.nowPlaying = !nav.nowPlaying }
        HotAction.LYRICS -> if (nav.nowPlaying) nav.bigLyrics = !nav.bigLyrics else nav.rightPanel = if (nav.rightPanel == "lyrics") null else "lyrics"
        HotAction.QUEUE -> nav.rightPanel = if (nav.rightPanel == "queue") null else "queue"
        HotAction.FULL_SCREEN -> window.toggleFullScreen()
        HotAction.SEARCH -> { nav.root(Screen.Search); nav.searchFocus++ }
        HotAction.LIBRARY -> nav.root(Screen.Library)
        HotAction.FAVOURITES -> nav.root(Screen.Liked)
        HotAction.HISTORY -> nav.root(Screen.History)
        HotAction.SETTINGS -> nav.root(Screen.Settings)
        HotAction.BACK -> if (nav.selection?.clear() != true) nav.back()
        HotAction.SELECT_ALL -> { nav.selection?.selectAll() ?: return false }
        HotAction.MY_WAVE -> {
            if (!graph.yandex.isReady) { nav.toast(t("Нужно войти в Яндекс Музыку (Сервисы)", "Sign in to Yandex Music first (Services)")); return true }
            graph.scope.launch { runCatching { player.startRadio(Player.MY_WAVE, t("Моя волна", "My Wave")) }.onFailure { nav.toast(it.message ?: "") } }
        }
        HotAction.HIDE -> window.hide()
        HotAction.QUIT -> window.quit()
    }
    return true
}

/**
 * One action on the keys page: its keys (× takes one off), «+» to press a new one (a key another
 * action had moves here, and that's said), «↺» back to the default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HotkeyRow(a: HotAction) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val settings by graph.settings.state.collectAsState()
    val all = Hotkeys.effective(settings.hotkeys)
    val keys = all.getValue(a)
    var capturing by remember { mutableStateOf(false) }
    // Focus arrives a frame after the field appears: only losing it after having had it cancels.
    var hadFocus by remember { mutableStateOf(false) }
    fun save(changes: Map<HotAction, List<String>>) = graph.settings.update { s ->
        s.copy(hotkeys = s.hotkeys + changes.map { (k, v) -> k.name to v })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a.title(), Modifier.width(280.dp), fontSize = 14.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { k ->
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(pal.paper3).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Hotkeys.label(k, ru), fontSize = 13.sp)
                    Text("×", color = pal.faint, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).clickable { save(mapOf(a to keys - k)) }.padding(horizontal = 4.dp))
                }
            }
            if (capturing) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Text(
                    t("Нажми сочетание… (Esc — отмена)", "Press the keys… (Esc cancels)"), color = accent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp)
                        .focusRequester(focus).onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) capturing = false }.focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                            val stroke = strokeOf(e) ?: return@onPreviewKeyEvent true
                            capturing = false
                            if (stroke == "Escape") return@onPreviewKeyEvent true
                            val taken = all.filter { (other, ks) -> other != a && stroke in ks }.keys
                            save(taken.associateWith { all.getValue(it) - stroke } + (a to (keys - stroke + stroke)))
                            if (taken.isNotEmpty()) nav.toast(t("${Hotkeys.label(stroke, true)} снято с «${taken.joinToString { it.title() }}»", "${Hotkeys.label(stroke, false)} taken from «${taken.joinToString { it.title() }}»"))
                            true
                        },
                )
            } else {
                Text("+", color = accent, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { hadFocus = false; capturing = true }.padding(horizontal = 10.dp, vertical = 1.dp))
            }
        }
        if (a.name in settings.hotkeys) TextButton(onClick = { graph.settings.update { it.copy(hotkeys = it.hotkeys - a.name) } }) { Text(t("Как было", "Default"), color = pal.muted, fontSize = 12.sp) }
    }
}

/** The top of the keys page: what the defaults are, and «reset all». */
@Composable
fun HotkeysIntro() {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            t("Клавиши как в Яндекс Музыке: K — пауза, J/L — перемотка, N/P — треки, F/D — нравится/нет, R — повтор, S — перемешать, M — звук, W — большой плеер. Работают, когда курсор не в поле ввода; в русской раскладке тоже.",
                "Keys as in Yandex Music: K pause, J/L seek, N/P tracks, F/D like/dislike, R repeat, S shuffle, M mute, W big player. They work when you’re not typing in a field, in any layout."),
            color = pal.muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { graph.settings.update { it.copy(hotkeys = emptyMap()) } }) { Text(t("Всё как было", "Reset all")) }
    }
}
