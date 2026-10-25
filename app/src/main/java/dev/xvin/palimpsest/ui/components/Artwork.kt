package dev.xvin.palimpsest.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.ArtProvider

/**
 * Cover art over a generated one ([GeneratedCover]: colours, a pattern and the title, all from the
 * name), so music without a picture still has a cover of its own. Round tiles (artists) skip the text.
 */
@Composable
fun Artwork(
    model: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    artist: String? = null,
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape)) {
        GeneratedCover(seed, artist, Modifier.fillMaxSize(), withText = corner < 30.dp)
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
    Artwork(model, track.album ?: track.title, modifier, corner, artist = track.albumArtist ?: track.artist)
}
