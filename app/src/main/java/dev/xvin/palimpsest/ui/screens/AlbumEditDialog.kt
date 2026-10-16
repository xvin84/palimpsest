package dev.xvin.palimpsest.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.data.library.TagEdit
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Edits the shared tags of a whole album at once (title, album artist, year, genre, cover). */
@Composable
fun AlbumEditDialog(tracks: List<TrackEntity>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = tracks.firstOrNull() ?: return
    val files = tracks.filter { it.path != null && TagIO.canEdit(File(it.path)) }
    var album by remember { mutableStateOf(first.album.orEmpty()) }
    var albumArtist by remember { mutableStateOf(first.albumArtist ?: first.artist) }
    var year by remember { mutableStateOf(first.year?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(first.genre.orEmpty()) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { cover = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }
    }

    AlertDialog(
        onDismissRequest = { if (progress < 0) onDismiss() },
        containerColor = Ink.Paper2,
        title = { Text("Изменить альбом") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Изменится у ${files.size} из ${tracks.size} треков (у остальных формат не поддерживает теги или они не скачаны).", color = Ink.Muted)
                OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Альбом") })
                OutlinedTextField(albumArtist, { albumArtist = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Исполнитель альбома") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it }, Modifier.weight(1f), singleLine = true, label = { Text("Год") })
                    OutlinedTextField(genre, { genre = it }, Modifier.weight(1f), singleLine = true, label = { Text("Жанр") })
                }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (cover != null) "Обложка выбрана ✓" else "Обложка для всех треков")
                }
                if (progress >= 0) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = progress < 0 && files.isNotEmpty(), onClick = {
                progress = 0f
                scope.launch {
                    var failed: Throwable? = null
                    withContext(Dispatchers.IO) {
                        files.forEachIndexed { i, t ->
                            runCatching {
                                TagIO.write(File(t.path!!), TagEdit(album = album, albumArtist = albumArtist, year = year, genre = genre, cover = cover))
                                ArtProvider.invalidate(context.cacheDir, t.id)
                                graph.scanner.rescanFile(t.path)
                            }.onFailure { failed = it }
                            progress = (i + 1f) / files.size
                        }
                    }
                    failed?.let { nav.error(it, "Изменение альбома") } ?: nav.toast("Альбом обновлён")
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(enabled = progress < 0, onClick = onDismiss) { Text("Отмена") } },
    )
}
