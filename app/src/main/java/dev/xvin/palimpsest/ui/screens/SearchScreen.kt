package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.EmptyState
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.components.TrackRow
import dev.xvin.palimpsest.ui.components.formatDuration
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One search box for the library and every connected service. */
@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Service?>(null) } // null = library
    var local by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var remote by remember { mutableStateOf<List<RemoteTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val settings by graph.settings.flow.collectAsStateWithLifecycle(null)
    val playing by graph.player.state.collectAsStateWithLifecycle()

    LaunchedEffect(query, source) {
        error = null
        if (query.isBlank()) { local = emptyList(); remote = emptyList(); return@LaunchedEffect }
        delay(if (source == null) 120 else 450) // debounce
        loading = true
        try {
            if (source == null) local = graph.db.tracks().search(query.trim())
            else remote = graph.remote.search(source!!, query.trim())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
            error = graph.errors.report(e, if (source == Service.YOUTUBE) "Поиск YouTube" else "Поиск Яндекс", show = false)?.title
            remote = emptyList()
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Поиск", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Трек, исполнитель, альбом") },
            shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Медиатека", source == null, { source = null })
            Pill("Яндекс.Музыка", source == Service.YANDEX, { source = Service.YANDEX })
            Pill("YouTube Music", source == Service.YOUTUBE, { source = Service.YOUTUBE })
        }
        if (source == Service.YANDEX && settings?.yandexToken == null) {
            EmptyState("Яндекс не подключён", "Войди во вкладке «Сервисы», чтобы искать и слушать треки Яндекс.Музыки.")
            return@Column
        }
        if (loading) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                failure?.let { f ->
                    androidx.compose.material3.TextButton(onClick = {
                        graph.errors.log.value.firstOrNull()?.let(nav::showError) ?: nav.error(f, "Поиск")
                    }) { Text("Подробнее") }
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (source == null) {
                itemsIndexed(local, key = { _, t -> t.id }) { i, t ->
                    TrackRow(t, onClick = { graph.player.play(local, i) }, onMore = { nav.showActions(t) }, playing = playing.nowPlaying?.trackId == t.id)
                }
            } else {
                itemsIndexed(remote, key = { i, r -> "${r.service}:${r.id}:$i" }) { i, r ->
                    RemoteRow(
                        r,
                        onPlay = {
                            scope.launch {
                                // Queue the whole result list so "next" keeps going through the results.
                                val rows = remote.map { graph.remote.ensureTrack(it) }
                                graph.player.play(rows, i)
                            }
                        },
                        onDownload = {
                            scope.launch {
                                val row = graph.remote.ensureTrack(r)
                                graph.downloads.enqueue(listOf(row.id))
                                nav.toast("Скачиваю «${r.title}»")
                            }
                        },
                        onMore = { scope.launch { nav.showActions(graph.remote.ensureTrack(r)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(r: RemoteTrack, onPlay: () -> Unit, onDownload: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onMore).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(r.coverUrl, r.album ?: r.title, Modifier.size(48.dp), corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(r.artistLine.ifBlank { null }, r.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(r.durationMs), style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        IconButton(onClick = onDownload, enabled = r.available) { Icon(Icons.Rounded.Download, "Скачать", tint = Ink.Muted) }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Ink.Muted) }
    }
}
