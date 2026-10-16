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
}
