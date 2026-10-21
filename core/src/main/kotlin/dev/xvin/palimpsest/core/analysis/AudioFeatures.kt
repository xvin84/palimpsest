package dev.xvin.palimpsest.core.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the analysis tells about a track. [key] like «A minor», [camelot] like «8A». [energy] 0..1. */
data class Features(val bpm: Float, val key: String, val camelot: String, val energy: Float)

/**
 * Tempo, key and energy from mono PCM (any rate; ~11 kHz is plenty), for mixes that flow:
 * - tempo: spectral-flux onsets, autocorrelated, with a soft preference for 90–140 BPM against
 *   octave errors;
 * - key: a chromagram matched against the Krumhansl–Kessler major/minor profiles;
 * - energy: loudness and how busy the onsets are.
 */
object AudioFeatures {
    private const val FFT = 2048
    private const val HOP = 256
    private val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    // Camelot wheel: number per pitch class for major (B) and minor (A) keys.
    private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val CAMELOT_MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    fun analyze(pcm: FloatArray, sampleRate: Int): Features? {
        if (pcm.size < FFT * 8) return null
        val frames = (pcm.size - FFT) / HOP
        val window = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * PI * it / (FFT - 1)) }
        val re = DoubleArray(FFT); val im = DoubleArray(FFT)
        var prev = DoubleArray(FFT / 2)
        val flux = DoubleArray(frames)
        val chroma = DoubleArray(12)
        val binHz = sampleRate.toDouble() / FFT
        // Pitch class of every bin between 55 Hz and 2 kHz (the rest is drums and air).
        val pitchClass = IntArray(FFT / 2) { b ->
            val f = b * binHz
            if (f < 55 || f > 2000) -1 else ((12 * log2(f / 440.0)).roundToInt() + 9 + 1200) % 12
        }
        var energySum = 0.0
        for (fr in 0 until frames) {
            val off = fr * HOP
            var e = 0.0
            for (i in 0 until FFT) { val v = pcm[off + i].toDouble(); re[i] = v * window[i]; im[i] = 0.0; e += v * v }
            energySum += e / FFT
            fft(re, im)
            val mag = DoubleArray(FFT / 2)
            var f = 0.0
            for (b in 1 until FFT / 2) {
                val m = sqrt(re[b] * re[b] + im[b] * im[b])
                mag[b] = m
                val lm = ln(1 + 100 * m); val lp = ln(1 + 100 * prev[b])
                if (lm > lp) f += lm - lp
                val pc = pitchClass[b]
                if (pc >= 0) chroma[pc] += m * m
            }
            flux[fr] = f
            prev = mag
        }

        // --- Tempo ---
        val fps = sampleRate.toDouble() / HOP
        // Onset envelope minus its local mean, so slow loudness changes don't look like beats.
        val env = DoubleArray(frames) { i ->
            val a = max(0, i - 8); val b = minOf(frames - 1, i + 8)
            var s = 0.0; for (k in a..b) s += flux[k]
            max(0.0, flux[i] - s / (b - a + 1))
        }
        var bestBpm = 120.0; var bestScore = -1.0
        var bpm = 60.0
        while (bpm <= 200.0) {
            val lag = fps * 60 / bpm
            val l0 = lag.toInt(); val frac = lag - l0
            var ac = 0.0
            for (i in 0 until frames - l0 - 1) ac += env[i] * (env[i + l0] * (1 - frac) + env[i + l0 + 1] * frac)
            // Log-Gaussian prior centred at 115 BPM: picks the felt tempo, not its double or half.
            val prior = exp(-0.5 * (log2(bpm / 115.0) / 0.9).pow(2))
            val score = ac * prior
            if (score > bestScore) { bestScore = score; bestBpm = bpm }
            bpm += 0.5
        }

        // --- Key ---
        val total = chroma.sum().takeIf { it > 0 } ?: 1.0
        val c = DoubleArray(12) { chroma[it] / total }
        var bestKey = 0; var bestMinor = false; var bestR = -2.0
        for (tonic in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MINOR else MAJOR
            val r = correlation(c, DoubleArray(12) { prof[(it - tonic + 12) % 12] })
            if (r > bestR) { bestR = r; bestKey = tonic; bestMinor = minor }
        }
        val keyName = NAMES[bestKey] + if (bestMinor) " minor" else " major"
        val camelot = (if (bestMinor) CAMELOT_MINOR[bestKey] else CAMELOT_MAJOR[bestKey]).toString() + if (bestMinor) "A" else "B"

        // --- Energy: loudness (−30…−5 dBFS) and onset activity ---
        val rmsDb = 10 * log10(energySum / frames + 1e-12)
        val loud = ((rmsDb + 30) / 25).coerceIn(0.0, 1.0)
        val busy = (env.average() / (flux.average() + 1e-9) * 2).coerceIn(0.0, 1.0)
        val energy = (0.65 * loud + 0.35 * busy).toFloat()
        return Features(bestBpm.toFloat(), keyName, camelot, energy)
    }

    /** «8A» → «A minor». */
    fun keyName(camelot: String?): String? {
        if (camelot.isNullOrEmpty()) return null
        val n = camelot.dropLast(1).toIntOrNull() ?: return null
        val table = if (camelot.last() == 'A') CAMELOT_MINOR else CAMELOT_MAJOR
        val pc = table.indexOf(n).takeIf { it >= 0 } ?: return null
        return NAMES[pc] + if (camelot.last() == 'A') " minor" else " major"
    }

    /** Camelot neighbours mix smoothly: same number, ±1, or the same number with the other letter. */
    fun compatible(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        val na = a.dropLast(1).toIntOrNull() ?: return false; val nb = b.dropLast(1).toIntOrNull() ?: return false
        val d = ((na - nb) % 12 + 12) % 12
        return (a.last() == b.last() && (d == 0 || d == 1 || d == 11)) || (na == nb)
    }

    /**
     * How rough going from one track to the next feels: 0 is seamless. Tempo counts with half and
     * double time folded in, key by Camelot distance, energy by the jump. Unknown parts cost a little.
     */
    fun transitionCost(bpmA: Float?, keyA: String?, energyA: Float?, bpmB: Float?, keyB: String?, energyB: Float?): Double {
        var cost = 0.0
        cost += if (bpmA != null && bpmB != null && bpmA > 0 && bpmB > 0) {
            val r = abs(log2(bpmB.toDouble() / bpmA))
            min(min(r, abs(r - 1)), abs(r + 1)) * 6 // 12 % tempo off ≈ 1
        } else 0.5
        cost += if (keyA.isNullOrEmpty() || keyB.isNullOrEmpty()) 0.5 else if (compatible(keyA, keyB)) 0.0 else camelotSteps(keyA, keyB) * 0.25
        cost += if (energyA != null && energyB != null) abs(energyA - energyB) * 2.0 else 0.3
        return cost
    }

    private fun camelotSteps(a: String, b: String): Int {
        val na = a.dropLast(1).toIntOrNull() ?: return 3; val nb = b.dropLast(1).toIntOrNull() ?: return 3
        val d = ((na - nb) % 12 + 12) % 12
        return min(d, 12 - d) + if (a.last() != b.last()) 1 else 0
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var n = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { n += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma).pow(2); db += (b[i] - mb).pow(2) }
        return n / sqrt(da * db + 1e-12)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
