package com.disp06.rc.shared

import com.disp06.rc.shared.dsp.Activity
import com.disp06.rc.shared.dsp.Analyzer
import com.disp06.rc.shared.dsp.DayAnalyzer
import com.disp06.rc.shared.dsp.MetricPoint
import com.disp06.rc.shared.dsp.Stress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SharedTests {
    private val start = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    /** Synthetic PPG with a known IBI series; returns (batch, true RMSSD over whole batch). */
    private fun synthetic(fs: Double, minutes: Int, inverted: Boolean, seed: Int = 1, motion: Boolean = false): Pair<SensorBatch, Double> {
        val rnd = Random(seed)
        val beats = ArrayList<Double>()
        val ibis = ArrayList<Double>()
        var t = 500.0
        var k = 0
        while (t < minutes * 60_000.0) {
            beats += t
            val ibi = 850 + 45 * sin(2 * PI * k / 9.0) + rnd.nextDouble(-25.0, 25.0)
            ibis += ibi; t += ibi; k++
        }
        val diffs = (1 until ibis.size - 1).map { ibis[it] - ibis[it - 1] }
        val trueRmssd = sqrt(diffs.sumOf { it * it } / diffs.size)

        val n = (minutes * 60 * fs).toInt()
        val ts = LongArray(n)
        val vals = FloatArray(n)
        var bi = 0
        for (i in 0 until n) {
            val tm = i * 1000.0 / fs + rnd.nextDouble(-2.0, 2.0)
            ts[i] = start + tm.toLong()
            while (bi < beats.size - 1 && beats[bi + 1] < tm - 600) bi++
            var v = 0.0
            for (j in bi until minOf(beats.size, bi + 3)) {
                val d = (tm - beats[j]) / 1000.0
                v += exp(-((d - 0.10) * (d - 0.10)) / (2 * 0.06 * 0.06)) + 0.35 * exp(-((d - 0.38) * (d - 0.38)) / (2 * 0.07 * 0.07))
            }
            v += 0.4 * sin(2 * PI * 0.2 * tm / 1000.0) + rnd.nextDouble(-0.05, 0.05) + 5.0
            vals[i] = (if (inverted) -v else v).toFloat()
        }
        val accN = minutes * 60 * 50
        val accTs = LongArray(accN) { start + it * 20L }
        val acc = FloatArray(accN * 3)
        for (i in 0 until accN) {
            val shake = if (motion) 3 * sin(2 * PI * 2 * i / 50.0) else 0.0
            acc[i * 3] = (0.1 + rnd.nextDouble(-0.02, 0.02) + shake).toFloat()
            acc[i * 3 + 1] = (0.2 + rnd.nextDouble(-0.02, 0.02)).toFloat()
            acc[i * 3 + 2] = (9.8 + rnd.nextDouble(-0.02, 0.02) + shake).toFloat()
        }
        val batch = SensorBatch(
            "dev-1", start, start + minutes * 60_000L, SamplingMode.STANDARD, PpgSource.RAW_PPG,
            listOf(
                SensorStream(StreamType.PPG, 1, ts, vals),
                SensorStream(StreamType.ACCEL, 3, accTs, acc),
                SensorStream(StreamType.STEPS, 1, longArrayOf(start + 1000, start + 200_000), floatArrayOf(100f, 160f)),
            ),
        )
        return batch to trueRmssd
    }

    @Test fun codecRoundTrip() {
        val (b, _) = synthetic(50.0, 5, false)
        val bytes = BatchCodec.encode(b)
        val d = BatchCodec.decode(bytes)
        assertEquals(b.key, d.key)
        assertEquals(b.streams.size, d.streams.size)
        for ((x, y) in b.streams.zip(d.streams)) {
            assertTrue(x.timestampsMs.contentEquals(y.timestampsMs))
            val tol = if (BatchCodec.quantScale(x.type) == 0f) 0f else 0.5f / BatchCodec.quantScale(x.type) + 1e-4f
            for (i in x.values.indices) assertEquals(x.values[i], y.values[i], tol)
        }
        println("5 min @50Hz PPG + 50Hz accel: ${b.sampleCount} samples -> ${bytes.size / 1024} KB")
    }

    @Test fun chunkerRoundTrip() {
        val data = ByteArray(123_457) { (it * 31).toByte() }
        val chunks = Chunker.split(data)
        assertTrue(chunks.all { it.size <= Protocol.MAX_CHUNK_BYTES })
        val joined = Chunker.join(chunks.withIndex().associate { it.index to it.value }, chunks.size)!!
        assertTrue(data.contentEquals(joined))
        assertNull(Chunker.join(mapOf(0 to chunks[0]), chunks.size))
    }

    private fun checkAccuracy(fs: Double, inverted: Boolean) {
        val (batch, truth) = synthetic(fs, 5, inverted)
        val m = Analyzer.analyze(batch).single()
        assertNotNull(m.rmssd)
        val err = abs(m.rmssd!! - truth) / truth
        println("fs=$fs inverted=$inverted RMSSD est=%.1f true=%.1f err=%.1f%% HR=%.1f q=%.2f".format(m.rmssd, truth, err * 100, m.heartRate, m.quality))
        assertTrue("RMSSD error ${err * 100}% too high", err < 0.15)
        assertEquals(60000 / 850.0, m.heartRate!!, 3.0)
        assertEquals(Activity.STILL, m.activity)
        assertEquals(60, m.steps)
    }

    @Test fun hrvAccuracy50Hz() = checkAccuracy(50.0, false)
    @Test fun hrvAccuracy25Hz() = checkAccuracy(25.0, false)
    @Test fun hrvAccuracyInvertedPolarity() = checkAccuracy(25.0, true)

    @Test fun motionGatesBeats() {
        val (batch, _) = synthetic(50.0, 5, false, motion = true)
        val m = Analyzer.analyze(batch).single()
        assertTrue(m.motionFraction > 0.9)
        assertNull(m.rmssd)
        assertTrue(m.activity == Activity.MODERATE || m.activity == Activity.ACTIVE)
    }

    @Test fun triggers() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = ZonedDateTime.of(2026, 11, 20, 12, 51, 0, 0, zone).toInstant().toEpochMilli()
        val t = Trigger.parse("time", "09:00, 21:30")!!
        assertEquals(ZonedDateTime.of(2026, 11, 20, 21, 30, 0, 0, zone).toInstant().toEpochMilli(), Trigger.nextFire(t, now, zone))
        val i = Trigger.parse("interval", "2h@08-22")!!
        assertEquals(ZonedDateTime.of(2026, 11, 20, 14, 0, 0, 0, zone).toInstant().toEpochMilli(), Trigger.nextFire(i, now, zone))
        val th = Trigger.parse("threshold", "stress>70 for 10m") as Trigger.Threshold
        assertEquals(70.0, th.value, 0.0); assertTrue(th.greater); assertEquals(10, th.minutes)
        assertNull(Trigger.parse("interval", "5m"))
        assertNull(Trigger.parse("time", "25:00"))
    }

    @Test fun dayAnalyzer() {
        val zone = ZoneId.of("UTC")
        val pts = (0 until 14 * 24).map { h ->
            val ts = start + h * 3_600_000L
            val dow = java.time.Instant.ofEpochMilli(ts).atZone(zone).dayOfWeek
            MetricPoint(ts, 70.0, 40.0, if (dow == DayOfWeek.WEDNESDAY) 70.0 else 30.0, Activity.STILL, 1.0)
        }
        val p = DayAnalyzer.analyze(pts, zone)
        assertEquals(DayOfWeek.WEDNESDAY, p.mostStressful)
        assertTrue(p.hotspots.all { it.first == DayOfWeek.WEDNESDAY })
        val b = Stress.baseline(pts, zone, start + 14 * 86_400_000L)
        assertEquals(40.0, b.rmssd, 0.01)
    }
}

class MergeTests {
    @Test fun mergeSplitBatches() {
        val t0 = 1_700_000_100_000L
        fun b(from: Long, to: Long, key: String) = SensorBatch(key, from, to, SamplingMode.STANDARD, PpgSource.HR_BPM, listOf(
            SensorStream(StreamType.HEART_RATE, 1, LongArray(((to - from) / 1000).toInt()) { from + it * 1000L }, FloatArray(((to - from) / 1000).toInt()) { 70f })))
        val m = SensorBatch.merge(listOf(b(t0 + 120_000, t0 + 300_000, "b"), b(t0, t0 + 120_000, "a")))!!
        assertEquals(300, m.stream(StreamType.HEART_RATE)!!.size)
        assertTrue(m.stream(StreamType.HEART_RATE)!!.timestampsMs.toList().zipWithNext().all { it.first <= it.second })
    }
}
