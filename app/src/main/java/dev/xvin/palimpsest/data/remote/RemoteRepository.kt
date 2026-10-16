package dev.xvin.palimpsest.data.remote

import dev.xvin.palimpsest.core.library.Versions
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.spotify.SpotifyImporter
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.AuthRequiredException
import dev.xvin.palimpsest.core.model.RadioBatch
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
    private val artistIndex: dev.xvin.palimpsest.data.library.ArtistIndex,
    private val onError: (Throwable, String) -> Unit = { _, _ -> },
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

    /**
     * Runs a Yandex call; if the token was rejected and we have a refresh token, renews the
     * token once and retries. Tokens live about a year, so this is rare but keeps sync silent.
     */
    private suspend fun <T> yandexCall(block: suspend () -> T): T = try {
        block()
    } catch (e: AuthRequiredException) {
        val refresh = settings.state.value?.yandexRefreshToken ?: throw e
        val t = yandexAuth.refresh(refresh)
        settings.setYandexAuth(t.access_token, t.refresh_token ?: refresh, settings.state.value?.yandexLogin)
        withRetryForToken { block() }
    }

    /** Likes locally and, for Yandex tracks, in the Yandex library too. */
    suspend fun setLiked(track: TrackEntity, liked: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        db.tracks().setLiked(track.id, liked)
        if (track.service != Service.YANDEX || track.remoteId == null || !yandex.isReady) return@withContext Result.success(Unit)
        runCatching { yandexCall { yandex.setLiked(track.remoteId, liked) } }
    }

    // --- Library rows for remote tracks ---------------------------------------------------------

    /**
     * Local files that aren't linked to any service yet, grouped by normalised title. Used to
     * recognise that a liked Yandex track is already on the phone (e.g. an MP3 from Murglar
     * without a URL tag) instead of adding a duplicate cloud copy.
     */
    private suspend fun unlinkedLocalIndex(): Map<String, List<TrackEntity>> =
        db.tracks().unlinkedLocal().groupBy { TrackMatcher.normalizeTitle(it.title) }

    private fun findLocalTwin(r: RemoteTrack, index: Map<String, List<TrackEntity>>): TrackEntity? =
        index[TrackMatcher.normalizeTitle(r.title)].orEmpty()
            .map { it to TrackMatcher.score(r.title, r.artistLine, r.durationMs, it.title, it.artist, it.durationMs) }
            .filter { it.second >= 0.85 }
            .maxByOrNull { it.second }?.first

    /** Finds or creates the library row for a service track. */
    suspend fun ensureTrack(
        r: RemoteTrack,
        liked: Boolean? = null,
        localIndex: Map<String, List<TrackEntity>>? = null,
    ): TrackEntity = withContext(Dispatchers.IO) {
        val dao = db.tracks()
        val twin = if (dao.byRemote(r.service, r.id) == null) findLocalTwin(r, localIndex ?: unlinkedLocalIndex()) else null
        if (twin != null) {
            // Link the existing file to the service track; the file itself stays untouched.
            val linked = twin.copy(
                service = r.service,
                remoteId = r.id,
                remoteAlbumId = r.albumId,
                coverUrl = twin.coverUrl ?: r.coverUrl,
                liked = liked ?: twin.liked,
            )
            dao.update(linked)
            artistIndex.setFromRemote(linked.id, r)
            return@withContext linked
        }
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
            artistIndex.setFromRemote(updated.id, r)
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
        e.copy(id = dao.insert(e)).also { artistIndex.setFromRemote(it.id, r) }
    }

    // --- Sync -------------------------------------------------------------------------------------

    suspend fun syncYandex(): Unit = withContext(Dispatchers.IO) {
        if (!yandex.isReady) return@withContext
        _status.value = SyncStatus(true, "Загружаю лайки…")
        try {
            val liked = yandexCall { yandex.likedTracks() }
            val likedIds = HashSet<Long>()
            var index = unlinkedLocalIndex()
            var linkedLocal = 0
            liked.forEachIndexed { i, r ->
                if (i % 50 == 0) _status.value = SyncStatus(true, "Лайки: $i / ${liked.size}")
                val row = ensureTrack(r, liked = true, localIndex = index)
                if (row.path != null && row.remoteId == r.id && index.values.any { list -> list.any { it.id == row.id } }) {
                    linkedLocal++
                    index = index.mapValues { (_, list) -> list.filter { it.id != row.id } }
                }
                likedIds += row.id
            }
            // Un-like tracks that were removed from likes on another device.
            db.tracks().linkedTo(Service.YANDEX).filter { it.liked && it.id !in likedIds }.forEach { db.tracks().setLiked(it.id, false) }

            _status.value = SyncStatus(true, "Загружаю плейлисты…")
            val playlists = yandexCall { yandex.playlists() }
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
            _status.value = SyncStatus(
                false,
                "Готово: ${liked.size} лайков, ${playlists.size} плейлистов" +
                    if (linkedLocal > 0) "; уже на телефоне нашлось $linkedLocal — не дублирую" else "",
            )
        } catch (e: Exception) {
            _status.value = SyncStatus(false, error = e.message ?: e.toString())
            onError(e, "Синхронизация с Яндексом")
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
            val remote = yandexCall { yandex.tracksByIds(ids) }.associateBy { it.id }
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
            onError(e, "Проверка новых версий")
            0
        }
    }

    /**
     * Imports a public Spotify playlist/album: each song is looked up on Yandex (if logged in)
     * and YouTube, the best match by title/artist/duration wins. Returns (matched, total).
     */
    suspend fun importSpotify(url: String, importer: SpotifyImporter): Pair<Int, Int> = withContext(Dispatchers.IO) {
        _status.value = SyncStatus(true, "Читаю Spotify…")
        try {
            val c = importer.fetch(url)
            val sources = listOfNotNull(yandex.takeIf { it.isReady }, providers[Service.YOUTUBE])
            val ids = ArrayList<Long>()
            c.items.forEachIndexed { i, item ->
                _status.value = SyncStatus(true, "Ищу ${i + 1}/${c.items.size}: ${item.artists} — ${item.title}")
                val match = sources.firstNotNullOfOrNull { p ->
                    runCatching { p.search("${item.artists} ${item.title}") }.getOrNull().orEmpty().take(8)
                        .map { it to TrackMatcher.score(item.title, item.artists, item.durationMs, it.title, it.artistLine, it.durationMs) }
                        .maxByOrNull { it.second }
                        ?.takeIf { it.second >= 0.6 }?.first
                }
                if (match != null) ids += ensureTrack(match).id
            }
            val existing = db.playlists().byRemote(Service.SPOTIFY, c.id)
            val pid = existing?.id ?: db.playlists().insert(
                PlaylistEntity(name = c.name, service = Service.SPOTIFY, remoteId = c.id, coverUrl = c.coverUrl),
            )
            db.playlists().replace(pid, ids)
            val missed = c.items.size - ids.size
            _status.value = SyncStatus(
                false,
                "«${c.name}»: найдено ${ids.size} из ${c.items.size}" + if (missed > 0) " (не нашлось: $missed)" else "",
            )
            ids.size to c.items.size
        } catch (e: Exception) {
            _status.value = SyncStatus(false, error = e.message ?: e.toString())
            onError(e, "Импорт из Spotify")
            0 to 0
        }
    }

    // --- Artist cards, albums, similar, radio (Yandex) ---------------------------------------------

    /** Artist page data: by known id, else by an id stored with the library's tracks, else by search. */
    suspend fun artistInfo(name: String, remoteIdHint: String?): ArtistInfo? = withContext(Dispatchers.IO) {
        if (!yandex.isReady) return@withContext null
        val id = remoteIdHint
            ?: db.artists().remoteIdOf(name)
            ?: yandexCall { yandex.searchArtist(name) }?.id
            ?: return@withContext null
        yandexCall { yandex.artistInfo(id) }
    }

    suspend fun album(albumId: String): AlbumWithTracks = withContext(Dispatchers.IO) { yandexCall { yandex.albumWithTracks(albumId) } }

    suspend fun similar(trackRemoteId: String): List<RemoteTrack> = withContext(Dispatchers.IO) { yandexCall { yandex.similarTracks(trackRemoteId) } }

    suspend fun radioStart(seeds: List<String>): RadioBatch = withContext(Dispatchers.IO) { yandexCall { yandex.radioStart(seeds) } }

    suspend fun waveStations(): List<dev.xvin.palimpsest.core.model.WaveStation> =
        withContext(Dispatchers.IO) { yandexCall { yandex.waveStations() } }

    suspend fun radioMore(sessionId: String, queue: List<String>): RadioBatch =
        withContext(Dispatchers.IO) { yandexCall { yandex.radioMore(sessionId, queue) } }

    suspend fun radioFeedback(sessionId: String, batchId: String?, type: String, trackId: String? = null, playedSec: Double? = null) {
        withContext(Dispatchers.IO) { runCatching { yandex.radioFeedback(sessionId, batchId, type, trackId, playedSec) } }
    }

    suspend fun search(service: Service, query: String): List<RemoteTrack> =
        providers[service]?.takeIf { it.isReady }?.search(query) ?: emptyList()
}
