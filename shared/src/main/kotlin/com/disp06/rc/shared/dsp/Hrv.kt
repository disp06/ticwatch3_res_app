package com.disp06.rc.shared.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** One inter-beat interval ending at [timeMs]. */
data class Ibi(val timeMs: Long, val ms: Double, val valid: Boolean)

data class HrvResult(
    val meanHr: Double,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val validBeats: Int,
    val totalBeats: Int,
)

object Hrv {
    const val MIN_IBI_MS = 300.0
    const val MAX_IBI_MS = 2000.0
    /** Reject IBIs deviating more than this fraction from the local median (missed/extra beats). */
    private const val ECTOPIC_TOLERANCE = 0.20
    /** Minimum number of valid successive differences required to report HRV for a window. */
    const val MIN_PAIRS = 30

    /** Converts beat times (absolute epoch ms) into cleaned IBIs. */
    fun ibis(beatTimesMs: DoubleArray): List<Ibi> {
        if (beatTimesMs.size < 2) return emptyList()
        val raw = (1 until beatTimesMs.size).map {
            val ms = beatTimesMs[it] - beatTimesMs[it - 1]
            Ibi(beatTimesMs[it].toLong(), ms, ms in MIN_IBI_MS..MAX_IBI_MS)
        }
        // Pass 1: deviation from the local median (missed / extra beats).
        val pass1 = raw.mapIndexed { i, ibi ->
            if (!ibi.valid) return@mapIndexed ibi
            val neigh = (maxOf(0, i - 4)..minOf(raw.size - 1, i + 4))
                .filter { it != i && raw[it].valid }.map { raw[it].ms }
            if (neigh.size < 3) return@mapIndexed ibi
            val med = Filters.median(neigh)
            ibi.copy(valid = abs(ibi.ms - med) <= ECTOPIC_TOLERANCE * med)
        }
        // Pass 2: successive differences far outside this window's own spread are detection
        // glitches, not physiology (robust 4.5 × MAD rule). Mark the later IBI of such a pair.
        val diffs = (1 until pass1.size).filter { pass1[it].valid && pass1[it - 1].valid }
            .map { abs(pass1[it].ms - pass1[it - 1].ms) }
        if (diffs.size < 10) return pass1
        val med = Filters.median(diffs)
        val mad = Filters.median(diffs.map { abs(it - med) }).coerceAtLeast(5.0)
        val limit = med + 4.5 * mad
        return pass1.mapIndexed { i, ibi ->
            if (i == 0 || !ibi.valid || !pass1[i - 1].valid) ibi
            else ibi.copy(valid = abs(ibi.ms - pass1[i - 1].ms) <= limit)
        }
    }

    /**
     * Time-domain HRV. Successive differences are only taken between two valid IBIs that
     * are directly adjacent (no rejected beat or gap in between), as required for RMSSD.
     */
    fun compute(ibis: List<Ibi>): HrvResult? {
        val valid = ibis.filter { it.valid }
        if (valid.size < 2) return null
        val meanIbi = valid.sumOf { it.ms } / valid.size
        val diffs = ArrayList<Double>()
        for (i in 1 until ibis.size) {
            val a = ibis[i - 1]; val b = ibis[i]
            val contiguous = b.timeMs - a.timeMs <= b.ms + 50 // b started where a ended
            if (a.valid && b.valid && contiguous) diffs += b.ms - a.ms
        }
        val enough = diffs.size >= MIN_PAIRS
        val rmssd = if (enough) sqrt(diffs.sumOf { it * it } / diffs.size) else null
        val pnn50 = if (enough) diffs.count { abs(it) > 50 } * 100.0 / diffs.size else null
        val sdnn = if (valid.size > MIN_PAIRS) {
            sqrt(valid.sumOf { (it.ms - meanIbi) * (it.ms - meanIbi) } / (valid.size - 1))
        } else null
        return HrvResult(60000.0 / meanIbi, rmssd, sdnn, pnn50, valid.size, ibis.size)
    }
}
