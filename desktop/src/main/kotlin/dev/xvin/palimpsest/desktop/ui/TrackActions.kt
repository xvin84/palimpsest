package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            shape = RoundedCornerShape(12.dp), color = pal.paper2, shadowElevation = 16.dp, border = BorderStroke(1.dp, pal.line),
            modifier = Modifier.width(310.dp),
        ) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (one != null) TrackArt(one, Modifier.size(44.dp), 6.dp)
                    else Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(pal.paper3), contentAlignment = Alignment.Center) { Text("${tracks.size}", style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(one?.title ?: t("${tracks.size} треков", "${tracks.size} tracks"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(one?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") } ?: fmt(tracks.sumOf { it.durationMs }), color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (one != null) Text(techLine(one), color = pal.faint, fontSize = 11.sp, maxLines = 1)
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (danger) color else pal.muted)
        Spacer(Modifier.width(12.dp))
        Text(text, color = color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LocalPal.current.line)

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
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.widthIn(min = 560.dp, max = 720.dp),
        title = { Text(one?.let { t("Теги: ", "Tags: ") + File(it.path!!).name } ?: t("Теги ${tracks.size} файлов", "Tags of ${tracks.size} files"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            val init = initial
            if (init == null) { CircularProgressIndicator(Modifier.padding(24.dp)); return@AlertDialog }
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (one == null) Text(t("Где у файлов разное, поле пустое и не тронется; что впишешь — запишется во все файлы.", "Where the files differ, the field is empty and stays as it is; what you type goes into every file."), color = pal.muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(cover ?: if (one != null) oldCover else null, one?.album ?: one?.title ?: "${tracks.size}", Modifier.size(96.dp), 8.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val chooser = javax.swing.JFileChooser().apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter(t("Картинки", "Pictures"), "jpg", "jpeg", "png") }
                            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) cover = runCatching { chooser.selectedFile.readBytes() }.getOrNull()
                        }) { Text(t("Обложка из файла…", "Cover from a file…")) }
                        OutlinedButton(onClick = { findCovers = !findCovers }) { Text(t("Найти обложку", "Find a cover")) }
                    }
                }
                if (findCovers) CoverSearch(
                    "${values[Tags.Field.ARTIST].orEmpty()} ${values[Tags.Field.TITLE] ?: values[Tags.Field.ALBUM].orEmpty()}".trim(),
                ) { bytes -> cover = bytes; findCovers = false }
                fields.forEach { f ->
                    val differ = init[f] == null
                    OutlinedTextField(
                        values[f].orEmpty(), { values[f] = it }, Modifier.fillMaxWidth().let { if (f == Tags.Field.LYRICS) it.heightIn(min = 140.dp, max = 260.dp) else it },
                        label = { Text(if (differ) label(f) + t(" — разные, не трогаю", " — differ, left as is") else label(f)) }, singleLine = f != Tags.Field.LYRICS,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = initial != null && !saving, onClick = {
                val init = initial ?: return@TextButton
                val changes = fields.mapNotNull { f -> values[f]?.takeIf { it != init[f].orEmpty() && !(init[f] == null && it.isBlank()) }?.let { f to it } }.toMap()
                if (changes.isEmpty() && cover == null) { onDismiss(); return@TextButton }
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
            }) { Text(if (saving) t("Записываю…", "Saving…") else t("Сохранить", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(t("Отмена", "Cancel")) } },
    )
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
        list == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(t("Ищу «$query»…", "Looking for «$query»…"), color = pal.muted) }
        list.isEmpty() -> Text(t("Ничего не нашлось", "Nothing found"), color = pal.muted)
        else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { url ->
                Artwork(url, query, Modifier.size(88.dp).clickable { scope.launch { withContext(Dispatchers.IO) { graph.covers.bytes(url) }?.let(onPick) } }, 6.dp)
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
        title = { Text(if (tracks.size == 1) t("Переместить «${tracks[0].title}»", "Move «${tracks[0].title}»") else t("Переместить ${tracks.size} файлов", "Move ${tracks.size} files")) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(t("Найти папку", "Find a folder")) })
                Spacer(Modifier.height(8.dp))
                val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    folders.filter { p -> words.all { it in p.lowercase() } }.take(300).forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy) { moveTo(File(p)) }.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp), tint = LocalAccent.current)
                            Spacer(Modifier.width(10.dp))
                            Text(short(p), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                    }
                }
                if (busy) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("Перемещаю…", "Moving…"), color = pal.muted) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val chooser = javax.swing.JFileChooser(roots.firstOrNull()).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) moveTo(chooser.selectedFile)
            }) { Text(t("Другая папка…", "Other folder…")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(t("Отмена", "Cancel")) } },
    )
}

// --- Deleting ----------------------------------------------------------------------------------------

@Composable
private fun DeleteDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val streams = tracks.count { it.remoteId != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size == 1) t("Удалить файл?", "Delete the file?") else t("Удалить ${tracks.size} файлов?", "Delete ${tracks.size} files?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tracks.take(6).forEach { Text(File(it.path!!).name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
                if (tracks.size > 6) Text(t("…и ещё ${tracks.size - 6}", "…and ${tracks.size - 6} more"), color = pal.muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(t("Файлы уйдут в корзину системы — оттуда их можно вернуть.", "The files go to the system trash, where they can be restored from."), color = pal.muted)
                if (streams > 0) Text(t("Треки с сервисов останутся в медиатеке — их можно будет слушать онлайн.", "Tracks from services stay in the library to stream."), color = pal.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
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
            }) { Text(t("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Отмена", "Cancel")) } },
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
        title = { Text(t("Версии «${current.title}»", "Versions of «${current.title}»")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                current.path?.let { p ->
                    Version(File(p).name, t("Сейчас · ", "Now · ") + techLine(current)) {
                        TextButton(onClick = { graph.player.play(listOf(current)) }) { Text(t("Слушать", "Play")) }
                    }
                }
                old.forEach { f ->
                    Version(f.name, line(f)) {
                        TextButton(onClick = { preview(f, t("старая версия", "earlier version")) }) { Text(t("Слушать", "Play")) }
                        TextButton(onClick = {
                            scope.launch {
                                val restored = withContext(Dispatchers.IO) { FileOps.restore(current, f) }
                                if (restored == null) { nav.toast(t("Не получилось вернуть", "Couldn’t restore")); return@launch }
                                val moved = graph.library.relocate(current, restored)
                                val fresh = graph.library.reread(listOf(moved)).firstOrNull() ?: moved
                                afterChange(graph, mapOf(current to fresh))
                                tick++
                                nav.toast(t("Вернул «${f.name}», прежний файл сохранён", "Restored «${f.name}», the other file is kept"))
                            }
                        }) { Text(t("Вернуть", "Restore")) }
                        TextButton(onClick = { if (FileOps.trash(f)) tick++ else nav.toast(t("Не удалось удалить", "Couldn’t delete")) }) { Text(t("В корзину", "Trash"), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Text(t("Прежние файлы лежат в папке .palimpsest/replaced рядом с треком.", "Earlier files live in .palimpsest/replaced next to the track."), color = pal.faint, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Готово", "Done")) } },
    )
}

/** Ids of files played only to listen to them (versions): not tracks of the library, not logged. */
const val PREVIEW = "preview:"

@Composable
private fun Version(name: String, info: String, actions: @Composable () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.paper3).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(info, color = pal.muted, fontSize = 12.sp, maxLines = 1)
        }
        actions()
    }
}
