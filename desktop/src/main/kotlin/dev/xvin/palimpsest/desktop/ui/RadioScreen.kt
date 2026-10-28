package dev.xvin.palimpsest.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.xvin.palimpsest.core.radio.RadioStation
import kotlinx.coroutines.delay

/**
 * «Радио»: internet radio — stations from the Radio Browser catalog (search, genres, the most
 * listened in Russia), the starred ones, the ones heard lately — and any stream by its link.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioScreen() {
    val graph = LocalGraph.current
    val pal = LocalPal.current
    val settings by graph.settings.state.collectAsState()
    val st by graph.player.state.collectAsState()
    val playing = graph.player.stationOf(st.current)
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<RadioStation>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(query, tag) {
        delay(if (query.isBlank()) 0 else 450)
        loading = true; error = null
        results = runCatching {
            when {
                query.isNotBlank() -> graph.radioBrowser.search(name = query.trim())
                tag == RU -> graph.radioBrowser.top(country = "RU")
                tag != null -> graph.radioBrowser.search(tag = tag!!)
                else -> graph.radioBrowser.top(country = if (language == "ru") "RU" else "")
            }
        }.onFailure { error = it.message }.getOrNull()
        loading = false
    }

    fun star(s: RadioStation, on: Boolean) = graph.settings.update { cur ->
        val rest = cur.radioFavorites.filter { it.key != s.key }
        cur.copy(radioFavorites = if (on) rest + s else rest)
    }

    @Composable
    fun Row(s: RadioStation) {
        val fav = settings.radioFavorites.any { it.key == s.key }
        StationRow(s, fav, playing?.key == s.key, { graph.player.playStation(s) }) { star(s, !fav) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 32.dp)) {
        item {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Радио", "Radio"), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                PButton(t("Добавить по ссылке", "Add by link"), { adding = true }, icon = Icons.Rounded.Add)
            }
            Text(t("Интернет-станции со всего мира — или любая по ссылке", "Stations from all over the world — or any by its link"), color = pal.muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            PField(query, { query = it }, t("Название станции", "Station name"), Modifier.width(420.dp))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(RU to t("Россия", "Russia")) + GENRES.map { it to it }).forEach { (v, label) ->
                    Pill(label, tag == v && query.isBlank(), { query = ""; tag = if (tag == v) null else v })
                }
            }
        }
        if (query.isBlank() && tag == null) {
            if (settings.radioFavorites.isNotEmpty()) {
                item { SectionHeader(t("Избранное", "Starred")) }
                items(settings.radioFavorites, key = { "f" + it.key }) { Row(it) }
            }
            val recent = settings.radioRecent.filter { r -> settings.radioFavorites.none { it.key == r.key } }
            if (recent.isNotEmpty()) {
                item { SectionHeader(t("Недавние", "Lately")) }
                items(recent, key = { "r" + it.key }) { Row(it) }
            }
            item { SectionHeader(t("Популярное", "Most listened")) }
        } else item { Spacer(Modifier.height(16.dp)) }
        when {
            loading -> item { Text(t("Ищу станции…", "Looking for stations…"), color = pal.muted, modifier = Modifier.padding(vertical = 12.dp)) }
            error != null -> item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(t("Каталог станций не отвечает. Избранные станции и ссылки всё равно играют.", "The station catalog doesn’t answer. Starred stations and links still play."), color = pal.muted)
                    Text(error!!, style = Type.meta, color = pal.faint)
                }
            }
            results.isNullOrEmpty() -> item { Text(t("Станций не нашлось", "No stations found"), color = pal.muted, modifier = Modifier.padding(vertical = 12.dp)) }
            else -> items(results!!, key = { "s" + it.key }) { Row(it) }
        }
    }

    if (adding) AddStationDialog({ adding = false }) { s -> adding = false; star(s, true); graph.player.playStation(s) }
}

private const val RU = "@ru"
private val GENRES = listOf("lofi", "jazz", "rock", "electronic", "classical", "ambient", "hip hop", "metal", "chillout", "news")

@Composable
private fun StationRow(s: RadioStation, favorite: Boolean, playing: Boolean, onPlay: () -> Unit, onStar: () -> Unit) {
    val pal = LocalPal.current
    val accent = LocalAccent.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(Radius.md))
            .background(if (hovered) pal.text.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onPlay).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(pal.paper3), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Radio, null, tint = pal.faint, modifier = Modifier.size(20.dp))
            if (s.favicon != null) AsyncImage(s.favicon, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (hovered || playing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                if (playing) PlayingBars(true, Modifier.size(16.dp), Color.White) else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = Type.rowTitle, color = if (playing) accent else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(s.country, s.codec?.let { c -> if (s.bitrate > 0) "$c ${s.bitrate}" else c }, s.tags?.split(',')?.take(4)?.joinToString(", ")).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = Type.rowSub, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PIconButton(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, t("В избранное", "Star"), onStar, iconSize = 18.dp, tint = if (favorite) accent else pal.faint)
    }
}

/** A station by its link: a stream, or an .m3u / .pls playlist with one. */
@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (RadioStation) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val u = url.trim()
    val ok = u.startsWith("http://", true) || u.startsWith("https://", true)
    PDialog(
        t("Станция по ссылке", "A station by its link"), onDismiss,
        subtitle = t("Ссылка на поток или плейлист (.m3u, .pls) — на сайте станции она обычно под кнопкой «Слушать».", "A stream link or a playlist (.m3u, .pls) — the station’s site usually has one under «Listen»."),
        buttons = {
            PButton(t("Отмена", "Cancel"), onDismiss, kind = Kind.GHOST)
            PButton(t("Слушать", "Listen"), { onAdd(RadioStation(name.trim().ifBlank { u.substringAfter("://").substringBefore('/') }, u)) }, kind = Kind.PRIMARY, enabled = ok)
        },
    ) {
        PInput(url, { url = it }, "https://…", Modifier.fillMaxWidth(), label = t("Ссылка", "Link"))
        Spacer(Modifier.height(10.dp))
        PInput(name, { name = it }, t("Как назвать", "What to call it"), Modifier.fillMaxWidth(), label = t("Название (можно не писать)", "Name (optional)"))
    }
}
