package dev.xvin.palimpsest.core

import dev.xvin.palimpsest.core.model.RemoteTrack
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.core.radio.RadioFeed
import dev.xvin.palimpsest.core.radio.StationSource
import dev.xvin.palimpsest.core.radio.StationSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    private fun t(id: String) = RemoteTrack(Service.YANDEX, id, "T$id", listOf("A"))
    private val spec = StationSpec(StationSpec.YANDEX, listOf(StationSpec.MY_WAVE), "My Wave")

    /** Hands out scripted batches; counts sessions. */
    private class Fake(val opens: ArrayDeque<List<RemoteTrack>>, val mores: ArrayDeque<Result<List<RemoteTrack>>>) : StationSource {
        var opened = 0
        val avoided = ArrayList<List<String>>()
        override suspend fun open(avoid: List<String>): List<RemoteTrack> { opened++; avoided += avoid; return opens.removeFirstOrNull() ?: emptyList() }
        override suspend fun more(avoid: List<String>): List<RemoteTrack> = (mores.removeFirstOrNull() ?: Result.success(emptyList())).getOrThrow()
    }

    @Test fun repeatsAreDroppedAndADryOrBrokenSessionIsReopened() = runBlocking {
        val fake = Fake(
            ArrayDeque(listOf(listOf(t("1"), t("2")), listOf(t("2"), t("5")), listOf(t("6")))),
            ArrayDeque(listOf(Result.success(listOf(t("1"), t("3:77"), t("3"))), Result.success(listOf(t("1"), t("2"))), Result.failure(java.io.IOException("gone")))),
        )
        val feed = RadioFeed(spec, fake)
        assertEquals(listOf("1", "2"), feed.start().map { it.id })
        // «3:77» and «3» are the same song; «1» was already played.
        assertEquals(listOf("3:77"), feed.next().map { it.id })
        // Only repeats → a new session, which avoids what was heard.
        assertEquals(listOf("5"), feed.next().map { it.id })
        assertEquals(2, fake.opened)
        assertTrue(fake.avoided[1].containsAll(listOf("YANDEX:1", "YANDEX:2", "YANDEX:3")))
        // A failing session → a new one.
        assertEquals(listOf("6"), feed.next().map { it.id })
        assertEquals(3, fake.opened)
    }

    @Test fun aRestoredStationOpensANewSessionAndRemembersWhatItPlayed() = runBlocking {
        val first = RadioFeed(spec, Fake(ArrayDeque(listOf(listOf(t("1"), t("2")))), ArrayDeque()))
        first.start()
        val saved = first.save()
        val fake = Fake(ArrayDeque(listOf(listOf(t("2"), t("9")))), ArrayDeque())
        val restored = RadioFeed(saved.spec, fake, saved.heard)
        assertEquals(listOf("9"), restored.next().map { it.id })
        assertEquals(1, fake.opened)
    }

    @Test fun finishedMeansNearTheEnd() = runBlocking {
        var last: Boolean? = null
        val src = object : StationSource {
            override suspend fun open(avoid: List<String>) = emptyList<RemoteTrack>()
            override suspend fun more(avoid: List<String>) = emptyList<RemoteTrack>()
            override suspend fun ended(track: RemoteTrack, playedSec: Double, finished: Boolean) { last = finished }
        }
        val feed = RadioFeed(spec, src)
        feed.ended(t("1"), 175_000, 180_000); assertEquals(true, last)
        feed.ended(t("1"), 20_000, 180_000); assertEquals(false, last)
    }
}
