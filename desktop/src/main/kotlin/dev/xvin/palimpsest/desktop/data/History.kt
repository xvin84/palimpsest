package dev.xvin.palimpsest.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class Play(val trackId: String, val title: String, val artist: String, val at: Long, val listenedMs: Long)

@Serializable
data class Playlist(val id: Long, val name: String, val trackIds: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis())

/** Listening history: a play counts after 30 s (or half a short track). */
class HistoryStore {
    private val file = JsonFile(File(Paths.config, "history.json"), ListSerializer(Play.serializer())) { emptyList() }
    private val _plays = MutableStateFlow(file.load())
    val plays: StateFlow<List<Play>> = _plays

    fun add(p: Play) {
        _plays.value = (_plays.value + p).takeLast(50_000)
        file.save(_plays.value)
    }

    fun clear() { _plays.value = emptyList(); file.save(emptyList()) }
}

class PlaylistStore {
    private val file = JsonFile(File(Paths.config, "playlists.json"), ListSerializer(Playlist.serializer())) { emptyList() }
    private val _lists = MutableStateFlow(file.load())
    val lists: StateFlow<List<Playlist>> = _lists

    private fun save(l: List<Playlist>) { _lists.value = l; file.save(l) }
    fun create(name: String): Playlist = Playlist((_lists.value.maxOfOrNull { it.id } ?: 0) + 1, name).also { save(_lists.value + it) }
    fun rename(id: Long, name: String) = save(_lists.value.map { if (it.id == id) it.copy(name = name) else it })
    fun delete(id: Long) = save(_lists.value.filterNot { it.id == id })
    fun add(id: Long, trackIds: List<String>) = save(_lists.value.map { if (it.id == id) it.copy(trackIds = it.trackIds + trackIds) else it })
    fun removeAt(id: Long, index: Int) = save(_lists.value.map { if (it.id == id) it.copy(trackIds = it.trackIds.filterIndexed { i, _ -> i != index }) else it })
}
