package dev.xvin.palimpsest.data.remote

import android.util.Log
import dev.xvin.palimpsest.core.scrobble.ListenBrainz
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends recorded plays to ListenBrainz. Plays are queued in the database (scrobbled = 0), so
 * listening offline is fine: everything goes out the next time the network is there.
 */
class Scrobbler(private val db: AppDatabase, private val settings: Settings, private val lb: ListenBrainz) {
    private val mutex = Mutex()

    suspend fun flush() = mutex.withLock {
        val s = settings.state.value ?: return@withLock
        val token = s.listenBrainzToken ?: return@withLock
        // Older plays are left alone: they predate scrobbling being switched on.
        val pending = db.plays().unscrobbled(System.currentTimeMillis() - 14L * 24 * 3600 * 1000)
        if (pending.isEmpty()) return@withLock
        runCatching {
            pending.chunked(100).forEach { chunk ->
                lb.submit(
                    token,
                    chunk.map { ListenBrainz.Listen(it.artist, it.title, it.album, it.startedAt / 1000, durationMs = 0) },
                )
                db.plays().markScrobbled(chunk.map { it.id })
            }
        }.onFailure { Log.w("Scrobbler", "flush failed: ${it.message}") }
    }
}
