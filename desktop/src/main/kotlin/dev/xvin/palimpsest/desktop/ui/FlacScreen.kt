package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xvin.palimpsest.desktop.data.FlacCandidate
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Перевести в FLAC»: pick the folders, search, then listen to each FLAC found against the old
 * file (at the same second) and keep it or not — like «FLAC-версии» on the phone.
 */
@Composable
fun FlacScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val up = graph.flacUpgrade
    val folders by up.folders.collectAsState()
    val search by up.state.collectAsState()
    val candidates by up.candidates.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val library by graph.library.tracks.collectAsState()
    val lossy = remember(folders, library, candidates) { up.lossyTracks(folders).size }
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    // Every folder that can be picked: the music folders and whatever was added here.
    val choices = (settings.musicRoots + folders).distinct()

    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
        item {
            PIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t("Назад", "Back"), nav::back, Modifier.padding(bottom = 8.dp).offset(x = (-6).dp), iconSize = 20.dp)
            Text(t("Перевести в FLAC", "Upgrade to FLAC"), style = MaterialTheme.typography.displayMedium)
            Text(
                t(
                    "Для MP3, AAC и других сжатых файлов ищет ту же песню в FLAC в Яндекс Музыке и скачивает её с тегами и обложкой файла. Ничего не заменяется, пока не послушаешь и не скажешь «Это он»; старый файл уходит в папку .palimpsest/replaced рядом с ним.",
                    "For MP3, AAC and other lossy files, looks for the same song in FLAC on Yandex Music and downloads it with the file’s tags and cover. Nothing is replaced until you listen and say «It’s the one»; the old file goes to .palimpsest/replaced next to it.",
                ),
                color = pal.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 760.dp).padding(vertical = 8.dp),
            )
        }
        item { SectionHeader(t("Папки", "Folders")) }
        items(choices) { f ->
            Row(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(Radius.md)).clickable(enabled = !search.running) { up.setFolders(if (f in folders) folders - f else folders + f) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PCheckbox(f in folders, { on -> up.setFolders(if (on) folders + f else folders - f) }, enabled = !search.running)
                Spacer(Modifier.width(12.dp))
                Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            PButton(t("Добавить папку…", "Add a folder…"), enabled = !search.running, kind = Kind.GHOST, icon = Icons.Rounded.Add, modifier = Modifier.padding(top = 4.dp), onClick = {
                val chooser = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val f = chooser.selectedFile.absolutePath
                    up.setFolders(folders + f)
                    // A folder outside the library joins the music folders, so its files get read.
                    if (settings.musicRoots.none { f == it || f.startsWith(it.trimEnd(File.separatorChar) + File.separator) }) {
                        graph.settings.update { it.copy(musicRoots = (it.musicRoots + f).distinct()) }
                        graph.scope.launch { graph.library.scan() }
                    }
                }
            })
        }
        item {
            Column(Modifier.padding(vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).background(pal.paper2).border(1.dp, pal.line.copy(alpha = 0.7f), RoundedCornerShape(Radius.xl)).padding(18.dp)) {
                when {
                    search.running -> {
                        Text(t("Ищу: ${search.checked} из ${search.total} · FLAC нашёлся для ${search.found} · только MP3/AAC: ${search.lossyOnly}",
                            "Searching: ${search.checked} of ${search.total} · FLAC found for ${search.found} · only MP3/AAC: ${search.lossyOnly}"), style = MaterialTheme.typography.bodyMedium)
                        search.current?.let { Text(it, color = pal.muted, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        PProgress(if (search.total > 0) search.checked.toFloat() / search.total else 0f, Modifier.fillMaxWidth().padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
                        PButton(t("Остановить", "Stop"), { up.stop() }, icon = Icons.Rounded.Stop)
                    }
                    else -> {
                        when (search.error) {
                            "yandex" -> Text(t("Войди в Яндекс Музыку (Сервисы) — FLAC берётся оттуда.", "Sign in to Yandex Music (Services) — the FLACs come from there."), color = MaterialTheme.colorScheme.error)
                            "plus" -> Text(
                                t("У аккаунта Яндекса сейчас нет Плюса — без него Яндекс отдаёт треки только в AAC 192, FLAC скачать нельзя.",
                                    "This Yandex account has no Plus right now — without it Yandex gives tracks only in AAC 192, so there is no FLAC to download."),
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> if (search.total > 0) Text(
                                t("Прошлый поиск: FLAC нашёлся для ${search.found} · только MP3/AAC: ${search.lossyOnly} · не нашлись: ${search.notFound}",
                                    "Last search: FLAC found for ${search.found} · only MP3/AAC: ${search.lossyOnly} · not found: ${search.notFound}"),
                                color = pal.muted,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PButton(
                            if (lossy > 0) t("Искать FLAC (файлов: $lossy)", "Look for FLAC ($lossy files)") else t("Сжатых файлов здесь нет", "No lossy files here"),
                            { up.start() }, icon = Icons.Rounded.Search, kind = Kind.PRIMARY, enabled = lossy > 0 && folders.isNotEmpty(), modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) item {
            SectionHeader(t("На проверку: ${candidates.size}", "To review: ${candidates.size}")) {
                PButton(t("Принять все", "Accept all"), { confirmAll = true }, icon = Icons.Rounded.DoneAll, kind = Kind.GHOST)
            }
        }
        items(candidates, key = { it.flacPath }) { c -> CandidateRow(c) }
        if (candidates.isEmpty() && !search.running) item {
            EmptyState(t("Пока ничего не ждёт проверки", "Nothing waiting for review"), t("Найденные FLAC появятся здесь.", "The FLACs found will appear here."))
        }
    }
    if (confirmAll) PDialog(
        t("Заменить все ${candidates.size}?", "Replace all ${candidates.size}?"), { confirmAll = false }, width = 460.dp,
        buttons = {
            PButton(t("Отмена", "Cancel"), { confirmAll = false }, kind = Kind.GHOST)
            PButton(t("Заменить", "Replace"), {
                confirmAll = false
                val all = candidates
                scope.launch { all.forEach { up.accept(it) }; nav.toast(t("Заменено: ${all.size}", "Replaced: ${all.size}")) }
            }, kind = Kind.PRIMARY)
        },
    ) {
        Text(t("Каждый FLAC встанет на место своего файла без прослушивания. Старые файлы останутся в .palimpsest/replaced.", "Every FLAC takes its file’s place without listening. The old files stay in .palimpsest/replaced."), style = MaterialTheme.typography.bodyMedium, color = pal.muted)
    }
}

@Composable
private fun CandidateRow(c: FlacCandidate) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val player by graph.player.state.collectAsState()
    val playingPath = player.current?.path
    val track = graph.library.tracks.value.firstOrNull { it.path == c.trackPath }
    fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
    fun listen(path: String) {
        val base = track ?: return
        // Switching between the two keeps the second: the same moment in the old file and the FLAC.
        val at = if (playingPath == c.trackPath || playingPath == c.flacPath) player.positionMs else 0L
        graph.player.playAlone(base.copy(path = path), at)
    }
    Column(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(pal.paper2).border(1.dp, pal.line.copy(alpha = 0.6f), RoundedCornerShape(Radius.lg)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArt(track, Modifier.size(44.dp), Radius.sm)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = Type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.artist, color = pal.muted, style = Type.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${listOfNotNull(c.oldCodec?.uppercase(), c.oldBitrateKbps?.let { "$it kbps" }).joinToString(" ")} · ${mb(c.oldSize)}  →  FLAC · ${mb(c.newSize)}",
                    color = pal.faint, style = Type.meta, modifier = Modifier.padding(top = 2.dp),
                )
                Text(File(c.trackPath).parent ?: "", color = pal.faint, style = Type.meta.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Old and new side by side, as one switch: flick between them at the same second.
                Tabs(listOf(c.trackPath to t("Было", "Old"), c.flacPath to "FLAC"), playingPath.takeIf { it == c.trackPath || it == c.flacPath } ?: "", { listen(it) })
                PButton(t("Не то", "Not it"), { graph.flacUpgrade.reject(c) }, kind = Kind.GHOST)
                PButton(t("Это он", "It’s the one"), kind = Kind.PRIMARY, onClick = {
                    scope.launch {
                        if (playingPath == c.trackPath || playingPath == c.flacPath) graph.player.engine.stop()
                        graph.flacUpgrade.accept(c)
                        nav.toast(t("«${c.title}» теперь во FLAC", "«${c.title}» is FLAC now"))
                    }
                })
            }
        }
    }
}
