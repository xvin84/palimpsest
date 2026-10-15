package dev.xvin.palimpsest.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a stream of mono samples into [bands] log-spaced levels in 0..1 for visualisers.
 * Writes come from the audio thread ([push]); reads ([compute]) from the UI thread.
 */
class SpectrumAnalyzer(private val fftSize: Int = 2048, val bands: Int = 48) {
    init { require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" } }

    private val ring = FloatArray(fftSize)
    @Volatile private var writePos = 0
    @Volatile var sampleRate = 44_100

    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val out = FloatArray(bands)
    private val bitRev = IntArray(fftSize).also { rev ->
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Adds interleaved samples, mixing channels down to mono. */
    fun push(interleaved: FloatArray, frames: Int, channels: Int) {
        var w = writePos
        var i = 0
        repeat(frames) {
            var s = 0f
            for (c in 0 until channels) s += interleaved[i + c]
            ring[w] = s / channels
            w = (w + 1) and (fftSize - 1)
            i += channels
        }
        writePos = w
    }

    fun clear() { ring.fill(0f) }

    /** Returns band levels in 0..1. The returned array is reused between calls. */
    @Synchronized
    fun compute(minHz: Double = 30.0, maxHz: Double = 16_000.0): FloatArray {
        val start = writePos
        for (i in 0 until fftSize) {
            val j = bitRev[i]
            re[j] = (ring[(start + i) and (fftSize - 1)] * window[i]).toDouble()
            im[j] = 0.0
        }
        fft()
        val binHz = sampleRate.toDouble() / fftSize
        val top = maxHz.coerceAtMost(sampleRate / 2.0 - binHz)
        val ratio = ln(top / minHz)
        for (b in 0 until bands) {
            val lo = minHz * kotlin.math.exp(ratio * b / bands)
            val hi = minHz * kotlin.math.exp(ratio * (b + 1) / bands)
            val i0 = (lo / binHz).toInt().coerceIn(1, fftSize / 2 - 1)
            val i1 = (hi / binHz).toInt().coerceIn(i0, fftSize / 2 - 1)
            var peak = 0.0
            for (k in i0..i1) peak = maxOf(peak, re[k] * re[k] + im[k] * im[k])
            // Normalise: a full-scale sine with a Hann window peaks at roughly (N/4)^2.
            val db = 10 * log10(peak / (fftSize / 4.0).pow(2) + 1e-12)
            // Gentle tilt so highs, which carry less energy, still move the bars.
            val tilt = 3.0 * log10((lo + hi) / 2 / 1000.0).coerceAtLeast(-1.0) * 2
            out[b] = (((db + tilt) + 70) / 70).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /** RMS level of the latest window, 0..1. Handy for pulsing backgrounds. */
    fun rms(): Float {
        var sum = 0.0
        for (s in ring) sum += s * s
        return sqrt(sum / fftSize).toFloat().coerceIn(0f, 1f)
    }

    private fun fft() {
        var size = 2
        while (size <= fftSize) {
            val half = size / 2
            val step = -2 * PI / size
            for (start in 0 until fftSize step size) {
                for (k in 0 until half) {
                    val wr = cos(step * k); val wi = sin(step * k)
                    val a = start + k; val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            size *= 2
        }
    }
}
