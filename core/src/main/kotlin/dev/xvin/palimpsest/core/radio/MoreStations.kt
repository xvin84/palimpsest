package dev.xvin.palimpsest.core.radio

import dev.xvin.palimpsest.core.model.MusicProvider
import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.net.Http
import dev.xvin.palimpsest.core.youtube.YouTubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * YouTube Music's wave: the endless mix from a song, page after page; when one mix runs out, a new
 * one from the last song heard. Seeds: `yt:<videoId>`, or `q:<artist title>` — the song is found
 * first (that's how a wave made of your own favourites starts).
 */
class YouTubeStation(private val yt: YouTubeProvider, private val seeds: List<String>) : StationSource {
    private var page: org.schabi.newpipe.extractor.Page? = null
    private var current: String? = null
    private var lastSong: String? = null
    private var next = 0

    override suspend fun open(avoid: List<String>): List<RemoteTrack> {
        page = null
        // Each new session starts from the next seed, so a wave of several favourites goes round them.
        for (i in seeds.indices) {
            val seed = seeds[(next + i) % seeds.size]
            val id = videoOf(seed) ?: continue
            next = (next + i + 1) % seeds.size
            return start(id)
        }
        lastSong?.let { return start(it) }
        return emptyList()
    }

    override suspend fun more(avoid: List<String>): List<RemoteTrack> {
        val id = current ?: return open(avoid)
        val p = page ?: return start(lastSong ?: return open(avoid))
        val (tracks, nextPage) = yt.mix(id, p)
        page = nextPage
        tracks.lastOrNull()?.let { lastSong = it.id }
        return tracks
    }

    private suspend fun start(videoId: String): List<RemoteTrack> {
        val (tracks, nextPage) = yt.mix(videoId)
        current = videoId
        page = nextPage
        tracks.lastOrNull()?.let { lastSong = it.id }
        return tracks
    }

    private suspend fun videoOf(seed: String): String? = when {
        seed.startsWith("yt:") -> seed.removePrefix("yt:")
        seed.startsWith("q:") -> runCatching { yt.search(seed.removePrefix("q:"), 0).firstOrNull()?.id }.getOrNull()
        else -> null
    }
}

/**
 * Bandcamp by a genre tag: the tag's top albums from Bandcamp's own discover page, one song from
 * each — the one Bandcamp features — so the wave keeps moving from artist to artist.
 */
class BandcampStation(
    private val bandcamp: dev.xvin.palimpsest.core.model.BrowsableProvider,
    private val tag: String,
    private val http: OkHttpClient = Http.client,
) : StationSource {
    private var cursor = "*"

    @Serializable
    private data class Featured(val id: Long = 0, val title: String = "")

    @Serializable
    private data class Item(@kotlinx.serialization.SerialName("item_url") val url: String = "", val title: String = "", @kotlinx.serialization.SerialName("featured_track") val featured: Featured? = null)

    @Serializable
    private data class Page(val results: List<Item> = emptyList(), val cursor: String? = null)

    override suspend fun open(avoid: List<String>): List<RemoteTrack> { cursor = "*"; return more(avoid) }

    override suspend fun more(avoid: List<String>): List<RemoteTrack> = coroutineScope {
        val page = discover()
        cursor = page.cursor ?: "*"
        page.results.take(8).map { item ->
            async {
                runCatching {
                    val tracks = bandcamp.album(item.url.substringBefore('?')).tracks
                    tracks.firstOrNull { it.title.equals(item.featured?.title, true) } ?: tracks.firstOrNull()
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun discover(): Page = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("category_id", 0)
            put("tag_norm_names", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(tag.lowercase().replace(' ', '-'))) })
            put("geoname_id", 0)
            put("slice", "top")
            put("cursor", cursor)
            put("size", 12)
            put("include_result_types", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")) })
        }.toString()
        val req = Request.Builder().url("https://bandcamp.com/api/discover/1/discover_web")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("Bandcamp: HTTP ${r.code}"); r.body.string() }
        Http.json.decodeFromString(Page.serializer(), text)
    }
}

/**
 * Several waves as one: each part gets its share of the songs ([weights], any scale), mixed so no
 * source plays twice in a row more than its share asks. A part that fails or runs dry is skipped
 * this time; the others carry on.
 */
class MixedStation(private val parts: List<Pair<StationSource, Int>>) : StationSource {
    override suspend fun open(avoid: List<String>): List<RemoteTrack> = blend { it.open(avoid) }
    override suspend fun more(avoid: List<String>): List<RemoteTrack> = blend { it.more(avoid) }

    override suspend fun started(track: RemoteTrack) = parts.forEach { runCatching { it.first.started(track) } }
    override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) = parts.forEach { runCatching { it.first.ended(track, playedSec, finished) } }

    private suspend fun blend(fetch: suspend (StationSource) -> List<RemoteTrack>): List<RemoteTrack> = coroutineScope {
        val got = parts.filter { it.second > 0 }.map { (src, w) -> async { runCatching { fetch(src) }.getOrDefault(emptyList()) to w } }.awaitAll()
        interleave(got)
    }

    companion object {
        /** Takes from each list in proportion to its weight: 2:1 → a, a, b, a, a, b… */
        fun interleave(lists: List<Pair<List<RemoteTrack>, Int>>): List<RemoteTrack> {
            val queues = lists.filter { it.first.isNotEmpty() && it.second > 0 }.map { ArrayDeque(it.first) to it.second }
            if (queues.isEmpty()) return emptyList()
            val credit = DoubleArray(queues.size)
            val total = queues.sumOf { it.second }.toDouble()
            val out = ArrayList<RemoteTrack>()
            // Take while every part still has songs, so the shares hold within a batch.
            while (queues.all { it.first.isNotEmpty() }) {
                queues.forEachIndexed { i, q -> credit[i] += q.second / total }
                val i = credit.indices.maxBy { credit[it] }
                credit[i] -= 1.0
                out += queues[i].first.removeFirst()
            }
            return out
        }
    }
}

/**
 * What «Моя волна» plays and how it's built, the same on both apps. A spec's seeds carry
 * everything: Yandex's tuning (`settingMoodEnergy:calm`…), songs to start YouTube from
 * (`q:artist title`, `yt:<id>`), Bandcamp's genre (`tag:ambient`) and, for the mixed wave, the
 * shares (`w:local=1`, `w:yandex=2`, `w:youtube=1`, `w:bandcamp=0`).
 */
object Waves {
    const val YOUTUBE = "youtube"
    const val BANDCAMP = "bandcamp"
    const val MIX = "mix"

    /** The spec for the wave the settings describe. [favourites] seed YouTube (a few of the user's songs). */
    fun spec(source: String, title: String, yandexTuning: List<String>, favourites: List<String>, bandcampTag: String, shares: Map<String, Int>): StationSpec = when (source) {
        YOUTUBE -> StationSpec(YOUTUBE, favourites.ifEmpty { listOf("q:lofi hip hop") }, title)
        BANDCAMP -> StationSpec(BANDCAMP, listOf("tag:" + bandcampTag.ifBlank { "ambient" }), title)
        MIX -> StationSpec(MIX, shares.filterValues { it > 0 }.map { (k, v) -> "w:$k=$v" } + yandexTuning + favourites + listOf("tag:" + bandcampTag.ifBlank { "ambient" }), title)
        else -> StationSpec(StationSpec.YANDEX, listOf(StationSpec.MY_WAVE) + yandexTuning, title)
    }

    /**
     * The source behind [spec]; null when nothing of it can play here (signed out of Yandex and no
     * other part). [yandex] wraps the Yandex part as the app needs (the phone refreshes its token).
     */
    fun source(
        spec: StationSpec,
        yandex: ((List<String>) -> StationSource)?,
        youtube: YouTubeProvider?,
        bandcamp: dev.xvin.palimpsest.core.model.BrowsableProvider?,
        local: (() -> StationSource)?,
        http: OkHttpClient = Http.client,
    ): StationSource? {
        val tag = spec.seeds.firstOrNull { it.startsWith("tag:") }?.removePrefix("tag:") ?: "ambient"
        val songs = spec.seeds.filter { it.startsWith("q:") || it.startsWith("yt:") }
        return when (spec.source) {
            StationSpec.YANDEX -> yandex?.invoke(spec.seeds)
            YOUTUBE -> youtube?.let { YouTubeStation(it, songs.ifEmpty { listOf("q:lofi hip hop") }) }
            BANDCAMP -> bandcamp?.let { BandcampStation(it, tag, http) }
            MIX -> {
                val shares = spec.seeds.filter { it.startsWith("w:") }.associate { it.removePrefix("w:").substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 0) }
                val parts = shares.mapNotNull { (part, w) ->
                    val src = when (part) {
                        "local" -> local?.invoke()
                        StationSpec.YANDEX -> yandex?.invoke(listOf(StationSpec.MY_WAVE) + spec.seeds.filter { it.startsWith("setting") })
                        YOUTUBE -> youtube?.let { YouTubeStation(it, songs.ifEmpty { listOf("q:lofi hip hop") }) }
                        BANDCAMP -> bandcamp?.let { BandcampStation(it, tag, http) }
                        else -> null
                    }
                    src?.let { it to w }
                }.filter { it.second > 0 }
                if (parts.isEmpty()) null else MixedStation(parts)
            }
            else -> null
        }
    }
}
