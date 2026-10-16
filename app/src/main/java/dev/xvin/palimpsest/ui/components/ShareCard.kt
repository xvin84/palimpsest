package dev.xvin.palimpsest.ui.components

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import dev.xvin.palimpsest.playback.MediaItems
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.playback.NowPlaying
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.LocalAccent
import dev.xvin.palimpsest.ui.theme.Serif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.i18n.tr

/** What goes into the share sheet. */
private enum class ShareWhat { CARD, FILE, BOTH }

/**
 * "Now listening" card rendered to an image, and/or the track's audio file, shared through the
 * Android share sheet. The card's background is the cover itself, blurred and tinted with the
 * track's accent colour, so it looks right for both bright and black-and-white covers.
 */
@Composable
fun ShareCardDialog(np: NowPlaying, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val accent = LocalAccent.current
    val audio by produceState<File?>(null, np.mediaId) { value = withContext(Dispatchers.IO) { audioFile(graph, np) } }
    var what by remember { mutableStateOf(ShareWhat.CARD) }
    val cover = np.artworkUri ?: np.artworkData
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text(tr(R.string.share)) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.8f)
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                ) {
                    if (cover != null) {
                        Artwork(
                            cover, np.album ?: np.title,
                            Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }.blur(36.dp, BlurredEdgeTreatment.Rectangle),
                            corner = 0.dp,
                        )
                    }
                    Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.28f)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.62f)))))
                    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr(R.string.share_now_listening), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                        Spacer(Modifier.height(14.dp))
                        Artwork(cover, np.album ?: np.title, Modifier.fillMaxWidth(0.78f).aspectRatio(1f).shadow(18.dp, RoundedCornerShape(14.dp)), corner = 14.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(np.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Serif), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(np.artist, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text("Palimpsest", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Serif, fontSize = 15.sp), color = Color.White.copy(alpha = 0.6f))
                    }
                }
                if (audio != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(tr(R.string.share_card), what == ShareWhat.CARD, { what = ShareWhat.CARD })
                        Pill(tr(R.string.file), what == ShareWhat.FILE, { what = ShareWhat.FILE })
                        Pill(tr(R.string.share_both), what == ShareWhat.BOTH, { what = ShareWhat.BOTH })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val uris = ArrayList<Uri>()
                    if (what != ShareWhat.FILE) {
                        val bitmap = layer.toImageBitmap().asAndroidBitmap()
                        val png = withContext(Dispatchers.IO) {
                            File(context.cacheDir, "share").apply { mkdirs() }.resolve("now-playing.png").also { f ->
                                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        }
                        uris += FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", png)
                    }
                    val file = audio
                    if (what != ShareWhat.CARD && file != null) uris += FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
                    val send = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                            .setType(if (what == ShareWhat.FILE) mimeOf(file!!) else "image/png")
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
                    }
                    send.putExtra(Intent.EXTRA_TEXT, "${np.artist} — ${np.title}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Read grants travel with ClipData: without it the chooser (and SEND_MULTIPLE targets) can't open the files.
                    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    context.startActivity(Intent.createChooser(send, tr(R.string.share_track)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    onDismiss()
                }
            }) { Text(tr(R.string.share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
    )
}

/** The file that is actually playing: a specific version if one was picked, else the library file. */
private suspend fun audioFile(graph: dev.xvin.palimpsest.AppGraph, np: NowPlaying): File? {
    val path = if (np.mediaId.startsWith(MediaItems.VERSION_PREFIX)) {
        np.mediaId.removePrefix(MediaItems.VERSION_PREFIX).toLongOrNull()?.let { graph.db.versions().byId(it)?.path }
    } else np.trackId?.let { graph.db.tracks().get(it)?.path }
    return path?.let(::File)?.takeIf { it.isFile }
}

private fun mimeOf(f: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "audio/*"
