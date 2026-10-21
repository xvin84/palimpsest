package dev.xvin.palimpsest.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover art as something Coil can load: a service track's URL, or a local file's embedded picture
 * extracted once into the cache (or a cover.jpg / folder.jpg next to it).
 */
class Covers(private val graph: Graph) {
    private val dir = File(Paths.cache, "art").apply { mkdirs() }
    private val known = ConcurrentHashMap<String, Any>()
    private val none = Any()

    suspend fun model(t: Track?): Any? {
        t ?: return null
        val path = t.path ?: return t.coverUrl
        known[t.id]?.let { return if (it === none) t.coverUrl else it }
        return withContext(Dispatchers.IO) {
            val out = File(dir, "${(path + t.modifiedAt).hashCode().toUInt()}.img")
            val found: File? = when {
                out.isFile -> out
                else -> runCatching { AudioFileIO.read(File(path)).tag?.firstArtwork?.binaryData }.getOrNull()
                    ?.takeIf { it.size > 100 }?.let { out.writeBytes(it); out }
                    ?: File(path).parentFile?.let { d -> listOf("cover.jpg", "folder.jpg", "cover.png", "front.jpg").map { File(d, it) }.firstOrNull { it.isFile } }
            }
            known[t.id] = found ?: none
            found ?: t.coverUrl
        }
    }

    /** Raw bytes, e.g. for writing into a downloaded file's tag. */
    fun bytes(url: String): ByteArray? = runCatching {
        graph.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    }.getOrNull()
}
