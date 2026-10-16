package dev.xvin.palimpsest.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Year
import dev.xvin.palimpsest.i18n.tr

@Serializable
data class FoundRelease(val album: RemoteAlbum, val artist: String, val foundAt: Long)

@Serializable
private data class ReleaseState(
    val seen: Map<String, List<String>> = emptyMap(),
    val found: List<FoundRelease> = emptyList(),
    val lastCheck: Long = 0,
)

/**
 * Notices new albums/singles from the artists you actually keep in your library. The first pass
 * only remembers what exists (no flood of "new" releases); later passes notify about the rest.
 */
class ReleaseWatcher(
    private val context: Context,
    private val db: AppDatabase,
    private val yandex: YandexMusicProvider,
) {
    private val file = File(context.filesDir, "releases.json")

    private fun load(): ReleaseState = runCatching { Http.json.decodeFromString<ReleaseState>(file.readText()) }.getOrDefault(ReleaseState())
    private fun save(s: ReleaseState) = runCatching { file.writeText(Http.json.encodeToString(s)) }

    fun recent(): List<FoundRelease> = load().found

    suspend fun check(force: Boolean = false): List<FoundRelease> = withContext(Dispatchers.IO) {
        if (!yandex.isReady) return@withContext emptyList()
        var state = load()
        if (!force && System.currentTimeMillis() - state.lastCheck < 20 * 3600_000L) return@withContext emptyList()
        val watched = db.artists().topWithRemoteIds(40)
        val fresh = ArrayList<FoundRelease>()
        val seen = state.seen.toMutableMap()
        val thisYear = Year.now().value
        for (a in watched) {
            val info = runCatching { yandex.artistInfo(a.remoteId) }.getOrNull() ?: continue
            val ids = info.albums.map { it.id }
            val known = seen[a.remoteId]
            if (known != null) {
                info.albums.filter { it.id !in known && (it.year ?: thisYear) >= thisYear - 1 }
                    .forEach { fresh += FoundRelease(it, info.artist.name, System.currentTimeMillis()) }
            }
            seen[a.remoteId] = (known.orEmpty() + ids).distinct()
        }
        state = state.copy(seen = seen, found = (fresh + state.found).distinctBy { it.album.id }.take(60), lastCheck = System.currentTimeMillis())
        save(state)
        fresh.take(5).forEach(::notify)
        fresh
    }

    private fun notify(r: FoundRelease) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.new_releases), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, r.album.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALBUM, r.album.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kind = if (r.album.type == "single") tr(R.string.release_new_single) else tr(R.string.release_new)
        nm.notify(
            r.album.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$kind: ${r.artist}")
                .setContentText(listOfNotNull(r.album.title, r.album.year?.toString()).joinToString(" · "))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "releases"
    }
}
