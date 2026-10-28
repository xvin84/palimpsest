package dev.xvin.palimpsest.desktop.playback

import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.radio.RadioFeed
import dev.xvin.palimpsest.core.radio.StationSource
import dev.xvin.palimpsest.core.radio.StationSpec
import dev.xvin.palimpsest.core.radio.YandexStation
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
    /** The station feeding the queue, while one does. */
    @Volatile private var feed: RadioFeed? = null
    private var topping: Job? = null
    /** The radio track playing now and since when, for the station's listening feedback. */
    private var radioPlaying: Pair<Track, Long>? = null
    private val _shaking = MutableStateFlow(false)
    val shaking: StateFlow<Boolean> = _shaking
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
        if (tracks.isEmpty()) return
        val station = s.lastStation?.let { saved -> sourceFor(saved.spec)?.let { RadioFeed(saved.spec, it, saved.heard) } }
        feed = station
        _state.value = PlayerState(queue = tracks, index = s.lastIndex.coerceIn(0, tracks.lastIndex), positionMs = s.lastPositionMs, radio = station?.spec?.title)
        order = tracks.indices.toList()
    }

    private fun saveQueue() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        // A long radio queue keeps its tail: that's where it goes on from.
        val ids = st.queue.map { t -> t.id }
        val from = if (ids.size > 1000) (st.index - 500).coerceIn(0, ids.size - 1000) else 0
        graph.settings.update {
            it.copy(lastQueue = ids.subList(from, minOf(ids.size, from + 1000)), lastIndex = st.index - from, lastPositionMs = st.positionMs, lastStation = feed?.save())
        }
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
        if (radio == null) endRadio()
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(queue = tracks, index = start, shuffle = shuffle, radio = radio, error = null)
        order = if (shuffle) listOf(start) + tracks.indices.filter { it != start }.shuffled() else tracks.indices.toList()
        startCurrent(0)
    }

    /** [t] alone from [fromMs]: comparing two files of the same song at the same moment. */
    fun playAlone(t: Track, fromMs: Long) {
        endRadio()
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
            // A radio never loops: it brings new tracks instead (see onEnded).
            st.repeat == Repeat.ALL && st.radio == null -> order[(p + order.size) % order.size]
            else -> null
        }
    }

    private fun advance(step: Int, user: Boolean) {
        val i = nextIndex(step) ?: run {
            // The radio ran dry before the next batch came: wait for it, then carry on.
            if (step > 0 && feed != null) { topUpRadio(thenAdvance = true); return }
            if (!user) engine.stop()
            return
        }
        _state.value = _state.value.copy(index = i)
        startCurrent(0)
    }

    private fun onEnded() {
        if (_state.value.repeat == Repeat.ONE) startCurrent(0) else advance(+1, user = false)
    }

    // --- Loading -----------------------------------------------------------------------------------

    private fun startCurrent(fromMs: Long) {
        val t = _state.value.current ?: return
        radioTrackChanged(t)
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
                if (_state.value.index >= _state.value.queue.size - 2) topUpRadio()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = null, error = "${t.title}: ${e.message ?: e.javaClass.simpleName}")
                System.err.println("Palimpsest: couldn't play ${t.id}: $e")
                // A cloud track that can't load shouldn't stop the music.
                delay(1500)
                if (_state.value.current?.id == t.id) advance(+1, user = false)
            }
        }
    }

    /** The track's audio as a file (fetched like for playing when it's a stream), e.g. to render a video of it. */
    suspend fun audioFileOf(t: Track, onProgress: (Float) -> Unit = {}): File = fileFor(t, onProgress)

    /** The file to play: the track's own, or the cloud audio fetched (and decrypted) into the cache. */
    private suspend fun fileFor(t: Track, onProgress: (Float) -> Unit): File {
        t.path?.let { p -> File(p).takeIf { it.isFile }?.let { return it } }
        val rid = t.remoteId ?: throw IllegalStateException("no file")
        val provider = graph.provider(t.service) ?: throw IllegalStateException("${t.service.displayName} unavailable")
        // «.part» is a download in progress or one that broke off: never play it as if it were whole.
        fun cachedFile() = cacheDir.listFiles { f -> f.nameWithoutExtension == key(t) && f.extension != "part" }?.firstOrNull()
        val cached = cachedFile()
        if (cached != null) { cached.setLastModified(System.currentTimeMillis()); return cached }
        return fetchLock.withLock {
            withContext(Dispatchers.IO) {
                cachedFile()?.let { return@withContext it }
                val info = provider.resolveStream(rid, graph.settings.value.quality)
                val tmp = File(cacheDir, key(t) + ".part")
                val req = Request.Builder().url(info.url).apply { info.headers.forEach { (k, v) -> header(k, v) } }.build()
                try {
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
                        // A connection that drops mid-file can look like a normal end.
                        if (total > 0 && done < total) throw java.io.IOException("download cut off at $done of $total bytes")
                    }
                }
                } catch (e: Throwable) {
                    tmp.delete()
                    throw e
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
        val spec = StationSpec(StationSpec.YANDEX, listOf(seed) + extraSeeds, title)
        // Starting the same station again remembers what it already played.
        val heard = (feed?.save() ?: graph.settings.value.lastStation)?.takeIf { it.spec.seed == seed }?.heard.orEmpty()
        val f = RadioFeed(spec, sourceFor(spec) ?: error("Sign in to Yandex Music first"), heard)
        val tracks = f.start().map(graph.library::ensureRemote)
        endRadio()
        feed = f
        play(tracks, 0, radio = title)
        saveQueue()
    }

    /** «Встряхнуть»: a new session that avoids everything heard, playing from its first track now. */
    suspend fun shakeRadio() {
        val f = feed ?: return
        _shaking.value = true
        try {
            val tracks = f.shake().map(graph.library::ensureRemote)
            val st = _state.value
            val keep = st.queue.take(st.index + 1)
            _state.value = st.copy(queue = keep + tracks, index = keep.size, error = null)
            order = (keep + tracks).indices.toList()
            startCurrent(0)
            saveQueue()
        } finally {
            _shaking.value = false
        }
    }

    private fun sourceFor(spec: StationSpec): StationSource? = when (spec.source) {
        StationSpec.YANDEX -> YandexStation(graph.yandex, spec.seeds).takeIf { graph.yandex.isReady }
        else -> null
    }

    private fun endRadio() {
        feed = null
        topping?.cancel()
        radioPlaying = null
    }

    /** Tells the station what was heard and what was skipped, so it adapts. */
    private fun radioTrackChanged(t: Track) {
        val f = feed ?: return
        val now = System.currentTimeMillis()
        val prev = radioPlaying
        if (prev?.first?.id == t.id) return
        radioPlaying = t to now
        graph.scope.launch {
            prev?.let { (p, at) -> p.asRemote()?.let { f.ended(it, now - at, p.durationMs) } }
            t.asRemote()?.let { f.started(it) }
        }
    }

    private fun Track.asRemote() = remoteId?.let { RemoteTrack(service, it, title, listOf(artist), durationMs = durationMs) }

    /** Brings the next batch; failures are retried a few times so the music doesn't just stop. */
    private fun topUpRadio(thenAdvance: Boolean = false) {
        val f = feed ?: return
        if (topping?.isActive == true) return
        topping = graph.scope.launch {
            for (wait in listOf(0L, 3_000L, 10_000L, 30_000L)) {
                delay(wait)
                if (feed !== f) return@launch
                val fresh = runCatching { f.next() }.getOrDefault(emptyList())
                if (fresh.isEmpty()) continue
                if (feed !== f) return@launch
                val atEnd = _state.value.index >= _state.value.queue.size - 1
                enqueue(fresh.map(graph.library::ensureRemote))
                saveQueue()
                if (thenAdvance && atEnd) advance(+1, user = false)
                return@launch
            }
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
