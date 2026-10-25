package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.audio.FlacMp4
import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.yandex.FlacLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File

/** A FLAC found for a lossy file, downloaded and waiting for «same song?». */
@Serializable
data class FlacCandidate(
    val trackPath: String,
    val title: String,
    val artist: String,
    val remoteId: String,
    val flacPath: String,
    val oldCodec: String? = null,
    val oldBitrateKbps: Int? = null,
    val oldSize: Long = 0,
    val newSize: Long = 0,
)

@Serializable
private data class FlacFile(val candidates: List<FlacCandidate> = emptyList(), val rejected: Set<String> = emptySet(), val folders: List<String> = emptyList())

data class FlacSearchState(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    val lossyOnly: Int = 0,
    val notFound: Int = 0,
    val current: String? = null,
    /** "yandex": not signed in; "plus": no Yandex Plus, so no FLAC. */
    val error: String? = null,
)

/**
 * «Перевести в FLAC» on the computer: for every MP3/AAC/Opus… file in the chosen folders, looks
 * for the same song in FLAC on Yandex Music ([FlacLookup], shared with the phone), downloads it
 * with the file's tags and cover, and keeps it waiting. Nothing changes until the user listens and
 * says it's the one: then the FLAC takes the file's place and the old file goes to
 * `.palimpsest/replaced` in its folder, so it can be put back.
 */
class FlacUpgrade(private val graph: Graph) {
    private val store = JsonFile(File(Paths.config, "flac_upgrade.json"), FlacFile.serializer()) { FlacFile() }
    private var data = store.load()
    private val dir = File(Paths.config, "flac").apply { mkdirs() }
    private val lookup = FlacLookup(graph.yandex)

    private val _candidates = MutableStateFlow(data.candidates.filter { File(it.flacPath).isFile && File(it.trackPath).isFile })
    val candidates: StateFlow<List<FlacCandidate>> = _candidates
    private val _state = MutableStateFlow(FlacSearchState())
    val state: StateFlow<FlacSearchState> = _state
    private val _folders = MutableStateFlow(data.folders.ifEmpty { graph.settings.value.musicRoots })
    /** Folders to look in (the music folders until the user picks others). */
    val folders: StateFlow<List<String>> = _folders
    private var job: Job? = null

    @Synchronized
    private fun persist() {
        data = data.copy(candidates = _candidates.value, folders = _folders.value)
        store.save(data)
    }

    fun setFolders(list: List<String>) { _folders.value = list.distinct(); persist() }

    /** Lossy local files under [folders] that have no FLAC waiting already. */
    fun lossyTracks(folders: List<String> = _folders.value): List<Track> {
        val roots = folders.map { File(it).absolutePath.trimEnd(File.separatorChar) + File.separator }
        val waiting = _candidates.value.mapTo(HashSet()) { it.trackPath }
        return graph.library.tracks.value.filter { t ->
            val p = t.path ?: return@filter false
            p !in waiting && roots.any { p.startsWith(it) } && isLossy(t) && File(p).isFile
        }
    }

    fun start() {
        if (job?.isActive == true) return
        job = graph.scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() { job?.cancel() }

    private suspend fun run() {
        if (!graph.yandex.isReady) { _state.value = FlacSearchState(error = "yandex"); return }
        if (lookup.accountHasPlus() == false) { _state.value = FlacSearchState(error = "plus"); return }
        val todo = lossyTracks()
        _state.value = FlacSearchState(running = true, total = todo.size)
        try {
            todo.forEachIndexed { i, t ->
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(checked = i, current = "${t.artist} — ${t.title}")
                val rejected = data.rejected.filter { it.startsWith(t.path + "\t") }.mapTo(HashSet()) { it.substringAfter('\t') }
                val linked = t.remoteId?.takeIf { t.service == dev.xvin.palimpsest.core.model.Service.YANDEX }
                when (val m = lookup.find(t.title, t.artist, t.durationMs, linked, rejected)) {
                    is FlacLookup.Match.Flac -> {
                        val ok = try {
                            download(t, m.remoteId)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            System.err.println("FLAC for ${t.path}: $e")
                            false
                        }
                        _state.value = if (ok) _state.value.copy(found = _state.value.found + 1) else _state.value.copy(notFound = _state.value.notFound + 1)
                    }
                    FlacLookup.Match.LossyOnly -> _state.value = _state.value.copy(lossyOnly = _state.value.lossyOnly + 1)
                    FlacLookup.Match.NotFound -> _state.value = _state.value.copy(notFound = _state.value.notFound + 1)
                }
                delay(250) // be gentle with search
            }
        } finally {
            _state.value = _state.value.copy(running = false, checked = _state.value.total.coerceAtMost(_state.value.checked + 1), current = null)
        }
    }

    /**
     * One track from its menu: looks for its FLAC and, when there is one, downloads it to wait on the
     * «Upgrade to FLAC» screen. Null with the reason in [FlacSearchState.error] terms ("yandex", "plus").
     */
    suspend fun findOne(t: Track): Pair<FlacLookup.Match?, String?> = withContext(Dispatchers.IO) {
        if (!graph.yandex.isReady) return@withContext null to "yandex"
        if (lookup.accountHasPlus() == false) return@withContext null to "plus"
        val rejected = data.rejected.filter { it.startsWith(t.path + "\t") }.mapTo(HashSet()) { it.substringAfter('\t') }
        val linked = t.remoteId?.takeIf { t.service == dev.xvin.palimpsest.core.model.Service.YANDEX }
        val m = lookup.find(t.title, t.artist, t.durationMs, linked, rejected)
        if (m is FlacLookup.Match.Flac) download(t, m.remoteId)
        m to null
    }

    private suspend fun download(t: Track, remoteId: String) = withContext(Dispatchers.IO) {
        val info = graph.yandex.resolveStream(remoteId, Quality.LOSSLESS, forDownload = true)
        val base = "${(t.path + remoteId).hashCode().toUInt()}"
        val raw = File(dir, "$base.part")
        val out = File(dir, "$base.flac")
        try {
            graph.http.newCall(Request.Builder().url(info.url).apply { info.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val input = info.aesCtrKey?.let { AesCtr.decrypting(resp.body.byteStream(), it) } ?: resp.body.byteStream()
                raw.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            currentCoroutineContext().ensureActive()
            // FLAC packed in MP4 («flac-mp4») is unpacked into a real .flac, bit for bit.
            if (FlacMp4.isFlacInMp4(raw)) FlacMp4.extract(raw, out) else if (!raw.renameTo(out)) { raw.copyTo(out, true) }
            val meta = runCatching { graph.yandex.track(remoteId) }.getOrNull()
            Tags.copy(File(t.path!!), out, url = meta?.webUrl, fallbackCover = (meta?.coverUrl ?: t.coverUrl)?.let(graph.covers::bytes))
            val c = FlacCandidate(t.path, t.title, t.artist, remoteId, out.absolutePath, t.codec, t.bitrateKbps, File(t.path).length(), out.length())
            _candidates.value = _candidates.value.filterNot { it.trackPath == t.path } + c
            persist()
        } finally {
            raw.delete()
        }
    }

    /** «Это он»: the FLAC takes the file's place; the old file goes to `.palimpsest/replaced` next to it. */
    suspend fun accept(c: FlacCandidate) = withContext(Dispatchers.IO) {
        val old = File(c.trackPath)
        val flac = File(c.flacPath)
        if (!flac.isFile || !old.isFile) { drop(c); return@withContext }
        val target = File(old.parentFile, old.nameWithoutExtension + ".flac").let { if (it.exists() && it != old) File(old.parentFile, old.nameWithoutExtension + " (FLAC).flac") else it }
        val keepDir = File(old.parentFile, ".palimpsest/replaced").apply { mkdirs() }
        val kept = File(keepDir, old.name).let { if (it.exists()) File(keepDir, "${System.currentTimeMillis()}_${old.name}") else it }
        move(old, kept)
        move(flac, target)
        drop(c)
        graph.library.scan()
    }

    /** «Не то»: the FLAC goes, and this match isn't offered for this file again. */
    fun reject(c: FlacCandidate) {
        File(c.flacPath).delete()
        data = data.copy(rejected = data.rejected + "${c.trackPath}\t${c.remoteId}")
        drop(c)
    }

    private fun drop(c: FlacCandidate) {
        _candidates.value = _candidates.value.filterNot { it.flacPath == c.flacPath }
        persist()
    }

    private fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "ape", "wv", "aiff", "aif", "dsf", "dff")

        fun isLossy(t: Track): Boolean {
            val codec = t.codec?.lowercase()
            if (codec != null) return LOSSLESS.none { codec.startsWith(it) }
            return File(t.path ?: return false).extension.lowercase() !in LOSSLESS
        }
    }
}
