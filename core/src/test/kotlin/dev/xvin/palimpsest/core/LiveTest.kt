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

    @Test fun newPipeOthers() = runBlocking {
        val proxy = System.getenv("HTTPS_PROXY")?.let { java.net.URI(it) }
        val http = okhttp3.OkHttpClient.Builder().apply {
            if (proxy != null) proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.host, proxy.port)))
        }.build()
        YouTubeProvider.initNewPipe(http)
        for (svc in listOf(org.schabi.newpipe.extractor.ServiceList.SoundCloud, org.schabi.newpipe.extractor.ServiceList.Bandcamp)) {
            val qh = svc.searchQHFactory
            println("${svc.serviceInfo.name} filters: ${qh.availableContentFilter.toList()}")
            val ex = svc.getSearchExtractor("lofi chill")
            ex.fetchPage()
            val items = ex.initialPage.items
            println("${svc.serviceInfo.name} items: " + items.take(6).joinToString { "${it.infoType}:${it.name} <${it.url}>" })
            for (first in items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().take(4)) {
                if (svc == org.schabi.newpipe.extractor.ServiceList.SoundCloud) {
                    val se = svc.getStreamExtractor(first.url)
                    runCatching { se.fetchPage(); println("SC audio: " + se.audioStreams.joinToString { "${it.format?.suffix}/${it.deliveryMethod}" }) }
                        .onFailure { e -> println("SC extractor error: ${e.javaClass.name}: ${e.message}"); e.printStackTrace(System.out) }
                }
                val info = runCatching { org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(svc, first.url) }
                    .onFailure { println("${svc.serviceInfo.name} ${first.name}: ${it.javaClass.simpleName} ${it.message} cause=${it.cause}") }.getOrNull() ?: continue
                println("${svc.serviceInfo.name} streams for ${first.name}: " + info.audioStreams.joinToString { "${it.format?.suffix}/${it.deliveryMethod}/${it.averageBitrate}/${it.content.take(60)}" })
            }
        }
    }

    @Test fun bandcamp() = runBlocking {
        val bc = dev.xvin.palimpsest.core.newpipe.NewPipeProvider.bandcamp(okhttp3.OkHttpClient())
        val r = bc.searchAll("Big Thief", dev.xvin.palimpsest.core.model.SearchType.ALL)
        println("BC best: ${r.best}")
        println("BC tracks: " + r.tracks.take(3).joinToString { "${it.artistLine} — ${it.title} [${it.id}] ${it.durationMs / 1000}s" })
        println("BC albums: " + r.albums.take(3).joinToString { "${it.title} · ${it.artists} [${it.id}]" })
        println("BC artists: " + r.artists.take(3).joinToString { "${it.name} [${it.id}] ${it.coverUrl}" }); println("BC covers: " + r.tracks.take(3).joinToString { it.coverUrl.toString() })
        val album = bc.album(r.albums.first().id)
        println("BC album ${album.album.title}: " + album.tracks.take(4).joinToString { "${it.trackNumber}. ${it.title} ${it.durationMs / 1000}s" })
        val s = album.tracks.firstNotNullOfOrNull { t -> runCatching { bc.resolveStream(t.id) }.onFailure { println("BC skip ${t.title}: ${it.message}") }.getOrNull() }
        println("BC stream: ${s?.codec} ${s?.bitrateKbps} ${s?.url?.take(60)}")
        r.artists.firstOrNull()?.let { a -> val info = bc.artist(a.id, a.name); println("BC artist ${info.artist.name}: popular=${info.popular.size} albums=${info.albums.size}") }
        check(album.tracks.isNotEmpty())
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
