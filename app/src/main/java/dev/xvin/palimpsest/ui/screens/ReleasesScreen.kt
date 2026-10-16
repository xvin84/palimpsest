package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.Artwork
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch

/** New albums and singles from the artists in your library. */
@Composable
fun ReleasesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(graph.releases.recent()) }
    var checking by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("Новые релизы", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
            Text(
                "Проверяю раз в день исполнителей, которых у тебя больше всего, и присылаю уведомление о новинках.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
            )
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    runCatching { graph.releases.check(force = true) }.onFailure { nav.error(it, "Новые релизы") }
                    list = graph.releases.recent()
                    checking = false
                }
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (checking) "Проверяю…" else "Проверить сейчас") }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (list.isEmpty() && !checking) item {
            Text(
                "Пока ничего нового. Первая проверка только запоминает дискографию — новинки появятся здесь, когда выйдут.",
                Modifier.padding(16.dp), color = Ink.Muted,
            )
        }
        items(list, key = { it.album.id }) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.openRemoteAlbum(r.album.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(r.album.coverUrl, r.album.title, Modifier.size(56.dp), corner = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(r.artist, if (r.album.type == "single") "сингл" else "альбом", r.album.year?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1,
                    )
                }
            }
        }
    }
}
