package dev.xvin.palimpsest.playback

import dev.xvin.palimpsest.core.radio.LocalWave
import dev.xvin.palimpsest.core.radio.WaveCandidate
import dev.xvin.palimpsest.core.radio.WaveMoodKind
import dev.xvin.palimpsest.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The moods the offline wave offers (they need the audio analysed). */
typealias WaveMood = WaveMoodKind

/**
 * «Офлайн-волна»: your downloaded files as an endless station ([LocalWave] picks and learns; this
 * hands it what the library knows — plays, skips, likes, the audio analysis — and the bans).
 */
class OfflineWave(private val db: AppDatabase) {

    suspend fun candidates(): List<WaveCandidate> = withContext(Dispatchers.IO) {
        val tracks = db.tracks().all().filter { it.path != null && File(it.path).isFile }
        if (tracks.isEmpty()) return@withContext emptyList()
        val stats = db.plays().trackStats().associateBy { it.trackId }
        val skips = db.wave().skipStats(System.currentTimeMillis() - 90L * 86_400_000).associateBy { it.trackId }
        val artistsOf = db.artists().all().groupBy({ it.trackId }, { it.name })
        tracks.map { t ->
            val st = stats[t.id]
            val sk = skips[t.id]
            WaveCandidate(
                id = t.id.toString(), title = t.title, artists = artistsOf[t.id].orEmpty().ifEmpty { listOf(t.artist) },
                durationMs = t.durationMs, liked = t.liked,
                plays = st?.plays ?: 0, lastPlayedAt = st?.lastPlayed,
                skips = sk?.skips ?: 0, lastSkippedAt = sk?.lastSkipped,
                bpm = t.bpm, musicalKey = t.musicalKey, energy = t.energy,
            )
        }
    }

    /** A station over the library; [seeds] may carry `mood:CALM` and the like. */
    fun station(seeds: List<String>, novelty: () -> Float): LocalWave = LocalWave(
        candidates = ::candidates,
        banned = { db.wave().banKeys().toSet() },
        novelty = novelty,
        mood = seeds.firstNotNullOfOrNull { s -> s.removePrefix("mood:").takeIf { s.startsWith("mood:") }?.let { runCatching { WaveMoodKind.valueOf(it) }.getOrNull() } } ?: WaveMoodKind.ANY,
    )
}

fun moodLabel(m: WaveMood): Int = when (m) {
    WaveMood.CALM -> dev.xvin.palimpsest.R.string.mood_calm
    WaveMood.ENERGETIC -> dev.xvin.palimpsest.R.string.mood_energetic
    WaveMood.FOCUS -> dev.xvin.palimpsest.R.string.mood_focus
    WaveMood.SAD -> dev.xvin.palimpsest.R.string.mood_sad
    WaveMood.ANY -> dev.xvin.palimpsest.R.string.offline_wave
}
