package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider
import dev.xvin.palimpsest.core.covers.OpenCatalogs
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.FlacLookup
import dev.xvin.palimpsest.desktop.data.FileOps
import dev.xvin.palimpsest.desktop.data.FlacUpgrade
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Tags
import dev.xvin.palimpsest.desktop.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A window opened from a track's menu. */
sealed interface TrackDialog {
    data class EditTags(val tracks: List<Track>) : TrackDialog
    data class Move(val tracks: List<Track>) : TrackDialog
    data class Delete(val tracks: List<Track>) : TrackDialog
    data class Versions(val track: Track) : TrackDialog
    data class Video(val track: Track) : TrackDialog
}

private fun Track.taggable() = path?.let { Tags.canEdit(File(it)) } == true

/** «FLAC · 1 411 kbps · 32,4 MB»: what the file is. */
fun techLine(t: Track): String = listOfNotNull(
    t.codec?.uppercase() ?: t.path?.substringAfterLast('.', "")?.uppercase()?.ifEmpty { null },
    t.bitrateKbps?.let { "$it kbps" },
    t.sizeBytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
    if (t.path == null) t.service.displayName else null,
).joinToString(" · ")

/**
 * The menu of one track or of several selected ones, at the mouse pointer: everything that can be
 * done with them, from the queue to the files themselves.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackMenu(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val liked by graph.library.liked.collectAsState()
    val playlists by graph.playlists.lists.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    val one = tracks.singleOrNull()
    val local = tracks.filter { it.path != null }
    val toDownload = tracks.filter { it.path == null && it.remoteId != null }
    val versions = remember(one) { one?.let(FileOps::versions).orEmpty() }
    fun act(block: () -> Unit) { onDismiss(); block() }
    Popup(
        popupPositionProvider = rememberCursorPositionProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false },
    ) {
        // Opens out of the pointer: a quick fade and grow from its corner.
        val appear = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        androidx.compose.animation.AnimatedVisibility(
            appear,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.EaseOutCubic), initialScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
        ) {
        Surface(
            shape = RoundedCornerShape(Radius.lg), color = pal.paper2, shadowElevation = 18.dp, border = BorderStroke(1.dp, pal.line),
            modifier = Modifier.width(300.dp),
        ) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(vertical = 5.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (one != null) TrackArt(one, Modifier.size(40.dp), Radius.sm)
                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(pal.paper3), contentAlignment = Alignment.Center) { Text("${tracks.size}", style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(one?.title ?: tracksCount(tracks.size), style = Type.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(one?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") } ?: fmtTotal(tracks.sumOf { it.durationMs }), color = pal.muted, style = Type.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (one != null) Text(techLine(one), color = pal.faint, style = Type.meta.copy(fontSize = 11.sp), maxLines = 1)
                    }
                }
                Divider()
                if (pickPlaylist) {
                    Item(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back")) { pickPlaylist = false }
                    playlists.forEach { p ->
                        Item(Icons.AutoMirrored.Rounded.QueueMusic, p.name) { act { graph.playlists.add(p.id, tracks.map { it.id }); nav.toast(t("Добавлено в «${p.name}»", "Added to «${p.name}»")) } }
                    }
                    Item(Icons.Rounded.Add, t("Новый плейлист", "New playlist")) {
                        act {
                            val p = graph.playlists.create(one?.title ?: (t("Плейлист ", "Playlist ") + (playlists.size + 1)))
                            graph.playlists.add(p.id, tracks.map { it.id })
                            nav.go(Screen.Playlist(p.id))
                        }
                    }
                    return@Column
                }
                Item(Icons.Rounded.PlayArrow, t("Слушать", "Play")) { act { graph.player.play(tracks) } }
                Item(Icons.Rounded.SkipNext, t("Играть следующим", "Play next")) { act { graph.player.playNext(tracks); nav.toast(t("Сыграет следующим", "Plays next")) } }
                Item(Icons.AutoMirrored.Rounded.QueueMusic, t("В очередь", "Add to queue")) { act { graph.player.enqueue(tracks); nav.toast(t("В очереди", "Queued")) } }
                val allLiked = tracks.all { it.id in liked }
                Item(if (allLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (allLiked) t("Убрать из любимых", "Unlike") else t("В любимые", "Like")) {
                    act { tracks.forEach { tr -> graph.library.setLiked(tr.id, !allLiked); likeRemote(graph, tr, !allLiked) } }
                }
                Item(Icons.AutoMirrored.Rounded.PlaylistAdd, t("В плейлист…", "Add to playlist…")) { pickPlaylist = true }
                if (toDownload.isNotEmpty()) Item(Icons.Rounded.Download, if (one != null) t("Скачать", "Download") else t("Скачать (${toDownload.size})", "Download (${toDownload.size})")) {
                    act { graph.downloads.enqueue(toDownload.map { it.id }); nav.toast(t("Скачиваю: ${toDownload.size}", "Downloading: ${toDownload.size}")) }
                }
                if (local.isNotEmpty() || one != null) Divider()
                if (local.any { it.taggable() }) Item(Icons.Rounded.Edit, t("Изменить теги…", "Edit tags…")) { act { nav.dialog = TrackDialog.EditTags(local.filter { it.taggable() }) } }
                if (one != null && one.path != null && FlacUpgrade.isLossy(one) && graph.yandex.isReady) Item(Icons.Rounded.GraphicEq, t("Найти во FLAC", "Find in FLAC")) { act { findFlac(graph, nav, one) } }
                if (versions.isNotEmpty()) Item(Icons.Rounded.History, t("Версии (${versions.size + 1})", "Versions (${versions.size + 1})")) { act { nav.dialog = TrackDialog.Versions(one!!) } }
                if (one != null && (one.path != null || one.remoteId != null)) Item(Icons.Rounded.Movie, t("Видео из трека…", "Video from the track…")) { act { nav.dialog = TrackDialog.Video(one) } }
                if (local.isNotEmpty()) Item(Icons.Rounded.DriveFileMove, t("Переместить в папку…", "Move to folder…")) { act { nav.dialog = TrackDialog.Move(local) } }
                if (one?.path != null) Item(Icons.Rounded.FolderOpen, t("Показать в папке", "Show in folder")) { act { FileOps.showInFolder(File(one.path)) } }
                one?.let(FileOps::webLink)?.let { link -> Item(Icons.Rounded.Link, t("Скопировать ссылку", "Copy link")) { act { FileOps.copyToClipboard(link); nav.toast(t("Ссылка скопирована", "Link copied")) } } }
                if (one != null) Item(Icons.Rounded.ContentCopy, t("Скопировать «исполнитель — название»", "Copy «artist — title»")) { act { FileOps.copyToClipboard("${one.artist} — ${one.title}") } }
                if (one != null) {
                    Divider()
                    one.artists.forEach { a -> Item(Icons.Rounded.Person, a) { act { nav.go(Screen.Artist(a)) } } }
                    one.album?.let { a ->
                        if (one.path != null) Item(Icons.Rounded.Album, a) { act { nav.go(Screen.LocalAlbum(a, one.albumArtist ?: one.artist)) } }
                        else one.remoteAlbumId?.let { id -> Item(Icons.Rounded.Album, a) { act { nav.go(Screen.RemoteAlbum(id, one.service)) } } }
                    }
                    if (one.path != null && one.service == Service.YANDEX) one.remoteAlbumId?.let { id ->
                        Item(Icons.Rounded.Album, t("Альбом на Яндексе", "Album on Yandex")) { act { nav.go(Screen.RemoteAlbum(id, Service.YANDEX)) } }
                    }
                    if (one.service == Service.YANDEX && one.remoteId != null && graph.yandex.isReady) {
                        Item(Icons.Rounded.Radio, t("Радио по треку", "Track radio")) {
                            act { graph.scope.launch { runCatching { graph.player.startRadio("track:${one.remoteId}", t("Радио: ", "Radio: ") + one.title) }.onFailure { nav.toast(it.message ?: "") } } }
                        }
                        Item(Icons.Rounded.AutoAwesome, t("Похожие треки", "Similar tracks")) { act { nav.go(Screen.Similar(one.remoteId, one.title)) } }
                    }
                }
                if (local.isNotEmpty()) {
                    Divider()
                    Item(Icons.Rounded.Delete, if (one != null) t("Удалить файл…", "Delete file…") else t("Удалить файлы (${local.size})…", "Delete files (${local.size})…"), danger = true) {
                        act { nav.dialog = TrackDialog.Delete(local) }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Item(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    val color = if (danger) MaterialTheme.colorScheme.error else pal.text
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp).height(32.dp).clip(RoundedCornerShape(Radius.sm))
            .background(if (hovered) (if (danger) color.copy(alpha = 0.12f) else pal.text.copy(alpha = 0.07f)) else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = if (danger) color else if (hovered) pal.text else pal.muted)
        Spacer(Modifier.width(11.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LocalPal.current.line.copy(alpha = 0.7f))

private fun findFlac(graph: Graph, nav: Nav, tr: Track) {
    nav.toast(t("Ищу «${tr.title}» во FLAC…", "Looking for «${tr.title}» in FLAC…"))
    graph.scope.launch {
        val (m, err) = runCatching { graph.flacUpgrade.findOne(tr) }.getOrElse { null to (it.message ?: it.toString()) }
        withContext(Dispatchers.Main) {
            when {
                err == "plus" -> nav.toast(t("Без Яндекс Плюса FLAC не отдают", "No FLAC without Yandex Plus"))
                err == "yandex" -> nav.toast(t("Нужно войти в Яндекс Музыку (Сервисы)", "Sign in to Yandex Music first (Services)"))
                err != null -> nav.toast(t("Не вышло: ", "Didn’t work: ") + err)
                m is FlacLookup.Match.Flac -> { nav.toast(t("FLAC скачан — послушай и сравни", "FLAC downloaded — listen and compare")); nav.go(Screen.Flac) }
                m == FlacLookup.Match.LossyOnly -> nav.toast(t("На Яндексе эта песня есть только в MP3/AAC", "Yandex has this song only in MP3/AAC"))
                else -> nav.toast(t("На Яндексе такой песни не нашлось", "Yandex doesn’t have this song"))
            }
        }
    }
}

/** The track menu's windows: tags, moving, deleting, versions. */
@Composable
fun TrackDialogs() {
    val nav = LocalNav.current
    val close = { nav.dialog = null }
    when (val d = nav.dialog) {
        is TrackDialog.EditTags -> TagEditor(d.tracks, close)
        is TrackDialog.Move -> MoveDialog(d.tracks, close)
        is TrackDialog.Delete -> DeleteDialog(d.tracks, close)
        is TrackDialog.Versions -> VersionsDialog(d.track, close)
        is TrackDialog.Video -> VideoDialog(d.track, close)
        null -> Unit
    }
}

/** After files changed under tracks: the library, playlists, the queue and covers follow. */
private suspend fun afterChange(graph: Graph, changed: Map<Track, Track>) = withContext(Dispatchers.Main) {
    changed.forEach { (old, new) -> graph.covers.forget(old.id); graph.covers.forget(new.id); graph.playlists.replaceId(old.id, new.id) }
    graph.player.replaced(changed.mapKeys { it.key.id })
}

// --- Tags --------------------------------------------------------------------------------------------

/**
 * Tags of one file, or the shared ones of several (only what's changed is written, so differing
 * titles or years stay as they are). The cover from a picture on disk or found on the internet.
 */
@Composable
private fun TagEditor(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val one = tracks.singleOrNull()
    val fields = if (one != null) Tags.Field.entries else listOf(Tags.Field.ARTIST, Tags.Field.ALBUM, Tags.Field.ALBUM_ARTIST, Tags.Field.YEAR, Tags.Field.GENRE, Tags.Field.DISC)
    // What the files say now: a value they share, or null when they differ.
    val initial by produceState<Map<Tags.Field, String?>?>(null, tracks) {
        value = withContext(Dispatchers.IO) {
            val read = tracks.map { Tags.read(File(it.path!!)).first }
            fields.associateWith { f -> read.map { it[f].orEmpty() }.distinct().singleOrNull() }
        }
    }
    val oldCover by produceState<Any?>(null, one) { value = one?.let { graph.covers.model(it) } }
    val values = remember { mutableStateMapOf<Tags.Field, String>() }
    LaunchedEffect(initial) { initial?.forEach { (k, v) -> values[k] = v.orEmpty() } }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var findCovers by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun label(f: Tags.Field) = when (f) {
        Tags.Field.TITLE -> t("Название", "Title")
        Tags.Field.ARTIST -> t("Исполнитель", "Artist")
        Tags.Field.ALBUM -> t("Альбом", "Album")
        Tags.Field.ALBUM_ARTIST -> t("Исполнитель альбома", "Album artist")
        Tags.Field.YEAR -> t("Год", "Year")
        Tags.Field.TRACK -> t("Номер", "Track no.")
        Tags.Field.DISC -> t("Диск", "Disc")
        Tags.Field.GENRE -> t("Жанр", "Genre")
        Tags.Field.LYRICS -> t("Текст песни (можно LRC)", "Lyrics (LRC works too)")
    }
    fun save() {
        val init = initial ?: return
        val changes = fields.mapNotNull { f -> values[f]?.takeIf { it != init[f].orEmpty() && !(init[f] == null && it.isBlank()) }?.let { f to it } }.toMap()
        if (changes.isEmpty() && cover == null) { onDismiss(); return }
        saving = true
        scope.launch {
            var failed = 0
            withContext(Dispatchers.IO) {
                tracks.forEach { tr -> runCatching { Tags.update(File(tr.path!!), changes, cover) }.onFailure { failed++; System.err.println("tags ${tr.path}: $it") } }
            }
            val fresh = graph.library.reread(tracks)
            afterChange(graph, tracks.zip(tracks.map { o -> fresh.firstOrNull { it.id == o.id } ?: o }).toMap())
            saving = false
            onDismiss()
            nav.toast(if (failed == 0) t("Теги записаны", "Tags saved") else t("Не удалось записать $failed из ${tracks.size} (файл занят или только для чтения)", "Couldn’t write $failed of ${tracks.size} (file busy or read-only)"))
        }
    }
    PDialog(
        one?.let { t("Теги", "Tags") } ?: t("Теги: ", "Tags: ") + count(tracks.size, "файл", "файла", "файлов", "file", "files"), onDismiss, width = 640.dp,
        subtitle = one?.let { File(it.path!!).name } ?: t("Где у файлов разное, поле пустое и не тронется; что впишешь — запишется во все файлы.", "Where the files differ, the field is empty and stays as it is; what you type goes into every file."),
        dismissible = !saving,
        buttons = {
            PButton(t("Отмена", "Cancel"), onDismiss, kind = Kind.GHOST, enabled = !saving)
            PButton(if (saving) t("Записываю…", "Saving…") else t("Сохранить", "Save"), ::save, kind = Kind.PRIMARY, enabled = initial != null && !saving)
        },
    ) {
        val init = initial
        if (init == null) { Spinner(Modifier.padding(24.dp)); return@PDialog }
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(cover ?: if (one != null) oldCover else null, one?.album ?: one?.title ?: "${tracks.size}", Modifier.size(96.dp), Radius.md)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(t("Обложка из файла…", "Cover from a file…"), {
                        val chooser = javax.swing.JFileChooser().apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter(t("Картинки", "Pictures"), "jpg", "jpeg", "png") }
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) cover = runCatching { chooser.selectedFile.readBytes() }.getOrNull()
                    }, icon = Icons.Rounded.FolderOpen)
                    PButton(t("Найти обложку", "Find a cover"), { findCovers = !findCovers }, icon = Icons.Rounded.ImageSearch)
                }
            }
            if (findCovers) CoverSearch(
                "${values[Tags.Field.ARTIST].orEmpty()} ${values[Tags.Field.TITLE] ?: values[Tags.Field.ALBUM].orEmpty()}".trim(),
            ) { bytes -> cover = bytes; findCovers = false }
            // Short fields two to a row; the title and the lyrics take the whole width.
            val wide = setOf(Tags.Field.TITLE, Tags.Field.LYRICS)
            @Composable
            fun Field(f: Tags.Field, modifier: Modifier) {
                val differ = init[f] == null
                PInput(
                    values[f].orEmpty(), { values[f] = it }, if (differ) t("разные — не трогаю", "differ — left as is") else "", modifier,
                    label = label(f), lines = if (f == Tags.Field.LYRICS) 6 else 1,
                )
            }
            fields.filter { it == Tags.Field.TITLE }.forEach { Field(it, Modifier.fillMaxWidth()) }
            fields.filter { it !in wide }.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { Field(it, Modifier.weight(1f)) }; if (pair.size == 1) Spacer(Modifier.weight(1f)) }
            }
            fields.filter { it == Tags.Field.LYRICS }.forEach { Field(it, Modifier.fillMaxWidth()) }
        }
    }
}

/** Covers for [query] from Yandex Music, Deezer and iTunes; a click picks one. */
@Composable
private fun CoverSearch(query: String, onPick: (ByteArray) -> Unit) {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val found by produceState<List<String>?>(null, query) {
        value = withContext(Dispatchers.IO) {
            val yandex = if (graph.yandex.isReady) runCatching { graph.yandex.search(query, 0).mapNotNull { it.coverUrl } }.getOrDefault(emptyList()) else emptyList()
            val open = runCatching { OpenCatalogs(graph.http).search(query).mapNotNull { it.coverUrl } }.getOrDefault(emptyList())
            (yandex.take(6) + open.take(10)).distinct()
        }
    }
    val list = found
    when {
        list == null -> Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); Spacer(Modifier.width(10.dp)); Text(t("Ищу «$query»…", "Looking for «$query»…"), style = MaterialTheme.typography.bodyMedium, color = pal.muted) }
        list.isEmpty() -> Text(t("Ничего не нашлось", "Nothing found"), style = MaterialTheme.typography.bodyMedium, color = pal.muted)
        else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { url ->
                Artwork(url, query, Modifier.size(88.dp).clip(RoundedCornerShape(Radius.sm)).clickable { scope.launch { withContext(Dispatchers.IO) { graph.covers.bytes(url) }?.let(onPick) } }, Radius.sm)
            }
        }
    }
}

// --- Moving ------------------------------------------------------------------------------------------

/** «Move to folder»: a folder of the library (filter to find it) or any other. */
@Composable
private fun MoveDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val all by graph.library.tracks.collectAsState()
    val roots = graph.settings.value.musicRoots.map { File(it).absolutePath }
    val folders = remember(all) {
        (roots + all.mapNotNull { it.path?.let(::File)?.parent }).distinct().sortedBy { it.lowercase() }
    }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun short(p: String) = roots.firstOrNull { p == it || p.startsWith(it + File.separator) }?.let { r -> File(r).name + p.removePrefix(r) } ?: p
    fun moveTo(dir: File) {
        busy = true
        scope.launch {
            val moved = LinkedHashMap<Track, Track>()
            var failed = 0
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                tracks.forEach { tr ->
                    val f = File(tr.path!!)
                    if (f.parentFile == dir) return@forEach
                    val target = FileOps.freeName(dir, f.name)
                    if (!FileOps.move(f, target)) { failed++; return@forEach }
                    FileOps.companions(f).forEach { c -> FileOps.move(c, FileOps.freeName(dir, target.nameWithoutExtension + "." + c.extension)) }
                    moved[tr] = graph.library.relocate(tr, target)
                }
            }
            afterChange(graph, moved)
            busy = false
            onDismiss()
            val outside = roots.none { dir.absolutePath == it || dir.absolutePath.startsWith(it + File.separator) }
            nav.toast(
                buildString {
                    append(t("Перемещено: ${moved.size}", "Moved: ${moved.size}"))
                    if (failed > 0) append(t(", не вышло: $failed", ", failed: $failed"))
                    if (outside) append(t(" — эта папка вне медиатеки, после обновления треки из неё пропадут", " — this folder is outside the library, the tracks leave it on the next refresh"))
                },
            )
        }
    }
    PDialog(
        if (tracks.size == 1) t("Переместить «${tracks[0].title}»", "Move «${tracks[0].title}»") else t("Переместить: ", "Move ") + count(tracks.size, "файл", "файла", "файлов", "file", "files"),
        onDismiss, width = 600.dp, dismissible = !busy,
        buttons = {
            if (busy) { Spinner(size = 16.dp); Text(t("Перемещаю…", "Moving…"), style = MaterialTheme.typography.bodyMedium, color = pal.muted); Spacer(Modifier.weight(1f)) }
            PButton(t("Другая папка…", "Other folder…"), {
                val chooser = javax.swing.JFileChooser(roots.firstOrNull()).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) moveTo(chooser.selectedFile)
            }, icon = Icons.Rounded.FolderOpen, enabled = !busy)
            PButton(t("Отмена", "Cancel"), onDismiss, kind = Kind.GHOST, enabled = !busy)
        },
    ) {
        PField(filter, { filter = it }, t("Найти папку", "Find a folder"), Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            folders.filter { p -> words.all { it in p.lowercase() } }.take(300).forEach { p ->
                val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(Radius.sm)).background(if (hovered) pal.text.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .hoverable(hover).clickable(enabled = !busy) { moveTo(File(p)) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(17.dp), tint = pal.muted)
                    Spacer(Modifier.width(10.dp))
                    Text(short(p), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// --- Deleting ----------------------------------------------------------------------------------------

@Composable
private fun DeleteDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val streams = tracks.count { it.remoteId != null }
    fun delete() {
        onDismiss()
        graph.scope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                tracks.forEach { tr ->
                    val f = File(tr.path!!)
                    if (!FileOps.trash(f)) return@forEach
                    ok++
                    FileOps.companions(f).forEach(FileOps::trash)
                    if (tr.remoteId != null) graph.library.upsert(tr.copy(path = null, folder = null, sizeBytes = 0)) else graph.library.remove(tr.id)
                }
            }
            withContext(Dispatchers.Main) {
                nav.toast(
                    if (ok == tracks.size) (if (ok == 1) t("«${tracks[0].title}» — в корзине", "«${tracks[0].title}» moved to the trash") else t("В корзине: $ok", "Moved to the trash: $ok"))
                    else t("Удалено $ok из ${tracks.size} — остальные не дались", "Deleted $ok of ${tracks.size} — the rest couldn’t be"),
                )
            }
        }
    }
    PDialog(
        if (tracks.size == 1) t("Удалить файл?", "Delete the file?") else t("Удалить ", "Delete ") + count(tracks.size, "файл", "файла", "файлов", "file", "files") + "?",
        onDismiss, width = 460.dp,
        buttons = {
            PButton(t("Отмена", "Cancel"), onDismiss, kind = Kind.GHOST)
            PButton(t("Удалить", "Delete"), ::delete, icon = Icons.Rounded.Delete, kind = Kind.DANGER)
        },
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(pal.paper3).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.take(6).forEach { Text(File(it.path!!).name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            if (tracks.size > 6) Text(t("…и ещё ${tracks.size - 6}", "…and ${tracks.size - 6} more"), color = pal.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(t("Файлы уйдут в корзину системы — оттуда их можно вернуть.", "The files go to the system trash, where they can be restored from."), color = pal.muted, style = MaterialTheme.typography.bodyMedium)
        if (streams > 0) Text(t("Треки с сервисов останутся в медиатеке — их можно будет слушать онлайн.", "Tracks from services stay in the library to stream."), color = pal.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

// --- Versions ----------------------------------------------------------------------------------------

/**
 * The file now and the earlier ones kept in `.palimpsest/replaced` (by «Upgrade to FLAC» or a
 * restore): listen to any, bring one back (the current file is kept in its place), or throw it out.
 */
@Composable
private fun VersionsDialog(track: Track, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val current = graph.library.get(track.id) ?: track
    val old = remember(tick, current.path) { FileOps.versions(current) }
    fun preview(f: File, label: String) = graph.player.playAlone(current.copy(id = PREVIEW + f.absolutePath, path = f.absolutePath, title = "${current.title} ($label)"), 0)
    fun line(f: File) = listOf(
        f.extension.uppercase(),
        "%.1f MB".format(f.length() / 1_048_576.0),
        java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(java.util.Date(f.lastModified())),
    ).joinToString(" · ")
    PDialog(
        t("Версии «${current.title}»", "Versions of «${current.title}»"), onDismiss, width = 620.dp,
        subtitle = t("Прежние файлы лежат в папке .palimpsest/replaced рядом с треком.", "Earlier files live in .palimpsest/replaced next to the track."),
        buttons = { PButton(t("Готово", "Done"), onDismiss, kind = Kind.PRIMARY) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            current.path?.let { p ->
                Version(File(p).name, t("Сейчас · ", "Now · ") + techLine(current)) {
                    PButton(t("Слушать", "Play"), { graph.player.play(listOf(current)) }, icon = Icons.Rounded.PlayArrow, kind = Kind.GHOST)
                }
            }
            old.forEach { f ->
                Version(f.name, line(f)) {
                    PIconButton(Icons.Rounded.PlayArrow, t("Слушать", "Play"), { preview(f, t("старая версия", "earlier version")) }, iconSize = 18.dp)
                    PButton(t("Вернуть", "Restore"), {
                        scope.launch {
                            val restored = withContext(Dispatchers.IO) { FileOps.restore(current, f) }
                            if (restored == null) { nav.toast(t("Не получилось вернуть", "Couldn’t restore")); return@launch }
                            val moved = graph.library.relocate(current, restored)
                            val fresh = graph.library.reread(listOf(moved)).firstOrNull() ?: moved
                            afterChange(graph, mapOf(current to fresh))
                            tick++
                            nav.toast(t("Вернул «${f.name}», прежний файл сохранён", "Restored «${f.name}», the other file is kept"))
                        }
                    }, kind = Kind.GHOST)
                    PIconButton(Icons.Rounded.Delete, t("В корзину", "Trash"), { if (FileOps.trash(f)) tick++ else nav.toast(t("Не удалось удалить", "Couldn’t delete")) }, iconSize = 18.dp, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Ids of files played only to listen to them (versions): not tracks of the library, not logged. */
const val PREVIEW = "preview:"

@Composable
private fun Version(name: String, info: String, actions: @Composable () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(pal.paper3).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.rowTitle)
            Text(info, color = pal.muted, style = Type.meta, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() }
    }
}

// --- Video ------------------------------------------------------------------------------------------

/** «Видео из трека»: cover or synced lyrics, 9:16 (Reels, Shorts) or 16:9 (YouTube), quality, visualiser. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VideoDialog(track: Track, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val saved = remember { graph.settings.value.renderChoice }
    fun <E : Enum<E>> pick(i: Int, all: Array<E>, default: E) = saved.getOrNull(i)?.let { n -> all.firstOrNull { it.name == n } } ?: default
    var mode by remember { mutableStateOf(pick(0, dev.xvin.palimpsest.desktop.render.RenderMode.entries.toTypedArray(), dev.xvin.palimpsest.desktop.render.RenderMode.COVER)) }
    var aspect by remember { mutableStateOf(pick(1, dev.xvin.palimpsest.desktop.render.RenderAspect.entries.toTypedArray(), dev.xvin.palimpsest.desktop.render.RenderAspect.VERTICAL)) }
    var quality by remember { mutableStateOf(pick(2, dev.xvin.palimpsest.desktop.render.RenderQuality.entries.toTypedArray(), dev.xvin.palimpsest.desktop.render.RenderQuality.HIGH)) }
    var viz by remember { mutableStateOf(pick(3, dev.xvin.palimpsest.desktop.render.RenderViz.entries.toTypedArray(), dev.xvin.palimpsest.desktop.render.RenderViz.BARS)) }
    val hasLyrics by produceState<Boolean?>(null, track.id) { value = graph.videos.syncedLyrics(track) != null }
    val busy by graph.videos.job.collectAsState()
    val effective = if (mode == dev.xvin.palimpsest.desktop.render.RenderMode.LYRICS && hasLyrics == false) dev.xvin.palimpsest.desktop.render.RenderMode.COVER else mode

    @Composable
    fun Group(title: String, content: @Composable () -> Unit) {
        Spacer(Modifier.height(16.dp))
        Caption(title)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
    fun make() {
        graph.settings.update { it.copy(renderChoice = listOf(mode.name, aspect.name, quality.name, viz.name)) }
        graph.videos.start(track, effective, aspect, quality, viz) { r ->
            r.onSuccess { f -> nav.toast(t("Видео готово: ${f.name}", "The video is ready: ${f.name}"), t("Показать", "Show")) { FileOps.showInFolder(f) } }
                .onFailure { nav.toast(t("Видео не получилось: ", "The video failed: ") + (it.message ?: it.javaClass.simpleName)) }
        }
        nav.toast(t("Делаю видео — прогресс слева внизу", "Making the video — progress at the bottom left"))
        onDismiss()
    }
    PDialog(
        t("Видео из трека", "Video from the track"), onDismiss, width = 600.dp,
        subtitle = t("Трек целиком, звук прямо из файла через твой эквалайзер. Спектр — как в плеере.", "The whole track, the sound straight from the file through your equalizer. The spectrum moves as in the player."),
        buttons = {
            PButton(t("Отмена", "Cancel"), onDismiss, kind = Kind.GHOST)
            PButton(t("Сделать видео", "Make the video"), ::make, icon = Icons.Rounded.Movie, kind = Kind.PRIMARY, enabled = busy == null && hasLyrics != null)
        },
    ) {
        Column {
                // Where it will be saved, in plain sight, with a way to change it.
                val settings by graph.settings.state.collectAsState()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(pal.paper3).padding(start = 12.dp, end = 4.dp).height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(17.dp), tint = pal.muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Сохранится в", "Saved to"), style = Type.meta, color = pal.faint)
                        Text((settings.videoDir?.let(::File) ?: dev.xvin.palimpsest.desktop.render.VideoRenders.defaultFolder).absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PButton(t("Изменить…", "Change…"), { pickVideoFolder(graph) }, kind = Kind.GHOST)
                    PIconButton(Icons.Rounded.FolderOpen, t("Открыть папку", "Open the folder"), { graph.videos.folder.apply { mkdirs() }.let(FileOps::showInFolder) }, iconSize = 18.dp)
                }
                Group(t("Что в кадре", "What's in it")) {
                    Pill(t("Обложка", "Cover"), effective == dev.xvin.palimpsest.desktop.render.RenderMode.COVER, { mode = dev.xvin.palimpsest.desktop.render.RenderMode.COVER })
                    if (hasLyrics == true) Pill(t("Текст", "Lyrics"), effective == dev.xvin.palimpsest.desktop.render.RenderMode.LYRICS, { mode = dev.xvin.palimpsest.desktop.render.RenderMode.LYRICS })
                }
                when (hasLyrics) {
                    null -> Text(t("Ищу синхронный текст…", "Looking for synced lyrics…"), color = pal.faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    false -> Text(t("Синхронного текста нет — в видео будет обложка", "No synced lyrics — the video shows the cover"), color = pal.faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    else -> Unit
                }
                Group(t("Формат", "Format")) {
                    Pill(t("Вертикальное 9:16", "Vertical 9:16"), aspect == dev.xvin.palimpsest.desktop.render.RenderAspect.VERTICAL, { aspect = dev.xvin.palimpsest.desktop.render.RenderAspect.VERTICAL })
                    Pill(t("Горизонтальное 16:9", "Horizontal 16:9"), aspect == dev.xvin.palimpsest.desktop.render.RenderAspect.HORIZONTAL, { aspect = dev.xvin.palimpsest.desktop.render.RenderAspect.HORIZONTAL })
                }
                Group(t("Качество", "Quality")) {
                    Pill(t("Обычное · 1080p · 30 к/с", "Standard · 1080p · 30 fps"), quality == dev.xvin.palimpsest.desktop.render.RenderQuality.STANDARD, { quality = dev.xvin.palimpsest.desktop.render.RenderQuality.STANDARD })
                    Pill(t("Высокое · 1080p · 60 к/с", "High · 1080p · 60 fps"), quality == dev.xvin.palimpsest.desktop.render.RenderQuality.HIGH, { quality = dev.xvin.palimpsest.desktop.render.RenderQuality.HIGH })
                    Pill(t("Максимум · 1440p · 60 к/с", "Max · 1440p · 60 fps"), quality == dev.xvin.palimpsest.desktop.render.RenderQuality.MAX, { quality = dev.xvin.palimpsest.desktop.render.RenderQuality.MAX })
                }
                Group(t("Спектр", "Spectrum")) {
                    Pill(t("Полосы", "Bars"), viz == dev.xvin.palimpsest.desktop.render.RenderViz.BARS, { viz = dev.xvin.palimpsest.desktop.render.RenderViz.BARS })
                    Pill(t("Зеркало", "Mirror"), viz == dev.xvin.palimpsest.desktop.render.RenderViz.MIRROR, { viz = dev.xvin.palimpsest.desktop.render.RenderViz.MIRROR })
                    Pill(t("Кольцо вокруг обложки", "Ring around the cover"), viz == dev.xvin.palimpsest.desktop.render.RenderViz.RING, { viz = dev.xvin.palimpsest.desktop.render.RenderViz.RING })
                }
                if (busy != null) Text(t("Сейчас уже делается видео «${busy!!.title}» — дождись его.", "A video of «${busy!!.title}» is being made — wait for it."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** Picks the folder for videos (Settings and the video window share it). */
fun pickVideoFolder(graph: Graph) {
    val chooser = javax.swing.JFileChooser(graph.videos.folder.takeIf { it.isDirectory } ?: dev.xvin.palimpsest.desktop.render.VideoRenders.defaultFolder.parentFile)
        .apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = t("Папка для видео", "Folder for videos") }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) graph.settings.update { it.copy(videoDir = chooser.selectedFile.absolutePath) }
}
