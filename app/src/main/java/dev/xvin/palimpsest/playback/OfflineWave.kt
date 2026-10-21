package dev.xvin.palimpsest.playback

import dev.xvin.palimpsest.core.analysis.AudioFeatures
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * «Офлайн-волна»: an endless-feeling mix built only from downloaded files, tuned by your own
 * history — favourites and favourite artists more often, just-heard tracks less, and a share of
 * never-played tracks so the library keeps surprising you. With the audio analysed it also follows a
 * [WaveMood] and orders the mix so tempo, key and energy flow from one track into the next.
 */
class OfflineWave(private val db: AppDatabase) {

    suspend fun build(count: Int = 60, random: Random = Random.Default, mood: WaveMood = WaveMood.ANY): List<TrackEntity> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val tracks = db.tracks().all().filter { it.path != null && File(it.path).isFile }
        if (tracks.isEmpty()) return@withContext emptyList()
        val stats = db.plays().trackStats().associateBy { it.trackId }
        val artistsOf = db.artists().all().groupBy({ it.trackId }, { it.name.lowercase() })

        // How much you listen to each artist (recent-ish weight via play counts).
        val affinity = HashMap<String, Double>()
        for ((tid, st) in stats) artistsOf[tid].orEmpty().forEach { affinity[it] = (affinity[it] ?: 0.0) + st.plays }
        val maxAffinity = affinity.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

        fun weight(t: TrackEntity): Double {
            val st = stats[t.id]
            val since = st?.let { now - it.lastPlayed } ?: Long.MAX_VALUE
            if (since < 6 * 3600_000L) return 0.0 // heard in the last hours — skip
            val recency = if (since < 2 * 86_400_000L) 0.25 else 1.0
            val artist = artistsOf[t.id].orEmpty().maxOfOrNull { affinity[it] ?: 0.0 } ?: 0.0
            val base = if (st == null) 0.6 else 0.4 + ln(1.0 + st.plays)
            return (base + (if (t.liked) 1.5 else 0.0) + 2.0 * artist / maxAffinity) * recency * mood.fit(t) * (0.6 + random.nextDouble() * 0.8)
        }

        // Weighted sampling without replacement (Efraimidis–Spirakis).
        val picked = tracks.mapNotNull { t -> weight(t).takeIf { it > 0 }?.let { w -> t to Math.pow(random.nextDouble(), 1.0 / w) } }
            .sortedByDescending { it.second }.take(count).map { it.first }.toMutableList()

        if (mood != WaveMood.ANY && picked.isEmpty()) return@withContext emptyList()
        val ordered = flow(picked, random)
        picked.clear(); picked.addAll(ordered)

        // Avoid the same artist twice in a row.
        for (i in 1 until picked.size) {
            if (picked[i].artist.equals(picked[i - 1].artist, true)) {
                val j = (i + 1 until picked.size).firstOrNull { !picked[it].artist.equals(picked[i - 1].artist, true) } ?: continue
                picked[i] = picked[j].also { picked[j] = picked[i] }
            }
        }
        picked
    }

    /**
     * Greedy chain through the picks: each next track is the smoothest of a few still-likely ones,
     * so the mix keeps your taste's order roughly but avoids jarring jumps. Without analysis the
     * order stays as picked.
     */
    private fun flow(picked: List<TrackEntity>, random: Random): List<TrackEntity> {
        if (picked.count { it.bpm != null && it.bpm > 0 } < picked.size / 2) return picked
        val left = picked.toMutableList()
        val out = ArrayList<TrackEntity>(picked.size)
        out += left.removeAt(0)
        while (left.isNotEmpty()) {
            val a = out.last()
            val window = left.take(LOOKAHEAD)
            val next = window.minBy { b ->
                AudioFeatures.transitionCost(a.bpm, a.musicalKey, a.energy, b.bpm, b.musicalKey, b.energy) +
                    left.indexOf(b) * 0.15 + random.nextDouble() * 0.3
            }
            left.remove(next); out += next
        }
        return out
    }

    private companion object { const val LOOKAHEAD = 6 }
}

/** What the offline wave should feel like; tracks that don't fit are rare, not banned. */
enum class WaveMood {
    ANY, CALM, ENERGETIC, FOCUS, SAD;

    fun fit(t: TrackEntity): Double {
        if (this == ANY) return 1.0
        val bpm = t.bpm?.takeIf { it > 0 } ?: return 0.25
        val e = t.energy ?: 0.5f
        val minor = t.musicalKey?.endsWith("A") == true
        val score = when (this) {
            CALM -> (1 - e) * 1.6 + (if (bpm < 100) 0.6 else if (bpm < 115) 0.2 else 0.0)
            ENERGETIC -> e * 1.6 + (if (bpm in 118f..175f) 0.6 else 0.0)
            FOCUS -> (1 - abs(e - 0.55) * 3).coerceAtLeast(0.0) + (if (bpm in 85f..125f) 0.6 else 0.0)
            SAD -> (if (minor) 0.9 else 0.0) + (1 - e) * 0.9 + (if (bpm < 105) 0.4 else 0.0)
            ANY -> 1.0
        }
        return (score * score).coerceAtLeast(0.02)
    }
}
