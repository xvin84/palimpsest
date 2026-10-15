package dev.xvin.palimpsest.core.library

import dev.xvin.palimpsest.core.model.Service

/** What we can guess about a track from its path alone, for files with missing tags. */
data class PathGuess(
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val trackNumber: Int?,
    val youtubeId: String?,
)

object FileNames {
    val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wv", "ape", "alac", "webm", "mka", "aiff", "aif", "wma",
    )

    private val ytId = Regex("""\s*\[([A-Za-z0-9_-]{11})]\s*$""")
    private val trackPrefix = Regex("""^(\d{1,3})\s*[.\-_)]\s*""")
    private val albumDir = Regex("""^\((\d{4})\)\s*(.+)$""")
    private val albumDirSuffix = Regex("""^(.+?)\s*[(\[](\d{4})[)\]]$""")

    fun isAudio(fileName: String): Boolean = extension(fileName) in AUDIO_EXTENSIONS

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /**
     * Guesses from layouts such as:
     *  - `Artist - Title.flac`
     *  - `Artist/(2003) Album/1. Title.flac`
     *  - `Title [dQw4w9WgXcQ].webm` (yt-dlp)
     */
    fun guess(relativePath: String): PathGuess {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var name = parts.last().substringBeforeLast('.')
        val yt = ytId.find(name)?.groupValues?.get(1)
        if (yt != null) name = name.replace(ytId, "")

        var trackNo: Int? = null
        trackPrefix.find(name)?.let {
            trackNo = it.groupValues[1].toInt()
            name = name.substring(it.range.last + 1)
        }

        var artist: String? = null
        var title = name.trim()
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            artist = title.substring(0, dash).trim()
            title = title.substring(dash + 3).trim()
        }

        var album: String? = null
        var year: Int? = null
        if (parts.size >= 2) {
            val parent = parts[parts.size - 2]
            albumDir.matchEntire(parent)?.let {
                year = it.groupValues[1].toInt(); album = it.groupValues[2].trim()
            } ?: albumDirSuffix.matchEntire(parent)?.let {
                album = it.groupValues[1].trim(); year = it.groupValues[2].toInt()
            }
            // Artist/Album/track: if the parent looked like an album, the grandparent is the artist.
            if (album != null && artist == null && parts.size >= 3) artist = parts[parts.size - 3]
        }
        return PathGuess(title.ifEmpty { parts.last() }, artist, album, year, trackNo, yt)
    }

    /**
     * Which service a file came from, judging by tags first and then by the folder it lives in
     * (e.g. `Music/Murglar/Яндекс/...`).
     */
    fun serviceFor(relativePath: String, urlTag: String?): Service {
        if (urlTag != null) {
            if ("music.yandex" in urlTag) return Service.YANDEX
            if ("youtube.com" in urlTag || "youtu.be" in urlTag) return Service.YOUTUBE
            if ("spotify.com" in urlTag) return Service.SPOTIFY
        }
        val lower = relativePath.lowercase()
        return when {
            "яндекс" in lower || "yandex" in lower -> Service.YANDEX
            "youtube" in lower || ytId.containsMatchIn(relativePath.substringBeforeLast('.')) -> Service.YOUTUBE
            "spotify" in lower -> Service.SPOTIFY
            else -> Service.LOCAL
        }
    }

    /** Characters that are illegal in file names on Android's FAT/exFAT-backed storage. */
    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(150).ifEmpty { "_" }
}

object YandexLinks {
    private val trackRe = Regex("""music\.yandex\.[a-z]+/album/(\d+)/track/(\d+)""")
    private val trackOnlyRe = Regex("""music\.yandex\.[a-z]+/track/(\d+)""")

    /** Returns `(albumId, trackId)` from a Yandex Music URL, albumId may be null. */
    fun parse(url: String?): Pair<String?, String>? {
        if (url == null) return null
        trackRe.find(url)?.let { return it.groupValues[1] to it.groupValues[2] }
        trackOnlyRe.find(url)?.let { return null to it.groupValues[1] }
        return null
    }

    fun trackUrl(albumId: String?, trackId: String): String =
        if (albumId != null) "https://music.yandex.ru/album/$albumId/track/$trackId"
        else "https://music.yandex.ru/track/$trackId"
}
