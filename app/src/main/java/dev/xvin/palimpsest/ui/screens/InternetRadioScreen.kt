package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.radio.RadioStation
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Интернет-радио»: stations from the Radio Browser catalog (search, genres, the country's most
 * listened), the ones starred, the ones heard lately — and any stream by its link.
 */
@Composable
fun InternetRadioScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val favorites by graph.settings.radioFavorites.collectAsStateWithLifecycle(emptyList())
    val recent by graph.settings.radioRecent.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<RadioStation>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    // The catalog's answer for what's typed or picked; the country's most listened when it's neither.
    LaunchedEffect(query, tag) {
        delay(if (query.isBlank()) 0 else 450)
        loading = true; error = null
        results = runCatching {
            when {
                query.isNotBlank() -> graph.radioBrowser.search(name = query.trim())
                tag == COUNTRY -> graph.radioBrowser.top(country = "RU")
                tag != null -> graph.radioBrowser.search(tag = tag!!)
                else -> graph.radioBrowser.top(country = if (java.util.Locale.getDefault().language == "ru") "RU" else "")
            }
        }.onFailure { error = it.message }.getOrNull()
        loading = false
    }

    fun play(st: RadioStation) = scope.launch { graph.playStation(st); nav.openPlayer() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back)) }
                Text(tr(R.string.iradio_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, tr(R.string.iradio_add)) }
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(tr(R.string.iradio_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(COUNTRY to tr(R.string.iradio_russia)) + GENRES.map { it to it }).forEach { (value, label) ->
                    val on = tag == value && query.isBlank()
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, color = if (on) Ink.Paper else Ink.Text,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.Text else Ink.Paper2)
                            .clickable { query = ""; tag = if (on) null else value }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (query.isBlank() && tag == null) {
            if (favorites.isNotEmpty()) {
                item { Caption(tr(R.string.iradio_favorites)) }
                items(favorites, key = { "f" + it.key }) { st -> StationRow(st, true, { play(st) }) { scope.launch { graph.settings.setRadioFavorite(st, false) } } }
            }
            val fresh = recent.filter { r -> favorites.none { it.key == r.key } }
            if (fresh.isNotEmpty()) {
                item { Caption(tr(R.string.iradio_recent)) }
                items(fresh, key = { "r" + it.key }) { st -> StationRow(st, false, { play(st) }) { scope.launch { graph.settings.setRadioFavorite(st, true) } } }
            }
            item { Caption(tr(R.string.iradio_popular)) }
        }
        when {
            loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
            error != null -> item {
                Column(Modifier.padding(16.dp)) {
                    Text(tr(R.string.iradio_catalog_error), color = Ink.Muted)
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = Ink.Faint, modifier = Modifier.padding(top = 4.dp))
                }
            }
            results.isNullOrEmpty() -> item { Text(tr(R.string.iradio_nothing), color = Ink.Muted, modifier = Modifier.padding(16.dp)) }
            else -> items(results!!, key = { "s" + it.key }) { st ->
                val fav = favorites.any { it.key == st.key }
                StationRow(st, fav, { play(st) }) { scope.launch { graph.settings.setRadioFavorite(st, !fav) } }
            }
        }
        item { Spacer(Modifier.padding(bottom = 96.dp)) }
    }

    if (adding) AddStationDialog(onDismiss = { adding = false }) { st ->
        adding = false
        scope.launch { graph.settings.setRadioFavorite(st, true); play(st) }
    }
}

private const val COUNTRY = "@country"
private val GENRES = listOf("lofi", "jazz", "rock", "electronic", "classical", "ambient", "hip hop", "metal", "chillout", "news")

@Composable
private fun Caption(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))

@Composable
private fun StationRow(st: RadioStation, favorite: Boolean, onPlay: () -> Unit, onStar: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Paper2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Radio, null, tint = Ink.Faint)
            if (st.favicon != null) AsyncImage(st.favicon, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(st.country, st.codec?.let { c -> if (st.bitrate > 0) "$c ${st.bitrate}" else c }, st.tags?.split(',')?.take(3)?.joinToString(", ")).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onStar) {
            Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, tr(R.string.iradio_star), tint = if (favorite) MaterialTheme.colorScheme.primary else Ink.Faint)
        }
    }
}

/** A station by its link: a stream, or an .m3u / .pls playlist with one. */
@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (RadioStation) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val ok = url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.iradio_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.iradio_add_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(tr(R.string.iradio_link)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr(R.string.iradio_name)) })
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val u = url.trim()
                onAdd(RadioStation(name.trim().ifBlank { u.substringAfter("://").substringBefore('/') }, u))
            }) { Text(tr(R.string.iradio_listen)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
