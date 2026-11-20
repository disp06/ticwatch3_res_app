package com.disp06.rc.shared.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR section (RBJ audio-EQ cookbook), Direct Form I. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    fun apply(x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        // Initialise state with the first sample to avoid a large start-up transient.
        var x1 = x.firstOrNull() ?: 0.0
        var x2 = x1
        val dcGain = (b0 + b1 + b2) / (1 + a1 + a2)
        var y1 = x1 * dcGain
        var y2 = y1
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            y[i] = yi
        }
        return y
    }

    companion object {
        private const val Q_BUTTERWORTH = 0.7071067811865476

        fun lowPass(fs: Double, fc: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(fs: Double, fc: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

object Filters {
    /** Zero-phase band-pass (forward + backward pass of HP and LP Butterworth sections). */
    fun bandPass(x: DoubleArray, fs: Double, lowHz: Double = 0.5, highHz: Double = 4.0): DoubleArray {
        if (x.size < 8) return x.copyOf()
        val hi = minOf(highHz, fs * 0.45)
        val chain = listOf(Biquad.highPass(fs, lowHz), Biquad.lowPass(fs, hi))
        var y = x
        for (f in chain) y = f.apply(y)
        y = y.reversedArray()
        for (f in chain) y = f.apply(y)
        return y.reversedArray()
    }

    /**
     * Linear interpolation of irregular samples onto a uniform grid starting at t[0].
     * Returns the resampled values; the grid timestamp of sample i is t[0] + i * 1000 / fs.
     */
    fun resample(t: LongArray, v: DoubleArray, fs: Double): DoubleArray {
        if (t.size < 2) return v.copyOf()
        val step = 1000.0 / fs
        val n = ((t.last() - t.first()) / step).toInt() + 1
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val ti = t.first() + i * step
            while (j < t.size - 2 && t[j + 1] < ti) j++
            val dt = (t[j + 1] - t[j]).toDouble()
            val frac = if (dt <= 0) 0.0 else ((ti - t[j]) / dt).coerceIn(0.0, 1.0)
            out[i] = v[j] + (v[j + 1] - v[j]) * frac
        }
        return out
    }

    fun mean(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += x[i]
        return s / (to - from)
    }

    fun std(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        val n = to - from
        if (n < 2) return 0.0
        val m = mean(x, from, to)
        var s = 0.0
        for (i in from until to) { val d = x[i] - m; s += d * d }
        return sqrt(s / (n - 1))
    }

    fun median(x: List<Double>): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
