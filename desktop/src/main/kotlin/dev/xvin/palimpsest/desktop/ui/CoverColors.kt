package dev.xvin.palimpsest.desktop.ui

import androidx.compose.ui.graphics.Color
import dev.xvin.palimpsest.desktop.data.Graph
import dev.xvin.palimpsest.desktop.data.Track
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** A cover's colours: [accent] (vivid), [tint] (dominant, Lane's backdrop), [mood] (what «Обложка» floods the window with). */
data class CoverPalette(val accent: Color, val tint: Color, val mood: Color)

/**
 * The cover's colours: a vivid one for the accent, the dominant one for Lane's backdrop and the
 * overall tone ([dev.xvin.palimpsest.core.covers.CoverTone]) — done on a 48×48 thumbnail.
 */
object CoverColors {
    suspend fun of(graph: Graph, t: Track): CoverPalette? {
        val model = graph.covers.model(t) ?: return null
        val bytes = when (model) {
            is File -> model.readBytes()
            is String -> graph.covers.bytes(model)
            else -> null
        } ?: return null
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val small = java.awt.image.BufferedImage(48, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(img, 0, 0, 48, 48, null); dispose() }
        // Buckets by hue; the most populated saturated bucket is the accent, the most populated overall the tint.
        val counts = HashMap<Int, IntArray>()
        val hsb = FloatArray(3)
        for (y in 0 until 48) for (x in 0 until 48) {
            val rgb = small.getRGB(x, y)
            val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
            java.awt.Color.RGBtoHSB(r, g, b, hsb)
            val key = ((hsb[0] * 18).toInt() shl 4) or ((hsb[1] * 3).toInt() shl 2) or (hsb[2] * 3).toInt()
            val c = counts.getOrPut(key) { IntArray(4) }
            c[0]++; c[1] += r; c[2] += g; c[3] += b
        }
        fun avg(c: IntArray) = Color(c[1] / c[0] / 255f, c[2] / c[0] / 255f, c[3] / c[0] / 255f)
        val dominant = counts.values.maxByOrNull { it[0] } ?: return null
        val vivid = counts.filter { (k, v) -> ((k shr 2) and 3) >= 1 && (k and 3) >= 1 && v[0] > 20 }.maxByOrNull { (k, v) -> v[0] * (1 + ((k shr 2) and 3)) }?.value
        val px = IntArray(48 * 48).also { small.getRGB(0, 0, 48, 48, it, 0, 48) }
        return CoverPalette(avg(vivid ?: dominant), avg(dominant), Color(dev.xvin.palimpsest.core.covers.CoverTone.of(px)))
    }
}
