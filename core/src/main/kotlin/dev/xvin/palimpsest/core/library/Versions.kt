package dev.xvin.palimpsest.core.library

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Logic behind track version history. Artists sometimes replace a track on a service with a
 * remaster, a censored cut or a completely different mix under the same ID. Instead of
 * overwriting, we keep each distinct audio as its own version.
 */
object Versions {
    /**
     * Durations further apart than this are considered different recordings. Generous enough
     * for VBR MP3 length estimates and services rounding to the second.
     */
    const val DURATION_TOLERANCE_MS = 2_000L

    enum class Change { SAME, DIFFERENT_DURATION, DIFFERENT_AUDIO, UNKNOWN }

    fun compare(localDurationMs: Long, remoteDurationMs: Long): Change = when {
        localDurationMs <= 0 || remoteDurationMs <= 0 -> Change.UNKNOWN
        abs(localDurationMs - remoteDurationMs) > DURATION_TOLERANCE_MS -> Change.DIFFERENT_DURATION
        else -> Change.SAME
    }

    /**
     * Hash of the audio payload that ignores tag edits. FLAC and MP3 keep tags (and the cover) at
     * the start of the file, so we hash the last 1 MiB, skipping a trailing ID3v1 block. Fast on a
     * phone, and it changes whenever the audio does. (In M4A the metadata may sit at the end, so a
     * tag edit there can look like a new version — acceptable for a rarely edited format.)
     */
    fun audioFingerprint(file: File): String {
        val len = file.length()
        val tail = if (len > 128 && hasId3v1(file, len)) 128L else 0L
        val window = 1L shl 20
        val end = len - tail
        val start = (end - window).coerceAtLeast(0)
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            input.skipFully(start)
            val buf = ByteArray(64 * 1024)
            var remaining = end - start
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun hasId3v1(file: File, len: Long): Boolean =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - 128)
            val b = ByteArray(3)
            raf.readFully(b)
            b[0] == 'T'.code.toByte() && b[1] == 'A'.code.toByte() && b[2] == 'G'.code.toByte()
        }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) return; left-- } else left -= s
        }
    }
}
