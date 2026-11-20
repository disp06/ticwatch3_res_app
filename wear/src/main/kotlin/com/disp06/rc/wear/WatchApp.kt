package com.disp06.rc.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.disp06.rc.shared.SamplingMode
import com.disp06.rc.wear.data.HousekeepingWorker
import java.util.UUID
import java.util.concurrent.TimeUnit

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Data collection", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Stress alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "housekeeping",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HousekeepingWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    companion object {
        const val CH_SERVICE = "collection"
        const val CH_REMINDERS = "reminders"
        const val CH_ALERTS = "alerts"
    }
}

/** Small persistent settings store (SharedPreferences; no DataStore to keep the APK lean). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("rc_watch", Context.MODE_PRIVATE)

    var collecting: Boolean
        get() = p.getBoolean("collecting", false)
        set(v) = p.edit().putBoolean("collecting", v).apply()

    var mode: SamplingMode
        get() = runCatching { SamplingMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(SamplingMode.STANDARD)
        set(v) = p.edit().putString("mode", v.name).apply()

    var gyroEnabled: Boolean
        get() = p.getBoolean("gyro", true)
        set(v) = p.edit().putBoolean("gyro", v).apply()

    /** 0 = auto-detect raw PPG, -1 = heart-rate sensor only, otherwise a specific sensor type id. */
    var ppgSensorType: Int
        get() = p.getInt("ppgSensor", 0)
        set(v) = p.edit().putInt("ppgSensor", v).apply()

    var syncMinutes: Int
        get() = p.getInt("syncMinutes", 15)
        set(v) = p.edit().putInt("syncMinutes", v.coerceIn(15, 120)).apply()

    var lastUploadMs: Long
        get() = p.getLong("lastUpload", 0)
        set(v) = p.edit().putLong("lastUpload", v).apply()

    var scheduleJson: String
        get() = p.getString("schedule", "[]")!!
        set(v) = p.edit().putString("schedule", v).apply()

    val deviceId: String
        get() = p.getString("deviceId", null) ?: UUID.randomUUID().toString().take(8).also {
            p.edit().putString("deviceId", it).apply()
        }
}
