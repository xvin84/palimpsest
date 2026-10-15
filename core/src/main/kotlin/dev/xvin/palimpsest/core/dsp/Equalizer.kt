package dev.xvin.palimpsest.core.dsp

import kotlin.math.abs
import kotlin.math.pow

/**
 * Real-time EQ + gain + peak limiter over interleaved float PCM.
 *
 * Configuration ([setProfile], [setGainDb]) may be called from any thread; the audio thread picks
 * up the new coefficients at the start of the next [process] call. Filter state survives
 * coefficient changes so moving a slider doesn't click.
 */
class Equalizer {
    private class Config(val coeffs: Array<Biquad>, val linearGain: Float)

    @Volatile private var profile: EqProfile = EqProfile.FLAT
    @Volatile private var extraGainDb: Double = 0.0
    @Volatile private var dirty = true

    private var sampleRate = 0
    private var channels = 0
    private var config = Config(emptyArray(), 1f)
    // state[channel][filter] = (z1, z2) in transposed direct form II
    private var z1 = Array(0) { DoubleArray(0) }
    private var z2 = Array(0) { DoubleArray(0) }
    private var limiterEnv = 0f

    var enabled: Boolean = true
        set(value) { field = value; dirty = true }

    fun setProfile(p: EqProfile) { profile = p; dirty = true }

    /** Extra gain on top of the profile preamp, e.g. ReplayGain or a user volume trim. */
    fun setGainDb(db: Double) { extraGainDb = db; dirty = true }

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate == this.sampleRate && channels == this.channels) return
        this.sampleRate = sampleRate
        this.channels = channels
        dirty = true
        z1 = Array(channels) { DoubleArray(0) }
        z2 = Array(channels) { DoubleArray(0) }
    }

    fun reset() {
        z1.forEach { it.fill(0.0) }
        z2.forEach { it.fill(0.0) }
        limiterEnv = 0f
    }

    /** True when processing would change nothing, so callers can skip the copy altogether. */
    val isTransparent: Boolean
        get() = (!enabled || profile.isFlat) && extraGainDb == 0.0

    private fun rebuild() {
        dirty = false
        val p = profile
        val coeffs = if (enabled) {
            p.filters.filter { it.enabled && it.gainDb != 0.0 }.map { Biquad.design(it, sampleRate) }.toTypedArray()
        } else emptyArray()
        val gainDb = (if (enabled) p.preampDb else 0.0) + extraGainDb
        if (z1.isEmpty() || z1[0].size != coeffs.size) {
            z1 = Array(channels) { DoubleArray(coeffs.size) }
            z2 = Array(channels) { DoubleArray(coeffs.size) }
        }
        config = Config(coeffs, 10.0.pow(gainDb / 20).toFloat())
    }

    /** Processes [frames] interleaved frames of [buf] in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (channels == 0) return
        if (dirty) rebuild()
        val cfg = config
        val coeffs = cfg.coeffs
        val n = coeffs.size
        val gain = cfg.linearGain
        val ch = channels
        for (c in 0 until ch) {
            val s1 = z1[c]
            val s2 = z2[c]
            var i = c
            repeat(frames) {
                var x = buf[i].toDouble() * gain
                for (k in 0 until n) {
                    val q = coeffs[k]
                    val y = q.b0 * x + s1[k]
                    s1[k] = q.b1 * x - q.a1 * y + s2[k]
                    s2[k] = q.b2 * x - q.a2 * y
                    x = y
                }
                buf[i] = x.toFloat()
                i += ch
            }
        }
        limit(buf, frames * ch, ch)
    }

    /**
     * Brick-wall limiter with instant attack and ~50 ms release. Linked across channels so the
     * stereo image doesn't wander. Only does work when boosted audio would actually clip.
     */
    private fun limit(buf: FloatArray, samples: Int, ch: Int) {
        val threshold = 0.98f
        val release = 0.9995f
        var env = limiterEnv
        var i = 0
        while (i < samples) {
            var peak = 0f
            for (c in 0 until ch) peak = maxOf(peak, abs(buf[i + c]))
            env = if (peak > env) peak else env * release
            if (env > threshold) {
                val g = threshold / env
                for (c in 0 until ch) buf[i + c] *= g
            }
            i += ch
        }
        limiterEnv = env
    }
}
