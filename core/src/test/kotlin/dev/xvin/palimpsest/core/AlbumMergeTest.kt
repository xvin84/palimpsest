package dev.xvin.palimpsest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMergeTest {
    private fun r(id: String, title: String) = dev.xvin.palimpsest.core.model.RemoteTrack(dev.xvin.palimpsest.core.model.Service.YANDEX, id, title, listOf("Taylor Swift"))

    @Test
    fun yoursTakeTheirPlaceInTheServiceOrder() {
        data class L(val remoteId: String?, val title: String)
        val remote = listOf(r("1", "All Too Well"), r("2", "Wildest Dreams"), r("3", "I Knew You Were Trouble"))
        val mine = listOf(L("3", "I Knew You Were Trouble"), L(null, "Wildest Dreams (feat. Nobody)"), L(null, "Bonus"))
        val rows = dev.xvin.palimpsest.core.library.AlbumMerge.merge(remote, mine, { it.remoteId }, { it.title })
        assertEquals(4, rows.size)
        assertTrue(rows[0] is dev.xvin.palimpsest.core.library.AlbumMerge.Row.Theirs)
        assertEquals("Wildest Dreams (feat. Nobody)", (rows[1] as dev.xvin.palimpsest.core.library.AlbumMerge.Row.Mine).track.title)
        assertEquals("3", (rows[2] as dev.xvin.palimpsest.core.library.AlbumMerge.Row.Mine).track.remoteId)
        assertEquals("Bonus", (rows[3] as dev.xvin.palimpsest.core.library.AlbumMerge.Row.Mine).track.title)
    }
}
