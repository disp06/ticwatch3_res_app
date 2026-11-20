package com.disp06.rc.shared.dsp

import com.disp06.rc.shared.PpgSource
import com.disp06.rc.shared.SensorBatch
import com.disp06.rc.shared.SensorStream
import com.disp06.rc.shared.StreamType
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Activity { STILL, LIGHT, MODERATE, ACTIVE, UNKNOWN }

/** Metrics for one analysis window (default 5 minutes, aligned to the wall clock). */
data class WindowMetrics(
    val startMs: Long,
    val endMs: Long,
    val heartRate: Double?,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val activity: Activity,
    /** Std-dev of acceleration magnitude, m/s^2. */
    val accelStd: Double?,
    val steps: Int?,
    /** 0..1 — fraction of the window with usable heart data. */
    val quality: Double,
    /** 0..1 — fraction of the window flagged as motion (beats there are rejected). */
    val motionFraction: Double,
    val source: PpgSource,
)

object Analyzer {
    const val WINDOW_MS = 5 * 60 * 1000L
    /** Acceleration-magnitude std (2 s blocks) above which PPG beats are discarded. */
    const val MOTION_STD = 0.6
    private const val MOTION_BLOCK_MS = 2000L
    private const val GAP_MS = 1500L

    fun analyze(batch: SensorBatch, windowMs: Long = WINDOW_MS): List<WindowMetrics> {
        val accel = batch.stream(StreamType.ACCEL)
        val motionBlocks = accel?.let { motionBlocks(it) } ?: emptyMap()
        val beats: DoubleArray = when (batch.ppgSource) {
            PpgSource.RAW_PPG -> batch.stream(StreamType.PPG)?.let { beatsFromPpg(it, motionBlocks) } ?: DoubleArray(0)
            else -> DoubleArray(0)
        }
        val ibis = Hrv.ibis(beats)
        val hrStream = batch.stream(StreamType.HEART_RATE)
        val steps = batch.stream(StreamType.STEPS)

        val first = (batch.startMs / windowMs) * windowMs
        val out = ArrayList<WindowMetrics>()
        var w = first
        while (w < batch.endMs) {
            val wEnd = w + windowMs
            val covered = minOf(wEnd, batch.endMs) - maxOf(w, batch.startMs)
            if (covered < 30_000) { w = wEnd; continue }
            val accelStd = accel?.let { magnitudeStd(it, w, wEnd) }
            val blocks = motionBlocks.filterKeys { it * MOTION_BLOCK_MS in w until wEnd }
            val motionFrac = if (blocks.isEmpty()) 0.0 else blocks.values.count { it } / blocks.size.toDouble()

            var hr: Double? = null
            var hrv: HrvResult? = null
            var quality = 0.0
            when (batch.ppgSource) {
                PpgSource.RAW_PPG -> {
                    val win = ibis.filter { it.timeMs in w until wEnd }
                    hrv = Hrv.compute(win)
                    hr = hrv?.meanHr
                    quality = (win.filter { it.valid }.sumOf { it.ms } / covered).coerceIn(0.0, 1.0)
                }
                PpgSource.HR_BPM -> hrStream?.let { s ->
                    val v = (0 until s.size).filter { s.timestampsMs[it] in w until wEnd && s.value(it) > 30f }
                        .map { s.value(it).toDouble() }
                    if (v.isNotEmpty()) hr = v.average()
                    quality = (v.size / (covered / 1000.0)).coerceIn(0.0, 1.0)
                }
                PpgSource.NONE -> {}
            }
            out += WindowMetrics(
                startMs = w, endMs = wEnd,
                heartRate = hr,
                rmssd = hrv?.rmssd, sdnn = hrv?.sdnn, pnn50 = hrv?.pnn50,
                activity = classify(accelStd),
                accelStd = accelStd,
                steps = steps?.let { stepDelta(it, w, wEnd) },
                quality = quality,
                motionFraction = motionFrac,
                source = batch.ppgSource,
            )
            w = wEnd
        }
        return out
    }

    fun classify(accelStd: Double?): Activity = when {
        accelStd == null -> Activity.UNKNOWN
        accelStd < 0.15 -> Activity.STILL
        accelStd < 0.8 -> Activity.LIGHT
        accelStd < 3.0 -> Activity.MODERATE
        else -> Activity.ACTIVE
    }

    /** Detects beats in each continuous PPG segment; returns absolute beat times in ms. */
    fun beatsFromPpg(ppg: SensorStream, motion: Map<Long, Boolean>): DoubleArray {
        val result = ArrayList<Double>()
        for ((from, to) in segments(ppg.timestampsMs)) {
            val n = to - from
            if (n < 50) continue
            val t = ppg.timestampsMs.copyOfRange(from, to)
            val durationS = (t.last() - t.first()) / 1000.0
            if (durationS < 10) continue
            val fs = ((n - 1) / durationS).roundToInt().coerceIn(5, 200).toDouble()
            val raw = DoubleArray(n) { ppg.value(from + it).toDouble() }
            val y = Filters.bandPass(Filters.resample(t, raw, fs), fs)
            val t0 = t.first()
            val valid = BooleanArray(y.size) { i ->
                val ms = t0 + (i * 1000.0 / fs).toLong()
                motion[ms / MOTION_BLOCK_MS] != true
            }
            // Ignore the first/last second of each segment (filter edge effects).
            val edge = fs
            for (b in PeakDetector.detect(y, fs, valid)) {
                if (b * fs < edge || b * fs > y.size - edge) continue
                result += t0 + b * 1000.0
            }
        }
        return result.toDoubleArray()
    }

    /** Index ranges [from, to) of samples without gaps longer than [GAP_MS]. */
    fun segments(t: LongArray): List<Pair<Int, Int>> {
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (i in 1 until t.size) {
            if (t[i] - t[i - 1] > GAP_MS) { out += start to i; start = i }
        }
        out += start to t.size
        return out
    }

    /** Motion flag per 2-second block index (epochMs / 2000). */
    fun motionBlocks(accel: SensorStream): Map<Long, Boolean> {
        val groups = HashMap<Long, MutableList<Double>>()
        for (i in 0 until accel.size) {
            groups.getOrPut(accel.timestampsMs[i] / MOTION_BLOCK_MS) { ArrayList() } += magnitude(accel, i)
        }
        return groups.mapValues { (_, v) -> v.size >= 4 && Filters.std(v.toDoubleArray()) > MOTION_STD }
    }

    private fun magnitude(s: SensorStream, i: Int): Double {
        val x = s.value(i, 0).toDouble(); val y = s.value(i, 1).toDouble(); val z = s.value(i, 2).toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private fun magnitudeStd(s: SensorStream, from: Long, to: Long): Double? {
        val m = (0 until s.size).filter { s.timestampsMs[it] in from until to }.map { magnitude(s, it) }
        return if (m.size < 10) null else Filters.std(m.toDoubleArray())
    }

    private fun stepDelta(s: SensorStream, from: Long, to: Long): Int? {
        val idx = (0 until s.size).filter { s.timestampsMs[it] in from until to }
        if (idx.isEmpty()) return null
        val first = s.value(idx.first()); val last = s.value(idx.last())
        return if (last >= first) (last - first).toInt() else last.toInt() // counter reset on reboot
    }
}
