package dev.xvin.palimpsest.core.dsp

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
enum class FilterType(val code: String) {
    PEAK("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),
}

@Serializable
data class EqFilter(
    val type: FilterType = FilterType.PEAK,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double = 1.0,
    val enabled: Boolean = true,
)

/**
 * A complete EQ setting: a preamp plus any number of biquad filters. The 10-band graphic EQ
 * is just a profile with ten fixed peak filters; AutoEQ headphone profiles map onto it 1:1.
 */
@Serializable
data class EqProfile(
    val name: String,
    val preampDb: Double = 0.0,
    val filters: List<EqFilter> = emptyList(),
) {
    val isFlat: Boolean get() = preampDb == 0.0 && filters.none { it.enabled && it.gainDb != 0.0 }

    /** Combined response in dB at [freqHz], for drawing the EQ curve. */
    fun responseDb(freqHz: Double, sampleRate: Int = 48_000): Double =
        preampDb + filters.filter { it.enabled }.sumOf { Biquad.design(it, sampleRate).magnitudeDb(freqHz, sampleRate) }

    /** Highest boost of the curve; used to suggest a preamp that avoids clipping. */
    fun peakBoostDb(sampleRate: Int = 48_000): Double =
        (0 until 200).maxOf { i ->
            val f = 20.0 * (20_000.0 / 20.0).pow(i / 199.0)
            responseDb(f, sampleRate) - preampDb
        }

    companion object {
        val GRAPHIC_BANDS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

        fun graphic(name: String, gains: List<Double>, preampDb: Double = 0.0): EqProfile {
            require(gains.size == GRAPHIC_BANDS.size)
            return EqProfile(
                name = name,
                preampDb = preampDb,
                filters = GRAPHIC_BANDS.mapIndexed { i, f -> EqFilter(FilterType.PEAK, f, gains[i], q = 1.41) },
            )
        }

        val FLAT = graphic("Без эквалайзера", List(10) { 0.0 })

        val PRESETS: List<EqProfile> = listOf(
            FLAT,
            graphic("Бас", listOf(6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), preampDb = -5.0),
            graphic("Рок", listOf(4.0, 3.0, 1.5, 0.0, -1.0, -0.5, 1.0, 2.5, 3.5, 4.0), preampDb = -4.0),
            graphic("Электроника", listOf(5.0, 4.0, 1.0, 0.0, -2.0, 1.5, 0.5, 1.0, 4.0, 5.0), preampDb = -5.0),
            graphic("Хип-хоп", listOf(5.0, 4.5, 1.5, 2.5, -1.0, -1.0, 1.0, -0.5, 1.5, 2.5), preampDb = -5.0),
            graphic("Вокал", listOf(-2.0, -1.5, -1.0, 1.0, 3.0, 3.5, 3.0, 1.5, 0.0, -1.0), preampDb = -3.5),
            graphic("Акустика", listOf(3.0, 2.5, 1.5, 0.5, 1.0, 1.0, 2.0, 2.5, 2.0, 1.5), preampDb = -3.0),
            graphic("Классика", listOf(3.0, 2.0, 1.0, 0.0, 0.0, 0.0, -1.0, -1.5, -2.0, -3.0), preampDb = -3.0),
            graphic("Громкость ночью", listOf(3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 2.0), preampDb = -3.0),
            graphic("Ярче", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.5, 3.0, 4.0, 4.5), preampDb = -4.5),
        )
    }
}

/** Normalised biquad coefficients (a0 = 1). Formulas from the RBJ Audio EQ Cookbook. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    fun magnitudeDb(freqHz: Double, sampleRate: Int): Double {
        val w = 2 * PI * freqHz / sampleRate
        val cw = cos(w); val c2w = cos(2 * w); val sw = sin(w); val s2w = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * log10(mag2)
    }

    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)

        fun design(f: EqFilter, sampleRate: Int): Biquad {
            if (!f.enabled || f.gainDb == 0.0) return IDENTITY
            // Keep the centre safely below Nyquist so 44.1 kHz streams don't blow up on a 20 kHz band.
            val freq = f.freqHz.coerceIn(10.0, sampleRate * 0.45)
            val a = 10.0.pow(f.gainDb / 40)
            val w0 = 2 * PI * freq / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * f.q.coerceAtLeast(0.05))
            val sa = 2 * sqrt(a) * alpha
            val (b0, b1, b2, a0, a1, a2) = when (f.type) {
                FilterType.PEAK -> Six(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                FilterType.LOW_SHELF -> Six(
                    a * ((a + 1) - (a - 1) * cw + sa),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - sa),
                    (a + 1) + (a - 1) * cw + sa,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - sa,
                )
                FilterType.HIGH_SHELF -> Six(
                    a * ((a + 1) + (a - 1) * cw + sa),
                    -2 * a * ((a - 1) + (a + 1) * cw),
                    a * ((a + 1) + (a - 1) * cw - sa),
                    (a + 1) - (a - 1) * cw + sa,
                    2 * ((a - 1) - (a + 1) * cw),
                    (a + 1) - (a - 1) * cw - sa,
                )
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }

    private data class Six(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double)
}
