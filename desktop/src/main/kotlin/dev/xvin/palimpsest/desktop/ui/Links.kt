package dev.xvin.palimpsest.desktop.ui

import dev.xvin.palimpsest.core.links.MusicLink
import dev.xvin.palimpsest.core.links.SongLink
import dev.xvin.palimpsest.core.model.SearchType
import dev.xvin.palimpsest.core.model.Service
import dev.xvin.palimpsest.desktop.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether [text] holds a music link the app can open (its own services, or one found through song.link). */
fun hasMusicLink(text: String): Boolean = MusicLink.find(text) != null || SongLink.find(text) != null

/**
 * Opens a music link — pasted anywhere in the window (Ctrl+V), dropped on it, or typed into search.
 * Yandex, YouTube and Bandcamp open directly; Spotify, Apple Music, Deezer, SoundCloud… are looked up
 * on song.link and the same song plays from a service that works here (YouTube when Yandex isn't
 * signed in).
 */
fun openLink(graph: Graph, nav: Nav, text: String) {
    MusicLink.find(text)?.let { open(graph, nav, it); return }
    val foreign = SongLink.find(text) ?: run { nav.toast(t("Тут нет ссылки на музыку", "No music link in that")); return }
    nav.toast(t("Ищу эту песню в сервисах, которые здесь играют…", "Looking for this song on the services that play here…"))
    graph.scope.launch {
        val found = runCatching { SongLink.resolve(graph.http, foreign, yandexFirst = graph.yandex.isReady) }.getOrNull()
        // song.link gives no YouTube links to some countries: then the song by its name.
        val link = found?.link?.takeIf { it.service != Service.YANDEX || graph.yandex.isReady }
            ?: found?.title?.let { title ->
                runCatching {
                    graph.youtube.searchAll("${found.artist.orEmpty()} $title".trim(), SearchType.TRACKS).tracks.firstOrNull()
                        ?.let { MusicLink(Service.YOUTUBE, MusicLink.Kind.TRACK, it.id) }
                }.getOrNull()
            }
            ?: found?.link
        withContext(Dispatchers.Main) {
            if (link != null) open(graph, nav, link)
            else nav.toast(t("Не нашёл эту песню ни в Яндекс Музыке, ни на YouTube", "Couldn't find this song on Yandex Music or YouTube"))
        }
    }
}

private fun open(graph: Graph, nav: Nav, link: MusicLink) {
    when (link.kind) {
        MusicLink.Kind.ALBUM -> nav.go(Screen.RemoteAlbum(link.id, link.service))
        MusicLink.Kind.ARTIST -> nav.go(Screen.Artist("", link.id, link.service))
        MusicLink.Kind.PLAYLIST -> nav.go(Screen.RemotePlaylist(link.service, link.id, ""))
        MusicLink.Kind.TRACK -> graph.scope.launch {
            val r = runCatching { graph.provider(link.service)?.track(link.id) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (r != null) graph.player.play(listOf(graph.library.ensureRemote(r)))
                else nav.toast(
                    t("Не получилось открыть в ${link.service.displayName}", "Couldn't open it on ${link.service.displayName}") +
                        when {
                            link.service == Service.YANDEX && !graph.yandex.isReady -> t(" — сначала войди в Яндекс Музыку в «Сервисах».", " — sign in to Yandex Music in Services first.")
                            link.service == Service.YOUTUBE -> t(" — YouTube может открываться только через прокси (Настройки).", " — YouTube may need a proxy (Settings).")
                            else -> ""
                        },
                )
            }
        }
    }
}

/** Text on the clipboard, if there is any. */
fun clipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()
