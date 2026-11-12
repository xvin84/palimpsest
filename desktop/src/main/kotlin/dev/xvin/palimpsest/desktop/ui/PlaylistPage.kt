package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.core.library.PlaylistExport
import dev.xvin.palimpsest.core.library.PlaylistItem
import dev.xvin.palimpsest.desktop.data.FileOps
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.data.Playlist
import dev.xvin.palimpsest.desktop.data.Track
import java.io.File

/**
 * A playlist page: its cover (a picture you chose, or four album covers from it), the name edited in
 * place, and everything else in sight — listen through, share, ⋯ — over the songs, which drag into
 * a new order by the handle.
 */
@Composable
fun PlaylistScreen(id: Long) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val lists by graph.playlists.lists.collectAsState()
    val all by graph.library.tracks.collectAsState()
    val liked by graph.library.liked.collectAsState()
    val preview by graph.preview.state.collectAsState()
    val p = lists.firstOrNull { it.id == id } ?: return
    val byId = remember(all) { all.associateBy { it.id } }
    val tracks = p.trackIds.mapNotNull(byId::get)
    var name by remember(p.id) { mutableStateOf(p.name) }
    var menu by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun items() = tracks.map { PlaylistItem(it.title, it.artist, it.durationMs, it.path, FileOps.webLink(it)) }

    // One column: in the table's first row its parts would otherwise lie on top of each other.
    val header: @Composable () -> Unit = { Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistCover(p, tracks, Modifier.size(148.dp).clickable { pickCover(graph, p) }, corner = 12.dp)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(t("ПЛЕЙЛИСТ", "PLAYLIST"), style = Type.caption, color = pal.muted)
                // The name is edited in place, set in the page title's own type.
                androidx.compose.foundation.text.BasicTextField(
                    name, { name = it; graph.playlists.rename(p.id, it) }, Modifier.fillMaxWidth().ownsKeys(), singleLine = true,
                    textStyle = MaterialTheme.typography.displayMedium.copy(color = pal.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(pal.text),
                )
                Text(tracksCount(tracks.size) + " · " + fmtTotal(tracks.sumOf { it.durationMs }), style = Type.meta, color = pal.faint, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PButton(t("Пролистать", "Listen through"), { graph.preview.start(tracks, p.name) }, icon = Icons.Rounded.FastForward, enabled = tracks.size > 1)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        PButton(t("Поделиться", "Share"), { share = true }, icon = Icons.Rounded.Share, enabled = tracks.isNotEmpty())
                        DropdownMenu(share, { share = false }) {
                            MenuItem(Icons.Rounded.ContentCopy, t("Скопировать списком со ссылками", "Copy as a list with links")) {
                                share = false
                                FileOps.copyToClipboard(PlaylistExport.text(p.name, items()))
                                nav.toast(t("Список скопирован — вставь его в чат или заметку", "Copied — paste it into a chat or a note"))
                            }
                            MenuItem(Icons.Rounded.SaveAlt, t("Сохранить как .m3u8", "Save as .m3u8")) {
                                share = false
                                val chooser = javax.swing.JFileChooser().apply { selectedFile = File(PlaylistExport.fileName(p.name, "m3u8")) }
                                if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val ok = runCatching { chooser.selectedFile.writeText(PlaylistExport.m3u(p.name, items())) }.isSuccess
                                    nav.toast(if (ok) t("Сохранено", "Saved") else t("Не получилось сохранить файл", "Couldn't save the file"))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        PIconButton(Icons.Rounded.MoreHoriz, t("Ещё", "More"), { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            MenuItem(Icons.Rounded.Image, t("Сменить обложку…", "Change the cover…")) { menu = false; pickCover(graph, p) }
                            if (p.cover != null) MenuItem(Icons.Rounded.GridView, t("Обложка из песен", "Cover from the songs")) { menu = false; graph.playlists.setCover(p.id, null) }
                            MenuItem(Icons.Rounded.ContentCopy, t("Дублировать", "Duplicate")) {
                                menu = false
                                val copy = graph.playlists.create(t("${p.name} (копия)", "${p.name} (copy)"))
                                graph.playlists.setTracks(copy.id, p.trackIds)
                                nav.go(Screen.Playlist(copy.id))
                            }
                            MenuItem(Icons.Rounded.DeleteOutline, t("Удалить плейлист", "Delete the playlist")) { menu = false; confirmDelete = true }
                        }
                    }
                }
            }
        }
        preview?.takeIf { it.title == p.name }?.let { pv ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(Radius.sm)).background(pal.paper2).padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("Пролистываю · ${pv.index} из ${pv.total}", "Listening through · ${pv.index} of ${pv.total}"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                PButton(t("Дослушать эту", "Play this one"), { graph.preview.stop(keep = true) }, kind = Kind.GHOST)
                PButton(t("Стоп", "Stop"), { graph.preview.stop(keep = false) }, kind = Kind.GHOST)
            }
        }
    } }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 22.dp)) {
        if (tracks.isEmpty()) {
            header()
            EmptyState(t("Плейлист пуст", "Empty playlist"), t("Правый клик или ⋯ у трека → «В плейлист…»", "Right-click or ⋯ on a track → «Add to playlist…»"), "empty-playlist")
        } else TrackTable(
            tracks, liked, numbered = true, header = header,
            onRemove = { i ->
                val before = p.trackIds
                graph.playlists.removeAt(p.id, i)
                nav.toast(t("«${tracks[i].title}» убрана из плейлиста", "«${tracks[i].title}» is out of the playlist"), t("Вернуть", "Undo")) { graph.playlists.setTracks(p.id, before) }
            },
            onMove = { ids -> graph.playlists.setTracks(p.id, ids) },
        )
    }

    if (confirmDelete) PDialog(
        t("Удалить плейлист?", "Delete the playlist?"), { confirmDelete = false },
        subtitle = t("«${p.name}» удалится, песни останутся в медиатеке.", "«${p.name}» goes, the songs stay in the library."),
        buttons = {
            PButton(t("Отмена", "Cancel"), { confirmDelete = false }, kind = Kind.GHOST)
            Spacer(Modifier.width(8.dp))
            PButton(t("Удалить", "Delete"), {
                confirmDelete = false
                graph.playlists.delete(p.id)
                nav.root(Screen.Library)
                nav.toast(t("«${p.name}» удалён", "«${p.name}» deleted"), t("Вернуть", "Undo")) { graph.playlists.restore(p) }
            }, kind = Kind.PRIMARY)
        },
    ) {}
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** A picture for the playlist, copied into the config folder (the original may move or go). */
private fun pickCover(graph: dev.xvin.palimpsest.desktop.data.Graph, p: Playlist) {
    val chooser = javax.swing.JFileChooser().apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter(t("Картинки", "Pictures"), "jpg", "jpeg", "png", "webp") }
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
    val src = chooser.selectedFile
    val dir = File(Paths.config, "playlist-covers").apply { mkdirs() }
    // A new name each time: the image loader would keep showing the old picture under the same one.
    dir.listFiles { f -> f.name.startsWith("${p.id}_") }?.forEach { it.delete() }
    val dest = File(dir, "${p.id}_${System.currentTimeMillis()}.${src.extension.lowercase()}")
    if (runCatching { src.copyTo(dest) }.isSuccess) graph.playlists.setCover(p.id, dest.absolutePath)
}

/**
 * A playlist's face: the picture chosen for it, else four covers from different albums in it, else
 * the one there is, else one generated from the name.
 */
@Composable
fun PlaylistCover(p: Playlist, tracks: List<Track>, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    if (p.cover != null) { Artwork(File(p.cover), p.name, modifier, corner); return }
    val faces = remember(tracks) { tracks.filter { it.isLocal || it.coverUrl != null }.distinctBy { (it.albumArtist ?: it.artist) + "\u0000" + it.album.orEmpty() }.take(4) }
    when {
        faces.size >= 4 -> Column(modifier.clip(RoundedCornerShape(corner))) {
            for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0..1) TrackArt(faces[row * 2 + col], Modifier.weight(1f).fillMaxSize(), corner = 0.dp)
            }
        }
        faces.isNotEmpty() -> TrackArt(faces.first(), modifier, corner)
        else -> Artwork(null, p.name, modifier, corner)
    }
}
