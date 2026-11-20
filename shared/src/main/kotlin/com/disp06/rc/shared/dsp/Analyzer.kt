package com.disp06.rc.shared.dsp

import com.disp06.rc.shared.PpgSource
import com.disp06.rc.shared.SensorBatch
import com.disp06.rc.shared.SensorStream
import com.disp06.rc.shared.StreamType
import kotlin.math.abs
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
        val hrStream = batch.stream(StreamType.HEART_RATE)
        val beats: DoubleArray = when (batch.ppgSource) {
            PpgSource.RAW_PPG -> batch.stream(StreamType.PPG)?.let { beatsFromPpg(it, motionBlocks, hrStream) } ?: DoubleArray(0)
            else -> DoubleArray(0)
        }
        val ibis = Hrv.ibis(beats)
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

            // Reference heart rate from the watch's own HR algorithm (present in HR-only mode and,
            // since v0.1.2, recorded alongside raw PPG).
            val refValues = hrStream?.let { st ->
                (0 until st.size).filter { st.timestampsMs[it] in w until wEnd && st.value(it) > 30f }.map { st.value(it).toDouble() }
            } ?: emptyList()
            val refHr = refValues.takeIf { it.isNotEmpty() }?.let { Filters.median(it) }
            val refQuality = (refValues.size / (covered / 1000.0)).coerceIn(0.0, 1.0)

            var hrv: HrvResult? = null
            var ibiQuality = 0.0
            if (batch.ppgSource == PpgSource.RAW_PPG) {
                val win = ibis.filter { it.timeMs in w until wEnd }
                hrv = Hrv.compute(win)
                ibiQuality = (win.filter { it.valid }.sumOf { it.ms } / covered).coerceIn(0.0, 1.0)
                // Beat detection disagreeing with the device HR means missed/extra beats: the IBIs
                // (and therefore HRV) can't be trusted for this window.
                if (hrv != null && refHr != null && abs(hrv.meanHr - refHr) / refHr > 0.15) {
                    hrv = null
                    ibiQuality = 0.0
                }
            }
            val hr: Double? = refHr ?: hrv?.meanHr
            val quality = maxOf(ibiQuality, refQuality)
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

    /**
     * Detects beats in each continuous PPG segment; returns absolute beat times in ms.
     * For multi-channel sensors the channel with the most rhythm-consistent beats is used.
     */
    fun beatsFromPpg(ppg: SensorStream, motion: Map<Long, Boolean>, ref: SensorStream? = null): DoubleArray {
        val result = ArrayList<Double>()
        val times = regularize(ppg.timestampsMs)
        for ((from, to) in segments(times)) {
            val n = to - from
            if (n < 50) continue
            val t = times.copyOfRange(from, to)
            val durationS = (t.last() - t.first()) / 1000.0
            if (durationS < 10) continue
            val fs = ((n - 1) / durationS).roundToInt().coerceIn(5, 200).toDouble()
            val refHr = ref?.let { r ->
                (0 until r.size).filter { r.timestampsMs[it] in t.first()..t.last() && r.value(it) > 30f }.map { r.value(it).toDouble() }
            }?.takeIf { it.size >= 3 }?.let { Filters.median(it) }
            val expectedIbiS = refHr?.let { 60.0 / it }
            val t0 = t.first()
            var best: DoubleArray? = null
            var bestConsistent = -1
            for (c in 0 until ppg.channels) {
                val raw = DoubleArray(n) { ppg.value(from + it, c).toDouble() }
                if (raw.all { it == raw[0] }) continue // unused / constant channel
                val y = Filters.bandPass(Filters.resample(t, raw, fs), fs)
                val valid = BooleanArray(y.size) { i -> motion[(t0 + (i * 1000.0 / fs).toLong()) / MOTION_BLOCK_MS] != true }
                val beats = PeakDetector.detect(y, fs, valid, expectedIbiS)
                    .filter { it * fs >= fs && it * fs <= y.size - fs } // drop filter edge (1 s each side)
                    .map { t0 + it * 1000.0 }.toDoubleArray()
                val consistent = consistentCount(beats, expectedIbiS?.times(1000))
                if (consistent > bestConsistent) { bestConsistent = consistent; best = beats }
            }
            best?.let { b -> b.forEach { result += it } }
        }
        return result.toDoubleArray()
    }

    /** Number of IBIs within ±20 % of the expected (or median) interval. */
    private fun consistentCount(beatsMs: DoubleArray, expectedMs: Double?): Int {
        if (beatsMs.size < 3) return 0
        val ibi = (1 until beatsMs.size).map { beatsMs[it] - beatsMs[it - 1] }
        val target = expectedMs ?: Filters.median(ibi)
        return ibi.count { abs(it - target) <= 0.2 * target }
    }

    /**
     * Repairs FIFO "clumped" timestamps: some sensor HALs stamp a whole hardware batch with
     * (almost) the same time at delivery. A sensor samples at a fixed rate, so each clump is
     * re-spaced backwards from its last timestamp at the period estimated from clump sizes.
     * Regular streams are returned unchanged.
     */
    fun regularize(t: LongArray): LongArray {
        if (t.size < 20) return t
        val tiny = (1 until t.size).count { t[it] - t[it - 1] < 3 }
        if (tiny < (t.size - 1) / 2) return t
        val ends = ArrayList<Int>() // index of last sample in each clump
        for (i in 1 until t.size) if (t[i] - t[i - 1] >= 3) ends += i - 1
        ends += t.size - 1
        var span = 0.0
        var count = 0
        for (k in 1 until ends.size) {
            val interval = t[ends[k]] - t[ends[k - 1]]
            if (interval in 1..3000) { span += interval; count += ends[k] - ends[k - 1] }
        }
        if (count == 0) return t
        val period = span / count
        val out = LongArray(t.size)
        var start = 0
        for (e in ends) {
            for (i in start..e) out[i] = t[e] - Math.round((e - i) * period)
            start = e + 1
        }
        // Keep the result monotonic if a clump's back-spacing overlaps the previous one.
        for (i in 1 until out.size) if (out[i] <= out[i - 1]) out[i] = out[i - 1] + 1
        return out
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
