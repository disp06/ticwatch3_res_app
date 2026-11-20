package com.disp06.rc.shared.dsp

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

/** Minimal metric row used by baseline / pattern analysis (decoupled from Room). */
data class MetricPoint(
    val timestampMs: Long,
    val heartRate: Double?,
    val rmssd: Double?,
    val stress: Double?,
    val activity: Activity,
    val quality: Double,
)

data class Baseline(
    /** Reference RMSSD (ms). */
    val rmssd: Double,
    /** Resting heart rate (bpm) used by the HR-only fallback. */
    val restingHr: Double,
    /** How the baseline was obtained: "sleep", "rest" or "default". */
    val kind: String,
)

object Stress {
    const val DEFAULT_RMSSD = 40.0
    const val DEFAULT_RESTING_HR = 62.0

    /**
     * Brief appendix heuristic: 100 * (1 - RMSSD / baseline), clamped to 0..100.
     * Returns null during moderate/vigorous activity (physical load is not stress) and when
     * HRV is unavailable. Falls back to heart-rate elevation over resting HR when the watch
     * only provides BPM (much weaker signal, flagged by the source column).
     */
    fun score(m: WindowMetrics, baseline: Baseline): Double? {
        if (m.activity == Activity.MODERATE || m.activity == Activity.ACTIVE) return null
        m.rmssd?.let { return (100 * (1 - it / baseline.rmssd)).coerceIn(0.0, 100.0) }
        val hr = m.heartRate ?: return null
        if (m.quality < 0.3) return null
        return (100 * (hr - baseline.restingHr) / (0.5 * baseline.restingHr)).coerceIn(0.0, 100.0)
    }

    /**
     * RMSSD baseline = mean RMSSD over the last 7 days of "sleep" windows (00:00–06:00 local,
     * still, good quality). Falls back to the median of all still windows, then to a default.
     */
    fun baseline(points: List<MetricPoint>, zone: ZoneId, nowMs: Long): Baseline {
        val week = points.filter { it.timestampMs >= nowMs - 7 * 86_400_000L && it.quality >= 0.5 }
        val still = week.filter { it.activity == Activity.STILL }
        val sleep = still.filter {
            val h = Instant.ofEpochMilli(it.timestampMs).atZone(zone).hour
            h in 0..5 && it.rmssd != null
        }
        val restHrs = still.mapNotNull { it.heartRate }
        val restingHr = if (restHrs.size >= 6) percentile(restHrs, 0.10) else DEFAULT_RESTING_HR
        return when {
            sleep.size >= 6 -> Baseline(sleep.mapNotNull { it.rmssd }.average(), restingHr, "sleep")
            still.count { it.rmssd != null } >= 6 ->
                Baseline(Filters.median(still.mapNotNull { it.rmssd }), restingHr, "rest")
            else -> Baseline(DEFAULT_RMSSD, restingHr, "default")
        }
    }

    private fun percentile(v: List<Double>, p: Double): Double {
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt()]
    }
}

data class DayPattern(
    /** Mean stress for Monday..Sunday (index 0 = Monday); null when no data. */
    val byWeekday: List<Double?>,
    /** [weekday][hour] mean stress. */
    val heatmap: List<List<Double?>>,
    val overallMean: Double?,
    val overallStd: Double?,
    val mostStressful: DayOfWeek?,
    val calmest: DayOfWeek?,
    /** Weekday/hour cells more than one std above the overall mean. */
    val hotspots: List<Pair<DayOfWeek, Int>>,
)

object DayAnalyzer {
    fun analyze(points: List<MetricPoint>, zone: ZoneId): DayPattern {
        val sums = Array(7) { DoubleArray(24) }
        val counts = Array(7) { IntArray(24) }
        val all = ArrayList<Double>()
        for (p in points) {
            val s = p.stress ?: continue
            val z = Instant.ofEpochMilli(p.timestampMs).atZone(zone)
            val d = z.dayOfWeek.value - 1
            sums[d][z.hour] += s; counts[d][z.hour]++
            all += s
        }
        val heat = (0 until 7).map { d -> (0 until 24).map { h -> if (counts[d][h] == 0) null else sums[d][h] / counts[d][h] } }
        val byDay = (0 until 7).map { d ->
            val n = counts[d].sum()
            if (n == 0) null else sums[d].sum() / n
        }
        val mean = if (all.isEmpty()) null else all.average()
        val std = if (all.size < 2 || mean == null) null else sqrt(all.sumOf { (it - mean) * (it - mean) } / (all.size - 1))
        val present = byDay.withIndex().filter { it.value != null }
        val hot = if (mean == null || std == null) emptyList() else
            heat.flatMapIndexed { d, row -> row.mapIndexedNotNull { h, v -> if (v != null && counts[d][h] >= 2 && v > mean + std) DayOfWeek.of(d + 1) to h else null } }
        return DayPattern(
            byWeekday = byDay,
            heatmap = heat,
            overallMean = mean,
            overallStd = std,
            mostStressful = present.maxByOrNull { it.value!! }?.let { DayOfWeek.of(it.index + 1) },
            calmest = present.minByOrNull { it.value!! }?.let { DayOfWeek.of(it.index + 1) },
            hotspots = hot,
        )
    }
}
