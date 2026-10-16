package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.model.Quality
import dev.xvin.palimpsest.core.yandex.YandexMusicProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Yandex client against canned responses shaped like the real API. */
class YandexTest {
    private val server = MockWebServer()
    private lateinit var yandex: YandexMusicProvider

    @Before fun setUp() {
        server.start()
        yandex = YandexMusicProvider({ "token" }, baseUrl = server.url("").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun likedTracksWithMixedIdTypes() = runBlocking {
        server.enqueue(json("""{"invocationInfo":{},"result":{"account":{"uid":123456,"login":"xvin","displayName":"X"},"plus":{"hasPlus":true}}}"""))
        server.enqueue(json("""{"result":{"library":{"uid":123456,"revision":5,"tracks":[{"id":"146160168","albumId":"39718785","timestamp":"2025-01-01"}]}}}"""))
        server.enqueue(json("""{"result":[{"id":"146160168","realId":"146160168","title":"in the pool","durationMs":245500,"available":true,
            "artists":[{"id":12345,"name":"kensuke ushio"}],
            "albums":[{"id":39718785,"title":"CHAINSAW MAN","year":2025,"genre":"soundtrack","coverUri":"avatars.yandex.net/get-music-content/x/y/%%","trackPosition":{"volume":1,"index":9}}],
            "lyricsInfo":{"hasAvailableSyncLyrics":true,"hasAvailableTextLyrics":true}}]}"""))
        val liked = yandex.likedTracks()
        assertEquals(1, liked.size)
        val t = liked[0]
        assertEquals("146160168", t.id)
        assertEquals("39718785", t.albumId)
        assertEquals("kensuke ushio", t.artistLine)
        assertEquals(9, t.trackNumber)
        assertEquals("https://avatars.yandex.net/get-music-content/x/y/1000x1000", t.coverUrl)
        assertEquals("https://music.yandex.ru/album/39718785/track/146160168", t.webUrl)
        assertTrue(t.hasSyncedLyrics)
        // The /tracks request must carry "id:albumId" pairs.
        server.takeRequest(); server.takeRequest()
        val tracksReq = server.takeRequest()
        assertTrue(tracksReq.body!!.utf8().contains("track-ids=146160168%3A39718785"))
        assertEquals("OAuth token", tracksReq.headers["Authorization"])
    }

    @Test fun fileInfoIsSignedAndParsed() = runBlocking {
        server.enqueue(json("""{"result":{"downloadInfo":{"trackId":"1","quality":"lossless","codec":"flac","bitrate":0,"transport":"raw","url":"https://strm.yandex.net/x.flac","urls":[]}}}"""))
        val s = yandex.resolveStream("1", Quality.LOSSLESS)
        assertEquals("flac", s.codec)
        assertEquals("flac", s.extension)
        val req = server.takeRequest()
        val url = req.url
        assertEquals("/get-file-info", url.encodedPath)
        assertEquals("raw", url.queryParameter("transports"))
        assertEquals("flac,flac-mp4,aac-mp4,he-aac-mp4,aac,he-aac,mp3", url.queryParameter("codecs"))
        assertEquals("YandexMusicDesktopAppWindows/5.95.0", req.headers["X-Yandex-Music-Client"])
        assertNotNull(url.queryParameter("sign"))
    }

    @Test fun playlistsWithNumericKinds() = runBlocking {
        server.enqueue(json("""{"result":{"account":{"uid":"42"}}}"""))
        server.enqueue(json("""{"result":[{"uid":42,"kind":1003,"title":"Дорога","trackCount":12,"owner":{"uid":42,"login":"xvin"},"cover":{"type":"pic","uri":"avatars.yandex.net/a/%%"}}]}"""))
        val pls = yandex.playlists()
        assertEquals("42:1003", pls.single().id)
        assertEquals("Дорога", pls.single().title)
    }

    @Test fun likeUsesAddMultiple() = runBlocking {
        server.enqueue(json("""{"result":{"account":{"uid":7}}}"""))
        server.enqueue(json("""{"result":{"revision":12}}"""))
        yandex.setLiked("555", liked = true)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("/users/7/likes/tracks/add-multiple", r.url.encodedPath)
        assertEquals("track-ids=555", r.body!!.utf8())
    }

    @Test fun radioSessionStartAndFeedback() = runBlocking {
        server.enqueue(json("""{"result":{"radioSessionId":"sess-1","batchId":"b-1","pumpkin":false,
            "sequence":[{"type":"track","liked":false,"track":{"id":"10","title":"A","durationMs":1000,"artists":[{"id":5,"name":"X"}],"albums":[{"id":7,"title":"Al"}]}}]}}"""))
        server.enqueue(json("""{"result":"ok"}"""))
        val b = yandex.radioStart("user:onyourwave")
        assertEquals("sess-1", b.sessionId)
        assertEquals("b-1", b.batchId)
        assertEquals(listOf("10"), b.tracks.map { it.id })
        assertEquals(listOf<String?>("5"), b.tracks[0].artistIds)
        val r1 = server.takeRequest()
        assertEquals("/rotor/session/new", r1.url.encodedPath)
        assertTrue(r1.body!!.utf8().contains("\"seeds\":[\"user:onyourwave\"]"))
        yandex.radioFeedback("sess-1", "b-1", "trackStarted", "10")
        val r2 = server.takeRequest()
        assertEquals("/rotor/session/sess-1/feedback", r2.url.encodedPath)
        val body = r2.body!!.utf8()
        assertTrue(body.contains("\"type\":\"trackStarted\"") && body.contains("\"trackId\":\"10\"") && body.contains("\"batchId\":\"b-1\""))
    }

    @Test fun artistBriefInfo() = runBlocking {
        server.enqueue(json("""{"result":{"artist":{"id":"123","name":"1.Kla$","genres":["rusrap"],"likesCount":25000,
            "cover":{"type":"from-artist-photos","uri":"avatars.yandex.net/get-music-content/a/%%"},"description":{"text":"Рэпер"}},
            "albums":[{"id":1,"title":"Сны","year":2023,"coverUri":"avatars.yandex.net/c/%%","artists":[{"id":123,"name":"1.Kla$"}],"type":"single"}],
            "alsoAlbums":[],"popularTracks":[{"id":"9","title":"Сукины дети","durationMs":218000,"artists":[{"id":123,"name":"1.Kla$"}]}],
            "similarArtists":[{"id":"456","name":"Czar"}]}}"""))
        val a = yandex.artistInfo("123")
        assertEquals("1.Kla$", a.artist.name)
        assertEquals("https://avatars.yandex.net/get-music-content/a/600x600", a.artist.coverUrl)
        assertEquals("Рэпер", a.description)
        assertEquals(listOf("Сны"), a.albums.map { it.title })
        assertEquals("Сукины дети", a.popular.single().title)
        assertEquals("Czar", a.similar.single().name)
    }
}

class ListenBrainzTest {
    @Test fun submitsImportBatch() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok"}""").build())
        val lb = dev.xvin.palimpsest.core.scrobble.ListenBrainz(base = server.url("").toString().trimEnd('/'))
        lb.submit("tok", listOf(
            dev.xvin.palimpsest.core.scrobble.ListenBrainz.Listen("Coldplay", "Yellow", "Parachutes", 1_700_000_000, 0),
            dev.xvin.palimpsest.core.scrobble.ListenBrainz.Listen("УННВ", "На грустной ноте", null, 1_700_000_300, 0),
        ))
        val r = server.takeRequest()
        assertEquals("Token tok", r.headers["Authorization"])
        val body = r.body!!.utf8()
        assertTrue(body.contains("\"listen_type\":\"import\""))
        assertTrue(body.contains("\"track_name\":\"На грустной ноте\""))
        server.close()
    }
}
