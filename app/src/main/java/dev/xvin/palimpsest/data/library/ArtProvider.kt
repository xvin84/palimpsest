package dev.xvin.palimpsest.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.xvin.palimpsest.BuildConfig
import dev.xvin.palimpsest.PalimpsestApp
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves cover art as `content://<pkg>.art/track/<id>`. One URI works everywhere: Compose
 * (via Coil), the media notification, the lock screen and Android Auto.
 *
 * Sources in order: embedded picture → cover.jpg/folder.jpg → the service's cover URL.
 * Results are cached on disk keyed by the file's mtime, so edits show up immediately.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException()
        val graph = (ctx.applicationContext as PalimpsestApp).graph
        val track = runBlocking { graph.db.tracks().get(id) } ?: throw FileNotFoundException("no track $id")
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val stamp = if (track.path != null) "${track.modifiedAt}" else track.coverUrl?.hashCode()?.toString() ?: "none"
        val cached = File(dir, "${id}_$stamp.img")
        if (!cached.exists()) {
            val bytes = track.path?.let { TagIO.coverBytes(File(it)) }
                ?: track.coverUrl?.let { url ->
                    runCatching {
                        graph.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (r.isSuccessful) r.body.bytes() else null
                        }
                    }.getOrNull()
                }
                ?: throw FileNotFoundException("no art for $id")
            // Drop older cached versions of this track's art.
            dir.listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.delete() }
            val tmp = File(dir, "${id}_$stamp.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(cached)
        }
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.art"

        fun uriFor(trackId: Long, version: Long = 0): Uri =
            Uri.parse("content://$AUTHORITY/track/$trackId?v=$version")

        /** Forget cached art (e.g. after the cover was edited). */
        fun invalidate(cacheDir: File, trackId: Long) {
            File(cacheDir, "art").listFiles { f -> f.name.startsWith("${trackId}_") }?.forEach { it.delete() }
        }
    }
}
