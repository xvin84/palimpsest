package dev.xvin.palimpsest.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class Play(val trackId: String, val title: String, val artist: String, val at: Long, val listenedMs: Long)

@Serializable
/** [cover]: a picture chosen for it (a file in the config folder); none — a collage of its songs. */
data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val cover: String? = null,
    /** Shared with the phone, so a rename on either side stays the same playlist. */
    val syncId: String? = null,
    /** When the name, the songs and the cover were last changed (the later change wins a sync conflict). */
    val nameAt: Long = 0,
    val tracksAt: Long = 0,
    val coverAt: Long = 0,
)

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

    /** Plays from another device, kept in time order. */
    fun addAll(ps: List<Play>) {
        if (ps.isEmpty()) return
        _plays.value = (_plays.value + ps).sortedBy { it.at }.takeLast(50_000)
        file.save(_plays.value)
    }
}

class PlaylistStore {
    private val file = JsonFile(File(Paths.config, "playlists.json"), ListSerializer(Playlist.serializer())) { emptyList() }
    private val _lists = MutableStateFlow(file.load())
    val lists: StateFlow<List<Playlist>> = _lists

    private fun save(l: List<Playlist>) { _lists.value = l; file.save(l) }
    private fun now() = System.currentTimeMillis()
    fun create(name: String, syncId: String? = null): Playlist =
        Playlist((_lists.value.maxOfOrNull { it.id } ?: 0) + 1, name, syncId = syncId, nameAt = now()).also { save(_lists.value + it) }
    fun rename(id: Long, name: String) = save(_lists.value.map { if (it.id == id) it.copy(name = name, nameAt = now()) else it })
    fun delete(id: Long) = save(_lists.value.filterNot { it.id == id })
    /** Puts a deleted playlist back as it was (undo). */
    fun restore(p: Playlist) { if (_lists.value.none { it.id == p.id }) save(_lists.value + p) }
    fun setCover(id: Long, cover: String?) = save(_lists.value.map { if (it.id == id) it.copy(cover = cover, coverAt = now()) else it })
    fun add(id: Long, trackIds: List<String>) = save(_lists.value.map { if (it.id == id) it.copy(trackIds = it.trackIds + trackIds, tracksAt = now()) else it })
    fun setTracks(id: Long, trackIds: List<String>) = save(_lists.value.map { if (it.id == id) it.copy(trackIds = trackIds, tracksAt = now()) else it })
    /** Gives a playlist its shared id the first time it's synced. */
    fun setSyncId(id: Long, syncId: String) = save(_lists.value.map { if (it.id == id) it.copy(syncId = syncId) else it })
    /** What a sync agreed on, times included (so the next sync doesn't take it for a fresh edit). */
    fun applySynced(id: Long, f: (Playlist) -> Playlist) = save(_lists.value.map { if (it.id == id) f(it) else it })
    /** A track's id changed (its file moved): the playlists follow. */
    fun replaceId(old: String, new: String) {
        if (old == new || _lists.value.none { old in it.trackIds }) return
        save(_lists.value.map { p -> p.copy(trackIds = p.trackIds.map { if (it == old) new else it }) })
    }
    fun removeAt(id: Long, index: Int) = save(_lists.value.map { if (it.id == id) it.copy(trackIds = it.trackIds.filterIndexed { i, _ -> i != index }, tracksAt = now()) else it })
}
