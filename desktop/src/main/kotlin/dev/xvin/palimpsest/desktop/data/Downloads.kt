package dev.xvin.palimpsest.desktop.data

import dev.xvin.palimpsest.core.crypto.AesCtr
import dev.xvin.palimpsest.core.library.FileNames
import dev.xvin.palimpsest.core.model.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

data class DownloadState(val queued: Int = 0, val current: String? = null, val progress: Float = 0f, val lastError: String? = null)

/**
 * Downloads service tracks into the download folder (`<dir>/<Service>/Artist - Title.ext`), with
 * tags, cover, lyrics and the URL tag that links the file back to its service track.
 */
class Downloads(private val graph: Graph) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    init {
        graph.scope.launch {
            for (id in queue) {
                runCatching { download(id) }.onFailure { e -> _state.value = _state.value.copy(lastError = e.message ?: e.toString()) }
                _state.value = _state.value.copy(queued = (_state.value.queued - 1).coerceAtLeast(0), current = null, progress = 0f)
            }
        }
    }

    fun enqueue(ids: List<String>) {
        _state.value = _state.value.copy(queued = _state.value.queued + ids.size)
        ids.forEach { queue.trySend(it) }
    }

    private fun folder(s: Service) = when (s) {
        Service.YANDEX -> "Яндекс"
        Service.YOUTUBE -> "YouTube"
        Service.BANDCAMP -> "Bandcamp"
        else -> "Разное"
    }

    private suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val t = graph.library.get(id) ?: return@withContext
        if (t.path != null && File(t.path).isFile) return@withContext
        val rid = t.remoteId ?: return@withContext
        val provider = graph.provider(t.service) ?: return@withContext
        _state.value = _state.value.copy(current = "${t.artist} — ${t.title}", progress = 0f)
        val info = provider.resolveStream(rid, graph.settings.value.quality, forDownload = true)
        val dir = File(graph.settings.value.downloadDir, folder(t.service)).apply { mkdirs() }
        val tmp = File(dir, ".download-${rid.hashCode().toUInt()}.${info.extension}")
        graph.http.newCall(Request.Builder().url(info.url).apply { info.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val total = resp.body.contentLength()
            val input = info.aesCtrKey?.let { AesCtr.decrypting(resp.body.byteStream(), it) } ?: resp.body.byteStream()
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) _state.value = _state.value.copy(progress = done.toFloat() / total)
                }
            }
        }
        val meta = runCatching { provider.track(rid) }.getOrNull()
        val lyrics = runCatching { graph.lyrics.best(t) }.getOrNull()
        Tags.write(
            tmp, meta?.title ?: t.title, meta?.artistLine?.ifBlank { null } ?: t.artist, meta?.album ?: t.album, meta?.year ?: t.year,
            meta?.trackNumber ?: t.trackNumber, lyrics?.synced ?: lyrics?.plain, meta?.webUrl, (meta?.coverUrl ?: t.coverUrl)?.let(graph.covers::bytes),
        )
        var target = File(dir, FileNames.sanitize("${t.artist} - ${t.title}") + "." + info.extension)
        if (target.exists()) target = File(dir, FileNames.sanitize("${t.artist} - ${t.title} ($rid)") + "." + info.extension)
        if (!tmp.renameTo(target)) { tmp.copyTo(target); tmp.delete() }
        graph.library.upsert(t.copy(path = target.absolutePath, sizeBytes = target.length(), modifiedAt = target.lastModified(), codec = info.codec, bitrateKbps = info.bitrateKbps))
    }
}
