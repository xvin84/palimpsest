package dev.xvin.palimpsest.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.palette.graphics.Palette
import dev.xvin.palimpsest.AppGraph
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.lyrics.ParsedLyrics
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.library.TagIO
import dev.xvin.palimpsest.data.settings.ReplayGainMode
import dev.xvin.palimpsest.playback.AudioEffects
import dev.xvin.palimpsest.playback.EffectsAudioProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders a track into an MP4: the file's own audio through the player's EQ, ReplayGain and
 * limiter (no microphone, no re-recording, so no noise), with a frame drawn for every 1/30 s.
 */
@OptIn(UnstableApi::class)
class VideoRenderer(private val context: Context, private val graph: AppGraph) {

    /** Whether the track has synced lyrics, i.e. whether the lyrics mode makes sense. */
    suspend fun syncedLyrics(track: TrackEntity): ParsedLyrics? =
        graph.lyrics.local(track)?.synced?.let(Lrc::parse)?.takeIf { it.isSynced && it.lines.isNotEmpty() }

    /** Renders and saves to Movies/Palimpsest; returns the saved video's content URI. */
    suspend fun render(track: TrackEntity, mode: RenderMode, aspect: RenderAspect, onProgress: (Float) -> Unit): Uri {
        val file = track.path?.let(::File)?.takeIf { it.isFile } ?: throw IOException(context.getString(R.string.render_needs_file))
        val s = graph.settings.state.filterNotNull().first()

        // Same sound as in the player: the profile for the current output, ReplayGain, limiter.
        val profile = s.eqFor(graph.outputs.current.value.key)
        val rg = when (s.replayGain) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> track.replayGainTrackDb ?: track.replayGainAlbumDb
            ReplayGainMode.ALBUM -> track.replayGainAlbumDb ?: track.replayGainTrackDb
        }
        val gainDb = if (rg == null) 0.0 else (rg + s.replayGainPreampDb).toDouble()

        val cover = withContext(Dispatchers.IO) { runCatching { TagIO.coverBytes(file) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        val accent = cover?.let { Palette.from(it).generate().let { p -> (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch)?.rgb } }
            ?.let(::vividAccent) ?: 0xFFE4572E.toInt()
        val lyrics = if (mode == RenderMode.LYRICS) syncedLyrics(track) ?: throw IOException(context.getString(R.string.render_needs_lyrics)) else null

        val timeline = SpectrumTimeline.analyze(file, FPS, 48, profile, s.eqEnabled, gainDb) { onProgress(it * 0.25f) }
        val spec = FrameSpec(
            aspect, mode, s.visualizer.takeIf { it != dev.xvin.palimpsest.data.settings.VisualizerMode.OFF } ?: dev.xvin.palimpsest.data.settings.VisualizerMode.BARS,
            accent, cover, track.title, track.artist, lyrics, timeline, timeline.durationUs / 1000,
            ResourcesCompat.getFont(context, R.font.playfair_display) ?: android.graphics.Typeface.SERIF,
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL),
        )
        val renderer = FrameRenderer(spec)

        val work = File(context.cacheDir, "render").apply { deleteRecursively(); mkdirs() }
        val backgroundPng = File(work, "background.png").also { f -> f.outputStream().use { renderer.background.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val out = File(work, "video.mp4")

        // The frame on top of the backdrop, redrawn for every presentation time.
        val frame = Bitmap.createBitmap(aspect.width, aspect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val overlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                renderer.draw(canvas, presentationTimeUs / 1000)
                return frame
            }
        }

        val fx = AudioEffects().apply {
            equalizer.setProfile(profile)
            equalizer.enabled = s.eqEnabled
            equalizer.setGainDb(gainDb)
        }
        // AAC encoders top out at 48 kHz; hi-res files are resampled after the effects.
        val processors = buildList<androidx.media3.common.audio.AudioProcessor> {
            add(EffectsAudioProcessor(fx))
            if (timeline.sampleRate > 48_000) add(SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) })
        }
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
        val video = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(backgroundPng)).setImageDurationMs(timeline.durationUs / 1000).build(),
        )
            .setDurationUs(timeline.durationUs)
            .setFrameRate(FPS)
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(video).build(),
            EditedMediaItemSequence.Builder(audio).build(),
        ).build()

        export(composition, out, aspect) { onProgress(0.25f + it * 0.73f) }
        val uri = withContext(Dispatchers.IO) { publish(out, FileNames.sanitize("${track.artist} - ${track.title}")) }
        work.deleteRecursively()
        onProgress(1f)
        return uri
    }

    private suspend fun export(composition: Composition, out: File, aspect: RenderAspect, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        coroutineScope {
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = launch {
                while (true) {
                    delay(400)
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val encoders = DefaultEncoderFactory.Builder(context)
                        // Mostly still pictures with a moving visualiser: 4 Mbit/s is crisp at 1080p and keeps a
                        // 4-minute video near 130 MB. Audio at 256 kbit/s AAC stays transparent.
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build())
                        .setRequestedAudioEncoderSettings(androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(256_000).build())
                        .build()
                    transformer = Transformer.Builder(context)
                        .setLooper(Looper.getMainLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoders)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer.start(composition, out.absolutePath)
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                }
            } finally {
                poll.cancel()
            }
        }
    }

    /** Copies the finished video into Movies/Palimpsest, where galleries and messengers find it. */
    private fun publish(video: File, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Palimpsest")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused the video")
        resolver.openOutputStream(uri)!!.use { o -> video.inputStream().use { it.copyTo(o) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Keeps the accent vivid on a dark backdrop (same idea as the app theme's readableAccent). */
    private fun vividAccent(rgb: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        if (hsv[1] < 0.18f) return 0xFFE4572E.toInt()
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.78f, 0.95f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    companion object {
        const val FPS = 30
    }
}
