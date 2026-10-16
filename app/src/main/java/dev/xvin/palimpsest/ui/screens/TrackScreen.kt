package dev.xvin.palimpsest.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.db.TrackVersionEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.data.library.TagEdit
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.SectionHeader
import dev.xvin.palimpsest.ui.components.TrackArtwork
import dev.xvin.palimpsest.ui.components.formatDuration
import dev.xvin.palimpsest.ui.components.techLine
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackScreen(trackId: Long, initialTab: Int) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val track by remember(trackId) { graph.db.tracks().observe(trackId) }.collectAsStateWithLifecycle(null)
    var tab by remember { mutableIntStateOf(initialTab) }
    val t = track ?: return
    val plays by remember(trackId) { graph.db.plays().observePlayCount(trackId) }.collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(t, Modifier.size(96.dp), corner = 12.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = Ink.Muted, maxLines = 1)
                Text("${techLine(t)} · прослушан $plays раз", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Теги", "Текст", "Версии").forEachIndexed { i, label -> Pill(label, tab == i, { tab = i }) }
        }
        when (tab) {
            0 -> TagsTab(t)
            1 -> LyricsTab(t)
            else -> VersionsTab(t)
        }
    }
}

@Composable
private fun TagsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val editable = t.path != null && TagIO.canEdit(File(t.path))
    var title by remember(t.id) { mutableStateOf(t.title) }
    var artist by remember(t.id) { mutableStateOf(t.artist) }
    var album by remember(t.id) { mutableStateOf(t.album.orEmpty()) }
    var albumArtist by remember(t.id) { mutableStateOf(t.albumArtist.orEmpty()) }
    var year by remember(t.id) { mutableStateOf(t.year?.toString().orEmpty()) }
    var genre by remember(t.id) { mutableStateOf(t.genre.orEmpty()) }
    var trackNo by remember(t.id) { mutableStateOf(t.trackNumber?.toString().orEmpty()) }
    var disc by remember(t.id) { mutableStateOf(t.discNumber?.toString().orEmpty()) }
    var cover by remember(t.id) { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!editable) {
            Text(
                if (t.path == null) "Трек ещё не скачан — теги можно править после загрузки." else "Этот формат не поддерживает запись тегов.",
                color = Ink.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Field("Название", title, editable) { title = it }
        Field("Исполнитель", artist, editable) { artist = it }
        Field("Альбом", album, editable) { album = it }
        Field("Исполнитель альбома", albumArtist, editable) { albumArtist = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Год", year, editable, Modifier.weight(1f)) { year = it }
            Field("№ трека", trackNo, editable, Modifier.weight(1f)) { trackNo = it }
            Field("Диск", disc, editable, Modifier.weight(1f)) { disc = it }
        }
        Field("Жанр", genre, editable) { genre = it }
        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (cover != null && cover!!.isNotEmpty()) "Обложка выбрана ✓" else "Сменить обложку")
                }
                TextButton(onClick = { cover = ByteArray(0) }) { Text("Убрать обложку", color = Ink.Muted) }
            }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                TagIO.write(
                                    File(t.path!!),
                                    TagEdit(
                                        title = title, artist = artist, album = album, albumArtist = albumArtist,
                                        year = year, genre = genre, trackNumber = trackNo, discNumber = disc,
                                        cover = cover, coverMime = if (cover?.take(4) == listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) "image/png" else "image/jpeg",
                                    ),
                                )
                                ArtProvider.invalidate(context.cacheDir, t.id)
                                graph.scanner.rescanFile(t.path)
                            }
                        }
                        saving = false
                        nav.toast(result.fold({ "Сохранено в файл" }, { "Не удалось сохранить: ${it.message}" }))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Сохраняю…" else "Сохранить в файл") }
        }

        SectionHeader("Файл")
        Text(t.path ?: "Не скачан (стрим)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Ink.Muted)
        if (t.remoteId != null) {
            Text("${t.service.displayName} · id ${t.remoteId}", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
        t.replayGainTrackDb?.let { Text("ReplayGain трека: %+.2f дБ".format(it), style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, enabled = enabled, singleLine = true, label = { Text(label) })
}

@Composable
private fun LyricsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var text by remember(t.id) { mutableStateOf("") }
    var source by remember(t.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var embed by remember { mutableStateOf(t.path != null) }

    LaunchedEffect(t.id) {
        val l = graph.lyrics.local(t)
        text = l?.synced ?: l?.plain.orEmpty()
        source = l?.source
        loading = false
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            source?.let { "Источник: $it" } ?: if (loading) "Загружаю…" else "Текста нет",
            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
        )
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), minLines = 8, maxLines = 24,
            label = { Text("Текст или LRC ([мм:сс.xx] строка)") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                loading = true
                scope.launch {
                    val l = graph.lyrics.fetchOnline(t)
                    loading = false
                    if (l != null) { text = l.synced ?: l.plain.orEmpty(); source = l.source } else nav.toast("Ничего не нашлось")
                }
            }, enabled = !loading) { Text("Найти онлайн") }
            Button(onClick = {
                scope.launch {
                    runCatching { graph.lyrics.save(t, text, embed) }
                        .onSuccess { source = "Изменено вручную"; nav.toast("Текст сохранён") }
                        .onFailure { nav.toast("Ошибка: ${it.message}") }
                }
            }) { Text("Сохранить") }
        }
        if (t.path != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(embed, { embed = it })
            Text("Записать и в тег файла", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VersionsTab(t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val versions by remember(t.id) { graph.db.versions().observeFor(t.id) }.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")) }
    val context = LocalContext.current

    // Swap in another file (e.g. a better rip); the current one is archived, never lost.
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val current = t.path?.let(::File) ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = dev.xvin.palimpsest.playback.MediaItems.displayName(context, uri) ?: "audio.${current.extension}"
                    val ext = name.substringAfterLast('.', current.extension).lowercase()
                    val tmp = File(current.parentFile, ".replace-${t.id}.$ext")
                    context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val target = File(current.parentFile, "${current.nameWithoutExtension}.$ext")
                    graph.versions.installNewAudio(t, tmp, target, "Заменён вручную: $name")
                }
            }
            nav.toast(
                result.fold(
                    { if (it is dev.xvin.palimpsest.data.library.VersionRepository.ReplaceResult.Identical) "Это тот же самый звук — ничего не изменилось" else "Файл заменён, прежний — в истории" },
                    { "Не удалось: ${it.message}" },
                ),
            )
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Когда исполнитель перезаливает трек, старый файл не перезаписывается, а уезжает сюда. " +
                "Любую версию можно послушать или вернуть.",
            style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )
        if (t.updateAvailable && t.remoteId != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalAccent.current.copy(alpha = 0.18f)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Новая на ${t.service.displayName}", style = MaterialTheme.typography.titleSmall)
                        Text("${formatDuration(t.remoteDurationMs ?: 0)} · ещё не скачана", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                    }
                    IconButton(onClick = { graph.player.playNewVersion(t.id); nav.openPlayer() }) { Icon(Icons.Rounded.PlayArrow, "Послушать новую") }
                }
                TextButton(onClick = {
                    scope.launch { graph.downloads.enqueue(listOf(t.id)) }
                    nav.toast("Скачиваю новую версию; текущая сохранится")
                }) { Text("Скачать и сделать текущей") }
            }
        }
        if (t.path != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.versions.snapshot(t, "Сохранена вручную")
                        nav.toast("Текущая версия сохранена")
                    }
                }) { Text("Сохранить текущую") }
                OutlinedButton(onClick = { replacePicker.launch(arrayOf("audio/*")) }) { Text("Заменить файл…") }
            }
        }

        if (versions.isEmpty()) Text("Версий пока нет.", color = Ink.Faint)
        versions.forEach { v -> VersionCard(v, fmt, t) }
    }
}

@Composable
private fun VersionCard(v: TrackVersionEntity, fmt: SimpleDateFormat, t: TrackEntity) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val exists = remember(v.path) { File(v.path).isFile }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (v.isCurrent) LocalAccent.current.copy(alpha = 0.12f) else Ink.Paper2).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (v.isCurrent) "Текущая" else fmt.format(Date(v.createdAt)), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        formatDuration(v.durationMs), v.codec?.uppercase(), v.bitrateKbps?.let { "$it кбит/с" },
                        "%.1f МБ".format(v.sizeBytes / 1_048_576f),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                )
                v.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Faint) }
                if (!exists) Text("Файл не найден", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (exists) IconButton(onClick = {
                if (v.isCurrent && t.path != null) graph.player.play(listOf(t)) else graph.player.playVersion(v.id)
                nav.openPlayer()
            }) { Icon(Icons.Rounded.PlayArrow, "Слушать") }
            if (!v.isCurrent && exists && t.path != null) {
                TextButton(onClick = {
                    graph.player.startAb(t.id, v.id, "версия от " + SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(v.createdAt)))
                    nav.openPlayer()
                }) { Text("A/B") }
                IconButton(onClick = {
                    scope.launch { graph.versions.restore(t, v); nav.toast("Версия восстановлена") }
                }) { Icon(Icons.Rounded.Restore, "Сделать текущей") }
            }
            if (!v.isCurrent) IconButton(onClick = { scope.launch { graph.versions.delete(v) } }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Ink.Faint) }
        }
    }
}
