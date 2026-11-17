package dev.xvin.palimpsest.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.core.render.VideoFrame
import dev.xvin.palimpsest.core.render.VideoLook
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.UiStyle
import dev.xvin.palimpsest.i18n.tr
import dev.xvin.palimpsest.render.RenderAspect
import dev.xvin.palimpsest.render.RenderMode
import dev.xvin.palimpsest.render.RenderQuality
import dev.xvin.palimpsest.render.RenderWorker
import dev.xvin.palimpsest.render.VideoRenderer
import dev.xvin.palimpsest.ui.LocalNav
import dev.xvin.palimpsest.ui.components.LocalGraph
import dev.xvin.palimpsest.ui.components.Pill
import dev.xvin.palimpsest.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Видео из трека»: a still of the video on top, redrawn as the choices below change — the look
 * («Тетрадь», «Обложка», «Пульт»; the app's own style first), cover or synced lyrics, vertical
 * (Reels/Shorts) or horizontal (YouTube), quality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenderDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val renderer = remember { VideoRenderer(context, graph) }
    // null while searching; then whether there are any, and the lyrics themselves for the still.
    val lyrics by produceState<Pair<Boolean, ParsedLyrics?>?>(null, track.id) { val l = renderer.syncedLyrics(track); value = (l != null) to l }
    val hasLyrics = lyrics?.first
    // Starts from the last render's choices; the look from the app's style the first time.
    val saved = remember { graph.settings.state.value }
    val styleLook = when (saved?.uiStyle) {
        UiStyle.PALIMPSEST -> VideoLook.NOTEBOOK
        UiStyle.CONSOLE -> VideoLook.CONSOLE
        else -> VideoLook.COVER
    }
    var look by remember { mutableStateOf(saved?.renderLook?.let { runCatching { VideoLook.valueOf(it) }.getOrNull() } ?: styleLook) }
    var mode by remember { mutableStateOf(saved?.renderMode?.let { runCatching { RenderMode.valueOf(it) }.getOrNull() } ?: RenderMode.COVER) }
    var aspect by remember { mutableStateOf(saved?.renderAspect?.let { runCatching { RenderAspect.valueOf(it) }.getOrNull() } ?: RenderAspect.VERTICAL) }
    var quality by remember { mutableStateOf(saved?.renderQuality?.let { runCatching { RenderQuality.valueOf(it) }.getOrNull() }?.takeIf { it != RenderQuality.PREVIEW } ?: RenderQuality.HIGH) }
    // Lyrics mode remembered from a track that had synced lyrics falls back to the cover here.
    val effectiveMode = if (mode == RenderMode.LYRICS && hasLyrics == false) RenderMode.COVER else mode
    val parts by produceState<VideoRenderer.Look?>(null, track.id) { value = renderer.look(track) }
    val still by produceState<android.graphics.Bitmap?>(null, parts, look, aspect, effectiveMode, lyrics) {
        val p = parts ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val spec = renderer.spec(
                track, p, look, aspect, lyrics?.second?.takeIf { effectiveMode == RenderMode.LYRICS },
                track.durationMs.takeIf { it > 0 } ?: 200_000, VideoFrame.previewEnvelope(),
            )
            renderer.preview(spec, p, aspect)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.Paper2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(tr(R.string.render_action), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(14.dp))
            // The still: what the video will look like.
            val vertical = aspect == RenderAspect.VERTICAL
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)
                    .then(if (vertical) Modifier.height(380.dp).aspectRatio(9f / 16) else Modifier.fillMaxWidth().aspectRatio(16f / 9))
                    .clip(RoundedCornerShape(14.dp)).background(Ink.Paper3),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(still, label = "still") { bmp ->
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }

            Label(tr(R.string.render_look))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr(R.string.style_palimpsest), look == VideoLook.NOTEBOOK, { look = VideoLook.NOTEBOOK })
                Pill(tr(R.string.style_glass), look == VideoLook.COVER, { look = VideoLook.COVER })
                Pill(tr(R.string.style_console), look == VideoLook.CONSOLE, { look = VideoLook.CONSOLE })
            }
            Text(
                tr(when (look) { VideoLook.NOTEBOOK -> R.string.render_look_notebook_hint; VideoLook.COVER -> R.string.render_look_cover_hint; VideoLook.CONSOLE -> R.string.render_look_console_hint }),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            Label(tr(R.string.render_whats_in))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr(R.string.render_mode_cover), effectiveMode == RenderMode.COVER, { mode = RenderMode.COVER })
                if (hasLyrics == true) Pill(tr(R.string.render_mode_lyrics), effectiveMode == RenderMode.LYRICS, { mode = RenderMode.LYRICS })
            }
            when (hasLyrics) {
                null -> Hint(tr(R.string.render_lyrics_searching))
                false -> Hint(tr(R.string.render_no_lyrics_hint))
                else -> Unit
            }

            Label(tr(R.string.render_format))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr(R.string.render_aspect_vertical), aspect == RenderAspect.VERTICAL, { aspect = RenderAspect.VERTICAL })
                Pill(tr(R.string.render_aspect_horizontal), aspect == RenderAspect.HORIZONTAL, { aspect = RenderAspect.HORIZONTAL })
            }

            Label(tr(R.string.render_quality))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RenderQuality.entries.filter { it != RenderQuality.PREVIEW }.forEach { q -> Pill(tr(q.labelRes), quality == q, { quality = q }) }
            }
            Hint(tr(R.string.render_hint))

            Button(
                onClick = {
                    RenderWorker.start(context, track.id, effectiveMode, aspect, quality, look)
                    graph.appScope.launch { graph.settings.setRenderChoice(mode.name, aspect.name, quality.name, look.name) }
                    nav.toast(tr(R.string.render_started))
                    onDismiss()
                },
                enabled = hasLyrics != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Rounded.Movie, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.render_start))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}
