package dev.xvin.palimpsest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.render.RenderAspect
import dev.xvin.palimpsest.render.RenderMode
import dev.xvin.palimpsest.render.RenderQuality
import kotlinx.coroutines.launch
import dev.xvin.palimpsest.render.RenderWorker
import dev.xvin.palimpsest.render.VideoRenderer
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.theme.Ink

/** «Видео из трека»: cover or synced lyrics, vertical (Reels/Shorts) or horizontal (YouTube). */
@Composable
fun RenderDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val hasLyrics by produceState<Boolean?>(null, track.id) { value = VideoRenderer(context, graph).syncedLyrics(track) != null }
    // Starts from the last render's choices.
    val saved = remember { graph.settings.state.value }
    var mode by remember { mutableStateOf(saved?.renderMode?.let { runCatching { RenderMode.valueOf(it) }.getOrNull() } ?: RenderMode.COVER) }
    var aspect by remember { mutableStateOf(saved?.renderAspect?.let { runCatching { RenderAspect.valueOf(it) }.getOrNull() } ?: RenderAspect.VERTICAL) }
    var quality by remember { mutableStateOf(saved?.renderQuality?.let { runCatching { RenderQuality.valueOf(it) }.getOrNull() } ?: RenderQuality.HIGH) }
    // Lyrics mode remembered from a track that had synced lyrics falls back to the cover here.
    val effectiveMode = if (mode == RenderMode.LYRICS && hasLyrics == false) RenderMode.COVER else mode
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.render_action)) },
        text = {
            Column {
                Text(tr(R.string.render_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr(R.string.render_mode_cover), effectiveMode == RenderMode.COVER, { mode = RenderMode.COVER })
                    if (hasLyrics == true) Pill(tr(R.string.render_mode_lyrics), effectiveMode == RenderMode.LYRICS, { mode = RenderMode.LYRICS })
                }
                if (hasLyrics == null) {
                    Text(tr(R.string.render_lyrics_searching), style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.padding(top = 6.dp))
                }
                if (hasLyrics == false) {
                    Text(tr(R.string.render_no_lyrics_hint), style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr(R.string.render_aspect_vertical), aspect == RenderAspect.VERTICAL, { aspect = RenderAspect.VERTICAL })
                    Pill(tr(R.string.render_aspect_horizontal), aspect == RenderAspect.HORIZONTAL, { aspect = RenderAspect.HORIZONTAL })
                }
                Spacer(Modifier.height(12.dp))
                Text(tr(R.string.render_quality), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RenderQuality.entries.forEach { q -> Pill(tr(q.labelRes), quality == q, { quality = q }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RenderWorker.start(context, track.id, effectiveMode, aspect, quality)
                graph.appScope.launch { graph.settings.setRenderChoice(mode.name, aspect.name, quality.name) }
                nav.toast(tr(R.string.render_started))
                onDismiss()
            }) { Text(tr(R.string.render_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
