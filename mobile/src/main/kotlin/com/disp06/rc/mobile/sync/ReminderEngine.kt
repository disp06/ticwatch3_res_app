package com.disp06.rc.mobile.sync

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.disp06.rc.mobile.PhoneApp
import com.disp06.rc.mobile.R
import com.disp06.rc.mobile.Settings
import com.disp06.rc.mobile.data.AppDb
import com.disp06.rc.mobile.data.MetricEntity
import com.disp06.rc.mobile.data.ReminderRule
import com.disp06.rc.shared.Trigger
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Reminders engine (brief §5.4).
 *  - time / interval rules → pushed to the watch and fired there by AlarmManager (works offline);
 *  - threshold rules → evaluated here after each processed batch and by the 15-min worker,
 *    then sent to the watch as an alert (plus an optional phone notification).
 */
object ReminderEngine {
    private const val COOLDOWN_MS = 60 * 60_000L
    /** Don't alert on data older than this (e.g. when a day of buffered data syncs at once). */
    private const val MAX_DATA_AGE_MS = 30 * 60_000L

    suspend fun evaluateThresholds(ctx: Context) {
        val db = AppDb.get(ctx)
        val now = System.currentTimeMillis()
        for (rule in db.rules().all()) {
            if (!rule.enabled || rule.type != "threshold") continue
            if (now < rule.snoozedUntil || now - rule.lastFired < COOLDOWN_MS) continue
            val t = Trigger.parse(rule.type, rule.triggerValue) as? Trigger.Threshold ?: continue
            val recent = db.metrics().range(now - t.minutes * 60_000L - 5 * 60_000L - MAX_DATA_AGE_MS, now)
            if (matches(recent, t, now)) fire(ctx, rule, alert = true)
        }
    }

    /** True if the latest consecutive windows covering [t.minutes] all satisfy the condition. */
    fun matches(metrics: List<MetricEntity>, t: Trigger.Threshold, now: Long): Boolean {
        val needed = ceil(t.minutes / 5.0).toInt().coerceAtLeast(1)
        val last = metrics.sortedBy { it.timestamp }.takeLast(needed)
        if (last.size < needed) return false
        if (now - last.last().timestamp > MAX_DATA_AGE_MS) return false
        // Require contiguous windows (no gaps).
        if (last.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp != 5 * 60_000L }) return false
        return last.all { m ->
            val v = when (t.metric) {
                "stress" -> m.stressScore
                "hr" -> m.heartRate
                "rmssd" -> m.rmssd
                else -> null
            } ?: return@all false
            if (t.greater) v > t.value else v < t.value
        }
    }

    suspend fun fire(ctx: Context, rule: ReminderRule, alert: Boolean) {
        val db = AppDb.get(ctx)
        db.rules().upsert(rule.copy(lastFired = System.currentTimeMillis()))
        WatchLink.notify(ctx, rule.message, if (alert) "Heads up" else "Reminder", rule.id, alert)
        db.diary().insert(manualNote(rule.message, metric = if (alert) "alert" else "reminder", tags = rule.type))
        if (Settings.get(ctx).current.phoneNotifications) phoneNotification(ctx, rule.id, rule.message)
    }

    fun phoneNotification(ctx: Context, id: Long, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, PhoneApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Research Companion")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(5000 + id.toInt(), n)
    }

    /** Snooze / Done from the watch notification. */
    suspend fun onWatchAction(ctx: Context, o: JSONObject) {
        val db = AppDb.get(ctx)
        val rule = db.rules().get(o.optLong("ruleId")) ?: return
        when (o.optString("action")) {
            "snooze" -> db.rules().upsert(rule.copy(snoozedUntil = System.currentTimeMillis() + 15 * 60_000L))
            "done" -> db.diary().insert(manualNote("Done: ${rule.message}", metric = "reminder", tags = "done,${rule.type}"))
        }
    }

    suspend fun syncSchedule(ctx: Context) = WatchLink.pushSchedule(ctx, AppDb.get(ctx).rules().all())
}

/** Periodic (15 min) evaluation + re-push of the watch schedule/config as a safety net. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        ReminderEngine.evaluateThresholds(applicationContext)
        WatchLink.requestStatus(applicationContext)
        return Result.success()
    }
}
