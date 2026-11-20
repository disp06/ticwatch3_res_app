package com.disp06.rc.mobile.sync

import android.content.Context
import android.util.Log
import com.disp06.rc.mobile.data.AppDb
import com.disp06.rc.mobile.data.DiaryEntry
import com.disp06.rc.mobile.data.MetricEntity
import com.disp06.rc.shared.BatchCodec
import com.disp06.rc.shared.SensorBatch
import com.disp06.rc.shared.dsp.Activity
import com.disp06.rc.shared.dsp.Analyzer
import com.disp06.rc.shared.dsp.MetricPoint
import com.disp06.rc.shared.dsp.Stress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.math.roundToInt

fun MetricEntity.toPoint() = MetricPoint(
    timestamp, heartRate, rmssd, stressScore,
    runCatching { Activity.valueOf(activity) }.getOrDefault(Activity.UNKNOWN), quality,
)

/**
 * Phone-side pipeline (brief §5.3): decode → merge overlapping batches → filter, peak detection,
 * IBI cleaning, HRV per 5-minute window → stress vs. personal baseline → activity → store →
 * hourly diary summaries → threshold reminders.
 */
object Processing {
    private const val TAG = "RC.Processing"
    private val lock = Mutex()
    private const val HOUR = 3_600_000L

    suspend fun processRange(ctx: Context, startMs: Long, endMs: Long) = lock.withLock {
        val db = AppDb.get(ctx)
        val w = Analyzer.WINDOW_MS
        val from = (startMs / w) * w
        val to = ((endMs + w - 1) / w) * w
        val infos = db.batches().overlapping(from, to)
        val decoded = infos.mapNotNull { info ->
            runCatching { BatchCodec.decode(db.batches().payload(info.id)) }
                .onFailure { Log.e(TAG, "Corrupt batch ${info.key}", it) }.getOrNull()
        }
        val merged = SensorBatch.merge(decoded) ?: return@withLock
        val windows = Analyzer.analyze(merged).filter { it.startMs in from until to }
        if (windows.isEmpty()) return@withLock

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val history = db.metrics().range(now - 7 * 24 * HOUR, now + HOUR).map { it.toPoint() }
        val baseline = Stress.baseline(history, zone, now)

        val rows = windows.map { m ->
            MetricEntity(
                id = db.metrics().at(m.startMs)?.id ?: 0,
                timestamp = m.startMs,
                heartRate = m.heartRate, rmssd = m.rmssd, sdnn = m.sdnn, pnn50 = m.pnn50,
                stressScore = Stress.score(m, baseline),
                activity = m.activity.name,
                steps = m.steps,
                quality = m.quality,
                motionFraction = m.motionFraction,
                source = m.source.name,
            )
        }
        db.metrics().upsert(rows)
        Log.i(TAG, "Stored ${rows.size} windows (baseline ${baseline.kind} %.1f ms)".format(baseline.rmssd))

        val hours = rows.map { (it.timestamp / HOUR) * HOUR }.distinct()
        for (h in hours) writeHourlySummary(db, h)
        ReminderEngine.evaluateThresholds(ctx)
    }

    /** One auto diary entry per metric per hour (upserted, so reprocessing never duplicates). */
    private suspend fun writeHourlySummary(db: AppDb, hourStart: Long) {
        val m = db.metrics().range(hourStart, hourStart + HOUR)
        if (m.isEmpty()) return
        val entries = mutableListOf<DiaryEntry>()
        suspend fun add(metric: String, value: Double?, note: String) {
            if (value == null) return
            val key = "h:$hourStart:$metric"
            entries += DiaryEntry(
                id = db.diary().idForKey(key) ?: 0, timestamp = hourStart, type = "auto", metric = metric,
                value = value, note = note, tags = "hourly", autoKey = key,
            )
        }
        val hr = m.mapNotNull { it.heartRate }
        add("hr", hr.takeIf { it.isNotEmpty() }?.average(), "Hourly average · range ${hr.minOrNull()?.roundToInt()}–${hr.maxOrNull()?.roundToInt()} bpm")
        val rm = m.mapNotNull { it.rmssd }
        add("hrv", rm.takeIf { it.isNotEmpty() }?.average(), "RMSSD hourly average · ${rm.size} windows")
        val st = m.mapNotNull { it.stressScore }
        add("stress", st.takeIf { it.isNotEmpty() }?.average(), "Peak ${st.maxOrNull()?.roundToInt()}")
        val steps = m.mapNotNull { it.steps }.sum()
        val dominant = m.groupingBy { it.activity }.eachCount().maxByOrNull { it.value }?.key?.lowercase() ?: "unknown"
        add("activity", steps.toDouble(), "Mostly $dominant · steps")
        db.diary().upsertAuto(entries)
    }
}
