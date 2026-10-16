package dev.xvin.palimpsest.playback

import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.random.Random

/**
 * «Офлайн-волна»: an endless-feeling mix built only from downloaded files, tuned by your own
 * history — favourites and favourite artists more often, just-heard tracks less, and a share of
 * never-played tracks so the library keeps surprising you.
 */
class OfflineWave(private val db: AppDatabase) {

    suspend fun build(count: Int = 60, random: Random = Random.Default): List<TrackEntity> = withContext(Dispatchers.IO) {
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
            return (base + (if (t.liked) 1.5 else 0.0) + 2.0 * artist / maxAffinity) * recency * (0.6 + random.nextDouble() * 0.8)
        }

        // Weighted sampling without replacement (Efraimidis–Spirakis).
        val picked = tracks.mapNotNull { t -> weight(t).takeIf { it > 0 }?.let { w -> t to Math.pow(random.nextDouble(), 1.0 / w) } }
            .sortedByDescending { it.second }.take(count).map { it.first }.toMutableList()

        // Avoid the same artist twice in a row.
        for (i in 1 until picked.size) {
            if (picked[i].artist.equals(picked[i - 1].artist, true)) {
                val j = (i + 1 until picked.size).firstOrNull { !picked[it].artist.equals(picked[i - 1].artist, true) } ?: continue
                picked[i] = picked[j].also { picked[j] = picked[i] }
            }
        }
        picked
    }
}
