package dev.xvin.palimpsest.core.covers

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colour a cover reads as at a glance — what «Обложка» floods the screen with.
 *
 * The accent is the most vivid spot, and that's wrong for a backdrop: a black-and-white cover with
 * a bit of skin in it turned the whole screen salmon, a navy cover with a small red logo turned it
 * red. Here every hue is weighted by how much of the cover it covers, and a cover that is mostly
 * grey, black or white gets a neutral graphite instead of whatever little colour it has.
 */
object CoverTone {
    /** Returned for covers with (almost) no colour; the styles treat a grey as «no hue». */
    const val NEUTRAL: Int = 0xFF76767C.toInt()

    /** [pixels] are ARGB, any order — a 48×48 thumbnail is plenty. */
    fun of(pixels: IntArray): Int {
        val bins = 24
        val weight = FloatArray(bins)
        val sum = Array(bins) { FloatArray(3) }
        var counted = 0
        var colourful = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            counted++
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val chroma = hi - lo
            // Near-black and faint tints don't count as colour: shadows and paper.
            if (chroma < 0.16f || hi < 0.14f) continue
            colourful++
            val bin = (hue(r, g, b, hi, chroma) / 360f * bins).toInt().coerceIn(0, bins - 1)
            weight[bin] += chroma
            sum[bin][0] += r * chroma; sum[bin][1] += g * chroma; sum[bin][2] += b * chroma
        }
        if (counted == 0 || colourful < counted * 0.12f) return NEUTRAL
        // The strongest hue with its neighbours, so a gradient across a bin edge isn't split in two.
        val best = (0 until bins).maxBy { weight[(it + bins - 1) % bins] + weight[it] * 1.5f + weight[(it + 1) % bins] }
        var w = 0f; var r = 0f; var g = 0f; var b = 0f
        for (k in -1..1) {
            val i = (best + k + bins) % bins
            w += weight[i]; r += sum[i][0]; g += sum[i][1]; b += sum[i][2]
        }
        if (w <= 0f) return NEUTRAL
        return (0xFF shl 24) or ((r / w * 255).toInt().coerceIn(0, 255) shl 16) or ((g / w * 255).toInt().coerceIn(0, 255) shl 8) or (b / w * 255).toInt().coerceIn(0, 255)
    }

    /** True for [NEUTRAL] and other greys: the style should use it as is, not make it «vivid». */
    fun isNeutral(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b)) < 24
    }

    private fun hue(r: Float, g: Float, b: Float, hi: Float, chroma: Float): Float {
        val h = when (hi) {
            r -> ((g - b) / chroma).let { if (it < 0) it + 6 else it }
            g -> (b - r) / chroma + 2
            else -> (r - g) / chroma + 4
        } * 60f
        return abs(h) % 360f
    }
}
