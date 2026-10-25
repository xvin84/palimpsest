package dev.xvin.palimpsest.desktop.playback

import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Paths
import dev.xvin.palimpsest.desktop.data.Play
import dev.xvin.palimpsest.desktop.data.Rg
import dev.xvin.palimpsest.desktop.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

enum class Repeat { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    /** Position in [queue] of the current track. */
    val index: Int = -1,
    val playing: Boolean = false,
    /** 0..1 while a cloud track is being fetched, null otherwise. */
    val loading: Float? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    val error: String? = null,
    /** Set while «My Wave» or another radio feeds the queue. */
    val radio: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * The queue and everything around the engine: cloud tracks are fetched (and decrypted) into a
 * cache before they play — the next one ahead of time — plays are logged, the radio keeps the
 * queue topped up, and the queue survives a restart.
 */
class Player(private val graph: Graph) {
    val engine = AudioEngine()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    private val cacheDir = File(Paths.cache, "stream").apply { mkdirs() }
    private var loadJob: Job? = null
    private val fetchLock = Mutex()
    private var listenedMs = 0L
    private var logged = false
    private var lastTick = 0L
    private var radioSession: String? = null
    private var radioBatch: String? = null
    /** Shuffle order: indices of [PlayerState.queue] in play order. */
    private var order: List<Int> = emptyList()

    init {
        val s = graph.settings.value
        engine.volume = s.volume
        applyEffects(null)
        // Ends, positions, the listening log.
        graph.scope.launch {
            var seenEnd = 0
            while (isActive) {
                delay(200)
                val e = engine.state.value
                val now = System.currentTimeMillis()
                if (e.playing && lastTick > 0) listenedMs += now - lastTick
                lastTick = now
                _state.value = _state.value.copy(positionMs = e.positionMs, playing = e.playing, error = e.error ?: _state.value.error)
                maybeLog()
                if (engine.endCount != seenEnd) {
                    seenEnd = engine.endCount
                    onEnded()
                }
            }
        }
        graph.scope.launch {
            while (isActive) { delay(10_000); saveQueue() }
        }
        graph.scope.launch { graph.settings.state.collect { applyEffects(_state.value.current); engine.volume = it.volume } }
        restoreQueue()
    }

    private fun restoreQueue() {
        val s = graph.settings.value
        val tracks = s.lastQueue.mapNotNull(graph.library::get)
        if (tracks.isNotEmpty()) _state.value = PlayerState(queue = tracks, index = s.lastIndex.coerceIn(0, tracks.lastIndex), positionMs = s.lastPositionMs)
        order = tracks.indices.toList()
    }

    private fun saveQueue() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        graph.settings.update { it.copy(lastQueue = st.queue.map { t -> t.id }.take(1000), lastIndex = st.index, lastPositionMs = st.positionMs) }
    }

    private fun applyEffects(t: Track?) {
        val s = graph.settings.value
        engine.equalizer.setProfile(EqProfile.PRESETS.firstOrNull { it.name == s.eqProfile } ?: EqProfile.FLAT)
        engine.equalizer.enabled = s.eqEnabled
        val rg = when (s.replayGain) {
            Rg.OFF -> null
            Rg.TRACK -> t?.rgTrackDb ?: t?.rgAlbumDb
            Rg.ALBUM -> t?.rgAlbumDb ?: t?.rgTrackDb
        }
        engine.equalizer.setGainDb(if (rg == null) 0.0 else (rg + s.preampDb).toDouble())
    }

    // --- Queue -------------------------------------------------------------------------------------

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, radio: String? = null) {
        if (tracks.isEmpty()) return
        if (radio == null) { radioSession = null; radioBatch = null }
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(queue = tracks, index = start, shuffle = shuffle, radio = radio, error = null)
        order = if (shuffle) listOf(start) + tracks.indices.filter { it != start }.shuffled() else tracks.indices.toList()
        startCurrent(0)
    }

    /** [t] alone from [fromMs]: comparing two files of the same song at the same moment. */
    fun playAlone(t: Track, fromMs: Long) {
        radioSession = null; radioBatch = null
        _state.value = _state.value.copy(queue = listOf(t), index = 0, shuffle = false, radio = null, error = null)
        order = listOf(0)
        startCurrent(fromMs)
    }

    fun playRemote(list: List<RemoteTrack>, index: Int) = play(list.map(graph.library::ensureRemote), index)

    fun enqueue(tracks: List<Track>) {
        val st = _state.value
        if (st.queue.isEmpty()) return play(tracks)
        _state.value = st.copy(queue = st.queue + tracks)
        order = order + (st.queue.size until st.queue.size + tracks.size)
    }

    fun playNext(tracks: List<Track>) {
        val st = _state.value
        if (st.queue.isEmpty()) return play(tracks)
        val at = st.index + 1
        val q = st.queue.toMutableList().apply { addAll(at, tracks) }
        _state.value = st.copy(queue = q)
        // Indices after the insertion point shift; the new ones play right after the current.
        val shifted = order.map { if (it >= at) it + tracks.size else it }
        val pos = shifted.indexOf(st.index)
        order = shifted.take(pos + 1) + (at until at + tracks.size) + shifted.drop(pos + 1)
    }

    /** Tracks changed under the queue (moved, retagged): the queue gets the new entries, [old] id → new track. */
    fun replaced(changes: Map<String, Track>) {
        val st = _state.value
        if (st.queue.none { it.id in changes }) return
        _state.value = st.copy(queue = st.queue.map { changes[it.id] ?: it })
    }

    fun removeAt(i: Int) {
        val st = _state.value
        if (i == st.index || i !in st.queue.indices) return
        _state.value = st.copy(queue = st.queue.filterIndexed { k, _ -> k != i }, index = if (i < st.index) st.index - 1 else st.index)
        order = order.filter { it != i }.map { if (it > i) it - 1 else it }
    }

    fun skipTo(i: Int) {
        if (i !in _state.value.queue.indices) return
        _state.value = _state.value.copy(index = i)
        startCurrent(0)
    }

    fun toggle() {
        val st = _state.value
        when {
            st.current == null -> Unit
            engine.state.value.file == null -> startCurrent(st.positionMs)
            else -> engine.toggle()
        }
    }

    fun next() = advance(+1, user = true)
    fun previous() {
        if (_state.value.positionMs > 4000) seek(0) else advance(-1, user = true)
    }

    fun seek(ms: Long) {
        if (engine.state.value.file == null) { _state.value = _state.value.copy(positionMs = ms); return }
        engine.seek(ms)
    }

    fun setShuffle(on: Boolean) {
        val st = _state.value
        order = if (on) listOf(st.index) + st.queue.indices.filter { it != st.index }.shuffled() else st.queue.indices.toList()
        _state.value = st.copy(shuffle = on)
    }

    fun cycleRepeat() {
        _state.value = _state.value.copy(repeat = Repeat.entries[(_state.value.repeat.ordinal + 1) % Repeat.entries.size])
    }

    fun setVolume(v: Float) = graph.settings.update { it.copy(volume = v.coerceIn(0f, 1f)) }

    private fun nextIndex(step: Int): Int? {
        val st = _state.value
        if (st.queue.isEmpty()) return null
        val pos = order.indexOf(st.index).takeIf { it >= 0 } ?: 0
        val p = pos + step
        return when {
            p in order.indices -> order[p]
            st.repeat == Repeat.ALL -> order[(p + order.size) % order.size]
            else -> null
        }
    }

    private fun advance(step: Int, user: Boolean) {
        val i = nextIndex(step) ?: run { if (!user) engine.stop(); return }
        _state.value = _state.value.copy(index = i)
        startCurrent(0)
    }

    private fun onEnded() {
        if (_state.value.repeat == Repeat.ONE) startCurrent(0) else advance(+1, user = false)
    }

    // --- Loading -----------------------------------------------------------------------------------

    private fun startCurrent(fromMs: Long) {
        val t = _state.value.current ?: return
        listenedMs = 0; logged = false
        applyEffects(t)
        loadJob?.cancel()
        engine.stop()
        loadJob = graph.scope.launch {
            try {
                val file = fileFor(t) { p -> _state.value = _state.value.copy(loading = p) }
                _state.value = _state.value.copy(loading = null, error = null)
                engine.play(file, fromMs)
                prefetchNext()
                topUpRadio()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = null, error = "${t.title}: ${e.message ?: e.javaClass.simpleName}")
                // A cloud track that can't load shouldn't stop the music.
                delay(1500)
                if (_state.value.current?.id == t.id) advance(+1, user = false)
            }
        }
    }

    /** The file to play: the track's own, or the cloud audio fetched (and decrypted) into the cache. */
    private suspend fun fileFor(t: Track, onProgress: (Float) -> Unit): File {
        t.path?.let { p -> File(p).takeIf { it.isFile }?.let { return it } }
        val rid = t.remoteId ?: throw IllegalStateException("no file")
        val provider = graph.provider(t.service) ?: throw IllegalStateException("${t.service.displayName} unavailable")
        val cached = cacheDir.listFiles { f -> f.nameWithoutExtension == key(t) }?.firstOrNull()
        if (cached != null) { cached.setLastModified(System.currentTimeMillis()); return cached }
        return fetchLock.withLock {
            withContext(Dispatchers.IO) {
                cacheDir.listFiles { f -> f.nameWithoutExtension == key(t) }?.firstOrNull()?.let { return@withContext it }
                val info = provider.resolveStream(rid, graph.settings.value.quality)
                val tmp = File(cacheDir, key(t) + ".part")
                val req = Request.Builder().url(info.url).apply { info.headers.forEach { (k, v) -> header(k, v) } }.build()
                graph.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val total = resp.body.contentLength()
                    val input = info.aesCtrKey?.let { AesCtr.decrypting(resp.body.byteStream(), it) } ?: resp.body.byteStream()
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                val out = File(cacheDir, key(t) + "." + info.extension)
                tmp.renameTo(out)
                trimCache()
                out
            }
        }
    }

    private fun key(t: Track) = t.id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)

    /** Keeps the stream cache under 2 GB, dropping the least recently played first. */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.extension != "part" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 2L shl 30) break; total -= f.length(); f.delete() }
    }

    private fun prefetchNext() {
        val i = nextIndex(+1) ?: return
        val t = _state.value.queue.getOrNull(i)?.takeIf { it.path == null } ?: return
        graph.scope.launch { runCatching { fileFor(t) {} } }
    }

    // --- Radio -------------------------------------------------------------------------------------

    /** Starts a Yandex radio station («Моя волна» is `user:onyourwave`). */
    suspend fun startRadio(seed: String, title: String, extraSeeds: List<String> = emptyList()) {
        val batch = graph.yandex.radioStart(listOf(seed) + extraSeeds)
        radioSession = batch.sessionId
        radioBatch = batch.batchId
        play(batch.tracks.map(graph.library::ensureRemote), 0, radio = title)
        radioSession = batch.sessionId
        radioBatch = batch.batchId
    }

    private suspend fun topUpRadio() {
        val session = radioSession ?: return
        val st = _state.value
        if (st.radio == null || st.index < st.queue.size - 2) return
        runCatching {
            val more = graph.yandex.radioMore(session, st.queue.mapNotNull { it.remoteId }.takeLast(20))
            radioBatch = more.batchId
            enqueue(more.tracks.map(graph.library::ensureRemote))
        }
    }

    // --- History -----------------------------------------------------------------------------------

    private fun maybeLog() {
        if (logged) return
        val t = _state.value.current ?: return
        if (t.id.startsWith("preview:")) return
        val threshold = if (t.durationMs > 0) minOf(30_000L, t.durationMs / 2) else 30_000L
        if (listenedMs < threshold) return
        logged = true
        graph.history.add(Play(t.id, t.title, t.artist, System.currentTimeMillis() - listenedMs, listenedMs))
    }

    companion object {
        const val MY_WAVE = "user:onyourwave"
    }
}
