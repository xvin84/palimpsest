package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.covers.OpenCatalogs
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.yandex.FlacLookup
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Covers from Deezer/iTunes and the FLAC lookup on real files. Run with:
 * LIVE=1 MUSIC=~/Music/Murglar [YANDEX_TOKEN=…] ./gradlew :core:test --tests '*CatalogLiveTest*' -i
 */
class CatalogLiveTest {
    @Before fun onlyWhenAsked() = assumeTrue(System.getenv("LIVE") == "1")

    private fun files(ext: Set<String>): List<File> = File(System.getenv("MUSIC") ?: return emptyList()).walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in ext }.toList()

    @Test fun coversForFileNames() = runBlocking {
        val cat = OpenCatalogs(okhttp3.OkHttpClient())
        val sample = files(setOf("mp3", "m4a", "webm", "flac")).shuffled(java.util.Random(1)).take(25)
        var found = 0
        for (f in sample) {
            val g = FileNames.guess(f.name)
            val hits = cat.search(listOfNotNull(g.artist, g.title).joinToString(" "))
            val best = OpenCatalogs.best(g.title, g.artist, 0, hits, { it.title }, { it.artist }, { it.durationMs })
            if (best != null) found++
            println("${if (best != null) "✓" else "✗"} ${g.artist} — ${g.title}  →  ${best?.let { "${it.source}: ${it.artist} — ${it.title} [${it.album}]" } ?: hits.take(2).joinToString { "${it.artist} — ${it.title}" }}")
        }
        println("covers: $found of ${sample.size}")
    }

    @Test fun flacForMp3s() = runBlocking {
        val token = System.getenv("YANDEX_TOKEN")
        assumeTrue(!token.isNullOrBlank())
        val lookup = FlacLookup(YandexMusicProvider({ token }, okhttp3.OkHttpClient()))
        val counts = HashMap<String, Int>()
        for (f in files(setOf("mp3")).take(30)) {
            val g = FileNames.guess(f.name)
            val m = lookup.find(g.title, g.artist ?: "", 0, null)
            counts.merge(m::class.simpleName!!, 1, Int::plus)
            println("$m  ←  ${g.artist} — ${g.title}")
        }
        println(counts)
    }
}
