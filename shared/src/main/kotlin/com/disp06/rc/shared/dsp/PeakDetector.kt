package com.disp06.rc.shared.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pulse detection on a band-passed, uniformly sampled PPG signal using the Slope Sum
 * Function (Zong et al. 2003), refining each beat to the waveform peak with parabolic
 * interpolation (important at 25 Hz, where one sample is 40 ms).
 *
 * Real wrist PPG often has alternating weak beats or an unknown polarity, which makes a
 * single fixed threshold miss every other beat (→ half the real heart rate). We therefore
 * run several candidates (both polarities × several threshold levels) and keep the one whose
 * beat rate matches an independent estimate of the pulse period: the watch's own heart-rate
 * sensor if available, otherwise the signal's autocorrelation.
 */
object PeakDetector {
    private const val SSF_WINDOW_S = 0.128
    private const val SEARCH_S = 0.30
    private val THRESHOLD_FACTORS = doubleArrayOf(0.6, 0.45, 0.3)

    /**
     * Returns beat times in seconds from the start of [y].
     * @param expectedIbiS expected beat period (s), e.g. from the device HR sensor; null = estimate.
     */
    fun detect(y: DoubleArray, fs: Double, valid: BooleanArray? = null, expectedIbiS: Double? = null): DoubleArray {
        if (y.size < fs * 3) return DoubleArray(0)
        val expected = expectedIbiS ?: autocorrPeriod(y, fs)
        val neg = DoubleArray(y.size) { -y[it] }
        var best = DoubleArray(0)
        var bestScore = Double.MAX_VALUE
        for (signal in listOf(y, neg)) for (f in THRESHOLD_FACTORS) {
            val refractory = if (expected != null) (0.6 * expected).coerceIn(0.28, 1.2) else 0.30
            val beats = detectOnce(signal, fs, valid, f, refractory)
            val s = score(beats, expected)
            if (s < bestScore) { bestScore = s; best = beats }
        }
        return best
    }

    /** Lower is better: rate mismatch vs. expected period + beat-to-beat irregularity. */
    private fun score(beats: DoubleArray, expected: Double?): Double {
        if (beats.size < 4) return Double.MAX_VALUE
        val ibi = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        val med = Filters.median(ibi.filter { it in 0.3..2.0 })
        if (med == 0.0) return Double.MAX_VALUE
        var sd = 0.0
        for (i in 1 until ibi.size) sd += abs(ibi[i] - ibi[i - 1])
        val irregularity = sd / (ibi.size - 1) / med
        val mismatch = if (expected != null) abs(med - expected) / expected else 0.0
        return 3 * mismatch + irregularity
    }

    /** Dominant pulse period (s) in 0.33–1.7 s (35–180 bpm) from the normalised autocorrelation. */
    fun autocorrPeriod(y: DoubleArray, fs: Double): Double? {
        val n = minOf(y.size, (30 * fs).toInt())
        if (n < 4 * fs) return null
        val m = Filters.mean(y, 0, n)
        val x = DoubleArray(n) { y[it] - m }
        var e0 = 0.0
        for (v in x) e0 += v * v
        if (e0 == 0.0) return null
        val minLag = (0.33 * fs).toInt().coerceAtLeast(1)
        val maxLag = minOf(n / 2, (1.7 * fs).toInt())
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            r[lag] = s / e0
        }
        // First local maximum that reaches at least 80 % of the global maximum (prefers the
        // fundamental over its multiples, which also correlate strongly).
        val globalMax = (minLag..maxLag).maxOf { r[it] }
        if (globalMax < 0.2) return null
        for (lag in minLag + 1..maxLag) {
            if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] >= 0.8 * globalMax) {
                return (lag + PeakDetector.parabolicOffset(r[lag - 1], r[lag], r[lag + 1])) / fs
            }
        }
        return null
    }

    private fun detectOnce(y: DoubleArray, fs: Double, valid: BooleanArray?, factor: Double, refractoryS: Double): DoubleArray {
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
        var threshold = factor * 3 * Filters.mean(ssf, 0, initLen)
        val recentPeaks = ArrayDeque<Double>()
        val refractory = (refractoryS * fs).roundToInt()
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
                threshold = factor * recentPeaks.average()
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
