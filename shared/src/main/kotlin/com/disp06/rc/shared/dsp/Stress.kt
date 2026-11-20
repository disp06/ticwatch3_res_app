package com.disp06.rc.shared.dsp

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
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
    /** How the baseline was obtained: "personal" (≥ 3 h of calm data), "learning" or "default". */
    val kind: String,
)

object Stress {
    const val DEFAULT_RMSSD = 40.0
    const val DEFAULT_RESTING_HR = 62.0
    /** Score at your typical resting HRV. */
    private const val NORMAL = 30.0

    /**
     * Personal, log-ratio stress score (0..100):
     *   30 − 60·log2(RMSSD / your resting RMSSD)  + up to 40·(HR − resting HR)/resting HR
     * so your usual resting state ≈ 30, HRV at 70 % of usual ≈ 60, at half ≈ 90; higher HRV → 0.
     * (The brief's 100·(1 − RMSSD/baseline) is 0 whenever HRV ≥ baseline, i.e. most of the time.)
     * Returns null during moderate/vigorous activity (physical load is not stress) and when no
     * usable signal exists. HR-only fallback uses heart-rate elevation alone.
     */
    fun score(m: WindowMetrics, baseline: Baseline): Double? {
        if (m.activity == Activity.MODERATE || m.activity == Activity.ACTIVE) return null
        val hrTerm = m.heartRate?.let { 40 * ((it - baseline.restingHr) / baseline.restingHr).coerceAtLeast(0.0) } ?: 0.0
        m.rmssd?.let { r ->
            if (r <= 0) return null
            return (NORMAL - 60 * ln(r / baseline.rmssd) / ln(2.0) + hrTerm).coerceIn(0.0, 100.0)
        }
        val hr = m.heartRate ?: return null
        if (m.quality < 0.3) return null
        return (NORMAL + 150 * (hr - baseline.restingHr) / baseline.restingHr).coerceIn(0.0, 100.0)
    }

    /**
     * Resting RMSSD baseline = median RMSSD of still, good-quality windows over the last 7 days.
     * Starts learning after 3 calm windows (15 min); "default" until then.
     */
    fun baseline(points: List<MetricPoint>, zone: ZoneId, nowMs: Long): Baseline {
        val week = points.filter { it.timestampMs >= nowMs - 7 * 86_400_000L && it.quality >= 0.5 }
        val still = week.filter { it.activity == Activity.STILL || it.activity == Activity.LIGHT }
        val rm = still.mapNotNull { it.rmssd }
        val restHrs = still.mapNotNull { it.heartRate }
        val restingHr = if (restHrs.size >= 3) percentile(restHrs, 0.10) else DEFAULT_RESTING_HR
        return when {
            rm.size >= 36 -> Baseline(Filters.median(rm), restingHr, "personal")
            rm.size >= 3 -> Baseline(Filters.median(rm), restingHr, "learning")
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
