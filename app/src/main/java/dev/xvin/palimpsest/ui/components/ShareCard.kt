package dev.xvin.palimpsest.ui.components

import android.content.Intent
import android.graphics.Bitmap
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

/** "Now listening" card rendered to an image and shared through the Android share sheet. */
@Composable
fun ShareCardDialog(np: NowPlaying, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val accent = LocalAccent.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Paper2,
        title = { Text("Поделиться") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.85f), Ink.Paper))),
            ) {
                Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("СЕЙЧАС СЛУШАЮ", style = MaterialTheme.typography.labelMedium, color = Ink.Text.copy(alpha = 0.8f))
                    Spacer(Modifier.height(14.dp))
                    Artwork(np.artworkUri ?: np.artworkData, np.album ?: np.title, Modifier.fillMaxWidth(0.78f).aspectRatio(1f), corner = 14.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(np.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Serif), color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, color = Ink.Text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text("Palimpsest", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Serif, fontSize = 15.sp), color = Ink.Text.copy(alpha = 0.6f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val file = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "share").apply { mkdirs() }.resolve("now-playing.png").also { f ->
                            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, "${np.artist} — ${np.title}")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Поделиться треком"))
                    onDismiss()
                }
            }) { Text("Поделиться") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

