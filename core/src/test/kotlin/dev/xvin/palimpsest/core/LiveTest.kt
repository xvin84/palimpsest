package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.lyrics.LrcLibClient
import dev.xvin.palimpsest.core.youtube.YouTubeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits real services. Run with: LIVE=1 ./gradlew :core:test --tests '*LiveTest*' -i */
class LiveTest {
    @Before fun onlyWhenAsked() = assumeTrue(System.getenv("LIVE") == "1")

    @Test fun youtubeSearchAndStream() = runBlocking {
        val proxy = System.getenv("HTTPS_PROXY")?.let { java.net.URI(it) }
        val http = okhttp3.OkHttpClient.Builder().apply {
            if (proxy != null) proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.host, proxy.port)))
        }.build()
        val yt = YouTubeProvider(http)
        val results = yt.search("Coldplay Yellow")
        println("YT results: " + results.take(5).joinToString { "${it.artistLine} — ${it.title} (${it.durationMs / 1000}s) [${it.id}]" })
        check(results.isNotEmpty())
        val stream = yt.resolveStream(results.first().id)
        println("YT stream: ${stream.codec} ${stream.bitrateKbps}kbps ${stream.url.take(80)}…")
    }

    @Test fun youtubeSearchAll() = runBlocking {
        val proxy = System.getenv("HTTPS_PROXY")?.let { java.net.URI(it) }
        val http = okhttp3.OkHttpClient.Builder().apply {
            if (proxy != null) proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.host, proxy.port)))
        }.build()
        val yt = YouTubeProvider(http)
        val r = yt.searchAll("Coldplay", dev.xvin.palimpsest.core.model.SearchType.ALL)
        println("YT best: ${r.best}")
        println("YT artists: " + r.artists.take(3).joinToString { "${it.name} [${it.id}]" })
        println("YT albums: " + r.albums.take(3).joinToString { "${it.title} · ${it.artists} [${it.id}] ${it.trackCount}" })
        println("YT playlists: " + r.playlists.take(3).joinToString { "${it.title} · ${it.owner} [${it.id}]" })
        check(r.best is dev.xvin.palimpsest.core.model.SearchHit.Artist)
        val album = r.albums.first()
        val tracks = yt.playlistTracks(album.id)
        println("YT album ${album.title}: " + tracks.take(5).joinToString { it.title })
        check(tracks.isNotEmpty())
        val a = yt.artist((r.best as dev.xvin.palimpsest.core.model.SearchHit.Artist).artist.id, "Coldplay")
        println("YT artist: ${a.artist.name} popular=${a.popular.size} albums=${a.albums.size} desc=${a.description?.take(60)}")
        check(a.popular.isNotEmpty())
    }

    @Test fun lrclib() = runBlocking {
        val l = LrcLibClient().find("Coldplay", "Yellow", "Parachutes", 269)
        println("LRCLIB: synced=${l?.synced != null} " + (l?.synced ?: l?.plain)?.lines()?.take(3))
        check(l != null)
    }

    @Test fun coverArtArchive() = runBlocking {
        val proxy = System.getenv("HTTPS_PROXY")?.let { java.net.URI(it) }
        val http = okhttp3.OkHttpClient.Builder().apply {
            if (proxy != null) proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.host, proxy.port)))
        }.build()
        val bytes = dev.xvin.palimpsest.core.covers.CoverArtArchive(http).find("Coldplay", "Yellow", 266_000)
        println("CAA: ${bytes?.size} bytes")
        check(bytes != null && bytes.size > 10_000)
    }

    @Test fun translate() = runBlocking {
        val r = dev.xvin.palimpsest.core.translate.Translator().translate(listOf("Look at the stars", "Look how they shine for you"))
        println("TR: ${r.sourceLanguage} ${r.lines}")
        check(r.lines.size == 2 && r.lines.all { it.isNotBlank() })
    }

    @Test fun netEase() = runBlocking {
        val l = dev.xvin.palimpsest.core.lyrics.NetEaseLyrics().find("Coldplay", "Yellow", 266_000)
        println("NetEase: synced=${l?.synced != null} " + (l?.synced ?: l?.plain)?.lines()?.take(4))
        check(l?.synced != null && "作词" !in l.synced!!)
    }

    @Test fun genius() = runBlocking {
        val l = dev.xvin.palimpsest.core.lyrics.GeniusLyrics().find("Coldplay", "Yellow")
        println("Genius: " + l?.plain?.lines()?.take(6))
        check(l?.plain?.contains("Look at the stars") == true)
        val ru = dev.xvin.palimpsest.core.lyrics.GeniusLyrics().find("2rbina 2rista", "Барон суббота")
        println("Genius RU: " + ru?.plain?.lines()?.take(4))
    }
}
