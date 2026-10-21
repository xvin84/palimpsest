package dev.xvin.palimpsest.core.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * FLAC packed in MP4 (Yandex's `flac-mp4`) back into a plain `.flac`, bit for bit: the FLAC
 * metadata comes from the `dfLa` box, the frames are copied as they are. Plain and fragmented
 * (DASH, `moof`/`trun`) MP4 both work. A `.flac` is what every player and tag editor expects; the
 * same audio in an `.m4a` was mistaken for AAC.
 */
object FlacMp4 {
    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val bodyStart get() = start + headerSize
        val end get() = start + size
    }

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = raf.readLong(); header = 16 } else if (size == 0L) size = to - pos
            if (size < header) break
            out += Box(type, pos, header, size)
            pos += size
        }
        return out
    }

    private fun child(raf: RandomAccessFile, parent: Box, type: String, skip: Int = 0) =
        boxes(raf, parent.bodyStart + skip, parent.end).firstOrNull { it.type == type }

    private fun path(raf: RandomAccessFile, root: Box, vararg types: String): Box? {
        var cur: Box = root
        for (t in types) cur = child(raf, cur, t) ?: return null
        return cur
    }

    /** The `fLaC` sample entry of the audio track, or null if this MP4 doesn't hold FLAC. */
    private fun flacEntry(raf: RandomAccessFile): Box? {
        val moov = boxes(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return null
        for (trak in boxes(raf, moov.bodyStart, moov.end).filter { it.type == "trak" }) {
            val stsd = path(raf, trak, "mdia", "minf", "stbl", "stsd") ?: continue
            // stsd: version/flags (4) + entry count (4), then the sample entries.
            boxes(raf, stsd.bodyStart + 8, stsd.end).firstOrNull { it.type == "fLaC" }?.let { return it }
        }
        return null
    }

    fun isFlacInMp4(file: File): Boolean = runCatching { RandomAccessFile(file, "r").use { flacEntry(it) != null } }.getOrDefault(false)

    fun extract(src: File, dst: File) {
        RandomAccessFile(src, "r").use { raf ->
            val top = boxes(raf, 0, raf.length())
            val moov = top.firstOrNull { it.type == "moov" } ?: throw IOException("no moov box")
            val entry = flacEntry(raf) ?: throw IOException("not FLAC in MP4")
            // AudioSampleEntry fields take 28 bytes before its child boxes.
            val dfla = boxes(raf, entry.bodyStart + 28, entry.end).firstOrNull { it.type == "dfLa" } ?: throw IOException("no dfLa box")
            raf.seek(dfla.bodyStart + 4) // version/flags
            val meta = ByteArray((dfla.end - dfla.bodyStart - 4).toInt()).also { raf.readFully(it) }
            fixLastBlockFlag(meta)

            dst.outputStream().buffered(1 shl 16).use { out ->
                out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                out.write(meta)
                val buf = ByteArray(1 shl 16)
                fun copy(offset: Long, size: Long) {
                    raf.seek(offset)
                    var left = size
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("truncated sample data")
                        out.write(buf, 0, n); left -= n
                    }
                }
                val moofs = top.filter { it.type == "moof" }
                if (moofs.isNotEmpty()) moofs.forEach { moof -> fragmentSamples(raf, moof).forEach { (o, s) -> copy(o, s) } }
                else plainSamples(raf, moov).forEach { (o, s) -> copy(o, s) }
            }
        }
    }

    /** Exactly one metadata block (the last) must carry the «last block» bit. */
    private fun fixLastBlockFlag(meta: ByteArray) {
        var pos = 0
        while (pos + 4 <= meta.size) {
            val len = ((meta[pos + 1].toInt() and 0xff) shl 16) or ((meta[pos + 2].toInt() and 0xff) shl 8) or (meta[pos + 3].toInt() and 0xff)
            val last = pos + 4 + len >= meta.size
            meta[pos] = if (last) (meta[pos].toInt() or 0x80).toByte() else (meta[pos].toInt() and 0x7f).toByte()
            pos += 4 + len
        }
    }

    /** (offset, size) of every sample of a non-fragmented file, via stsz/stsc/stco. */
    private fun plainSamples(raf: RandomAccessFile, moov: Box): List<Pair<Long, Long>> {
        val trak = boxes(raf, moov.bodyStart, moov.end).first { it.type == "trak" && path(raf, it, "mdia", "minf", "stbl", "stsd") != null }
        val stbl = path(raf, trak, "mdia", "minf", "stbl")!!
        val stsz = child(raf, stbl, "stsz") ?: throw IOException("no stsz")
        raf.seek(stsz.bodyStart + 4)
        val fixed = raf.readInt().toLong() and 0xffffffffL
        val count = raf.readInt()
        val sizes = LongArray(count) { if (fixed != 0L) fixed else raf.readInt().toLong() and 0xffffffffL }
        val chunkOffsets: LongArray = child(raf, stbl, "stco")?.let { b ->
            raf.seek(b.bodyStart + 4); val n = raf.readInt(); LongArray(n) { raf.readInt().toLong() and 0xffffffffL }
        } ?: child(raf, stbl, "co64")?.let { b ->
            raf.seek(b.bodyStart + 4); val n = raf.readInt(); LongArray(n) { raf.readLong() }
        } ?: throw IOException("no chunk offsets")
        val stsc = child(raf, stbl, "stsc") ?: throw IOException("no stsc")
        raf.seek(stsc.bodyStart + 4)
        val runs = List(raf.readInt()) { Triple(raf.readInt(), raf.readInt(), raf.readInt()) } // firstChunk (1-based), samplesPerChunk, desc
        val out = ArrayList<Pair<Long, Long>>(count)
        var sample = 0
        for (c in chunkOffsets.indices) {
            val perChunk = runs.lastOrNull { it.first <= c + 1 }?.second ?: break
            var off = chunkOffsets[c]
            repeat(perChunk) {
                if (sample < count) { out += off to sizes[sample]; off += sizes[sample]; sample++ }
            }
        }
        return out
    }

    /** (offset, size) of the samples in one `moof` (tfhd + trun). */
    private fun fragmentSamples(raf: RandomAccessFile, moof: Box): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for (traf in boxes(raf, moof.bodyStart, moof.end).filter { it.type == "traf" }) {
            val tfhd = child(raf, traf, "tfhd") ?: continue
            raf.seek(tfhd.bodyStart)
            val tfFlags = raf.readInt() and 0xffffff
            raf.readInt() // track id
            var base = moof.start
            if (tfFlags and 0x1 != 0) base = raf.readLong()
            if (tfFlags and 0x2 != 0) raf.readInt()
            if (tfFlags and 0x8 != 0) raf.readInt()
            val defaultSize = if (tfFlags and 0x10 != 0) raf.readInt().toLong() and 0xffffffffL else 0L
            for (trun in boxes(raf, traf.bodyStart, traf.end).filter { it.type == "trun" }) {
                raf.seek(trun.bodyStart)
                val flags = raf.readInt() and 0xffffff
                val n = raf.readInt()
                var offset = base + if (flags and 0x1 != 0) raf.readInt().toLong() else 0L
                if (flags and 0x4 != 0) raf.readInt()
                repeat(n) {
                    if (flags and 0x100 != 0) raf.readInt()
                    val size = if (flags and 0x200 != 0) raf.readInt().toLong() and 0xffffffffL else defaultSize
                    if (flags and 0x400 != 0) raf.readInt()
                    if (flags and 0x800 != 0) raf.readInt()
                    out += offset to size
                    offset += size
                }
            }
        }
        return out
    }
}
