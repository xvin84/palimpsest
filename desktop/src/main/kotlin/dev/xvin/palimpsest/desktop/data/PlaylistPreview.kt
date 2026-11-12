package dev.xvin.palimpsest.desktop.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PreviewState(val title: String, val index: Int, val total: Int)

/**
 * «Listen through»: a few seconds of each song of a playlist, from about a third in (past the
 * intro, near the chorus), one after another. Playing anything else stops it.
 */
class PlaylistPreview(private val graph: Graph) {
    private val _state = MutableStateFlow<PreviewState?>(null)
    val state: StateFlow<PreviewState?> = _state
    private var job: Job? = null

    fun start(tracks: List<Track>, title: String) {
        job?.cancel()
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }
        graph.player.play(tracks)
        job = graph.scope.launch {
            try {
                while (true) {
                    val st = graph.player.state.value
                    // Something else is playing now: the preview is over.
                    if (st.queue.map { it.id } != ids) break
                    val idx = st.index
                    val t = st.current ?: break
                    _state.value = PreviewState(title, idx + 1, ids.size)
                    // A cloud song first has to load before it can seek.
                    withTimeoutOrNull(8_000) { while (graph.player.state.value.let { it.loading != null || !it.playing }) delay(100) }
                    if (graph.player.state.value.index == idx && t.durationMs > 0) graph.player.seek(t.durationMs / 3)
                    delay(SNIPPET_MS)
                    val now = graph.player.state.value
                    if (now.queue.map { it.id } != ids) break
                    // Skipped by hand meanwhile: the song on now gets its own few seconds.
                    if (now.index != idx) continue
                    if (idx >= ids.lastIndex) { if (now.playing) graph.player.toggle(); break }
                    graph.player.next()
                }
            } finally {
                _state.value = null
            }
        }
    }

    /** Stops listening through; [keep]: the song on now plays on from its start. */
    fun stop(keep: Boolean) {
        job?.cancel(); job = null
        _state.value = null
        if (keep) graph.player.seek(0) else if (graph.player.state.value.playing) graph.player.toggle()
    }

    private companion object { const val SNIPPET_MS = 12_000L }
}
