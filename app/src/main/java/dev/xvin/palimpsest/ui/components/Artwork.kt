package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.ArtProvider
import dev.xvin.palimpsest.ui.theme.Ink
import dev.xvin.palimpsest.ui.theme.Serif

/**
 * Cover art with a generated fallback: a tinted tile with the first letter set in the display
 * serif, so a library full of untagged files still looks intentional.
 */
@Composable
fun Artwork(
    model: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape)) {
        Placeholder(seed, Modifier.fillMaxSize())
        if (model != null) {
            val ctx = LocalContext.current
            val request = remember(model) {
                ImageRequest.Builder(ctx).data(model).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun TrackArtwork(track: TrackEntity, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val model = remember(track.id, track.modifiedAt, track.coverUrl, track.hasEmbeddedCover, track.path) {
        if (track.path != null || track.coverUrl != null) ArtProvider.uriFor(track.id, track.modifiedAt) else null
    }
    Artwork(model, track.album ?: track.title, modifier, corner)
}

@Composable
private fun Placeholder(seed: String, modifier: Modifier) {
    val hue = remember(seed) { ((seed.hashCode() and 0x7fffffff) % 360).toFloat() }
    val base = remember(hue) { Color.hsv(hue, 0.35f, 0.32f) }
    val letter = remember(seed) { seed.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪" }
    BoxWithConstraints(
        modifier.background(Brush.linearGradient(listOf(base, Color.hsv((hue + 30) % 360, 0.3f, 0.16f)))),
        contentAlignment = Alignment.Center,
    ) {
        val size = with(LocalDensity.current) { (maxWidth * 0.55f).toSp() }
        Text(
            letter,
            style = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = size, color = Ink.Text.copy(alpha = 0.55f)),
        )
    }
}
