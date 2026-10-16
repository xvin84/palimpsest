package dev.xvin.palimpsest.ui.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → «Журнал ошибок»: every error with its explanation; tap for hints and details. */
@Composable
fun ErrorsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val log by graph.errors.log.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm:ss", Locale.forLanguageTag("ru")) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("Журнал ошибок", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(enabled = log.isNotEmpty(), onClick = {
                    val all = log.joinToString("\n\n========\n\n") { "${it.title}\n${it.explanation}\n\n${it.details}" }
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Palimpsest errors", all))) }
                    nav.toast("Журнал скопирован")
                }) { Text("Скопировать всё") }
                TextButton(enabled = log.isNotEmpty(), onClick = { graph.errors.clear() }) { Text("Очистить", color = Ink.Muted) }
            }
        }
        if (log.isEmpty()) item { Text("Ошибок не было 🎉", Modifier.padding(16.dp), color = Ink.Muted) }
        items(log, key = { "${it.at}${it.title}" }) { e ->
            Column(Modifier.fillMaxWidth().clickable { nav.showError(e) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(fmt.format(Date(e.at)), e.context).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
