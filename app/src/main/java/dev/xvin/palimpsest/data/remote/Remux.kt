package dev.xvin.palimpsest.data.remote

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * YouTube serves audio as fragmented (DASH) MP4. Players handle it, but tag writers and some
 * older players don't. Re-muxing into a plain MP4 is lossless (no re-encoding) and quick.
 */
object Remux {
    /** True if the MP4 contains `moof` boxes, i.e. is fragmented. Only scans top-level boxes. */
    fun isFragmentedMp4(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val len = raf.length()
            val header = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(header)
                var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (type == "moof") return@use true
                if (size == 1L) { // 64-bit size follows
                    val big = ByteArray(8); raf.readFully(big); size = ByteBuffer.wrap(big).long
                } else if (size == 0L) break
                if (size < 8) break
                pos += size
            }
            false
        }
    }.getOrDefault(false)

    /** Copies the audio track of [src] into a regular MP4 at [dst]. */
    fun toPlainMp4(src: File, dst: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
            val buf = ByteBuffer.allocate(maxSize.coerceAtLeast(256 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.size = extractor.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
