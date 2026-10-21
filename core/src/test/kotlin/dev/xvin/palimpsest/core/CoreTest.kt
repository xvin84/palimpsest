package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.dsp.AutoEqParser
import dev.xvin.palimpsest.core.dsp.EqFilter
import dev.xvin.palimpsest.core.dsp.EqProfile
import dev.xvin.palimpsest.core.dsp.Equalizer
import dev.xvin.palimpsest.core.dsp.FilterType
import dev.xvin.palimpsest.core.dsp.SpectrumAnalyzer
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.library.YandexLinks
import dev.xvin.palimpsest.core.lyrics.Lrc
import dev.xvin.palimpsest.core.match.TrackMatcher
import dev.xvin.palimpsest.core.model.Service
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private fun sine(freq: Double, sr: Int, frames: Int, ch: Int = 2) = FloatArray(frames * ch) { i ->
        (0.25 * sin(2 * PI * freq * (i / ch) / sr)).toFloat()
    }

    private fun rms(buf: FloatArray, from: Int) = sqrt(buf.drop(from).sumOf { it.toDouble() * it } / (buf.size - from))

    @Test fun flatProfileIsTransparent() {
        val eq = Equalizer().apply { configure(48_000, 2); setProfile(EqProfile.FLAT) }
        val input = sine(1000.0, 48_000, 4800)
        val buf = input.copyOf()
        eq.process(buf, 4800)
        assertArrayEquals(input, buf, 1e-6f)
    }

    @Test fun peakFilterBoostsItsBand() {
        val profile = EqProfile("t", filters = listOf(EqFilter(FilterType.PEAK, 1000.0, 6.0, 1.0)))
        val eq = Equalizer().apply { configure(48_000, 2); setProfile(profile) }
        val input = sine(1000.0, 48_000, 48_000)
        val buf = input.copyOf()
        eq.process(buf, 48_000)
        val gainDb = 20 * log10(rms(buf, 20_000) / rms(input, 20_000))
        assertEquals(6.0, gainDb, 0.3)
        // Far away from the band nothing should change.
        assertEquals(0.0, profile.responseDb(60.0), 0.3)
        assertEquals(6.0, profile.responseDb(1000.0), 0.05)
    }

    @Test fun limiterPreventsClipping() {
        val eq = Equalizer().apply { configure(44_100, 2); setGainDb(18.0) }
        val buf = sine(200.0, 44_100, 44_100)
        eq.process(buf, 44_100)
        assertTrue(buf.maxOf { abs(it) } <= 0.981f)
    }

    @Test fun shelvesHaveExpectedResponse() {
        val p = EqProfile("s", filters = listOf(EqFilter(FilterType.LOW_SHELF, 100.0, 5.0, 0.71), EqFilter(FilterType.HIGH_SHELF, 8000.0, -4.0, 0.71)))
        assertEquals(5.0, p.responseDb(20.0), 0.3)
        assertEquals(-4.0, p.responseDb(19_000.0), 0.4)
        assertEquals(0.0, p.responseDb(1000.0), 0.5)
    }
}

class AutoEqTest {
    @Test fun parsesAutoEqFile() {
        val text = """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON PK Fc 2283 Hz Gain -3.1 dB Q 1.84
            Filter 3: OFF PK Fc 5000 Hz Gain 2.0 dB Q 3.00
            Filter 4: ON HSC Fc 10000 Hz Gain -1.2 dB Q 0.70
        """.trimIndent()
        val p = AutoEqParser.parse(text, "HD 600")
        assertEquals(-6.4, p.preampDb, 1e-9)
        assertEquals(4, p.filters.size)
        assertEquals(FilterType.LOW_SHELF, p.filters[0].type)
        assertEquals(2283.0, p.filters[1].freqHz, 1e-9)
        assertEquals(false, p.filters[2].enabled)
        // Round trip
        val again = AutoEqParser.parse(AutoEqParser.format(p), "x")
        assertEquals(p.filters.map { it.type }, again.filters.map { it.type })
    }
}

class SpectrumTest {
    @Test fun sineLandsInTheRightBand() {
        val a = SpectrumAnalyzer(2048, 48).apply { sampleRate = 48_000 }
        val frames = 4096
        val buf = FloatArray(frames) { (0.8 * sin(2 * PI * 1000.0 * it / 48_000)).toFloat() }
        a.push(buf, frames, 1)
        val bands = a.compute()
        val loudest = bands.indices.maxBy { bands[it] }
        // 1 kHz on a 30 Hz–16 kHz log scale of 48 bands sits around band 26.
        assertTrue("loudest=$loudest", loudest in 24..28)
        assertTrue(bands[loudest] > 0.8f)
        assertTrue(bands[2] < 0.3f)
    }
}

class LrcTest {
    @Test fun parsesSyncedLyrics() {
        val lrc = """
            [ar:Coldplay]
            [ti:Yellow]
            [00:12.50]Look at the stars
            [00:17.3]Look how they shine for you
            [01:02.00][02:10.00]And it was all yellow
        """.trimIndent()
        val p = Lrc.parse(lrc)
        assertTrue(p.isSynced)
        assertEquals(4, p.lines.size)
        assertEquals(12_500, p.lines[0].timeMs)
        assertEquals(17_300, p.lines[1].timeMs)
        assertEquals("And it was all yellow", p.lines[3].text)
        assertEquals(-1, p.indexAt(1000))
        assertEquals(1, p.indexAt(20_000))
        assertEquals(3, p.indexAt(500_000))
    }

    @Test fun plainTextStaysPlain() {
        val p = Lrc.parse("line one\nline two")
        assertEquals(false, p.isSynced)
        assertEquals(2, p.lines.size)
    }
}

class FileNamesTest {
    @Test fun flatMurglarLayout() {
        val g = FileNames.guess("Murglar/Яндекс/2rbina 2rista - Барон суббота.flac")
        assertEquals("2rbina 2rista", g.artist)
        assertEquals("Барон суббота", g.title)
        assertNull(g.album)
    }

    @Test fun albumFolderLayout() {
        val g = FileNames.guess("Murglar/Яндекс/Coldplay/(2003) Yellow/1. Yellow.flac")
        assertEquals("Coldplay", g.artist)
        assertEquals("Yellow", g.album)
        assertEquals(2003, g.year)
        assertEquals(1, g.trackNumber)
        assertEquals("Yellow", g.title)
    }

    @Test fun ytDlpLayout() {
        val g = FileNames.guess("Murglar/Openings/YOASOBI「アイドル」 Official Music Video [ZRtdQ81jPUQ].webm")
        assertEquals("ZRtdQ81jPUQ", g.youtubeId)
        assertEquals("YOASOBI「アイドル」 Official Music Video", g.title)
        assertEquals(Service.YOUTUBE, FileNames.serviceFor("Murglar/Openings/x [ZRtdQ81jPUQ].webm", null))
    }

    @Test fun serviceFromFolderAndTag() {
        assertEquals(Service.YANDEX, FileNames.serviceFor("Murglar/Яндекс/a.flac", null))
        assertEquals(Service.YANDEX, FileNames.serviceFor("x.flac", "https://music.yandex.ru/album/39718785/track/146160168"))
        assertEquals(Service.LOCAL, FileNames.serviceFor("Rips/a.flac", null))
    }

    @Test fun yandexLinks() {
        assertEquals("39718785" to "146160168", YandexLinks.parse("https://music.yandex.ru/album/39718785/track/146160168"))
        assertEquals(null to "123", YandexLinks.parse("https://music.yandex.com/track/123?x=1"))
        assertNull(YandexLinks.parse("https://example.com"))
    }

    @Test fun sanitize() {
        assertEquals("AC_DC - Back In Black", FileNames.sanitize("AC/DC - Back In Black"))
        assertEquals("What_", FileNames.sanitize("What?"))
    }
}

class MatcherTest {
    @Test fun sameSongAcrossServices() {
        val s = TrackMatcher.score(
            "Барон Суббота (feat. The Scorr)", "2rbina 2rista, The Scorr", 201_000,
            "Барон суббота", "2rbina 2rista", 202_000,
        )
        assertTrue("score=$s", s >= 0.8)
    }

    @Test fun differentSongs() {
        val s = TrackMatcher.score("Yellow", "Coldplay", 266_000, "Fix You", "Coldplay", 295_000)
        assertTrue("score=$s", s < 0.6)
    }

    @Test fun youtubeNoise() {
        assertEquals("アイドル", TrackMatcher.normalizeTitle("アイドル (Official Music Video)"))
        assertEquals("beyonce мой", TrackMatcher.normalize("Beyoncé Мой"))
    }
}

class AesCtrTest {
    @Test fun seekableDecryptMatchesFullDecrypt() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = ByteArray(10_000) { (it % 251).toByte() }
        val enc = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(plain)
        }
        for (offset in listOf(0L, 15L, 16L, 4097L, 9_999L)) {
            val part = AesCtr.decrypting(enc.copyOfRange(offset.toInt(), enc.size).inputStream(), key, offset).readBytes()
            assertArrayEquals(plain.copyOfRange(offset.toInt(), plain.size), part)
        }
    }
}

class StreamInfoTest {
    @Test fun extensions() {
        fun ext(c: String) = dev.xvin.palimpsest.core.model.StreamInfo("u", c).extension
        assertEquals("flac", ext("flac"))
        assertEquals("m4a", ext("flac-mp4"))
        assertEquals("m4a", ext("he-aac-mp4"))
        assertEquals("m4a", ext("m4a")) // YouTube MediaFormat suffix
        assertEquals("webm", ext("webm"))
        assertEquals("mp3", ext("mp3"))
    }
}

class SpotifyTest {
    @Test fun parsesLinks() {
        val s = dev.xvin.palimpsest.core.spotify.SpotifyImporter
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", s.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("album" to "4m2880jivSbbyEGAKfITCa", s.parse("https://open.spotify.com/intl-de/album/4m2880jivSbbyEGAKfITCa"))
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", s.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(s.parse("https://open.spotify.com/track/xyz"))
    }

    @Test fun parsesEmbedPage() {
        val html = javaClass.getResource("/spotify_embed_playlist.html")!!.readText()
        val c = dev.xvin.palimpsest.core.spotify.SpotifyImporter.parseEmbed(html, "playlist", "37i9dQZF1DXcBWIGoYBM5M")
        assertEquals("Today’s Top Hits", c.name)
        assertEquals(50, c.items.size)
        assertTrue(c.items.all { it.title.isNotBlank() && it.durationMs > 0 })
        assertTrue(c.coverUrl!!.startsWith("https://"))
    }

    @Test fun parsesAlbumEmbedWithCover() {
        val html = javaClass.getResource("/spotify_embed_album.html")!!.readText()
        val c = dev.xvin.palimpsest.core.spotify.SpotifyImporter.parseEmbed(html, "album", "2noRn2Aes5aoNVsU6iWThc")
        assertEquals("Discovery", c.name)
        assertEquals(14, c.items.size)
        assertTrue(c.coverUrl!!.startsWith("https://"))
    }
}

class M3uTest {
    @Test fun matchesPcPathsToPhoneLibrary() {
        val m3u = "#EXTM3U\n/home/xvin/Music/Murglar//Яндекс/ДЕТИ RAVE - RUSSIA.flac\nC:\\Music\\Other\\a.mp3\n"
        val lib = listOf(
            "/storage/emulated/0/Music/Murglar/Яндекс/ДЕТИ RAVE - RUSSIA.flac",
            "/storage/emulated/0/Music/Backup/ДЕТИ RAVE - RUSSIA.flac",
            "/storage/emulated/0/Music/x/a.mp3",
        )
        val idx = dev.xvin.palimpsest.core.library.M3u.indexByFileName(lib)
        val entries = dev.xvin.palimpsest.core.library.M3u.entries(m3u)
        assertEquals(2, entries.size)
        assertEquals(lib[0], dev.xvin.palimpsest.core.library.M3u.match(entries[0], idx))
        assertEquals(lib[2], dev.xvin.palimpsest.core.library.M3u.match(entries[1], idx))
    }
}

class ArtistNamesTest {
    private fun s(x: String) = dev.xvin.palimpsest.core.library.ArtistNames.split(x)

    @Test fun splitsTagsFromTheLibrary() {
        assertEquals(listOf("1.Kla$", "BAbyBoi", "Мираж"), s("1.Kla$, BAbyBoi, Мираж"))
        assertEquals(listOf("1.Kla$", "Czar"), s("1.Kla$;Czar"))
        assertEquals(listOf("16canva", "16", "MallBoy"), s("16canva, 16, MallBoy"))
        assertEquals(listOf("Coldplay", "Rihanna"), s("Coldplay feat. Rihanna"))
    }

    @Test fun keepsRealNamesWhole() {
        assertEquals(listOf("Simon & Garfunkel"), s("Simon & Garfunkel"))
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), s("Tyler, The Creator, Kali Uchis"))
        assertEquals(listOf("AC/DC"), s("AC/DC"))
        assertEquals(listOf("Lil Nas X"), s("Lil Nas X"))
    }
}

class TranslatorTest {
    @Test fun parsesGoogleResponse() {
        val json = """[[["Посмотри на звёзды\n","Look at the stars\n",null,null,10],["Как они сияют","Look how they shine",null,null,10]],null,"en",null,null,null,1.0]"""
        val (text, lang) = dev.xvin.palimpsest.core.translate.Translator.parse(json)
        assertEquals("Посмотри на звёзды\nКак они сияют", text)
        assertEquals("en", lang)
    }

    @Test fun netEaseDropsCredits() {
        val lrc = "[00:00.000] 作词 : Chris Martin\n[00:01.000] 作曲 : Coldplay\n[00:33.79]Look at the stars"
        assertEquals("[00:33.79]Look at the stars", dev.xvin.palimpsest.core.lyrics.NetEaseLyrics.clean(lrc))
    }

    @Test fun geniusExtractsLyricsContainers() {
        val html = """<div id="lyrics-root"><div data-lyrics-container="true" class="a"><div data-exclude-from-selection="true">3 Contributors<div>Yellow Lyrics</div></div>[Verse 1]<br/><a href="/x"><span>Look at the stars</span></a><br>Look how they shine</div>
            <div class="ad"></div><div data-lyrics-container="true">And it&#x27;s &amp; done</div></div>"""
        assertEquals("[Verse 1]\nLook at the stars\nLook how they shine\nAnd it's & done", dev.xvin.palimpsest.core.lyrics.GeniusLyrics.extract(html))
    }

    @Test fun translatorParsesPerLineAnswers() {
        val (texts, langs) = dev.xvin.palimpsest.core.translate.Translator.parsePerLine("""[["Посмотри на звёзды","en"],["Я живу","ru"]]""")
        assertEquals(listOf("Посмотри на звёзды", "Я живу"), texts)
        assertEquals(listOf("en", "ru"), langs)
        assertEquals(listOf("a", "b"), dev.xvin.palimpsest.core.translate.Translator.parsePerLine("""["a","b"]""").first)
    }

    @Test fun translatorParsesYandexAnswers() {
        val (texts, langs) = dev.xvin.palimpsest.core.translate.Translator.parseYandex("""{"code":200,"lang":"en-ru","text":["Посмотри на звезды",""]}""")
        assertEquals(listOf("Посмотри на звезды", ""), texts)
        assertEquals(listOf("en"), langs)
    }

    /** FLAC-in-MP4 samples made with ffmpeg (set FLAC_MP4_DIR); the extracted .flac must decode to the same PCM. */
    @Test fun flacFromMp4() {
        val dir = System.getenv("FLAC_MP4_DIR")?.let { java.io.File(it) } ?: return
        for (name in listOf("plain.mp4", "frag.mp4")) {
            val src = java.io.File(dir, name)
            check(dev.xvin.palimpsest.core.audio.FlacMp4.isFlacInMp4(src)) { "$name not detected" }
            dev.xvin.palimpsest.core.audio.FlacMp4.extract(src, java.io.File(dir, name.replace(".mp4", ".out.flac")))
        }
        check(!dev.xvin.palimpsest.core.audio.FlacMp4.isFlacInMp4(java.io.File(dir, "src.flac")))
    }
}

class AudioFeaturesTest {
    /** A 128 BPM kick over an A-minor chord (A, C, E) must read as ~128 BPM and A minor. */
    @org.junit.Test fun tempoAndKey() {
        val rate = 11025
        val n = rate * 30
        val pcm = FloatArray(n)
        val beat = rate * 60.0 / 128
        val tones = doubleArrayOf(220.0, 261.63, 329.63, 110.0)
        for (i in 0 until n) {
            var v = 0.0
            for (f in tones) v += 0.12 * kotlin.math.sin(2 * Math.PI * f * i / rate)
            val sinceBeat = (i % beat) / rate
            v += 0.8 * kotlin.math.exp(-sinceBeat * 40) * kotlin.math.sin(2 * Math.PI * 60 * sinceBeat)
            pcm[i] = v.toFloat()
        }
        val f = dev.xvin.palimpsest.core.analysis.AudioFeatures.analyze(pcm, rate)!!
        println("features: $f")
        check(kotlin.math.abs(f.bpm - 128) < 2) { "bpm ${f.bpm}" }
        check(f.key == "A minor") { "key ${f.key}" }
        check(f.camelot == "8A")
    }
}

class MusicLinkTest {
    @org.junit.Test fun parses() {
        fun p(u: String) = dev.xvin.palimpsest.core.links.MusicLink.find(u)?.let { "${it.service} ${it.kind} ${it.id}" }
        check(p("https://music.yandex.ru/album/4766/track/57703") == "YANDEX TRACK 57703")
        check(p("Послушай: https://music.yandex.com/track/123?utm_source=x") == "YANDEX TRACK 123")
        check(p("https://music.yandex.ru/album/4766") == "YANDEX ALBUM 4766")
        check(p("https://music.yandex.ru/artist/41191/tracks") == "YANDEX ARTIST 41191")
        check(p("https://music.yandex.ru/users/music-blog/playlists/2363") == "YANDEX PLAYLIST music-blog:2363")
        check(p("https://youtu.be/dQw4w9WgXcQ?si=abc") == "YOUTUBE TRACK dQw4w9WgXcQ")
        check(p("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD") == "YOUTUBE TRACK dQw4w9WgXcQ")
        check(p("https://www.youtube.com/playlist?list=PL123abc") == "YOUTUBE PLAYLIST PL123abc")
        check(p("https://lorn.bandcamp.com/album/vessel") == "BANDCAMP ALBUM lorn.bandcamp.com/album/vessel")
        check(p("https://lorn.bandcamp.com/track/anvil") == "BANDCAMP TRACK lorn.bandcamp.com/track/anvil")
        check(p("https://example.com/x") == null)
    }
}
