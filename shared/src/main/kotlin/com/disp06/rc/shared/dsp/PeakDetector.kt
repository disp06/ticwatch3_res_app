package com.disp06.rc.shared.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pulse detection on a band-passed, uniformly sampled PPG signal using the Slope Sum
 * Function (Zong et al. 2003), then refining each beat to the waveform peak with
 * parabolic interpolation (important at 25 Hz, where one sample is 40 ms).
 */
object PeakDetector {
    private const val SSF_WINDOW_S = 0.128
    private const val REFRACTORY_S = 0.30
    private const val SEARCH_S = 0.30

    /** Returns beat times in seconds from the start of [y]. */
    fun detect(y: DoubleArray, fs: Double, valid: BooleanArray? = null): DoubleArray {
        if (y.size < fs * 3) return DoubleArray(0)
        val a = detectPolarity(y, fs, valid)
        val b = detectPolarity(DoubleArray(y.size) { -y[it] }, fs, valid)
        // Sensor polarity is vendor-specific; keep the polarity giving the more regular rhythm.
        return if (score(a) <= score(b)) a else b
    }

    private fun score(beats: DoubleArray): Double {
        if (beats.size < 4) return Double.MAX_VALUE
        val ibi = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        val m = ibi.average()
        var sd = 0.0
        for (i in 1 until ibi.size) sd += abs(ibi[i] - ibi[i - 1])
        return sd / (ibi.size - 1) / m
    }

    private fun detectPolarity(y: DoubleArray, fs: Double, valid: BooleanArray?): DoubleArray {
        val w = maxOf(1, (SSF_WINDOW_S * fs).roundToInt())
        val ssf = DoubleArray(y.size)
        var run = 0.0
        val inc = DoubleArray(y.size)
        for (i in 1 until y.size) {
            inc[i] = maxOf(0.0, y[i] - y[i - 1])
            run += inc[i]
            if (i - w >= 0) run -= inc[i - w]
            ssf[i] = run
        }
        val initLen = minOf(y.size, (3 * fs).toInt())
        var threshold = 0.6 * 3 * Filters.mean(ssf, 0, initLen)
        val recentPeaks = ArrayDeque<Double>()
        val refractory = (REFRACTORY_S * fs).roundToInt()
        val search = (SEARCH_S * fs).roundToInt()
        val beats = ArrayList<Double>()
        var i = 1
        var lastBeat = -refractory
        while (i < y.size) {
            if (ssf[i] > threshold && ssf[i - 1] <= threshold && i - lastBeat >= refractory) {
                val end = minOf(y.size - 1, i + search)
                var pk = i
                var ssfMax = ssf[i]
                for (k in i..end) {
                    if (y[k] > y[pk]) pk = k
                    if (ssf[k] > ssfMax) ssfMax = ssf[k]
                }
                if (pk in 1 until y.size - 1 && (valid == null || valid[pk])) {
                    beats += pk + parabolicOffset(y[pk - 1], y[pk], y[pk + 1])
                }
                recentPeaks.addLast(ssfMax)
                if (recentPeaks.size > 8) recentPeaks.removeFirst()
                threshold = 0.6 * recentPeaks.average()
                lastBeat = pk
                i = maxOf(i + 1, pk + 1)
                continue
            }
            // Slowly decay the threshold if nothing is found for 2 s (lost signal / amplitude drop).
            if (i - lastBeat > 2 * fs) threshold *= 0.995
            i++
        }
        return DoubleArray(beats.size) { beats[it] / fs }
    }

    /** Sub-sample peak location from three neighbouring samples; result in [-0.5, 0.5]. */
    fun parabolicOffset(ym1: Double, y0: Double, yp1: Double): Double {
        val den = ym1 - 2 * y0 + yp1
        if (den == 0.0) return 0.0
        return (0.5 * (ym1 - yp1) / den).coerceIn(-0.5, 0.5)
    }
}
