package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.Duplicates
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.techLine
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import kotlinx.coroutines.launch

@Composable
fun DuplicatesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Duplicates.Group>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { groups = graph.duplicates.find() }

    fun mergeAll(list: List<Duplicates.Group>) {
        busy = true
        scope.launch {
            runCatching { list.forEach { graph.duplicates.merge(it) } }.onFailure { nav.error(it, "Объединение дубликатов") }
            busy = false
            reload++
            nav.toast("Объединено: ${list.size}")
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("Дубликаты", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
            Text(
                "Один и тот же трек, сохранённый несколько раз. При объединении остаётся лучшая копия (файл, lossless, выше битрейт), " +
                    "а лишние файлы не удаляются — уходят в её историю версий. Плейлисты, история и лайки переносятся.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
            )
            val g = groups
            if (g != null && g.isNotEmpty()) {
                Button(onClick = { mergeAll(g) }, enabled = !busy, modifier = Modifier.padding(16.dp)) { Text("Объединить все (${g.size})") }
            }
        }
        when {
            groups == null || busy -> item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator() } }
            groups!!.isEmpty() -> item { Text("Дубликатов не нашлось ✓", Modifier.padding(16.dp), color = Ink.Muted) }
            else -> items(groups!!, key = { it.keep.id }) { g ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Paper2).padding(12.dp),
                ) {
                    Text("${g.keep.artist} — ${g.keep.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Copy(g.keep, keep = true)
                    g.others.forEach { Copy(it, keep = false) }
                    TextButton(onClick = { mergeAll(listOf(g)) }, enabled = !busy) { Text("Объединить", color = LocalAccent.current) }
                }
            }
        }
    }
}

@Composable
private fun Copy(t: TrackEntity, keep: Boolean) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (keep) "✓ " else "→ ", color = if (keep) LocalAccent.current else Ink.Faint)
        Column {
            Text(techLine(t), style = MaterialTheme.typography.labelMedium, color = if (keep) Ink.Text else Ink.Muted)
            Text(t.path ?: "облако · ${t.service.displayName}", style = MaterialTheme.typography.labelSmall, color = Ink.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
