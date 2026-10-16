package dev.xvin.palimpsest.core.yandex

import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.AlbumWithTracks
import dev.xvin.palimpsest.core.model.ArtistInfo
import dev.xvin.palimpsest.core.model.AuthRequiredException
import dev.xvin.palimpsest.core.model.RadioBatch
import dev.xvin.palimpsest.core.model.RemoteAlbum
import dev.xvin.palimpsest.core.model.RemoteArtist
import dev.xvin.palimpsest.core.model.WaveStation
import dev.xvin.palimpsest.core.model.Lyrics
import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.ProviderException
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.model.RemotePlaylist
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.model.StreamInfo
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.net.fetch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Yandex Music through its private mobile/desktop API (the same one Murglar and
 * github.com/MarshalX/yandex-music-api use). Unofficial: Yandex can change it at any time.
 */
class YandexMusicProvider(
    private val token: () -> String?,
    private val http: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.music.yandex.net",
) : MusicProvider {
    override val service = Service.YANDEX
    override val isReady: Boolean get() = !token().isNullOrBlank()

    data class Account(val uid: Long, val login: String?, val name: String?, val hasPlus: Boolean)

    @Volatile private var cachedUid: Long? = null

    suspend fun account(): Account {
        val status: YStatus = get("/account/status")
        val acc = status.account
        val uid = acc.uid?.toLongOrNull() ?: throw AuthRequiredException(Service.YANDEX)
        cachedUid = uid
        return Account(uid, acc.login, acc.displayName ?: acc.fullName, status.plus?.hasPlus == true)
    }

    private suspend fun uid(): Long = cachedUid ?: account().uid

    override suspend fun search(query: String, page: Int): List<RemoteTrack> {
        val res: YSearch = get("/search", mapOf("text" to query, "type" to "track", "page" to page.toString(), "nocorrect" to "False"))
        return res.tracks?.results.orEmpty().map { it.toRemote() }
    }

    override suspend fun likedTracks(): List<RemoteTrack> {
        val likes: YLikes = get("/users/${uid()}/likes/tracks")
        val ids = likes.library.tracks.map { if (it.albumId != null) "${it.id}:${it.albumId}" else it.id }
        return tracksByIds(ids)
    }

    override suspend fun playlists(): List<RemotePlaylist> {
        val list: List<YPlaylist> = get("/users/${uid()}/playlists/list")
        return list.map { p ->
            RemotePlaylist(
                service = Service.YANDEX,
                id = "${p.owner?.uid ?: p.uid ?: uid()}:${p.kind}",
                title = p.title,
                owner = p.owner?.name ?: p.owner?.login,
                trackCount = p.trackCount,
                coverUrl = coverUrl(p.cover?.uri ?: p.cover?.itemsUri?.firstOrNull() ?: p.ogImage, 400),
            )
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack> {
        val (owner, kind) = playlistId.split(':').let { (if (it.size == 2) it[0] else uid().toString()) to it.last() }
        val p: YPlaylist = get("/users/$owner/playlists/$kind")
        val entries = p.tracks.orEmpty()
        val full = entries.mapNotNull { it.track?.toRemote() }
        if (full.size == entries.size) return full
        // Some responses only carry ids; fetch the rest in batches.
        return tracksByIds(entries.mapNotNull { it.track?.id ?: it.id })
    }

    override suspend fun track(id: String): RemoteTrack? = tracksByIds(listOf(id)).firstOrNull()

    // --- Artists, albums, similar ---------------------------------------------------------------

    suspend fun searchArtist(name: String): RemoteArtist? {
        val res: YSearchArtistsResult = get("/search", mapOf("text" to name, "type" to "artist", "page" to "0", "nocorrect" to "False"))
        val all = res.artists?.results.orEmpty().filter { it.id != null }
        val wanted = TrackMatcher.normalize(name)
        val best = all.firstOrNull { TrackMatcher.normalize(it.name) == wanted } ?: return null
        return best.toRemote()
    }

    suspend fun artistInfo(artistId: String): ArtistInfo {
        val b: YBriefInfo = get("/artists/$artistId/brief-info")
        return ArtistInfo(
            artist = b.artist.toRemote() ?: RemoteArtist(Service.YANDEX, artistId, b.artist.name),
            genres = b.artist.genres,
            description = b.artist.description?.text?.takeIf { it.isNotBlank() },
            likes = b.artist.likesCount,
            popular = b.popularTracks.map { it.toRemote() },
            albums = (b.albums + b.alsoAlbums).mapNotNull { it.toRemote() }.distinctBy { it.id },
            similar = b.similarArtists.mapNotNull { it.toRemote() },
        )
    }

    suspend fun albumWithTracks(albumId: String): AlbumWithTracks {
        val a: YAlbumFull = get("/albums/$albumId/with-tracks")
        val album = a.toRemote() ?: throw ProviderException("Album $albumId not found")
        // Tracks inside an album often omit the album block; fill it in so tags/covers are right.
        val tracks = a.volumes.orEmpty().flatten().map { t ->
            val r = t.toRemote()
            r.copy(
                album = r.album ?: album.title,
                albumId = r.albumId ?: album.id,
                coverUrl = r.coverUrl ?: album.coverUrl,
                year = r.year ?: album.year,
                webUrl = YandexLinks.trackUrl(r.albumId ?: album.id, r.id),
            )
        }
        return AlbumWithTracks(album, tracks)
    }

    suspend fun similarTracks(trackId: String): List<RemoteTrack> {
        val s: YSimilar = get("/tracks/${trackId.substringBefore(':')}/similar")
        return s.similarTracks.map { it.toRemote() }
    }

    // --- Radio (rotor sessions) -------------------------------------------------------------------

    /**
     * Starts a radio session. Seeds: `user:onyourwave` («Моя волна»), `track:<id>`, `artist:<id>`,
     * `album:<id>`, `genre:<name>`.
     */
    suspend fun radioStart(seed: String, queue: List<String> = emptyList()): RadioBatch = radioStart(listOf(seed), queue)

    /** Several seeds at once, e.g. «Моя волна» plus `settingMoodEnergy:calm`. */
    suspend fun radioStart(seeds: List<String>, queue: List<String> = emptyList()): RadioBatch {
        val body = buildJsonObject {
            put("seeds", buildJsonArray { seeds.forEach { add(JsonPrimitive(it)) } })
            put("includeTracksInResponse", true)
            if (queue.isNotEmpty()) put("queue", buildJsonArray { queue.forEach { add(JsonPrimitive(it)) } })
        }
        val r: YRotorSession = postJson("/rotor/session/new", body)
        val id = r.radioSessionId ?: throw ProviderException("Radio: no session was created")
        return RadioBatch(id, r.batchId, r.sequence.mapNotNull { it.track?.toRemote() })
    }

    /** Stations shown in the wave settings (activities, moods, …). */
    suspend fun waveStations(): List<WaveStation> {
        val w: YWaveSettings = get("/rotor/wave/settings", mapOf("seeds" to "user:onyourwave"))
        return w.blocks.flatMap { b ->
            b.items.mapNotNull { st ->
                val id = st.id ?: return@mapNotNull null
                if (id.type.isBlank() || id.tag.isBlank()) return@mapNotNull null
                WaveStation("${id.type}:${id.tag}", st.name, b.type, st.icon?.backgroundColor, st.icon?.imageUrl?.let { coverUrl(it, 200) })
            }
        }.distinctBy { it.seed }
    }

    /** Next batch; [queue] = tracks already received, so the station doesn't repeat them. */
    suspend fun radioMore(sessionId: String, queue: List<String>): RadioBatch {
        val body = buildJsonObject { put("queue", buildJsonArray { queue.takeLast(50).forEach { add(JsonPrimitive(it)) } }) }
        val r: YRotorSession = postJson("/rotor/session/$sessionId/tracks", body)
        return RadioBatch(sessionId, r.batchId, r.sequence.mapNotNull { it.track?.toRemote() })
    }

    /**
     * Listening feedback that tunes the station: `radioStarted`, `trackStarted`, `trackFinished`,
     * `skip`. Best effort — failures are ignored by callers.
     */
    suspend fun radioFeedback(sessionId: String, batchId: String?, type: String, trackId: String? = null, playedSec: Double? = null) {
        val ts = java.time.Instant.now().toString()
        val body = buildJsonObject {
            put(
                "event",
                buildJsonObject {
                    put("type", type)
                    put("timestamp", ts)
                    trackId?.let { put("trackId", it) }
                    playedSec?.let { put("totalPlayedSeconds", it) }
                },
            )
            batchId?.let { put("batchId", it) }
            put("from", "radio-mobile-user-onyourwave")
        }
        postJson<JsonElement>("/rotor/session/$sessionId/feedback", body)
    }

    /** Likes or un-likes a track in the user's Yandex library ("Мне нравится"). */
    suspend fun setLiked(trackId: String, liked: Boolean) {
        val action = if (liked) "add-multiple" else "remove"
        val form = FormBody.Builder().add("track-ids", trackId).build()
        post<JsonElement>("/users/${uid()}/likes/tracks/$action", form)
    }

    suspend fun tracksByIds(ids: List<String>): List<RemoteTrack> {
        val out = ArrayList<RemoteTrack>(ids.size)
        for (chunk in ids.chunked(200)) {
            val form = FormBody.Builder()
                .add("track-ids", chunk.joinToString(","))
                .add("with-positions", "True")
                .build()
            val tracks: List<YTrack> = post("/tracks", form)
            tracks.mapTo(out) { it.toRemote() }
        }
        return out
    }

    override suspend fun resolveStream(id: String, quality: Quality, forDownload: Boolean): StreamInfo {
        val q = when (quality) {
            Quality.LOSSLESS -> "lossless"
            Quality.HIGH -> "hq"
            Quality.NORMAL -> "nq"
        }
        // Prefer the modern endpoint (gives FLAC); fall back to the legacy MP3 one.
        val modern = runCatching { fileInfo(id, q, "raw") }.recoverCatching { fileInfo(id, q, "encraw") }
        modern.getOrNull()?.let { return it }
        return runCatching { legacyDownload(id) }.getOrElse {
            throw ProviderException("Could not get a link for track $id: ${modern.exceptionOrNull()?.message ?: it.message}", it)
        }
    }

    private suspend fun fileInfo(trackId: String, quality: String, transport: String): StreamInfo {
        val ts = System.currentTimeMillis() / 1000
        val codecs = FILE_INFO_CODECS
        val message = "$ts$trackId$quality${codecs.joinToString("")}$transport"
        val sign = hmacBase64(DESKTOP_SIGN_KEY, message).dropLast(1)
        val info: YFileInfo = get(
            "/get-file-info",
            mapOf(
                "ts" to ts.toString(),
                "trackId" to trackId,
                "quality" to quality,
                "codecs" to codecs.joinToString(","),
                "transports" to transport,
                "sign" to sign,
            ),
            extraHeaders = mapOf("X-Yandex-Music-Client" to DESKTOP_CLIENT),
        )
        val d = info.downloadInfo ?: throw ProviderException("get-file-info: empty response")
        val url = d.url ?: d.urls.firstOrNull() ?: throw ProviderException("get-file-info: no link")
        val key = d.key?.takeIf { d.transport == "encraw" && it.isNotBlank() }?.let(::hexToBytes)
        return StreamInfo(url = url, codec = d.codec, bitrateKbps = d.bitrate, aesCtrKey = key)
    }

    private suspend fun legacyDownload(trackId: String): StreamInfo {
        val infos: List<YLegacyDownloadInfo> = get("/tracks/$trackId/download-info")
        val best = infos.filter { !it.preview }.maxByOrNull { it.bitrateInKbps }
            ?: throw ProviderException("Track is not available for download")
        val xml = http.newCall(Request.Builder().url(best.downloadInfoUrl).build()).fetch().body
        fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)
            ?: throw ProviderException("download-info: no field $name")
        val host = tag("host"); val path = tag("path"); val ts = tag("ts"); val s = tag("s")
        val sign = md5Hex("XGRlBW9FXlekgbPrRHuSiA" + path.substring(1) + s)
        return StreamInfo(url = "https://$host/get-mp3/$sign/$ts$path", codec = best.codec, bitrateKbps = best.bitrateInKbps)
    }

    override suspend fun lyrics(id: String): Lyrics? {
        suspend fun fetch(format: String): String? = runCatching {
            val numericId = id.substringBefore(':')
            val ts = System.currentTimeMillis() / 1000
            val sign = hmacBase64(ANDROID_SIGN_KEY, "$numericId$ts")
            val res: YLyrics = get("/tracks/$numericId/lyrics", mapOf("format" to format, "timeStamp" to ts.toString(), "sign" to sign))
            val url = res.downloadUrl ?: return@runCatching null
            http.newCall(Request.Builder().url(url).build()).fetch().let { if (it.isSuccessful) it.body else null }
        }.getOrNull()
        val synced = fetch("LRC")
        val plain = if (synced == null) fetch("TEXT") else null
        if (synced == null && plain == null) return null
        return Lyrics(plain = plain, synced = synced, source = "Yandex Music")
    }

    // --- HTTP plumbing -------------------------------------------------------------------------

    private suspend inline fun <reified T> get(
        path: String,
        params: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): T {
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return execute(Request.Builder().url(url).get(), extraHeaders)
    }

    private suspend inline fun <reified T> post(path: String, body: FormBody): T =
        execute(Request.Builder().url("$baseUrl$path").post(body), emptyMap())

    private suspend inline fun <reified T> postJson(path: String, body: JsonObject): T =
        execute(Request.Builder().url("$baseUrl$path").post(body.toString().toRequestBody("application/json".toMediaType())), emptyMap())

    private suspend inline fun <reified T> execute(builder: Request.Builder, extraHeaders: Map<String, String>): T {
        val tok = token() ?: throw AuthRequiredException(Service.YANDEX)
        builder.header("Authorization", "OAuth $tok")
            .header("Accept-Language", "ru")
            .header("X-Yandex-Music-Client", ANDROID_CLIENT)
            .header("User-Agent", "Yandex-Music-API")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val resp = http.newCall(builder.build()).fetch()
        val text = resp.body
        if (resp.code == 401 || resp.code == 403) throw AuthRequiredException(Service.YANDEX)
        val env = runCatching { Http.json.decodeFromString<YEnvelope>(text) }.getOrNull()
        if (!resp.isSuccessful || env?.result == null) {
            val msg = (env?.error as? JsonObject)?.let { it["message"] ?: it["name"] }?.jsonPrimitive?.content
            throw ProviderException("Yandex ${resp.code}: ${msg ?: text.take(200)}")
        }
        return Http.json.decodeFromJsonElement<T>(env.result as JsonElement)
    }

    // --- Mapping --------------------------------------------------------------------------------

    private fun YTrack.toRemote(): RemoteTrack {
        val album = albums.firstOrNull()
        val fullTitle = if (!version.isNullOrBlank()) "$title ($version)" else title
        val albumId = album?.id
        return RemoteTrack(
            service = Service.YANDEX,
            id = id,
            title = fullTitle,
            artists = artists.map { it.name }.filter { it.isNotBlank() },
            album = album?.title,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl(coverUri ?: album?.coverUri, 1000),
            year = album?.year,
            genre = album?.genre,
            trackNumber = album?.trackPosition?.index,
            discNumber = album?.trackPosition?.volume,
            available = available,
            hasLyrics = lyricsInfo?.hasAvailableTextLyrics == true || lyricsInfo?.hasAvailableSyncLyrics == true,
            hasSyncedLyrics = lyricsInfo?.hasAvailableSyncLyrics == true,
            webUrl = YandexLinks.trackUrl(albumId, id),
            artistIds = artists.filter { it.name.isNotBlank() }.map { it.id },
        )
    }

    private fun YArtistFull.toRemote(): RemoteArtist? {
        val id = id ?: return null
        val uri = cover?.uri ?: ogImage
        return RemoteArtist(Service.YANDEX, id, name, coverUrl(uri, 600))
    }

    private fun YAlbumFull.toRemote(): RemoteAlbum? {
        val id = id ?: return null
        return RemoteAlbum(Service.YANDEX, id, title, artists.map { it.name }, year, coverUrl(coverUri, 600), trackCount, type)
    }

    companion object {
        private const val ANDROID_CLIENT = "YandexMusicAndroid/24023621"
        private const val DESKTOP_CLIENT = "YandexMusicDesktopAppWindows/5.95.0"
        private const val ANDROID_SIGN_KEY = "p93jhgh689SBReK6ghtw62"
        private const val DESKTOP_SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"
        private val FILE_INFO_CODECS = listOf("flac", "flac-mp4", "aac-mp4", "he-aac-mp4", "aac", "he-aac", "mp3")

        fun coverUrl(uri: String?, size: Int): String? =
            uri?.takeIf { it.isNotBlank() }?.let { "https://" + it.removePrefix("https://").replace("%%", "${size}x$size") }

        internal fun hmacBase64(key: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
        }

        internal fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
