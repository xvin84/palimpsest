package dev.xvin.palimpsest.data.remote

import dev.xvin.palimpsest.core.library.Versions
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.yandex.YDeviceCode
import dev.xvin.palimpsest.core.yandex.YandexAuth
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import dev.xvin.palimpsest.data.db.AppDatabase
import dev.xvin.palimpsest.data.db.PlaylistEntity
import dev.xvin.palimpsest.data.db.TrackEntity
import dev.xvin.palimpsest.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class SyncStatus(val running: Boolean = false, val message: String? = null, val error: String? = null)

/**
 * Glue between music services and the local library: login, syncing likes and playlists,
 * turning search results into library rows, and checking linked tracks for new versions.
 */
class RemoteRepository(
    private val db: AppDatabase,
    private val settings: Settings,
    private val yandexAuth: YandexAuth,
    val yandex: YandexMusicProvider,
    val providers: Map<Service, MusicProvider>,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    // --- Yandex login ---------------------------------------------------------------------------

    suspend fun startYandexLogin(): YDeviceCode = yandexAuth.requestDeviceCode("Palimpsest (Android)")

    /** Waits for the user to confirm [code] on ya.ru/device. Returns the account name on success. */
    suspend fun finishYandexLogin(code: YDeviceCode): String? {
        val token = yandexAuth.awaitToken(code) ?: return null
        return completeLogin(token.access_token, token.refresh_token)
    }

    /** Alternative: a token obtained through the WebView login or pasted by hand. */
    suspend fun loginWithToken(token: String): String? = completeLogin(token.trim(), null)

    private suspend fun completeLogin(token: String, refresh: String?): String? {
        settings.setYandexAuth(token, refresh, null)
        settings.state.value // ensure state updated before account() reads token
        return try {
            val acc = withRetryForToken { yandex.account() }
            val name = acc.login ?: acc.name ?: acc.uid.toString()
            settings.setYandexAuth(token, refresh, name)
            name
        } catch (e: Exception) {
            settings.setYandexAuth(null, null, null)
            throw e
        }
    }

    /** DataStore propagates asynchronously; give the hot state a moment to see the new token. */
    private suspend fun <T> withRetryForToken(block: suspend () -> T): T {
        repeat(20) {
            if (settings.state.value?.yandexToken != null) return block()
            kotlinx.coroutines.delay(50)
        }
        return block()
    }

    suspend fun logoutYandex() = settings.setYandexAuth(null, null, null)

    // --- Library rows for remote tracks ---------------------------------------------------------

    /** Finds or creates the library row for a service track. */
    suspend fun ensureTrack(r: RemoteTrack, liked: Boolean? = null): TrackEntity = withContext(Dispatchers.IO) {
        val dao = db.tracks()
        val existing = dao.byRemote(r.service, r.id)
        if (existing != null) {
            val updated = existing.copy(
                coverUrl = r.coverUrl ?: existing.coverUrl,
                liked = liked ?: existing.liked,
                // Fill gaps for stream-only rows; local files keep their own tags.
                title = if (existing.path == null) r.title else existing.title,
                artist = if (existing.path == null) r.artistLine.ifBlank { existing.artist } else existing.artist,
                album = if (existing.path == null) r.album ?: existing.album else existing.album,
                durationMs = if (existing.path == null) r.durationMs else existing.durationMs,
                remoteAlbumId = r.albumId ?: existing.remoteAlbumId,
            )
            if (updated != existing) dao.update(updated)
            return@withContext updated
        }
        val e = TrackEntity(
            path = null,
            title = r.title,
            artist = r.artistLine.ifBlank { "Неизвестный исполнитель" },
            album = r.album,
            albumArtist = null,
            year = r.year,
            genre = r.genre,
            trackNumber = r.trackNumber,
            discNumber = r.discNumber,
            durationMs = r.durationMs,
            folder = null,
            service = r.service,
            remoteId = r.id,
            remoteAlbumId = r.albumId,
            coverUrl = r.coverUrl,
            liked = liked ?: false,
        )
        e.copy(id = dao.insert(e))
    }

    // --- Sync -------------------------------------------------------------------------------------

    suspend fun syncYandex(): Unit = withContext(Dispatchers.IO) {
        if (!yandex.isReady) return@withContext
        _status.value = SyncStatus(true, "Загружаю лайки…")
        try {
            val liked = yandex.likedTracks()
            val likedIds = HashSet<Long>()
            liked.forEachIndexed { i, r ->
                if (i % 50 == 0) _status.value = SyncStatus(true, "Лайки: $i / ${liked.size}")
                likedIds += ensureTrack(r, liked = true).id
            }
            // Un-like tracks that were removed from likes on another device.
            db.tracks().linkedTo(Service.YANDEX).filter { it.liked && it.id !in likedIds }.forEach { db.tracks().setLiked(it.id, false) }

            _status.value = SyncStatus(true, "Загружаю плейлисты…")
            val playlists = yandex.playlists()
            for (p in playlists) {
                _status.value = SyncStatus(true, "Плейлист «${p.title}»")
                val tracks = runCatching { yandex.playlistTracks(p.id) }.getOrElse { continue }
                val ids = tracks.map { ensureTrack(it).id }
                val local = db.playlists().byRemote(Service.YANDEX, p.id)
                val pid = local?.id ?: db.playlists().insert(
                    PlaylistEntity(name = p.title, service = Service.YANDEX, remoteId = p.id, coverUrl = p.coverUrl),
                )
                if (local != null && local.name != p.title) db.playlists().rename(pid, p.title)
                db.playlists().replace(pid, ids)
            }
            // "Мне нравится" as a playlist too, newest first like in the official app.
            val likesPl = db.playlists().byRemote(Service.YANDEX, "likes")?.id
                ?: db.playlists().insert(PlaylistEntity(name = "Мне нравится · Яндекс", service = Service.YANDEX, remoteId = "likes"))
            db.playlists().replace(likesPl, liked.mapNotNull { db.tracks().byRemote(Service.YANDEX, it.id)?.id })
            _status.value = SyncStatus(false, "Готово: ${liked.size} лайков, ${playlists.size} плейлистов")
        } catch (e: Exception) {
            _status.value = SyncStatus(false, error = e.message ?: e.toString())
        }
    }

    /**
     * Compares downloaded Yandex tracks with what Yandex serves now. A different duration means
     * the artist re-uploaded the track: we flag it so the user can fetch the new version while
     * keeping the old one.
     */
    suspend fun checkForUpdates(): Int = withContext(Dispatchers.IO) {
        if (!yandex.isReady) return@withContext 0
        _status.value = SyncStatus(true, "Проверяю обновления треков…")
        try {
            val linked = db.tracks().linkedTo(Service.YANDEX).filter { it.path != null }
            val ids = linked.map { t -> t.remoteAlbumId?.let { "${t.remoteId}:$it" } ?: t.remoteId!! }
            val remote = yandex.tracksByIds(ids).associateBy { it.id }
            var changed = 0
            for (t in linked) {
                val r = remote[t.remoteId] ?: continue
                val diff = Versions.compare(t.durationMs, r.durationMs) == Versions.Change.DIFFERENT_DURATION
                if (diff) changed++
                if (diff != t.updateAvailable || t.remoteDurationMs != r.durationMs) {
                    db.tracks().update(t.copy(updateAvailable = diff, remoteDurationMs = r.durationMs))
                }
            }
            _status.value = SyncStatus(false, if (changed == 0) "Все треки актуальны" else "Изменилось треков: $changed")
            changed
        } catch (e: Exception) {
            _status.value = SyncStatus(false, error = e.message ?: e.toString())
            0
        }
    }

    suspend fun search(service: Service, query: String): List<RemoteTrack> =
        providers[service]?.takeIf { it.isReady }?.search(query) ?: emptyList()
}
