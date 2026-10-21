package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.library.ArtistNames
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One track, local (a file) or from a service (streamed), or both. [id] is stable: the file path
 * for local-only tracks, `SERVICE:remoteId` for service tracks (a downloaded one keeps it).
 */
@Serializable
data class Track(
    val id: String,
    val path: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long = 0,
    val service: Service = Service.LOCAL,
    val remoteId: String? = null,
    val remoteAlbumId: String? = null,
    val coverUrl: String? = null,
    val rgTrackDb: Float? = null,
    val rgAlbumDb: Float? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val folder: String? = null,
) {
    val isLocal get() = path != null
    val artists: List<String> get() = ArtistNames.split(artist)
    val albumKey get() = (albumArtist ?: artist) + "\u0000" + album.orEmpty()
}

@Serializable
private data class LibraryFile(val tracks: List<Track> = emptyList(), val liked: Set<String> = emptySet())

data class ScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val error: String? = null)

/**
 * The library: files under the music folders (incremental — a file is re-read only when its size
 * or date changed), plus service tracks that were played, liked or downloaded. Persisted as JSON.
 */
class LibraryStore(private val settings: SettingsStore) {
    private companion object {
        /** Held strongly: java.util.logging keeps loggers weakly, and a collected one forgets its level. */
        val quiet: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }
    }

    init { quiet.level = Level.OFF }

    private val file = JsonFile(File(Paths.config, "library.json"), LibraryFile.serializer()) { LibraryFile() }
    private val loaded = file.load()
    private val _tracks = MutableStateFlow(loaded.tracks)
    val tracks: StateFlow<List<Track>> = _tracks
    private val _liked = MutableStateFlow(loaded.liked)
    val liked: StateFlow<Set<String>> = _liked
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress
    private val mutex = Mutex()

    private fun persist() = file.save(LibraryFile(_tracks.value, _liked.value))

    fun get(id: String) = _tracks.value.firstOrNull { it.id == id }

    suspend fun scan(force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _progress.value = ScanProgress(running = true)
                val roots = settings.value.musicRoots.map(::File).filter { it.isDirectory }
                val files = roots.flatMap { root ->
                    root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }
                        .filter { it.isFile && !it.name.startsWith(".") && FileNames.isAudio(it.name) }.map { root to it }.toList()
                }
                val byPath = _tracks.value.filter { it.path != null }.associateBy { it.path!! }
                val todo = files.filter { (_, f) ->
                    val t = byPath[f.absolutePath]
                    force || t == null || t.sizeBytes != f.length() || t.modifiedAt != f.lastModified()
                }
                _progress.value = ScanProgress(true, 0, todo.size)
                var done = 0
                val io = Dispatchers.IO.limitedParallelism(6)
                val read = coroutineScope {
                    todo.chunked(64).flatMap { chunk ->
                        chunk.map { (root, f) -> async(io) { runCatching { read(root, f) }.getOrNull() } }.awaitAll().filterNotNull()
                            .also { done += chunk.size; _progress.value = ScanProgress(true, done, todo.size) }
                    }
                }
                val seen = files.mapTo(HashSet()) { it.second.absolutePath }
                val merged = LinkedHashMap<String, Track>()
                // Keep service tracks; drop files that are gone (a downloaded service track stays as a stream).
                _tracks.value.forEach { t ->
                    when {
                        t.path == null -> merged[t.id] = t
                        t.path in seen -> merged[t.id] = t
                        t.remoteId != null -> merged[t.id] = t.copy(path = null)
                    }
                }
                read.forEach { n ->
                    // A file of a known service track joins it instead of becoming a second track.
                    val key = if (n.remoteId != null) "${n.service}:${n.remoteId}" else n.id
                    val old = merged[key] ?: merged.values.firstOrNull { it.path == n.path }
                    if (old != null) merged.remove(old.id)
                    merged[key] = n.copy(id = key, addedAt = old?.addedAt ?: n.addedAt, coverUrl = n.coverUrl ?: old?.coverUrl)
                }
                _tracks.value = merged.values.toList()
                persist()
                _progress.value = ScanProgress(false, todo.size, todo.size)
            } catch (e: Exception) {
                _progress.value = ScanProgress(false, error = e.message ?: e.toString())
            }
        }
    }

    private fun read(root: File, f: File): Track {
        val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')
        val guess = FileNames.guess(rel)
        val folder = rel.substringBeforeLast('/', "").substringBeforeLast('\\', "").ifEmpty { null }
        val base = Track(
            id = f.absolutePath, path = f.absolutePath, title = guess.title, artist = guess.artist ?: "—", album = guess.album,
            year = guess.year, trackNumber = guess.trackNumber, sizeBytes = f.length(), modifiedAt = f.lastModified(), folder = folder,
        )
        return runCatching {
            val af = AudioFileIO.read(f)
            val tag = af.tag
            fun t(k: FieldKey) = runCatching { tag?.getFirst(k) }.getOrNull()?.takeIf { it.isNotBlank() }
            fun custom(name: String) = runCatching { tag?.getFirst(name) }.getOrNull()?.takeIf { it.isNotBlank() }
            val url = t(FieldKey.URL_OFFICIAL_RELEASE_SITE) ?: custom("URL") ?: custom("WWW")
            val service = FileNames.serviceFor(rel, url)
            val yandex = YandexLinks.parse(url)
            val remoteId = when (service) {
                Service.YANDEX -> yandex?.second
                Service.YOUTUBE -> guess.youtubeId ?: url?.let { Regex("""(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})""").find(it)?.groupValues?.get(1) }
                Service.BANDCAMP -> url?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
                else -> null
            }
            fun gain(name: String) = custom(name)?.substringBefore(" dB")?.trim()?.replace(',', '.')?.toFloatOrNull()
            val h = af.audioHeader
            base.copy(
                title = t(FieldKey.TITLE) ?: guess.title,
                artist = t(FieldKey.ARTIST) ?: guess.artist ?: "—",
                album = t(FieldKey.ALBUM) ?: guess.album,
                albumArtist = t(FieldKey.ALBUM_ARTIST),
                year = t(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: guess.year,
                trackNumber = t(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull() ?: guess.trackNumber,
                durationMs = (h.preciseTrackLength * 1000).toLong(),
                service = service, remoteId = remoteId, remoteAlbumId = yandex?.first,
                rgTrackDb = gain("REPLAYGAIN_TRACK_GAIN"), rgAlbumDb = gain("REPLAYGAIN_ALBUM_GAIN"),
                codec = h.format?.substringBefore(' ')?.lowercase(), bitrateKbps = h.bitRateAsNumber.toInt().takeIf { it > 0 },
            )
        }.getOrElse { base }
    }

    /** A service track as a library entry (created on first play, like or download). */
    fun ensureRemote(r: RemoteTrack): Track {
        val id = "${r.service}:${r.id}"
        get(id)?.let { return it }
        val t = Track(
            id = id, title = r.title, artist = r.artistLine.ifBlank { "—" }, album = r.album, year = r.year, trackNumber = r.trackNumber,
            durationMs = r.durationMs, service = r.service, remoteId = r.id, remoteAlbumId = r.albumId, coverUrl = r.coverUrl,
        )
        _tracks.value = _tracks.value + t
        persist()
        return t
    }

    fun upsert(t: Track) {
        _tracks.value = _tracks.value.filterNot { it.id == t.id } + t
        persist()
    }

    fun remove(id: String) {
        _tracks.value = _tracks.value.filterNot { it.id == id }
        _liked.value = _liked.value - id
        persist()
    }

    fun setLiked(id: String, liked: Boolean) {
        _liked.value = if (liked) _liked.value + id else _liked.value - id
        persist()
    }

    /** Replaces the liked set of one service with what the service says (sync). */
    fun syncLikes(service: Service, tracks: List<RemoteTrack>) {
        val ids = tracks.map { ensureRemote(it).id }.toSet()
        _liked.value = _liked.value.filterNot { it.startsWith("$service:") }.toSet() + ids
        persist()
    }
}
